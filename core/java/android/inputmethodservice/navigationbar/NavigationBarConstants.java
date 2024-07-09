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

package android.inputmethodservice.navigationbar;

import android.annotation.ColorInt;
import android.graphics.Color;

final class NavigationBarConstants {
    private NavigationBarConstants() {
        // Not intended to be instantiated.
    }

    // Copied from "navbar_back_button_ime_offset"
    // TODO(b/215443343): Handle this in the drawable then remove this constant.
    static final float NAVBAR_BACK_BUTTON_IME_OFFSET = 2.0f;

    // Copied from "white" at packages/SettingsLib/res/values/colors.xml
    @ColorInt
    static final int WHITE = Color.WHITE;

    // Copied from "black" at packages/SettingsLib/res/values/colors.xml
    @ColorInt
    static final int BLACK = Color.BLACK;

    // Copied from "navigation_bar_deadzone_hold"
    static final int NAVIGATION_BAR_DEADZONE_HOLD = 333;

    // Copied from "navigation_bar_deadzone_hold"
    static final int NAVIGATION_BAR_DEADZONE_DECAY = 333;

    // Copied from "navigation_bar_deadzone_size"
    static final float NAVIGATION_BAR_DEADZONE_SIZE = 12.0f;

    // Copied from "navigation_bar_deadzone_size_max"
    static final float NAVIGATION_BAR_DEADZONE_SIZE_MAX = 32.0f;

    // Copied from "nav_key_button_shadow_offset_x"
    static final float NAV_KEY_BUTTON_SHADOW_OFFSET_X = 0.0f;

    // Copied from "nav_key_button_shadow_offset_y"
    static final float NAV_KEY_BUTTON_SHADOW_OFFSET_Y = 1.0f;

    // Copied from "nav_key_button_shadow_radius"
    static final float NAV_KEY_BUTTON_SHADOW_RADIUS = 0.5f;

    // Copied from "nav_key_button_shadow_color"
    @ColorInt
    static final int NAV_KEY_BUTTON_SHADOW_COLOR = 0x30000000;
}
