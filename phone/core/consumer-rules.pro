# Keep all tslib JNI classes (native methods + classes created from native code)
-keep class dev.tslib.** { *; }

# WebRTC's libjingle_peerconnection_so resolves org.webrtc classes and members
# from JNI_OnLoad by name. The upstream AAR ships no consumer rules, so an R8
# build that renames these classes aborts with
# "JNI DETECTED ERROR ... java_class == null" on the first peer-connection use.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# WebRTC's JNI bootstrap also resolves the JNI Zero runtime by name.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
