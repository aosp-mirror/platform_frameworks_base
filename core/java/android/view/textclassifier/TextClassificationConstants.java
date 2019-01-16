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

import android.annotation.Nullable;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * TextClassifier specific settings.
 * This is encoded as a key=value list, separated by commas. Ex:
 *
 * <pre>
 * smart_linkify_enabled                    (boolean)
 * system_textclassifier_enabled            (boolean)
 * model_dark_launch_enabled                (boolean)
 * smart_selection_enabled                  (boolean)
 * smart_text_share_enabled                 (boolean)
 * smart_linkify_enabled                    (boolean)
 * smart_select_animation_enabled           (boolean)
 * suggest_selection_max_range_length       (int)
 * classify_text_max_range_length           (int)
 * generate_links_max_text_length           (int)
 * generate_links_log_sample_rate           (int)
 * entity_list_default                      (String[])
 * entity_list_not_editable                 (String[])
 * entity_list_editable                     (String[])
 * </pre>
 *
 * <p>
 * Type: string
 * see also android.provider.Settings.Global.TEXT_CLASSIFIER_CONSTANTS
 *
 * Example of setting the values for testing.
 * adb shell settings put global text_classifier_constants \
 *      model_dark_launch_enabled=true,smart_selection_enabled=true,\
 *      entity_list_default=phone:address
 * @hide
 */
public final class TextClassificationConstants {

    private static final String LOG_TAG = "TextClassificationConstants";

    private static final String LOCAL_TEXT_CLASSIFIER_ENABLED =
            "local_textclassifier_enabled";
    private static final String SYSTEM_TEXT_CLASSIFIER_ENABLED =
            "system_textclassifier_enabled";
    private static final String MODEL_DARK_LAUNCH_ENABLED =
            "model_dark_launch_enabled";
    private static final String SMART_SELECTION_ENABLED =
            "smart_selection_enabled";
    private static final String SMART_TEXT_SHARE_ENABLED =
            "smart_text_share_enabled";
    private static final String SMART_LINKIFY_ENABLED =
            "smart_linkify_enabled";
    private static final String SMART_SELECT_ANIMATION_ENABLED =
            "smart_select_animation_enabled";
    private static final String SUGGEST_SELECTION_MAX_RANGE_LENGTH =
            "suggest_selection_max_range_length";
    private static final String CLASSIFY_TEXT_MAX_RANGE_LENGTH =
            "classify_text_max_range_length";
    private static final String GENERATE_LINKS_MAX_TEXT_LENGTH =
            "generate_links_max_text_length";
    private static final String GENERATE_LINKS_LOG_SAMPLE_RATE =
            "generate_links_log_sample_rate";
    private static final String ENTITY_LIST_DEFAULT =
            "entity_list_default";
    private static final String ENTITY_LIST_NOT_EDITABLE =
            "entity_list_not_editable";
    private static final String ENTITY_LIST_EDITABLE =
            "entity_list_editable";
    private static final String IN_APP_CONVERSATION_ACTION_TYPES_DEFAULT =
            "in_app_conversation_action_types_default";
    private static final String NOTIFICATION_CONVERSATION_ACTION_TYPES_DEFAULT =
            "notification_conversation_action_types_default";

    private static final boolean LOCAL_TEXT_CLASSIFIER_ENABLED_DEFAULT = true;
    private static final boolean SYSTEM_TEXT_CLASSIFIER_ENABLED_DEFAULT = true;
    private static final boolean MODEL_DARK_LAUNCH_ENABLED_DEFAULT = false;
    private static final boolean SMART_SELECTION_ENABLED_DEFAULT = true;
    private static final boolean SMART_TEXT_SHARE_ENABLED_DEFAULT = true;
    private static final boolean SMART_LINKIFY_ENABLED_DEFAULT = true;
    private static final boolean SMART_SELECT_ANIMATION_ENABLED_DEFAULT = true;
    private static final int SUGGEST_SELECTION_MAX_RANGE_LENGTH_DEFAULT = 10 * 1000;
    private static final int CLASSIFY_TEXT_MAX_RANGE_LENGTH_DEFAULT = 10 * 1000;
    private static final int GENERATE_LINKS_MAX_TEXT_LENGTH_DEFAULT = 100 * 1000;
    private static final int GENERATE_LINKS_LOG_SAMPLE_RATE_DEFAULT = 100;
    private static final String ENTITY_LIST_DELIMITER = ":";
    private static final String ENTITY_LIST_DEFAULT_VALUE = new StringJoiner(ENTITY_LIST_DELIMITER)
            .add(TextClassifier.TYPE_ADDRESS)
            .add(TextClassifier.TYPE_EMAIL)
            .add(TextClassifier.TYPE_PHONE)
            .add(TextClassifier.TYPE_URL)
            .add(TextClassifier.TYPE_DATE)
            .add(TextClassifier.TYPE_DATE_TIME)
            .add(TextClassifier.TYPE_FLIGHT_NUMBER).toString();
    private static final String CONVERSATION_ACTIONS_TYPES_DEFAULT_VALUES =
            new StringJoiner(ENTITY_LIST_DELIMITER)
                    .add(ConversationAction.TYPE_TEXT_REPLY)
                    .add(ConversationAction.TYPE_CREATE_REMINDER)
                    .add(ConversationAction.TYPE_CALL_PHONE)
                    .add(ConversationAction.TYPE_OPEN_URL)
                    .add(ConversationAction.TYPE_SEND_EMAIL)
                    .add(ConversationAction.TYPE_SEND_SMS)
                    .add(ConversationAction.TYPE_TRACK_FLIGHT)
                    .add(ConversationAction.TYPE_VIEW_CALENDAR)
                    .add(ConversationAction.TYPE_VIEW_MAP)
                    .toString();

    private final boolean mSystemTextClassifierEnabled;
    private final boolean mLocalTextClassifierEnabled;
    private final boolean mModelDarkLaunchEnabled;
    private final boolean mSmartSelectionEnabled;
    private final boolean mSmartTextShareEnabled;
    private final boolean mSmartLinkifyEnabled;
    private final boolean mSmartSelectionAnimationEnabled;
    private final int mSuggestSelectionMaxRangeLength;
    private final int mClassifyTextMaxRangeLength;
    private final int mGenerateLinksMaxTextLength;
    private final int mGenerateLinksLogSampleRate;
    private final List<String> mEntityListDefault;
    private final List<String> mEntityListNotEditable;
    private final List<String> mEntityListEditable;
    private final List<String> mInAppConversationActionTypesDefault;
    private final List<String> mNotificationConversationActionTypesDefault;

    private TextClassificationConstants(@Nullable String settings) {
        final KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(settings);
        } catch (IllegalArgumentException e) {
            // Failed to parse the settings string, log this and move on with defaults.
            Slog.e(LOG_TAG, "Bad TextClassifier settings: " + settings);
        }
        mSystemTextClassifierEnabled = parser.getBoolean(
                SYSTEM_TEXT_CLASSIFIER_ENABLED,
                SYSTEM_TEXT_CLASSIFIER_ENABLED_DEFAULT);
        mLocalTextClassifierEnabled = parser.getBoolean(
                LOCAL_TEXT_CLASSIFIER_ENABLED,
                LOCAL_TEXT_CLASSIFIER_ENABLED_DEFAULT);
        mModelDarkLaunchEnabled = parser.getBoolean(
                MODEL_DARK_LAUNCH_ENABLED,
                MODEL_DARK_LAUNCH_ENABLED_DEFAULT);
        mSmartSelectionEnabled = parser.getBoolean(
                SMART_SELECTION_ENABLED,
                SMART_SELECTION_ENABLED_DEFAULT);
        mSmartTextShareEnabled = parser.getBoolean(
                SMART_TEXT_SHARE_ENABLED,
                SMART_TEXT_SHARE_ENABLED_DEFAULT);
        mSmartLinkifyEnabled = parser.getBoolean(
                SMART_LINKIFY_ENABLED,
                SMART_LINKIFY_ENABLED_DEFAULT);
        mSmartSelectionAnimationEnabled = parser.getBoolean(
                SMART_SELECT_ANIMATION_ENABLED,
                SMART_SELECT_ANIMATION_ENABLED_DEFAULT);
        mSuggestSelectionMaxRangeLength = parser.getInt(
                SUGGEST_SELECTION_MAX_RANGE_LENGTH,
                SUGGEST_SELECTION_MAX_RANGE_LENGTH_DEFAULT);
        mClassifyTextMaxRangeLength = parser.getInt(
                CLASSIFY_TEXT_MAX_RANGE_LENGTH,
                CLASSIFY_TEXT_MAX_RANGE_LENGTH_DEFAULT);
        mGenerateLinksMaxTextLength = parser.getInt(
                GENERATE_LINKS_MAX_TEXT_LENGTH,
                GENERATE_LINKS_MAX_TEXT_LENGTH_DEFAULT);
        mGenerateLinksLogSampleRate = parser.getInt(
                GENERATE_LINKS_LOG_SAMPLE_RATE,
                GENERATE_LINKS_LOG_SAMPLE_RATE_DEFAULT);
        mEntityListDefault = parseEntityList(parser.getString(
                ENTITY_LIST_DEFAULT,
                ENTITY_LIST_DEFAULT_VALUE));
        mEntityListNotEditable = parseEntityList(parser.getString(
                ENTITY_LIST_NOT_EDITABLE,
                ENTITY_LIST_DEFAULT_VALUE));
        mEntityListEditable = parseEntityList(parser.getString(
                ENTITY_LIST_EDITABLE,
                ENTITY_LIST_DEFAULT_VALUE));
        mInAppConversationActionTypesDefault = parseEntityList(parser.getString(
                IN_APP_CONVERSATION_ACTION_TYPES_DEFAULT,
                CONVERSATION_ACTIONS_TYPES_DEFAULT_VALUES));
        mNotificationConversationActionTypesDefault = parseEntityList(parser.getString(
                NOTIFICATION_CONVERSATION_ACTION_TYPES_DEFAULT,
                CONVERSATION_ACTIONS_TYPES_DEFAULT_VALUES));
    }

    /** Load from a settings string. */
    public static TextClassificationConstants loadFromString(String settings) {
        return new TextClassificationConstants(settings);
    }

    public boolean isLocalTextClassifierEnabled() {
        return mLocalTextClassifierEnabled;
    }

    public boolean isSystemTextClassifierEnabled() {
        return mSystemTextClassifierEnabled;
    }

    public boolean isModelDarkLaunchEnabled() {
        return mModelDarkLaunchEnabled;
    }

    public boolean isSmartSelectionEnabled() {
        return mSmartSelectionEnabled;
    }

    public boolean isSmartTextShareEnabled() {
        return mSmartTextShareEnabled;
    }

    public boolean isSmartLinkifyEnabled() {
        return mSmartLinkifyEnabled;
    }

    public boolean isSmartSelectionAnimationEnabled() {
        return mSmartSelectionAnimationEnabled;
    }

    public int getSuggestSelectionMaxRangeLength() {
        return mSuggestSelectionMaxRangeLength;
    }

    public int getClassifyTextMaxRangeLength() {
        return mClassifyTextMaxRangeLength;
    }

    public int getGenerateLinksMaxTextLength() {
        return mGenerateLinksMaxTextLength;
    }

    public int getGenerateLinksLogSampleRate() {
        return mGenerateLinksLogSampleRate;
    }

    public List<String> getEntityListDefault() {
        return mEntityListDefault;
    }

    public List<String> getEntityListNotEditable() {
        return mEntityListNotEditable;
    }

    public List<String> getEntityListEditable() {
        return mEntityListEditable;
    }

    public List<String> getInAppConversationActionTypes() {
        return mInAppConversationActionTypesDefault;
    }

    public List<String> getNotificationConversationActionTypes() {
        return mNotificationConversationActionTypesDefault;
    }

    private static List<String> parseEntityList(String listStr) {
        return Collections.unmodifiableList(Arrays.asList(listStr.split(ENTITY_LIST_DELIMITER)));
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("TextClassificationConstants:");
        pw.increaseIndent();
        pw.printPair("isLocalTextClassifierEnabled", mLocalTextClassifierEnabled);
        pw.printPair("isSystemTextClassifierEnabled", mSystemTextClassifierEnabled);
        pw.printPair("isModelDarkLaunchEnabled", mModelDarkLaunchEnabled);
        pw.printPair("isSmartSelectionEnabled", mSmartSelectionEnabled);
        pw.printPair("isSmartTextShareEnabled", mSmartTextShareEnabled);
        pw.printPair("isSmartLinkifyEnabled", mSmartLinkifyEnabled);
        pw.printPair("isSmartSelectionAnimationEnabled", mSmartSelectionAnimationEnabled);
        pw.printPair("getSuggestSelectionMaxRangeLength", mSuggestSelectionMaxRangeLength);
        pw.printPair("getClassifyTextMaxRangeLength", mClassifyTextMaxRangeLength);
        pw.printPair("getGenerateLinksMaxTextLength", mGenerateLinksMaxTextLength);
        pw.printPair("getGenerateLinksLogSampleRate", mGenerateLinksLogSampleRate);
        pw.printPair("getEntityListDefault", mEntityListDefault);
        pw.printPair("getEntityListNotEditable", mEntityListNotEditable);
        pw.printPair("getEntityListEditable", mEntityListEditable);
        pw.printPair("getInAppConversationActionTypes", mInAppConversationActionTypesDefault);
        pw.printPair("getNotificationConversationActionTypes",
                mNotificationConversationActionTypesDefault);
        pw.decreaseIndent();
        pw.println();
    }
}
