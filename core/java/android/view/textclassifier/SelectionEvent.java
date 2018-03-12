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
import android.annotation.Nullable;
import android.view.textclassifier.TextClassifier.EntityType;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;

/**
 * A selection event.
 * Specify index parameters as word token indices.
 */
public final class SelectionEvent {

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
    @IntDef({INVOCATION_MANUAL, INVOCATION_LINK})
    public @interface InvocationMethod {}

    /** Selection was invoked by the user long pressing, double tapping, or dragging to select. */
    public static final int INVOCATION_MANUAL = 1;
    /** Selection was invoked by the user tapping on a link. */
    public static final int INVOCATION_LINK = 2;

    private final int mAbsoluteStart;
    private final int mAbsoluteEnd;
    private final @EventType int mEventType;
    private final @EntityType String mEntityType;
    @Nullable private final String mWidgetVersion;
    private final String mPackageName;
    private final String mWidgetType;
    private final @InvocationMethod int mInvocationMethod;

    // These fields should only be set by creator of a SelectionEvent.
    private String mSignature;
    private long mEventTime;
    private long mDurationSinceSessionStart;
    private long mDurationSinceLastEvent;
    private int mEventIndex;
    private String mSessionId;
    private int mStart;
    private int mEnd;
    private int mSmartStart;
    private int mSmartEnd;

    SelectionEvent(
            int start, int end,
            @EventType int eventType, @EntityType String entityType,
            @InvocationMethod int invocationMethod, String signature, Logger.Config config) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        mAbsoluteStart = start;
        mAbsoluteEnd = end;
        mEventType = eventType;
        mEntityType = Preconditions.checkNotNull(entityType);
        mSignature = Preconditions.checkNotNull(signature);
        Preconditions.checkNotNull(config);
        mWidgetVersion = config.getWidgetVersion();
        mPackageName = Preconditions.checkNotNull(config.getPackageName());
        mWidgetType = Preconditions.checkNotNull(config.getWidgetType());
        mInvocationMethod = invocationMethod;
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
    public int getEventType() {
        return mEventType;
    }

    /**
     * Returns the type of entity that is associated with this event. e.g.
     * {@link android.view.textclassifier.TextClassifier#TYPE_EMAIL}.
     */
    @EntityType
    public String getEntityType() {
        return mEntityType;
    }

    /**
     * Returns the package name of the app that this event originated in.
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the type of widget that was involved in triggering this event.
     */
    public String getWidgetType() {
        return mWidgetType;
    }

    /**
     * Returns a string version info for the widget this event was triggered in.
     */
    public String getWidgetVersion() {
        return mWidgetVersion;
    }

    /**
     * Returns the way the selection mode was invoked.
     */
    public @InvocationMethod int getInvocationMethod() {
        return mInvocationMethod;
    }

    /**
     * Returns the signature of the text classifier result associated with this event.
     */
    public String getSignature() {
        return mSignature;
    }

    SelectionEvent setSignature(String signature) {
        mSignature = Preconditions.checkNotNull(signature);
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
        return mDurationSinceLastEvent;
    }

    SelectionEvent setDurationSincePreviousEvent(long durationMs) {
        this.mDurationSinceLastEvent = durationMs;
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
    public String getSessionId() {
        return mSessionId;
    }

    SelectionEvent setSessionId(String id) {
        mSessionId = id;
        return this;
    }

    /**
     * Returns the start index of this events token relative to the index of the start selection
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
     * Returns the end index of this events token relative to the index of the start selection
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
     * Returns the start index of this events token relative to the index of the smart selection
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
     * Returns the end index of this events token relative to the index of the smart selection
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
        switch (mEventType) {
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
    public String toString() {
        return String.format(Locale.US,
        "SelectionEvent {absoluteStart=%d, absoluteEnd=%d, eventType=%d, entityType=%s, "
                + "widgetVersion=%s, packageName=%s, widgetType=%s, signature=%s, "
                + "eventTime=%d, durationSinceSessionStart=%d, durationSinceLastEvent=%d, "
                + "eventIndex=%d, sessionId=%s, start=%d, end=%d, smartStart=%d, smartEnd=%d}",
                mAbsoluteStart, mAbsoluteEnd, mEventType, mEntityType,
                mWidgetVersion, mPackageName, mWidgetType, mSignature,
                mEventTime, mDurationSinceSessionStart, mDurationSinceLastEvent,
                mEventIndex, mSessionId, mStart, mEnd, mSmartStart, mSmartEnd);
    }
}
