/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.input.screenshot

import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.DisplaySpec

/**
 * The emulations specs for all 8 permutations of:
 * - phone or tablet.
 * - dark of light mode.
 * - portrait or landscape.
 */
val DeviceEmulationSpec.Companion.PhoneAndTabletFull
    get() = PhoneAndTabletFullSpec

private val PhoneAndTabletFullSpec =
        DeviceEmulationSpec.forDisplays(Displays.Phone, Displays.Tablet)

/**
 * The emulations specs of:
 * - phone + light mode + portrait.
 * - phone + light mode + landscape.
 * - tablet + dark mode + portrait.
 *
 * This allows to test the most important permutations of a screen/layout with only 3
 * configurations.
 */
val DeviceEmulationSpec.Companion.PhoneAndTabletMinimal
    get() = PhoneAndTabletMinimalSpec

private val PhoneAndTabletMinimalSpec =
    DeviceEmulationSpec.forDisplays(Displays.Phone, isDarkTheme = false) +
    DeviceEmulationSpec.forDisplays(Displays.Tablet, isDarkTheme = true, isLandscape = false)

/**
 * This allows to test only single most important configuration.
 */
val DeviceEmulationSpec.Companion.PhoneMinimal
    get() = PhoneMinimalSpec

private val PhoneMinimalSpec =
    DeviceEmulationSpec.forDisplays(Displays.Phone, isDarkTheme = false, isLandscape = false)

object Displays {
    val Phone =
        DisplaySpec(
            "phone",
            width = 1440,
            height = 3120,
            densityDpi = 560,
        )

    val Tablet =
        DisplaySpec(
            "tablet",
            width = 2560,
            height = 1600,
            densityDpi = 320,
        )
}