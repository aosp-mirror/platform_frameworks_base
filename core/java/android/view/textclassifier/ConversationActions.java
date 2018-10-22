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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.app.Person;
import android.app.RemoteAction;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.SpannedString;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Represents a list of actions suggested by a {@link TextClassifier} on a given conversation.
 *
 * @see TextClassifier#suggestConversationActions(Request)
 */
public final class ConversationActions implements Parcelable {

    public static final Creator<ConversationActions> CREATOR =
            new Creator<ConversationActions>() {
                @Override
                public ConversationActions createFromParcel(Parcel in) {
                    return new ConversationActions(in);
                }

                @Override
                public ConversationActions[] newArray(int size) {
                    return new ConversationActions[size];
                }
            };

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

    /** @hide */
    @Retention(SOURCE)
    @StringDef(
            value = {
                    HINT_FOR_NOTIFICATION,
                    HINT_FOR_IN_APP,
            },
            prefix = "HINT_")
    public @interface Hint {}
    /**
     * To indicate the generated actions will be used within the app.
     */
    public static final String HINT_FOR_IN_APP = "in_app";
    /**
     * To indicate the generated actions will be used for notification.
     */
    public static final String HINT_FOR_NOTIFICATION = "notification";

    private List<ConversationAction> mConversationActions;

    /** Constructs a {@link ConversationActions} object. */
    public ConversationActions(@NonNull List<ConversationAction> conversationActions) {
        mConversationActions =
                Collections.unmodifiableList(Preconditions.checkNotNull(conversationActions));
    }

    private ConversationActions(Parcel in) {
        mConversationActions =
                Collections.unmodifiableList(in.createTypedArrayList(ConversationAction.CREATOR));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeTypedList(mConversationActions);
    }

    /** Returns an immutable list of {@link ConversationAction} objects. */
    @NonNull
    public List<ConversationAction> getConversationActions() {
        return mConversationActions;
    }

    /** Represents the action suggested by a {@link TextClassifier} on a given conversation. */
    public static final class ConversationAction implements Parcelable {

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

        @NonNull
        @ActionType
        /** Returns the type of this action, for example, {@link #TYPE_VIEW_CALENDAR}. */
        public String getType() {
            return mType;
        }

        @Nullable
        /**
         * Returns a RemoteAction object, which contains the icon, label and a PendingIntent, for
         * the specified action type.
         */
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

    /** Represents a message in the conversation. */
    public static final class Message implements Parcelable {
        @Nullable
        private final Person mAuthor;
        @Nullable
        private final ZonedDateTime mComposeTime;
        @Nullable
        private final CharSequence mText;
        @NonNull
        private final Bundle mExtras;

        private Message(
                @Nullable Person author,
                @Nullable ZonedDateTime composeTime,
                @Nullable CharSequence text,
                @NonNull Bundle bundle) {
            mAuthor = author;
            mComposeTime = composeTime;
            mText = text;
            mExtras = Preconditions.checkNotNull(bundle);
        }

        private Message(Parcel in) {
            mAuthor = in.readParcelable(null);
            mComposeTime =
                    in.readInt() == 0
                            ? null
                            : ZonedDateTime.parse(
                                    in.readString(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
            mText = in.readCharSequence();
            mExtras = in.readBundle();
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeParcelable(mAuthor, flags);
            parcel.writeInt(mComposeTime != null ? 1 : 0);
            if (mComposeTime != null) {
                parcel.writeString(mComposeTime.format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
            }
            parcel.writeCharSequence(mText);
            parcel.writeBundle(mExtras);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Message> CREATOR =
                new Creator<Message>() {
                    @Override
                    public Message createFromParcel(Parcel in) {
                        return new Message(in);
                    }

                    @Override
                    public Message[] newArray(int size) {
                        return new Message[size];
                    }
                };

        /** Returns the person that composed the message. */
        @Nullable
        public Person getAuthor() {
            return mAuthor;
        }

        /** Returns the compose time of the message. */
        @Nullable
        public ZonedDateTime getTime() {
            return mComposeTime;
        }

        /** Returns the text of the message. */
        @Nullable
        public CharSequence getText() {
            return mText;
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

        /** Builder class to construct a {@link Message} */
        public static final class Builder {
            @Nullable
            private Person mAuthor;
            @Nullable
            private ZonedDateTime mComposeTime;
            @Nullable
            private CharSequence mText;
            @Nullable
            private Bundle mExtras;

            /** Sets the person who composed this message. */
            @NonNull
            public Builder setAuthor(@Nullable Person author) {
                mAuthor = author;
                return this;
            }

            /** Sets the text of this message */
            @NonNull
            public Builder setText(@Nullable CharSequence text) {
                mText = text;
                return this;
            }

            /** Sets the compose time of this message */
            @NonNull
            public Builder setComposeTime(@Nullable ZonedDateTime composeTime) {
                mComposeTime = composeTime;
                return this;
            }

            /** Sets a set of extended data to the message. */
            @NonNull
            public Builder setExtras(@Nullable Bundle bundle) {
                this.mExtras = bundle;
                return this;
            }

            /** Builds the {@link Message} object. */
            @NonNull
            public Message build() {
                return new Message(
                        mAuthor,
                        mComposeTime,
                        mText == null ? null : new SpannedString(mText),
                        mExtras == null ? new Bundle() : mExtras.deepCopy());
            }
        }
    }

    /** Configuration object for specifying what action types to identify. */
    public static final class TypeConfig implements Parcelable {
        @NonNull
        @ActionType
        private final Set<String> mExcludedTypes;
        @NonNull
        @ActionType
        private final Set<String> mIncludedTypes;
        private final boolean mIncludeTypesFromTextClassifier;

        private TypeConfig(
                @NonNull Set<String> includedTypes,
                @NonNull Set<String> excludedTypes,
                boolean includeTypesFromTextClassifier) {
            mIncludedTypes = Preconditions.checkNotNull(includedTypes);
            mExcludedTypes = Preconditions.checkNotNull(excludedTypes);
            mIncludeTypesFromTextClassifier = includeTypesFromTextClassifier;
        }

        private TypeConfig(Parcel in) {
            mIncludedTypes = new ArraySet<>(in.createStringArrayList());
            mExcludedTypes = new ArraySet<>(in.createStringArrayList());
            mIncludeTypesFromTextClassifier = in.readByte() != 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeStringList(new ArrayList<>(mIncludedTypes));
            parcel.writeStringList(new ArrayList<>(mExcludedTypes));
            parcel.writeByte((byte) (mIncludeTypesFromTextClassifier ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<TypeConfig> CREATOR =
                new Creator<TypeConfig>() {
                    @Override
                    public TypeConfig createFromParcel(Parcel in) {
                        return new TypeConfig(in);
                    }

                    @Override
                    public TypeConfig[] newArray(int size) {
                        return new TypeConfig[size];
                    }
                };

        /**
         * Returns a final list of types that the text classifier should look for.
         *
         * <p>NOTE: This method is intended for use by a text classifier.
         *
         * @param defaultTypes types the text classifier thinks should be included before factoring
         *    in the included/excluded types given by the client.
         */
        @NonNull
        public Collection<String> resolveTypes(@Nullable Collection<String> defaultTypes) {
            Set<String> types = new ArraySet<>();
            if (mIncludeTypesFromTextClassifier && defaultTypes != null) {
                types.addAll(defaultTypes);
            }
            types.addAll(mIncludedTypes);
            types.removeAll(mExcludedTypes);
            return Collections.unmodifiableCollection(types);
        }

        /**
         * Return whether the client allows the text classifier to include its own list of default
         * types. If this function returns {@code true}, the text classifier can consider specifying
         * a default list of entity types in {@link #resolveTypes(Collection)}.
         *
         * <p>NOTE: This method is intended for use by a text classifier.
         *
         * @see #resolveTypes(Collection)
         */
        public boolean shouldIncludeTypesFromTextClassifier() {
            return mIncludeTypesFromTextClassifier;
        }

        /** Builder class to construct the {@link TypeConfig} object. */
        public static final class Builder {
            @Nullable
            private Collection<String> mExcludedTypes;
            @Nullable
            private Collection<String> mIncludedTypes;
            private boolean mIncludeTypesFromTextClassifier = true;

            /**
             * Sets a collection of types that are explicitly included, for example, {@link
             * #TYPE_VIEW_CALENDAR}.
             */
            @NonNull
            public Builder setIncludedTypes(
                    @Nullable @ActionType Collection<String> includedTypes) {
                mIncludedTypes = includedTypes;
                return this;
            }

            /**
             * Sets a collection of types that are explicitly excluded, for example, {@link
             * #TYPE_VIEW_CALENDAR}.
             */
            @NonNull
            public Builder setExcludedTypes(
                    @Nullable @ActionType Collection<String> excludedTypes) {
                mExcludedTypes = excludedTypes;
                return this;
            }

            /**
             * Specifies whether or not to include the types suggested by the text classifier. By
             * default, it is included.
             */
            @NonNull
            public Builder includeTypesFromTextClassifier(boolean includeTypesFromTextClassifier) {
                mIncludeTypesFromTextClassifier = includeTypesFromTextClassifier;
                return this;
            }

            /**
             * Combines all of the options that have been set and returns a new {@link TypeConfig}
             * object.
             */
            @NonNull
            public TypeConfig build() {
                return new TypeConfig(
                        mIncludedTypes == null
                                ? Collections.emptySet()
                                : new ArraySet<>(mIncludedTypes),
                        mExcludedTypes == null
                                ? Collections.emptySet()
                                : new ArraySet<>(mExcludedTypes),
                        mIncludeTypesFromTextClassifier);
            }
        }
    }

    /**
     * A request object for generating conversation action suggestions.
     *
     * @see TextClassifier#suggestConversationActions(Request)
     */
    public static final class Request implements Parcelable {
        @NonNull
        private final List<Message> mConversation;
        @NonNull
        private final TypeConfig mTypeConfig;
        private final int mMaxSuggestions;
        @NonNull
        @Hint
        private final List<String> mHints;

        private Request(
                @NonNull List<Message> conversation,
                @NonNull TypeConfig typeConfig,
                int maxSuggestions,
                @Nullable @Hint List<String> hints) {
            mConversation = Preconditions.checkNotNull(conversation);
            mTypeConfig = Preconditions.checkNotNull(typeConfig);
            mMaxSuggestions = maxSuggestions;
            mHints = hints;
        }

        private Request(Parcel in) {
            List<Message> conversation = new ArrayList<>();
            in.readParcelableList(conversation, null);
            mConversation = Collections.unmodifiableList(conversation);
            mTypeConfig = in.readParcelable(null);
            mMaxSuggestions = in.readInt();
            List<String> hints = new ArrayList<>();
            in.readStringList(hints);
            mHints = Collections.unmodifiableList(hints);
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeParcelableList(mConversation, flags);
            parcel.writeParcelable(mTypeConfig, flags);
            parcel.writeInt(mMaxSuggestions);
            parcel.writeStringList(mHints);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Request> CREATOR =
                new Creator<Request>() {
                    @Override
                    public Request createFromParcel(Parcel in) {
                        return new Request(in);
                    }

                    @Override
                    public Request[] newArray(int size) {
                        return new Request[size];
                    }
                };

        /** Returns the type config. */
        @NonNull
        public TypeConfig getTypeConfig() {
            return mTypeConfig;
        }

        /** Returns an immutable list of messages that make up the conversation. */
        @NonNull
        public List<Message> getConversation() {
            return mConversation;
        }

        /**
         * Return the maximal number of suggestions the caller wants, value 0 means no restriction.
         */
        @IntRange(from = 0)
        public int getMaxSuggestions() {
            return mMaxSuggestions;
        }

        /** Returns an immutable list of hints */
        @Nullable
        @Hint
        public List<String> getHints() {
            return mHints;
        }

        /** Builder object to construct the {@link Request} object. */
        public static final class Builder {
            @NonNull
            private List<Message> mConversation;
            @Nullable
            private TypeConfig mTypeConfig;
            private int mMaxSuggestions;
            @Nullable
            @Hint
            private List<String> mHints;

            /**
             * Constructs a builder.
             *
             * @param conversation the conversation that the text classifier is going to generate
             *     actions for.
             */
            public Builder(@NonNull List<Message> conversation) {
                mConversation = Preconditions.checkNotNull(conversation);
            }

            /**
             * Sets the hints to help text classifier to generate actions. It could be used to help
             * text classifier to infer what types of actions the caller may be interested in.
             */
            public Builder setHints(@Nullable @Hint List<String> hints) {
                mHints = hints;
                return this;
            }

            /** Sets the type config. */
            @NonNull
            public Builder setTypeConfig(@Nullable TypeConfig typeConfig) {
                mTypeConfig = typeConfig;
                return this;
            }

            /** Sets the maximum number of suggestions you want.
             * <p>
             * Value 0 means no restriction.
             */
            @NonNull
            public Builder setMaxSuggestions(@IntRange(from = 0) int maxSuggestions) {
                mMaxSuggestions = Preconditions.checkArgumentNonnegative(maxSuggestions);
                return this;
            }

            /** Builds the {@link Request} object. */
            @NonNull
            public Request build() {
                return new Request(
                        Collections.unmodifiableList(mConversation),
                        mTypeConfig == null ? new TypeConfig.Builder().build() : mTypeConfig,
                        mMaxSuggestions,
                        mHints == null
                                ? Collections.emptyList()
                                : Collections.unmodifiableList(mHints));
            }
        }
    }
}
