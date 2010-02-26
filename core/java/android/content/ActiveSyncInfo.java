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

import android.accounts.Account;
import android.os.Parcel;
import android.os.Parcelable.Creator;

/**
 * Information about the sync operation that is currently underway.
 */
public class ActiveSyncInfo {
    /** @hide */
    private final int authorityId;
    /** @hide */
    private final Account account;
    /** @hide */
    private final String authority;
    /** @hide */
    private final long startTime;

    /**
     * Get the {@link Account} that is currently being synced.
     * @return the account
     */
    public Account getAccount() {
        return new Account(account.name, account.type);
    }

    /** @hide */
    public int getAuthorityId() {
        return authorityId;
    }

    /**
     * Get the authority of the provider that is currently being synced.
     * @return the authority
     */
    public String getAuthority() {
        return authority;
    }

    /**
     * Get the start time of the current sync operation. This is represented in elapsed real time.
     * See {@link android.os.SystemClock#elapsedRealtime()}.
     * @return the start time in milliseconds since boot
     */
    public long getStartTime() {
        return startTime;
    }

    /** @hide */
    ActiveSyncInfo(int authorityId, Account account, String authority,
            long startTime) {
        this.authorityId = authorityId;
        this.account = account;
        this.authority = authority;
        this.startTime = startTime;
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(authorityId);
        account.writeToParcel(parcel, 0);
        parcel.writeString(authority);
        parcel.writeLong(startTime);
    }

    /** @hide */
    ActiveSyncInfo(Parcel parcel) {
        authorityId = parcel.readInt();
        account = new Account(parcel);
        authority = parcel.readString();
        startTime = parcel.readLong();
    }

    /** @hide */
    public static final Creator<ActiveSyncInfo> CREATOR = new Creator<ActiveSyncInfo>() {
        public ActiveSyncInfo createFromParcel(Parcel in) {
            return new ActiveSyncInfo(in);
        }

        public ActiveSyncInfo[] newArray(int size) {
            return new ActiveSyncInfo[size];
        }
    };
}