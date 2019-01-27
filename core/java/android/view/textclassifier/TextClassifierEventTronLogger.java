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

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXTCLASSIFIER_MODEL;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_FIRST_ENTITY_TYPE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_SCORE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_SECOND_ENTITY_TYPE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_SESSION_ID;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_THIRD_ENTITY_TYPE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_WIDGET_TYPE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TEXT_CLASSIFIER_WIDGET_VERSION;

import android.metrics.LogMaker;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.Preconditions;


/**
 * Log {@link TextClassifierEvent} by using Tron, only support language detection and
 * conversation actions.
 *
 * @hide
 */
public final class TextClassifierEventTronLogger {

    private static final String TAG = "TCEventTronLogger";

    private final MetricsLogger mMetricsLogger;

    public TextClassifierEventTronLogger() {
        this(new MetricsLogger());
    }

    @VisibleForTesting
    public TextClassifierEventTronLogger(MetricsLogger metricsLogger) {
        mMetricsLogger = Preconditions.checkNotNull(metricsLogger);
    }

    /** Emits a text classifier event to the logs. */
    public void writeEvent(TextClassifierEvent event) {
        Preconditions.checkNotNull(event);

        int category = getCategory(event);
        if (category == -1) {
            Log.w(TAG, "Unknown category: " + event.getEventCategory());
            return;
        }
        final LogMaker log = new LogMaker(category)
                .setSubtype(getLogType(event))
                .addTaggedData(FIELD_TEXT_CLASSIFIER_SESSION_ID, event.getResultId())
                .addTaggedData(FIELD_TEXTCLASSIFIER_MODEL, getModelName(event))
                .addTaggedData(FIELD_TEXT_CLASSIFIER_SCORE, event.getScore());

        String[] entityTypes = event.getEntityTypes();
        // The old logger does not support a field of list type, and thus workaround by store them
        // in three separate fields. This is not an issue with the new logger.
        if (entityTypes.length >= 1) {
            log.addTaggedData(FIELD_TEXT_CLASSIFIER_FIRST_ENTITY_TYPE, entityTypes[0]);
        }
        if (entityTypes.length >= 2) {
            log.addTaggedData(FIELD_TEXT_CLASSIFIER_SECOND_ENTITY_TYPE, entityTypes[1]);
        }
        if (entityTypes.length >= 3) {
            log.addTaggedData(FIELD_TEXT_CLASSIFIER_THIRD_ENTITY_TYPE, entityTypes[2]);
        }
        TextClassificationContext eventContext = event.getEventContext();
        if (eventContext != null) {
            log.addTaggedData(FIELD_TEXT_CLASSIFIER_WIDGET_TYPE, eventContext.getWidgetType());
            log.addTaggedData(FIELD_TEXT_CLASSIFIER_WIDGET_VERSION,
                    eventContext.getWidgetVersion());
            log.setPackageName(eventContext.getPackageName());
        }
        mMetricsLogger.write(log);
        debugLog(log);
    }

    private static String getModelName(TextClassifierEvent event) {
        if (event.getModelName() != null) {
            return event.getModelName();
        }
        return SelectionSessionLogger.SignatureParser.getModelName(event.getResultId());
    }

    private static int getCategory(TextClassifierEvent event) {
        switch (event.getEventCategory()) {
            case TextClassifierEvent.CATEGORY_CONVERSATION_ACTIONS:
                return MetricsEvent.CONVERSATION_ACTIONS;
            case TextClassifierEvent.CATEGORY_LANGUAGE_DETECTION:
                return MetricsEvent.LANGUAGE_DETECTION;
        }
        return -1;
    }

    private static int getLogType(TextClassifierEvent event) {
        switch (event.getEventType()) {
            case TextClassifierEvent.TYPE_SMART_ACTION:
                return MetricsEvent.ACTION_TEXT_SELECTION_SMART_SHARE;
            case TextClassifierEvent.TYPE_ACTIONS_SHOWN:
                return MetricsEvent.ACTION_TEXT_CLASSIFIER_ACTIONS_SHOWN;
            case TextClassifierEvent.TYPE_MANUAL_REPLY:
                return MetricsEvent.ACTION_TEXT_CLASSIFIER_MANUAL_REPLY;
            case TextClassifierEvent.TYPE_ACTIONS_GENERATED:
                return MetricsEvent.ACTION_TEXT_CLASSIFIER_ACTIONS_GENERATED;
            default:
                return MetricsEvent.VIEW_UNKNOWN;
        }
    }

    private String toCategoryName(int category) {
        switch (category) {
            case MetricsEvent.CONVERSATION_ACTIONS:
                return "conversation_actions";
            case MetricsEvent.LANGUAGE_DETECTION:
                return "language_detection";
        }
        return "unknown";
    }

    private String toEventName(int logType) {
        switch (logType) {
            case MetricsEvent.ACTION_TEXT_SELECTION_SMART_SHARE:
                return "smart_share";
            case MetricsEvent.ACTION_TEXT_CLASSIFIER_ACTIONS_SHOWN:
                return "actions_shown";
            case MetricsEvent.ACTION_TEXT_CLASSIFIER_MANUAL_REPLY:
                return "manual_reply";
            case MetricsEvent.ACTION_TEXT_CLASSIFIER_ACTIONS_GENERATED:
                return "actions_generated";
        }
        return "unknown";
    }

    private void debugLog(LogMaker log) {
        if (!Log.ENABLE_FULL_LOGGING) {
            return;
        }
        final String id = String.valueOf(log.getTaggedData(FIELD_TEXT_CLASSIFIER_SESSION_ID));
        final String categoryName = toCategoryName(log.getCategory());
        final String eventName = toEventName(log.getSubtype());
        final String widgetType =
                String.valueOf(log.getTaggedData(FIELD_TEXT_CLASSIFIER_WIDGET_TYPE));
        final String widgetVersion =
                String.valueOf(log.getTaggedData(FIELD_TEXT_CLASSIFIER_WIDGET_VERSION));
        final String model = String.valueOf(log.getTaggedData(FIELD_TEXTCLASSIFIER_MODEL));
        final String firstEntityType =
                String.valueOf(log.getTaggedData(FIELD_TEXT_CLASSIFIER_FIRST_ENTITY_TYPE));
        final String secondEntityType =
                String.valueOf(log.getTaggedData(FIELD_TEXT_CLASSIFIER_SECOND_ENTITY_TYPE));
        final String thirdEntityType =
                String.valueOf(log.getTaggedData(FIELD_TEXT_CLASSIFIER_THIRD_ENTITY_TYPE));
        final String score =
                String.valueOf(log.getTaggedData(FIELD_TEXT_CLASSIFIER_SCORE));

        StringBuilder builder = new StringBuilder();
        builder.append("writeEvent: ");
        builder.append("id=").append(id);
        builder.append(", category=").append(categoryName);
        builder.append(", eventName=").append(eventName);
        builder.append(", widgetType=").append(widgetType);
        builder.append(", widgetVersion=").append(widgetVersion);
        builder.append(", model=").append(model);
        builder.append(", firstEntityType=").append(firstEntityType);
        builder.append(", secondEntityType=").append(secondEntityType);
        builder.append(", thirdEntityType=").append(thirdEntityType);
        builder.append(", score=").append(score);

        Log.v(TAG, builder.toString());
    }
}
