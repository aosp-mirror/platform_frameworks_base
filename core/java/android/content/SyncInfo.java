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
import android.content.pm.RegisteredServicesCache;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;

/**
 * Information about the sync operation that is currently underway.
 */
public class SyncInfo implements Parcelable {
    /** @hide */
    public final int authorityId;

    /**
     * The {@link Account} that is currently being synced. Will be null if this sync is running via
     * a {@link SyncService}.
     */
    public final Account account;

    /**
     * The authority of the provider that is currently being synced. Will be null if this sync
     * is running via a {@link SyncService}.
     */
    public final String authority;

    /**
     * The {@link SyncService} that is targeted by this operation. Null if this sync is running via
     * a {@link AbstractThreadedSyncAdapter}. 
     */
    public final ComponentName service;

    /**
     * The start time of the current sync operation in milliseconds since boot.
     * This is represented in elapsed real time.
     * See {@link android.os.SystemClock#elapsedRealtime()}.
     */
    public final long startTime;

    /** @hide */
    public SyncInfo(int authorityId, Account account, String authority, ComponentName service,
            long startTime) {
        this.authorityId = authorityId;
        this.account = account;
        this.authority = authority;
        this.startTime = startTime;
        this.service = service;
    }

    /** @hide */
    public SyncInfo(SyncInfo other) {
        this.authorityId = other.authorityId;
        this.account = new Account(other.account.name, other.account.type);
        this.authority = other.authority;
        this.startTime = other.startTime;
        this.service = other.service;
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(authorityId);
        parcel.writeParcelable(account, flags);
        parcel.writeString(authority);
        parcel.writeLong(startTime);
        parcel.writeParcelable(service, flags);
        
    }

    /** @hide */
    SyncInfo(Parcel parcel) {
        authorityId = parcel.readInt();
        account = parcel.readParcelable(Account.class.getClassLoader());
        authority = parcel.readString();
        startTime = parcel.readLong();
        service = parcel.readParcelable(ComponentName.class.getClassLoader());
    }

    /** @hide */
    public static final Creator<SyncInfo> CREATOR = new Creator<SyncInfo>() {
        public SyncInfo createFromParcel(Parcel in) {
            return new SyncInfo(in);
        }

        public SyncInfo[] newArray(int size) {
            return new SyncInfo[size];
        }
    };
}
