/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker.testapp;

import android.content.ComponentName;

public class Components {
    public static final String PACKAGE_NAME = "com.android.wm.shell.flicker.testapp";

    public static class SimpleActivity {
        public static final String LABEL = "SimpleApp";
        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".SimpleActivity");
    }

    public static class FixedActivity {
        public static final String EXTRA_FIXED_ORIENTATION = "fixed_orientation";
        public static final String LABEL = "FixedApp";
        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".FixedActivity");
    }

    public static class NonResizeableActivity {
        public static final String LABEL = "NonResizeableApp";
        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".NonResizeableActivity");
    }

    public static class PipActivity {
        // Test App > Pip Activity
        public static final String LABEL = "PipApp";
        public static final String MENU_ACTION_NO_OP = "No-Op";
        public static final String MENU_ACTION_ON = "On";
        public static final String MENU_ACTION_OFF = "Off";
        public static final String MENU_ACTION_CLEAR = "Clear";

        // Intent action that this activity dynamically registers to enter picture-in-picture
        public static final String ACTION_ENTER_PIP = PACKAGE_NAME + ".PipActivity.ENTER_PIP";
        // Intent action that this activity dynamically registers to set requested orientation.
        // Will apply the oriention to the value set in the EXTRA_FIXED_ORIENTATION extra.
        public static final String ACTION_SET_REQUESTED_ORIENTATION =
                PACKAGE_NAME + ".PipActivity.SET_REQUESTED_ORIENTATION";

        // Calls enterPictureInPicture() on creation
        public static final String EXTRA_ENTER_PIP = "enter_pip";
        // Sets the fixed orientation (can be one of {@link ActivityInfo.ScreenOrientation}
        public static final String EXTRA_PIP_ORIENTATION = "fixed_orientation";
        // Adds a click listener to finish this activity when it is clicked
        public static final String EXTRA_TAP_TO_FINISH = "tap_to_finish";

        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".PipActivity");
    }

    public static class ImeActivity {
        public static final String LABEL = "ImeApp";
        public static final String ACTION_CLOSE_IME =
                PACKAGE_NAME + ".action.CLOSE_IME";
        public static final String ACTION_OPEN_IME =
                PACKAGE_NAME + ".action.OPEN_IME";
        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".ImeActivity");
    }

    public static class SplitScreenActivity {
        public static final String LABEL = "SplitScreenPrimaryApp";
        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".SplitScreenActivity");
    }

    public static class SplitScreenSecondaryActivity {
        public static final String LABEL = "SplitScreenSecondaryApp";
        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".SplitScreenSecondaryActivity");
    }

    public static class LaunchBubbleActivity {
        public static final String LABEL = "LaunchBubbleApp";
        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".LaunchBubbleActivity");
    }

    public static class BubbleActivity {
        public static final String LABEL = "BubbleApp";
        public static final ComponentName COMPONENT = new ComponentName(PACKAGE_NAME,
                PACKAGE_NAME + ".BubbleActivity");
    }
}
