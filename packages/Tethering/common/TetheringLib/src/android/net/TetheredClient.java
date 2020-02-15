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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Information on a tethered downstream client.
 * @hide
 */
@SystemApi
@SystemApi(client = MODULE_LIBRARIES)
@TestApi
public final class TetheredClient implements Parcelable {
    @NonNull
    private final MacAddress mMacAddress;
    @NonNull
    private final List<AddressInfo> mAddresses;
    // TODO: use an @IntDef here
    private final int mTetheringType;

    public TetheredClient(@NonNull MacAddress macAddress,
            @NonNull Collection<AddressInfo> addresses, int tetheringType) {
        mMacAddress = macAddress;
        mAddresses = new ArrayList<>(addresses);
        mTetheringType = tetheringType;
    }

    private TetheredClient(@NonNull Parcel in) {
        this(in.readParcelable(null), in.createTypedArrayList(AddressInfo.CREATOR), in.readInt());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mMacAddress, flags);
        dest.writeTypedList(mAddresses);
        dest.writeInt(mTetheringType);
    }

    @NonNull
    public MacAddress getMacAddress() {
        return mMacAddress;
    }

    @NonNull
    public List<AddressInfo> getAddresses() {
        return new ArrayList<>(mAddresses);
    }

    public int getTetheringType() {
        return mTetheringType;
    }

    /**
     * Return a new {@link TetheredClient} that has all the attributes of this instance, plus the
     * {@link AddressInfo} of the provided {@link TetheredClient}.
     *
     * <p>Duplicate addresses are removed.
     * @hide
     */
    public TetheredClient addAddresses(@NonNull TetheredClient other) {
        final LinkedHashSet<AddressInfo> newAddresses = new LinkedHashSet<>(
                mAddresses.size() + other.mAddresses.size());
        newAddresses.addAll(mAddresses);
        newAddresses.addAll(other.mAddresses);
        return new TetheredClient(mMacAddress, newAddresses, mTetheringType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMacAddress, mAddresses, mTetheringType);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof TetheredClient)) return false;
        final TetheredClient other = (TetheredClient) obj;
        return mMacAddress.equals(other.mMacAddress)
                && mAddresses.equals(other.mAddresses)
                && mTetheringType == other.mTetheringType;
    }

    /**
     * Information on an lease assigned to a tethered client.
     */
    public static final class AddressInfo implements Parcelable {
        @NonNull
        private final LinkAddress mAddress;
        @Nullable
        private final String mHostname;
        // TODO: use LinkAddress expiration time once it is supported
        private final long mExpirationTime;

        /** @hide */
        public AddressInfo(@NonNull LinkAddress address, @Nullable String hostname) {
            this(address, hostname, 0);
        }

        /** @hide */
        public AddressInfo(@NonNull LinkAddress address, String hostname, long expirationTime) {
            this.mAddress = address;
            this.mHostname = hostname;
            this.mExpirationTime = expirationTime;
        }

        private AddressInfo(Parcel in) {
            this(in.readParcelable(null),  in.readString(), in.readLong());
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeParcelable(mAddress, flags);
            dest.writeString(mHostname);
            dest.writeLong(mExpirationTime);
        }

        @NonNull
        public LinkAddress getAddress() {
            return mAddress;
        }

        @Nullable
        public String getHostname() {
            return mHostname;
        }

        /** @hide TODO: use expiration time in LinkAddress */
        public long getExpirationTime() {
            return mExpirationTime;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAddress, mHostname, mExpirationTime);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof AddressInfo)) return false;
            final AddressInfo other = (AddressInfo) obj;
            // Use .equals() for addresses as all changes, including address expiry changes,
            // should be included.
            return other.mAddress.equals(mAddress)
                    && Objects.equals(mHostname, other.mHostname)
                    && mExpirationTime == other.mExpirationTime;
        }

        @NonNull
        public static final Creator<AddressInfo> CREATOR = new Creator<AddressInfo>() {
            @NonNull
            @Override
            public AddressInfo createFromParcel(@NonNull Parcel in) {
                return new AddressInfo(in);
            }

            @NonNull
            @Override
            public AddressInfo[] newArray(int size) {
                return new AddressInfo[size];
            }
        };
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<TetheredClient> CREATOR = new Creator<TetheredClient>() {
        @NonNull
        @Override
        public TetheredClient createFromParcel(@NonNull Parcel in) {
            return new TetheredClient(in);
        }

        @NonNull
        @Override
        public TetheredClient[] newArray(int size) {
            return new TetheredClient[size];
        }
    };
}
