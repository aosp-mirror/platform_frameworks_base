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
 */
package com.android.systemui.statusbar.phone

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsets.Type.InsetsType
import android.view.WindowInsetsAnimation
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
import android.view.WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener

/** A dialog shown as a bottom sheet. */
open class SystemUIBottomSheetDialog(
    context: Context,
    private val configurationController: ConfigurationController? = null,
    theme: Int = R.style.Theme_SystemUI_Dialog
) : Dialog(context, theme) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        setupEdgeToEdge()
        setCanceledOnTouchOutside(true)
    }

    private fun setupWindow() {
        window?.apply {
            setType(TYPE_STATUS_BAR_SUB_PANEL)
            addPrivateFlags(SYSTEM_FLAG_SHOW_FOR_ALL_USERS or PRIVATE_FLAG_NO_MOVE_ANIMATION)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(0, 0, 0, 0)
            attributes =
                attributes.apply {
                    fitInsetsSides = 0
                    horizontalMargin = 0f
                }
        }
    }

    private fun setupEdgeToEdge() {
        val edgeToEdgeHorizontally =
            context.resources.getBoolean(R.bool.config_edgeToEdgeBottomSheetDialog)
        val width = if (edgeToEdgeHorizontally) MATCH_PARENT else WRAP_CONTENT
        val height = WRAP_CONTENT
        window?.setLayout(width, height)
    }

    override fun onStart() {
        super.onStart()
        configurationController?.addCallback(onConfigChanged)
        window?.decorView?.setWindowInsetsAnimationCallback(insetsAnimationCallback)
    }

    override fun onStop() {
        super.onStop()
        configurationController?.removeCallback(onConfigChanged)
        window?.decorView?.setWindowInsetsAnimationCallback(null)
    }

    /** Called after any insets change. */
    open fun onInsetsChanged(@InsetsType changedTypes: Int, insets: WindowInsets) {}

    /** Can be overridden by subclasses to receive config changed events. */
    open fun onConfigurationChanged() {}

    private val insetsAnimationCallback =
        object : WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {

            private var lastInsets: WindowInsets? = null

            override fun onEnd(animation: WindowInsetsAnimation) {
                lastInsets?.let { onInsetsChanged(animation.typeMask, it) }
            }

            override fun onProgress(
                insets: WindowInsets,
                animations: MutableList<WindowInsetsAnimation>,
            ): WindowInsets {
                lastInsets = insets
                onInsetsChanged(changedTypes = allAnimationMasks(animations), insets)
                return insets
            }

            private fun allAnimationMasks(animations: List<WindowInsetsAnimation>): Int =
                animations.fold(0) { acc: Int, it -> acc or it.typeMask }
        }

    private val onConfigChanged =
        object : ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                super.onConfigChanged(newConfig)
                setupEdgeToEdge()
                onConfigurationChanged()
            }
        }
}
