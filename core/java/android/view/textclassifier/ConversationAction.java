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
package android.view.textclassifier;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.app.RemoteAction;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;

/** Represents the action suggested by a {@link TextClassifier} on a given conversation. */
public final class ConversationAction implements Parcelable {

    /** @hide */
    @Retention(SOURCE)
    @StringDef(
            value = {
                    TYPE_VIEW_CALENDAR,
                    TYPE_VIEW_MAP,
                    TYPE_TRACK_FLIGHT,
                    TYPE_OPEN_URL,
                    TYPE_SEND_SMS,
                    TYPE_CALL_PHONE,
                    TYPE_SEND_EMAIL,
                    TYPE_TEXT_REPLY,
                    TYPE_CREATE_REMINDER,
                    TYPE_SHARE_LOCATION
            },
            prefix = "TYPE_")
    public @interface ActionType {}

    /**
     * Indicates an action to view a calendar at a specified time.
     */
    public static final String TYPE_VIEW_CALENDAR = "view_calendar";
    /**
     * Indicates an action to view the map at a specified location.
     */
    public static final String TYPE_VIEW_MAP = "view_map";
    /**
     * Indicates an action to track a flight.
     */
    public static final String TYPE_TRACK_FLIGHT = "track_flight";
    /**
     * Indicates an action to open an URL.
     */
    public static final String TYPE_OPEN_URL = "open_url";
    /**
     * Indicates an action to send a SMS.
     */
    public static final String TYPE_SEND_SMS = "send_sms";
    /**
     * Indicates an action to call a phone number.
     */
    public static final String TYPE_CALL_PHONE = "call_phone";
    /**
     * Indicates an action to send an email.
     */
    public static final String TYPE_SEND_EMAIL = "send_email";
    /**
     * Indicates an action to reply with a text message.
     */
    public static final String TYPE_TEXT_REPLY = "text_reply";
    /**
     * Indicates an action to create a reminder.
     */
    public static final String TYPE_CREATE_REMINDER = "create_reminder";
    /**
     * Indicates an action to reply with a location.
     */
    public static final String TYPE_SHARE_LOCATION = "share_location";

    /** @hide **/
    public static final String TYPE_ADD_CONTACT = "add_contact";

    public static final Creator<ConversationAction> CREATOR =
            new Creator<ConversationAction>() {
                @Override
                public ConversationAction createFromParcel(Parcel in) {
                    return new ConversationAction(in);
                }

                @Override
                public ConversationAction[] newArray(int size) {
                    return new ConversationAction[size];
                }
            };

    @NonNull
    @ActionType
    private final String mType;
    @NonNull
    private final CharSequence mTextReply;
    @Nullable
    private final RemoteAction mAction;

    @FloatRange(from = 0, to = 1)
    private final float mScore;

    @NonNull
    private final Bundle mExtras;

    private ConversationAction(
            @NonNull String type,
            @Nullable RemoteAction action,
            @Nullable CharSequence textReply,
            float score,
            @NonNull Bundle extras) {
        mType = Preconditions.checkNotNull(type);
        mAction = action;
        mTextReply = textReply;
        mScore = score;
        mExtras = Preconditions.checkNotNull(extras);
    }

    private ConversationAction(Parcel in) {
        mType = in.readString();
        mAction = in.readParcelable(null);
        mTextReply = in.readCharSequence();
        mScore = in.readFloat();
        mExtras = in.readBundle();
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mType);
        parcel.writeParcelable(mAction, flags);
        parcel.writeCharSequence(mTextReply);
        parcel.writeFloat(mScore);
        parcel.writeBundle(mExtras);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Returns the type of this action, for example, {@link #TYPE_VIEW_CALENDAR}. */
    @NonNull
    @ActionType
    public String getType() {
        return mType;
    }

    /**
     * Returns a RemoteAction object, which contains the icon, label and a PendingIntent, for
     * the specified action type.
     */
    @Nullable
    public RemoteAction getAction() {
        return mAction;
    }

    /**
     * Returns the confidence score for the specified action. The value ranges from 0 (low
     * confidence) to 1 (high confidence).
     */
    @FloatRange(from = 0, to = 1)
    public float getConfidenceScore() {
        return mScore;
    }

    /**
     * Returns the text reply that could be sent as a reply to the given conversation.
     * <p>
     * This is only available when the type of the action is {@link #TYPE_TEXT_REPLY}.
     */
    @Nullable
    public CharSequence getTextReply() {
        return mTextReply;
    }

    /**
     * Returns the extended data related to this conversation action.
     *
     * <p><b>NOTE: </b>Each call to this method returns a new bundle copy so clients should
     * prefer to hold a reference to the returned bundle rather than frequently calling this
     * method.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras.deepCopy();
    }

    /** Builder class to construct {@link ConversationAction}. */
    public static final class Builder {
        @Nullable
        @ActionType
        private String mType;
        @Nullable
        private RemoteAction mAction;
        @Nullable
        private CharSequence mTextReply;
        private float mScore;
        @Nullable
        private Bundle mExtras;

        public Builder(@NonNull @ActionType String actionType) {
            mType = Preconditions.checkNotNull(actionType);
        }

        /**
         * Sets an action that may be performed on the given conversation.
         */
        @NonNull
        public Builder setAction(@Nullable RemoteAction action) {
            mAction = action;
            return this;
        }

        /**
         * Sets a text reply that may be performed on the given conversation.
         */
        @NonNull
        public Builder setTextReply(@Nullable CharSequence textReply) {
            mTextReply = textReply;
            return this;
        }

        /** Sets the confident score. */
        @NonNull
        public Builder setConfidenceScore(@FloatRange(from = 0, to = 1) float score) {
            mScore = score;
            return this;
        }

        /**
         * Sets the extended data for the conversation action object.
         */
        @NonNull
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /** Builds the {@link ConversationAction} object. */
        @NonNull
        public ConversationAction build() {
            return new ConversationAction(
                    mType,
                    mAction,
                    mTextReply,
                    mScore,
                    mExtras == null ? Bundle.EMPTY : mExtras.deepCopy());
        }
    }
}
