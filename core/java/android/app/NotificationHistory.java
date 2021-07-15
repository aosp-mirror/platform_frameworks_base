/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @hide
 */
public final class NotificationHistory implements Parcelable {

    /**
     * A historical notification. Any new fields added here should also be added to
     * {@link #readNotificationFromParcel} and
     * {@link #writeNotificationToParcel(HistoricalNotification, Parcel, int)}.
     */
    public static final class HistoricalNotification {
        private String mPackage;
        private String mChannelName;
        private String mChannelId;
        private int mUid;
        private @UserIdInt int mUserId;
        private long mPostedTimeMs;
        private String mTitle;
        private String mText;
        private Icon mIcon;
        private String mConversationId;

        private HistoricalNotification() {}

        public String getPackage() {
            return mPackage;
        }

        public String getChannelName() {
            return mChannelName;
        }

        public String getChannelId() {
            return mChannelId;
        }

        public int getUid() {
            return mUid;
        }

        public int getUserId() {
            return mUserId;
        }

        public long getPostedTimeMs() {
            return mPostedTimeMs;
        }

        public String getTitle() {
            return mTitle;
        }

        public String getText() {
            return mText;
        }

        public Icon getIcon() {
            return mIcon;
        }

        public String getKey() {
            return mPackage + "|" + mUid + "|" + mPostedTimeMs;
        }

        public String getConversationId() {
            return mConversationId;
        }

        @Override
        public String toString() {
            return "HistoricalNotification{" +
                    "key='" + getKey() + '\'' +
                    ", mChannelName='" + mChannelName + '\'' +
                    ", mChannelId='" + mChannelId + '\'' +
                    ", mUserId=" + mUserId +
                    ", mUid=" + mUid +
                    ", mTitle='" + mTitle + '\'' +
                    ", mText='" + mText + '\'' +
                    ", mIcon=" + mIcon +
                    ", mPostedTimeMs=" + mPostedTimeMs +
                    ", mConversationId=" + mConversationId +
                    '}';
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HistoricalNotification that = (HistoricalNotification) o;
            boolean iconsAreSame = getIcon() == null && that.getIcon() == null
                    || (getIcon() != null && that.getIcon() != null
                    && getIcon().sameAs(that.getIcon()));
            return getUid() == that.getUid() &&
                    getUserId() == that.getUserId() &&
                    getPostedTimeMs() == that.getPostedTimeMs() &&
                    Objects.equals(getPackage(), that.getPackage()) &&
                    Objects.equals(getChannelName(), that.getChannelName()) &&
                    Objects.equals(getChannelId(), that.getChannelId()) &&
                    Objects.equals(getTitle(), that.getTitle()) &&
                    Objects.equals(getText(), that.getText()) &&
                    Objects.equals(getConversationId(), that.getConversationId()) &&
                    iconsAreSame;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getPackage(), getChannelName(), getChannelId(), getUid(),
                    getUserId(),
                    getPostedTimeMs(), getTitle(), getText(), getIcon(), getConversationId());
        }

        public static final class Builder {
            private String mPackage;
            private String mChannelName;
            private String mChannelId;
            private int mUid;
            private @UserIdInt int mUserId;
            private long mPostedTimeMs;
            private String mTitle;
            private String mText;
            private Icon mIcon;
            private String mConversationId;

            public Builder() {}

            public Builder setPackage(String aPackage) {
                mPackage = aPackage;
                return this;
            }

            public Builder setChannelName(String channelName) {
                mChannelName = channelName;
                return this;
            }

            public Builder setChannelId(String channelId) {
                mChannelId = channelId;
                return this;
            }

            public Builder setUid(int uid) {
                mUid = uid;
                return this;
            }

            public Builder setUserId(int userId) {
                mUserId = userId;
                return this;
            }

            public Builder setPostedTimeMs(long postedTimeMs) {
                mPostedTimeMs = postedTimeMs;
                return this;
            }

            public Builder setTitle(String title) {
                mTitle = title;
                return this;
            }

            public Builder setText(String text) {
                mText = text;
                return this;
            }

            public Builder setIcon(Icon icon) {
                mIcon = icon;
                return this;
            }

            public Builder setConversationId(String conversationId) {
                mConversationId = conversationId;
                return this;
            }

            public HistoricalNotification build() {
                HistoricalNotification n = new HistoricalNotification();
                n.mPackage = mPackage;
                n.mChannelName = mChannelName;
                n.mChannelId = mChannelId;
                n.mUid = mUid;
                n.mUserId = mUserId;
                n.mPostedTimeMs = mPostedTimeMs;
                n.mTitle = mTitle;
                n.mText = mText;
                n.mIcon = mIcon;
                n.mConversationId = mConversationId;
                return n;
            }
        }
    }

    // Only used when creating the resulting history. Not used for reading/unparceling.
    private List<HistoricalNotification> mNotificationsToWrite = new ArrayList<>();
    // ditto
    private Set<String> mStringsToWrite = new HashSet<>();

    // Mostly used for reading/unparceling events.
    private Parcel mParcel = null;
    private int mHistoryCount;
    private int mIndex = 0;

    // Sorted array of commonly used strings to shrink the size of the parcel. populated from
    // mStringsToWrite on write and the parcel on read.
    private String[] mStringPool;

    /**
     * Construct the iterator from a parcel.
     */
    private NotificationHistory(Parcel in) {
        byte[] bytes = in.readBlob();
        Parcel data = Parcel.obtain();
        data.unmarshall(bytes, 0, bytes.length);
        data.setDataPosition(0);
        mHistoryCount = data.readInt();
        mIndex = data.readInt();
        if (mHistoryCount > 0) {
            mStringPool = data.createStringArray();

            final int listByteLength = data.readInt();
            final int positionInParcel = data.readInt();
            mParcel = Parcel.obtain();
            mParcel.setDataPosition(0);
            mParcel.appendFrom(data, data.dataPosition(), listByteLength);
            mParcel.setDataSize(mParcel.dataPosition());
            mParcel.setDataPosition(positionInParcel);
        }
    }

    /**
     * Create an empty iterator.
     */
    public NotificationHistory() {
        mHistoryCount = 0;
    }

    /**
     * Returns whether or not there are more events to read using {@link #getNextNotification()}.
     *
     * @return true if there are more events, false otherwise.
     */
    public boolean hasNextNotification() {
        return mIndex < mHistoryCount;
    }

    /**
     * Retrieve the next {@link HistoricalNotification} from the collection and put the
     * resulting data into {@code notificationOut}.
     *
     * @return The next {@link HistoricalNotification} or null if there are no more notifications.
     */
    public @Nullable HistoricalNotification getNextNotification() {
        if (!hasNextNotification()) {
            return null;
        }
        HistoricalNotification n = readNotificationFromParcel(mParcel);
        mIndex++;
        if (!hasNextNotification()) {
            mParcel.recycle();
            mParcel = null;
        }
        return n;
    }

    /**
     * Adds all of the pooled strings that have been read from disk
     */
    public void addPooledStrings(@NonNull List<String> strings) {
        mStringsToWrite.addAll(strings);
    }

    /**
     * Builds the pooled strings from pending notifications. Useful if the pooled strings on
     * disk contains strings that aren't relevant to the notifications in our collection.
     */
    public void poolStringsFromNotifications() {
        mStringsToWrite.clear();
        for (int i = 0; i < mNotificationsToWrite.size(); i++) {
            final HistoricalNotification notification = mNotificationsToWrite.get(i);
            mStringsToWrite.add(notification.getPackage());
            mStringsToWrite.add(notification.getChannelName());
            mStringsToWrite.add(notification.getChannelId());
            if (!TextUtils.isEmpty(notification.getConversationId())) {
                mStringsToWrite.add(notification.getConversationId());
            }
        }
    }

    /**
     * Used when populating a history from disk; adds an historical notification.
     */
    public void addNotificationToWrite(@NonNull HistoricalNotification notification) {
        if (notification == null) {
            return;
        }
        mNotificationsToWrite.add(notification);
        mHistoryCount++;
    }

    /**
     * Used when populating a history from disk; adds an historical notification.
     */
    public void addNewNotificationToWrite(@NonNull HistoricalNotification notification) {
        if (notification == null) {
            return;
        }
        mNotificationsToWrite.add(0, notification);
        mHistoryCount++;
    }

    public void addNotificationsToWrite(@NonNull NotificationHistory notificationHistory) {
        for (HistoricalNotification hn : notificationHistory.getNotificationsToWrite()) {
            addNotificationToWrite(hn);
        }
        Collections.sort(mNotificationsToWrite,
                (o1, o2) -> -1 * Long.compare(o1.getPostedTimeMs(), o2.getPostedTimeMs()));
        poolStringsFromNotifications();
    }

    /**
     * Removes a package's historical notifications and regenerates the string pool
     */
    public void removeNotificationsFromWrite(String packageName) {
        for (int i = mNotificationsToWrite.size() - 1; i >= 0; i--) {
            if (packageName.equals(mNotificationsToWrite.get(i).getPackage())) {
                mNotificationsToWrite.remove(i);
            }
        }
        poolStringsFromNotifications();
    }

    /**
     * Removes an individual historical notification and regenerates the string pool
     */
    public boolean removeNotificationFromWrite(String packageName, long postedTime) {
        boolean removed = false;
        for (int i = mNotificationsToWrite.size() - 1; i >= 0; i--) {
            HistoricalNotification hn = mNotificationsToWrite.get(i);
            if (packageName.equals(hn.getPackage())
                    && postedTime == hn.getPostedTimeMs()) {
                removed = true;
                mNotificationsToWrite.remove(i);
            }
        }
        if (removed) {
            poolStringsFromNotifications();
        }

        return removed;
    }

    /**
     * Removes all notifications from a conversation and regenerates the string pool
     */
    public boolean removeConversationsFromWrite(String packageName, Set<String> conversationIds) {
        boolean removed = false;
        for (int i = mNotificationsToWrite.size() - 1; i >= 0; i--) {
            HistoricalNotification hn = mNotificationsToWrite.get(i);
            if (packageName.equals(hn.getPackage())
                    && hn.getConversationId() != null
                    && conversationIds.contains(hn.getConversationId())) {
                removed = true;
                mNotificationsToWrite.remove(i);
            }
        }
        if (removed) {
            poolStringsFromNotifications();
        }

        return removed;
    }

    /**
     * Removes all notifications from a channel and regenerates the string pool
     */
    public boolean removeChannelFromWrite(String packageName, String channelId) {
        boolean removed = false;
        for (int i = mNotificationsToWrite.size() - 1; i >= 0; i--) {
            HistoricalNotification hn = mNotificationsToWrite.get(i);
            if (packageName.equals(hn.getPackage())
                    && Objects.equals(channelId, hn.getChannelId())) {
                removed = true;
                mNotificationsToWrite.remove(i);
            }
        }
        if (removed) {
            poolStringsFromNotifications();
        }

        return removed;
    }

    /**
     * Gets pooled strings in order to write them to disk
     */
    public @NonNull String[] getPooledStringsToWrite() {
        String[] stringsToWrite = mStringsToWrite.toArray(new String[]{});
        Arrays.sort(stringsToWrite);
        return stringsToWrite;
    }

    /**
     * Gets the historical notifications in order to write them to disk
     */
    public @NonNull List<HistoricalNotification> getNotificationsToWrite() {
        return mNotificationsToWrite;
    }

    /**
     * Gets the number of notifications in the collection
     */
    public int getHistoryCount() {
        return mHistoryCount;
    }

    private int findStringIndex(String str) {
        final int index = Arrays.binarySearch(mStringPool, str);
        if (index < 0) {
            throw new IllegalStateException("String '" + str + "' is not in the string pool");
        }
        return index;
    }

    /**
     * Writes a single notification to the parcel. Modify this when updating member variables of
     * {@link HistoricalNotification}.
     */
    private void writeNotificationToParcel(HistoricalNotification notification, Parcel p,
            int flags) {
        final int packageIndex;
        if (notification.mPackage != null) {
            packageIndex = findStringIndex(notification.mPackage);
        } else {
            packageIndex = -1;
        }

        final int channelNameIndex;
        if (notification.getChannelName() != null) {
            channelNameIndex = findStringIndex(notification.getChannelName());
        } else {
            channelNameIndex = -1;
        }

        final int channelIdIndex;
        if (notification.getChannelId() != null) {
            channelIdIndex = findStringIndex(notification.getChannelId());
        } else {
            channelIdIndex = -1;
        }

        final int conversationIdIndex;
        if (!TextUtils.isEmpty(notification.getConversationId())) {
            conversationIdIndex = findStringIndex(notification.getConversationId());
        } else {
            conversationIdIndex = -1;
        }

        p.writeInt(packageIndex);
        p.writeInt(channelNameIndex);
        p.writeInt(channelIdIndex);
        p.writeInt(conversationIdIndex);
        p.writeInt(notification.getUid());
        p.writeInt(notification.getUserId());
        p.writeLong(notification.getPostedTimeMs());
        p.writeString(notification.getTitle());
        p.writeString(notification.getText());
        notification.getIcon().writeToParcel(p, flags);
    }

    /**
     * Reads a single notification from the parcel. Modify this when updating member variables of
     * {@link HistoricalNotification}.
     */
    private HistoricalNotification readNotificationFromParcel(Parcel p) {
        HistoricalNotification.Builder notificationOut = new HistoricalNotification.Builder();
        final int packageIndex = p.readInt();
        if (packageIndex >= 0) {
            notificationOut.mPackage = mStringPool[packageIndex];
        } else {
            notificationOut.mPackage = null;
        }

        final int channelNameIndex = p.readInt();
        if (channelNameIndex >= 0) {
            notificationOut.setChannelName(mStringPool[channelNameIndex]);
        } else {
            notificationOut.setChannelName(null);
        }

        final int channelIdIndex = p.readInt();
        if (channelIdIndex >= 0) {
            notificationOut.setChannelId(mStringPool[channelIdIndex]);
        } else {
            notificationOut.setChannelId(null);
        }

        final int conversationIdIndex = p.readInt();
        if (conversationIdIndex >= 0) {
            notificationOut.setConversationId(mStringPool[conversationIdIndex]);
        } else {
            notificationOut.setConversationId(null);
        }

        notificationOut.setUid(p.readInt());
        notificationOut.setUserId(p.readInt());
        notificationOut.setPostedTimeMs(p.readLong());
        notificationOut.setTitle(p.readString());
        notificationOut.setText(p.readString());
        notificationOut.setIcon(Icon.CREATOR.createFromParcel(p));

        return notificationOut.build();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        Parcel data = Parcel.obtain();
        data.writeInt(mHistoryCount);
        data.writeInt(mIndex);
        if (mHistoryCount > 0) {
            mStringPool = getPooledStringsToWrite();
            data.writeStringArray(mStringPool);

            if (!mNotificationsToWrite.isEmpty()) {
                // typically system_server to a process

                // Write out the events
                Parcel p = Parcel.obtain();
                try {
                    p.setDataPosition(0);
                    for (int i = 0; i < mHistoryCount; i++) {
                        final HistoricalNotification notification = mNotificationsToWrite.get(i);
                        writeNotificationToParcel(notification, p, flags);
                    }

                    final int listByteLength = p.dataPosition();

                    // Write the total length of the data.
                    data.writeInt(listByteLength);

                    // Write our current position into the data.
                    data.writeInt(0);

                    // Write the data.
                    data.appendFrom(p, 0, listByteLength);
                } finally {
                    p.recycle();
                }

            } else if (mParcel != null) {
                // typically process to process as mNotificationsToWrite is not populated on
                // unparcel.

                // Write the total length of the data.
                data.writeInt(mParcel.dataSize());

                // Write out current position into the data.
                data.writeInt(mParcel.dataPosition());

                // Write the data.
                data.appendFrom(mParcel, 0, mParcel.dataSize());
            } else {
                throw new IllegalStateException(
                        "Either mParcel or mNotificationsToWrite must not be null");
            }
        }
        // Data can be too large for a transact. Write the data as a Blob, which will be written to
        // ashmem if too large.
        dest.writeBlob(data.marshall());
    }

    public static final @NonNull Creator<NotificationHistory> CREATOR
            = new Creator<NotificationHistory>() {
        @Override
        public NotificationHistory createFromParcel(Parcel source) {
            return new NotificationHistory(source);
        }

        @Override
        public NotificationHistory[] newArray(int size) {
            return new NotificationHistory[size];
        }
    };
}
