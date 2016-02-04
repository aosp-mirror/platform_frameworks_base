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

package com.android.mtp;

import android.mtp.MtpStorageInfo;

import com.android.internal.annotations.VisibleForTesting;

class MtpRoot {
    final int mDeviceId;
    final int mStorageId;
    final String mDescription;
    final long mFreeSpace;
    final long mMaxCapacity;
    final String mVolumeIdentifier;

    @VisibleForTesting
    MtpRoot(int deviceId,
            int storageId,
            String description,
            long freeSpace,
            long maxCapacity,
            String volumeIdentifier) {
        mDeviceId = deviceId;
        mStorageId = storageId;
        mDescription = description;
        mFreeSpace = freeSpace;
        mMaxCapacity = maxCapacity;
        mVolumeIdentifier = volumeIdentifier;
    }

    MtpRoot(int deviceId, MtpStorageInfo storageInfo) {
        mDeviceId = deviceId;
        mStorageId = storageInfo.getStorageId();
        mDescription = storageInfo.getDescription();
        mFreeSpace = storageInfo.getFreeSpace();
        mMaxCapacity = storageInfo.getMaxCapacity();
        mVolumeIdentifier = storageInfo.getVolumeIdentifier();
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof MtpRoot))
            return false;
        final MtpRoot other = (MtpRoot) object;
        return mDeviceId == other.mDeviceId &&
                mStorageId == other.mStorageId &&
                mDescription.equals(other.mDescription) &&
                mFreeSpace == other.mFreeSpace &&
                mMaxCapacity == other.mMaxCapacity &&
                mVolumeIdentifier.equals(other.mVolumeIdentifier);
    }

    @Override
    public int hashCode() {
        return mDeviceId ^ mStorageId ^ mDescription.hashCode() ^
                ((int) mFreeSpace) ^ ((int) mMaxCapacity) ^ mVolumeIdentifier.hashCode();
    }

    @Override
    public String toString() {
        return "MtpRoot{Name: " + mDescription + "}";
    }
}
