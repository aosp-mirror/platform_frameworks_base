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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.content.Context;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.BreakIterator;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A helper for logging TextClassifier related events.
 */
public abstract class Logger {

    /**
     * Use this to specify an indeterminate positive index.
     */
    public static final int OUT_OF_BOUNDS = Integer.MAX_VALUE;

    /**
     * Use this to specify an indeterminate negative index.
     */
    public static final int OUT_OF_BOUNDS_NEGATIVE = Integer.MIN_VALUE;

    private static final String LOG_TAG = "Logger";
    /* package */ static final boolean DEBUG_LOG_ENABLED = true;

    private static final String NO_SIGNATURE = "";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({WIDGET_TEXTVIEW, WIDGET_WEBVIEW, WIDGET_EDITTEXT,
            WIDGET_EDIT_WEBVIEW, WIDGET_CUSTOM_TEXTVIEW, WIDGET_CUSTOM_EDITTEXT,
            WIDGET_CUSTOM_UNSELECTABLE_TEXTVIEW, WIDGET_UNKNOWN})
    public @interface WidgetType {}

    public static final String WIDGET_TEXTVIEW = "textview";
    public static final String WIDGET_EDITTEXT = "edittext";
    public static final String WIDGET_UNSELECTABLE_TEXTVIEW = "nosel-textview";
    public static final String WIDGET_WEBVIEW = "webview";
    public static final String WIDGET_EDIT_WEBVIEW = "edit-webview";
    public static final String WIDGET_CUSTOM_TEXTVIEW = "customview";
    public static final String WIDGET_CUSTOM_EDITTEXT = "customedit";
    public static final String WIDGET_CUSTOM_UNSELECTABLE_TEXTVIEW = "nosel-customview";
    public static final String WIDGET_UNKNOWN = "unknown";

    private @SelectionEvent.InvocationMethod int mInvocationMethod;
    private SelectionEvent mPrevEvent;
    private SelectionEvent mSmartEvent;
    private SelectionEvent mStartEvent;

    /**
     * Logger that does not log anything.
     * @hide
     */
    public static final Logger DISABLED = new Logger() {
        @Override
        public void writeEvent(SelectionEvent event) {}
    };

    @Nullable
    private final Config mConfig;

    public Logger(Config config) {
        mConfig = Preconditions.checkNotNull(config);
    }

    private Logger() {
        mConfig = null;
    }

    /**
     * Writes the selection event to a log.
     */
    public abstract void writeEvent(@NonNull SelectionEvent event);

    /**
     * Returns true if the signature matches that of a smart selection event (i.e.
     * {@link SelectionEvent#EVENT_SMART_SELECTION_SINGLE} or
     * {@link SelectionEvent#EVENT_SMART_SELECTION_MULTI}).
     * Returns false otherwise.
     */
    public boolean isSmartSelection(@NonNull String signature) {
        return false;
    }


    /**
     * Returns a token iterator for tokenizing text for logging purposes.
     */
    public BreakIterator getTokenIterator(@NonNull Locale locale) {
        return BreakIterator.getWordInstance(Preconditions.checkNotNull(locale));
    }

    /**
     * Logs a "selection started" event.
     *
     * @param invocationMethod  the way the selection was triggered
     * @param start  the token index of the selected token
     */
    public final void logSelectionStartedEvent(
            @SelectionEvent.InvocationMethod int invocationMethod, int start) {
        if (mConfig == null) {
            return;
        }

        mInvocationMethod = invocationMethod;
        logEvent(new SelectionEvent(
                start, start + 1, SelectionEvent.EVENT_SELECTION_STARTED,
                TextClassifier.TYPE_UNKNOWN, mInvocationMethod, NO_SIGNATURE, mConfig));
    }

    /**
     * Logs a "selection modified" event.
     * Use when the user modifies the selection.
     *
     * @param start  the start token (inclusive) index of the selection
     * @param end  the end token (exclusive) index of the selection
     */
    public final void logSelectionModifiedEvent(int start, int end) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");

        if (mConfig == null) {
            return;
        }

        logEvent(new SelectionEvent(
                start, end, SelectionEvent.EVENT_SELECTION_MODIFIED,
                TextClassifier.TYPE_UNKNOWN, mInvocationMethod, NO_SIGNATURE, mConfig));
    }

    /**
     * Logs a "selection modified" event.
     * Use when the user modifies the selection and the selection's entity type is known.
     *
     * @param start  the start token (inclusive) index of the selection
     * @param end  the end token (exclusive) index of the selection
     * @param classification  the TextClassification object returned by the TextClassifier that
     *      classified the selected text
     */
    public final void logSelectionModifiedEvent(
            int start, int end, @NonNull TextClassification classification) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        Preconditions.checkNotNull(classification);

        if (mConfig == null) {
            return;
        }

        final String entityType = classification.getEntityCount() > 0
                ? classification.getEntity(0)
                : TextClassifier.TYPE_UNKNOWN;
        final String signature = classification.getSignature();
        logEvent(new SelectionEvent(
                start, end, SelectionEvent.EVENT_SELECTION_MODIFIED,
                entityType, mInvocationMethod, signature, mConfig));
    }

    /**
     * Logs a "selection modified" event.
     * Use when a TextClassifier modifies the selection.
     *
     * @param start  the start token (inclusive) index of the selection
     * @param end  the end token (exclusive) index of the selection
     * @param selection  the TextSelection object returned by the TextClassifier for the
     *      specified selection
     */
    public final void logSelectionModifiedEvent(
            int start, int end, @NonNull TextSelection selection) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        Preconditions.checkNotNull(selection);

        if (mConfig == null) {
            return;
        }

        final int eventType;
        if (isSmartSelection(selection.getSignature())) {
            eventType = end - start > 1
                    ? SelectionEvent.EVENT_SMART_SELECTION_MULTI
                    : SelectionEvent.EVENT_SMART_SELECTION_SINGLE;

        } else {
            eventType = SelectionEvent.EVENT_AUTO_SELECTION;
        }
        final String entityType = selection.getEntityCount() > 0
                ? selection.getEntity(0)
                : TextClassifier.TYPE_UNKNOWN;
        final String signature = selection.getSignature();
        logEvent(new SelectionEvent(start, end, eventType, entityType, mInvocationMethod, signature,
                mConfig));
    }

    /**
     * Logs an event specifying an action taken on a selection.
     * Use when the user clicks on an action to act on the selected text.
     *
     * @param start  the start token (inclusive) index of the selection
     * @param end  the end token (exclusive) index of the selection
     * @param actionType  the action that was performed on the selection
     */
    public final void logSelectionActionEvent(
            int start, int end, @SelectionEvent.ActionType int actionType) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        checkActionType(actionType);

        if (mConfig == null) {
            return;
        }

        logEvent(new SelectionEvent(
                start, end, actionType, TextClassifier.TYPE_UNKNOWN, mInvocationMethod,
                NO_SIGNATURE, mConfig));
    }

    /**
     * Logs an event specifying an action taken on a selection.
     * Use when the user clicks on an action to act on the selected text and the selection's
     * entity type is known.
     *
     * @param start  the start token (inclusive) index of the selection
     * @param end  the end token (exclusive) index of the selection
     * @param actionType  the action that was performed on the selection
     * @param classification  the TextClassification object returned by the TextClassifier that
     *      classified the selected text
     *
     * @throws IllegalArgumentException If actionType is not a valid SelectionEvent actionType
     */
    public final void logSelectionActionEvent(
            int start, int end, @SelectionEvent.ActionType int actionType,
            @NonNull TextClassification classification) {
        Preconditions.checkArgument(end >= start, "end cannot be less than start");
        Preconditions.checkNotNull(classification);
        checkActionType(actionType);

        if (mConfig == null) {
            return;
        }

        final String entityType = classification.getEntityCount() > 0
                ? classification.getEntity(0)
                : TextClassifier.TYPE_UNKNOWN;
        final String signature = classification.getSignature();
        logEvent(new SelectionEvent(start, end, actionType, entityType, mInvocationMethod,
                signature, mConfig));
    }

    private void logEvent(@NonNull SelectionEvent event) {
        Preconditions.checkNotNull(event);

        if (event.getEventType() != SelectionEvent.EVENT_SELECTION_STARTED
                && mStartEvent == null) {
            if (DEBUG_LOG_ENABLED) {
                Log.d(LOG_TAG, "Selection session not yet started. Ignoring event");
            }
            return;
        }

        final long now = System.currentTimeMillis();
        switch (event.getEventType()) {
            case SelectionEvent.EVENT_SELECTION_STARTED:
                Preconditions.checkArgument(event.getAbsoluteEnd() == event.getAbsoluteStart() + 1);
                event.setSessionId(startNewSession());
                mStartEvent = event;
                break;
            case SelectionEvent.EVENT_SMART_SELECTION_SINGLE:  // fall through
            case SelectionEvent.EVENT_SMART_SELECTION_MULTI:
                mSmartEvent = event;
                break;
            case SelectionEvent.EVENT_SELECTION_MODIFIED:  // fall through
            case SelectionEvent.EVENT_AUTO_SELECTION:
                if (mPrevEvent != null
                        && mPrevEvent.getAbsoluteStart() == event.getAbsoluteStart()
                        && mPrevEvent.getAbsoluteEnd() == event.getAbsoluteEnd()) {
                    // Selection did not change. Ignore event.
                    return;
                }
        }

        event.setEventTime(now);
        if (mStartEvent != null) {
            event.setSessionId(mStartEvent.getSessionId())
                    .setDurationSinceSessionStart(now - mStartEvent.getEventTime())
                    .setStart(event.getAbsoluteStart() - mStartEvent.getAbsoluteStart())
                    .setEnd(event.getAbsoluteEnd() - mStartEvent.getAbsoluteStart());
        }
        if (mSmartEvent != null) {
            event.setSignature(mSmartEvent.getSignature())
                    .setSmartStart(mSmartEvent.getAbsoluteStart() - mStartEvent.getAbsoluteStart())
                    .setSmartEnd(mSmartEvent.getAbsoluteEnd() - mStartEvent.getAbsoluteStart());
        }
        if (mPrevEvent != null) {
            event.setDurationSincePreviousEvent(now - mPrevEvent.getEventTime())
                    .setEventIndex(mPrevEvent.getEventIndex() + 1);
        }
        writeEvent(event);
        mPrevEvent = event;

        if (event.isTerminal()) {
            endSession();
        }
    }

    private String startNewSession() {
        endSession();
        return UUID.randomUUID().toString();
    }

    private void endSession() {
        mPrevEvent = null;
        mSmartEvent = null;
        mStartEvent = null;
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


    /**
     * A Logger config.
     */
    public static final class Config {

        private final String mPackageName;
        private final String mWidgetType;
        @Nullable private final String mWidgetVersion;

        /**
         * @param context Context of the widget the logger logs for
         * @param widgetType a name for the widget being logged for. e.g.
         *      {@link #WIDGET_TEXTVIEW}
         * @param widgetVersion a string version info for the widget the logger logs for
         */
        public Config(
                @NonNull Context context,
                @WidgetType String widgetType,
                @Nullable String widgetVersion) {
            mPackageName = Preconditions.checkNotNull(context).getPackageName();
            mWidgetType = widgetType;
            mWidgetVersion = widgetVersion;
        }

        /**
         * Returns the package name of the application the logger logs for.
         */
        public String getPackageName() {
            return mPackageName;
        }

        /**
         * Returns the name for the widget being logged for. e.g. {@link #WIDGET_TEXTVIEW}.
         */
        public String getWidgetType() {
            return mWidgetType;
        }

        /**
         * Returns string version info for the logger. This is specific to the text classifier.
         */
        @Nullable
        public String getWidgetVersion() {
            return mWidgetVersion;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPackageName, mWidgetType, mWidgetVersion);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }

            if (!(obj instanceof Config)) {
                return false;
            }

            final Config other = (Config) obj;
            return Objects.equals(mPackageName, other.mPackageName)
                    && Objects.equals(mWidgetType, other.mWidgetType)
                    && Objects.equals(mWidgetVersion, other.mWidgetType);
        }
    }
}
