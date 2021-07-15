/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.SparseArray;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;

/**
 * Version of UserInfoHelper for testing. By default there is one user that starts in a running
 * state with a userId of 0;
 */
public class FakeUserInfoHelper extends UserInfoHelper {

    public static final int DEFAULT_USERID = 0;

    private final IntArray mRunningUserIds;
    private final SparseArray<IntArray> mProfiles;

    private int mCurrentUserId;

    public FakeUserInfoHelper() {
        mCurrentUserId = DEFAULT_USERID;
        mRunningUserIds = IntArray.wrap(new int[]{DEFAULT_USERID});
        mProfiles = new SparseArray<>();
    }

    public void startUser(int userId) {
        startUserInternal(userId, true);
    }

    private void startUserInternal(int userId, boolean alwaysDispatch) {
        int idx = mRunningUserIds.indexOf(userId);
        if (idx < 0) {
            mRunningUserIds.add(userId);
        } else if (!alwaysDispatch) {
            return;
        }

        dispatchOnUserStarted(userId);
    }

    public void stopUser(int userId) {
        int idx = mRunningUserIds.indexOf(userId);
        if (idx >= 0) {
            mRunningUserIds.remove(idx);
        }

        dispatchOnUserStopped(userId);
    }

    public void setCurrentUserId(int parentUser) {
        setCurrentUserIds(parentUser, new int[]{parentUser});
    }

    public void setCurrentUserIds(int parentUser, int[] currentProfileUserIds) {
        Preconditions.checkArgument(ArrayUtils.contains(currentProfileUserIds, parentUser));
        int oldUserId = mCurrentUserId;
        mCurrentUserId = parentUser;
        mProfiles.put(parentUser,
                IntArray.fromArray(currentProfileUserIds, currentProfileUserIds.length));

        // ensure all profiles are started if they didn't exist before...
        for (int userId : currentProfileUserIds) {
            startUserInternal(userId, false);
        }

        if (oldUserId != mCurrentUserId) {
            dispatchOnCurrentUserChanged(oldUserId, mCurrentUserId);
        }
    }

    @Override
    public int[] getRunningUserIds() {
        return mRunningUserIds.toArray();
    }

    @Override
    public boolean isCurrentUserId(int userId) {
        return ArrayUtils.contains(getProfileIds(mCurrentUserId), userId);
    }

    @Override
    public int getCurrentUserId() {
        return mCurrentUserId;
    }

    @Override
    protected int[] getProfileIds(int userId) {
        IntArray profiles = mProfiles.get(userId);
        if (profiles != null) {
            return profiles.toArray();
        } else {
            return new int[] {userId};
        }
    }

    @Override
    public void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {}
}
