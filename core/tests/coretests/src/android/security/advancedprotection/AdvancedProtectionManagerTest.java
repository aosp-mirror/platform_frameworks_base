/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.security.advancedprotection;

import static android.security.advancedprotection.AdvancedProtectionManager.ACTION_SHOW_ADVANCED_PROTECTION_SUPPORT_DIALOG;
import static android.security.advancedprotection.AdvancedProtectionManager.EXTRA_SUPPORT_DIALOG_FEATURE;
import static android.security.advancedprotection.AdvancedProtectionManager.EXTRA_SUPPORT_DIALOG_TYPE;
import static android.security.advancedprotection.AdvancedProtectionManager.FEATURE_ID_DISALLOW_CELLULAR_2G;
import static android.security.advancedprotection.AdvancedProtectionManager.SUPPORT_DIALOG_TYPE_BLOCKED_INTERACTION;
import static android.security.advancedprotection.AdvancedProtectionManager.SUPPORT_DIALOG_TYPE_DISABLED_SETTING;
import static android.security.advancedprotection.AdvancedProtectionManager.SUPPORT_DIALOG_TYPE_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AdvancedProtectionManagerTest {
    private static final int FEATURE_ID_INVALID = -1;
    private static final int SUPPORT_DIALOG_TYPE_INVALID = -1;

    @Test
    public void testCreateSupportIntent_validFeature_validTypeUnknown_createsIntent() {
        Intent intent = AdvancedProtectionManager.createSupportIntent(
                FEATURE_ID_DISALLOW_CELLULAR_2G, SUPPORT_DIALOG_TYPE_UNKNOWN);

        assertEquals(ACTION_SHOW_ADVANCED_PROTECTION_SUPPORT_DIALOG, intent.getAction());
        assertEquals(FEATURE_ID_DISALLOW_CELLULAR_2G, intent.getIntExtra(
                EXTRA_SUPPORT_DIALOG_FEATURE, FEATURE_ID_INVALID));
        assertEquals(SUPPORT_DIALOG_TYPE_UNKNOWN, intent.getIntExtra(EXTRA_SUPPORT_DIALOG_TYPE,
                SUPPORT_DIALOG_TYPE_INVALID));
    }

    @Test
    public void testCreateSupportIntent_validFeature_validTypeBlockedInteraction_createsIntent() {
        Intent intent = AdvancedProtectionManager.createSupportIntent(
                FEATURE_ID_DISALLOW_CELLULAR_2G, SUPPORT_DIALOG_TYPE_BLOCKED_INTERACTION);

        assertEquals(ACTION_SHOW_ADVANCED_PROTECTION_SUPPORT_DIALOG, intent.getAction());
        assertEquals(FEATURE_ID_DISALLOW_CELLULAR_2G, intent.getIntExtra(
                EXTRA_SUPPORT_DIALOG_FEATURE, FEATURE_ID_INVALID));
        assertEquals(SUPPORT_DIALOG_TYPE_BLOCKED_INTERACTION, intent.getIntExtra(
                EXTRA_SUPPORT_DIALOG_TYPE, SUPPORT_DIALOG_TYPE_INVALID));
    }

    @Test
    public void testCreateSupportIntent_validFeature_validTypeDisabledSetting_createsIntent() {
        Intent intent = AdvancedProtectionManager.createSupportIntent(
                FEATURE_ID_DISALLOW_CELLULAR_2G, SUPPORT_DIALOG_TYPE_DISABLED_SETTING);

        assertEquals(ACTION_SHOW_ADVANCED_PROTECTION_SUPPORT_DIALOG, intent.getAction());
        assertEquals(FEATURE_ID_DISALLOW_CELLULAR_2G, intent.getIntExtra(
                EXTRA_SUPPORT_DIALOG_FEATURE, FEATURE_ID_INVALID));
        assertEquals(SUPPORT_DIALOG_TYPE_DISABLED_SETTING, intent.getIntExtra(
                EXTRA_SUPPORT_DIALOG_TYPE, SUPPORT_DIALOG_TYPE_INVALID));
    }

    @Test
    public void testCreateSupportIntent_validFeature_invalidType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                AdvancedProtectionManager.createSupportIntent(FEATURE_ID_DISALLOW_CELLULAR_2G,
                        SUPPORT_DIALOG_TYPE_INVALID));
    }

    @Test
    public void testCreateSupportIntent_invalidFeature_validType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                AdvancedProtectionManager.createSupportIntent(FEATURE_ID_INVALID,
                        SUPPORT_DIALOG_TYPE_BLOCKED_INTERACTION));
    }

    @Test
    public void testCreateSupportIntent_invalidFeature_invalidType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                AdvancedProtectionManager.createSupportIntent(FEATURE_ID_INVALID,
                        SUPPORT_DIALOG_TYPE_INVALID));
    }
}
