/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.ui.compose.section

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.systemui.biometrics.AuthController
import com.android.systemui.communal.ui.binder.CommunalLockIconViewBinder
import com.android.systemui.communal.ui.viewmodel.CommunalLockIconViewModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.ui.composable.blueprint.BlueprintAlignmentLines
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LongPressHandlingViewLogger
import com.android.systemui.log.dagger.LongPressTouchLog
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class CommunalLockSection
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val windowManager: WindowManager,
    private val authController: AuthController,
    private val viewModel: Lazy<CommunalLockIconViewModel>,
    private val falsingManager: Lazy<FalsingManager>,
    private val vibratorHelper: Lazy<VibratorHelper>,
    private val featureFlags: FeatureFlagsClassic,
    @LongPressTouchLog private val logBuffer: LogBuffer,
) {
    @Composable
    fun ContentScope.LockIcon(modifier: Modifier = Modifier) {
        val context = LocalContext.current

        AndroidView(
            factory = { context ->
                DeviceEntryIconView(
                        context,
                        null,
                        logger = LongPressHandlingViewLogger(logBuffer, tag = TAG),
                    )
                    .apply {
                        id = R.id.device_entry_icon_view
                        CommunalLockIconViewBinder.bind(
                            applicationScope,
                            this,
                            viewModel.get(),
                            falsingManager.get(),
                            vibratorHelper.get(),
                        )
                    }
            },
            modifier =
                modifier.element(LockIconElementKey).layout { measurable, _ ->
                    val lockIconBounds = lockIconBounds(context)
                    val placeable =
                        measurable.measure(
                            Constraints.fixed(
                                width = lockIconBounds.width,
                                height = lockIconBounds.height,
                            )
                        )
                    layout(
                        width = placeable.width,
                        height = placeable.height,
                        alignmentLines =
                            mapOf(
                                BlueprintAlignmentLines.LockIcon.Left to lockIconBounds.left,
                                BlueprintAlignmentLines.LockIcon.Top to lockIconBounds.top,
                                BlueprintAlignmentLines.LockIcon.Right to lockIconBounds.right,
                                BlueprintAlignmentLines.LockIcon.Bottom to lockIconBounds.bottom,
                            ),
                    ) {
                        placeable.place(0, 0)
                    }
                },
        )
    }

    /** Returns the bounds of the lock icon, in window view coordinates. */
    private fun lockIconBounds(context: Context): IntRect {
        val windowViewBounds = windowManager.currentWindowMetrics.bounds
        var widthPx = windowViewBounds.right.toFloat()
        if (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE)) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            // Assumed to be initially neglected as there are no left or right insets in portrait.
            // However, on landscape, these insets need to included when calculating the midpoint.
            @Suppress("DEPRECATION")
            widthPx -= (insets.systemWindowInsetLeft + insets.systemWindowInsetRight).toFloat()
        }
        val defaultDensity =
            DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val lockIconRadiusPx = (defaultDensity * 36).toInt()

        val scaleFactor = authController.scaleFactor
        val bottomPaddingPx =
            context.resources.getDimensionPixelSize(
                com.android.systemui.customization.R.dimen.lock_icon_margin_bottom
            )
        val heightPx = windowViewBounds.bottom.toFloat()
        val (center, radius) =
            Pair(
                IntOffset(
                    x = (widthPx / 2).toInt(),
                    y = (heightPx - ((bottomPaddingPx + lockIconRadiusPx) * scaleFactor)).toInt(),
                ),
                (lockIconRadiusPx * scaleFactor).toInt(),
            )

        return IntRect(center, radius)
    }

    companion object {
        private const val TAG = "CommunalLockSection"
    }
}

private val LockIconElementKey = ElementKey("CommunalLockIcon")
