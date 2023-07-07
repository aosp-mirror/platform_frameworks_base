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

package android.hardware.input;

/**
 * The KeyboardBacklightState class is a representation of a keyboard backlight which is a
 * single-colored backlight that illuminates all the keys on the keyboard.
 *
 * @hide
 */
public abstract class KeyboardBacklightState {

    /**
     * Get the backlight brightness level in range [0, {@link #getMaxBrightnessLevel()}].
     *
     * @return backlight brightness level
     */
    public abstract int getBrightnessLevel();

    /**
     * Get the max backlight brightness level.
     *
     * @return max backlight brightness level
     */
    public abstract int getMaxBrightnessLevel();
}

