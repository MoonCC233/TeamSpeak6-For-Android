package dev.tsdroid.core

import android.content.Context
import android.content.Intent

/**
 * Resolves the host app's launcher activity without a compile-time reference to it.
 *
 * Before the shared-core split, [dev.tsdroid.service.TsConnectionService] and
 * [dev.tsdroid.service.ScreenShareService] built their "open the app" intents as
 * `Intent(this, MainActivity::class.java)`. The activity now lives in the application module, so
 * the component is looked up through the PackageManager instead. The result is the same explicit
 * launcher intent the old code produced: it carries no flags, exactly like the direct
 * class-reference intent.
 */
object AppLaunch {

    fun activityIntent(context: Context): Intent {
        val component = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.component
        return if (component != null) {
            Intent().setComponent(component)
        } else {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(context.packageName)
        }
    }
}
