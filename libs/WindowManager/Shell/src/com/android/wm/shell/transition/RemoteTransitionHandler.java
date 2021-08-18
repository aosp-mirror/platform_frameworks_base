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
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.RemoteTransition;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;

/**
 * Handler that deals with RemoteTransitions. It will only request to handle a transition
 * if the request includes a specific remote.
 */
public class RemoteTransitionHandler implements Transitions.TransitionHandler {
    private static final String TAG = "RemoteTransitionHandler";

    private final ShellExecutor mMainExecutor;

    /** Includes remotes explicitly requested by, eg, ActivityOptions */
    private final ArrayMap<IBinder, RemoteTransition> mRequestedRemotes = new ArrayMap<>();

    /** Ordered by specificity. Last filters will be checked first */
    private final ArrayList<Pair<TransitionFilter, RemoteTransition>> mFilters =
            new ArrayList<>();

    private final ArrayMap<IBinder, RemoteDeathHandler> mDeathHandlers = new ArrayMap<>();

    RemoteTransitionHandler(@NonNull ShellExecutor mainExecutor) {
        mMainExecutor = mainExecutor;
    }

    void addFiltered(TransitionFilter filter, RemoteTransition remote) {
        handleDeath(remote.asBinder(), null /* finishCallback */);
        mFilters.add(new Pair<>(filter, remote));
    }

    void removeFiltered(RemoteTransition remote) {
        boolean removed = false;
        for (int i = mFilters.size() - 1; i >= 0; --i) {
            if (mFilters.get(i).second == remote) {
                mFilters.remove(i);
                removed = true;
            }
        }
        if (removed) {
            unhandleDeath(remote.asBinder(), null /* finishCallback */);
        }
    }

    @Override
    public void onTransitionMerged(@NonNull IBinder transition) {
        mRequestedRemotes.remove(transition);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        RemoteTransition pendingRemote = mRequestedRemotes.get(transition);
        if (pendingRemote == null) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition %s doesn't have "
                    + "explicit remote, search filters for match for %s", transition, info);
            // If no explicit remote, search filters until one matches
            for (int i = mFilters.size() - 1; i >= 0; --i) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Checking filter %s",
                        mFilters.get(i));
                if (mFilters.get(i).first.matches(info)) {
                    Slog.d(TAG, "Found filter" + mFilters.get(i));
                    pendingRemote = mFilters.get(i).second;
                    // Add to requested list so that it can be found for merge requests.
                    mRequestedRemotes.put(transition, pendingRemote);
                    break;
                }
            }
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Delegate animation for %s to %s",
                transition, pendingRemote);

        if (pendingRemote == null) return false;

        final RemoteTransition remote = pendingRemote;
        IRemoteTransitionFinishedCallback cb = new IRemoteTransitionFinishedCallback.Stub() {
            @Override
            public void onTransitionFinished(WindowContainerTransaction wct,
                    SurfaceControl.Transaction sct) {
                unhandleDeath(remote.asBinder(), finishCallback);
                mMainExecutor.execute(() -> {
                    if (sct != null) {
                        finishTransaction.merge(sct);
                    }
                    mRequestedRemotes.remove(transition);
                    finishCallback.onTransitionFinished(wct, null /* wctCB */);
                });
            }
        };
        try {
            handleDeath(remote.asBinder(), finishCallback);
            remote.getRemoteTransition().startAnimation(transition, info, startTransaction, cb);
        } catch (RemoteException e) {
            Log.e(Transitions.TAG, "Error running remote transition.", e);
            unhandleDeath(remote.asBinder(), finishCallback);
            mRequestedRemotes.remove(transition);
            mMainExecutor.execute(
                    () -> finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */));
        }
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final IRemoteTransition remote = mRequestedRemotes.get(mergeTarget).getRemoteTransition();
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Attempt merge %s into %s",
                transition, remote);
        if (remote == null) return;

        IRemoteTransitionFinishedCallback cb = new IRemoteTransitionFinishedCallback.Stub() {
            @Override
            public void onTransitionFinished(WindowContainerTransaction wct,
                    SurfaceControl.Transaction sct) {
                mMainExecutor.execute(() -> {
                    if (!mRequestedRemotes.containsKey(mergeTarget)) {
                        Log.e(TAG, "Merged transition finished after it's mergeTarget (the "
                                + "transition it was supposed to merge into). This usually means "
                                + "that the mergeTarget's RemoteTransition impl erroneously "
                                + "accepted/ran the merge request after finishing the mergeTarget");
                    }
                    finishCallback.onTransitionFinished(wct, null /* wctCB */);
                });
            }
        };
        try {
            remote.mergeAnimation(transition, info, t, mergeTarget, cb);
        } catch (RemoteException e) {
            Log.e(Transitions.TAG, "Error attempting to merge remote transition.", e);
        }
    }

    @Override
    @Nullable
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @Nullable TransitionRequestInfo request) {
        RemoteTransition remote = request.getRemoteTransition();
        if (remote == null) return null;
        mRequestedRemotes.put(transition, remote);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "RemoteTransition directly requested"
                + " for %s: %s", transition, remote);
        return new WindowContainerTransaction();
    }

    private void handleDeath(@NonNull IBinder remote,
            @Nullable Transitions.TransitionFinishCallback finishCallback) {
        synchronized (mDeathHandlers) {
            RemoteDeathHandler deathHandler = mDeathHandlers.get(remote);
            if (deathHandler == null) {
                deathHandler = new RemoteDeathHandler(remote);
                try {
                    remote.linkToDeath(deathHandler, 0 /* flags */);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to link to death");
                    return;
                }
                mDeathHandlers.put(remote, deathHandler);
            }
            deathHandler.addUser(finishCallback);
        }
    }

    private void unhandleDeath(@NonNull IBinder remote,
            @Nullable Transitions.TransitionFinishCallback finishCallback) {
        synchronized (mDeathHandlers) {
            RemoteDeathHandler deathHandler = mDeathHandlers.get(remote);
            if (deathHandler == null) return;
            deathHandler.removeUser(finishCallback);
            if (deathHandler.getUserCount() == 0) {
                if (!deathHandler.mPendingFinishCallbacks.isEmpty()) {
                    throw new IllegalStateException("Unhandling death for binder that still has"
                            + " pending finishCallback(s).");
                }
                remote.unlinkToDeath(deathHandler, 0 /* flags */);
                mDeathHandlers.remove(remote);
            }
        }
    }

    /** NOTE: binder deaths can alter the filter order */
    private class RemoteDeathHandler implements IBinder.DeathRecipient {
        private final IBinder mRemote;
        private final ArrayList<Transitions.TransitionFinishCallback> mPendingFinishCallbacks =
                new ArrayList<>();
        private int mUsers = 0;

        RemoteDeathHandler(IBinder remote) {
            mRemote = remote;
        }

        void addUser(@Nullable Transitions.TransitionFinishCallback finishCallback) {
            if (finishCallback != null) {
                mPendingFinishCallbacks.add(finishCallback);
            }
            ++mUsers;
        }

        void removeUser(@Nullable Transitions.TransitionFinishCallback finishCallback) {
            if (finishCallback != null) {
                mPendingFinishCallbacks.remove(finishCallback);
            }
            --mUsers;
        }

        int getUserCount() {
            return mUsers;
        }

        @Override
        @BinderThread
        public void binderDied() {
            mMainExecutor.execute(() -> {
                for (int i = mFilters.size() - 1; i >= 0; --i) {
                    if (mRemote.equals(mFilters.get(i).second.asBinder())) {
                        mFilters.remove(i);
                    }
                }
                for (int i = mRequestedRemotes.size() - 1; i >= 0; --i) {
                    if (mRemote.equals(mRequestedRemotes.valueAt(i).asBinder())) {
                        mRequestedRemotes.removeAt(i);
                    }
                }
                for (int i = mPendingFinishCallbacks.size() - 1; i >= 0; --i) {
                    mPendingFinishCallbacks.get(i).onTransitionFinished(
                            null /* wct */, null /* wctCB */);
                }
                mPendingFinishCallbacks.clear();
            });
        }
    }
}
