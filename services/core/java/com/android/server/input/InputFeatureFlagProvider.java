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

package com.android.server.input;

import android.sysprop.InputProperties;

import java.util.Optional;

/**
 * A component of {@link InputManagerService} responsible for managing the input sysprop flags
 *
 * @hide
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class InputFeatureFlagProvider {

    // To disable Keyboard backlight control via Framework, run:
    // 'adb shell setprop persist.input.keyboard_backlight_control.enabled false' (requires restart)
    private static final boolean KEYBOARD_BACKLIGHT_CONTROL_ENABLED =
            InputProperties.enable_keyboard_backlight_control().orElse(true);

    // To disable Framework controlled keyboard backlight animation run:
    // adb shell setprop persist.input.keyboard.backlight_animation.enabled false (requires restart)
    private static final boolean KEYBOARD_BACKLIGHT_ANIMATION_ENABLED =
            InputProperties.enable_keyboard_backlight_animation().orElse(false);

    private static Optional<Boolean> sKeyboardBacklightControlOverride = Optional.empty();
    private static Optional<Boolean> sKeyboardBacklightAnimationOverride = Optional.empty();

    public static boolean isKeyboardBacklightControlEnabled() {
        return sKeyboardBacklightControlOverride.orElse(KEYBOARD_BACKLIGHT_CONTROL_ENABLED);
    }

    public static boolean isKeyboardBacklightAnimationEnabled() {
        return sKeyboardBacklightAnimationOverride.orElse(KEYBOARD_BACKLIGHT_ANIMATION_ENABLED);
    }

    public static void setKeyboardBacklightControlEnabled(boolean enabled) {
        sKeyboardBacklightControlOverride = Optional.of(enabled);
    }

    public static void setKeyboardBacklightAnimationEnabled(boolean enabled) {
        sKeyboardBacklightAnimationOverride = Optional.of(enabled);
    }

    /**
     * Clears all input feature flag overrides.
     */
    public static void clearOverrides() {
        sKeyboardBacklightControlOverride = Optional.empty();
        sKeyboardBacklightAnimationOverride = Optional.empty();
    }
}
