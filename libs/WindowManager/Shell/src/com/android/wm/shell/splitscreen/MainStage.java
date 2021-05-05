/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.splitscreen;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import android.graphics.Rect;
import android.view.SurfaceSession;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * Main stage for split-screen mode. When split-screen is active all standard activity types launch
 * on the main stage, except for task that are explicitly pinned to the {@link SideStage}.
 * @see StageCoordinator
 */
class MainStage extends StageTaskListener {
    private static final String TAG = MainStage.class.getSimpleName();

    private boolean mIsActive = false;

    MainStage(ShellTaskOrganizer taskOrganizer, int displayId,
            StageListenerCallbacks callbacks, SyncTransactionQueue syncQueue,
            SurfaceSession surfaceSession) {
        super(taskOrganizer, displayId, callbacks, syncQueue, surfaceSession);
    }

    boolean isActive() {
        return mIsActive;
    }

    void activate(Rect rootBounds, WindowContainerTransaction wct) {
        if (mIsActive) return;

        final WindowContainerToken rootToken = mRootTaskInfo.token;
        wct.setBounds(rootToken, rootBounds)
                .setWindowingMode(rootToken, WINDOWING_MODE_MULTI_WINDOW)
                .setLaunchRoot(
                        rootToken,
                        CONTROLLED_WINDOWING_MODES,
                        CONTROLLED_ACTIVITY_TYPES)
                .reparentTasks(
                        null /* currentParent */,
                        rootToken,
                        CONTROLLED_WINDOWING_MODES,
                        CONTROLLED_ACTIVITY_TYPES,
                        true /* onTop */)
                // Moving the root task to top after the child tasks were re-parented , or the root
                // task cannot be visible and focused.
                .reorder(rootToken, true /* onTop */);

        mIsActive = true;
    }

    void deactivate(WindowContainerTransaction wct) {
        deactivate(wct, false /* toTop */);
    }

    void deactivate(WindowContainerTransaction wct, boolean toTop) {
        if (!mIsActive) return;
        mIsActive = false;

        if (mRootTaskInfo == null) return;
        final WindowContainerToken rootToken = mRootTaskInfo.token;
        wct.setLaunchRoot(
                        rootToken,
                        null,
                        null)
                .reparentTasks(
                        rootToken,
                        null /* newParent */,
                        CONTROLLED_WINDOWING_MODES_WHEN_ACTIVE,
                        CONTROLLED_ACTIVITY_TYPES,
                        toTop)
                // We want this re-order to the bottom regardless since we are re-parenting
                // all its tasks.
                .reorder(rootToken, false /* onTop */);
    }

    void updateConfiguration(int windowingMode, Rect bounds, WindowContainerTransaction wct) {
        wct.setBounds(mRootTaskInfo.token, bounds)
                .setWindowingMode(mRootTaskInfo.token, windowingMode);
    }
}
