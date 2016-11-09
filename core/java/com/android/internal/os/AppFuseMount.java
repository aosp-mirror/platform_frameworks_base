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
import java.io.File;

public class AppFuseMount implements Parcelable {
    final public File mountPoint;
    final public ParcelFileDescriptor fd;

    public AppFuseMount(File mountPoint, ParcelFileDescriptor fd) {
        this.mountPoint = mountPoint;
        this.fd = fd;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.mountPoint.getPath());
        dest.writeParcelable(fd, flags);
    }

    public static final Parcelable.Creator<AppFuseMount> CREATOR =
            new Parcelable.Creator<AppFuseMount>() {
        @Override
        public AppFuseMount createFromParcel(Parcel in) {
            return new AppFuseMount(new File(in.readString()), in.readParcelable(null));
        }

        @Override
        public AppFuseMount[] newArray(int size) {
            return new AppFuseMount[size];
        }
    };
}
