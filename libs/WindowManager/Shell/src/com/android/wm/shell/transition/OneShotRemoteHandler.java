/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

/**
 * Handler that forwards to a RemoteTransition. It is designed for one-shot use to attach a
 * specific remote animation to a specific transition.
 */
public class OneShotRemoteHandler implements Transitions.TransitionHandler {
    private final ShellExecutor mMainExecutor;

    /** The specific transition that this handler is associated with. Just for validation. */
    private IBinder mTransition = null;

    /** The remote to delegate animation to */
    private final RemoteTransition mRemote;

    public OneShotRemoteHandler(@NonNull ShellExecutor mainExecutor,
            @NonNull RemoteTransition remote) {
        mMainExecutor = mainExecutor;
        mRemote = remote;
    }

    public void setTransition(@NonNull IBinder transition) {
        mTransition = transition;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (mTransition != transition) return false;
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Using registered One-shot remote"
                + " transition %s for %s.", mRemote, transition);

        final IBinder.DeathRecipient remoteDied = () -> {
            Log.e(Transitions.TAG, "Remote transition died, finishing");
            mMainExecutor.execute(
                    () -> finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */));
        };
        IRemoteTransitionFinishedCallback cb = new IRemoteTransitionFinishedCallback.Stub() {
            @Override
            public void onTransitionFinished(WindowContainerTransaction wct,
                    SurfaceControl.Transaction sct) {
                if (mRemote.asBinder() != null) {
                    mRemote.asBinder().unlinkToDeath(remoteDied, 0 /* flags */);
                }
                mMainExecutor.execute(() -> {
                    if (sct != null) {
                        finishTransaction.merge(sct);
                    }
                    finishCallback.onTransitionFinished(wct, null /* wctCB */);
                });
            }
        };
        try {
            if (mRemote.asBinder() != null) {
                mRemote.asBinder().linkToDeath(remoteDied, 0 /* flags */);
            }
            mRemote.getRemoteTransition().startAnimation(transition, info, startTransaction, cb);
        } catch (RemoteException e) {
            Log.e(Transitions.TAG, "Error running remote transition.", e);
            if (mRemote.asBinder() != null) {
                mRemote.asBinder().unlinkToDeath(remoteDied, 0 /* flags */);
            }
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        }
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Using registered One-shot remote"
                + " transition %s for %s.", mRemote, transition);

        IRemoteTransitionFinishedCallback cb = new IRemoteTransitionFinishedCallback.Stub() {
            @Override
            public void onTransitionFinished(WindowContainerTransaction wct,
                    SurfaceControl.Transaction sct) {
                mMainExecutor.execute(
                        () -> finishCallback.onTransitionFinished(wct, null /* wctCB */));
            }
        };
        try {
            mRemote.getRemoteTransition().mergeAnimation(transition, info, t, mergeTarget, cb);
        } catch (RemoteException e) {
            Log.e(Transitions.TAG, "Error merging remote transition.", e);
        }
    }

    @Override
    @Nullable
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @Nullable TransitionRequestInfo request) {
        RemoteTransition remote = request.getRemoteTransition();
        IRemoteTransition iRemote = remote != null ? remote.getRemoteTransition() : null;
        if (iRemote != mRemote.getRemoteTransition()) return null;
        mTransition = transition;
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "RemoteTransition directly requested"
                + " for %s: %s", transition, remote);
        return new WindowContainerTransaction();
    }
}
