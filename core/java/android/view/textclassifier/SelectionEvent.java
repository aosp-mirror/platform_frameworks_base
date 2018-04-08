/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;
import android.view.textclassifier.TextClassifier.EntityType;
import android.view.textclassifier.TextClassifier.WidgetType;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;
import java.util.Objects;

/**
 * A selection event.
 * Specify index parameters as word token indices.
 */
public final class SelectionEvent implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ACTION_OVERTYPE, ACTION_COPY, ACTION_PASTE, ACTION_CUT,
            ACTION_SHARE, ACTION_SMART_SHARE, ACTION_DRAG, ACTION_ABANDON,
            ACTION_OTHER, ACTION_SELECT_ALL, ACTION_RESET})
    // NOTE: ActionType values should not be lower than 100 to avoid colliding with the other
    // EventTypes declared below.
    public @interface ActionType {
        /*
         * Terminal event types range: [100,200).
         * Non-terminal event types range: [200,300).
         */
    }

    /** User typed over the selection. */
    public static final int ACTION_OVERTYPE = 100;
    /** User copied the selection. */
    public static final int ACTION_COPY = 101;
    /** User pasted over the selection. */
    public static final int ACTION_PASTE = 102;
    /** User cut the selection. */
    public static final int ACTION_CUT = 103;
    /** User shared the selection. */
    public static final int ACTION_SHARE = 104;
    /** User clicked the textAssist menu item. */
    public static final int ACTION_SMART_SHARE = 105;
    /** User dragged+dropped the selection. */
    public static final int ACTION_DRAG = 106;
    /** User abandoned the selection. */
    public static final int ACTION_ABANDON = 107;
    /** User performed an action on the selection. */
    public static final int ACTION_OTHER = 108;

    // Non-terminal actions.
    /** User activated Select All */
    public static final int ACTION_SELECT_ALL = 200;
    /** User reset the smart selection. */
    public static final int ACTION_RESET = 201;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ACTION_OVERTYPE, ACTION_COPY, ACTION_PASTE, ACTION_CUT,
            ACTION_SHARE, ACTION_SMART_SHARE, ACTION_DRAG, ACTION_ABANDON,
            ACTION_OTHER, ACTION_SELECT_ALL, ACTION_RESET,
            EVENT_SELECTION_STARTED, EVENT_SELECTION_MODIFIED,
            EVENT_SMART_SELECTION_SINGLE, EVENT_SMART_SELECTION_MULTI,
            EVENT_AUTO_SELECTION})
    // NOTE: EventTypes declared here must be less than 100 to avoid colliding with the
    // ActionTypes declared above.
    public @interface EventType {
        /*
         * Range: 1 -> 99.
         */
    }

    /** User started a new selection. */
    public static final int EVENT_SELECTION_STARTED = 1;
    /** User modified an existing selection. */
    public static final int EVENT_SELECTION_MODIFIED = 2;
    /** Smart selection triggered for a single token (word). */
    public static final int EVENT_SMART_SELECTION_SINGLE = 3;
    /** Smart selection triggered spanning multiple tokens (words). */
    public static final int EVENT_SMART_SELECTION_MULTI = 4;
    /** Something else other than User or the default TextClassifier triggered a selection. */
    public static final int EVENT_AUTO_SELECTION = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({INVOCATION_MANUAL, INVOCATION_LINK, INVOCATION_UNKNOWN})
    public @interface InvocationMethod {}

    /** Selection was invoked by the user long pressing, double tapping, or dragging to select. */
    public static final int INVOCATION_MANUAL = 1;
    /** Selection was invoked by the user tapping on a link. */
    public static final int INVOCATION_LINK = 2;
    /** Unknown invocation method */
    public static final int INVOCATION_UNKNOWN = 0;

    private static final String NO_SIGNATURE = "";

    private final int mAbsoluteStart;
    private final int mAbsoluteEnd;
    private final @EntityType String mEntityType;

    private @EventType int mEventType;
    private String mPackageName = "";
    private String mWidgetType = TextClassifier.WIDGET_TYPE_UNKNOWN;
    private @InvocationMethod int mInvocationMethod;
    @Nullable private String mWidgetVersion;
    @Nullable private String mResultId;
    private long mEventTime;
    private long mDurationSinceSessionStart;
    private long mDurationSincePreviousEvent;
    private int mEventIndex;
    @Nullable private TextClassificationSessionId mSessionId;
    private int mStart;
    private int mEnd;
    private int mSmartStart;
    private int mSmartEnd;

    SelectionEvent(
            int start, int end,
            @EventType int eventType, @EntityType String entityType,
            @InvocationMethod int invocationMethod, @Nullable String resultId) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        mAbsoluteStart = start;
        mAbsoluteEnd = end;
        mEventType = eventType;
        mEntityType = Preconditions.checkNotNull(entityType);
        mResultId = resultId;
        mInvocationMethod = invocationMethod;
    }

    private SelectionEvent(Parcel in) {
        mAbsoluteStart = in.readInt();
        mAbsoluteEnd = in.readInt();
        mEventType = in.readInt();
        mEntityType = in.readString();
        mWidgetVersion = in.readInt() > 0 ? in.readString() : null;
        mPackageName = in.readString();
        mWidgetType = in.readString();
        mInvocationMethod = in.readInt();
        mResultId = in.readString();
        mEventTime = in.readLong();
        mDurationSinceSessionStart = in.readLong();
        mDurationSincePreviousEvent = in.readLong();
        mEventIndex = in.readInt();
        mSessionId = in.readInt() > 0
                ? TextClassificationSessionId.CREATOR.createFromParcel(in) : null;
        mStart = in.readInt();
        mEnd = in.readInt();
        mSmartStart = in.readInt();
        mSmartEnd = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAbsoluteStart);
        dest.writeInt(mAbsoluteEnd);
        dest.writeInt(mEventType);
        dest.writeString(mEntityType);
        dest.writeInt(mWidgetVersion != null ? 1 : 0);
        if (mWidgetVersion != null) {
            dest.writeString(mWidgetVersion);
        }
        dest.writeString(mPackageName);
        dest.writeString(mWidgetType);
        dest.writeInt(mInvocationMethod);
        dest.writeString(mResultId);
        dest.writeLong(mEventTime);
        dest.writeLong(mDurationSinceSessionStart);
        dest.writeLong(mDurationSincePreviousEvent);
        dest.writeInt(mEventIndex);
        dest.writeInt(mSessionId != null ? 1 : 0);
        if (mSessionId != null) {
            mSessionId.writeToParcel(dest, flags);
        }
        dest.writeInt(mStart);
        dest.writeInt(mEnd);
        dest.writeInt(mSmartStart);
        dest.writeInt(mSmartEnd);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Creates a "selection started" event.
     *
     * @param invocationMethod  the way the selection was triggered
     * @param start  the index of the selected text
     */
    @NonNull
    public static SelectionEvent createSelectionStartedEvent(
            @SelectionEvent.InvocationMethod int invocationMethod, int start) {
        return new SelectionEvent(
                start, start + 1, SelectionEvent.EVENT_SELECTION_STARTED,
                TextClassifier.TYPE_UNKNOWN, invocationMethod, NO_SIGNATURE);
    }

    /**
     * Creates a "selection modified" event.
     * Use when the user modifies the selection.
     *
     * @param start  the start (inclusive) index of the selection
     * @param end  the end (exclusive) index of the selection
     *
     * @throws IllegalArgumentException if end is less than start
     */
    @NonNull
    public static SelectionEvent createSelectionModifiedEvent(int start, int end) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        return new SelectionEvent(
                start, end, SelectionEvent.EVENT_SELECTION_MODIFIED,
                TextClassifier.TYPE_UNKNOWN, INVOCATION_UNKNOWN, NO_SIGNATURE);
    }

    /**
     * Creates a "selection modified" event.
     * Use when the user modifies the selection and the selection's entity type is known.
     *
     * @param start  the start (inclusive) index of the selection
     * @param end  the end (exclusive) index of the selection
     * @param classification  the TextClassification object returned by the TextClassifier that
     *      classified the selected text
     *
     * @throws IllegalArgumentException if end is less than start
     */
    @NonNull
    public static SelectionEvent createSelectionModifiedEvent(
            int start, int end, @NonNull TextClassification classification) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        Preconditions.checkNotNull(classification);
        final String entityType = classification.getEntityCount() > 0
                ? classification.getEntity(0)
                : TextClassifier.TYPE_UNKNOWN;
        return new SelectionEvent(
                start, end, SelectionEvent.EVENT_SELECTION_MODIFIED,
                entityType, INVOCATION_UNKNOWN, classification.getId());
    }

    /**
     * Creates a "selection modified" event.
     * Use when a TextClassifier modifies the selection.
     *
     * @param start  the start (inclusive) index of the selection
     * @param end  the end (exclusive) index of the selection
     * @param selection  the TextSelection object returned by the TextClassifier for the
     *      specified selection
     *
     * @throws IllegalArgumentException if end is less than start
     */
    @NonNull
    public static SelectionEvent createSelectionModifiedEvent(
            int start, int end, @NonNull TextSelection selection) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        Preconditions.checkNotNull(selection);
        final String entityType = selection.getEntityCount() > 0
                ? selection.getEntity(0)
                : TextClassifier.TYPE_UNKNOWN;
        return new SelectionEvent(
                start, end, SelectionEvent.EVENT_AUTO_SELECTION,
                entityType, INVOCATION_UNKNOWN, selection.getId());
    }

    /**
     * Creates an event specifying an action taken on a selection.
     * Use when the user clicks on an action to act on the selected text.
     *
     * @param start  the start (inclusive) index of the selection
     * @param end  the end (exclusive) index of the selection
     * @param actionType  the action that was performed on the selection
     *
     * @throws IllegalArgumentException if end is less than start
     */
    @NonNull
    public static SelectionEvent createSelectionActionEvent(
            int start, int end, @SelectionEvent.ActionType int actionType) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        checkActionType(actionType);
        return new SelectionEvent(
                start, end, actionType, TextClassifier.TYPE_UNKNOWN, INVOCATION_UNKNOWN,
                NO_SIGNATURE);
    }

    /**
     * Creates an event specifying an action taken on a selection.
     * Use when the user clicks on an action to act on the selected text and the selection's
     * entity type is known.
     *
     * @param start  the start (inclusive) index of the selection
     * @param end  the end (exclusive) index of the selection
     * @param actionType  the action that was performed on the selection
     * @param classification  the TextClassification object returned by the TextClassifier that
     *      classified the selected text
     *
     * @throws IllegalArgumentException if end is less than start
     * @throws IllegalArgumentException If actionType is not a valid SelectionEvent actionType
     */
    @NonNull
    public static SelectionEvent createSelectionActionEvent(
            int start, int end, @SelectionEvent.ActionType int actionType,
            @NonNull TextClassification classification) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        Preconditions.checkNotNull(classification);
        checkActionType(actionType);
        final String entityType = classification.getEntityCount() > 0
                ? classification.getEntity(0)
                : TextClassifier.TYPE_UNKNOWN;
        return new SelectionEvent(start, end, actionType, entityType, INVOCATION_UNKNOWN,
                classification.getId());
    }

    /**
     * @throws IllegalArgumentException If eventType is not an {@link SelectionEvent.ActionType}
     */
    private static void checkActionType(@SelectionEvent.EventType int eventType)
            throws IllegalArgumentException {
        switch (eventType) {
            case SelectionEvent.ACTION_OVERTYPE:  // fall through
            case SelectionEvent.ACTION_COPY:  // fall through
            case SelectionEvent.ACTION_PASTE:  // fall through
            case SelectionEvent.ACTION_CUT:  // fall through
            case SelectionEvent.ACTION_SHARE:  // fall through
            case SelectionEvent.ACTION_SMART_SHARE:  // fall through
            case SelectionEvent.ACTION_DRAG:  // fall through
            case SelectionEvent.ACTION_ABANDON:  // fall through
            case SelectionEvent.ACTION_SELECT_ALL:  // fall through
            case SelectionEvent.ACTION_RESET:  // fall through
                return;
            default:
                throw new IllegalArgumentException(
                        String.format(Locale.US, "%d is not an eventType", eventType));
        }
    }

    int getAbsoluteStart() {
        return mAbsoluteStart;
    }

    int getAbsoluteEnd() {
        return mAbsoluteEnd;
    }

    /**
     * Returns the type of event that was triggered. e.g. {@link #ACTION_COPY}.
     */
    @EventType
    public int getEventType() {
        return mEventType;
    }

    /**
     * Sets the event type.
     */
    void setEventType(@EventType int eventType) {
        mEventType = eventType;
    }

    /**
     * Returns the type of entity that is associated with this event. e.g.
     * {@link android.view.textclassifier.TextClassifier#TYPE_EMAIL}.
     */
    @EntityType
    @NonNull
    public String getEntityType() {
        return mEntityType;
    }

    /**
     * Returns the package name of the app that this event originated in.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the type of widget that was involved in triggering this event.
     */
    @WidgetType
    @NonNull
    public String getWidgetType() {
        return mWidgetType;
    }

    /**
     * Returns a string version info for the widget this event was triggered in.
     */
    @Nullable
    public String getWidgetVersion() {
        return mWidgetVersion;
    }

    /**
     * Sets the {@link TextClassificationContext} for this event.
     */
    void setTextClassificationSessionContext(TextClassificationContext context) {
        mPackageName = context.getPackageName();
        mWidgetType = context.getWidgetType();
        mWidgetVersion = context.getWidgetVersion();
    }

    /**
     * Returns the way the selection mode was invoked.
     */
    public @InvocationMethod int getInvocationMethod() {
        return mInvocationMethod;
    }

    /**
     * Sets the invocationMethod for this event.
     */
    void setInvocationMethod(@InvocationMethod int invocationMethod) {
        mInvocationMethod = invocationMethod;
    }

    /**
     * Returns the id of the text classifier result associated with this event.
     */
    @Nullable
    public String getResultId() {
        return mResultId;
    }

    SelectionEvent setResultId(@Nullable String resultId) {
        mResultId = resultId;
        return this;
    }

    /**
     * Returns the time this event was triggered.
     */
    public long getEventTime() {
        return mEventTime;
    }

    SelectionEvent setEventTime(long timeMs) {
        mEventTime = timeMs;
        return this;
    }

    /**
     * Returns the duration in ms between when this event was triggered and when the first event in
     * the selection session was triggered.
     */
    public long getDurationSinceSessionStart() {
        return mDurationSinceSessionStart;
    }

    SelectionEvent setDurationSinceSessionStart(long durationMs) {
        mDurationSinceSessionStart = durationMs;
        return this;
    }

    /**
     * Returns the duration in ms between when this event was triggered and when the previous event
     * in the selection session was triggered.
     */
    public long getDurationSincePreviousEvent() {
        return mDurationSincePreviousEvent;
    }

    SelectionEvent setDurationSincePreviousEvent(long durationMs) {
        this.mDurationSincePreviousEvent = durationMs;
        return this;
    }

    /**
     * Returns the index (e.g. 1st event, 2nd event, etc.) of this event in the selection session.
     */
    public int getEventIndex() {
        return mEventIndex;
    }

    SelectionEvent setEventIndex(int index) {
        mEventIndex = index;
        return this;
    }

    /**
     * Returns the selection session id.
     */
    @Nullable
    public TextClassificationSessionId getSessionId() {
        return mSessionId;
    }

    SelectionEvent setSessionId(TextClassificationSessionId id) {
        mSessionId = id;
        return this;
    }

    /**
     * Returns the start index of this events relative to the index of the start selection
     * event in the selection session.
     */
    public int getStart() {
        return mStart;
    }

    SelectionEvent setStart(int start) {
        mStart = start;
        return this;
    }

    /**
     * Returns the end index of this events relative to the index of the start selection
     * event in the selection session.
     */
    public int getEnd() {
        return mEnd;
    }

    SelectionEvent setEnd(int end) {
        mEnd = end;
        return this;
    }

    /**
     * Returns the start index of this events relative to the index of the smart selection
     * event in the selection session.
     */
    public int getSmartStart() {
        return mSmartStart;
    }

    SelectionEvent setSmartStart(int start) {
        this.mSmartStart = start;
        return this;
    }

    /**
     * Returns the end index of this events relative to the index of the smart selection
     * event in the selection session.
     */
    public int getSmartEnd() {
        return mSmartEnd;
    }

    SelectionEvent setSmartEnd(int end) {
        mSmartEnd = end;
        return this;
    }

    boolean isTerminal() {
        return isTerminal(mEventType);
    }

    /**
     * Returns true if the eventType is a terminal event type. Otherwise returns false.
     * A terminal event is an event that ends a selection interaction.
     */
    public static boolean isTerminal(@EventType int eventType) {
        switch (eventType) {
            case ACTION_OVERTYPE:  // fall through
            case ACTION_COPY:  // fall through
            case ACTION_PASTE:  // fall through
            case ACTION_CUT:  // fall through
            case ACTION_SHARE:  // fall through
            case ACTION_SMART_SHARE:  // fall through
            case ACTION_DRAG:  // fall through
            case ACTION_ABANDON:  // fall through
            case ACTION_OTHER:  // fall through
                return true;
            default:
                return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAbsoluteStart, mAbsoluteEnd, mEventType, mEntityType,
                mWidgetVersion, mPackageName, mWidgetType, mInvocationMethod, mResultId,
                mEventTime, mDurationSinceSessionStart, mDurationSincePreviousEvent,
                mEventIndex, mSessionId, mStart, mEnd, mSmartStart, mSmartEnd);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SelectionEvent)) {
            return false;
        }

        final SelectionEvent other = (SelectionEvent) obj;
        return mAbsoluteStart == other.mAbsoluteStart
                && mAbsoluteEnd == other.mAbsoluteEnd
                && mEventType == other.mEventType
                && Objects.equals(mEntityType, other.mEntityType)
                && Objects.equals(mWidgetVersion, other.mWidgetVersion)
                && Objects.equals(mPackageName, other.mPackageName)
                && Objects.equals(mWidgetType, other.mWidgetType)
                && mInvocationMethod == other.mInvocationMethod
                && Objects.equals(mResultId, other.mResultId)
                && mEventTime == other.mEventTime
                && mDurationSinceSessionStart == other.mDurationSinceSessionStart
                && mDurationSincePreviousEvent == other.mDurationSincePreviousEvent
                && mEventIndex == other.mEventIndex
                && Objects.equals(mSessionId, other.mSessionId)
                && mStart == other.mStart
                && mEnd == other.mEnd
                && mSmartStart == other.mSmartStart
                && mSmartEnd == other.mSmartEnd;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "SelectionEvent {absoluteStart=%d, absoluteEnd=%d, eventType=%d, entityType=%s, "
                        + "widgetVersion=%s, packageName=%s, widgetType=%s, invocationMethod=%s, "
                        + "resultId=%s, eventTime=%d, durationSinceSessionStart=%d, "
                        + "durationSincePreviousEvent=%d, eventIndex=%d,"
                        + "sessionId=%s, start=%d, end=%d, smartStart=%d, smartEnd=%d}",
                mAbsoluteStart, mAbsoluteEnd, mEventType, mEntityType,
                mWidgetVersion, mPackageName, mWidgetType, mInvocationMethod,
                mResultId, mEventTime, mDurationSinceSessionStart,
                mDurationSincePreviousEvent, mEventIndex,
                mSessionId, mStart, mEnd, mSmartStart, mSmartEnd);
    }

    public static final Creator<SelectionEvent> CREATOR = new Creator<SelectionEvent>() {
        @Override
        public SelectionEvent createFromParcel(Parcel in) {
            return new SelectionEvent(in);
        }

        @Override
        public SelectionEvent[] newArray(int size) {
            return new SelectionEvent[size];
        }
    };
}