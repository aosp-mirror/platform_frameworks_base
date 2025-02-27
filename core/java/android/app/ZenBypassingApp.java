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

package android.app;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * @hide
 */
public final class ZenBypassingApp implements Parcelable {

    @NonNull private String mPkg;
    private boolean mAllChannelsBypass;


    public ZenBypassingApp(@NonNull String pkg, boolean allChannelsBypass) {
        mPkg = pkg;
        mAllChannelsBypass = allChannelsBypass;
    }

    public ZenBypassingApp(Parcel source) {
        mPkg = source.readString();
        mAllChannelsBypass = source.readBoolean();
    }

    @NonNull
    public String getPkg() {
        return mPkg;
    }

    public boolean doAllChannelsBypass() {
        return mAllChannelsBypass;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mPkg);
        dest.writeBoolean(mAllChannelsBypass);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ZenBypassingApp> CREATOR
            = new Parcelable.Creator<ZenBypassingApp>() {
        @Override
        public ZenBypassingApp createFromParcel(Parcel source) {
            return new ZenBypassingApp(source);
        }
        @Override
        public ZenBypassingApp[] newArray(int size) {
            return new ZenBypassingApp[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ZenBypassingApp)) return false;
        ZenBypassingApp that = (ZenBypassingApp) o;
        return mAllChannelsBypass == that.mAllChannelsBypass && Objects.equals(mPkg,
                that.mPkg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPkg, mAllChannelsBypass);
    }

    @Override
    public String toString() {
        return "ZenBypassingApp{" +
                "mPkg='" + mPkg + '\'' +
                ", mAllChannelsBypass=" + mAllChannelsBypass +
                '}';
    }
}
