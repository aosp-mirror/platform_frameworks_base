/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.text.TextUtils;
import android.os.Parcelable;
import android.os.Parcel;

/**
 * Value type that represents a SyncAdapterType. This object overrides {@link #equals} and
 * {@link #hashCode}, making it suitable for use as the key of a {@link java.util.Map}
 */
public class SyncAdapterType implements Parcelable {
    public final String authority;
    public final String accountType;
    public final boolean userVisible;

    public SyncAdapterType(String authority, String accountType, boolean userVisible) {
        if (TextUtils.isEmpty(authority)) {
            throw new IllegalArgumentException("the authority must not be empty: " + authority);
        }
        if (TextUtils.isEmpty(accountType)) {
            throw new IllegalArgumentException("the accountType must not be empty: " + accountType);
        }
        this.authority = authority;
        this.accountType = accountType;
        this.userVisible = userVisible;
    }

    public static SyncAdapterType newKey(String authority, String accountType) {
        return new SyncAdapterType(authority, accountType, true);
    }

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof SyncAdapterType)) return false;
        final SyncAdapterType other = (SyncAdapterType)o;
        // don't include userVisible in the equality check
        return authority.equals(other.authority) && accountType.equals(other.accountType);
    }

    public int hashCode() {
        int result = 17;
        result = 31 * result + authority.hashCode();
        result = 31 * result + accountType.hashCode();
        // don't include userVisible in the hash
        return result;
    }

    public String toString() {
        return "SyncAdapterType {name=" + authority + ", type=" + accountType
                + ", userVisible=" + userVisible + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(authority);
        dest.writeString(accountType);
        dest.writeInt(userVisible ? 1 : 0);
    }

    public SyncAdapterType(Parcel source) {
        this(source.readString(), source.readString(), source.readInt() != 0);
    }

    public static final Creator<SyncAdapterType> CREATOR = new Creator<SyncAdapterType>() {
        public SyncAdapterType createFromParcel(Parcel source) {
            return new SyncAdapterType(source);
        }

        public SyncAdapterType[] newArray(int size) {
            return new SyncAdapterType[size];
        }
    };
}