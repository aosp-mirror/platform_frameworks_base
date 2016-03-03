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
    public boolean launchedFromApp;
    public boolean launchedFromAppDocked;
    public boolean launchedFromHome;
    public boolean launchedFromSearchHome;
    public boolean launchedReuseTaskStackViews;
    public boolean launchedHasConfigurationChanged;
    public boolean launchedViaDragGesture;
    public boolean launchedWhileDocking;
    public int launchedToTaskId;
    public int launchedNumVisibleTasks;
    public int launchedNumVisibleThumbnails;

    public void reset() {
        launchedFromHome = false;
        launchedFromSearchHome = false;
        launchedFromApp = false;
        launchedFromAppDocked = false;
        launchedToTaskId = -1;
        launchedWithAltTab = false;
        launchedHasConfigurationChanged = false;
        launchedViaDragGesture = false;
        launchedWhileDocking = false;
    }

    /** Called when the configuration has changed, and we want to reset any configuration specific
     * members. */
    public void updateOnConfigurationChange() {
        // Reset this flag on configuration change to ensure that we recreate new task views
        launchedReuseTaskStackViews = false;
        // Set this flag to indicate that the configuration has changed since Recents last launched
        launchedHasConfigurationChanged = true;
        launchedViaDragGesture = false;
        launchedWhileDocking = false;
    }

    /**
     * Returns the task to focus given the current launch state.
     */
    public int getInitialFocusTaskIndex(int numTasks) {
        RecentsDebugFlags debugFlags = Recents.getDebugFlags();
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        if (launchedFromApp) {
            if (!launchState.launchedWithAltTab && debugFlags.isFastToggleRecentsEnabled()) {
                // If fast toggling, focus the front most task so that the next tap will focus the
                // N-1 task
                return numTasks - 1;
            }

            // If coming from another app, focus the next task
            return Math.max(0, numTasks - 2);
        } else {
            if (!launchState.launchedWithAltTab && debugFlags.isFastToggleRecentsEnabled()) {
                // If fast toggling, defer focusing until the next tap (which will automatically
                // focus the front most task)
                return -1;
            }

            // If coming from home, focus the first task
            return numTasks - 1;
        }
    }
}
