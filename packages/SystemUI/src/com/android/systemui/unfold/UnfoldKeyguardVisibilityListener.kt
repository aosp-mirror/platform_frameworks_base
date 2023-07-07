package com.android.systemui.unfold

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.unfold.util.UnfoldKeyguardVisibilityManager
import com.android.systemui.util.kotlin.getOrNull
import java.util.Optional
import javax.inject.Inject

/**
 * Used to set the keyguard visibility state to [UnfoldKeyguardVisibilityManager].
 *
 * It is not possible to directly inject a sysui class (e.g. [KeyguardStateController]) into
 * [DeviceStateProvider], as it can't depend on google sysui directly. So,
 * [UnfoldKeyguardVisibilityManager] is provided to clients, that can set the keyguard visibility
 * accordingly.
 */
@SysUISingleton
class UnfoldKeyguardVisibilityListener
@Inject
constructor(
    keyguardStateController: KeyguardStateController,
    unfoldComponent: Optional<SysUIUnfoldComponent>,
) {

    private val unfoldKeyguardVisibilityManager =
        unfoldComponent.getOrNull()?.getUnfoldKeyguardVisibilityManager()

    private val delegate = { keyguardStateController.isVisible }

    fun init() {
        unfoldKeyguardVisibilityManager?.setKeyguardVisibleDelegate(delegate).also {
            Log.d(TAG, "setKeyguardVisibleDelegate set")
        }
    }
}

private const val TAG = "UnfoldKeyguardVisibilityListener"
