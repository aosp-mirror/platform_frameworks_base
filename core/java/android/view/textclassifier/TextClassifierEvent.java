/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * A text classifier event.
 */
// TODO: Comprehensive javadoc.
public final class TextClassifierEvent implements Parcelable {

    public static final Creator<TextClassifierEvent> CREATOR = new Creator<TextClassifierEvent>() {
        @Override
        public TextClassifierEvent createFromParcel(Parcel in) {
            return readFromParcel(in);
        }

        @Override
        public TextClassifierEvent[] newArray(int size) {
            return new TextClassifierEvent[size];
        }
    };

    /** @hide **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CATEGORY_UNDEFINED, CATEGORY_SELECTION, CATEGORY_LINKIFY,
            CATEGORY_CONVERSATION_ACTIONS, CATEGORY_LANGUAGE_DETECTION})
    public @interface Category {
        // For custom event categories, use range 1000+.
    }
    /** Undefined category */
    public static final int CATEGORY_UNDEFINED = 0;
    /** Smart selection */
    public static final int CATEGORY_SELECTION = 1;
    /** Linkify */
    public static final int CATEGORY_LINKIFY = 2;
    /** Conversation actions */
    public static final int CATEGORY_CONVERSATION_ACTIONS = 3;
    /** Language detection */
    public static final int CATEGORY_LANGUAGE_DETECTION = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_UNDEFINED, TYPE_SELECTION_STARTED, TYPE_SELECTION_MODIFIED,
             TYPE_SMART_SELECTION_SINGLE, TYPE_SMART_SELECTION_MULTI, TYPE_AUTO_SELECTION,
             TYPE_ACTIONS_SHOWN, TYPE_LINK_CLICKED, TYPE_OVERTYPE, TYPE_COPY_ACTION,
             TYPE_PASTE_ACTION, TYPE_CUT_ACTION, TYPE_SHARE_ACTION, TYPE_SMART_ACTION,
             TYPE_SELECTION_DRAG, TYPE_SELECTION_DESTROYED, TYPE_OTHER_ACTION, TYPE_SELECT_ALL,
             TYPE_SELECTION_RESET, TYPE_MANUAL_REPLY, TYPE_ACTIONS_GENERATED})
    public @interface Type {
        // For custom event types, use range 1,000,000+.
    }
    /** User started a new selection. */
    public static final int TYPE_UNDEFINED = 0;
    /** User started a new selection. */
    public static final int TYPE_SELECTION_STARTED = 1;
    /** User modified an existing selection. */
    public static final int TYPE_SELECTION_MODIFIED = 2;
    /** Smart selection triggered for a single token (word). */
    public static final int TYPE_SMART_SELECTION_SINGLE = 3;
    /** Smart selection triggered spanning multiple tokens (words). */
    public static final int TYPE_SMART_SELECTION_MULTI = 4;
    /** Something else other than user or the default TextClassifier triggered a selection. */
    public static final int TYPE_AUTO_SELECTION = 5;
    /** Smart actions shown to the user. */
    public static final int TYPE_ACTIONS_SHOWN = 6;
    /** User clicked a link. */
    public static final int TYPE_LINK_CLICKED = 7;
    /** User typed over the selection. */
    public static final int TYPE_OVERTYPE = 8;
    /** User clicked on Copy action. */
    public static final int TYPE_COPY_ACTION = 9;
    /** User clicked on Paste action. */
    public static final int TYPE_PASTE_ACTION = 10;
    /** User clicked on Cut action. */
    public static final int TYPE_CUT_ACTION = 11;
    /** User clicked on Share action. */
    public static final int TYPE_SHARE_ACTION = 12;
    /** User clicked on a Smart action. */
    public static final int TYPE_SMART_ACTION = 13;
    /** User dragged+dropped the selection. */
    public static final int TYPE_SELECTION_DRAG = 14;
    /** Selection is destroyed. */
    public static final int TYPE_SELECTION_DESTROYED = 15;
    /** User clicked on a custom action. */
    public static final int TYPE_OTHER_ACTION = 16;
    /** User clicked on Select All action */
    public static final int TYPE_SELECT_ALL = 17;
    /** User reset the smart selection. */
    public static final int TYPE_SELECTION_RESET = 18;
    /** User composed a reply. */
    public static final int TYPE_MANUAL_REPLY = 19;
    /** TextClassifier generated some actions */
    public static final int TYPE_ACTIONS_GENERATED = 20;

    @Category private final int mEventCategory;
    @Type private final int mEventType;
    @Nullable private final String[] mEntityTypes;
    @Nullable private final TextClassificationContext mEventContext;
    @Nullable private final String mResultId;
    private final int mEventIndex;
    private final long mEventTime;
    private final Bundle mExtras;

    // Smart selection.
    private final int mRelativeWordStartIndex;
    private final int mRelativeWordEndIndex;
    private final int mRelativeSuggestedWordStartIndex;
    private final int mRelativeSuggestedWordEndIndex;

    // Smart action.
    private final int[] mActionIndices;

    // Language detection.
    @Nullable private final String mLanguage;
    private final float mScore;

    @Nullable private final String mModelName;

    private TextClassifierEvent(
            int eventCategory,
            int eventType,
            String[] entityTypes,
            TextClassificationContext eventContext,
            String resultId,
            int eventIndex,
            long eventTime,
            Bundle extras,
            int relativeWordStartIndex,
            int relativeWordEndIndex,
            int relativeSuggestedWordStartIndex,
            int relativeSuggestedWordEndIndex,
            int[] actionIndex,
            String language,
            float score,
            String modelVersion) {
        mEventCategory = eventCategory;
        mEventType = eventType;
        mEntityTypes = entityTypes;
        mEventContext = eventContext;
        mResultId = resultId;
        mEventIndex = eventIndex;
        mEventTime = eventTime;
        mExtras = extras;
        mRelativeWordStartIndex = relativeWordStartIndex;
        mRelativeWordEndIndex = relativeWordEndIndex;
        mRelativeSuggestedWordStartIndex = relativeSuggestedWordStartIndex;
        mRelativeSuggestedWordEndIndex = relativeSuggestedWordEndIndex;
        mActionIndices = actionIndex;
        mLanguage = language;
        mScore = score;
        mModelName = modelVersion;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEventCategory);
        dest.writeInt(mEventType);
        dest.writeStringArray(mEntityTypes);
        dest.writeParcelable(mEventContext, flags);
        dest.writeString(mResultId);
        dest.writeInt(mEventIndex);
        dest.writeLong(mEventTime);
        dest.writeBundle(mExtras);
        dest.writeInt(mRelativeWordStartIndex);
        dest.writeInt(mRelativeWordEndIndex);
        dest.writeInt(mRelativeSuggestedWordStartIndex);
        dest.writeInt(mRelativeSuggestedWordEndIndex);
        dest.writeIntArray(mActionIndices);
        dest.writeString(mLanguage);
        dest.writeFloat(mScore);
        dest.writeString(mModelName);
    }

    private static TextClassifierEvent readFromParcel(Parcel in) {
        return new TextClassifierEvent(
                /* eventCategory= */ in.readInt(),
                /* eventType= */ in.readInt(),
                /* entityTypes=*/ in.readStringArray(),
                /* eventContext= */ in.readParcelable(null),
                /* resultId= */ in.readString(),
                /* eventIndex= */ in.readInt(),
                /* eventTime= */ in.readLong(),
                /* extras= */ in.readBundle(),
                /* relativeWordStartIndex= */ in.readInt(),
                /* relativeWordEndIndex= */ in.readInt(),
                /* relativeSuggestedWordStartIndex= */ in.readInt(),
                /* relativeSuggestedWordEndIndex= */ in.readInt(),
                /* actionIndices= */ in.createIntArray(),
                /* language= */ in.readString(),
                /* score= */ in.readFloat(),
                /* modelVersion= */ in.readString());
    }

    /**
     * Returns the event category. e.g. {@link #CATEGORY_SELECTION}.
     */
    @Category
    public int getEventCategory() {
        return mEventCategory;
    }

    /**
     * Returns the event type. e.g. {@link #TYPE_SELECTION_STARTED}.
     */
    @Type
    public int getEventType() {
        return mEventType;
    }

    /**
     * Returns an array of entity types. e.g. {@link TextClassifier#TYPE_ADDRESS}.
     */
    @NonNull
    public String[] getEntityTypes() {
        return mEntityTypes;
    }

    /**
     * Returns the event context.
     */
    @Nullable
    public TextClassificationContext getEventContext() {
        return mEventContext;
    }

    /**
     * Returns the id of the text classifier result related to this event.
     */
    @Nullable
    public String getResultId() {
        return mResultId;
    }

    /**
     * Returns the index of this event in the series of event it belongs to.
     */
    public int getEventIndex() {
        return mEventIndex;
    }

    // TODO: Remove this API.
    /**
     * Returns the time this event occurred. This is the number of milliseconds since
     * January 1, 1970, 00:00:00 GMT. 0 indicates not set.
     */
    public long getEventTime() {
        return mEventTime;
    }

    /**
     * Returns a bundle containing non-structured extra information about this event.
     *
     * <p><b>NOTE: </b>Do not modify this bundle.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * For smart selection. Returns the relative word index of the start of the selection.
     */
    public int getRelativeWordStartIndex() {
        return mRelativeWordStartIndex;
    }

    /**
     * For smart selection. Returns the relative word (exclusive) index of the end of the selection.
     */
    public int getRelativeWordEndIndex() {
        return mRelativeWordEndIndex;
    }

    /**
     * For smart selection. Returns the relative word index of the start of the smart selection.
     */
    public int getRelativeSuggestedWordStartIndex() {
        return mRelativeSuggestedWordStartIndex;
    }

    /**
     * For smart selection. Returns the relative word (exclusive) index of the end of the
     * smart selection.
     */
    public int getRelativeSuggestedWordEndIndex() {
        return mRelativeSuggestedWordEndIndex;
    }

    /**
     * Returns the indices of the actions relating to this event.
     * Actions are usually returned by the text classifier in priority order with the most
     * preferred action at index 0. This list gives an indication of the position of the actions
     * that are being reported.
     */
    @NonNull
    public int[] getActionIndices() {
        return mActionIndices;
    }

    /**
     * For language detection. Returns the language tag for the detected locale.
     * @see java.util.Locale#forLanguageTag(String).
     */
    @Nullable
    public String getLanguage() {
        return mLanguage;
    }

    /**
     * Returns the score of the suggestion.
     */
    public float getScore() {
        return mScore;
    }

    /**
     * Returns the model name.
     * @hide
     */
    @Nullable
    public String getModelName() {
        return mModelName;
    }

    /**
     * Builder to build a text classifier event.
     */
    public static final class Builder {

        private final int mEventCategory;
        private final int mEventType;
        private String[] mEntityTypes = new String[0];
        @Nullable private TextClassificationContext mEventContext;
        @Nullable private String mResultId;
        private int mEventIndex;
        private long mEventTime;
        @Nullable private Bundle mExtras;
        private int mRelativeWordStartIndex;
        private int mRelativeWordEndIndex;
        private int mRelativeSuggestedWordStartIndex;
        private int mRelativeSuggestedWordEndIndex;
        private int[] mActionIndices = new int[0];
        @Nullable private String mLanguage;
        private float mScore;

        private String mModelName;

        /**
         * Creates a builder for building {@link TextClassifierEvent}s.
         *
         * @param eventCategory The event category. e.g. {@link #CATEGORY_SELECTION}
         * @param eventType The event type. e.g. {@link #TYPE_SELECTION_STARTED}
         */
        public Builder(@Category int eventCategory, @Type int eventType) {
            mEventCategory = eventCategory;
            mEventType = eventType;
        }

        /**
         * Sets the entity types. e.g. {@link TextClassifier#TYPE_ADDRESS}.
         */
        @NonNull
        public Builder setEntityTypes(@NonNull String... entityTypes) {
            mEntityTypes = new String[entityTypes.length];
            System.arraycopy(entityTypes, 0, mEntityTypes, 0, entityTypes.length);
            return this;
        }

        /**
         * Sets the event context.
         */
        @NonNull
        public Builder setEventContext(@Nullable TextClassificationContext eventContext) {
            mEventContext = eventContext;
            return this;
        }

        /**
         * Sets the id of the text classifier result related to this event.
         */
        @NonNull
        public Builder setResultId(@Nullable String resultId) {
            mResultId = resultId;
            return this;
        }

        /**
         * Sets the index of this events in the series of events it belongs to.
         */
        @NonNull
        public Builder setEventIndex(int eventIndex) {
            mEventIndex = eventIndex;
            return this;
        }

        // TODO: Remove this API.
        /**
         * Sets the time this event occurred. This is the number of milliseconds since
         * January 1, 1970, 00:00:00 GMT. 0 indicates not set.
         */
        @NonNull
        public Builder setEventTime(long eventTime) {
            mEventTime = eventTime;
            return this;
        }

        /**
         * Sets a bundle containing non-structured extra information about the event.
         *
         * <p><b>NOTE: </b>Prefer to set only immutable values on the bundle otherwise, avoid
         * updating the internals of this bundle as it may have unexpected consequences on the
         * clients of the built event object. For similar reasons, avoid depending on mutable
         * objects in this bundle.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = Preconditions.checkNotNull(extras);
            return this;
        }

        /**
         * For smart selection. Sets the relative word index of the start of the selection.
         */
        @NonNull
        public Builder setRelativeWordStartIndex(int relativeWordStartIndex) {
            mRelativeWordStartIndex = relativeWordStartIndex;
            return this;
        }

        /**
         * For smart selection. Sets the relative word (exclusive) index of the end of the
         * selection.
         */
        @NonNull
        public Builder setRelativeWordEndIndex(int relativeWordEndIndex) {
            mRelativeWordEndIndex = relativeWordEndIndex;
            return this;
        }

        /**
         * For smart selection. Sets the relative word index of the start of the smart selection.
         */
        @NonNull
        public Builder setRelativeSuggestedWordStartIndex(int relativeSuggestedWordStartIndex) {
            mRelativeSuggestedWordStartIndex = relativeSuggestedWordStartIndex;
            return this;
        }

        /**
         * For smart selection. Sets the relative word (exclusive) index of the end of the
         * smart selection.
         */
        @NonNull
        public Builder setRelativeSuggestedWordEndIndex(int relativeSuggestedWordEndIndex) {
            mRelativeSuggestedWordEndIndex = relativeSuggestedWordEndIndex;
            return this;
        }

        /**
         * Sets the indices of the actions involved in this event. Actions are usually returned by
         * the text classifier in priority order with the most preferred action at index 0.
         * This index gives an indication of the position of the action that is being reported.
         */
        @NonNull
        public Builder setActionIndices(@NonNull int... actionIndices) {
            mActionIndices = new int[actionIndices.length];
            System.arraycopy(actionIndices, 0, mActionIndices, 0, actionIndices.length);
            return this;
        }

        /**
         * For language detection. Sets the language tag for the detected locale.
         * @see java.util.Locale#forLanguageTag(String).
         */
        @NonNull
        public Builder setLanguage(@Nullable String language) {
            mLanguage = language;
            return this;
        }

        /**
         * Sets the score of the suggestion.
         */
        @NonNull
        public Builder setScore(float score) {
            mScore = score;
            return this;
        }

        /**
         * Sets the model name string.
         * @hide
         */
        public Builder setModelName(@Nullable String modelVersion) {
            mModelName = modelVersion;
            return this;
        }

        /**
         * Builds and returns a text classifier event.
         */
        @NonNull
        public TextClassifierEvent build() {
            mExtras = mExtras == null ? Bundle.EMPTY : mExtras;
            return new TextClassifierEvent(
                    mEventCategory,
                    mEventType,
                    mEntityTypes,
                    mEventContext,
                    mResultId,
                    mEventIndex,
                    mEventTime,
                    mExtras,
                    mRelativeWordStartIndex,
                    mRelativeWordEndIndex,
                    mRelativeSuggestedWordStartIndex,
                    mRelativeSuggestedWordEndIndex,
                    mActionIndices,
                    mLanguage,
                    mScore,
                    mModelName);
        }
        // TODO: Add build(boolean validate).
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(128);
        out.append("TextClassifierEvent{");
        out.append("mEventCategory=").append(mEventCategory);
        out.append(", mEventTypes=").append(Arrays.toString(mEntityTypes));
        out.append(", mEventContext=").append(mEventContext);
        out.append(", mResultId=").append(mResultId);
        out.append(", mEventIndex=").append(mEventIndex);
        out.append(", mEventTime=").append(mEventTime);
        out.append(", mExtras=").append(mExtras);
        out.append(", mRelativeWordStartIndex=").append(mRelativeWordStartIndex);
        out.append(", mRelativeWordEndIndex=").append(mRelativeWordEndIndex);
        out.append(", mRelativeSuggestedWordStartIndex=").append(mRelativeSuggestedWordStartIndex);
        out.append(", mRelativeSuggestedWordEndIndex=").append(mRelativeSuggestedWordEndIndex);
        out.append(", mActionIndices=").append(Arrays.toString(mActionIndices));
        out.append(", mLanguage=").append(mLanguage);
        out.append(", mScore=").append(mScore);
        out.append(", mModelName=").append(mModelName);
        out.append("}");
        return out.toString();
    }
}
