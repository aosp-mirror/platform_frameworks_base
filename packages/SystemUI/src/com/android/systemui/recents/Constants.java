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

    public static class Metrics {
        // DO NOT MODIFY THE ORDER OF THESE METRICS
        public static final int DismissSourceKeyboard = 0;
        public static final int DismissSourceSwipeGesture = 1;
        public static final int DismissSourceHeaderButton = 2;
    }

    public static class DebugFlags {
        // Enable this with any other debug flag to see more info
        public static final boolean Verbose = false;

        public static class App {
            // Enables debug drawing for the transition thumbnail
            public static final boolean EnableTransitionThumbnailDebugMode = false;
            // Enables the filtering of tasks according to their grouping
            public static final boolean EnableTaskFiltering = false;
            // Enables dismiss-all
            public static final boolean EnableDismissAll = false;
            // Enables debug mode
            public static final boolean EnableDebugMode = false;
            // Enables the search bar integration
            public static final boolean EnableSearchBar = false;
            // Enables the thumbnail alpha on the front-most task
            public static final boolean EnableThumbnailAlphaOnFrontmost = false;
            // Enables all system stacks to show up in the same recents stack
            public static final boolean EnableMultiStackToSingleStack = true;
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
            public static String DebugModeVersion = "A";
        }

        public static class TaskStackView {
            public static final int TaskStackMinOverscrollRange = 32;
            public static final int TaskStackMaxOverscrollRange = 128;
            public static final int FilterStartDelay = 25;
        }
    }
}
