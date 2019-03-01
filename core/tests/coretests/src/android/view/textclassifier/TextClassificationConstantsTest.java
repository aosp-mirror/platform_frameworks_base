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

import static com.google.common.truth.Truth.assertWithMessage;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationConstantsTest {

    private static final float EPSILON = 0.0001f;

    @Test
    public void testLoadFromString() {
        final String s = "local_textclassifier_enabled=true,"
                + "system_textclassifier_enabled=true,"
                + "model_dark_launch_enabled=true,"
                + "smart_selection_enabled=true,"
                + "smart_text_share_enabled=true,"
                + "smart_linkify_enabled=true,"
                + "smart_select_animation_enabled=true,"
                + "suggest_selection_max_range_length=10,"
                + "classify_text_max_range_length=11,"
                + "generate_links_max_text_length=12,"
                + "generate_links_log_sample_rate=13,"
                + "entity_list_default=phone,"
                + "entity_list_not_editable=address:flight,"
                + "entity_list_editable=date:datetime,"
                + "in_app_conversation_action_types_default=text_reply,"
                + "notification_conversation_action_types_default=send_email:call_phone,"
                + "lang_id_threshold_override=0.3";
        final TextClassificationConstants constants =
                TextClassificationConstants.loadFromString(s);

        assertWithMessage("local_textclassifier_enabled")
                .that(constants.isLocalTextClassifierEnabled()).isTrue();
        assertWithMessage("system_textclassifier_enabled")
                .that(constants.isSystemTextClassifierEnabled()).isTrue();
        assertWithMessage("model_dark_launch_enabled")
                .that(constants.isModelDarkLaunchEnabled()).isTrue();
        assertWithMessage("smart_selection_enabled")
                .that(constants.isSmartSelectionEnabled()).isTrue();
        assertWithMessage("smart_text_share_enabled")
                .that(constants.isSmartTextShareEnabled()).isTrue();
        assertWithMessage("smart_linkify_enabled")
                .that(constants.isSmartLinkifyEnabled()).isTrue();
        assertWithMessage("smart_select_animation_enabled")
                .that(constants.isSmartSelectionAnimationEnabled()).isTrue();
        assertWithMessage("suggest_selection_max_range_length")
                .that(constants.getSuggestSelectionMaxRangeLength()).isEqualTo(10);
        assertWithMessage("classify_text_max_range_length")
                .that(constants.getClassifyTextMaxRangeLength()).isEqualTo(11);
        assertWithMessage("generate_links_max_text_length")
                .that(constants.getGenerateLinksMaxTextLength()).isEqualTo(12);
        assertWithMessage("generate_links_log_sample_rate")
                .that(constants.getGenerateLinksLogSampleRate()).isEqualTo(13);
        assertWithMessage("entity_list_default")
                .that(constants.getEntityListDefault())
                .containsExactly("phone");
        assertWithMessage("entity_list_not_editable")
                .that(constants.getEntityListNotEditable())
                .containsExactly("address", "flight");
        assertWithMessage("entity_list_editable")
                .that(constants.getEntityListEditable())
                .containsExactly("date", "datetime");
        assertWithMessage("in_app_conversation_action_types_default")
                .that(constants.getInAppConversationActionTypes())
                .containsExactly("text_reply");
        assertWithMessage("notification_conversation_action_types_default")
                .that(constants.getNotificationConversationActionTypes())
                .containsExactly("send_email", "call_phone");
        assertWithMessage("lang_id_threshold_override")
                .that(constants.getLangIdThresholdOverride()).isWithin(EPSILON).of(0.3f);
    }

    @Test
    public void testLoadFromString_differentValues() {
        final String s = "local_textclassifier_enabled=false,"
                + "system_textclassifier_enabled=false,"
                + "model_dark_launch_enabled=false,"
                + "smart_selection_enabled=false,"
                + "smart_text_share_enabled=false,"
                + "smart_linkify_enabled=false,"
                + "smart_select_animation_enabled=false,"
                + "suggest_selection_max_range_length=8,"
                + "classify_text_max_range_length=7,"
                + "generate_links_max_text_length=6,"
                + "generate_links_log_sample_rate=5,"
                + "entity_list_default=email:url,"
                + "entity_list_not_editable=date,"
                + "entity_list_editable=flight,"
                + "in_app_conversation_action_types_default=view_map:track_flight,"
                + "notification_conversation_action_types_default=share_location,"
                + "lang_id_threshold_override=2";
        final TextClassificationConstants constants =
                TextClassificationConstants.loadFromString(s);

        assertWithMessage("local_textclassifier_enabled")
                .that(constants.isLocalTextClassifierEnabled()).isFalse();
        assertWithMessage("system_textclassifier_enabled")
                .that(constants.isSystemTextClassifierEnabled()).isFalse();
        assertWithMessage("model_dark_launch_enabled")
                .that(constants.isModelDarkLaunchEnabled()).isFalse();
        assertWithMessage("smart_selection_enabled")
                .that(constants.isSmartSelectionEnabled()).isFalse();
        assertWithMessage("smart_text_share_enabled")
                .that(constants.isSmartTextShareEnabled()).isFalse();
        assertWithMessage("smart_linkify_enabled")
                .that(constants.isSmartLinkifyEnabled()).isFalse();
        assertWithMessage("smart_select_animation_enabled")
                .that(constants.isSmartSelectionAnimationEnabled()).isFalse();
        assertWithMessage("suggest_selection_max_range_length")
                .that(constants.getSuggestSelectionMaxRangeLength()).isEqualTo(8);
        assertWithMessage("classify_text_max_range_length")
                .that(constants.getClassifyTextMaxRangeLength()).isEqualTo(7);
        assertWithMessage("generate_links_max_text_length")
                .that(constants.getGenerateLinksMaxTextLength()).isEqualTo(6);
        assertWithMessage("generate_links_log_sample_rate")
                .that(constants.getGenerateLinksLogSampleRate()).isEqualTo(5);
        assertWithMessage("entity_list_default")
                .that(constants.getEntityListDefault())
                .containsExactly("email", "url");
        assertWithMessage("entity_list_not_editable")
                .that(constants.getEntityListNotEditable())
                .containsExactly("date");
        assertWithMessage("entity_list_editable")
                .that(constants.getEntityListEditable())
                .containsExactly("flight");
        assertWithMessage("in_app_conversation_action_types_default")
                .that(constants.getInAppConversationActionTypes())
                .containsExactly("view_map", "track_flight");
        assertWithMessage("notification_conversation_action_types_default")
                .that(constants.getNotificationConversationActionTypes())
                .containsExactly("share_location");
        assertWithMessage("lang_id_threshold_override")
                .that(constants.getLangIdThresholdOverride()).isWithin(EPSILON).of(2f);
    }

    @Test
    public void testLoadFromString_defaultValues() {
        final TextClassificationConstants constants =
                TextClassificationConstants.loadFromString("");

        assertWithMessage("local_textclassifier_enabled")
                .that(constants.isLocalTextClassifierEnabled()).isTrue();
        assertWithMessage("system_textclassifier_enabled")
                .that(constants.isSystemTextClassifierEnabled()).isTrue();
        assertWithMessage("model_dark_launch_enabled")
                .that(constants.isModelDarkLaunchEnabled()).isFalse();
        assertWithMessage("smart_selection_enabled")
                .that(constants.isSmartSelectionEnabled()).isTrue();
        assertWithMessage("smart_text_share_enabled")
                .that(constants.isSmartTextShareEnabled()).isTrue();
        assertWithMessage("smart_linkify_enabled")
                .that(constants.isSmartLinkifyEnabled()).isTrue();
        assertWithMessage("smart_select_animation_enabled")
                .that(constants.isSmartSelectionAnimationEnabled()).isTrue();
        assertWithMessage("suggest_selection_max_range_length")
                .that(constants.getSuggestSelectionMaxRangeLength()).isEqualTo(10 * 1000);
        assertWithMessage("classify_text_max_range_length")
                .that(constants.getClassifyTextMaxRangeLength()).isEqualTo(10 * 1000);
        assertWithMessage("generate_links_max_text_length")
                .that(constants.getGenerateLinksMaxTextLength()).isEqualTo(100 * 1000);
        assertWithMessage("generate_links_log_sample_rate")
                .that(constants.getGenerateLinksLogSampleRate()).isEqualTo(100);
        assertWithMessage("entity_list_default")
                .that(constants.getEntityListDefault())
                .containsExactly("address", "email", "url", "phone", "date", "datetime", "flight");
        assertWithMessage("entity_list_not_editable")
                .that(constants.getEntityListNotEditable())
                .containsExactly("address", "email", "url", "phone", "date", "datetime", "flight");
        assertWithMessage("entity_list_editable")
                .that(constants.getEntityListEditable())
                .containsExactly("address", "email", "url", "phone", "date", "datetime", "flight");
        assertWithMessage("in_app_conversation_action_types_default")
                .that(constants.getInAppConversationActionTypes())
                .containsExactly("text_reply", "create_reminder", "call_phone", "open_url",
                        "send_email", "send_sms", "track_flight", "view_calendar", "view_map",
                        "add_contact", "copy");
        assertWithMessage("notification_conversation_action_types_default")
                .that(constants.getNotificationConversationActionTypes())
                .containsExactly("text_reply", "create_reminder", "call_phone", "open_url",
                        "send_email", "send_sms", "track_flight", "view_calendar", "view_map",
                        "add_contact", "copy");
        assertWithMessage("lang_id_threshold_override")
                .that(constants.getLangIdThresholdOverride()).isWithin(EPSILON).of(-1f);
    }
}
