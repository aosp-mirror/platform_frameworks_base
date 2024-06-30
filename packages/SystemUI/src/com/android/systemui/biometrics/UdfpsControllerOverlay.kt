/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics

import android.annotation.SuppressLint
import android.annotation.UiThread
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_OTHER
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR
import android.hardware.biometrics.BiometricRequestConstants.RequestReason
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback
import android.os.Build
import android.os.RemoteException
import android.os.Trace
import android.provider.Settings
import android.util.Log
import android.util.RotationUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener
import androidx.annotation.LayoutRes
import androidx.annotation.VisibleForTesting
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.biometrics.ui.binder.UdfpsTouchOverlayBinder
import com.android.systemui.biometrics.ui.view.UdfpsTouchOverlay
import com.android.systemui.biometrics.ui.viewmodel.DefaultUdfpsTouchOverlayViewModel
import com.android.systemui.biometrics.ui.viewmodel.DeviceEntryUdfpsTouchOverlayViewModel
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "UdfpsControllerOverlay"

@VisibleForTesting const val SETTING_REMOVE_ENROLLMENT_UI = "udfps_overlay_remove_enrollment_ui"

/**
 * Keeps track of the overlay state and UI resources associated with a single FingerprintService
 * request. This state can persist across configuration changes via the [show] and [hide] methods.
 */
@ExperimentalCoroutinesApi
@UiThread
class UdfpsControllerOverlay
@JvmOverloads
constructor(
    private val context: Context,
    private val inflater: LayoutInflater,
    private val windowManager: WindowManager,
    private val accessibilityManager: AccessibilityManager,
    private val statusBarStateController: StatusBarStateController,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val dialogManager: SystemUIDialogManager,
    private val dumpManager: DumpManager,
    private val transitionController: LockscreenShadeTransitionController,
    private val configurationController: ConfigurationController,
    private val keyguardStateController: KeyguardStateController,
    private val unlockedScreenOffAnimationController: UnlockedScreenOffAnimationController,
    private var udfpsDisplayModeProvider: UdfpsDisplayModeProvider,
    val requestId: Long,
    @RequestReason val requestReason: Int,
    private val controllerCallback: IUdfpsOverlayControllerCallback,
    private val onTouch: (View, MotionEvent, Boolean) -> Boolean,
    private val activityTransitionAnimator: ActivityTransitionAnimator,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    private val isDebuggable: Boolean = Build.IS_DEBUGGABLE,
    private val udfpsKeyguardAccessibilityDelegate: UdfpsKeyguardAccessibilityDelegate,
    private val transitionInteractor: KeyguardTransitionInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val deviceEntryUdfpsTouchOverlayViewModel: Lazy<DeviceEntryUdfpsTouchOverlayViewModel>,
    private val defaultUdfpsTouchOverlayViewModel: Lazy<DefaultUdfpsTouchOverlayViewModel>,
    private val shadeInteractor: ShadeInteractor,
    private val udfpsOverlayInteractor: UdfpsOverlayInteractor,
    private val powerInteractor: PowerInteractor,
    @Application private val scope: CoroutineScope,
) {
    private val currentStateUpdatedToOffAodOrDozing: Flow<Unit> =
        transitionInteractor.currentKeyguardState
            .filter {
                it == KeyguardState.OFF || it == KeyguardState.AOD || it == KeyguardState.DOZING
            }
            .map {} // map to Unit
    private var listenForCurrentKeyguardState: Job? = null
    private var addViewRunnable: Runnable? = null
    private var overlayViewLegacy: UdfpsView? = null
        private set

    private var overlayTouchView: UdfpsTouchOverlay? = null

    /**
     * Get the current UDFPS overlay touch view which is a different View depending on whether the
     * DeviceEntryUdfpsRefactor flag is enabled or not.
     *
     * @return The view, when [isShowing], else null
     */
    fun getTouchOverlay(): View? {
        return if (DeviceEntryUdfpsRefactor.isEnabled) {
            overlayTouchView
        } else {
            overlayViewLegacy
        }
    }

    private var overlayParams: UdfpsOverlayParams = UdfpsOverlayParams()
    private var sensorBounds: Rect = Rect()

    private var overlayTouchListener: TouchExplorationStateChangeListener? = null

    private val coreLayoutParams =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                0 /* flags set in computeLayoutParams() */,
                PixelFormat.TRANSLUCENT
            )
            .apply {
                title = TAG
                fitInsetsTypes = 0
                gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                flags =
                    (Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS or
                        WindowManager.LayoutParams.FLAG_SPLIT_TOUCH)
                privateFlags =
                    WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                        WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION
                // Avoid announcing window title.
                accessibilityTitle = " "
                inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
            }

    /** If the overlay is currently showing. */
    val isShowing: Boolean
        get() = getTouchOverlay() != null

    /** Opposite of [isShowing]. */
    val isHiding: Boolean
        get() = getTouchOverlay() == null

    /** The animation controller if the overlay [isShowing]. */
    val animationViewController: UdfpsAnimationViewController<*>?
        get() = overlayViewLegacy?.animationViewController

    private var touchExplorationEnabled = false

    private fun shouldRemoveEnrollmentUi(): Boolean {
        if (isDebuggable) {
            return Settings.Global.getInt(
                context.contentResolver,
                SETTING_REMOVE_ENROLLMENT_UI,
                0 /* def */
            ) != 0
        }
        return false
    }

    /** Show the overlay or return false and do nothing if it is already showing. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(controller: UdfpsController, params: UdfpsOverlayParams): Boolean {
        if (getTouchOverlay() == null) {
            overlayParams = params
            sensorBounds = Rect(params.sensorBounds)
            try {
                if (DeviceEntryUdfpsRefactor.isEnabled) {
                    overlayTouchView =
                        (inflater.inflate(R.layout.udfps_touch_overlay, null, false)
                                as UdfpsTouchOverlay)
                            .apply {
                                // This view overlaps the sensor area
                                // prevent it from being selectable during a11y
                                if (requestReason.isImportantForAccessibility()) {
                                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                }

                                addViewNowOrLater(this, null)
                                when (requestReason) {
                                    REASON_AUTH_KEYGUARD ->
                                        UdfpsTouchOverlayBinder.bind(
                                            view = this,
                                            viewModel = deviceEntryUdfpsTouchOverlayViewModel.get(),
                                            udfpsOverlayInteractor = udfpsOverlayInteractor,
                                        )
                                    else ->
                                        UdfpsTouchOverlayBinder.bind(
                                            view = this,
                                            viewModel = defaultUdfpsTouchOverlayViewModel.get(),
                                            udfpsOverlayInteractor = udfpsOverlayInteractor,
                                        )
                                }
                            }
                } else {
                    overlayViewLegacy =
                        (inflater.inflate(R.layout.udfps_view, null, false) as UdfpsView).apply {
                            overlayParams = params
                            setUdfpsDisplayModeProvider(udfpsDisplayModeProvider)
                            val animation = inflateUdfpsAnimation(this, controller)
                            if (animation != null) {
                                animation.init()
                                animationViewController = animation
                            }
                            // This view overlaps the sensor area
                            // prevent it from being selectable during a11y
                            if (requestReason.isImportantForAccessibility()) {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }

                            addViewNowOrLater(this, animation)
                            sensorRect = sensorBounds
                        }
                }
                getTouchOverlay()?.apply {
                    touchExplorationEnabled = accessibilityManager.isTouchExplorationEnabled
                    overlayTouchListener = TouchExplorationStateChangeListener {
                        if (accessibilityManager.isTouchExplorationEnabled) {
                            setOnHoverListener { v, event -> onTouch(v, event, true) }
                            setOnTouchListener(null)
                            touchExplorationEnabled = true
                        } else {
                            setOnHoverListener(null)
                            setOnTouchListener { v, event -> onTouch(v, event, true) }
                            touchExplorationEnabled = false
                        }
                    }
                    accessibilityManager.addTouchExplorationStateChangeListener(
                        overlayTouchListener!!
                    )
                    overlayTouchListener?.onTouchExplorationStateChanged(true)
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "showUdfpsOverlay | failed to add window", e)
            }
            return true
        }

        Log.v(TAG, "showUdfpsOverlay | the overlay is already showing")
        return false
    }

    private fun addViewNowOrLater(view: View, animation: UdfpsAnimationViewController<*>?) {
        addViewRunnable =
            kotlinx.coroutines.Runnable {
                Trace.setCounter("UdfpsAddView", 1)
                windowManager.addView(view, coreLayoutParams.updateDimensions(animation))
            }
        if (powerInteractor.detailedWakefulness.value.isAwake()) {
            // Device is awake, so we add the view immediately.
            addViewIfPending()
        } else {
            listenForCurrentKeyguardState?.cancel()
            listenForCurrentKeyguardState =
                scope.launch { currentStateUpdatedToOffAodOrDozing.collect { addViewIfPending() } }
        }
    }

    private fun addViewIfPending() {
        addViewRunnable?.let {
            listenForCurrentKeyguardState?.cancel()
            it.run()
        }
        addViewRunnable = null
    }

    fun updateOverlayParams(updatedOverlayParams: UdfpsOverlayParams) {
        DeviceEntryUdfpsRefactor.isUnexpectedlyInLegacyMode()
        overlayParams = updatedOverlayParams
        sensorBounds = updatedOverlayParams.sensorBounds
        getTouchOverlay()?.let {
            if (addViewRunnable == null) {
                // Only updateViewLayout if there's no pending view to add to WM.
                // If there is a pending view, that means the view hasn't been added yet so there's
                // no need to update any layouts. Instead the correct params will be used when the
                // view is eventually added.
                windowManager.updateViewLayout(it, coreLayoutParams.updateDimensions(null))
            }
        }
    }

    fun inflateUdfpsAnimation(
        view: UdfpsView,
        controller: UdfpsController
    ): UdfpsAnimationViewController<*>? {
        DeviceEntryUdfpsRefactor.assertInLegacyMode()

        val isEnrollment =
            when (requestReason) {
                REASON_ENROLL_FIND_SENSOR,
                REASON_ENROLL_ENROLLING -> true
                else -> false
            }

        val filteredRequestReason =
            if (isEnrollment && shouldRemoveEnrollmentUi()) {
                REASON_AUTH_OTHER
            } else {
                requestReason
            }

        return when (filteredRequestReason) {
            REASON_ENROLL_FIND_SENSOR,
            REASON_ENROLL_ENROLLING -> {
                // Enroll udfps UI is handled by settings, so use empty view here
                UdfpsFpmEmptyViewController(
                    view.addUdfpsView(R.layout.udfps_fpm_empty_view) {
                        updateAccessibilityViewLocation(sensorBounds)
                    },
                    statusBarStateController,
                    shadeInteractor,
                    dialogManager,
                    dumpManager,
                    udfpsOverlayInteractor,
                )
            }
            REASON_AUTH_KEYGUARD -> {
                UdfpsKeyguardViewControllerLegacy(
                    view.addUdfpsView(R.layout.udfps_keyguard_view_legacy) {
                        updateSensorLocation(sensorBounds)
                    },
                    statusBarStateController,
                    statusBarKeyguardViewManager,
                    keyguardUpdateMonitor,
                    dumpManager,
                    transitionController,
                    configurationController,
                    keyguardStateController,
                    unlockedScreenOffAnimationController,
                    dialogManager,
                    controller,
                    activityTransitionAnimator,
                    primaryBouncerInteractor,
                    alternateBouncerInteractor,
                    udfpsKeyguardAccessibilityDelegate,
                    selectedUserInteractor,
                    transitionInteractor,
                    shadeInteractor,
                    udfpsOverlayInteractor,
                )
            }
            REASON_AUTH_BP -> {
                // note: empty controller, currently shows no visual affordance
                UdfpsBpViewController(
                    view.addUdfpsView(R.layout.udfps_bp_view),
                    statusBarStateController,
                    shadeInteractor,
                    dialogManager,
                    dumpManager,
                    udfpsOverlayInteractor,
                )
            }
            REASON_AUTH_OTHER,
            REASON_AUTH_SETTINGS -> {
                UdfpsFpmEmptyViewController(
                    view.addUdfpsView(R.layout.udfps_fpm_empty_view),
                    statusBarStateController,
                    shadeInteractor,
                    dialogManager,
                    dumpManager,
                    udfpsOverlayInteractor,
                )
            }
            else -> {
                Log.e(TAG, "Animation for reason $requestReason not supported yet")
                null
            }
        }
    }

    /** Hide the overlay or return false and do nothing if it is already hidden. */
    fun hide(): Boolean {
        val wasShowing = isShowing

        overlayViewLegacy?.apply {
            if (isDisplayConfigured) {
                unconfigureDisplay()
            }
            animationViewController = null
        }
        if (DeviceEntryUdfpsRefactor.isEnabled) {
            udfpsDisplayModeProvider.disable(null)
        }
        getTouchOverlay()?.apply {
            if (this.parent != null) {
                windowManager.removeView(this)
            }
            Trace.setCounter("UdfpsAddView", 0)
            setOnTouchListener(null)
            setOnHoverListener(null)
            overlayTouchListener?.let {
                accessibilityManager.removeTouchExplorationStateChangeListener(it)
            }
        }

        overlayViewLegacy = null
        overlayTouchView = null
        overlayTouchListener = null
        listenForCurrentKeyguardState?.cancel()

        return wasShowing
    }

    /** Cancel this request. */
    fun cancel() {
        try {
            controllerCallback.onUserCanceled()
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception", e)
        }
    }

    /** Checks if the id is relevant for this overlay. */
    fun matchesRequestId(id: Long): Boolean = requestId == -1L || requestId == id

    private fun WindowManager.LayoutParams.updateDimensions(
        animation: UdfpsAnimationViewController<*>?
    ): WindowManager.LayoutParams {
        val paddingX = animation?.paddingX ?: 0
        val paddingY = animation?.paddingY ?: 0

        val isEnrollment =
            when (requestReason) {
                REASON_ENROLL_FIND_SENSOR,
                REASON_ENROLL_ENROLLING -> true
                else -> false
            }

        // Use expanded overlay unless touchExploration enabled
        var rotatedBounds =
            if (accessibilityManager.isTouchExplorationEnabled && isEnrollment) {
                Rect(overlayParams.sensorBounds)
            } else {
                Rect(0, 0, overlayParams.naturalDisplayWidth, overlayParams.naturalDisplayHeight)
            }

        val rot = overlayParams.rotation
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            if (!shouldRotate(animation)) {
                Log.v(
                    TAG,
                    "Skip rotating UDFPS bounds " +
                        Surface.rotationToString(rot) +
                        " animation=$animation" +
                        " isGoingToSleep=${keyguardUpdateMonitor.isGoingToSleep}" +
                        " isOccluded=${keyguardStateController.isOccluded}"
                )
            } else {
                Log.v(TAG, "Rotate UDFPS bounds " + Surface.rotationToString(rot))
                RotationUtils.rotateBounds(
                    rotatedBounds,
                    overlayParams.naturalDisplayWidth,
                    overlayParams.naturalDisplayHeight,
                    rot
                )

                RotationUtils.rotateBounds(
                    sensorBounds,
                    overlayParams.naturalDisplayWidth,
                    overlayParams.naturalDisplayHeight,
                    rot
                )
            }
        }

        x = rotatedBounds.left - paddingX
        y = rotatedBounds.top - paddingY
        height = rotatedBounds.height() + 2 * paddingX
        width = rotatedBounds.width() + 2 * paddingY

        return this
    }

    private fun shouldRotate(animation: UdfpsAnimationViewController<*>?): Boolean {
        val keyguardNotShowing =
            if (DeviceEntryUdfpsRefactor.isEnabled) {
                !keyguardStateController.isShowing
            } else {
                animation !is UdfpsKeyguardViewControllerLegacy
            }

        if (keyguardNotShowing) {
            // always rotate view if we're not on the keyguard
            return true
        }

        // on the keyguard, make sure we don't rotate if we're going to sleep or not occluded
        return !(keyguardUpdateMonitor.isGoingToSleep || !keyguardStateController.isOccluded)
    }

    private inline fun <reified T : View> UdfpsView.addUdfpsView(
        @LayoutRes id: Int,
        init: T.() -> Unit = {}
    ): T {
        val subView = inflater.inflate(id, null) as T
        addView(subView)
        subView.init()
        return subView
    }
}

@RequestReason
private fun Int.isImportantForAccessibility() =
    this == REASON_ENROLL_FIND_SENSOR || this == REASON_ENROLL_ENROLLING || this == REASON_AUTH_BP
