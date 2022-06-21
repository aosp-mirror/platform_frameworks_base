/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.testing.screenshot

/** The specification of a device display to be used in a screenshot test. */
data class DisplaySpec(
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/** The specification of a screenshot diff test. */
class ScreenshotTestSpec(
    val display: DisplaySpec,
    val isDarkTheme: Boolean = false,
    val isLandscape: Boolean = false,
) {
    companion object {
        /**
         * Return a list of [ScreenshotTestSpec] for each of the [displays].
         *
         * If [isDarkTheme] is null, this will create a spec for both light and dark themes, for
         * each of the orientation.
         *
         * If [isLandscape] is null, this will create a spec for both portrait and landscape, for
         * each of the light/dark themes.
         */
        fun forDisplays(
            vararg displays: DisplaySpec,
            isDarkTheme: Boolean? = null,
            isLandscape: Boolean? = null,
        ): List<ScreenshotTestSpec> {
            return displays.flatMap { display ->
                buildList {
                    fun addDisplay(isLandscape: Boolean) {
                        if (isDarkTheme != true) {
                            add(ScreenshotTestSpec(display, isDarkTheme = false, isLandscape))
                        }

                        if (isDarkTheme != false) {
                            add(ScreenshotTestSpec(display, isDarkTheme = true, isLandscape))
                        }
                    }

                    if (isLandscape != true) {
                        addDisplay(isLandscape = false)
                    }

                    if (isLandscape != false) {
                        addDisplay(isLandscape = true)
                    }
                }
            }
        }
    }

    override fun toString(): String = buildString {
        // This string is appended to PNGs stored in the device, so let's keep it simple.
        append(display.name)
        if (isDarkTheme) append("_dark")
        if (isLandscape) append("_landscape")
    }
}
