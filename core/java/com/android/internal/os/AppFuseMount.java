/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.os;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.storage.IStorageManager;
import com.android.internal.util.Preconditions;

/**
 * Parcelable class representing AppFuse mount.
 * This conveys the result for IStorageManager#openProxyFileDescriptor.
 * @see IStorageManager#openProxyFileDescriptor
 */
public class AppFuseMount implements Parcelable {
    final public int mountPointId;
    final public ParcelFileDescriptor fd;

    /**
     * @param mountPointId Integer number for mount point that is unique in the lifetime of
     *     StorageManagerService.
     * @param fd File descriptor pointing /dev/fuse and tagged with the mount point.
     */
    public AppFuseMount(int mountPointId, ParcelFileDescriptor fd) {
        Preconditions.checkNotNull(fd);
        this.mountPointId = mountPointId;
        this.fd = fd;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.mountPointId);
        dest.writeParcelable(fd, flags);
    }

    public static final Parcelable.Creator<AppFuseMount> CREATOR =
            new Parcelable.Creator<AppFuseMount>() {
        @Override
        public AppFuseMount createFromParcel(Parcel in) {
            return new AppFuseMount(in.readInt(), in.readParcelable(null));
        }

        @Override
        public AppFuseMount[] newArray(int size) {
            return new AppFuseMount[size];
        }
    };
}
