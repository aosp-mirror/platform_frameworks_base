package com.android.systemui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.android.internal.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.FlagListenable
import com.android.systemui.flags.Flags
import com.android.systemui.settings.UserTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject

@SysUISingleton
class ChooserSelector @Inject constructor(
        private val context: Context,
        private val userTracker: UserTracker,
        private val featureFlags: FeatureFlags,
        @Application private val coroutineScope: CoroutineScope,
        @Background private val bgDispatcher: CoroutineDispatcher,
) : CoreStartable {

    private val chooserComponent = ComponentName.unflattenFromString(
            context.resources.getString(R.string.config_chooserActivity))

    override fun start() {
        coroutineScope.launch {
            val listener = FlagListenable.Listener { event ->
                if (event.flagName == Flags.CHOOSER_UNBUNDLED.name) {
                    launch { updateUnbundledChooserEnabled() }
                    event.requestNoRestart()
                }
            }
            featureFlags.addListener(Flags.CHOOSER_UNBUNDLED, listener)
            updateUnbundledChooserEnabled()

            awaitCancellationAndThen { featureFlags.removeListener(listener) }
        }
    }

    private suspend fun updateUnbundledChooserEnabled() {
        setUnbundledChooserEnabled(withContext(bgDispatcher) {
            featureFlags.isEnabled(Flags.CHOOSER_UNBUNDLED)
        })
    }

    private fun setUnbundledChooserEnabled(enabled: Boolean) {
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        userTracker.userProfiles.forEach {
            try {
                context.createContextAsUser(it.userHandle, /* flags = */ 0).packageManager
                        .setComponentEnabledSetting(chooserComponent, newState, /* flags = */ 0)
            } catch (e: IllegalArgumentException) {
                Log.w(
                        "ChooserSelector",
                        "Unable to set IntentResolver enabled=$enabled for user ${it.id}",
                        e,
                )
            }
        }
    }

    suspend inline fun awaitCancellation(): Nothing = suspendCancellableCoroutine { }
    suspend inline fun awaitCancellationAndThen(block: () -> Unit): Nothing = try {
        awaitCancellation()
    } finally {
        block()
    }
}
