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

package android.hardware.display;

/**
 * Interface for notifying the display owner about brightness changes.
 *
 * @hide
 */
oneway interface IBrightnessListener {
    /**
     * Called when the display's brightness has changed.
     *
     * @param brightness the new brightness of the display. Value of {@code 0.0} indicates the
     *   minimum supported brightness and value of {@code 1.0} indicates the maximum supported
     *   brightness.
     */
    void onBrightnessChanged(float brightness);
}
