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
    private RemoteTransition mRemote;

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
                + " transition %s for (#%d).", mRemote, info.getDebugId());

        final IBinder.DeathRecipient remoteDied = () -> {
            Log.e(Transitions.TAG, "Remote transition died, finishing");
            mMainExecutor.execute(
                    () -> finishCallback.onTransitionFinished(null /* wct */));
        };
        IRemoteTransitionFinishedCallback cb = new IRemoteTransitionFinishedCallback.Stub() {
            @Override
            public void onTransitionFinished(WindowContainerTransaction wct,
                    SurfaceControl.Transaction sct) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                        "Finished one-shot remote transition %s for (#%d).", mRemote,
                        info.getDebugId());
                if (mRemote.asBinder() != null) {
                    mRemote.asBinder().unlinkToDeath(remoteDied, 0 /* flags */);
                }
                if (sct != null) {
                    finishTransaction.merge(sct);
                }
                mMainExecutor.execute(() -> {
                    finishCallback.onTransitionFinished(wct);
                    mRemote = null;
                });
            }
        };
        Transitions.setRunningRemoteTransitionDelegate(mRemote.getAppThread());
        try {
            if (mRemote.asBinder() != null) {
                mRemote.asBinder().linkToDeath(remoteDied, 0 /* flags */);
            }
            // If the remote is actually in the same process, then make a copy of parameters since
            // remote impls assume that they have to clean-up native references.
            final SurfaceControl.Transaction remoteStartT = RemoteTransitionHandler.copyIfLocal(
                    startTransaction, mRemote.getRemoteTransition());
            final TransitionInfo remoteInfo =
                    remoteStartT == startTransaction ? info : info.localRemoteCopy();
            mRemote.getRemoteTransition().startAnimation(transition, remoteInfo, remoteStartT, cb);
            // assume that remote will apply the start transaction.
            startTransaction.clear();
        } catch (RemoteException e) {
            Log.e(Transitions.TAG, "Error running remote transition.", e);
            if (mRemote.asBinder() != null) {
                mRemote.asBinder().unlinkToDeath(remoteDied, 0 /* flags */);
            }
            finishCallback.onTransitionFinished(null /* wct */);
            mRemote = null;
        }
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Merging registered One-shot remote"
                + " transition %s for (#%d).", mRemote, info.getDebugId());
        IRemoteTransitionFinishedCallback cb = new IRemoteTransitionFinishedCallback.Stub() {
            @Override
            public void onTransitionFinished(WindowContainerTransaction wct,
                    SurfaceControl.Transaction sct) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                        "Finished merging one-shot remote transition %s for (#%d).", mRemote,
                        info.getDebugId());
                // We have merged, since we sent the transaction over binder, the one in this
                // process won't be cleared if the remote applied it. We don't actually know if the
                // remote applied the transaction, but applying twice will break surfaceflinger
                // so just assume the worst-case and clear the local transaction.
                t.clear();
                mMainExecutor.execute(() -> {
                    finishCallback.onTransitionFinished(wct);
                    mRemote = null;
                });
            }
        };
        try {
            // If the remote is actually in the same process, then make a copy of parameters since
            // remote impls assume that they have to clean-up native references.
            final SurfaceControl.Transaction remoteT =
                    RemoteTransitionHandler.copyIfLocal(t, mRemote.getRemoteTransition());
            final TransitionInfo remoteInfo = remoteT == t ? info : info.localRemoteCopy();
            mRemote.getRemoteTransition().mergeAnimation(
                    transition, remoteInfo, remoteT, mergeTarget, cb);
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

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @Nullable SurfaceControl.Transaction finishTransaction) {
        try {
            mRemote.getRemoteTransition().onTransitionConsumed(transition, aborted);
        } catch (RemoteException e) {
            Log.e(Transitions.TAG, "Error calling onTransitionConsumed()", e);
        }
    }

    @Override
    public String toString() {
        return "OneShotRemoteHandler:" + mRemote.getDebugName() + ":"
                + mRemote.getRemoteTransition();
    }
}
