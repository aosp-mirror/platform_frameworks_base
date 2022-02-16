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

import android.accounts.Account;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Value type that contains information about a periodic sync.
 */
public class PeriodicSync implements Parcelable {
    /** The account to be synced. Can be null. */
    public final Account account;
    /** The authority of the sync. Can be null. */
    public final String authority;
    /** Any extras that parameters that are to be passed to the sync adapter. */
    public final Bundle extras;
    /** How frequently the sync should be scheduled, in seconds. Kept around for API purposes. */
    public final long period;
    /**
     * How much flexibility can be taken in scheduling the sync, in seconds.
     * {@hide}
     */
    public final long flexTime;

      /**
       * Creates a new PeriodicSync, copying the Bundle. This constructor is no longer used.
       */
    public PeriodicSync(Account account, String authority, Bundle extras, long periodInSeconds) {
        this.account = account;
        this.authority = authority;
        if (extras == null) {
            this.extras = new Bundle();
        } else {
            this.extras = new Bundle(extras);
        }
        this.period = periodInSeconds;
        // Old API uses default flex time. No-one should be using this ctor anyway.
        this.flexTime = 0L;
    }

    /**
     * Create a copy of a periodic sync.
     * {@hide}
     */
    public PeriodicSync(PeriodicSync other) {
        this.account = other.account;
        this.authority = other.authority;
        this.extras = new Bundle(other.extras);
        this.period = other.period;
        this.flexTime = other.flexTime;
    }

    /**
     * A PeriodicSync for a sync with a specified provider.
     * {@hide}
     */
    public PeriodicSync(Account account, String authority, Bundle extras,
            long period, long flexTime) {
        this.account = account;
        this.authority = authority;
        this.extras = new Bundle(extras);
        this.period = period;
        this.flexTime = flexTime;
    }

    private PeriodicSync(Parcel in) {
        this.account = in.readParcelable(null);
        this.authority = in.readString();
        this.extras = in.readBundle();
        this.period = in.readLong();
        this.flexTime = in.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(account, flags);
        dest.writeString(authority);
        dest.writeBundle(extras);
        dest.writeLong(period);
        dest.writeLong(flexTime);
    }

    public static final @android.annotation.NonNull Creator<PeriodicSync> CREATOR = new Creator<PeriodicSync>() {
        @Override
        public PeriodicSync createFromParcel(Parcel source) {
            return new PeriodicSync(source);
        }

        @Override
        public PeriodicSync[] newArray(int size) {
            return new PeriodicSync[size];
        }
    };

    @Override
    public boolean equals(@Nullable Object o) {
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
                && syncExtrasEquals(extras, other.extras);
    }

    /**
     * Periodic sync extra comparison function.
     * {@hide}
     */
    public static boolean syncExtrasEquals(Bundle b1, Bundle b2) {
        if (b1.size() != b2.size()) {
            return false;
        }
        if (b1.isEmpty()) {
            return true;
        }
        for (String key : b1.keySet()) {
            if (!b2.containsKey(key)) {
                return false;
            }
            // Null check. According to ContentResolver#validateSyncExtrasBundle null-valued keys
            // are allowed in the bundle.
            if (!Objects.equals(b1.get(key), b2.get(key))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "account: " + account +
               ", authority: " + authority +
               ". period: " + period + "s " +
               ", flex: " + flexTime;
    }
}
