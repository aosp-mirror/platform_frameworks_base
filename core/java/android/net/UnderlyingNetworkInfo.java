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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A lightweight container used to carry information on the networks that underly a given
 * virtual network.
 *
 * @hide
 */
public final class UnderlyingNetworkInfo implements Parcelable {
    /** The owner of this network. */
    public final int ownerUid;
    /** The interface name of this network. */
    @NonNull
    public final String iface;
    /** The names of the interfaces underlying this network. */
    @NonNull
    public final List<String> underlyingIfaces;

    public UnderlyingNetworkInfo(int ownerUid, @NonNull String iface,
            @NonNull List<String> underlyingIfaces) {
        Objects.requireNonNull(iface);
        Objects.requireNonNull(underlyingIfaces);
        this.ownerUid = ownerUid;
        this.iface = iface;
        this.underlyingIfaces = underlyingIfaces;
    }

    private UnderlyingNetworkInfo(@NonNull Parcel in) {
        this.ownerUid = in.readInt();
        this.iface = in.readString();
        this.underlyingIfaces = new ArrayList<>();
        in.readList(this.underlyingIfaces, null /*classLoader*/);
    }

    @Override
    public String toString() {
        return "UnderlyingNetworkInfo{"
                + "ownerUid=" + ownerUid
                + ", iface='" + iface + '\''
                + ", underlyingIfaces='" + underlyingIfaces.toString() + '\''
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(ownerUid);
        dest.writeString(iface);
        dest.writeList(underlyingIfaces);
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
        return ownerUid == that.ownerUid
                && Objects.equals(iface, that.iface)
                && Objects.equals(underlyingIfaces, that.underlyingIfaces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerUid, iface, underlyingIfaces);
    }
}
