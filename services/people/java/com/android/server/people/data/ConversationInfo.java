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
import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutInfo.ShortcutFlags;
import android.net.Uri;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents a conversation that is provided by the app based on {@link ShortcutInfo}.
 */
public class ConversationInfo {

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

    @ShortcutFlags
    private int mShortcutFlags;

    @ConversationFlags
    private int mConversationFlags;

    private ConversationInfo(Builder builder) {
        mShortcutId = builder.mShortcutId;
        mLocusId = builder.mLocusId;
        mContactUri = builder.mContactUri;
        mContactPhoneNumber = builder.mContactPhoneNumber;
        mNotificationChannelId = builder.mNotificationChannelId;
        mShortcutFlags = builder.mShortcutFlags;
        mConversationFlags = builder.mConversationFlags;
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
     * ID of the {@link android.app.NotificationChannel} where the notifications for this
     * conversation are posted.
     */
    @Nullable
    String getNotificationChannelId() {
        return mNotificationChannelId;
    }

    /** Whether the shortcut for this conversation is set long-lived by the app. */
    public boolean isShortcutLongLived() {
        return hasShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED);
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
                && mShortcutFlags == other.mShortcutFlags
                && mConversationFlags == other.mConversationFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mShortcutId, mLocusId, mContactUri, mContactPhoneNumber,
                mNotificationChannelId, mShortcutFlags, mConversationFlags);
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
        sb.append(", shortcutFlags=0x").append(Integer.toHexString(mShortcutFlags));
        sb.append(" [");
        if (isShortcutLongLived()) {
            sb.append("Liv");
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

        @ShortcutFlags
        private int mShortcutFlags;

        @ConversationFlags
        private int mConversationFlags;

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
            mShortcutFlags = conversationInfo.mShortcutFlags;
            mConversationFlags = conversationInfo.mConversationFlags;
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

        ConversationInfo build() {
            Objects.requireNonNull(mShortcutId);
            return new ConversationInfo(this);
        }
    }
}
