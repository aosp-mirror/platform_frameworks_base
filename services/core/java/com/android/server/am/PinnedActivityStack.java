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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.RemoteAction;
import android.content.res.Configuration;
import android.graphics.Rect;

import com.android.server.wm.PinnedStackWindowController;
import com.android.server.wm.PinnedStackWindowListener;

import java.util.ArrayList;
import java.util.List;

/**
 * State and management of the pinned stack of activities.
 */
class PinnedActivityStack extends ActivityStack<PinnedStackWindowController>
        implements PinnedStackWindowListener {

    PinnedActivityStack(ActivityDisplay display, int stackId, ActivityStackSupervisor supervisor,
            boolean onTop) {
        super(display, stackId, supervisor, WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, onTop);
    }

    @Override
    PinnedStackWindowController createStackWindowController(int displayId, boolean onTop,
            Rect outBounds) {
        return new PinnedStackWindowController(mStackId, this, displayId, onTop, outBounds,
                mStackSupervisor.mWindowManager);
    }

    Rect getDefaultPictureInPictureBounds(float aspectRatio) {
        return getWindowContainerController().getPictureInPictureBounds(aspectRatio,
                null /* currentStackBounds */);
    }

    void animateResizePinnedStack(Rect sourceHintBounds, Rect toBounds, int animationDuration,
            boolean fromFullscreen) {
        if (skipResizeAnimation(toBounds == null /* toFullscreen */)) {
            mService.moveTasksToFullscreenStack(mStackId, true /* onTop */);
        } else {
            getWindowContainerController().animateResizePinnedStack(toBounds, sourceHintBounds,
                    animationDuration, fromFullscreen);
        }
    }

    private boolean skipResizeAnimation(boolean toFullscreen) {
        if (!toFullscreen) {
            return false;
        }
        final Configuration parentConfig = getParent().getConfiguration();
        final ActivityRecord top = topRunningNonOverlayTaskActivity();
        return top != null && !top.isConfigurationCompatible(parentConfig);
    }

    void setPictureInPictureAspectRatio(float aspectRatio) {
        getWindowContainerController().setPictureInPictureAspectRatio(aspectRatio);
    }

    void setPictureInPictureActions(List<RemoteAction> actions) {
        getWindowContainerController().setPictureInPictureActions(actions);
    }

    boolean isAnimatingBoundsToFullscreen() {
        return getWindowContainerController().isAnimatingBoundsToFullscreen();
    }

    /**
     * Returns whether to defer the scheduling of the multi-window mode.
     */
    boolean deferScheduleMultiWindowModeChanged() {
        // For the pinned stack, the deferring of the multi-window mode changed is tied to the
        // transition animation into picture-in-picture, and is called once the animation completes,
        // or is interrupted in a way that would leave the stack in a non-fullscreen state.
        // @see BoundsAnimationController
        // @see BoundsAnimationControllerTests
        return mWindowContainerController.deferScheduleMultiWindowModeChanged();
    }

    public void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds,
            boolean forceUpdate) {
        // It is guaranteed that the activities requiring the update will be in the pinned stack at
        // this point (either reparented before the animation into PiP, or before reparenting after
        // the animation out of PiP)
        synchronized(this) {
            ArrayList<TaskRecord> tasks = getAllTasks();
            for (int i = 0; i < tasks.size(); i++ ) {
                mStackSupervisor.updatePictureInPictureMode(tasks.get(i), targetStackBounds,
                        forceUpdate);
            }
        }
    }
}
