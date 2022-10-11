/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

/**
 * Class to encapsulate all possible status bar states regarding Keyguard.
 */
public class StatusBarState {

    /**
     * The status bar is in the "normal", unlocked mode or the device is still locked but we're
     * accessing camera from power button double-tap shortcut.
     */
    public static final int SHADE = 0;

    /**
     * Status bar is currently the Keyguard. In single column mode, when you swipe from the top of
     * the keyguard to expand QS immediately, it's still KEYGUARD state.
     */
    public static final int KEYGUARD = 1;

    /**
     * Status bar is in the special mode, where it was transitioned from lockscreen to shade.
     * Depending on user's security settings, dismissing the shade will either show the
     * bouncer or go directly to unlocked {@link #SHADE} mode.
     */
    public static final int SHADE_LOCKED = 2;

    /**
     * Returns the textual representation of the status bar state.
     */
    public static String toString(int state) {
        switch (state) {
            case SHADE:
                return "SHADE";
            case SHADE_LOCKED:
                return "SHADE_LOCKED";
            case KEYGUARD:
                return "KEYGUARD";
            default:
                return "UNKNOWN: " + state;
        }
    }
}
