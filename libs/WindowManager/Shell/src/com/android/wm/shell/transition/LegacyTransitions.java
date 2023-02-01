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
import android.os.RemoteException;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.IWindowContainerTransactionCallback;

/**
 * Utilities and interfaces for transition-like usage on top of the legacy app-transition and
 * synctransaction tools.
 */
public class LegacyTransitions {

    /**
     * Interface for a "legacy" transition. Effectively wraps a sync callback + remoteAnimation
     * into one callback.
     */
    public interface ILegacyTransition {
        /**
         * Called when both the associated sync transaction finishes and the remote animation is
         * ready.
         */
        void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback, SurfaceControl.Transaction t);
    }

    /**
     * Makes sure that a remote animation and corresponding sync callback are called together
     * such that the sync callback is called first. This assumes that both the callback receiver
     * and the remoteanimation are in the same process so that order is preserved on both ends.
     */
    public static class LegacyTransition {
        private final ILegacyTransition mLegacyTransition;
        private int mSyncId = -1;
        private SurfaceControl.Transaction mTransaction;
        private int mTransit;
        private RemoteAnimationTarget[] mApps;
        private RemoteAnimationTarget[] mWallpapers;
        private RemoteAnimationTarget[] mNonApps;
        private IRemoteAnimationFinishedCallback mFinishCallback = null;
        private boolean mCancelled = false;
        private final SyncCallback mSyncCallback = new SyncCallback();
        private final RemoteAnimationAdapter mAdapter =
                new RemoteAnimationAdapter(new RemoteAnimationWrapper(), 0, 0);

        public LegacyTransition(@WindowManager.TransitionType int type,
                @NonNull ILegacyTransition legacyTransition) {
            mLegacyTransition = legacyTransition;
            mTransit = type;
        }

        public @WindowManager.TransitionType int getType() {
            return mTransit;
        }

        public IWindowContainerTransactionCallback getSyncCallback() {
            return mSyncCallback;
        }

        public RemoteAnimationAdapter getAdapter() {
            return mAdapter;
        }

        private class SyncCallback extends IWindowContainerTransactionCallback.Stub {
            @Override
            public void onTransactionReady(int id, SurfaceControl.Transaction t)
                    throws RemoteException {
                mSyncId = id;
                mTransaction = t;
                checkApply();
            }
        }

        private class RemoteAnimationWrapper extends IRemoteAnimationRunner.Stub {
            @Override
            public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                    IRemoteAnimationFinishedCallback finishedCallback) throws RemoteException {
                mTransit = transit;
                mApps = apps;
                mWallpapers = wallpapers;
                mNonApps = nonApps;
                mFinishCallback = finishedCallback;
                checkApply();
            }

            @Override
            public void onAnimationCancelled(boolean isKeyguardOccluded) throws RemoteException {
                mCancelled = true;
                mApps = mWallpapers = mNonApps = null;
                checkApply();
            }
        }


        private void checkApply() throws RemoteException {
            if (mSyncId < 0 || (mFinishCallback == null && !mCancelled)) return;
            mLegacyTransition.onAnimationStart(mTransit, mApps, mWallpapers,
                    mNonApps, mFinishCallback, mTransaction);
        }
    }
}
