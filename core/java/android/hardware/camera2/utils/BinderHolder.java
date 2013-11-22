/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.camera2.utils;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.IBinder;

/**
 * @hide
 */
public class BinderHolder implements Parcelable {
    private IBinder mBinder = null;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mBinder);
    }

    public void readFromParcel(Parcel src) {
        mBinder = src.readStrongBinder();
    }

    public static final Parcelable.Creator<BinderHolder> CREATOR =
             new Parcelable.Creator<BinderHolder>() {
         @Override
         public BinderHolder createFromParcel(Parcel in) {
             return new BinderHolder(in);
         }

         @Override
         public BinderHolder[] newArray(int size) {
             return new BinderHolder[size];
         }
    };

    public IBinder getBinder() {
        return mBinder;
    }

    public void setBinder(IBinder binder) {
        mBinder = binder;
    }

    public BinderHolder() {}

    public BinderHolder(IBinder binder) {
        mBinder = binder;
    }

    private BinderHolder(Parcel in) {
        mBinder = in.readStrongBinder();
    }
}

