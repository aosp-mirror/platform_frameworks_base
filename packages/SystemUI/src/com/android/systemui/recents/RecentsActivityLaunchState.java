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
 * The launch state of the RecentsActivity.
 *
 * TODO: We will be refactoring this out RecentsConfiguration.
 * Current Constraints:
 *  - needed in onStart() before onNewIntent()
 *  - needs to be reset when Recents is hidden
 *  - needs to be computed in Recents component
 *  - needs to be accessible by views
 */
public class RecentsActivityLaunchState {

    public RecentsConfiguration mConfig;

    public boolean launchedWithAltTab;
    public boolean launchedWithNoRecentTasks;
    public boolean launchedFromAppWithThumbnail;
    public boolean launchedFromHome;
    public boolean launchedFromSearchHome;
    public boolean launchedReuseTaskStackViews;
    public boolean launchedHasConfigurationChanged;
    public int launchedToTaskId;
    public int launchedNumVisibleTasks;
    public int launchedNumVisibleThumbnails;

    RecentsActivityLaunchState(RecentsConfiguration config) {
        mConfig = config;
    }

    /** Called when the configuration has changed, and we want to reset any configuration specific
     * members. */
    public void updateOnConfigurationChange() {
        // Reset this flag on configuration change to ensure that we recreate new task views
        launchedReuseTaskStackViews = false;
        // Set this flag to indicate that the configuration has changed since Recents last launched
        launchedHasConfigurationChanged = true;
    }

    /** Returns whether the status bar scrim should be animated when shown for the first time. */
    public boolean shouldAnimateStatusBarScrim() {
        return launchedFromHome;
    }

    /** Returns whether the status bar scrim should be visible. */
    public boolean hasStatusBarScrim() {
        return !launchedWithNoRecentTasks;
    }

    /** Returns whether the nav bar scrim should be animated when shown for the first time. */
    public boolean shouldAnimateNavBarScrim() {
        return true;
    }

    /** Returns whether the nav bar scrim should be visible. */
    public boolean hasNavBarScrim() {
        // Only show the scrim if we have recent tasks, and if the nav bar is not transposed
        return !launchedWithNoRecentTasks && mConfig.hasTransposedNavBar;
    }
}
