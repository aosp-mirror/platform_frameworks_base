/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.battery

import com.android.settingslib.graph.ThemedBatteryDrawable

/** An object storing specs related to the battery icon in the status bar. */
object BatterySpecs {

    /** Width of the main battery icon, not including the shield. */
    const val BATTERY_WIDTH = ThemedBatteryDrawable.WIDTH
    /** Height of the main battery icon, not including the shield. */
    const val BATTERY_HEIGHT = ThemedBatteryDrawable.HEIGHT

    private const val SHIELD_WIDTH = 10f
    private const val SHIELD_HEIGHT = 13f

    /**
     * Amount that the left side of the shield should be offset from the left side of the battery.
     */
    const val SHIELD_LEFT_OFFSET = 8f
    /** Amount that the top of the shield should be offset from the top of the battery. */
    const val SHIELD_TOP_OFFSET = 10f

    const val SHIELD_STROKE = 4f

    /** The full width of the battery icon, including the main battery icon *and* the shield. */
    const val BATTERY_WIDTH_WITH_SHIELD = SHIELD_LEFT_OFFSET + SHIELD_WIDTH
    /** The full height of the battery icon, including the main battery icon *and* the shield. */
    const val BATTERY_HEIGHT_WITH_SHIELD = SHIELD_TOP_OFFSET + SHIELD_HEIGHT

    /**
     * Given the desired height of the main battery icon in pixels, returns the height that the full
     * battery icon will take up in pixels.
     *
     * If there's no shield, this will just return [mainBatteryHeight]. Otherwise, the shield
     * extends slightly below the bottom of the main battery icon so we need some extra height.
     */
    @JvmStatic
    fun getFullBatteryHeight(mainBatteryHeight: Float, displayShield: Boolean): Float {
        return if (!displayShield) {
            mainBatteryHeight
        } else {
            val verticalScaleFactor = mainBatteryHeight / BATTERY_HEIGHT
            verticalScaleFactor * BATTERY_HEIGHT_WITH_SHIELD
        }
    }

    /**
     * Given the desired width of the main battery icon in pixels, returns the width that the full
     * battery icon will take up in pixels.
     *
     * If there's no shield, this will just return [mainBatteryWidth]. Otherwise, the shield extends
     * past the right side of the main battery icon so we need some extra width.
     */
    @JvmStatic
    fun getFullBatteryWidth(mainBatteryWidth: Float, displayShield: Boolean): Float {
        return if (!displayShield) {
            mainBatteryWidth
        } else {
            val horizontalScaleFactor = mainBatteryWidth / BATTERY_WIDTH
            horizontalScaleFactor * BATTERY_WIDTH_WITH_SHIELD
        }
    }

    /**
     * Given the height of the full battery icon, return how tall the main battery icon should be.
     *
     * If there's no shield, this will just return [fullBatteryHeight]. Otherwise, the shield takes
     * up some of the view's height so the main battery width will be just a portion of
     * [fullBatteryHeight].
     */
    @JvmStatic
    fun getMainBatteryHeight(fullBatteryHeight: Float, displayShield: Boolean): Float {
        return if (!displayShield) {
            fullBatteryHeight
        } else {
            return (BATTERY_HEIGHT / BATTERY_HEIGHT_WITH_SHIELD) * fullBatteryHeight
        }
    }

    /**
     * Given the width of the full battery icon, return how wide the main battery icon should be.
     *
     * If there's no shield, this will just return [fullBatteryWidth]. Otherwise, the shield takes
     * up some of the view's width so the main battery width will be just a portion of
     * [fullBatteryWidth].
     */
    @JvmStatic
    fun getMainBatteryWidth(fullBatteryWidth: Float, displayShield: Boolean): Float {
        return if (!displayShield) {
            fullBatteryWidth
        } else {
            return (BATTERY_WIDTH / BATTERY_WIDTH_WITH_SHIELD) * fullBatteryWidth
        }
    }
}
