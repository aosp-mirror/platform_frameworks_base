/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.usage;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.BroadcastOptions;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class containing a collection of stats related to response events started from an app
 * after receiving a broadcast.
 *
 * @hide
 */
@SystemApi
public final class BroadcastResponseStats implements Parcelable {
    private final String mPackageName;
    private int mBroadcastsDispatchedCount;
    private int mNotificationsPostedCount;
    private int mNotificationsUpdatedCount;
    private int mNotificationsCancelledCount;

    public BroadcastResponseStats(@NonNull String packageName) {
        mPackageName = packageName;
    }

    private BroadcastResponseStats(@NonNull Parcel in) {
        mPackageName = in.readString8();
        mBroadcastsDispatchedCount = in.readInt();
        mNotificationsPostedCount = in.readInt();
        mNotificationsUpdatedCount = in.readInt();
        mNotificationsCancelledCount = in.readInt();
    }

    /**
     * @return the name of the package that the stats in this object correspond to.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the total number of broadcasts that were dispatched to the app by the caller.
     *
     * <b> Note that the returned count will only include the broadcasts that the caller explicitly
     * requested to record using
     * {@link BroadcastOptions#recordResponseEventWhileInBackground(long)}.
     *
     * @return the total number of broadcasts that were dispatched to the app.
     */
    @IntRange(from = 0)
    public int getBroadcastsDispatchedCount() {
        return mBroadcastsDispatchedCount;
    }

    /**
     * Returns the total number of notifications posted by the app soon after receiving a
     * broadcast.
     *
     * <b> Note that the returned count will only include the notifications that correspond to the
     * broadcasts that the caller explicitly requested to record using
     * {@link BroadcastOptions#recordResponseEventWhileInBackground(long)}.
     *
     * @return the total number of notifications posted by the app soon after receiving
     *         a broadcast.
     */
    @IntRange(from = 0)
    public int getNotificationsPostedCount() {
        return mNotificationsPostedCount;
    }

    /**
     * Returns the total number of notifications updated by the app soon after receiving a
     * broadcast.
     *
     * <b> Note that the returned count will only include the notifications that correspond to the
     * broadcasts that the caller explicitly requested to record using
     * {@link BroadcastOptions#recordResponseEventWhileInBackground(long)}.
     *
     * @return the total number of notifications updated by the app soon after receiving
     *         a broadcast.
     */
    @IntRange(from = 0)
    public int getNotificationsUpdatedCount() {
        return mNotificationsUpdatedCount;
    }

    /**
     * Returns the total number of notifications cancelled by the app soon after receiving a
     * broadcast.
     *
     * <b> Note that the returned count will only include the notifications that correspond to the
     * broadcasts that the caller explicitly requested to record using
     * {@link BroadcastOptions#recordResponseEventWhileInBackground(long)}.
     *
     * @return the total number of notifications cancelled by the app soon after receiving
     *         a broadcast.
     */
    @IntRange(from = 0)
    public int getNotificationsCancelledCount() {
        return mNotificationsCancelledCount;
    }

    /** @hide */
    public void incrementBroadcastsDispatchedCount(@IntRange(from = 0) int count) {
        mBroadcastsDispatchedCount += count;
    }

    /** @hide */
    public void incrementNotificationsPostedCount(@IntRange(from = 0) int count) {
        mNotificationsPostedCount += count;
    }

    /** @hide */
    public void incrementNotificationsUpdatedCount(@IntRange(from = 0) int count) {
        mNotificationsUpdatedCount += count;
    }

    /** @hide */
    public void incrementNotificationsCancelledCount(@IntRange(from = 0) int count) {
        mNotificationsCancelledCount += count;
    }

    /** @hide */
    public void addCounts(@NonNull BroadcastResponseStats stats) {
        incrementBroadcastsDispatchedCount(stats.getBroadcastsDispatchedCount());
        incrementNotificationsPostedCount(stats.getNotificationsPostedCount());
        incrementNotificationsUpdatedCount(stats.getNotificationsUpdatedCount());
        incrementNotificationsCancelledCount(stats.getNotificationsCancelledCount());
    }

    @Override
    public @NonNull String toString() {
        return "stats {"
                + "broadcastsSent=" + mBroadcastsDispatchedCount
                + ",notificationsPosted=" + mNotificationsPostedCount
                + ",notificationsUpdated=" + mNotificationsUpdatedCount
                + ",notificationsCancelled=" + mNotificationsCancelledCount
                + "}";
    }

    @Override
    public @ContentsFlags int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, @WriteFlags int flags) {
        dest.writeString8(mPackageName);
        dest.writeInt(mBroadcastsDispatchedCount);
        dest.writeInt(mNotificationsPostedCount);
        dest.writeInt(mNotificationsUpdatedCount);
        dest.writeInt(mNotificationsCancelledCount);
    }

    public static final @NonNull Creator<BroadcastResponseStats> CREATOR =
            new Creator<BroadcastResponseStats>() {
                @Override
                public @NonNull BroadcastResponseStats createFromParcel(@NonNull Parcel source) {
                    return new BroadcastResponseStats(source);
                }

                @Override
                public @NonNull BroadcastResponseStats[] newArray(int size) {
                    return new BroadcastResponseStats[size];
                }
            };
}
