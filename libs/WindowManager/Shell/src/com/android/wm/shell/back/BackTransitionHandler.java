/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.back;

import android.os.IBinder;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.transition.Transitions;

class BackTransitionHandler implements Transitions.TransitionHandler {
    private BackAnimationController mBackAnimationController;
    private WindowContainerToken mDepartingWindowContainerToken;

    BackTransitionHandler(@NonNull BackAnimationController backAnimationController) {
        mBackAnimationController = backAnimationController;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (mDepartingWindowContainerToken != null) {
            final TransitionInfo.Change change = info.getChange(mDepartingWindowContainerToken);
            if (change == null) {
                return false;
            }

            startTransaction.hide(change.getLeash());
            startTransaction.apply();
            mDepartingWindowContainerToken = null;
            mBackAnimationController.finishTransition(finishCallback);
            return true;
        }

        return false;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
    }

    void setDepartingWindowContainerToken(
            @Nullable WindowContainerToken departingWindowContainerToken) {
        mDepartingWindowContainerToken = departingWindowContainerToken;
    }
}

