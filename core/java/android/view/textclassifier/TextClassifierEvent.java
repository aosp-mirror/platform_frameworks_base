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
import android.icu.util.ULocale;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * This class represents events that are sent by components to the {@link TextClassifier} to report
 * something of note that relates to a feature powered by the TextClassifier. The TextClassifier may
 * log these events or use them to improve future responses to queries.
 * <p>
 * Each category of events has its their own subclass. Events of each type have an associated
 * set of related properties. You can find their specification in the subclasses.
 */
public abstract class TextClassifierEvent implements Parcelable {

    private static final int PARCEL_TOKEN_TEXT_SELECTION_EVENT = 1;
    private static final int PARCEL_TOKEN_TEXT_LINKIFY_EVENT = 2;
    private static final int PARCEL_TOKEN_CONVERSATION_ACTION_EVENT = 3;
    private static final int PARCEL_TOKEN_LANGUAGE_DETECTION_EVENT = 4;

    /** @hide **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CATEGORY_SELECTION, CATEGORY_LINKIFY,
            CATEGORY_CONVERSATION_ACTIONS, CATEGORY_LANGUAGE_DETECTION})
    public @interface Category {
        // For custom event categories, use range 1000+.
    }

    /**
     * Smart selection
     *
     * @see TextSelectionEvent
     */
    public static final int CATEGORY_SELECTION = 1;
    /**
     * Linkify
     *
     * @see TextLinkifyEvent
     */
    public static final int CATEGORY_LINKIFY = 2;
    /**
     *  Conversation actions
     *
     * @see ConversationActionsEvent
     */
    public static final int CATEGORY_CONVERSATION_ACTIONS = 3;
    /**
     * Language detection
     *
     * @see LanguageDetectionEvent
     */
    public static final int CATEGORY_LANGUAGE_DETECTION = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_SELECTION_STARTED, TYPE_SELECTION_MODIFIED,
            TYPE_SMART_SELECTION_SINGLE, TYPE_SMART_SELECTION_MULTI, TYPE_AUTO_SELECTION,
            TYPE_ACTIONS_SHOWN, TYPE_LINK_CLICKED, TYPE_OVERTYPE, TYPE_COPY_ACTION,
            TYPE_PASTE_ACTION, TYPE_CUT_ACTION, TYPE_SHARE_ACTION, TYPE_SMART_ACTION,
            TYPE_SELECTION_DRAG, TYPE_SELECTION_DESTROYED, TYPE_OTHER_ACTION, TYPE_SELECT_ALL,
            TYPE_SELECTION_RESET, TYPE_MANUAL_REPLY, TYPE_ACTIONS_GENERATED, TYPE_LINKS_GENERATED,
            TYPE_READ_CLIPBOARD})
    public @interface Type {
        // For custom event types, use range 1,000,000+.
    }

    // All these event type constants are required to match with those defined in
    // textclassifier_enums.proto.
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
    /** Some text links were generated.*/
    public static final int TYPE_LINKS_GENERATED = 21;
    /**
     * Read a clipboard.
     * TODO: Make this public.
     *
     * @hide
     */
    public static final int TYPE_READ_CLIPBOARD = 22;

    @Category
    private final int mEventCategory;
    @Type
    private final int mEventType;
    @Nullable
    private final String[] mEntityTypes;
    @Nullable
    private TextClassificationContext mEventContext;
    @Nullable
    private final String mResultId;
    private final int mEventIndex;
    private final float[] mScores;
    @Nullable
    private final String mModelName;
    private final int[] mActionIndices;
    @Nullable
    private final ULocale mLocale;
    private final Bundle mExtras;

    /**
     * Session id holder to help with converting this event to the legacy SelectionEvent.
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Nullable
    public TextClassificationSessionId mHiddenTempSessionId;

    private TextClassifierEvent(Builder builder) {
        mEventCategory = builder.mEventCategory;
        mEventType = builder.mEventType;
        mEntityTypes = builder.mEntityTypes;
        mEventContext = builder.mEventContext;
        mResultId = builder.mResultId;
        mEventIndex = builder.mEventIndex;
        mScores = builder.mScores;
        mModelName = builder.mModelName;
        mActionIndices = builder.mActionIndices;
        mLocale = builder.mLocale;
        mExtras = builder.mExtras == null ? Bundle.EMPTY : builder.mExtras;
    }

    private TextClassifierEvent(Parcel in) {
        mEventCategory = in.readInt();
        mEventType = in.readInt();
        mEntityTypes = in.readStringArray();
        mEventContext = in.readParcelable(null, android.view.textclassifier.TextClassificationContext.class);
        mResultId = in.readString();
        mEventIndex = in.readInt();
        int scoresLength = in.readInt();
        mScores = new float[scoresLength];
        in.readFloatArray(mScores);
        mModelName = in.readString();
        mActionIndices = in.createIntArray();
        final String languageTag = in.readString();
        mLocale = languageTag == null ? null : ULocale.forLanguageTag(languageTag);
        mExtras = in.readBundle();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<TextClassifierEvent> CREATOR = new Creator<TextClassifierEvent>() {
        @Override
        public TextClassifierEvent createFromParcel(Parcel in) {
            int token = in.readInt();
            if (token == PARCEL_TOKEN_TEXT_SELECTION_EVENT) {
                return new TextSelectionEvent(in);
            }
            if (token == PARCEL_TOKEN_TEXT_LINKIFY_EVENT) {
                return new TextLinkifyEvent(in);
            }
            if (token == PARCEL_TOKEN_LANGUAGE_DETECTION_EVENT) {
                return new LanguageDetectionEvent(in);
            }
            if (token == PARCEL_TOKEN_CONVERSATION_ACTION_EVENT) {
                return new ConversationActionsEvent(in);
            }
            throw new IllegalStateException("Unexpected input event type token in parcel.");
        }

        @Override
        public TextClassifierEvent[] newArray(int size) {
            return new TextClassifierEvent[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(getParcelToken());
        dest.writeInt(mEventCategory);
        dest.writeInt(mEventType);
        dest.writeStringArray(mEntityTypes);
        dest.writeParcelable(mEventContext, flags);
        dest.writeString(mResultId);
        dest.writeInt(mEventIndex);
        dest.writeInt(mScores.length);
        dest.writeFloatArray(mScores);
        dest.writeString(mModelName);
        dest.writeIntArray(mActionIndices);
        dest.writeString(mLocale == null ? null : mLocale.toLanguageTag());
        dest.writeBundle(mExtras);
    }

    private int getParcelToken() {
        if (this instanceof TextSelectionEvent) {
            return PARCEL_TOKEN_TEXT_SELECTION_EVENT;
        }
        if (this instanceof TextLinkifyEvent) {
            return PARCEL_TOKEN_TEXT_LINKIFY_EVENT;
        }
        if (this instanceof LanguageDetectionEvent) {
            return PARCEL_TOKEN_LANGUAGE_DETECTION_EVENT;
        }
        if (this instanceof ConversationActionsEvent) {
            return PARCEL_TOKEN_CONVERSATION_ACTION_EVENT;
        }
        throw new IllegalArgumentException("Unexpected type: " + this.getClass().getSimpleName());
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
     *
     * @see Builder#setEntityTypes(String...) for supported types.
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
     * Sets the event context.
     * <p>
     * Package-private for SystemTextClassifier's use.
     */
    void setEventContext(@Nullable TextClassificationContext eventContext) {
        mEventContext = eventContext;
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

    /**
     * Returns the scores of the suggestions.
     */
    @NonNull
    public float[] getScores() {
        return mScores;
    }

    /**
     * Returns the model name.
     */
    @Nullable
    public String getModelName() {
        return mModelName;
    }

    /**
     * Returns the indices of the actions relating to this event.
     * Actions are usually returned by the text classifier in priority order with the most
     * preferred action at index 0. This list gives an indication of the position of the actions
     * that are being reported.
     *
     * @see Builder#setActionIndices(int...)
     */
    @NonNull
    public int[] getActionIndices() {
        return mActionIndices;
    }

    /**
     * Returns the detected locale.
     */
    @Nullable
    public ULocale getLocale() {
        return mLocale;
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

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(128);
        out.append(this.getClass().getSimpleName());
        out.append("{");
        out.append("mEventCategory=").append(mEventCategory);
        out.append(", mEventType=").append(mEventType);
        out.append(", mEntityTypes=").append(Arrays.toString(mEntityTypes));
        out.append(", mEventContext=").append(mEventContext);
        out.append(", mResultId=").append(mResultId);
        out.append(", mEventIndex=").append(mEventIndex);
        out.append(", mExtras=").append(mExtras);
        out.append(", mScores=").append(Arrays.toString(mScores));
        out.append(", mModelName=").append(mModelName);
        out.append(", mActionIndices=").append(Arrays.toString(mActionIndices));
        toString(out);
        out.append("}");
        return out.toString();
    }

    /**
     * Overrides this to append extra fields to the output of {@link #toString()}.
     * <p>
     * Extra fields should be  formatted like this: ", {field_name}={field_value}".
     */
    void toString(StringBuilder out) {}

    /**
     * Returns a {@link SelectionEvent} equivalent of this event; or {@code null} if it can not be
     * converted to a {@link SelectionEvent}.
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Nullable
    public final SelectionEvent toSelectionEvent() {
        final int invocationMethod;
        switch (getEventCategory()) {
            case TextClassifierEvent.CATEGORY_SELECTION:
                invocationMethod = SelectionEvent.INVOCATION_MANUAL;
                break;
            case TextClassifierEvent.CATEGORY_LINKIFY:
                invocationMethod = SelectionEvent.INVOCATION_LINK;
                break;
            default:
                // Cannot be converted to a SelectionEvent.
                return null;
        }

        final String entityType = getEntityTypes().length > 0
                ? getEntityTypes()[0] : TextClassifier.TYPE_UNKNOWN;
        final SelectionEvent out = new SelectionEvent(
                /* absoluteStart= */ 0,
                /* absoluteEnd= */ 0,
                /* eventType= */0,
                entityType,
                SelectionEvent.INVOCATION_UNKNOWN,
                SelectionEvent.NO_SIGNATURE);
        out.setInvocationMethod(invocationMethod);

        final TextClassificationContext eventContext = getEventContext();
        if (eventContext != null) {
            out.setTextClassificationSessionContext(getEventContext());
        }
        out.setSessionId(mHiddenTempSessionId);
        final String resultId = getResultId();
        out.setResultId(resultId == null ? SelectionEvent.NO_SIGNATURE : resultId);
        out.setEventIndex(getEventIndex());


        final int eventType;
        switch (getEventType()) {
            case TextClassifierEvent.TYPE_SELECTION_STARTED:
                eventType = SelectionEvent.EVENT_SELECTION_STARTED;
                break;
            case TextClassifierEvent.TYPE_SELECTION_MODIFIED:
                eventType = SelectionEvent.EVENT_SELECTION_MODIFIED;
                break;
            case TextClassifierEvent.TYPE_SMART_SELECTION_SINGLE:
                eventType = SelectionEvent.EVENT_SMART_SELECTION_SINGLE;
                break;
            case TextClassifierEvent.TYPE_SMART_SELECTION_MULTI:
                eventType = SelectionEvent.EVENT_SMART_SELECTION_MULTI;
                break;
            case TextClassifierEvent.TYPE_AUTO_SELECTION:
                eventType = SelectionEvent.EVENT_AUTO_SELECTION;
                break;
            case TextClassifierEvent.TYPE_OVERTYPE:
                eventType = SelectionEvent.ACTION_OVERTYPE;
                break;
            case TextClassifierEvent.TYPE_COPY_ACTION:
                eventType = SelectionEvent.ACTION_COPY;
                break;
            case TextClassifierEvent.TYPE_PASTE_ACTION:
                eventType = SelectionEvent.ACTION_PASTE;
                break;
            case TextClassifierEvent.TYPE_CUT_ACTION:
                eventType = SelectionEvent.ACTION_CUT;
                break;
            case TextClassifierEvent.TYPE_SHARE_ACTION:
                eventType = SelectionEvent.ACTION_SHARE;
                break;
            case TextClassifierEvent.TYPE_SMART_ACTION:
                eventType = SelectionEvent.ACTION_SMART_SHARE;
                break;
            case TextClassifierEvent.TYPE_SELECTION_DRAG:
                eventType = SelectionEvent.ACTION_DRAG;
                break;
            case TextClassifierEvent.TYPE_SELECTION_DESTROYED:
                eventType = SelectionEvent.ACTION_ABANDON;
                break;
            case TextClassifierEvent.TYPE_OTHER_ACTION:
                eventType = SelectionEvent.ACTION_OTHER;
                break;
            case TextClassifierEvent.TYPE_SELECT_ALL:
                eventType = SelectionEvent.ACTION_SELECT_ALL;
                break;
            case TextClassifierEvent.TYPE_SELECTION_RESET:
                eventType = SelectionEvent.ACTION_RESET;
                break;
            default:
                eventType = 0;
                break;
        }
        out.setEventType(eventType);

        if (this instanceof TextClassifierEvent.TextSelectionEvent) {
            final TextClassifierEvent.TextSelectionEvent selEvent =
                    (TextClassifierEvent.TextSelectionEvent) this;
            // TODO: Ideally, we should have these fields in events of type
            // TextClassifierEvent.TextLinkifyEvent events too but we're now past the API deadline
            // and will have to do with these fields being set only in TextSelectionEvent events.
            // Fix this at the next API bump.
            out.setStart(selEvent.getRelativeWordStartIndex());
            out.setEnd(selEvent.getRelativeWordEndIndex());
            out.setSmartStart(selEvent.getRelativeSuggestedWordStartIndex());
            out.setSmartEnd(selEvent.getRelativeSuggestedWordEndIndex());
        }

        return out;
    }

    /**
     * Builder to build a text classifier event.
     *
     * @param <T> The subclass to be built.
     */
    public abstract static class Builder<T extends Builder<T>> {

        private final int mEventCategory;
        private final int mEventType;
        private String[] mEntityTypes = new String[0];
        @Nullable
        private TextClassificationContext mEventContext;
        @Nullable
        private String mResultId;
        private int mEventIndex;
        private float[] mScores = new float[0];
        @Nullable
        private String mModelName;
        private int[] mActionIndices = new int[0];
        @Nullable
        private ULocale mLocale;
        @Nullable
        private Bundle mExtras;

        /**
         * Creates a builder for building {@link TextClassifierEvent}s.
         *
         * @param eventCategory The event category. e.g. {@link #CATEGORY_SELECTION}
         * @param eventType     The event type. e.g. {@link #TYPE_SELECTION_STARTED}
         */
        private Builder(@Category int eventCategory, @Type int eventType) {
            mEventCategory = eventCategory;
            mEventType = eventType;
        }

        /**
         * Sets the entity types. e.g. {@link TextClassifier#TYPE_ADDRESS}.
         * <p>
         * Supported types:
         * <p>See {@link TextClassifier} types
         * <p>See {@link ConversationAction} types
         * <p>See {@link ULocale#toLanguageTag()}
         */
        @NonNull
        public T setEntityTypes(@NonNull String... entityTypes) {
            Objects.requireNonNull(entityTypes);
            mEntityTypes = new String[entityTypes.length];
            System.arraycopy(entityTypes, 0, mEntityTypes, 0, entityTypes.length);
            return self();
        }

        /**
         * Sets the event context.
         */
        @NonNull
        public T setEventContext(@Nullable TextClassificationContext eventContext) {
            mEventContext = eventContext;
            return self();
        }

        /**
         * Sets the id of the text classifier result related to this event.
         */
        @NonNull
        public T setResultId(@Nullable String resultId) {
            mResultId = resultId;
            return self();
        }

        /**
         * Sets the index of this event in the series of events it belongs to.
         */
        @NonNull
        public T setEventIndex(int eventIndex) {
            mEventIndex = eventIndex;
            return self();
        }

        /**
         * Sets the scores of the suggestions.
         */
        @NonNull
        public T setScores(@NonNull float... scores) {
            Objects.requireNonNull(scores);
            mScores = new float[scores.length];
            System.arraycopy(scores, 0, mScores, 0, scores.length);
            return self();
        }

        /**
         * Sets the model name string.
         */
        @NonNull
        public T setModelName(@Nullable String modelVersion) {
            mModelName = modelVersion;
            return self();
        }

        /**
         * Sets the indices of the actions involved in this event. Actions are usually returned by
         * the text classifier in priority order with the most preferred action at index 0.
         * These indices give an indication of the position of the actions that are being reported.
         * <p>
         * E.g.
         * <pre>
         *   // 3 smart actions are shown at index 0, 1, 2 respectively in response to a link click.
         *   new TextClassifierEvent.Builder(CATEGORY_LINKIFY, TYPE_ACTIONS_SHOWN)
         *       .setEventIndex(0, 1, 2)
         *       ...
         *       .build();
         *
         *   ...
         *
         *   // Smart action at index 1 is activated.
         *   new TextClassifierEvent.Builder(CATEGORY_LINKIFY, TYPE_SMART_ACTION)
         *       .setEventIndex(1)
         *       ...
         *       .build();
         * </pre>
         *
         * @see TextClassification#getActions()
         */
        @NonNull
        public T setActionIndices(@NonNull int... actionIndices) {
            mActionIndices = new int[actionIndices.length];
            System.arraycopy(actionIndices, 0, mActionIndices, 0, actionIndices.length);
            return self();
        }

        /**
         * Sets the detected locale.
         */
        @NonNull
        public T setLocale(@Nullable ULocale locale) {
            mLocale = locale;
            return self();
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
        public T setExtras(@NonNull Bundle extras) {
            mExtras = Objects.requireNonNull(extras);
            return self();
        }

        abstract T self();
    }

    /**
     * This class represents events that are related to the smart text selection feature.
     * <p>
     * <pre>
     *     // User started a selection. e.g. "York" in text "New York City, NY".
     *     new TextSelectionEvent.Builder(TYPE_SELECTION_STARTED)
     *         .setEventContext(classificationContext)
     *         .setEventIndex(0)
     *         .build();
     *
     *     // System smart-selects a recognized entity. e.g. "New York City".
     *     new TextSelectionEvent.Builder(TYPE_SMART_SELECTION_MULTI)
     *         .setEventContext(classificationContext)
     *         .setResultId(textSelection.getId())
     *         .setRelativeWordStartIndex(-1) // Goes back one word to "New" from "York".
     *         .setRelativeWordEndIndex(2)    // Goes forward 2 words from "York" to start of ",".
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setEventIndex(1)
     *         .build();
     *
     *     // User resets the selection to the original selection. i.e. "York".
     *     new TextSelectionEvent.Builder(TYPE_SELECTION_RESET)
     *         .setEventContext(classificationContext)
     *         .setResultId(textSelection.getId())
     *         .setRelativeSuggestedWordStartIndex(-1) // Repeated from above.
     *         .setRelativeSuggestedWordEndIndex(2)    // Repeated from above.
     *         .setRelativeWordStartIndex(0)           // Original selection is always at (0, 1].
     *         .setRelativeWordEndIndex(1)
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setEventIndex(2)
     *         .build();
     *
     *     // User modified the selection. e.g. "New".
     *     new TextSelectionEvent.Builder(TYPE_SELECTION_MODIFIED)
     *         .setEventContext(classificationContext)
     *         .setResultId(textSelection.getId())
     *         .setRelativeSuggestedWordStartIndex(-1) // Repeated from above.
     *         .setRelativeSuggestedWordEndIndex(2)    // Repeated from above.
     *         .setRelativeWordStartIndex(-1)          // Goes backward one word from "York" to
     *         "New".
     *         .setRelativeWordEndIndex(0)             // Goes backward one word to exclude "York".
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setEventIndex(3)
     *         .build();
     *
     *     // Smart (contextual) actions (at indices, 0, 1, 2) presented to the user.
     *     // e.g. "Map", "Ride share", "Explore".
     *     new TextSelectionEvent.Builder(TYPE_ACTIONS_SHOWN)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setActionIndices(0, 1, 2)
     *         .setEventIndex(4)
     *         .build();
     *
     *     // User chooses the "Copy" action.
     *     new TextSelectionEvent.Builder(TYPE_COPY_ACTION)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setEventIndex(5)
     *         .build();
     *
     *     // User chooses smart action at index 1. i.e. "Ride share".
     *     new TextSelectionEvent.Builder(TYPE_SMART_ACTION)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setActionIndices(1)
     *         .setEventIndex(5)
     *         .build();
     *
     *     // Selection dismissed.
     *     new TextSelectionEvent.Builder(TYPE_SELECTION_DESTROYED)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setEventIndex(6)
     *         .build();
     * </pre>
     * <p>
     */
    public static final class TextSelectionEvent extends TextClassifierEvent implements Parcelable {

        @NonNull
        public static final Creator<TextSelectionEvent> CREATOR =
                new Creator<TextSelectionEvent>() {
                    @Override
                    public TextSelectionEvent createFromParcel(Parcel in) {
                        in.readInt(); // skip token, we already know this is a TextSelectionEvent
                        return new TextSelectionEvent(in);
                    }

                    @Override
                    public TextSelectionEvent[] newArray(int size) {
                        return new TextSelectionEvent[size];
                    }
                };

        final int mRelativeWordStartIndex;
        final int mRelativeWordEndIndex;
        final int mRelativeSuggestedWordStartIndex;
        final int mRelativeSuggestedWordEndIndex;

        private TextSelectionEvent(TextSelectionEvent.Builder builder) {
            super(builder);
            mRelativeWordStartIndex = builder.mRelativeWordStartIndex;
            mRelativeWordEndIndex = builder.mRelativeWordEndIndex;
            mRelativeSuggestedWordStartIndex = builder.mRelativeSuggestedWordStartIndex;
            mRelativeSuggestedWordEndIndex = builder.mRelativeSuggestedWordEndIndex;
        }

        private TextSelectionEvent(Parcel in) {
            super(in);
            mRelativeWordStartIndex = in.readInt();
            mRelativeWordEndIndex = in.readInt();
            mRelativeSuggestedWordStartIndex = in.readInt();
            mRelativeSuggestedWordEndIndex = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mRelativeWordStartIndex);
            dest.writeInt(mRelativeWordEndIndex);
            dest.writeInt(mRelativeSuggestedWordStartIndex);
            dest.writeInt(mRelativeSuggestedWordEndIndex);
        }

        /**
         * Returns the relative word index of the start of the selection.
         */
        public int getRelativeWordStartIndex() {
            return mRelativeWordStartIndex;
        }

        /**
         * Returns the relative word (exclusive) index of the end of the selection.
         */
        public int getRelativeWordEndIndex() {
            return mRelativeWordEndIndex;
        }

        /**
         * Returns the relative word index of the start of the smart selection.
         */
        public int getRelativeSuggestedWordStartIndex() {
            return mRelativeSuggestedWordStartIndex;
        }

        /**
         * Returns the relative word (exclusive) index of the end of the
         * smart selection.
         */
        public int getRelativeSuggestedWordEndIndex() {
            return mRelativeSuggestedWordEndIndex;
        }

        @Override
        void toString(StringBuilder out) {
            out.append(", getRelativeWordStartIndex=").append(mRelativeWordStartIndex);
            out.append(", getRelativeWordEndIndex=").append(mRelativeWordEndIndex);
            out.append(", getRelativeSuggestedWordStartIndex=")
                    .append(mRelativeSuggestedWordStartIndex);
            out.append(", getRelativeSuggestedWordEndIndex=")
                    .append(mRelativeSuggestedWordEndIndex);
        }

        /**
         * Builder class for {@link TextSelectionEvent}.
         */
        public static final class Builder extends
                TextClassifierEvent.Builder<TextSelectionEvent.Builder> {
            int mRelativeWordStartIndex;
            int mRelativeWordEndIndex;
            int mRelativeSuggestedWordStartIndex;
            int mRelativeSuggestedWordEndIndex;

            /**
             * Creates a builder for building {@link TextSelectionEvent}s.
             *
             * @param eventType     The event type. e.g. {@link #TYPE_SELECTION_STARTED}
             */
            public Builder(@Type int eventType) {
                super(CATEGORY_SELECTION, eventType);
            }

            /**
             * Sets the relative word index of the start of the selection.
             */
            @NonNull
            public Builder setRelativeWordStartIndex(int relativeWordStartIndex) {
                mRelativeWordStartIndex = relativeWordStartIndex;
                return this;
            }

            /**
             * Sets the relative word (exclusive) index of the end of the
             * selection.
             */
            @NonNull
            public Builder setRelativeWordEndIndex(int relativeWordEndIndex) {
                mRelativeWordEndIndex = relativeWordEndIndex;
                return this;
            }

            /**
             * Sets the relative word index of the start of the smart
             * selection.
             */
            @NonNull
            public Builder setRelativeSuggestedWordStartIndex(int relativeSuggestedWordStartIndex) {
                mRelativeSuggestedWordStartIndex = relativeSuggestedWordStartIndex;
                return this;
            }

            /**
             * Sets the relative word (exclusive) index of the end of the
             * smart selection.
             */
            @NonNull
            public Builder setRelativeSuggestedWordEndIndex(int relativeSuggestedWordEndIndex) {
                mRelativeSuggestedWordEndIndex = relativeSuggestedWordEndIndex;
                return this;
            }

            @Override
            TextSelectionEvent.Builder self() {
                return this;
            }

            /**
             * Builds and returns a {@link TextSelectionEvent}.
             */
            @NonNull
            public TextSelectionEvent build() {
                return new TextSelectionEvent(this);
            }
        }
    }

    /**
     * This class represents events that are related to the smart linkify feature.
     * <p>
     * <pre>
     *     // User clicked on a link.
     *     new TextLinkifyEvent.Builder(TYPE_LINK_CLICKED)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setEventIndex(0)
     *         .build();
     *
     *     // Smart (contextual) actions presented to the user in response to a link click.
     *     new TextLinkifyEvent.Builder(TYPE_ACTIONS_SHOWN)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setActionIndices(range(textClassification.getActions().size()))
     *         .setEventIndex(1)
     *         .build();
     *
     *     // User chooses smart action at index 0.
     *     new TextLinkifyEvent.Builder(TYPE_SMART_ACTION)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(textClassification.getEntity(0))
     *         .setScore(textClassification.getConfidenceScore(entityType))
     *         .setActionIndices(0)
     *         .setEventIndex(2)
     *         .build();
     * </pre>
     */
    public static final class TextLinkifyEvent extends TextClassifierEvent implements Parcelable {

        @NonNull
        public static final Creator<TextLinkifyEvent> CREATOR =
                new Creator<TextLinkifyEvent>() {
                    @Override
                    public TextLinkifyEvent createFromParcel(Parcel in) {
                        in.readInt(); // skip token, we already know this is a TextLinkifyEvent
                        return new TextLinkifyEvent(in);
                    }

                    @Override
                    public TextLinkifyEvent[] newArray(int size) {
                        return new TextLinkifyEvent[size];
                    }
                };

        private TextLinkifyEvent(Parcel in) {
            super(in);
        }

        private TextLinkifyEvent(TextLinkifyEvent.Builder builder) {
            super(builder);
        }

        /**
         * Builder class for {@link TextLinkifyEvent}.
         */
        public static final class Builder
                extends TextClassifierEvent.Builder<TextLinkifyEvent.Builder> {
            /**
             * Creates a builder for building {@link TextLinkifyEvent}s.
             *
             * @param eventType The event type. e.g. {@link #TYPE_SMART_ACTION}
             */
            public Builder(@Type int eventType) {
                super(TextClassifierEvent.CATEGORY_LINKIFY, eventType);
            }

            @Override
            Builder self() {
                return this;
            }

            /**
             * Builds and returns a {@link TextLinkifyEvent}.
             */
            @NonNull
            public TextLinkifyEvent build() {
                return new TextLinkifyEvent(this);
            }
        }
    }

    /**
     * This class represents events that are related to the language detection feature.
     * <p>
     * <pre>
     *     // Translate action shown for foreign text.
     *     new LanguageDetectionEvent.Builder(TYPE_ACTIONS_SHOWN)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(language)
     *         .setScore(score)
     *         .setActionIndices(textClassification.getActions().indexOf(translateAction))
     *         .setEventIndex(0)
     *         .build();
     *
     *     // Translate action selected.
     *     new LanguageDetectionEvent.Builder(TYPE_SMART_ACTION)
     *         .setEventContext(classificationContext)
     *         .setResultId(textClassification.getId())
     *         .setEntityTypes(language)
     *         .setScore(score)
     *         .setActionIndices(textClassification.getActions().indexOf(translateAction))
     *         .setEventIndex(1)
     *         .build();
     */
    public static final class LanguageDetectionEvent extends TextClassifierEvent
            implements Parcelable {

        @NonNull
        public static final Creator<LanguageDetectionEvent> CREATOR =
                new Creator<LanguageDetectionEvent>() {
                    @Override
                    public LanguageDetectionEvent createFromParcel(Parcel in) {
                        // skip token, we already know this is a LanguageDetectionEvent.
                        in.readInt();
                        return new LanguageDetectionEvent(in);
                    }

                    @Override
                    public LanguageDetectionEvent[] newArray(int size) {
                        return new LanguageDetectionEvent[size];
                    }
                };

        private LanguageDetectionEvent(Parcel in) {
            super(in);
        }

        private LanguageDetectionEvent(LanguageDetectionEvent.Builder builder) {
            super(builder);
        }

        /**
         * Builder class for {@link LanguageDetectionEvent}.
         */
        public static final class Builder
                extends TextClassifierEvent.Builder<LanguageDetectionEvent.Builder> {

            /**
             * Creates a builder for building {@link TextSelectionEvent}s.
             *
             * @param eventType The event type. e.g. {@link #TYPE_SMART_ACTION}
             */
            public Builder(@Type int eventType) {
                super(TextClassifierEvent.CATEGORY_LANGUAGE_DETECTION, eventType);
            }

            @Override
            Builder self() {
                return this;
            }

            /**
             * Builds and returns a {@link LanguageDetectionEvent}.
             */
            @NonNull
            public LanguageDetectionEvent build() {
                return new LanguageDetectionEvent(this);
            }
        }
    }

    /**
     * This class represents events that are related to the conversation actions feature.
     * <p>
     * <pre>
     *     // Conversation (contextual) actions/replies generated.
     *     new ConversationActionsEvent.Builder(TYPE_ACTIONS_GENERATED)
     *         .setEventContext(classificationContext)
     *         .setResultId(conversationActions.getId())
     *         .setEntityTypes(getTypes(conversationActions))
     *         .setActionIndices(range(conversationActions.getActions().size()))
     *         .setEventIndex(0)
     *         .build();
     *
     *     // Conversation actions/replies presented to user.
     *     new ConversationActionsEvent.Builder(TYPE_ACTIONS_SHOWN)
     *         .setEventContext(classificationContext)
     *         .setResultId(conversationActions.getId())
     *         .setEntityTypes(getTypes(conversationActions))
     *         .setActionIndices(range(conversationActions.getActions().size()))
     *         .setEventIndex(1)
     *         .build();
     *
     *     // User clicked the "Reply" button to compose their custom reply.
     *     new ConversationActionsEvent.Builder(TYPE_MANUAL_REPLY)
     *         .setEventContext(classificationContext)
     *         .setResultId(conversationActions.getId())
     *         .setEventIndex(2)
     *         .build();
     *
     *     // User selected a smart (contextual) action/reply.
     *     new ConversationActionsEvent.Builder(TYPE_SMART_ACTION)
     *         .setEventContext(classificationContext)
     *         .setResultId(conversationActions.getId())
     *         .setEntityTypes(conversationActions.get(1).getType())
     *         .setScore(conversationAction.get(1).getConfidenceScore())
     *         .setActionIndices(1)
     *         .setEventIndex(2)
     *         .build();
     * </pre>
     */
    public static final class ConversationActionsEvent extends TextClassifierEvent
            implements Parcelable {

        @NonNull
        public static final Creator<ConversationActionsEvent> CREATOR =
                new Creator<ConversationActionsEvent>() {
                    @Override
                    public ConversationActionsEvent createFromParcel(Parcel in) {
                        // skip token, we already know this is a ConversationActionsEvent.
                        in.readInt();
                        return new ConversationActionsEvent(in);
                    }

                    @Override
                    public ConversationActionsEvent[] newArray(int size) {
                        return new ConversationActionsEvent[size];
                    }
                };

        private ConversationActionsEvent(Parcel in) {
            super(in);
        }

        private ConversationActionsEvent(ConversationActionsEvent.Builder builder) {
            super(builder);
        }

        /**
         * Builder class for {@link ConversationActionsEvent}.
         */
        public static final class Builder
                extends TextClassifierEvent.Builder<ConversationActionsEvent.Builder> {
            /**
             * Creates a builder for building {@link TextSelectionEvent}s.
             *
             * @param eventType The event type. e.g. {@link #TYPE_SMART_ACTION}
             */
            public Builder(@Type int eventType) {
                super(TextClassifierEvent.CATEGORY_CONVERSATION_ACTIONS, eventType);
            }

            @Override
            Builder self() {
                return this;
            }

            /**
             * Builds and returns a {@link ConversationActionsEvent}.
             */
            @NonNull
            public ConversationActionsEvent build() {
                return new ConversationActionsEvent(this);
            }
        }
    }
}
