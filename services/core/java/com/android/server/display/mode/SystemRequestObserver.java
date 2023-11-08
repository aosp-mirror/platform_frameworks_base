/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.mode;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.Map;

/**
 * SystemRequestObserver responsible for handling system requests to filter allowable display
 * modes
 */
class SystemRequestObserver {
    private static final String TAG = "SystemRequestObserver";

    private final VotesStorage mVotesStorage;

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            // noop, binderDied(@NonNull IBinder who) is overridden
        }
        @Override
        public void binderDied(@NonNull IBinder who) {
            removeSystemRequestedVotes(who);
            who.unlinkToDeath(mDeathRecipient, 0);
        }
    };

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<IBinder, SparseArray<int[]>> mDisplaysRestrictions = new HashMap<>();

    SystemRequestObserver(VotesStorage storage) {
        mVotesStorage = storage;
    }

    void requestDisplayModes(IBinder token, int displayId, @Nullable int[] modeIds) {
        if (modeIds == null) {
            removeSystemRequestedVote(token, displayId);
        } else {
            addSystemRequestedVote(token, displayId, modeIds);
        }
    }

    private void addSystemRequestedVote(IBinder token, int displayId, int[] modeIds) {
        try {
            boolean needLinkToDeath = false;
            synchronized (mLock) {
                SparseArray<int[]> existingRestrictionForBinder = mDisplaysRestrictions.get(token);
                if (existingRestrictionForBinder == null) {
                    needLinkToDeath = true;
                    existingRestrictionForBinder = new SparseArray<>();
                    mDisplaysRestrictions.put(token, existingRestrictionForBinder);
                }
                existingRestrictionForBinder.put(displayId, modeIds);

                // aggregate modes for display and update vote storage
            }
            if (needLinkToDeath) {
                token.linkToDeath(mDeathRecipient, 0);
            }
        } catch (RemoteException re) {
            removeSystemRequestedVotes(token);
        }
    }

    private void removeSystemRequestedVote(IBinder token, int displayId) {
        boolean needToUnlink = false;
        synchronized (mLock) {
            SparseArray<int[]> existingRestrictionForBinder = mDisplaysRestrictions.get(token);
            if (existingRestrictionForBinder != null) {
                existingRestrictionForBinder.remove(displayId);
                needToUnlink = existingRestrictionForBinder.size() == 0;

                // aggregate modes for display and update vote storage
            }
        }
        if (needToUnlink) {
            token.unlinkToDeath(mDeathRecipient, 0);
        }
    }

    private void removeSystemRequestedVotes(IBinder token) {
        synchronized (mLock) {
            mDisplaysRestrictions.remove(token);

            // aggregate modes for display and update vote storage
        }
    }
}
