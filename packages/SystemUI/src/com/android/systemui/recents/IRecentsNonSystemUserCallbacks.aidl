/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.graphics.Rect;

/**
 * Due to the fact that RecentsActivity is per-user, we need to establish an
 * interface (this) for the system user to callback to the secondary users in
 * response to UI events coming in from the system user's SystemUI.
 */
oneway interface IRecentsNonSystemUserCallbacks {
    void preloadRecents();
    void cancelPreloadingRecents();
    void showRecents(boolean triggeredFromAltTab, boolean draggingInRecents, boolean animate,
            boolean reloadTasks, boolean fromHome, int recentsGrowTarget);
    void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);
    void toggleRecents(int recentsGrowTarget);
    void onConfigurationChanged();
    void dockTopTask(int topTaskId, int dragMode, int stackCreateMode,
            in Rect initialBounds);
    void onDraggingInRecents(float distanceFromTop);
    void onDraggingInRecentsEnded(float velocity);
}
