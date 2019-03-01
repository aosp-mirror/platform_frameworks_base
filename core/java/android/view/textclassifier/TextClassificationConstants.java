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
 * smart_linkify_enabled                            (boolean)
 * system_textclassifier_enabled                    (boolean)
 * model_dark_launch_enabled                        (boolean)
 * smart_selection_enabled                          (boolean)
 * smart_text_share_enabled                         (boolean)
 * smart_linkify_enabled                            (boolean)
 * smart_select_animation_enabled                   (boolean)
 * suggest_selection_max_range_length               (int)
 * classify_text_max_range_length                   (int)
 * generate_links_max_text_length                   (int)
 * generate_links_log_sample_rate                   (int)
 * entity_list_default                              (String[])
 * entity_list_not_editable                         (String[])
 * entity_list_editable                             (String[])
 * in_app_conversation_action_types_default         (String[])
 * notification_conversation_action_types_default   (String[])
 * lang_id_threshold_override                       (float)
 * template_intent_factory_enabled                  (boolean)
 * translate_in_classification_enabled              (boolean)
 * detect_languages_from_text_enabled               (boolean)
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

    /**
     * Whether the smart linkify feature is enabled.
     */
    private static final String SMART_LINKIFY_ENABLED = "smart_linkify_enabled";
    /**
     * Whether SystemTextClassifier is enabled.
     */
    private static final String SYSTEM_TEXT_CLASSIFIER_ENABLED = "system_textclassifier_enabled";
    /**
     * Whether TextClassifierImpl is enabled.
     */
    private static final String LOCAL_TEXT_CLASSIFIER_ENABLED = "local_textclassifier_enabled";
    /**
     * Enable smart selection without a visible UI changes.
     */
    private static final String MODEL_DARK_LAUNCH_ENABLED = "model_dark_launch_enabled";

    /**
     * Whether the smart selection feature is enabled.
     */
    private static final String SMART_SELECTION_ENABLED = "smart_selection_enabled";
    /**
     * Whether the smart text share feature is enabled.
     */
    private static final String SMART_TEXT_SHARE_ENABLED = "smart_text_share_enabled";
    /**
     * Whether animation for smart selection is enabled.
     */
    private static final String SMART_SELECT_ANIMATION_ENABLED =
            "smart_select_animation_enabled";
    /**
     * Max length of text that suggestSelection can accept.
     */
    private static final String SUGGEST_SELECTION_MAX_RANGE_LENGTH =
            "suggest_selection_max_range_length";
    /**
     * Max length of text that classifyText can accept.
     */
    private static final String CLASSIFY_TEXT_MAX_RANGE_LENGTH = "classify_text_max_range_length";
    /**
     * Max length of text that generateLinks can accept.
     */
    private static final String GENERATE_LINKS_MAX_TEXT_LENGTH = "generate_links_max_text_length";
    /**
     * Sampling rate for generateLinks logging.
     */
    private static final String GENERATE_LINKS_LOG_SAMPLE_RATE =
            "generate_links_log_sample_rate";
    /**
     * A colon(:) separated string that specifies the default entities types for
     * generateLinks when hint is not given.
     */
    private static final String ENTITY_LIST_DEFAULT = "entity_list_default";
    /**
     * A colon(:) separated string that specifies the default entities types for
     * generateLinks when the text is in a not editable UI widget.
     */
    private static final String ENTITY_LIST_NOT_EDITABLE = "entity_list_not_editable";
    /**
     * A colon(:) separated string that specifies the default entities types for
     * generateLinks when the text is in an editable UI widget.
     */
    private static final String ENTITY_LIST_EDITABLE = "entity_list_editable";
    /**
     * A colon(:) separated string that specifies the default action types for
     * suggestConversationActions when the suggestions are used in an app.
     */
    private static final String IN_APP_CONVERSATION_ACTION_TYPES_DEFAULT =
            "in_app_conversation_action_types_default";
    /**
     * A colon(:) separated string that specifies the default action types for
     * suggestConversationActions when the suggestions are used in a notification.
     */
    private static final String NOTIFICATION_CONVERSATION_ACTION_TYPES_DEFAULT =
            "notification_conversation_action_types_default";
    /**
     * Threshold to accept a suggested language from LangID model.
     */
    private static final String LANG_ID_THRESHOLD_OVERRIDE = "lang_id_threshold_override";
    /**
     * Whether to enable {@link android.view.textclassifier.TemplateIntentFactory}.
     */
    private static final String TEMPLATE_INTENT_FACTORY_ENABLED = "template_intent_factory_enabled";

    /**
     * Whether to enable "translate" action in classifyText.
     */
    private static final String TRANSLATE_IN_CLASSIFICATION_ENABLED =
            "translate_in_classification_enabled";
    /**
     * Whether to detect the languages of the text in request by using langId for the native
     * model.
     */
    private static final String DETECT_LANGUAGES_FROM_TEXT_ENABLED =
            "detect_languages_from_text_enabled";

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
    private static final String STRING_LIST_DELIMITER = ":";
    private static final String ENTITY_LIST_DEFAULT_VALUE = new StringJoiner(STRING_LIST_DELIMITER)
            .add(TextClassifier.TYPE_ADDRESS)
            .add(TextClassifier.TYPE_EMAIL)
            .add(TextClassifier.TYPE_PHONE)
            .add(TextClassifier.TYPE_URL)
            .add(TextClassifier.TYPE_DATE)
            .add(TextClassifier.TYPE_DATE_TIME)
            .add(TextClassifier.TYPE_FLIGHT_NUMBER).toString();
    private static final String CONVERSATION_ACTIONS_TYPES_DEFAULT_VALUES =
            new StringJoiner(STRING_LIST_DELIMITER)
                    .add(ConversationAction.TYPE_TEXT_REPLY)
                    .add(ConversationAction.TYPE_CREATE_REMINDER)
                    .add(ConversationAction.TYPE_CALL_PHONE)
                    .add(ConversationAction.TYPE_OPEN_URL)
                    .add(ConversationAction.TYPE_SEND_EMAIL)
                    .add(ConversationAction.TYPE_SEND_SMS)
                    .add(ConversationAction.TYPE_TRACK_FLIGHT)
                    .add(ConversationAction.TYPE_VIEW_CALENDAR)
                    .add(ConversationAction.TYPE_VIEW_MAP)
                    .add(ConversationAction.TYPE_ADD_CONTACT)
                    .toString();
    /**
     * < 0  : Not set. Use value from LangId model.
     * 0 - 1: Override value in LangId model.
     *
     * @see EntityConfidence
     */
    private static final float LANG_ID_THRESHOLD_OVERRIDE_DEFAULT = -1f;
    private static final boolean TEMPLATE_INTENT_FACTORY_ENABLED_DEFAULT = true;
    private static final boolean TRANSLATE_IN_CLASSIFICATION_ENABLED_DEFAULT = true;
    private static final boolean DETECT_LANGUAGES_FROM_TEXT_ENABLED_DEFAULT = true;

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
    private final float mLangIdThresholdOverride;
    private final boolean mTemplateIntentFactoryEnabled;
    private final boolean mTranslateInClassificationEnabled;
    private final boolean mDetectLanguagesFromTextEnabled;

    private TextClassificationConstants(@Nullable String settings) {
        ConfigParser configParser = new ConfigParser(settings);
        mSystemTextClassifierEnabled =
                configParser.getBoolean(
                        SYSTEM_TEXT_CLASSIFIER_ENABLED,
                        SYSTEM_TEXT_CLASSIFIER_ENABLED_DEFAULT);
        mLocalTextClassifierEnabled =
                configParser.getBoolean(
                        LOCAL_TEXT_CLASSIFIER_ENABLED,
                        LOCAL_TEXT_CLASSIFIER_ENABLED_DEFAULT);
        mModelDarkLaunchEnabled =
                configParser.getBoolean(
                        MODEL_DARK_LAUNCH_ENABLED,
                        MODEL_DARK_LAUNCH_ENABLED_DEFAULT);
        mSmartSelectionEnabled =
                configParser.getBoolean(
                        SMART_SELECTION_ENABLED,
                        SMART_SELECTION_ENABLED_DEFAULT);
        mSmartTextShareEnabled =
                configParser.getBoolean(
                        SMART_TEXT_SHARE_ENABLED,
                        SMART_TEXT_SHARE_ENABLED_DEFAULT);
        mSmartLinkifyEnabled =
                configParser.getBoolean(
                        SMART_LINKIFY_ENABLED,
                        SMART_LINKIFY_ENABLED_DEFAULT);
        mSmartSelectionAnimationEnabled =
                configParser.getBoolean(
                        SMART_SELECT_ANIMATION_ENABLED,
                        SMART_SELECT_ANIMATION_ENABLED_DEFAULT);
        mSuggestSelectionMaxRangeLength =
                configParser.getInt(
                        SUGGEST_SELECTION_MAX_RANGE_LENGTH,
                        SUGGEST_SELECTION_MAX_RANGE_LENGTH_DEFAULT);
        mClassifyTextMaxRangeLength =
                configParser.getInt(
                        CLASSIFY_TEXT_MAX_RANGE_LENGTH,
                        CLASSIFY_TEXT_MAX_RANGE_LENGTH_DEFAULT);
        mGenerateLinksMaxTextLength =
                configParser.getInt(
                        GENERATE_LINKS_MAX_TEXT_LENGTH,
                        GENERATE_LINKS_MAX_TEXT_LENGTH_DEFAULT);
        mGenerateLinksLogSampleRate =
                configParser.getInt(
                        GENERATE_LINKS_LOG_SAMPLE_RATE,
                        GENERATE_LINKS_LOG_SAMPLE_RATE_DEFAULT);
        mEntityListDefault = parseStringList(configParser.getString(
                ENTITY_LIST_DEFAULT,
                ENTITY_LIST_DEFAULT_VALUE));
        mEntityListNotEditable = parseStringList(
                configParser.getString(
                        ENTITY_LIST_NOT_EDITABLE,
                        ENTITY_LIST_DEFAULT_VALUE));
        mEntityListEditable = parseStringList(
                configParser.getString(
                        ENTITY_LIST_EDITABLE,
                        ENTITY_LIST_DEFAULT_VALUE));
        mInAppConversationActionTypesDefault = parseStringList(
                configParser.getString(
                        IN_APP_CONVERSATION_ACTION_TYPES_DEFAULT,
                        CONVERSATION_ACTIONS_TYPES_DEFAULT_VALUES));
        mNotificationConversationActionTypesDefault = parseStringList(
                configParser.getString(
                        NOTIFICATION_CONVERSATION_ACTION_TYPES_DEFAULT,
                        CONVERSATION_ACTIONS_TYPES_DEFAULT_VALUES));
        mLangIdThresholdOverride =
                configParser.getFloat(
                        LANG_ID_THRESHOLD_OVERRIDE,
                        LANG_ID_THRESHOLD_OVERRIDE_DEFAULT);
        mTemplateIntentFactoryEnabled = configParser.getBoolean(
                TEMPLATE_INTENT_FACTORY_ENABLED,
                TEMPLATE_INTENT_FACTORY_ENABLED_DEFAULT);
        mTranslateInClassificationEnabled = configParser.getBoolean(
                TRANSLATE_IN_CLASSIFICATION_ENABLED, TRANSLATE_IN_CLASSIFICATION_ENABLED_DEFAULT);
        mDetectLanguagesFromTextEnabled = configParser.getBoolean(
                DETECT_LANGUAGES_FROM_TEXT_ENABLED, DETECT_LANGUAGES_FROM_TEXT_ENABLED_DEFAULT);
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

    public float getLangIdThresholdOverride() {
        return mLangIdThresholdOverride;
    }

    public boolean isTemplateIntentFactoryEnabled() {
        return mTemplateIntentFactoryEnabled;
    }

    public boolean isTranslateInClassificationEnabled() {
        return mTranslateInClassificationEnabled;
    }

    public boolean isDetectLanguagesFromTextEnabled() {
        return mDetectLanguagesFromTextEnabled;
    }

    private static List<String> parseStringList(String listStr) {
        return Collections.unmodifiableList(Arrays.asList(listStr.split(STRING_LIST_DELIMITER)));
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
        pw.printPair("getLangIdThresholdOverride", mLangIdThresholdOverride);
        pw.printPair("isTemplateIntentFactoryEnabled", mTemplateIntentFactoryEnabled);
        pw.printPair("isTranslateInClassificationEnabled", mTranslateInClassificationEnabled);
        pw.printPair("isDetectLanguageFromTextEnabled", mDetectLanguagesFromTextEnabled);
        pw.decreaseIndent();
        pw.println();
    }
}
