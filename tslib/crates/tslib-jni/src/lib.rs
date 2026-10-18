#![allow(non_snake_case)]

mod audio;
mod bbcode;
mod client;
mod error;
mod identity;
mod types;

use jni::sys::jint;
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, Ordering};

/// Called when the native library is loaded via `System.loadLibrary`.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: jni::JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    // Initialize Android logger so Rust log macros output to logcat
    //
    // 注意：级别用 Debug 而非 Trace —— `jni` crate 在 Trace 级会为每次 JNI 调用打印
    // 多行日志（每调用约 5-10 行），实测 20 秒可产生 7000+ 行，淹没排查所需的日志。
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("tslib-jni"),
    );

    // 安装 panic hook：Android 上 app 进程的 stderr 指向 /dev/null，Rust 默认 panic
    // 输出（文件:行号 + 消息）会丢失，JNI 边界上的 panic 只表现为 SIGABRT 原生崩溃。
    // 这里把 panic 消息写入 logcat（tag: tslib-jni），便于定位崩溃点。
    std::panic::set_hook(Box::new(|info| {
        log::error!(
            "Rust panic on thread {:?}: {info}",
            std::thread::current().name()
        );
    }));

    // Bridge tracing (used by tslib-core) → log (used by android_logger)
    // This makes all tracing::info!() etc. from the core library visible in logcat
    let _ = tracing_log::LogTracer::init();

    // 尝试初始化 ndk-context（JVM + Application Context）：
    // hickory-resolver（DNS 解析）与 cpal（音频）在 Android 上通过
    // ndk_context::android_context() 获取 JVM/Context 以调用 Java 平台 API
    // （例如用 ConnectivityManager 读取 DNS 服务器列表）。
    // 若未初始化，首次连接服务器（DNS 解析）时会在 Rust 侧 panic：
    // "android context was not initialized"（表现为 SIGABRT 原生崩溃）。
    //
    // 注意：本库由 TsDroidApp 的类静态初始化加载，早于 Application 注册，
    // 此时 currentApplication() 尚为 null；这里只做尽力尝试，
    // 真正的初始化在 nativeCreate / nativeWaitConnected 入口重试。
    match vm.get_env() {
        Ok(mut env) => try_init_ndk_context(&mut env),
        Err(e) => log::warn!("ndk-context 初始化延迟（无法获取 JNIEnv）：{e}"),
    }

    log::info!("tslib-jni native library loaded (with tracing bridge)");
    jni::sys::JNI_VERSION_1_6
}

/// ndk-context 是否已成功初始化。
static NDK_CONTEXT_READY: AtomicBool = AtomicBool::new(false);

/// 尝试初始化 `ndk-context`（JVM + Application Context）；可重复调用，
/// 成功一次之后即为空操作。
///
/// 必须在 hickory-resolver 首次 DNS 解析前完成。JNI_OnLoad 时机往往过早
/// （本库在 Application 注册前由类静态初始化加载），因此还需要在
/// nativeCreate / nativeWaitConnected 等连接入口重试。
///
/// Context 通过 `android.app.ActivityThread#currentApplication()` 获取。
/// 传给 ndk-context 的 Context 必须是**存活至进程结束的全局引用**——
/// ndk-context 内部只保存裸指针，故这里有意不回收（进程级泄漏）。
pub(crate) fn try_init_ndk_context(env: &mut JNIEnv) {
    if NDK_CONTEXT_READY.load(Ordering::Acquire) {
        return;
    }

    // ActivityThread.currentApplication() -> Application（本进程全局对象）
    let application = match env
        .call_static_method(
            "android/app/ActivityThread",
            "currentApplication",
            "()Landroid/app/Application;",
            &[],
        )
        .and_then(|v| v.l())
    {
        Ok(app) => app,
        Err(e) => {
            log::error!("ndk-context 初始化失败（currentApplication 调用异常）：{e}");
            return;
        }
    };

    if application.is_null() {
        // Application 尚未注册：本库被应用类的静态初始化提前加载。
        // 返回并在后续 JNI 入口重试（nativeCreate / nativeWaitConnected）。
        log::warn!("ndk-context 初始化推迟：currentApplication() 暂为 null（Application 尚未注册）");
        return;
    }

    let context_ref = match env.new_global_ref(application) {
        Ok(r) => r,
        Err(e) => {
            log::error!("ndk-context 初始化失败（创建全局引用异常）：{e}");
            return;
        }
    };

    let vm_ptr = match env.get_java_vm() {
        Ok(vm) => vm.get_java_vm_pointer() as *mut std::ffi::c_void,
        Err(e) => {
            log::error!("ndk-context 初始化失败（获取 JavaVM 异常）：{e}");
            return;
        }
    };
    let context_ptr = context_ref.as_obj().as_raw() as *mut std::ffi::c_void;
    // 全局引用交由进程生命周期管理（不能释放，否则 ndk-context 悬垂）
    std::mem::forget(context_ref);
    unsafe { ndk_context::initialize_android_context(vm_ptr, context_ptr) };
    NDK_CONTEXT_READY.store(true, Ordering::Release);
    log::info!("ndk-context 初始化完成（JVM + Application Context）");
}

/// Helper to get a `String` from a `JString`, returning `None` on null.
pub(crate) fn get_string(env: &mut JNIEnv, s: &jni::objects::JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(|s| s.into())
}

/// Helper to get a required `String` from a `JString`.
/// Throws `TsLibException` if null.
pub(crate) fn require_string(env: &mut JNIEnv, s: &jni::objects::JString) -> Result<String, ()> {
    match get_string(env, s) {
        Some(s) => Ok(s),
        None => {
            error::throw_tslib_exception(env, "String argument must not be null");
            Err(())
        }
    }
}
