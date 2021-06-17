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

package com.android.server.biometrics.sensors;

import android.util.SparseArray;

/**
 * Tracks biometric performance across sensors and users.
 */
public class PerformanceTracker {

    private static final String TAG = "PerformanceTracker";
    // Keyed by SensorId
    private static SparseArray<PerformanceTracker> sTrackers;

    public static PerformanceTracker getInstanceForSensorId(int sensorId) {
        if (sTrackers == null) {
            sTrackers = new SparseArray<>();
        }

        if (!sTrackers.contains(sensorId)) {
            sTrackers.put(sensorId, new PerformanceTracker());
        }
        return sTrackers.get(sensorId);
    }

    private static class Info {
        int mAccept; // number of accepted biometrics
        int mReject; // number of rejected biometrics
        int mAcquire; // total number of acquisitions.

        int mAcceptCrypto;
        int mRejectCrypto;
        int mAcquireCrypto;

        int mTimedLockout; // total number of lockouts
        int mPermanentLockout; // total number of permanent lockouts
    }

    // Keyed by UserId
    private final SparseArray<Info> mAllUsersInfo;
    private int mHALDeathCount;

    private PerformanceTracker() {
        mAllUsersInfo = new SparseArray<>();
    }

    private void createUserEntryIfNecessary(int userId) {
        if (!mAllUsersInfo.contains(userId)) {
            mAllUsersInfo.put(userId, new Info());
        }
    }

    public void incrementAuthForUser(int userId, boolean accepted) {
        createUserEntryIfNecessary(userId);

        if (accepted) {
            mAllUsersInfo.get(userId).mAccept++;
        } else {
            mAllUsersInfo.get(userId).mReject++;
        }
    }

    void incrementCryptoAuthForUser(int userId, boolean accepted) {
        createUserEntryIfNecessary(userId);

        if (accepted) {
            mAllUsersInfo.get(userId).mAcceptCrypto++;
        } else {
            mAllUsersInfo.get(userId).mRejectCrypto++;
        }
    }

    void incrementAcquireForUser(int userId, boolean isCrypto) {
        createUserEntryIfNecessary(userId);

        if (isCrypto) {
            mAllUsersInfo.get(userId).mAcquireCrypto++;
        } else {
            mAllUsersInfo.get(userId).mAcquire++;
        }
    }

    void incrementTimedLockoutForUser(int userId) {
        createUserEntryIfNecessary(userId);

        mAllUsersInfo.get(userId).mTimedLockout++;
    }

    void incrementPermanentLockoutForUser(int userId) {
        createUserEntryIfNecessary(userId);

        mAllUsersInfo.get(userId).mPermanentLockout++;
    }

    public void incrementHALDeathCount() {
        mHALDeathCount++;
    }

    public void clear() {
        mAllUsersInfo.clear();
        mHALDeathCount = 0;
    }

    public int getAcceptForUser(int userId) {
        return mAllUsersInfo.contains(userId) ? mAllUsersInfo.get(userId).mAccept : 0;
    }

    public int getRejectForUser(int userId) {
        return mAllUsersInfo.contains(userId) ? mAllUsersInfo.get(userId).mReject : 0;
    }

    public int getAcquireForUser(int userId) {
        return mAllUsersInfo.contains(userId) ? mAllUsersInfo.get(userId).mAcquire : 0;
    }

    public int getAcceptCryptoForUser(int userId) {
        return mAllUsersInfo.contains(userId) ? mAllUsersInfo.get(userId).mAcceptCrypto : 0;
    }

    public int getRejectCryptoForUser(int userId) {
        return mAllUsersInfo.contains(userId) ? mAllUsersInfo.get(userId).mRejectCrypto : 0;
    }

    public int getAcquireCryptoForUser(int userId) {
        return mAllUsersInfo.contains(userId) ? mAllUsersInfo.get(userId).mAcquireCrypto : 0;
    }

    public int getTimedLockoutForUser(int userId) {
        return mAllUsersInfo.contains(userId) ? mAllUsersInfo.get(userId).mTimedLockout : 0;
    }

    public int getPermanentLockoutForUser(int userId) {
        return mAllUsersInfo.contains(userId) ? mAllUsersInfo.get(userId).mPermanentLockout : 0;
    }

    public int getHALDeathCount() {
        return mHALDeathCount;
    }
}
