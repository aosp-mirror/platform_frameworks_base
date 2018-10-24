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
    public boolean launchedFromHome;
    public boolean launchedViaDragGesture;
    public boolean launchedViaDockGesture;
    public int launchedToTaskId;
    public int launchedNumVisibleTasks;
    public int launchedNumVisibleThumbnails;

    public void reset() {
        launchedFromHome = false;
        launchedFromApp = false;
        launchedFromPipApp = false;
        launchedWithNextPipApp = false;
        launchedToTaskId = -1;
        launchedWithAltTab = false;
        launchedViaDragGesture = false;
        launchedViaDockGesture = false;
    }
}
