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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationConstantsTest {

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
                + "generate_links_log_sample_rate=13";
        final TextClassificationConstants constants =
                TextClassificationConstants.loadFromString(s);
        assertTrue("local_textclassifier_enabled",
                constants.isLocalTextClassifierEnabled());
        assertTrue("system_textclassifier_enabled",
                constants.isSystemTextClassifierEnabled());
        assertTrue("model_dark_launch_enabled", constants.isModelDarkLaunchEnabled());
        assertTrue("smart_selection_enabled", constants.isSmartSelectionEnabled());
        assertTrue("smart_text_share_enabled", constants.isSmartTextShareEnabled());
        assertTrue("smart_linkify_enabled", constants.isSmartLinkifyEnabled());
        assertTrue("smart_select_animation_enabled", constants.isSmartSelectionAnimationEnabled());
        assertEquals("suggest_selection_max_range_length",
                10, constants.getSuggestSelectionMaxRangeLength());
        assertEquals("classify_text_max_range_length",
                11, constants.getClassifyTextMaxRangeLength());
        assertEquals("generate_links_max_text_length",
                12, constants.getGenerateLinksMaxTextLength());
        assertEquals("generate_links_log_sample_rate",
                13, constants.getGenerateLinksLogSampleRate());
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
                + "generate_links_log_sample_rate=5";
        final TextClassificationConstants constants =
                TextClassificationConstants.loadFromString(s);
        assertFalse("local_textclassifier_enabled",
                constants.isLocalTextClassifierEnabled());
        assertFalse("system_textclassifier_enabled",
                constants.isSystemTextClassifierEnabled());
        assertFalse("model_dark_launch_enabled", constants.isModelDarkLaunchEnabled());
        assertFalse("smart_selection_enabled", constants.isSmartSelectionEnabled());
        assertFalse("smart_text_share_enabled", constants.isSmartTextShareEnabled());
        assertFalse("smart_linkify_enabled", constants.isSmartLinkifyEnabled());
        assertFalse("smart_select_animation_enabled",
                constants.isSmartSelectionAnimationEnabled());
        assertEquals("suggest_selection_max_range_length",
                8, constants.getSuggestSelectionMaxRangeLength());
        assertEquals("classify_text_max_range_length",
                7, constants.getClassifyTextMaxRangeLength());
        assertEquals("generate_links_max_text_length",
                6, constants.getGenerateLinksMaxTextLength());
        assertEquals("generate_links_log_sample_rate",
                5, constants.getGenerateLinksLogSampleRate());
    }

    @Test
    public void testEntityListParsing() {
        final TextClassificationConstants constants = TextClassificationConstants.loadFromString(
                "entity_list_default=phone,"
                        + "entity_list_not_editable=address:flight,"
                        + "entity_list_editable=date:datetime");
        assertEquals(1, constants.getEntityListDefault().size());
        assertEquals("phone", constants.getEntityListDefault().get(0));
        assertEquals(2, constants.getEntityListNotEditable().size());
        assertEquals("address", constants.getEntityListNotEditable().get(0));
        assertEquals("flight", constants.getEntityListNotEditable().get(1));
        assertEquals(2, constants.getEntityListEditable().size());
        assertEquals("date", constants.getEntityListEditable().get(0));
        assertEquals("datetime", constants.getEntityListEditable().get(1));
    }
}
