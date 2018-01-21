/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui;

import android.graphics.Rect;
import android.view.Display;
import android.view.View;

public interface RecentsComponent {
    void showRecentApps(boolean triggeredFromAltTab);
    void showNextAffiliatedTask();
    void showPrevAffiliatedTask();

    /**
     * Docks the top-most task and opens recents.
     */
    boolean splitPrimaryTask(int dragMode, int stackCreateMode, Rect initialBounds,
            int metricsDockAction);

    /**
     * Called during a drag-from-navbar-in gesture.
     *
     * @param distanceFromTop the distance of the current drag in gesture from the top of the
     *                        screen
     */
    void onDraggingInRecents(float distanceFromTop);

    /**
     * Called when the gesture to drag in recents ended.
     *
     * @param velocity the velocity of the finger when releasing it in pixels per second
     */
    void onDraggingInRecentsEnded(float velocity);
}
