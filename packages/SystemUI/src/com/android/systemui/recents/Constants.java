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
 * limitations under the License.
 */

package com.android.systemui.recents;

/**
 * Constants
 */
public class Constants {
    public static class DebugFlags {
        // Enable this with any other debug flag to see more info
        public static final boolean Verbose = false;

        public static class App {
            // Enables debug drawing for the transition thumbnail
            public static final boolean EnableTransitionThumbnailDebugMode = false;
            // Enables the filtering of tasks according to their grouping
            public static final boolean EnableTaskFiltering = false;
            // Enables clipping of tasks against each other
            public static final boolean EnableTaskStackClipping = true;
            // Enables tapping on the TaskBar to launch the task
            public static final boolean EnableTaskBarTouchEvents = true;
            // Enables app-info pane on long-pressing the icon
            public static final boolean EnableDevAppInfoOnLongPress = true;
            // Enables debug mode
            public static final boolean EnableDebugMode = false;
            // Enables the search bar layout
            public static final boolean EnableSearchLayout = true;
            // Enables the thumbnail alpha on the front-most task
            public static final boolean EnableThumbnailAlphaOnFrontmost = false;
            // This disables the bitmap and icon caches
            public static final boolean DisableBackgroundCache = false;
            // Enables the simulated task affiliations
            public static final boolean EnableSimulatedTaskGroups = false;
            // Defines the number of mock task affiliations per group
            public static final int TaskAffiliationsGroupCount = 12;
            // Enables us to create mock recents tasks
            public static final boolean EnableSystemServicesProxy = false;
            // Defines the number of mock recents packages to create
            public static final int SystemServicesProxyMockPackageCount = 3;
            // Defines the number of mock recents tasks to create
            public static final int SystemServicesProxyMockTaskCount = 100;
        }
    }

    public static class Values {
        public static class App {
            public static int AppWidgetHostId = 1024;
            public static String Key_SearchAppWidgetId = "searchAppWidgetId";
            public static String Key_DebugModeEnabled = "debugModeEnabled";
            public static String DebugModeVersion = "A";
        }

        public static class TaskStackView {
            public static final int TaskStackMinOverscrollRange = 32;
            public static final int TaskStackMaxOverscrollRange = 128;
            public static final int FilterStartDelay = 25;
        }
    }
}
