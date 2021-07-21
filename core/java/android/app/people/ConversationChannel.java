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

package android.app.people;

import android.annotation.Nullable;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.pm.ShortcutInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * The non-customized notification channel of a conversation. It contains the information to render
 * the conversation and allows the user to open and customize the conversation setting.
 *
 * @hide
 */
public final class ConversationChannel implements Parcelable {

    private ShortcutInfo mShortcutInfo;
    private int mUid;
    private NotificationChannel mNotificationChannel;
    private NotificationChannelGroup mNotificationChannelGroup;
    private long mLastEventTimestamp;
    private boolean mHasActiveNotifications;
    private boolean mHasBirthdayToday;
    private List<ConversationStatus> mStatuses;

    public static final Creator<ConversationChannel> CREATOR = new Creator<ConversationChannel>() {
        @Override
        public ConversationChannel createFromParcel(Parcel in) {
            return new ConversationChannel(in);
        }

        @Override
        public ConversationChannel[] newArray(int size) {
            return new ConversationChannel[size];
        }
    };

    public ConversationChannel(ShortcutInfo shortcutInfo, int uid,
            NotificationChannel parentNotificationChannel,
            NotificationChannelGroup parentNotificationChannelGroup, long lastEventTimestamp,
            boolean hasActiveNotifications) {
        mShortcutInfo = shortcutInfo;
        mUid = uid;
        mNotificationChannel = parentNotificationChannel;
        mNotificationChannelGroup = parentNotificationChannelGroup;
        mLastEventTimestamp = lastEventTimestamp;
        mHasActiveNotifications = hasActiveNotifications;
    }

    public ConversationChannel(ShortcutInfo shortcutInfo, int uid,
            NotificationChannel parentNotificationChannel,
            NotificationChannelGroup parentNotificationChannelGroup, long lastEventTimestamp,
            boolean hasActiveNotifications, boolean hasBirthdayToday,
            List<ConversationStatus> statuses) {
        mShortcutInfo = shortcutInfo;
        mUid = uid;
        mNotificationChannel = parentNotificationChannel;
        mNotificationChannelGroup = parentNotificationChannelGroup;
        mLastEventTimestamp = lastEventTimestamp;
        mHasActiveNotifications = hasActiveNotifications;
        mHasBirthdayToday = hasBirthdayToday;
        mStatuses = statuses;
    }

    public ConversationChannel(Parcel in) {
        mShortcutInfo = in.readParcelable(ShortcutInfo.class.getClassLoader());
        mUid = in.readInt();
        mNotificationChannel = in.readParcelable(NotificationChannel.class.getClassLoader());
        mNotificationChannelGroup =
                in.readParcelable(NotificationChannelGroup.class.getClassLoader());
        mLastEventTimestamp = in.readLong();
        mHasActiveNotifications = in.readBoolean();
        mHasBirthdayToday = in.readBoolean();
        mStatuses = new ArrayList<>();
        in.readParcelableList(mStatuses, ConversationStatus.class.getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mShortcutInfo, flags);
        dest.writeInt(mUid);
        dest.writeParcelable(mNotificationChannel, flags);
        dest.writeParcelable(mNotificationChannelGroup, flags);
        dest.writeLong(mLastEventTimestamp);
        dest.writeBoolean(mHasActiveNotifications);
        dest.writeBoolean(mHasBirthdayToday);
        dest.writeParcelableList(mStatuses, flags);
    }

    public ShortcutInfo getShortcutInfo() {
        return mShortcutInfo;
    }

    public int getUid() {
        return mUid;
    }

    public NotificationChannel getNotificationChannel() {
        return mNotificationChannel;
    }

    public NotificationChannelGroup getNotificationChannelGroup() {
        return mNotificationChannelGroup;
    }

    public long getLastEventTimestamp() {
        return mLastEventTimestamp;
    }

    /**
     * Whether this conversation has any active notifications. If it's true, the shortcut for this
     * conversation can't be uncached until all its active notifications are dismissed.
     */
    public boolean hasActiveNotifications() {
        return mHasActiveNotifications;
    }

    /** Whether this conversation has a birthday today, as associated in the Contacts Database. */
    public boolean hasBirthdayToday() {
        return mHasBirthdayToday;
    }

    /** Returns statuses associated with the conversation. */
    public @Nullable List<ConversationStatus> getStatuses() {
        return mStatuses;
    }

    @Override
    public String toString() {
        return "ConversationChannel{" +
                "mShortcutInfo=" + mShortcutInfo +
                ", mUid=" + mUid +
                ", mNotificationChannel=" + mNotificationChannel +
                ", mNotificationChannelGroup=" + mNotificationChannelGroup +
                ", mLastEventTimestamp=" + mLastEventTimestamp +
                ", mHasActiveNotifications=" + mHasActiveNotifications +
                ", mHasBirthdayToday=" + mHasBirthdayToday +
                ", mStatuses=" + mStatuses +
                '}';
    }
}
