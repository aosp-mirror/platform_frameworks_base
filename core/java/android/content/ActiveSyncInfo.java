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

/** @hide */
public class ActiveSyncInfo {
    public final int authorityId;
    public final Account account;
    public final String authority;
    public final long startTime;
    
    ActiveSyncInfo(int authorityId, Account account, String authority,
            long startTime) {
        this.authorityId = authorityId;
        this.account = account;
        this.authority = authority;
        this.startTime = startTime;
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(authorityId);
        account.writeToParcel(parcel, 0);
        parcel.writeString(authority);
        parcel.writeLong(startTime);
    }

    ActiveSyncInfo(Parcel parcel) {
        authorityId = parcel.readInt();
        account = new Account(parcel);
        authority = parcel.readString();
        startTime = parcel.readLong();
    }
    
    public static final Creator<ActiveSyncInfo> CREATOR = new Creator<ActiveSyncInfo>() {
        public ActiveSyncInfo createFromParcel(Parcel in) {
            return new ActiveSyncInfo(in);
        }

        public ActiveSyncInfo[] newArray(int size) {
            return new ActiveSyncInfo[size];
        }
    };
}