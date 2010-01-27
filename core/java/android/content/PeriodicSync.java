/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.Parcelable;
import android.os.Bundle;
import android.os.Parcel;
import android.accounts.Account;

/**
 * Value type that contains information about a periodic sync. Is parcelable, making it suitable
 * for passing in an IPC.
 */
public class PeriodicSync implements Parcelable {
    /** The account to be synced */
    public final Account account;
    /** The authority of the sync */
    public final String authority;
    /** Any extras that parameters that are to be passed to the sync adapter. */
    public final Bundle extras;
    /** How frequently the sync should be scheduled, in seconds. */
    public final long period;

    /** Creates a new PeriodicSync, copying the Bundle */
    public PeriodicSync(Account account, String authority, Bundle extras, long period) {
        this.account = account;
        this.authority = authority;
        this.extras = new Bundle(extras);
        this.period = period;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        account.writeToParcel(dest, flags);
        dest.writeString(authority);
        dest.writeBundle(extras);
        dest.writeLong(period);
    }

    public static final Creator<PeriodicSync> CREATOR = new Creator<PeriodicSync>() {
        public PeriodicSync createFromParcel(Parcel source) {
            return new PeriodicSync(Account.CREATOR.createFromParcel(source),
                    source.readString(), source.readBundle(), source.readLong());
        }

        public PeriodicSync[] newArray(int size) {
            return new PeriodicSync[size];
        }
    };

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof PeriodicSync)) {
            return false;
        }

        final PeriodicSync other = (PeriodicSync) o;

        return account.equals(other.account)
                && authority.equals(other.authority)
                && period == other.period
                && SyncStorageEngine.equals(extras, other.extras);
    }
}
