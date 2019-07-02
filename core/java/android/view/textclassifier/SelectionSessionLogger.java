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
import android.content.Context;
import android.metrics.LogMaker;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.Preconditions;

import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A helper for logging selection session events.
 * @hide
 */
public final class SelectionSessionLogger {

    private static final String LOG_TAG = "SelectionSessionLogger";
    static final String CLASSIFIER_ID = "androidtc";

    private static final int START_EVENT_DELTA = MetricsEvent.FIELD_SELECTION_SINCE_START;
    private static final int PREV_EVENT_DELTA = MetricsEvent.FIELD_SELECTION_SINCE_PREVIOUS;
    private static final int INDEX = MetricsEvent.FIELD_SELECTION_SESSION_INDEX;
    private static final int WIDGET_TYPE = MetricsEvent.FIELD_SELECTION_WIDGET_TYPE;
    private static final int WIDGET_VERSION = MetricsEvent.FIELD_SELECTION_WIDGET_VERSION;
    private static final int MODEL_NAME = MetricsEvent.FIELD_TEXTCLASSIFIER_MODEL;
    private static final int ENTITY_TYPE = MetricsEvent.FIELD_SELECTION_ENTITY_TYPE;
    private static final int SMART_START = MetricsEvent.FIELD_SELECTION_SMART_RANGE_START;
    private static final int SMART_END = MetricsEvent.FIELD_SELECTION_SMART_RANGE_END;
    private static final int EVENT_START = MetricsEvent.FIELD_SELECTION_RANGE_START;
    private static final int EVENT_END = MetricsEvent.FIELD_SELECTION_RANGE_END;
    private static final int SESSION_ID = MetricsEvent.FIELD_SELECTION_SESSION_ID;

    private static final String ZERO = "0";
    private static final String UNKNOWN = "unknown";

    private final MetricsLogger mMetricsLogger;

    public SelectionSessionLogger() {
        mMetricsLogger = new MetricsLogger();
    }

    @VisibleForTesting
    public SelectionSessionLogger(@NonNull MetricsLogger metricsLogger) {
        mMetricsLogger = Preconditions.checkNotNull(metricsLogger);
    }

    /** Emits a selection event to the logs. */
    public void writeEvent(@NonNull SelectionEvent event) {
        Preconditions.checkNotNull(event);
        final LogMaker log = new LogMaker(MetricsEvent.TEXT_SELECTION_SESSION)
                .setType(getLogType(event))
                .setSubtype(getLogSubType(event))
                .setPackageName(event.getPackageName())
                .addTaggedData(START_EVENT_DELTA, event.getDurationSinceSessionStart())
                .addTaggedData(PREV_EVENT_DELTA, event.getDurationSincePreviousEvent())
                .addTaggedData(INDEX, event.getEventIndex())
                .addTaggedData(WIDGET_TYPE, event.getWidgetType())
                .addTaggedData(WIDGET_VERSION, event.getWidgetVersion())
                .addTaggedData(ENTITY_TYPE, event.getEntityType())
                .addTaggedData(EVENT_START, event.getStart())
                .addTaggedData(EVENT_END, event.getEnd());
        if (isPlatformLocalTextClassifierSmartSelection(event.getResultId())) {
            // Ensure result id and smart indices are only set for events with smart selection from
            // the platform's textclassifier.
            log.addTaggedData(MODEL_NAME, SignatureParser.getModelName(event.getResultId()))
                    .addTaggedData(SMART_START, event.getSmartStart())
                    .addTaggedData(SMART_END, event.getSmartEnd());
        }
        if (event.getSessionId() != null) {
            log.addTaggedData(SESSION_ID, event.getSessionId().flattenToString());
        }
        mMetricsLogger.write(log);
        debugLog(log);
    }

    private static int getLogType(SelectionEvent event) {
        switch (event.getEventType()) {
            case SelectionEvent.ACTION_OVERTYPE:
                return MetricsEvent.ACTION_TEXT_SELECTION_OVERTYPE;
            case SelectionEvent.ACTION_COPY:
                return MetricsEvent.ACTION_TEXT_SELECTION_COPY;
            case SelectionEvent.ACTION_PASTE:
                return MetricsEvent.ACTION_TEXT_SELECTION_PASTE;
            case SelectionEvent.ACTION_CUT:
                return MetricsEvent.ACTION_TEXT_SELECTION_CUT;
            case SelectionEvent.ACTION_SHARE:
                return MetricsEvent.ACTION_TEXT_SELECTION_SHARE;
            case SelectionEvent.ACTION_SMART_SHARE:
                return MetricsEvent.ACTION_TEXT_SELECTION_SMART_SHARE;
            case SelectionEvent.ACTION_DRAG:
                return MetricsEvent.ACTION_TEXT_SELECTION_DRAG;
            case SelectionEvent.ACTION_ABANDON:
                return MetricsEvent.ACTION_TEXT_SELECTION_ABANDON;
            case SelectionEvent.ACTION_OTHER:
                return MetricsEvent.ACTION_TEXT_SELECTION_OTHER;
            case SelectionEvent.ACTION_SELECT_ALL:
                return MetricsEvent.ACTION_TEXT_SELECTION_SELECT_ALL;
            case SelectionEvent.ACTION_RESET:
                return MetricsEvent.ACTION_TEXT_SELECTION_RESET;
            case SelectionEvent.EVENT_SELECTION_STARTED:
                return MetricsEvent.ACTION_TEXT_SELECTION_START;
            case SelectionEvent.EVENT_SELECTION_MODIFIED:
                return MetricsEvent.ACTION_TEXT_SELECTION_MODIFY;
            case SelectionEvent.EVENT_SMART_SELECTION_SINGLE:
                return MetricsEvent.ACTION_TEXT_SELECTION_SMART_SINGLE;
            case SelectionEvent.EVENT_SMART_SELECTION_MULTI:
                return MetricsEvent.ACTION_TEXT_SELECTION_SMART_MULTI;
            case SelectionEvent.EVENT_AUTO_SELECTION:
                return MetricsEvent.ACTION_TEXT_SELECTION_AUTO;
            default:
                return MetricsEvent.VIEW_UNKNOWN;
        }
    }

    private static int getLogSubType(SelectionEvent event) {
        switch (event.getInvocationMethod()) {
            case SelectionEvent.INVOCATION_MANUAL:
                return MetricsEvent.TEXT_SELECTION_INVOCATION_MANUAL;
            case SelectionEvent.INVOCATION_LINK:
                return MetricsEvent.TEXT_SELECTION_INVOCATION_LINK;
            default:
                return MetricsEvent.TEXT_SELECTION_INVOCATION_UNKNOWN;
        }
    }

    private static String getLogTypeString(int logType) {
        switch (logType) {
            case MetricsEvent.ACTION_TEXT_SELECTION_OVERTYPE:
                return "OVERTYPE";
            case MetricsEvent.ACTION_TEXT_SELECTION_COPY:
                return "COPY";
            case MetricsEvent.ACTION_TEXT_SELECTION_PASTE:
                return "PASTE";
            case MetricsEvent.ACTION_TEXT_SELECTION_CUT:
                return "CUT";
            case MetricsEvent.ACTION_TEXT_SELECTION_SHARE:
                return "SHARE";
            case MetricsEvent.ACTION_TEXT_SELECTION_SMART_SHARE:
                return "SMART_SHARE";
            case MetricsEvent.ACTION_TEXT_SELECTION_DRAG:
                return "DRAG";
            case MetricsEvent.ACTION_TEXT_SELECTION_ABANDON:
                return "ABANDON";
            case MetricsEvent.ACTION_TEXT_SELECTION_OTHER:
                return "OTHER";
            case MetricsEvent.ACTION_TEXT_SELECTION_SELECT_ALL:
                return "SELECT_ALL";
            case MetricsEvent.ACTION_TEXT_SELECTION_RESET:
                return "RESET";
            case MetricsEvent.ACTION_TEXT_SELECTION_START:
                return "SELECTION_STARTED";
            case MetricsEvent.ACTION_TEXT_SELECTION_MODIFY:
                return "SELECTION_MODIFIED";
            case MetricsEvent.ACTION_TEXT_SELECTION_SMART_SINGLE:
                return "SMART_SELECTION_SINGLE";
            case MetricsEvent.ACTION_TEXT_SELECTION_SMART_MULTI:
                return "SMART_SELECTION_MULTI";
            case MetricsEvent.ACTION_TEXT_SELECTION_AUTO:
                return "AUTO_SELECTION";
            default:
                return UNKNOWN;
        }
    }

    private static String getLogSubTypeString(int logSubType) {
        switch (logSubType) {
            case MetricsEvent.TEXT_SELECTION_INVOCATION_MANUAL:
                return "MANUAL";
            case MetricsEvent.TEXT_SELECTION_INVOCATION_LINK:
                return "LINK";
            default:
                return UNKNOWN;
        }
    }

    static boolean isPlatformLocalTextClassifierSmartSelection(String signature) {
        return SelectionSessionLogger.CLASSIFIER_ID.equals(
                SelectionSessionLogger.SignatureParser.getClassifierId(signature));
    }

    private static void debugLog(LogMaker log) {
        if (!Log.ENABLE_FULL_LOGGING) {
            return;
        }
        final String widgetType = Objects.toString(log.getTaggedData(WIDGET_TYPE), UNKNOWN);
        final String widgetVersion = Objects.toString(log.getTaggedData(WIDGET_VERSION), "");
        final String widget = widgetVersion.isEmpty()
                ? widgetType : widgetType + "-" + widgetVersion;
        final int index = Integer.parseInt(Objects.toString(log.getTaggedData(INDEX), ZERO));
        if (log.getType() == MetricsEvent.ACTION_TEXT_SELECTION_START) {
            String sessionId = Objects.toString(log.getTaggedData(SESSION_ID), "");
            sessionId = sessionId.substring(sessionId.lastIndexOf("-") + 1);
            Log.d(LOG_TAG, String.format("New selection session: %s (%s)", widget, sessionId));
        }

        final String model = Objects.toString(log.getTaggedData(MODEL_NAME), UNKNOWN);
        final String entity = Objects.toString(log.getTaggedData(ENTITY_TYPE), UNKNOWN);
        final String type = getLogTypeString(log.getType());
        final String subType = getLogSubTypeString(log.getSubtype());
        final int smartStart = Integer.parseInt(
                Objects.toString(log.getTaggedData(SMART_START), ZERO));
        final int smartEnd = Integer.parseInt(
                Objects.toString(log.getTaggedData(SMART_END), ZERO));
        final int eventStart = Integer.parseInt(
                Objects.toString(log.getTaggedData(EVENT_START), ZERO));
        final int eventEnd = Integer.parseInt(
                Objects.toString(log.getTaggedData(EVENT_END), ZERO));

        Log.v(LOG_TAG,
                String.format(Locale.US, "%2d: %s/%s/%s, range=%d,%d - smart_range=%d,%d (%s/%s)",
                        index, type, subType, entity, eventStart, eventEnd, smartStart, smartEnd,
                        widget, model));
    }

    /**
     * Returns a token iterator for tokenizing text for logging purposes.
     */
    public static BreakIterator getTokenIterator(@NonNull Locale locale) {
        return BreakIterator.getWordInstance(Preconditions.checkNotNull(locale));
    }

    /**
     * Creates a string id that may be used to identify a TextClassifier result.
     */
    public static String createId(
            String text, int start, int end, Context context, int modelVersion,
            List<Locale> locales) {
        Preconditions.checkNotNull(text);
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(locales);
        final StringJoiner localesJoiner = new StringJoiner(",");
        for (Locale locale : locales) {
            localesJoiner.add(locale.toLanguageTag());
        }
        final String modelName = String.format(Locale.US, "%s_v%d", localesJoiner.toString(),
                modelVersion);
        final int hash = Objects.hash(text, start, end, context.getPackageName());
        return SignatureParser.createSignature(CLASSIFIER_ID, modelName, hash);
    }

    /**
     * Helper for creating and parsing string ids for
     * {@link android.view.textclassifier.TextClassifierImpl}.
     */
    @VisibleForTesting
    public static final class SignatureParser {

        static String createSignature(String classifierId, String modelName, int hash) {
            return String.format(Locale.US, "%s|%s|%d", classifierId, modelName, hash);
        }

        static String getClassifierId(@Nullable String signature) {
            if (signature == null) {
                return "";
            }
            final int end = signature.indexOf("|");
            if (end >= 0) {
                return signature.substring(0, end);
            }
            return "";
        }

        static String getModelName(@Nullable String signature) {
            if (signature == null) {
                return "";
            }
            final int start = signature.indexOf("|") + 1;
            final int end = signature.indexOf("|", start);
            if (start >= 1 && end >= start) {
                return signature.substring(start, end);
            }
            return "";
        }

        static int getHash(@Nullable String signature) {
            if (signature == null) {
                return 0;
            }
            final int index1 = signature.indexOf("|");
            final int index2 = signature.indexOf("|", index1);
            if (index2 > 0) {
                return Integer.parseInt(signature.substring(index2));
            }
            return 0;
        }
    }
}
