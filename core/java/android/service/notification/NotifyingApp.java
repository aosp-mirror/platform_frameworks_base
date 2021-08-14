/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.notification;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * @hide
 */
public final class NotifyingApp implements Parcelable, Comparable<NotifyingApp> {

    private int mUserId;
    private String mPkg;
    private long mLastNotified;

    public NotifyingApp() {}

    protected NotifyingApp(Parcel in) {
        mUserId = in.readInt();
        mPkg = in.readString();
        mLastNotified = in.readLong();
    }

    public int getUserId() {
        return mUserId;
    }

    /**
     * Sets the userid of the package that sent the notification. Returns self.
     */
    public NotifyingApp setUserId(int mUserId) {
        this.mUserId = mUserId;
        return this;
    }

    public String getPackage() {
        return mPkg;
    }

    /**
     * Sets the package that sent the notification. Returns self.
     */
    public NotifyingApp setPackage(@NonNull String mPkg) {
        this.mPkg = mPkg;
        return this;
    }

    public long getLastNotified() {
        return mLastNotified;
    }

    /**
     * Sets the time the notification was originally sent. Returns self.
     */
    public NotifyingApp setLastNotified(long mLastNotified) {
        this.mLastNotified = mLastNotified;
        return this;
    }

    public static final @NonNull Creator<NotifyingApp> CREATOR = new Creator<NotifyingApp>() {
        @Override
        public NotifyingApp createFromParcel(Parcel in) {
            return new NotifyingApp(in);
        }

        @Override
        public NotifyingApp[] newArray(int size) {
            return new NotifyingApp[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUserId);
        dest.writeString(mPkg);
        dest.writeLong(mLastNotified);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NotifyingApp that = (NotifyingApp) o;
        return getUserId() == that.getUserId()
                && getLastNotified() == that.getLastNotified()
                && Objects.equals(mPkg, that.mPkg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getUserId(), mPkg, getLastNotified());
    }

    /**
     * Sorts notifying apps from newest last notified date to oldest.
     */
    @Override
    public int compareTo(NotifyingApp o) {
        if (getLastNotified() == o.getLastNotified()) {
            if (getUserId() == o.getUserId()) {
                return getPackage().compareTo(o.getPackage());
            }
            return Integer.compare(getUserId(), o.getUserId());
        }

        return -Long.compare(getLastNotified(), o.getLastNotified());
    }

    @Override
    public String toString() {
        return "NotifyingApp{"
                + "mUserId=" + mUserId
                + ", mPkg='" + mPkg + '\''
                + ", mLastNotified=" + mLastNotified
                + '}';
    }
}
