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

package com.android.wm.shell.transition;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;

import static com.android.wm.shell.transition.Transitions.TransitionObserver;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SingleInstanceRemoteListener;
import com.android.wm.shell.util.TransitionUtil;

/**
 * The {@link TransitionObserver} that observes for transitions involving the home
 * activity. It reports transitions to the caller via {@link IHomeTransitionListener}.
 */
public class HomeTransitionObserver implements TransitionObserver,
        RemoteCallable<HomeTransitionObserver> {
    private final SingleInstanceRemoteListener<HomeTransitionObserver, IHomeTransitionListener>
            mListener;

    private @NonNull final Context mContext;
    private @NonNull final ShellExecutor mMainExecutor;
    private @NonNull final Transitions mTransitions;

    public HomeTransitionObserver(@NonNull Context context,
            @NonNull ShellExecutor mainExecutor,
            @NonNull Transitions transitions) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mTransitions = transitions;

        mListener = new SingleInstanceRemoteListener<>(this,
                c -> mTransitions.registerObserver(this),
                c -> mTransitions.unregisterObserver(this));

    }

    @Override
    public void onTransitionReady(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        for (TransitionInfo.Change change : info.getChanges()) {
            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null || taskInfo.taskId == -1) {
                continue;
            }

            final int mode = change.getMode();
            if (taskInfo.getActivityType() == ACTIVITY_TYPE_HOME
                    && TransitionUtil.isOpenOrCloseMode(mode)) {
                mListener.call(l -> l.onHomeVisibilityChanged(TransitionUtil.isOpeningType(mode)));
            }
        }
    }

    @Override
    public void onTransitionStarting(@NonNull IBinder transition) {}

    @Override
    public void onTransitionMerged(@NonNull IBinder merged,
            @NonNull IBinder playing) {}

    @Override
    public void onTransitionFinished(@NonNull IBinder transition,
            boolean aborted) {}

    /**
     * Sets the home transition listener that receives any transitions resulting in a change of
     *
     */
    public void setHomeTransitionListener(IHomeTransitionListener listener) {
        if (listener != null) {
            mListener.register(listener);
        } else {
            mListener.unregister();
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    /**
     * Invalidates this controller, preventing future calls to send updates.
     */
    public void invalidate() {
        mTransitions.unregisterObserver(this);
    }
}
