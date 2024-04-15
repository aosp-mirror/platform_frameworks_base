/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.display.ui.view

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.annotation.VisibleForTesting
import androidx.core.view.updatePadding
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.DialogDelegate
import com.android.systemui.statusbar.phone.SystemUIBottomSheetDialog
import javax.inject.Inject
import kotlin.math.max

/**
 * Dialog used to decide what to do with a connected display.
 *
 * [onCancelMirroring] is called **only** if mirroring didn't start, or when the dismiss button is
 * pressed.
 */
class MirroringConfirmationDialogDelegate
@VisibleForTesting
constructor(
    context: Context,
    private val showConcurrentDisplayInfo: Boolean = false,
    private val onStartMirroringClickListener: View.OnClickListener,
    private val onCancelMirroring: View.OnClickListener,
    private val navbarBottomInsetsProvider: () -> Int,
) : DialogDelegate<Dialog> {

    private lateinit var mirrorButton: TextView
    private lateinit var dismissButton: TextView
    private lateinit var dualDisplayWarning: TextView
    private lateinit var bottomSheet: View
    private var enabledPressed = false
    private val defaultDialogBottomInset =
        context.resources.getDimensionPixelSize(R.dimen.dialog_bottom_padding)

    override fun onCreate(dialog: Dialog, savedInstanceState: Bundle?) {
        dialog.setContentView(R.layout.connected_display_dialog)

        mirrorButton =
            dialog.requireViewById<TextView>(R.id.enable_display).apply {
                setOnClickListener(onStartMirroringClickListener)
                enabledPressed = true
            }
        dismissButton =
            dialog.requireViewById<TextView>(R.id.cancel).apply {
                setOnClickListener(onCancelMirroring)
            }

        dualDisplayWarning =
            dialog.requireViewById<TextView>(R.id.dual_display_warning).apply {
                visibility = if (showConcurrentDisplayInfo) View.VISIBLE else View.GONE
            }

        bottomSheet = dialog.requireViewById(R.id.cd_bottom_sheet)

        dialog.setOnDismissListener {
            if (!enabledPressed) {
                onCancelMirroring.onClick(null)
            }
        }
        setupInsets()
    }

    override fun onStart(dialog: Dialog) {
        dialog.window?.decorView?.setWindowInsetsAnimationCallback(insetsAnimationCallback)
    }

    override fun onStop(dialog: Dialog) {
        dialog.window?.decorView?.setWindowInsetsAnimationCallback(null)
    }

    private fun setupInsets(navbarInsets: Int = navbarBottomInsetsProvider()) {
        // This avoids overlap between dialog content and navigation bars.
        // we only care about the bottom inset as in all other configuration where navigations
        // are in other display sides there is no overlap with the dialog.
        bottomSheet.updatePadding(bottom = max(navbarInsets, defaultDialogBottomInset))
    }

    override fun onConfigurationChanged(dialog: Dialog, configuration: Configuration) {
        setupInsets()
    }

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

            private fun onInsetsChanged(changedTypes: Int, insets: WindowInsets) {
                val navbarType = WindowInsets.Type.navigationBars()
                if (changedTypes and navbarType != 0) {
                    setupInsets(insets.getInsets(navbarType).bottom)
                }
            }
        }

    class Factory
    @Inject
    constructor(
        @Application private val context: Context,
        private val dialogFactory: SystemUIBottomSheetDialog.Factory,
    ) {

        fun createDialog(
            showConcurrentDisplayInfo: Boolean = false,
            onStartMirroringClickListener: View.OnClickListener,
            onCancelMirroring: View.OnClickListener,
            navbarBottomInsetsProvider: () -> Int,
            @StyleRes theme: Int = R.style.Theme_SystemUI_Dialog,
        ): Dialog =
            dialogFactory.create(
                delegate =
                    MirroringConfirmationDialogDelegate(
                        context = context,
                        showConcurrentDisplayInfo = showConcurrentDisplayInfo,
                        onStartMirroringClickListener = onStartMirroringClickListener,
                        onCancelMirroring = onCancelMirroring,
                        navbarBottomInsetsProvider = navbarBottomInsetsProvider,
                    ),
                theme = theme,
            )
    }
}
