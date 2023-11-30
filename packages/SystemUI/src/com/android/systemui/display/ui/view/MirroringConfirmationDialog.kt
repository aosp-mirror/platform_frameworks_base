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
package com.android.systemui.display.ui.view

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.updatePadding
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIBottomSheetDialog
import com.android.systemui.statusbar.policy.ConfigurationController
import kotlin.math.max

/**
 * Dialog used to decide what to do with a connected display.
 *
 * [onCancelMirroring] is called **only** if mirroring didn't start, or when the dismiss button is
 * pressed.
 */
class MirroringConfirmationDialog(
    context: Context,
    private val onStartMirroringClickListener: View.OnClickListener,
    private val onCancelMirroring: View.OnClickListener,
    private val navbarBottomInsetsProvider: () -> Int,
    configurationController: ConfigurationController? = null,
    private val showConcurrentDisplayInfo: Boolean = false,
    theme: Int = R.style.Theme_SystemUI_Dialog,
) : SystemUIBottomSheetDialog(context, configurationController, theme) {

    private lateinit var mirrorButton: TextView
    private lateinit var dismissButton: TextView
    private lateinit var dualDisplayWarning: TextView
    private var enabledPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.connected_display_dialog)

        mirrorButton =
            requireViewById<TextView>(R.id.enable_display).apply {
                setOnClickListener(onStartMirroringClickListener)
                enabledPressed = true
            }
        dismissButton =
            requireViewById<TextView>(R.id.cancel).apply { setOnClickListener(onCancelMirroring) }

        dualDisplayWarning =
            requireViewById<TextView>(R.id.dual_display_warning).apply {
                visibility = if (showConcurrentDisplayInfo) View.VISIBLE else View.GONE
            }

        setOnDismissListener {
            if (!enabledPressed) {
                onCancelMirroring.onClick(null)
            }
        }
        setupInsets()
    }

    private fun setupInsets() {
        // This avoids overlap between dialog content and navigation bars.
        requireViewById<View>(R.id.cd_bottom_sheet).apply {
            val navbarInsets = navbarBottomInsetsProvider()
            val defaultDialogBottomInset =
                context.resources.getDimensionPixelSize(R.dimen.dialog_bottom_padding)
            // we only care about the bottom inset as in all other configuration where navigations
            // are in other display sides there is no overlap with the dialog.
            updatePadding(bottom = max(navbarInsets, defaultDialogBottomInset))
        }
    }

    override fun onConfigurationChanged() {
        super.onConfigurationChanged()
        setupInsets()
    }
}
