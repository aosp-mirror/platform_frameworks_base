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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A lightweight container used to carry information on the networks that underly a given
 * virtual network.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class UnderlyingNetworkInfo implements Parcelable {
    /** The owner of this network. */
    private final int mOwnerUid;

    /** The interface name of this network. */
    @NonNull
    private final String mIface;

    /** The names of the interfaces underlying this network. */
    @NonNull
    private final List<String> mUnderlyingIfaces;

    public UnderlyingNetworkInfo(int ownerUid, @NonNull String iface,
            @NonNull List<String> underlyingIfaces) {
        Objects.requireNonNull(iface);
        Objects.requireNonNull(underlyingIfaces);
        mOwnerUid = ownerUid;
        mIface = iface;
        mUnderlyingIfaces = Collections.unmodifiableList(new ArrayList<>(underlyingIfaces));
    }

    private UnderlyingNetworkInfo(@NonNull Parcel in) {
        mOwnerUid = in.readInt();
        mIface = in.readString();
        List<String> underlyingIfaces = new ArrayList<>();
        in.readList(underlyingIfaces, null /*classLoader*/);
        mUnderlyingIfaces = Collections.unmodifiableList(underlyingIfaces);
    }

    /** Get the owner of this network. */
    public int getOwnerUid() {
        return mOwnerUid;
    }

    /** Get the interface name of this network. */
    @NonNull
    public String getInterface() {
        return mIface;
    }

    /** Get the names of the interfaces underlying this network. */
    @NonNull
    public List<String> getUnderlyingInterfaces() {
        return mUnderlyingIfaces;
    }

    @Override
    public String toString() {
        return "UnderlyingNetworkInfo{"
                + "ownerUid=" + mOwnerUid
                + ", iface='" + mIface + '\''
                + ", underlyingIfaces='" + mUnderlyingIfaces.toString() + '\''
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mOwnerUid);
        dest.writeString(mIface);
        dest.writeList(mUnderlyingIfaces);
    }

    @NonNull
    public static final Parcelable.Creator<UnderlyingNetworkInfo> CREATOR =
            new Parcelable.Creator<UnderlyingNetworkInfo>() {
        @NonNull
        @Override
        public UnderlyingNetworkInfo createFromParcel(@NonNull Parcel in) {
            return new UnderlyingNetworkInfo(in);
        }

        @NonNull
        @Override
        public UnderlyingNetworkInfo[] newArray(int size) {
            return new UnderlyingNetworkInfo[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UnderlyingNetworkInfo)) return false;
        final UnderlyingNetworkInfo that = (UnderlyingNetworkInfo) o;
        return mOwnerUid == that.getOwnerUid()
                && Objects.equals(mIface, that.getInterface())
                && Objects.equals(mUnderlyingIfaces, that.getUnderlyingInterfaces());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mOwnerUid, mIface, mUnderlyingIfaces);
    }
}
