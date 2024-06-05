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
package com.android.systemui.contrast

import android.app.UiModeManager
import android.app.UiModeManager.ContrastUtils.CONTRAST_LEVEL_HIGH
import android.app.UiModeManager.ContrastUtils.CONTRAST_LEVEL_MEDIUM
import android.app.UiModeManager.ContrastUtils.CONTRAST_LEVEL_STANDARD
import android.app.UiModeManager.ContrastUtils.fromContrastLevel
import android.app.UiModeManager.ContrastUtils.toContrastLevel
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.Executor

/** Dialog to select contrast options */
class ContrastDialog(
    context: Context?,
    @Main private val mainExecutor: Executor,
    private val uiModeManager: UiModeManager,
    private val userTracker: UserTracker,
    private val secureSettings: SecureSettings,
) : SystemUIDialog(context), UiModeManager.ContrastChangeListener {

    @VisibleForTesting lateinit var contrastButtons: Map<Int, FrameLayout>
    lateinit var dialogView: View
    @VisibleForTesting var initialContrast: Float = fromContrastLevel(CONTRAST_LEVEL_STANDARD)

    public override fun onCreate(savedInstanceState: Bundle?) {
        dialogView = LayoutInflater.from(context).inflate(R.layout.contrast_dialog, null)
        setView(dialogView)

        setTitle(R.string.quick_settings_contrast_label)
        setNeutralButton(R.string.cancel) { _, _ ->
            secureSettings.putFloatForUser(
                Settings.Secure.CONTRAST_LEVEL,
                initialContrast,
                userTracker.userId
            )
            dismiss()
        }
        setPositiveButton(R.string.done) { _, _ -> dismiss() }
        super.onCreate(savedInstanceState)

        contrastButtons =
            mapOf(
                CONTRAST_LEVEL_STANDARD to requireViewById(R.id.contrast_button_standard),
                CONTRAST_LEVEL_MEDIUM to requireViewById(R.id.contrast_button_medium),
                CONTRAST_LEVEL_HIGH to requireViewById(R.id.contrast_button_high)
            )

        contrastButtons.forEach { (contrastLevel, contrastButton) ->
            contrastButton.setOnClickListener {
                val contrastValue = fromContrastLevel(contrastLevel)
                secureSettings.putFloatForUser(
                    Settings.Secure.CONTRAST_LEVEL,
                    contrastValue,
                    userTracker.userId
                )
            }
        }

        initialContrast = uiModeManager.contrast
        highlightContrast(toContrastLevel(initialContrast))
    }

    override fun start() {
        uiModeManager.addContrastChangeListener(mainExecutor, this)
    }

    override fun stop() {
        uiModeManager.removeContrastChangeListener(this)
    }

    override fun onContrastChanged(contrast: Float) {
        highlightContrast(toContrastLevel(contrast))
    }

    private fun highlightContrast(contrast: Int) {
        contrastButtons.forEach { (level, button) -> button.isSelected = level == contrast }
    }
}
