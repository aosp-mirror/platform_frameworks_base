/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

/**
 * Interface used to control special UI modes.
 * @hide
 */
interface IUiModeManager {
    /**
     * Enables the car mode. Only the system can do this.
     * @hide
     */
    void enableCarMode(int flags);

    /**
     * Disables the car mode.
     */
    void disableCarMode(int flags);

    /**
     * Return the current running mode.
     */
    int getCurrentModeType();
    
    /**
     * Sets the night mode.
     * The mode can be one of:
     *   1 - notnight mode
     *   2 - night mode
     *   3 - automatic mode switching
     */
    void setNightMode(int mode);

    /**
     * Gets the currently configured night mode.  Return 1 for notnight,
     * 2 for night, and 3 for automatic mode switching.
     */
    int getNightMode();

    /**
     * Tells if UI mode is locked or not.
     */
    boolean isUiModeLocked();

    /**
     * Tells if Night mode is locked or not.
     */
    boolean isNightModeLocked();
}
