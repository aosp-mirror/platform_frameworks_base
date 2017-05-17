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
    // Set if the activity that we launched from entered PiP during the transition into Recents
    public boolean launchedFromPipApp;
    // Set if the next activity that quick-switch will launch is the PiP activity
    public boolean launchedWithNextPipApp;
    public boolean launchedFromBlacklistedApp;
    public boolean launchedFromHome;
    public boolean launchedViaDragGesture;
    public boolean launchedViaDockGesture;
    public int launchedToTaskId;
    public int launchedNumVisibleTasks;
    public int launchedNumVisibleThumbnails;

    public void reset() {
        launchedFromHome = false;
        launchedFromApp = false;
        launchedFromBlacklistedApp = false;
        launchedFromPipApp = false;
        launchedWithNextPipApp = false;
        launchedToTaskId = -1;
        launchedWithAltTab = false;
        launchedViaDragGesture = false;
        launchedViaDockGesture = false;
    }

    /**
     * Returns the task to focus given the current launch state.
     */
    public int getInitialFocusTaskIndex(int numTasks, boolean useGridLayout) {
        RecentsDebugFlags debugFlags = Recents.getDebugFlags();
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        if (launchedFromApp) {
            if (!launchState.launchedWithAltTab && debugFlags.isFastToggleRecentsEnabled()) {
                // If fast toggling, focus the front most task so that the next tap will launch the
                // task
                return numTasks - 1;
            }

            if (launchState.launchedFromBlacklistedApp) {
                // If we are launching from a blacklisted app, focus the front most task so that the
                // next tap will launch the task
                return numTasks - 1;
            }

            if (useGridLayout) {
                // If coming from another app to the grid layout, focus the front most task
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

            // If coming from home, focus the front most task
            return numTasks - 1;
        }
    }
}
