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
 * Current Constraints:
 *  - needed in onStart() before onNewIntent()
 *  - needs to be reset when Recents is hidden
 *  - needs to be computed in Recents component
 *  - needs to be accessible by views
 */
public class RecentsActivityLaunchState {

    public boolean launchedWithAltTab;
    public boolean launchedFromAppWithThumbnail;
    public boolean launchedFromHome;
    public boolean launchedFromSearchHome;
    public boolean launchedReuseTaskStackViews;
    public boolean launchedHasConfigurationChanged;
    public boolean launchedViaDragGesture;
    public int launchedToTaskId;
    public int launchedNumVisibleTasks;
    public int launchedNumVisibleThumbnails;

    /** Called when the configuration has changed, and we want to reset any configuration specific
     * members. */
    public void updateOnConfigurationChange() {
        // Reset this flag on configuration change to ensure that we recreate new task views
        launchedReuseTaskStackViews = false;
        // Set this flag to indicate that the configuration has changed since Recents last launched
        launchedHasConfigurationChanged = true;
        launchedViaDragGesture = false;
    }

    /**
     * Returns the task to focus given the current launch state.
     */
    public int getInitialFocusTaskIndex(int numTasks) {
        RecentsDebugFlags flags = Recents.getDebugFlags();
        if (flags.isPageOnToggleEnabled() && !launchedWithAltTab) {
            // If we are fast toggling, then focus the next task depending on when you are on home
            // or coming in from another app
            if (launchedFromHome) {
                return numTasks - 1;
            } else {
                if (flags.isFastToggleRecentsEnabled() || !flags.isInitialStatePaging()) {
                    return numTasks - 1;
                } else {
                    return numTasks - 2;
                }
            }
        }

        if (launchedWithAltTab && launchedFromAppWithThumbnail) {
            // If alt-tabbing from another app, focus the next task
            return numTasks - 2;
        } else if ((launchedWithAltTab && launchedFromHome) ||
                (!launchedWithAltTab && launchedFromAppWithThumbnail)) {
            // If alt-tabbing from home, or launching from an app normally, focus that task
            return numTasks - 1;
        } else {
            // Otherwise, we are launching recents from home normally, focus no tasks so that we
            // know to return home
            return -1;
        }
    }
}
