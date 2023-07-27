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

package com.android.systemui.keyguard.ui.view.layout

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.LEFT
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.RIGHT
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.animation.view.LaunchableLinearLayout
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import javax.inject.Inject

/**
 * Positions elements of the lockscreen to the default position.
 *
 * This will be the most common use case for phones in portrait mode.
 */
@SysUISingleton
class ShortcutsBesideUdfpsLockscreenLayout
@Inject
constructor(
    private val authController: AuthController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val windowManager: WindowManager,
    private val context: Context,
) : LockscreenLayout {
    override val id: String = SHORTCUTS_BESIDE_UDFPS

    override fun layoutIndicationArea(rootView: KeyguardRootView) {
        val indicationArea = rootView.findViewById<View>(R.id.keyguard_indication_area) ?: return

        rootView.getConstraintSet().apply {
            constrainWidth(indicationArea.id, MATCH_PARENT)
            constrainHeight(indicationArea.id, WRAP_CONTENT)
            connect(
                indicationArea.id,
                BOTTOM,
                PARENT_ID,
                BOTTOM,
                R.dimen.keyguard_indication_margin_bottom.dp()
            )
            connect(indicationArea.id, START, PARENT_ID, START)
            connect(indicationArea.id, END, PARENT_ID, END)
            applyTo(rootView)
        }
    }

    override fun layoutLockIcon(rootView: KeyguardRootView) {
        val isUdfpsSupported = keyguardUpdateMonitor.isUdfpsSupported
        val scaleFactor: Float = authController.scaleFactor
        val mBottomPaddingPx = R.dimen.lock_icon_margin_bottom.dp()
        val mDefaultPaddingPx = R.dimen.lock_icon_padding.dp()
        val scaledPadding: Int = (mDefaultPaddingPx * scaleFactor).toInt()
        val bounds = windowManager.currentWindowMetrics.bounds
        val widthPixels = bounds.right.toFloat()
        val heightPixels = bounds.bottom.toFloat()
        val defaultDensity =
            DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val lockIconRadiusPx = (defaultDensity * 36).toInt()

        if (isUdfpsSupported) {
            authController.udfpsLocation?.let { udfpsLocation ->
                centerLockIcon(udfpsLocation, authController.udfpsRadius, scaledPadding, rootView)
            }
        } else {
            centerLockIcon(
                Point(
                    (widthPixels / 2).toInt(),
                    (heightPixels - ((mBottomPaddingPx + lockIconRadiusPx) * scaleFactor)).toInt()
                ),
                lockIconRadiusPx * scaleFactor,
                scaledPadding,
                rootView
            )
        }
    }

    @VisibleForTesting
    internal fun centerLockIcon(
        center: Point,
        radius: Float,
        drawablePadding: Int,
        rootView: KeyguardRootView,
    ) {
        val lockIconView = rootView.findViewById<View>(R.id.lock_icon_view) ?: return
        val lockIcon = lockIconView.findViewById<View>(R.id.lock_icon) ?: return
        lockIcon.setPadding(drawablePadding, drawablePadding, drawablePadding, drawablePadding)

        val sensorRect =
            Rect().apply {
                set(
                    center.x - radius.toInt(),
                    center.y - radius.toInt(),
                    center.x + radius.toInt(),
                    center.y + radius.toInt(),
                )
            }

        rootView.getConstraintSet().apply {
            constrainWidth(lockIconView.id, sensorRect.right - sensorRect.left)
            constrainHeight(lockIconView.id, sensorRect.bottom - sensorRect.top)
            connect(lockIconView.id, TOP, PARENT_ID, TOP, sensorRect.top)
            connect(lockIconView.id, START, PARENT_ID, START, sensorRect.left)
            applyTo(rootView)
        }
    }

    override fun layoutShortcuts(rootView: KeyguardRootView) {
        val leftShortcut = rootView.findViewById<View>(R.id.start_button) ?: return
        val rightShortcut = rootView.findViewById<View>(R.id.end_button) ?: return
        val lockIconView = rootView.findViewById<View>(R.id.lock_icon_view) ?: return
        val udfpsSupported = keyguardUpdateMonitor.isUdfpsSupported
        val width =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width)
        val height =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height)
        val horizontalOffsetMargin =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_horizontal_offset)
        val verticalOffsetMargin =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_vertical_offset)
        val padding =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_padding)

        if (!udfpsSupported) {
            leftShortcut.apply {
                updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginStart = horizontalOffsetMargin
                    bottomMargin = verticalOffsetMargin
                }
                setPadding(padding)
            }

            rightShortcut.apply {
                updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginEnd = horizontalOffsetMargin
                    bottomMargin = verticalOffsetMargin
                }
                setPadding(padding)
            }
        }

        rootView.getConstraintSet().apply {
            if (udfpsSupported) {
                constrainWidth(leftShortcut.id, width)
                constrainHeight(leftShortcut.id, height)
                connect(leftShortcut.id, LEFT, PARENT_ID, LEFT)
                connect(leftShortcut.id, RIGHT, lockIconView.id, LEFT)
                connect(leftShortcut.id, TOP, lockIconView.id, TOP)
                connect(leftShortcut.id, BOTTOM, lockIconView.id, BOTTOM)

                constrainWidth(rightShortcut.id, width)
                constrainHeight(rightShortcut.id, height)
                connect(rightShortcut.id, RIGHT, PARENT_ID, RIGHT)
                connect(rightShortcut.id, LEFT, lockIconView.id, RIGHT)
                connect(rightShortcut.id, TOP, lockIconView.id, TOP)
                connect(rightShortcut.id, BOTTOM, lockIconView.id, BOTTOM)
            } else {
                constrainWidth(leftShortcut.id, width)
                constrainHeight(leftShortcut.id, height)
                connect(leftShortcut.id, LEFT, PARENT_ID, LEFT)
                connect(leftShortcut.id, BOTTOM, PARENT_ID, BOTTOM)

                constrainWidth(rightShortcut.id, width)
                constrainHeight(rightShortcut.id, height)
                connect(rightShortcut.id, RIGHT, PARENT_ID, RIGHT)
                connect(rightShortcut.id, BOTTOM, PARENT_ID, BOTTOM)
            }
            applyTo(rootView)
        }
    }

    override fun layoutAmbientIndicationArea(rootView: KeyguardRootView) {
        val ambientIndicationContainer =
            rootView.findViewById<View>(R.id.ambient_indication_container) ?: return
        val lockIconView = rootView.findViewById<View>(R.id.lock_icon_view) ?: return
        val indicationArea = rootView.findViewById<View>(R.id.keyguard_indication_area) ?: return

        rootView.getConstraintSet().apply {
            constrainWidth(ambientIndicationContainer.id, MATCH_PARENT)

            if (keyguardUpdateMonitor.isUdfpsSupported) {
                //constrain below udfps and above indication area
                constrainHeight(ambientIndicationContainer.id, MATCH_CONSTRAINT)
                connect(ambientIndicationContainer.id, TOP, lockIconView.id, BOTTOM)
                connect(ambientIndicationContainer.id, BOTTOM, indicationArea.id, TOP)
                connect(ambientIndicationContainer.id, LEFT, PARENT_ID, LEFT)
                connect(ambientIndicationContainer.id, RIGHT, PARENT_ID, RIGHT)
            } else {
                //constrain above lock icon
                constrainHeight(ambientIndicationContainer.id, WRAP_CONTENT)
                connect(ambientIndicationContainer.id, BOTTOM, lockIconView.id, TOP)
                connect(ambientIndicationContainer.id, LEFT, PARENT_ID, LEFT)
                connect(ambientIndicationContainer.id, RIGHT, PARENT_ID, RIGHT)
            }
            applyTo(rootView)
        }
    }

    override fun layoutSettingsPopupMenu(rootView: KeyguardRootView) {
        val popupMenu =
            rootView.findViewById<LaunchableLinearLayout>(R.id.keyguard_settings_button) ?: return
        val icon = popupMenu.findViewById<ImageView>(R.id.icon) ?: return
        val textView = popupMenu.findViewById<TextView>(R.id.text) ?: return
        val horizontalOffsetMargin =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_horizontal_offset)

        icon.updateLayoutParams<LinearLayout.LayoutParams> {
            height =
                context
                    .resources
                    .getDimensionPixelSize(R.dimen.keyguard_settings_popup_menu_icon_height)
            width =
                context
                    .resources
                    .getDimensionPixelSize(R.dimen.keyguard_settings_popup_menu_icon_width)
            marginEnd =
                context
                    .resources
                    .getDimensionPixelSize(R.dimen.keyguard_settings_popup_menu_icon_end_margin)
        }

        textView.updateLayoutParams<LinearLayout.LayoutParams> {
            height = WRAP_CONTENT
            width = WRAP_CONTENT
        }

        popupMenu.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin =
                context.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_vertical_offset)
            marginStart = horizontalOffsetMargin
            marginEnd = horizontalOffsetMargin
        }
        popupMenu.setPadding(
            context.resources.getDimensionPixelSize(R.dimen.keyguard_settings_popup_menu_padding)
        )

        rootView.getConstraintSet().apply {
            constrainWidth(popupMenu.id, WRAP_CONTENT)
            constrainHeight(popupMenu.id, WRAP_CONTENT)
            constrainMinHeight(
                popupMenu.id,
                context.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height)
            )
            connect(popupMenu.id, LEFT, PARENT_ID, LEFT)
            connect(popupMenu.id, RIGHT, PARENT_ID, RIGHT)
            connect(popupMenu.id, BOTTOM, PARENT_ID, BOTTOM)

            applyTo(rootView)
        }
    }

    private fun Int.dp(): Int {
        return context.resources.getDimensionPixelSize(this)
    }

    private fun ConstraintLayout.getConstraintSet(): ConstraintSet =
        ConstraintSet().also {
            it.clone(this)
        }

    companion object {
        const val SHORTCUTS_BESIDE_UDFPS = "shortcutsBesideUdfps"
    }
}
