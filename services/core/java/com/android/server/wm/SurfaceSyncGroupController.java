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

package com.android.server.wm;

import android.annotation.Nullable;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.window.AddToSurfaceSyncGroupResult;
import android.window.ISurfaceSyncGroupCompletedListener;
import android.window.ITransactionReadyCallback;
import android.window.SurfaceSyncGroup;

import com.android.internal.annotations.GuardedBy;

class SurfaceSyncGroupController {
    private static final String TAG = "SurfaceSyncGroupController";
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<IBinder, SurfaceSyncGroupData> mSurfaceSyncGroups = new ArrayMap<>();

    boolean addToSyncGroup(IBinder syncGroupToken, boolean parentSyncGroupMerge,
            @Nullable ISurfaceSyncGroupCompletedListener completedListener,
            AddToSurfaceSyncGroupResult outAddToSyncGroupResult) {
        SurfaceSyncGroup root;
        synchronized (mLock) {
            SurfaceSyncGroupData syncGroupData = mSurfaceSyncGroups.get(syncGroupToken);
            if (syncGroupData == null) {
                root = new SurfaceSyncGroup(TAG + "-" + syncGroupToken.hashCode());
                if (completedListener != null) {
                    root.addSyncCompleteCallback(Runnable::run, () -> {
                        try {
                            completedListener.onSurfaceSyncGroupComplete();
                        } catch (RemoteException e) {
                        }
                    });
                }
                mSurfaceSyncGroups.put(syncGroupToken,
                        new SurfaceSyncGroupData(Binder.getCallingUid(), root));
            } else {
                root = syncGroupData.mSurfaceSyncGroup;
            }
        }

        ITransactionReadyCallback callback =
                root.createTransactionReadyCallback(parentSyncGroupMerge);
        if (callback == null) {
            return false;
        }
        outAddToSyncGroupResult.mParentSyncGroup = root;
        outAddToSyncGroupResult.mTransactionReadyCallback = callback;
        return true;
    }

    void markSyncGroupReady(IBinder syncGroupToken) {
        final SurfaceSyncGroup root;
        synchronized (mLock) {
            SurfaceSyncGroupData syncGroupData = mSurfaceSyncGroups.get(syncGroupToken);
            if (syncGroupData == null) {
                throw new IllegalArgumentException(
                        "SurfaceSyncGroup Token has not been set up or has already been marked as"
                                + " ready");
            }
            if (syncGroupData.mOwningUid != Binder.getCallingUid()) {
                throw new IllegalArgumentException(
                        "Only process that created the SurfaceSyncGroup can call "
                                + "markSyncGroupReady");
            }
            root = syncGroupData.mSurfaceSyncGroup;
            mSurfaceSyncGroups.remove(syncGroupToken);
        }

        root.markSyncReady();
    }

    private static class SurfaceSyncGroupData {
        final int mOwningUid;
        final SurfaceSyncGroup mSurfaceSyncGroup;

        private SurfaceSyncGroupData(int owningUid, SurfaceSyncGroup surfaceSyncGroup) {
            mOwningUid = owningUid;
            mSurfaceSyncGroup = surfaceSyncGroup;
        }
    }
}
