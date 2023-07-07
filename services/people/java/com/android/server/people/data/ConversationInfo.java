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

package com.android.server.people.data;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.people.ConversationStatus;
import android.content.LocusId;
import android.content.LocusIdProto;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutInfo.ShortcutFlags;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.Preconditions;
import com.android.server.people.ConversationInfoProto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a conversation that is provided by the app based on {@link ShortcutInfo}.
 */
public class ConversationInfo {
    private static final boolean DEBUG = false;

    // Schema version for the backup payload. Must be incremented whenever fields are added in
    // backup payload.
    private static final int VERSION = 1;

    private static final String TAG = ConversationInfo.class.getSimpleName();

    private static final int FLAG_IMPORTANT = 1;

    private static final int FLAG_NOTIFICATION_SILENCED = 1 << 1;

    private static final int FLAG_BUBBLED = 1 << 2;

    private static final int FLAG_PERSON_IMPORTANT = 1 << 3;

    private static final int FLAG_PERSON_BOT = 1 << 4;

    private static final int FLAG_CONTACT_STARRED = 1 << 5;

    private static final int FLAG_DEMOTED = 1 << 6;

    @IntDef(flag = true, prefix = {"FLAG_"}, value = {
            FLAG_IMPORTANT,
            FLAG_NOTIFICATION_SILENCED,
            FLAG_BUBBLED,
            FLAG_PERSON_IMPORTANT,
            FLAG_PERSON_BOT,
            FLAG_CONTACT_STARRED,
            FLAG_DEMOTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface ConversationFlags {
    }

    @NonNull
    private String mShortcutId;

    @Nullable
    private LocusId mLocusId;

    @Nullable
    private Uri mContactUri;

    @Nullable
    private String mContactPhoneNumber;

    @Nullable
    private String mNotificationChannelId;

    @Nullable
    private String mParentNotificationChannelId;

    private long mLastEventTimestamp;

    private long mCreationTimestamp;

    @ShortcutFlags
    private int mShortcutFlags;

    @ConversationFlags
    private int mConversationFlags;

    private Map<String, ConversationStatus> mCurrStatuses;

    private ConversationInfo(Builder builder) {
        mShortcutId = builder.mShortcutId;
        mLocusId = builder.mLocusId;
        mContactUri = builder.mContactUri;
        mContactPhoneNumber = builder.mContactPhoneNumber;
        mNotificationChannelId = builder.mNotificationChannelId;
        mParentNotificationChannelId = builder.mParentNotificationChannelId;
        mLastEventTimestamp = builder.mLastEventTimestamp;
        mCreationTimestamp = builder.mCreationTimestamp;
        mShortcutFlags = builder.mShortcutFlags;
        mConversationFlags = builder.mConversationFlags;
        mCurrStatuses = builder.mCurrStatuses;
    }

    @NonNull
    public String getShortcutId() {
        return mShortcutId;
    }

    @Nullable
    LocusId getLocusId() {
        return mLocusId;
    }

    /** The URI to look up the entry in the contacts data provider. */
    @Nullable
    Uri getContactUri() {
        return mContactUri;
    }

    /** The phone number of the associated contact. */
    @Nullable
    String getContactPhoneNumber() {
        return mContactPhoneNumber;
    }

    /**
     * ID of the conversation-specific {@link android.app.NotificationChannel} where the
     * notifications for this conversation are posted.
     */
    @Nullable
    String getNotificationChannelId() {
        return mNotificationChannelId;
    }

    /**
     * ID of the parent {@link android.app.NotificationChannel} for this conversation. This is the
     * notification channel where the notifications are posted before this conversation is
     * customized by the user.
     */
    @Nullable
    String getParentNotificationChannelId() {
        return mParentNotificationChannelId;
    }

    /**
     * Timestamp of the last event, {@code 0L} if there are no events. This timestamp is for
     * identifying and sorting the recent conversations. It may only count a subset of event types.
     */
    long getLastEventTimestamp() {
        return mLastEventTimestamp;
    }

    /**
     * Timestamp of the creation of the conversationInfo.
     */
    long getCreationTimestamp() {
        return mCreationTimestamp;
    }

    /** Whether the shortcut for this conversation is set long-lived by the app. */
    public boolean isShortcutLongLived() {
        return hasShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED);
    }

    /**
     * Whether the shortcut for this conversation is cached in Shortcut Service, with cache owner
     * set as notifications.
     */
    public boolean isShortcutCachedForNotification() {
        return hasShortcutFlags(ShortcutInfo.FLAG_CACHED_NOTIFICATIONS);
    }

    /** Whether this conversation is marked as important by the user. */
    public boolean isImportant() {
        return hasConversationFlags(FLAG_IMPORTANT);
    }

    /** Whether the notifications for this conversation should be silenced. */
    public boolean isNotificationSilenced() {
        return hasConversationFlags(FLAG_NOTIFICATION_SILENCED);
    }

    /** Whether the notifications for this conversation should show in bubbles. */
    public boolean isBubbled() {
        return hasConversationFlags(FLAG_BUBBLED);
    }

    /**
     * Whether this conversation is demoted by the user. New notifications for the demoted
     * conversation will not show in the conversation space.
     */
    public boolean isDemoted() {
        return hasConversationFlags(FLAG_DEMOTED);
    }

    /** Whether the associated person is marked as important by the app. */
    public boolean isPersonImportant() {
        return hasConversationFlags(FLAG_PERSON_IMPORTANT);
    }

    /** Whether the associated person is marked as a bot by the app. */
    public boolean isPersonBot() {
        return hasConversationFlags(FLAG_PERSON_BOT);
    }

    /** Whether the associated contact is marked as starred by the user. */
    public boolean isContactStarred() {
        return hasConversationFlags(FLAG_CONTACT_STARRED);
    }

    public Collection<ConversationStatus> getStatuses() {
        return mCurrStatuses.values();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ConversationInfo)) {
            return false;
        }
        ConversationInfo other = (ConversationInfo) obj;
        return Objects.equals(mShortcutId, other.mShortcutId)
                && Objects.equals(mLocusId, other.mLocusId)
                && Objects.equals(mContactUri, other.mContactUri)
                && Objects.equals(mContactPhoneNumber, other.mContactPhoneNumber)
                && Objects.equals(mNotificationChannelId, other.mNotificationChannelId)
                && Objects.equals(mParentNotificationChannelId, other.mParentNotificationChannelId)
                && Objects.equals(mLastEventTimestamp, other.mLastEventTimestamp)
                && mCreationTimestamp == other.mCreationTimestamp
                && mShortcutFlags == other.mShortcutFlags
                && mConversationFlags == other.mConversationFlags
                && Objects.equals(mCurrStatuses, other.mCurrStatuses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mShortcutId, mLocusId, mContactUri, mContactPhoneNumber,
                mNotificationChannelId, mParentNotificationChannelId, mLastEventTimestamp,
                mCreationTimestamp, mShortcutFlags, mConversationFlags, mCurrStatuses);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ConversationInfo {");
        sb.append("shortcutId=").append(mShortcutId);
        sb.append(", locusId=").append(mLocusId);
        sb.append(", contactUri=").append(mContactUri);
        sb.append(", phoneNumber=").append(mContactPhoneNumber);
        sb.append(", notificationChannelId=").append(mNotificationChannelId);
        sb.append(", parentNotificationChannelId=").append(mParentNotificationChannelId);
        sb.append(", lastEventTimestamp=").append(mLastEventTimestamp);
        sb.append(", creationTimestamp=").append(mCreationTimestamp);
        sb.append(", statuses=").append(mCurrStatuses);
        sb.append(", shortcutFlags=0x").append(Integer.toHexString(mShortcutFlags));
        sb.append(" [");
        if (isShortcutLongLived()) {
            sb.append("Liv");
        }
        if (isShortcutCachedForNotification()) {
            sb.append("Cac");
        }
        sb.append("]");
        sb.append(", conversationFlags=0x").append(Integer.toHexString(mConversationFlags));
        sb.append(" [");
        if (isImportant()) {
            sb.append("Imp");
        }
        if (isNotificationSilenced()) {
            sb.append("Sil");
        }
        if (isBubbled()) {
            sb.append("Bub");
        }
        if (isDemoted()) {
            sb.append("Dem");
        }
        if (isPersonImportant()) {
            sb.append("PIm");
        }
        if (isPersonBot()) {
            sb.append("Bot");
        }
        if (isContactStarred()) {
            sb.append("Sta");
        }
        sb.append("]}");
        return sb.toString();
    }

    private boolean hasShortcutFlags(@ShortcutFlags int flags) {
        return (mShortcutFlags & flags) == flags;
    }

    private boolean hasConversationFlags(@ConversationFlags int flags) {
        return (mConversationFlags & flags) == flags;
    }

    /** Writes field members to {@link ProtoOutputStream}. */
    void writeToProto(@NonNull ProtoOutputStream protoOutputStream) {
        protoOutputStream.write(ConversationInfoProto.SHORTCUT_ID, mShortcutId);
        if (mLocusId != null) {
            long locusIdToken = protoOutputStream.start(ConversationInfoProto.LOCUS_ID_PROTO);
            protoOutputStream.write(LocusIdProto.LOCUS_ID, mLocusId.getId());
            protoOutputStream.end(locusIdToken);
        }
        if (mContactUri != null) {
            protoOutputStream.write(ConversationInfoProto.CONTACT_URI, mContactUri.toString());
        }
        if (mNotificationChannelId != null) {
            protoOutputStream.write(ConversationInfoProto.NOTIFICATION_CHANNEL_ID,
                    mNotificationChannelId);
        }
        if (mParentNotificationChannelId != null) {
            protoOutputStream.write(ConversationInfoProto.PARENT_NOTIFICATION_CHANNEL_ID,
                    mParentNotificationChannelId);
        }
        protoOutputStream.write(ConversationInfoProto.LAST_EVENT_TIMESTAMP, mLastEventTimestamp);
        protoOutputStream.write(ConversationInfoProto.CREATION_TIMESTAMP, mCreationTimestamp);
        protoOutputStream.write(ConversationInfoProto.SHORTCUT_FLAGS, mShortcutFlags);
        protoOutputStream.write(ConversationInfoProto.CONVERSATION_FLAGS, mConversationFlags);
        if (mContactPhoneNumber != null) {
            protoOutputStream.write(ConversationInfoProto.CONTACT_PHONE_NUMBER,
                    mContactPhoneNumber);
        }
        // ConversationStatus is a transient object and not persisted
    }

    @Nullable
    byte[] getBackupPayload() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        try {
            out.writeUTF(mShortcutId);
            out.writeUTF(mLocusId != null ? mLocusId.getId() : "");
            out.writeUTF(mContactUri != null ? mContactUri.toString() : "");
            out.writeUTF(mNotificationChannelId != null ? mNotificationChannelId : "");
            out.writeInt(mShortcutFlags);
            out.writeInt(mConversationFlags);
            out.writeUTF(mContactPhoneNumber != null ? mContactPhoneNumber : "");
            out.writeUTF(mParentNotificationChannelId != null ? mParentNotificationChannelId : "");
            out.writeLong(mLastEventTimestamp);
            out.writeInt(VERSION);
            out.writeLong(mCreationTimestamp);
            // ConversationStatus is a transient object and not persisted
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write fields to backup payload.", e);
            return null;
        }
        return baos.toByteArray();
    }

    /** Reads from {@link ProtoInputStream} and constructs a {@link ConversationInfo}. */
    @NonNull
    static ConversationInfo readFromProto(@NonNull ProtoInputStream protoInputStream)
            throws IOException {
        ConversationInfo.Builder builder = new ConversationInfo.Builder();
        while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (protoInputStream.getFieldNumber()) {
                case (int) ConversationInfoProto.SHORTCUT_ID:
                    builder.setShortcutId(
                            protoInputStream.readString(ConversationInfoProto.SHORTCUT_ID));
                    break;
                case (int) ConversationInfoProto.LOCUS_ID_PROTO:
                    long locusIdToken = protoInputStream.start(
                            ConversationInfoProto.LOCUS_ID_PROTO);
                    while (protoInputStream.nextField()
                            != ProtoInputStream.NO_MORE_FIELDS) {
                        if (protoInputStream.getFieldNumber() == (int) LocusIdProto.LOCUS_ID) {
                            builder.setLocusId(new LocusId(
                                    protoInputStream.readString(LocusIdProto.LOCUS_ID)));
                        }
                    }
                    protoInputStream.end(locusIdToken);
                    break;
                case (int) ConversationInfoProto.CONTACT_URI:
                    builder.setContactUri(Uri.parse(protoInputStream.readString(
                            ConversationInfoProto.CONTACT_URI)));
                    break;
                case (int) ConversationInfoProto.NOTIFICATION_CHANNEL_ID:
                    builder.setNotificationChannelId(protoInputStream.readString(
                            ConversationInfoProto.NOTIFICATION_CHANNEL_ID));
                    break;
                case (int) ConversationInfoProto.PARENT_NOTIFICATION_CHANNEL_ID:
                    builder.setParentNotificationChannelId(protoInputStream.readString(
                            ConversationInfoProto.PARENT_NOTIFICATION_CHANNEL_ID));
                    break;
                case (int) ConversationInfoProto.LAST_EVENT_TIMESTAMP:
                    builder.setLastEventTimestamp(protoInputStream.readLong(
                            ConversationInfoProto.LAST_EVENT_TIMESTAMP));
                    break;
                case (int) ConversationInfoProto.CREATION_TIMESTAMP:
                    builder.setCreationTimestamp(protoInputStream.readLong(
                            ConversationInfoProto.CREATION_TIMESTAMP));
                    break;
                case (int) ConversationInfoProto.SHORTCUT_FLAGS:
                    builder.setShortcutFlags(protoInputStream.readInt(
                            ConversationInfoProto.SHORTCUT_FLAGS));
                    break;
                case (int) ConversationInfoProto.CONVERSATION_FLAGS:
                    builder.setConversationFlags(protoInputStream.readInt(
                            ConversationInfoProto.CONVERSATION_FLAGS));
                    break;
                case (int) ConversationInfoProto.CONTACT_PHONE_NUMBER:
                    builder.setContactPhoneNumber(protoInputStream.readString(
                            ConversationInfoProto.CONTACT_PHONE_NUMBER));
                    break;
                default:
                    Slog.w(TAG, "Could not read undefined field: "
                            + protoInputStream.getFieldNumber());
            }
        }
        return builder.build();
    }

    @Nullable
    static ConversationInfo readFromBackupPayload(@NonNull byte[] payload) {
        ConversationInfo.Builder builder = new ConversationInfo.Builder();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            builder.setShortcutId(in.readUTF());
            String locusId = in.readUTF();
            if (!TextUtils.isEmpty(locusId)) {
                builder.setLocusId(new LocusId(locusId));
            }
            String contactUri = in.readUTF();
            if (!TextUtils.isEmpty(contactUri)) {
                builder.setContactUri(Uri.parse(contactUri));
            }
            String notificationChannelId = in.readUTF();
            if (!TextUtils.isEmpty(notificationChannelId)) {
                builder.setNotificationChannelId(notificationChannelId);
            }
            builder.setShortcutFlags(in.readInt());
            builder.setConversationFlags(in.readInt());
            String contactPhoneNumber = in.readUTF();
            if (!TextUtils.isEmpty(contactPhoneNumber)) {
                builder.setContactPhoneNumber(contactPhoneNumber);
            }
            String parentNotificationChannelId = in.readUTF();
            if (!TextUtils.isEmpty(parentNotificationChannelId)) {
                builder.setParentNotificationChannelId(parentNotificationChannelId);
            }
            builder.setLastEventTimestamp(in.readLong());
            int payloadVersion = maybeReadVersion(in);
            if (payloadVersion == 1) {
                builder.setCreationTimestamp(in.readLong());
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read conversation info fields from backup payload.", e);
            return null;
        }
        return builder.build();
    }

    private static int maybeReadVersion(DataInputStream in) throws IOException {
        try {
            return in.readInt();
        } catch (EOFException eofException) {
            // EOF is expected if using old backup payload protocol.
            if (DEBUG) Log.d(TAG, "Eof reached for data stream, missing version number");
            return 0;
        }
    }

    /**
     * Builder class for {@link ConversationInfo} objects.
     */
    static class Builder {

        private String mShortcutId;

        @Nullable
        private LocusId mLocusId;

        @Nullable
        private Uri mContactUri;

        @Nullable
        private String mContactPhoneNumber;

        @Nullable
        private String mNotificationChannelId;

        @Nullable
        private String mParentNotificationChannelId;

        private long mLastEventTimestamp;

        private long mCreationTimestamp;

        @ShortcutFlags
        private int mShortcutFlags;

        @ConversationFlags
        private int mConversationFlags;

        private Map<String, ConversationStatus> mCurrStatuses = new HashMap<>();

        Builder() {
        }

        Builder(@NonNull ConversationInfo conversationInfo) {
            if (mShortcutId == null) {
                mShortcutId = conversationInfo.mShortcutId;
            } else {
                Preconditions.checkArgument(mShortcutId.equals(conversationInfo.mShortcutId));
            }
            mLocusId = conversationInfo.mLocusId;
            mContactUri = conversationInfo.mContactUri;
            mContactPhoneNumber = conversationInfo.mContactPhoneNumber;
            mNotificationChannelId = conversationInfo.mNotificationChannelId;
            mParentNotificationChannelId = conversationInfo.mParentNotificationChannelId;
            mLastEventTimestamp = conversationInfo.mLastEventTimestamp;
            mCreationTimestamp = conversationInfo.mCreationTimestamp;
            mShortcutFlags = conversationInfo.mShortcutFlags;
            mConversationFlags = conversationInfo.mConversationFlags;
            mCurrStatuses = conversationInfo.mCurrStatuses;
        }

        Builder setShortcutId(@NonNull String shortcutId) {
            mShortcutId = shortcutId;
            return this;
        }

        Builder setLocusId(LocusId locusId) {
            mLocusId = locusId;
            return this;
        }

        Builder setContactUri(Uri contactUri) {
            mContactUri = contactUri;
            return this;
        }

        Builder setContactPhoneNumber(String phoneNumber) {
            mContactPhoneNumber = phoneNumber;
            return this;
        }

        Builder setNotificationChannelId(String notificationChannelId) {
            mNotificationChannelId = notificationChannelId;
            return this;
        }

        Builder setParentNotificationChannelId(String parentNotificationChannelId) {
            mParentNotificationChannelId = parentNotificationChannelId;
            return this;
        }

        Builder setLastEventTimestamp(long lastEventTimestamp) {
            mLastEventTimestamp = lastEventTimestamp;
            return this;
        }

        Builder setCreationTimestamp(long creationTimestamp) {
            mCreationTimestamp = creationTimestamp;
            return this;
        }

        Builder setShortcutFlags(@ShortcutFlags int shortcutFlags) {
            mShortcutFlags = shortcutFlags;
            return this;
        }

        Builder setConversationFlags(@ConversationFlags int conversationFlags) {
            mConversationFlags = conversationFlags;
            return this;
        }

        Builder setImportant(boolean value) {
            return setConversationFlag(FLAG_IMPORTANT, value);
        }

        Builder setNotificationSilenced(boolean value) {
            return setConversationFlag(FLAG_NOTIFICATION_SILENCED, value);
        }

        Builder setBubbled(boolean value) {
            return setConversationFlag(FLAG_BUBBLED, value);
        }

        Builder setDemoted(boolean value) {
            return setConversationFlag(FLAG_DEMOTED, value);
        }

        Builder setPersonImportant(boolean value) {
            return setConversationFlag(FLAG_PERSON_IMPORTANT, value);
        }

        Builder setPersonBot(boolean value) {
            return setConversationFlag(FLAG_PERSON_BOT, value);
        }

        Builder setContactStarred(boolean value) {
            return setConversationFlag(FLAG_CONTACT_STARRED, value);
        }

        private Builder setConversationFlag(@ConversationFlags int flags, boolean value) {
            if (value) {
                return addConversationFlags(flags);
            } else {
                return removeConversationFlags(flags);
            }
        }

        private Builder addConversationFlags(@ConversationFlags int flags) {
            mConversationFlags |= flags;
            return this;
        }

        private Builder removeConversationFlags(@ConversationFlags int flags) {
            mConversationFlags &= ~flags;
            return this;
        }

        Builder setStatuses(List<ConversationStatus> statuses) {
            mCurrStatuses.clear();
            if (statuses != null) {
                for (ConversationStatus status : statuses) {
                    mCurrStatuses.put(status.getId(), status);
                }
            }
            return this;
        }

        Builder addOrUpdateStatus(ConversationStatus status) {
            mCurrStatuses.put(status.getId(), status);
            return this;
        }

        Builder clearStatus(String statusId) {
            mCurrStatuses.remove(statusId);
            return this;
        }

        ConversationInfo build() {
            Objects.requireNonNull(mShortcutId);
            return new ConversationInfo(this);
        }
    }
}
