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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.SpannedString;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a list of actions suggested by a {@link TextClassifier} on a given conversation.
 *
 * @see TextClassifier#suggestConversationActions(Request)
 */
public final class ConversationActions implements Parcelable {

    public static final @android.annotation.NonNull Creator<ConversationActions> CREATOR =
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

    private final List<ConversationAction> mConversationActions;
    private final String mId;

    /** Constructs a {@link ConversationActions} object. */
    public ConversationActions(
            @NonNull List<ConversationAction> conversationActions, @Nullable String id) {
        mConversationActions =
                Collections.unmodifiableList(Preconditions.checkNotNull(conversationActions));
        mId = id;
    }

    private ConversationActions(Parcel in) {
        mConversationActions =
                Collections.unmodifiableList(in.createTypedArrayList(ConversationAction.CREATOR));
        mId = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeTypedList(mConversationActions);
        parcel.writeString(mId);
    }

    /**
     * Returns an immutable list of {@link ConversationAction} objects, which are ordered from high
     * confidence to low confidence.
     */
    @NonNull
    public List<ConversationAction> getConversationActions() {
        return mConversationActions;
    }

    /**
     * Returns the id, if one exists, for this object.
     */
    @Nullable
    public String getId() {
        return mId;
    }

    /** Represents a message in the conversation. */
    public static final class Message implements Parcelable {
        /**
         * Represents the local user.
         *
         * @see Builder#Builder(Person)
         */
        @NonNull
        public static final Person PERSON_USER_SELF =
                new Person.Builder()
                        .setKey("text-classifier-conversation-actions-user-self")
                        .build();

        /**
         * Represents the remote user.
         * <p>
         * If possible, you are suggested to create a {@link Person} object that can identify
         * the remote user better, so that the underlying model could differentiate between
         * different remote users.
         *
         * @see Builder#Builder(Person)
         */
        @NonNull
        public static final Person PERSON_USER_OTHERS =
                new Person.Builder()
                        .setKey("text-classifier-conversation-actions-user-others")
                        .build();

        @Nullable
        private final Person mAuthor;
        @Nullable
        private final ZonedDateTime mReferenceTime;
        @Nullable
        private final CharSequence mText;
        @NonNull
        private final Bundle mExtras;

        private Message(
                @Nullable Person author,
                @Nullable ZonedDateTime referenceTime,
                @Nullable CharSequence text,
                @NonNull Bundle bundle) {
            mAuthor = author;
            mReferenceTime = referenceTime;
            mText = text;
            mExtras = Preconditions.checkNotNull(bundle);
        }

        private Message(Parcel in) {
            mAuthor = in.readParcelable(null);
            mReferenceTime =
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
            parcel.writeInt(mReferenceTime != null ? 1 : 0);
            if (mReferenceTime != null) {
                parcel.writeString(mReferenceTime.format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
            }
            parcel.writeCharSequence(mText);
            parcel.writeBundle(mExtras);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Creator<Message> CREATOR =
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
        @NonNull
        public Person getAuthor() {
            return mAuthor;
        }

        /**
         * Returns the reference time of the message, for example it could be the compose or send
         * time of this message.
         */
        @Nullable
        public ZonedDateTime getReferenceTime() {
            return mReferenceTime;
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
            private ZonedDateTime mReferenceTime;
            @Nullable
            private CharSequence mText;
            @Nullable
            private Bundle mExtras;

            /**
             * Constructs a builder.
             *
             * @param author the person that composed the message, use {@link #PERSON_USER_SELF}
             *               to represent the local user. If it is not possible to identify the
             *               remote user that the local user is conversing with, use
             *               {@link #PERSON_USER_OTHERS} to represent a remote user.
             */
            public Builder(@NonNull Person author) {
                mAuthor = Preconditions.checkNotNull(author);
            }

            /** Sets the text of this message. */
            @NonNull
            public Builder setText(@Nullable CharSequence text) {
                mText = text;
                return this;
            }

            /**
             * Sets the reference time of this message, for example it could be the compose or send
             * time of this message.
             */
            @NonNull
            public Builder setReferenceTime(@Nullable ZonedDateTime referenceTime) {
                mReferenceTime = referenceTime;
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
                        mReferenceTime,
                        mText == null ? null : new SpannedString(mText),
                        mExtras == null ? new Bundle() : mExtras.deepCopy());
            }
        }
    }

    /**
     * A request object for generating conversation action suggestions.
     *
     * @see TextClassifier#suggestConversationActions(Request)
     */
    public static final class Request implements Parcelable {

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

        @NonNull
        private final List<Message> mConversation;
        @NonNull
        private final TextClassifier.EntityConfig mTypeConfig;
        private final int mMaxSuggestions;
        @NonNull
        @Hint
        private final List<String> mHints;
        @Nullable
        private String mCallingPackageName;
        @Nullable
        private final String mConversationId;

        private Request(
                @NonNull List<Message> conversation,
                @NonNull TextClassifier.EntityConfig typeConfig,
                int maxSuggestions,
                String conversationId,
                @Nullable @Hint List<String> hints) {
            mConversation = Preconditions.checkNotNull(conversation);
            mTypeConfig = Preconditions.checkNotNull(typeConfig);
            mMaxSuggestions = maxSuggestions;
            mConversationId = conversationId;
            mHints = hints;
        }

        private static Request readFromParcel(Parcel in) {
            List<Message> conversation = new ArrayList<>();
            in.readParcelableList(conversation, null);
            TextClassifier.EntityConfig typeConfig = in.readParcelable(null);
            int maxSuggestions = in.readInt();
            String conversationId = in.readString();
            List<String> hints = new ArrayList<>();
            in.readStringList(hints);
            String callingPackageName = in.readString();

            Request request = new Request(
                    conversation,
                    typeConfig,
                    maxSuggestions,
                    conversationId,
                    hints);
            request.setCallingPackageName(callingPackageName);
            return request;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeParcelableList(mConversation, flags);
            parcel.writeParcelable(mTypeConfig, flags);
            parcel.writeInt(mMaxSuggestions);
            parcel.writeString(mConversationId);
            parcel.writeStringList(mHints);
            parcel.writeString(mCallingPackageName);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Creator<Request> CREATOR =
                new Creator<Request>() {
                    @Override
                    public Request createFromParcel(Parcel in) {
                        return readFromParcel(in);
                    }

                    @Override
                    public Request[] newArray(int size) {
                        return new Request[size];
                    }
                };

        /** Returns the type config. */
        @NonNull
        public TextClassifier.EntityConfig getTypeConfig() {
            return mTypeConfig;
        }

        /** Returns an immutable list of messages that make up the conversation. */
        @NonNull
        public List<Message> getConversation() {
            return mConversation;
        }

        /**
         * Return the maximal number of suggestions the caller wants, value -1 means no restriction
         * and this is the default.
         */
        @IntRange(from = -1)
        public int getMaxSuggestions() {
            return mMaxSuggestions;
        }

        /**
         * Return an unique identifier of the conversation that is generating actions for. This
         * identifier is unique within the calling package only, so use it with
         * {@link #getCallingPackageName()}.
         */
        @Nullable
        public String getConversationId() {
            return mConversationId;
        }

        /** Returns an immutable list of hints */
        @Nullable
        @Hint
        public List<String> getHints() {
            return mHints;
        }

        /**
         * Sets the name of the package that is sending this request.
         * <p>
         * Package-private for SystemTextClassifier's use.
         * @hide
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void setCallingPackageName(@Nullable String callingPackageName) {
            mCallingPackageName = callingPackageName;
        }

        /**
         * Returns the name of the package that sent this request.
         * This returns {@code null} if no calling package name is set.
         */
        @Nullable
        public String getCallingPackageName() {
            return mCallingPackageName;
        }

        /** Builder object to construct the {@link Request} object. */
        public static final class Builder {
            @NonNull
            private List<Message> mConversation;
            @Nullable
            private TextClassifier.EntityConfig mTypeConfig;
            private int mMaxSuggestions = -1;
            @Nullable
            private String mConversationId;
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
            @NonNull
            public Builder setHints(@Nullable @Hint List<String> hints) {
                mHints = hints;
                return this;
            }

            /** Sets the type config. */
            @NonNull
            public Builder setTypeConfig(@Nullable TextClassifier.EntityConfig typeConfig) {
                mTypeConfig = typeConfig;
                return this;
            }

            /**
             * Sets the maximum number of suggestions you want. Value -1 means no restriction and
             * this is the default.
             */
            @NonNull
            public Builder setMaxSuggestions(@IntRange(from = -1) int maxSuggestions) {
                mMaxSuggestions = Preconditions.checkArgumentNonnegative(maxSuggestions);
                return this;
            }

            /**
             * Sets an unique identifier of the conversation that is generating actions for.
             */
            @NonNull
            public Builder setConversationId(@Nullable String conversationId) {
                mConversationId = conversationId;
                return this;
            }

            /** Builds the {@link Request} object. */
            @NonNull
            public Request build() {
                return new Request(
                        Collections.unmodifiableList(mConversation),
                        mTypeConfig == null
                                ? new TextClassifier.EntityConfig.Builder().build()
                                : mTypeConfig,
                        mMaxSuggestions,
                        mConversationId,
                        mHints == null
                                ? Collections.emptyList()
                                : Collections.unmodifiableList(mHints));
            }
        }
    }
}
