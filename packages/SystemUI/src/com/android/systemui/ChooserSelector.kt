package com.android.systemui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.FlagListenable
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@SysUISingleton
class ChooserSelector @Inject constructor(
        context: Context,
        private val featureFlags: FeatureFlags,
        @Application private val coroutineScope: CoroutineScope,
        @Background private val bgDispatcher: CoroutineDispatcher
) : CoreStartable(context) {

    private val packageManager = context.packageManager
    private val chooserComponent = ComponentName.unflattenFromString(
            context.resources.getString(ChooserSelectorResourceHelper.CONFIG_CHOOSER_ACTIVITY))

    override fun start() {
        coroutineScope.launch {
            val listener = FlagListenable.Listener { event ->
                if (event.flagId == Flags.CHOOSER_UNBUNDLED.id) {
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
        packageManager.setComponentEnabledSetting(chooserComponent, newState, /* flags = */ 0)
    }

    suspend inline fun awaitCancellation(): Nothing = suspendCancellableCoroutine { }
    suspend inline fun awaitCancellationAndThen(block: () -> Unit): Nothing = try {
        awaitCancellation()
    } finally {
        block()
    }
}
