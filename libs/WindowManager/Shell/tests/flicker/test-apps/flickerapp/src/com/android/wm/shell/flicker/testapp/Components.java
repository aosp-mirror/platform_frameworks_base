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
    public abstract static class ComponentsInfo {
        public ComponentName getComponentName() {
            return ComponentName.createRelative(PACKAGE_NAME, "." + getActivityName());
        }
        public abstract String getActivityName();
    }

    public static final String PACKAGE_NAME = "com.android.wm.shell.flicker.testapp";

    public static class FixedActivity extends ComponentsInfo {
        // Sets the fixed orientation (can be one of {@link ActivityInfo.ScreenOrientation}
        public static final String EXTRA_FIXED_ORIENTATION = "fixed_orientation";

        @Override
        public String getActivityName() {
            return FixedActivity.class.getSimpleName();
        }
    }

    public static class NonResizeableActivity extends ComponentsInfo {
        @Override
        public String getActivityName() {
            return NonResizeableActivity.class.getSimpleName();
        }
    }

    public static class PipActivity extends ComponentsInfo {
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

        @Override
        public String getActivityName() {
            return PipActivity.class.getSimpleName();
        }
    }

    public static class ImeActivity extends ComponentsInfo {
        @Override
        public String getActivityName() {
            return ImeActivity.class.getSimpleName();
        }
    }

    public static class SplitScreenActivity extends ComponentsInfo {
        @Override
        public String getActivityName() {
            return SplitScreenActivity.class.getSimpleName();
        }
    }

    public static class SplitScreenSecondaryActivity extends ComponentsInfo {
        @Override
        public String getActivityName() {
            return SplitScreenSecondaryActivity.class.getSimpleName();
        }
    }
}
