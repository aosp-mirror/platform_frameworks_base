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

package com.android.systemui.statusbar.chips.ron.demo.ui.viewmodel

import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Drawable
import com.android.systemui.CoreStartable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.chips.ron.shared.StatusBarRonChips
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.commandline.ParseableCommand
import com.android.systemui.statusbar.commandline.Type
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A view model that will emit demo RON chips (rich ongoing notification chips) from [chip] based on
 * adb commands sent by the user.
 *
 * Example adb commands:
 *
 * To show a chip with the SysUI icon and custom text and color:
 * ```
 * adb shell cmd statusbar demo-ron -p com.android.systemui -t 10min -c "\\#434343"
 * ```
 *
 * To hide the chip:
 * ```
 * adb shell cmd statusbar demo-ron --hide
 * ```
 *
 * See [DemoRonCommand] for more information on the adb command spec.
 */
@SysUISingleton
class DemoRonChipViewModel
@Inject
constructor(
    private val commandRegistry: CommandRegistry,
    private val packageManager: PackageManager,
    private val systemClock: SystemClock,
) : OngoingActivityChipViewModel, CoreStartable {
    override fun start() {
        commandRegistry.registerCommand("demo-ron") { DemoRonCommand() }
    }

    private val _chip =
        MutableStateFlow<OngoingActivityChipModel>(OngoingActivityChipModel.Hidden())
    override val chip: StateFlow<OngoingActivityChipModel> = _chip.asStateFlow()

    private inner class DemoRonCommand : ParseableCommand("demo-ron") {
        private val packageName: String? by
            param(
                longName = "packageName",
                shortName = "p",
                description = "The package name for the demo RON app",
                valueParser = Type.String,
            )

        private val text: String? by
            param(
                longName = "text",
                shortName = "t",
                description = "Text to display in the chip",
                valueParser = Type.String,
            )

        private val backgroundColor: Int? by
            param(
                longName = "color",
                shortName = "c",
                description =
                    "The color to show as the chip background color. " +
                        "You can either just write a basic color like 'red' or 'green', " +
                        "or you can include a #RRGGBB string in this format: \"\\\\#434343\".",
                valueParser = Type.Color,
            )

        private val hide by
            flag(
                longName = "hide",
                description = "Hides any existing demo RON chip",
            )

        override fun execute(pw: PrintWriter) {
            if (!StatusBarRonChips.isEnabled) {
                pw.println(
                    "Error: com.android.systemui.status_bar_ron_chips must be enabled " +
                        "before using this demo feature"
                )
                return
            }

            if (hide) {
                _chip.value = OngoingActivityChipModel.Hidden()
                return
            }

            val currentPackageName = packageName
            if (currentPackageName == null) {
                pw.println("--packageName (or -p) must be included")
                return
            }

            val appIcon = getAppIcon(currentPackageName)
            if (appIcon == null) {
                pw.println("Package $currentPackageName could not be found")
                return
            }

            val colors =
                if (backgroundColor != null) {
                    ColorsModel.Custom(backgroundColorInt = backgroundColor!!)
                } else {
                    ColorsModel.Themed
                }

            val currentText = text
            if (currentText != null) {
                _chip.value =
                    OngoingActivityChipModel.Shown.Text(
                        icon = appIcon,
                        colors = colors,
                        text = currentText,
                    )
            } else {
                _chip.value =
                    OngoingActivityChipModel.Shown.Timer(
                        icon = appIcon,
                        colors = colors,
                        startTimeMs = systemClock.elapsedRealtime(),
                        onClickListener = null,
                    )
            }
        }

        private fun getAppIcon(packageName: String): OngoingActivityChipModel.ChipIcon? {
            lateinit var iconDrawable: Drawable
            try {
                // Note: For the real implementation, we should check if applicationInfo exists
                // before fetching the icon, so that we either don't show the chip or show a good
                // backup icon in case the app info can't be found for some reason.
                iconDrawable = packageManager.getApplicationIcon(packageName)
            } catch (e: NameNotFoundException) {
                return null
            }
            return OngoingActivityChipModel.ChipIcon.FullColorAppIcon(
                Icon.Loaded(drawable = iconDrawable, contentDescription = null),
            )
        }
    }
}
