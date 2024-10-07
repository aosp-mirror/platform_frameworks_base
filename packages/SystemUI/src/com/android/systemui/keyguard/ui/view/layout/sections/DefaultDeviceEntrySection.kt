/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.keyguard.LockIconView
import com.android.keyguard.LockIconViewController
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.KeyguardBottomAreaRefactor
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.DeviceEntryIconViewBinder
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryBackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LongPressHandlingViewLogger
import com.android.systemui.log.dagger.LongPressTouchLog
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.statusbar.VibratorHelper
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Includes the device entry icon. */
@ExperimentalCoroutinesApi
class DefaultDeviceEntrySection
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val authController: AuthController,
    private val windowManager: WindowManager,
    private val context: Context,
    private val notificationPanelView: NotificationPanelView,
    private val featureFlags: FeatureFlags,
    private val lockIconViewController: Lazy<LockIconViewController>,
    private val deviceEntryIconViewModel: Lazy<DeviceEntryIconViewModel>,
    private val deviceEntryForegroundViewModel: Lazy<DeviceEntryForegroundViewModel>,
    private val deviceEntryBackgroundViewModel: Lazy<DeviceEntryBackgroundViewModel>,
    private val falsingManager: Lazy<FalsingManager>,
    private val vibratorHelper: Lazy<VibratorHelper>,
    @LongPressTouchLog private val logBuffer: LogBuffer,
) : KeyguardSection() {
    private val deviceEntryIconViewId = R.id.device_entry_icon_view
    private var disposableHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (
            !KeyguardBottomAreaRefactor.isEnabled &&
                !MigrateClocksToBlueprint.isEnabled &&
                !DeviceEntryUdfpsRefactor.isEnabled
        ) {
            return
        }

        notificationPanelView.findViewById<View>(R.id.lock_icon_view).let {
            notificationPanelView.removeView(it)
        }

        val view =
            if (DeviceEntryUdfpsRefactor.isEnabled) {
                DeviceEntryIconView(
                        context,
                        null,
                        logger =
                            LongPressHandlingViewLogger(
                                logBuffer = logBuffer,
                                TAG
                            )
                    )
                    .apply { id = deviceEntryIconViewId }
            } else {
                // KeyguardBottomAreaRefactor.isEnabled or MigrateClocksToBlueprint.isEnabled
                LockIconView(context, null).apply { id = R.id.lock_icon_view }
            }
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (DeviceEntryUdfpsRefactor.isEnabled) {
            constraintLayout.findViewById<DeviceEntryIconView?>(deviceEntryIconViewId)?.let {
                disposableHandle?.dispose()
                disposableHandle =
                    DeviceEntryIconViewBinder.bind(
                        applicationScope,
                        it,
                        deviceEntryIconViewModel.get(),
                        deviceEntryForegroundViewModel.get(),
                        deviceEntryBackgroundViewModel.get(),
                        falsingManager.get(),
                        vibratorHelper.get(),
                    )
            }
        } else {
            constraintLayout.findViewById<LockIconView?>(R.id.lock_icon_view)?.let {
                lockIconViewController.get().setLockIconView(it)
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val isUdfpsSupported =
            if (DeviceEntryUdfpsRefactor.isEnabled) {
                Log.d(
                    "DefaultDeviceEntrySection",
                    "isUdfpsSupported=${deviceEntryIconViewModel.get().isUdfpsSupported.value}"
                )
                deviceEntryIconViewModel.get().isUdfpsSupported.value
            } else {
                authController.isUdfpsSupported
            }
        val scaleFactor: Float = authController.scaleFactor
        val mBottomPaddingPx =
            context.resources.getDimensionPixelSize(R.dimen.lock_icon_margin_bottom)
        val bounds = windowManager.currentWindowMetrics.bounds
        var widthPixels = bounds.right.toFloat()
        if (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE)) {
            // Assumed to be initially neglected as there are no left or right insets in portrait.
            // However, on landscape, these insets need to included when calculating the midpoint.
            val insets = windowManager.currentWindowMetrics.windowInsets
            widthPixels -= (insets.systemWindowInsetLeft + insets.systemWindowInsetRight).toFloat()
        }
        val heightPixels = bounds.bottom.toFloat()
        val defaultDensity =
            DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val iconRadiusPx = (defaultDensity * 36).toInt()

        if (isUdfpsSupported) {
            if (DeviceEntryUdfpsRefactor.isEnabled) {
                deviceEntryIconViewModel.get().udfpsLocation.value?.let { udfpsLocation ->
                    Log.d(
                        "DeviceEntrySection",
                        "udfpsLocation=$udfpsLocation, " +
                            "scaledLocation=(${udfpsLocation.centerX},${udfpsLocation.centerY}), " +
                            "unusedAuthController=${authController.udfpsLocation}"
                    )
                    centerIcon(
                        Point(udfpsLocation.centerX.toInt(), udfpsLocation.centerY.toInt()),
                        udfpsLocation.radius,
                        constraintSet
                    )
                }
            } else {
                authController.udfpsLocation?.let { udfpsLocation ->
                    Log.d("DeviceEntrySection", "udfpsLocation=$udfpsLocation")
                    centerIcon(udfpsLocation, authController.udfpsRadius, constraintSet)
                }
            }
        } else {
            centerIcon(
                Point(
                    (widthPixels / 2).toInt(),
                    (heightPixels - ((mBottomPaddingPx + iconRadiusPx) * scaleFactor)).toInt()
                ),
                iconRadiusPx * scaleFactor,
                constraintSet,
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (DeviceEntryUdfpsRefactor.isEnabled) {
            constraintLayout.removeView(deviceEntryIconViewId)
            disposableHandle?.dispose()
        } else {
            constraintLayout.removeView(R.id.lock_icon_view)
        }
    }

    @VisibleForTesting
    internal fun centerIcon(center: Point, radius: Float, constraintSet: ConstraintSet) {
        val sensorRect =
            Rect().apply {
                set(
                    center.x - radius.toInt(),
                    center.y - radius.toInt(),
                    center.x + radius.toInt(),
                    center.y + radius.toInt(),
                )
            }

        val iconId =
            if (DeviceEntryUdfpsRefactor.isEnabled) {
                deviceEntryIconViewId
            } else {
                R.id.lock_icon_view
            }

        constraintSet.apply {
            constrainWidth(iconId, sensorRect.right - sensorRect.left)
            constrainHeight(iconId, sensorRect.bottom - sensorRect.top)
            connect(
                iconId,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                sensorRect.top
            )
            connect(
                iconId,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                sensorRect.left
            )
        }

        // This is only intended to be here until the KeyguardBottomAreaRefactor flag is enabled
        // Without this logic, the lock icon location changes but the KeyguardBottomAreaView is not
        // updated and visible ui layout jank occurs. This is due to AmbientIndicationContainer
        // being in NPVC and laying out prior to the KeyguardRootView.
        // Remove when both DeviceEntryUdfpsRefactor and KeyguardBottomAreaRefactor are enabled.
        if (DeviceEntryUdfpsRefactor.isEnabled && !KeyguardBottomAreaRefactor.isEnabled) {
            with(notificationPanelView) {
                val isUdfpsSupported = deviceEntryIconViewModel.get().isUdfpsSupported.value
                val bottomAreaViewRight = findViewById<View>(R.id.keyguard_bottom_area)?.right ?: 0
                findViewById<View>(R.id.ambient_indication_container)?.let {
                    val (ambientLeft, ambientTop) = it.locationOnScreen
                    if (isUdfpsSupported) {
                        // make top of ambient indication view the bottom of the lock icon
                        it.layout(
                            ambientLeft,
                            sensorRect.bottom,
                            bottomAreaViewRight - ambientLeft,
                            ambientTop + it.measuredHeight
                        )
                    } else {
                        // make bottom of ambient indication view the top of the lock icon
                        it.layout(
                            ambientLeft,
                            sensorRect.top - it.measuredHeight,
                            bottomAreaViewRight - ambientLeft,
                            sensorRect.top
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DefaultDeviceEntrySection"
    }
}
