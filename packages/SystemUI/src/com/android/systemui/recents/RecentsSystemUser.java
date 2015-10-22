/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

/**
 * An implementation of the system user's Recents interface to be called remotely by secondary
 * users.
 */
public class RecentsSystemUser extends IRecentsSystemUserCallbacks.Stub {

    private static final String TAG = "RecentsSystemUser";

    private Context mContext;
    private RecentsImpl mImpl;
    private final SparseArray<IRecentsNonSystemUserCallbacks> mNonSystemUserRecents =
            new SparseArray<>();

    public RecentsSystemUser(Context context, RecentsImpl impl) {
        mContext = context;
        mImpl = impl;
    }

    @Override
    public void registerNonSystemUserCallbacks(final IBinder nonSystemUserCallbacks, int userId) {
        try {
            final IRecentsNonSystemUserCallbacks callback =
                    IRecentsNonSystemUserCallbacks.Stub.asInterface(nonSystemUserCallbacks);
            nonSystemUserCallbacks.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    mNonSystemUserRecents.removeAt(mNonSystemUserRecents.indexOfValue(callback));
                }
            }, 0);
            mNonSystemUserRecents.put(userId, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register NonSystemUserCallbacks", e);
        }
    }

    public IRecentsNonSystemUserCallbacks getNonSystemUserRecentsForUser(int userId) {
        return mNonSystemUserRecents.get(userId);
    }

    @Override
    public void updateRecentsVisibility(boolean visible) {
        mImpl.onVisibilityChanged(mContext, visible);
    }

    @Override
    public void startScreenPinning() {
        mImpl.onStartScreenPinning(mContext);
    }
}
