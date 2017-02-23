/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import android.app.RemoteAction;
import android.graphics.Rect;

import com.android.server.am.ActivityStackSupervisor.ActivityContainer;
import com.android.server.wm.PinnedStackWindowController;
import com.android.server.wm.StackWindowController;

import java.util.List;

/**
 * State and management of the pinned stack of activities.
 */
class PinnedActivityStack extends ActivityStack<PinnedStackWindowController> {

    PinnedActivityStack(ActivityContainer activityContainer,
            RecentTasks recentTasks, boolean onTop) {
        super(activityContainer, recentTasks, onTop);
    }

    @Override
    PinnedStackWindowController createStackWindowController(int displayId, boolean onTop,
            Rect outBounds) {
        return new PinnedStackWindowController(mStackId, this, displayId, onTop, outBounds);
    }

    void animateResizePinnedStack(Rect bounds, int animationDuration) {
        getWindowContainerController().animateResizePinnedStack(bounds, animationDuration);
    }

    void setPictureInPictureAspectRatio(float aspectRatio) {
        getWindowContainerController().setPictureInPictureAspectRatio(aspectRatio);
    }

    void setPictureInPictureActions(List<RemoteAction> actions) {
        getWindowContainerController().setPictureInPictureActions(actions);
    }
}
