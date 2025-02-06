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
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.biometrics.AuthController
import com.android.systemui.customization.R as customR
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.DeviceEntryIconViewBinder
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryBackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.TouchHandlingViewLogger
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import com.android.systemui.log.dagger.LongPressTouchLog
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.VibratorHelper
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle

/** Includes the device entry icon. */
class DefaultDeviceEntrySection
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val authController: AuthController,
    private val windowManager: WindowManager,
    @ShadeDisplayAware private val context: Context,
    private val notificationPanelView: NotificationPanelView,
    private val featureFlags: FeatureFlags,
    private val deviceEntryIconViewModel: Lazy<DeviceEntryIconViewModel>,
    private val deviceEntryForegroundViewModel: Lazy<DeviceEntryForegroundViewModel>,
    private val deviceEntryBackgroundViewModel: Lazy<DeviceEntryBackgroundViewModel>,
    private val falsingManager: Lazy<FalsingManager>,
    private val vibratorHelper: Lazy<VibratorHelper>,
    @LongPressTouchLog private val logBuffer: LogBuffer,
    @KeyguardBlueprintLog blueprintLogBuffer: LogBuffer,
) : KeyguardSection() {
    private val blueprintLogger = Logger(blueprintLogBuffer, TAG)
    private val deviceEntryIconViewId = R.id.device_entry_icon_view
    private var disposableHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        val view =
            DeviceEntryIconView(
                    context,
                    null,
                    logger = TouchHandlingViewLogger(logBuffer = logBuffer, TAG),
                )
                .apply { id = deviceEntryIconViewId }

        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
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
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val isUdfpsSupported = deviceEntryIconViewModel.get().isUdfpsSupported.value
        blueprintLogger.d({ "isUdfpsSupported=$bool1" }) { bool1 = isUdfpsSupported }

        val scaleFactor: Float = authController.scaleFactor
        val mBottomPaddingPx =
            context.resources.getDimensionPixelSize(customR.dimen.lock_icon_margin_bottom)
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
            deviceEntryIconViewModel.get().udfpsLocation.value?.let { udfpsLocation ->
                blueprintLogger.d({
                    "udfpsLocation=$str1, scaledLocation=$str2, unusedAuthController=$str3"
                }) {
                    str1 = "$udfpsLocation"
                    str2 = "(${udfpsLocation.centerX}, ${udfpsLocation.centerY})"
                    str3 = "${authController.udfpsLocation}"
                }
                centerIcon(
                    Point(udfpsLocation.centerX.toInt(), udfpsLocation.centerY.toInt()),
                    udfpsLocation.radius,
                    constraintSet,
                )
            }
        } else {
            centerIcon(
                Point(
                    (widthPixels / 2).toInt(),
                    (heightPixels - ((mBottomPaddingPx + iconRadiusPx) * scaleFactor)).toInt(),
                ),
                iconRadiusPx * scaleFactor,
                constraintSet,
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(deviceEntryIconViewId)
        disposableHandle?.dispose()
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

        val iconId = deviceEntryIconViewId

        constraintSet.apply {
            constrainWidth(iconId, sensorRect.right - sensorRect.left)
            constrainHeight(iconId, sensorRect.bottom - sensorRect.top)
            connect(
                iconId,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                sensorRect.top,
            )
            connect(
                iconId,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                sensorRect.left,
            )
        }
    }

    companion object {
        private const val TAG = "DefaultDeviceEntrySection"
    }
}
