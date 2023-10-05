/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.pip2.phone;

import static android.view.WindowManager.TRANSIT_OPEN;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.pip.PipBoundsAlgorithm;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.common.pip.PipUtils;
import com.android.wm.shell.pip.PipMenuController;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

/**
 * Implementation of transitions for PiP on phone.
 */
public class PipTransition extends PipTransitionController {
    @Nullable
    private IBinder mAutoEnterButtonNavTransition;

    public PipTransition(
            @NonNull ShellInit shellInit,
            @NonNull ShellTaskOrganizer shellTaskOrganizer,
            @NonNull Transitions transitions,
            PipBoundsState pipBoundsState,
            PipMenuController pipMenuController,
            PipBoundsAlgorithm pipBoundsAlgorithm) {
        super(shellInit, shellTaskOrganizer, transitions, pipBoundsState, pipMenuController,
                pipBoundsAlgorithm);
    }

    @Override
    protected void onInit() {
        if (PipUtils.isPip2ExperimentEnabled()) {
            mTransitions.addHandler(this);
        }
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (isAutoEnterInButtonNavigation(request)) {
            mAutoEnterButtonNavTransition = transition;
            return getEnterPipTransaction(transition, request);
        }
        return null;
    }

    @Override
    public void augmentRequest(@NonNull IBinder transition, @NonNull TransitionRequestInfo request,
            @NonNull WindowContainerTransaction outWct) {
        if (isAutoEnterInButtonNavigation(request)) {
            outWct.merge(getEnterPipTransaction(transition, request), true /* transfer */);
            mAutoEnterButtonNavTransition = transition;
        }
    }

    private WindowContainerTransaction getEnterPipTransaction(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        final ActivityManager.RunningTaskInfo pipTask = request.getPipTask();
        PictureInPictureParams pipParams = pipTask.pictureInPictureParams;
        mPipBoundsState.setBoundsStateForEntry(pipTask.topActivity, pipTask.topActivityInfo,
                pipParams, mPipBoundsAlgorithm);

        // calculate the entry bounds and notify core to move task to pinned with final bounds
        final Rect entryBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.movePipActivityToPinnedRootTask(pipTask.token, entryBounds);
        return wct;
    }

    private boolean isAutoEnterInButtonNavigation(@NonNull TransitionRequestInfo requestInfo) {
        final ActivityManager.RunningTaskInfo pipTask = requestInfo.getPipTask();
        if (pipTask == null) {
            return false;
        }
        if (pipTask.pictureInPictureParams == null) {
            return false;
        }

        // Assuming auto-enter is enabled and pipTask is non-null, the TRANSIT_OPEN request type
        // implies that we are entering PiP in button navigation mode. This is guaranteed by
        // TaskFragment#startPausing()` in Core which wouldn't get called in gesture nav.
        return requestInfo.getType() == TRANSIT_OPEN
                && pipTask.pictureInPictureParams.isAutoEnterEnabled();
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (transition == mAutoEnterButtonNavTransition) {
            startTransaction.apply();
            finishCallback.onTransitionFinished(null);
            return true;
        }
        return false;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {}

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @Nullable SurfaceControl.Transaction finishT) {}
}
