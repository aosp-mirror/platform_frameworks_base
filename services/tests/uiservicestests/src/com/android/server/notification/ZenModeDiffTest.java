/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.Flags.FLAG_MODES_API;
import static android.app.Flags.FLAG_MODES_UI;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import android.app.AutomaticZenRule;
import android.app.Flags;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.net.Uri;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeDiff;
import android.service.notification.ZenModeDiff.RuleDiff;
import android.service.notification.ZenPolicy;
import android.testing.TestableLooper;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;

import com.android.server.UiServiceTestCase;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;


@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
@TestableLooper.RunWithLooper
public class ZenModeDiffTest extends UiServiceTestCase {
    // Base set of exempt fields independent of fields that are enabled/disabled via flags.
    // version is not included in the diff; manual & automatic rules have special handling;
    // deleted rules are not included in the diff.
    public static final Set<String> ZEN_MODE_CONFIG_EXEMPT_FIELDS =
            android.app.Flags.modesApi()
                    ? Set.of("version", "manualRule", "automaticRules", "deletedRules")
                    : Set.of("version", "manualRule", "automaticRules");

    // allowPriorityChannels is flagged by android.app.modes_api
    public static final Set<String> ZEN_MODE_CONFIG_FLAGGED_FIELDS =
            Set.of("allowPriorityChannels");

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.progressionOf(FLAG_MODES_API, FLAG_MODES_UI);
    }

    public ZenModeDiffTest(FlagsParameterization flags) {
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Test
    public void testRuleDiff_addRemoveSame() {
        // Test add, remove, and both sides same
        ZenModeConfig.ZenRule r = makeRule();

        // Both sides same rule
        ZenModeDiff.RuleDiff dSame = new ZenModeDiff.RuleDiff(r, r);
        assertFalse(dSame.hasDiff());

        // from existent rule to null: expect deleted
        ZenModeDiff.RuleDiff deleted = new ZenModeDiff.RuleDiff(r, null);
        assertTrue(deleted.hasDiff());
        assertTrue(deleted.wasRemoved());

        // from null to new rule: expect added
        ZenModeDiff.RuleDiff added = new ZenModeDiff.RuleDiff(null, r);
        assertTrue(added.hasDiff());
        assertTrue(added.wasAdded());
    }

    @Test
    public void testRuleDiff_fieldDiffs() throws Exception {
        // Start these the same
        ZenModeConfig.ZenRule r1 = makeRule();
        ZenModeConfig.ZenRule r2 = makeRule();

        // maps mapping field name -> expected output value as we set diffs
        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();
        List<Field> fieldsForDiff = getFieldsForDiffCheck(
                ZenModeConfig.ZenRule.class, getZenRuleExemptFields(), false);
        generateFieldDiffs(r1, r2, fieldsForDiff, expectedFrom, expectedTo);

        ZenModeDiff.RuleDiff d = new ZenModeDiff.RuleDiff(r1, r2);
        assertTrue(d.hasDiff());

        // Now diff them and check that each of the fields has a diff
        for (Field f : fieldsForDiff) {
            String name = f.getName();
            assertNotNull("diff not found for field: " + name, d.getDiffForField(name));
            assertTrue(d.getDiffForField(name).hasDiff());
            assertTrue("unexpected field: " + name, expectedFrom.containsKey(name));
            assertTrue("unexpected field: " + name, expectedTo.containsKey(name));
            assertEquals(expectedFrom.get(name), d.getDiffForField(name).from());
            assertEquals(expectedTo.get(name), d.getDiffForField(name).to());
        }
    }

    @Test
    @EnableFlags({FLAG_MODES_API, FLAG_MODES_UI})
    public void testRuleDiff_toStringNoChangeAddRemove() throws Exception {
        // Start with two identical rules
        ZenModeConfig.ZenRule r1 = makeRule();
        ZenModeConfig.ZenRule r2 = makeRule();

        ZenModeDiff.RuleDiff d = new ZenModeDiff.RuleDiff(r1, r2);
        assertThat(d.toString()).isEqualTo("ZenRuleDiff{no changes}");

        d = new ZenModeDiff.RuleDiff(r1, null);
        assertThat(d.toString()).isEqualTo("ZenRuleDiff{removed}");

        d = new ZenModeDiff.RuleDiff(null, r2);
        assertThat(d.toString()).isEqualTo("ZenRuleDiff{added}");
    }

    @Test
    @EnableFlags({FLAG_MODES_API, FLAG_MODES_UI})
    public void testRuleDiff_toString() throws Exception {
        // Start with two identical rules
        ZenModeConfig.ZenRule r1 = makeRule();
        ZenModeConfig.ZenRule r2 = makeRule();

        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();
        List<Field> fieldsForDiff = getFieldsForDiffCheck(
                ZenModeConfig.ZenRule.class, getZenRuleExemptFields(), false);
        generateFieldDiffs(r1, r2, fieldsForDiff, expectedFrom, expectedTo);

        ZenModeDiff.RuleDiff d = new ZenModeDiff.RuleDiff(r1, r2);
        assertThat(d.toString()).isEqualTo("ZenRuleDiff{"
                + "enabled:true->false, "
                + "conditionOverride:2->1, "
                + "name:string1->string2, "
                + "zenMode:2->1, "
                + "conditionId:null->, "
                + "condition:null->Condition["
                + "state=STATE_TRUE,"
                + "id=hello:,"
                + "summary=,"
                + "line1=,"
                + "line2=,"
                + "icon=-1,"
                + "source=SOURCE_UNKNOWN,"
                + "flags=2], "
                + "component:null->ComponentInfo{b/b}, "
                + "configurationActivity:null->ComponentInfo{a/a}, "
                + "id:string1->string2, "
                + "creationTime:200->100, "
                + "enabler:string1->string2, "
                + "zenPolicy:ZenPolicyDiff{"
                + "mPriorityCategories_Reminders:1->2, "
                + "mPriorityCategories_Events:1->2, "
                + "mPriorityCategories_Messages:1->2, "
                + "mPriorityCategories_Calls:1->2, "
                + "mPriorityCategories_RepeatCallers:1->2, "
                + "mPriorityCategories_Alarms:1->2, "
                + "mPriorityCategories_Media:1->2, "
                + "mPriorityCategories_System:1->2, "
                + "mPriorityCategories_Conversations:1->2, "
                + "mVisualEffects_FullScreenIntent:1->2, "
                + "mVisualEffects_Lights:1->2, "
                + "mVisualEffects_Peek:1->2, "
                + "mVisualEffects_StatusBar:1->2, "
                + "mVisualEffects_Badge:1->2, "
                + "mVisualEffects_Ambient:1->2, "
                + "mVisualEffects_NotificationList:1->2, "
                + "mPriorityMessages:2->1, "
                + "mPriorityCalls:2->1, "
                + "mConversationSenders:2->1, "
                + "mAllowChannels:2->1}, "
                + "modified:true->false, "
                + "pkg:string1->string2, "
                + "zenDeviceEffects:ZenDeviceEffectsDiff{"
                + "mGrayscale:true->false, "
                + "mSuppressAmbientDisplay:true->false, "
                + "mDimWallpaper:true->false, "
                + "mNightMode:true->false, "
                + "mDisableAutoBrightness:true->false, "
                + "mDisableTapToWake:true->false, "
                + "mDisableTiltToWake:true->false, "
                + "mDisableTouch:true->false, "
                + "mMinimizeRadioUsage:true->false, "
                + "mMaximizeDoze:true->false, "
                + "mExtraEffects:[effect1]->[effect2]}, "
                + "triggerDescription:string1->string2, "
                + "type:2->1, "
                + "allowManualInvocation:true->false, "
                + "iconResName:string1->string2, "
                + "legacySuppressedEffects:2->1}");
    }

    @Test
    @EnableFlags({FLAG_MODES_API, FLAG_MODES_UI})
    public void testRuleDiff_toStringNullStartPolicy() throws Exception {
        // Start with two identical rules
        ZenModeConfig.ZenRule r1 = makeRule();
        ZenModeConfig.ZenRule r2 = makeRule();

        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();
        List<Field> fieldsForDiff = getFieldsForDiffCheck(
                ZenModeConfig.ZenRule.class, getZenRuleExemptFields(), false);
        generateFieldDiffs(r1, r2, fieldsForDiff, expectedFrom, expectedTo);

        // Create a ZenRule with ZenDeviceEffects and ZenPolicy as null.
        r1.zenPolicy = null;
        r1.zenDeviceEffects = null;
        ZenModeDiff.RuleDiff d = new ZenModeDiff.RuleDiff(r1, r2);
        assertThat(d.toString()).isEqualTo("ZenRuleDiff{"
                + "enabled:true->false, "
                + "conditionOverride:2->1, "
                + "name:string1->string2, "
                + "zenMode:2->1, "
                + "conditionId:null->, "
                + "condition:null->Condition["
                + "state=STATE_TRUE,"
                + "id=hello:,"
                + "summary=,"
                + "line1=,"
                + "line2=,"
                + "icon=-1,"
                + "source=SOURCE_UNKNOWN,"
                + "flags=2], "
                + "component:null->ComponentInfo{b/b}, "
                + "configurationActivity:null->ComponentInfo{a/a}, "
                + "id:string1->string2, "
                + "creationTime:200->100, "
                + "enabler:string1->string2, "
                + "zenPolicy:ZenPolicyDiff{added}, "
                + "modified:true->false, "
                + "pkg:string1->string2, "
                + "zenDeviceEffects:ZenDeviceEffectsDiff{added}, "
                + "triggerDescription:string1->string2, "
                + "type:2->1, "
                + "allowManualInvocation:true->false, "
                + "iconResName:string1->string2, "
                + "legacySuppressedEffects:2->1}");
    }

    @Test
    public void testDeviceEffectsDiff_addRemoveSame() {
        // Test add, remove, and both sides same
        ZenDeviceEffects effects = new ZenDeviceEffects.Builder().build();

        // Both sides same rule
        ZenModeDiff.DeviceEffectsDiff dSame = new ZenModeDiff.DeviceEffectsDiff(effects, effects);
        assertFalse(dSame.hasDiff());

        // from existent rule to null: expect deleted
        ZenModeDiff.DeviceEffectsDiff deleted = new ZenModeDiff.DeviceEffectsDiff(effects, null);
        assertTrue(deleted.hasDiff());
        assertTrue(deleted.wasRemoved());

        // from null to new rule: expect added
        ZenModeDiff.DeviceEffectsDiff added = new ZenModeDiff.DeviceEffectsDiff(null, effects);
        assertTrue(added.hasDiff());
        assertTrue(added.wasAdded());
    }

    @Test
    public void testDeviceEffectsDiff_fieldDiffs() throws Exception {
        // Start these the same
        ZenDeviceEffects effects1 = new ZenDeviceEffects.Builder().build();
        ZenDeviceEffects effects2 = new ZenDeviceEffects.Builder().build();

        // maps mapping field name -> expected output value as we set diffs
        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();
        List<Field> fieldsForDiff = getFieldsForDiffCheck(
                ZenDeviceEffects.class, Collections.emptySet() /*no exempt fields*/, true);
        generateFieldDiffs(effects1, effects2, fieldsForDiff, expectedFrom, expectedTo);

        ZenModeDiff.DeviceEffectsDiff d = new ZenModeDiff.DeviceEffectsDiff(effects1, effects2);
        assertTrue(d.hasDiff());

        // Now diff them and check that each of the fields has a diff
        for (Field f : fieldsForDiff) {
            String name = f.getName();
            assertNotNull("diff not found for field: " + name, d.getDiffForField(name));
            assertTrue(d.getDiffForField(name).hasDiff());
            assertTrue("unexpected field: " + name, expectedFrom.containsKey(name));
            assertTrue("unexpected field: " + name, expectedTo.containsKey(name));
            assertEquals(expectedFrom.get(name), d.getDiffForField(name).from());
            assertEquals(expectedTo.get(name), d.getDiffForField(name).to());
        }
    }

    @Test
    public void testDeviceEffectsDiff_toString() throws Exception {
        // Ensure device effects toString is readable.
        ZenDeviceEffects effects1 = new ZenDeviceEffects.Builder().build();
        ZenDeviceEffects effects2 = new ZenDeviceEffects.Builder().build();

        ZenModeDiff.DeviceEffectsDiff d = new ZenModeDiff.DeviceEffectsDiff(effects1, effects2);
        assertThat(d.toString()).isEqualTo("ZenDeviceEffectsDiff{no changes}");

        d = new ZenModeDiff.DeviceEffectsDiff(effects1, null);
        assertThat(d.toString()).isEqualTo("ZenDeviceEffectsDiff{removed}");

        d = new ZenModeDiff.DeviceEffectsDiff(null, effects2);
        assertThat(d.toString()).isEqualTo("ZenDeviceEffectsDiff{added}");

        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();
        List<Field> fieldsForDiff = getFieldsForDiffCheck(
                ZenDeviceEffects.class, Collections.emptySet() /*no exempt fields*/, true);
        generateFieldDiffs(effects1, effects2, fieldsForDiff, expectedFrom, expectedTo);

        d = new ZenModeDiff.DeviceEffectsDiff(effects1, effects2);
        assertThat(d.toString()).isEqualTo("ZenDeviceEffectsDiff{"
                + "mGrayscale:true->false, "
                + "mSuppressAmbientDisplay:true->false, "
                + "mDimWallpaper:true->false, "
                + "mNightMode:true->false, "
                + "mDisableAutoBrightness:true->false, "
                + "mDisableTapToWake:true->false, "
                + "mDisableTiltToWake:true->false, "
                + "mDisableTouch:true->false, "
                + "mMinimizeRadioUsage:true->false, "
                + "mMaximizeDoze:true->false, "
                + "mExtraEffects:[effect1]->[effect2]}");
    }


    @Test
    public void testPolicyDiff_addRemoveSame() {
        // Test add, remove, and both sides same
        ZenPolicy effects = new ZenPolicy.Builder().build();

        // Both sides same rule
        ZenModeDiff.PolicyDiff dSame = new ZenModeDiff.PolicyDiff(effects, effects);
        assertFalse(dSame.hasDiff());

        // from existent rule to null: expect deleted
        ZenModeDiff.PolicyDiff deleted = new ZenModeDiff.PolicyDiff(effects, null);
        assertTrue(deleted.hasDiff());
        assertTrue(deleted.wasRemoved());

        // from null to new rule: expect added
        ZenModeDiff.PolicyDiff added = new ZenModeDiff.PolicyDiff(null, effects);
        assertTrue(added.hasDiff());
        assertTrue(added.wasAdded());
    }

    @Test
    public void testPolicyDiff_fieldDiffs() throws Exception {
        // Start these the same
        ZenPolicy policy1 = new ZenPolicy.Builder().build();
        ZenPolicy policy2 = new ZenPolicy.Builder().build();

        // maps mapping field name -> expected output value as we set diffs
        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();

        List<Field> fieldsForDiff = getFieldsForDiffCheck(ZenPolicy.class, Collections.emptySet(),
                false);
        generateFieldDiffsForZenPolicy(policy1, policy2, fieldsForDiff, expectedFrom, expectedTo);

        ZenModeDiff.PolicyDiff d = new ZenModeDiff.PolicyDiff(policy1, policy2);
        assertTrue(d.hasDiff());

        // Now diff them and check that each of the fields has a diff.
        // Because ZenPolicy consolidates priority category and visual effect fields in a list,
        // we cannot use reflection on ZenPolicy to get the list of fields.
        ArrayList<String> diffFields = new ArrayList<>();
        Field[] fields = ZenModeDiff.PolicyDiff.class.getDeclaredFields();

        for (Field field : fields) {
            int m = field.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m)) {
                diffFields.add((String) field.get(policy1));
            }
        }

        for (String name : diffFields) {
            assertNotNull("diff not found for field: " + name, d.getDiffForField(name));
            assertTrue(d.getDiffForField(name).hasDiff());
            assertTrue("unexpected field: " + name, expectedFrom.containsKey(name));
            assertTrue("unexpected field: " + name, expectedTo.containsKey(name));
            assertEquals(expectedFrom.get(name), d.getDiffForField(name).from());
            assertEquals(expectedTo.get(name), d.getDiffForField(name).to());
        }
    }

    @Test
    public void testPolicyDiff_toString() throws Exception {
        // Ensure device effects toString is readable.
        ZenPolicy policy1 = new ZenPolicy.Builder().build();
        ZenPolicy policy2 = new ZenPolicy.Builder().build();

        ZenModeDiff.PolicyDiff d = new ZenModeDiff.PolicyDiff(policy1, policy2);
        assertThat(d.toString()).isEqualTo("ZenPolicyDiff{no changes}");

        d = new ZenModeDiff.PolicyDiff(policy1, null);
        assertThat(d.toString()).isEqualTo("ZenPolicyDiff{removed}");

        d = new ZenModeDiff.PolicyDiff(null, policy2);
        assertThat(d.toString()).isEqualTo("ZenPolicyDiff{added}");

        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();
        List<Field> fieldsForDiff = getFieldsForDiffCheck(
                ZenPolicy.class, Collections.emptySet() /*no exempt fields*/, false);
        generateFieldDiffsForZenPolicy(policy1, policy2, fieldsForDiff, expectedFrom, expectedTo);

        d = new ZenModeDiff.PolicyDiff(policy1, policy2);
        assertThat(d.toString()).isEqualTo("ZenPolicyDiff{"
                + "mPriorityCategories_Reminders:1->2, "
                + "mPriorityCategories_Events:1->2, "
                + "mPriorityCategories_Messages:1->2, "
                + "mPriorityCategories_Calls:1->2, "
                + "mPriorityCategories_RepeatCallers:1->2, "
                + "mPriorityCategories_Alarms:1->2, "
                + "mPriorityCategories_Media:1->2, "
                + "mPriorityCategories_System:1->2, "
                + "mPriorityCategories_Conversations:1->2, "
                + "mVisualEffects_FullScreenIntent:1->2, "
                + "mVisualEffects_Lights:1->2, "
                + "mVisualEffects_Peek:1->2, "
                + "mVisualEffects_StatusBar:1->2, "
                + "mVisualEffects_Badge:1->2, "
                + "mVisualEffects_Ambient:1->2, "
                + "mVisualEffects_NotificationList:1->2, "
                + "mPriorityMessages:2->1, "
                + "mPriorityCalls:2->1, "
                + "mConversationSenders:2->1, "
                + "mAllowChannels:2->1}");
    }

    private static Set<String> getZenRuleExemptFields() {
        // "Metadata" fields are never compared.
        Set<String> exemptFields = new LinkedHashSet<>(
                Set.of("userModifiedFields", "zenPolicyUserModifiedFields",
                        "zenDeviceEffectsUserModifiedFields", "deletionInstant", "disabledOrigin"));
        // Flagged fields are only compared if their flag is on.
        if (!Flags.modesApi()) {
            exemptFields.addAll(
                    Set.of(RuleDiff.FIELD_TYPE, RuleDiff.FIELD_TRIGGER_DESCRIPTION,
                            RuleDiff.FIELD_ICON_RES, RuleDiff.FIELD_ALLOW_MANUAL,
                            RuleDiff.FIELD_ZEN_DEVICE_EFFECTS,
                            RuleDiff.FIELD_LEGACY_SUPPRESSED_EFFECTS));
        }
        if (Flags.modesApi() && Flags.modesUi()) {
            exemptFields.add(RuleDiff.FIELD_SNOOZING); // Obsolete.
        } else {
            exemptFields.add(RuleDiff.FIELD_CONDITION_OVERRIDE);
            exemptFields.add(RuleDiff.FIELD_LEGACY_SUPPRESSED_EFFECTS);
        }
        return exemptFields;
    }

    @Test
    public void testConfigDiff_addRemoveSame() {
        // Default config, will test add, remove, and no change
        ZenModeConfig c = new ZenModeConfig();

        ZenModeDiff.ConfigDiff dSame = new ZenModeDiff.ConfigDiff(c, c);
        assertFalse(dSame.hasDiff());

        ZenModeDiff.ConfigDiff added = new ZenModeDiff.ConfigDiff(null, c);
        assertTrue(added.hasDiff());
        assertTrue(added.wasAdded());

        ZenModeDiff.ConfigDiff removed = new ZenModeDiff.ConfigDiff(c, null);
        assertTrue(removed.hasDiff());
        assertTrue(removed.wasRemoved());
    }

    @Test
    public void testConfigDiff_fieldDiffs() throws Exception {
        // these two start the same
        ZenModeConfig c1 = new ZenModeConfig();
        ZenModeConfig c2 = new ZenModeConfig();

        // maps mapping field name -> expected output value as we set diffs
        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();
        List<Field> fieldsForDiff = getFieldsForDiffCheck(
                ZenModeConfig.class, getConfigExemptAndFlaggedFields(), false);
        generateFieldDiffs(c1, c2, fieldsForDiff, expectedFrom, expectedTo);

        ZenModeDiff.ConfigDiff d = new ZenModeDiff.ConfigDiff(c1, c2);
        assertTrue(d.hasDiff());

        // Now diff them and check that each of the fields has a diff
        for (Field f : fieldsForDiff) {
            String name = f.getName();
            assertNotNull("diff not found for field: " + name, d.getDiffForField(name));
            assertTrue(d.getDiffForField(name).hasDiff());
            assertTrue("unexpected field: " + name, expectedFrom.containsKey(name));
            assertTrue("unexpected field: " + name, expectedTo.containsKey(name));
            assertEquals(expectedFrom.get(name), d.getDiffForField(name).from());
            assertEquals(expectedTo.get(name), d.getDiffForField(name).to());
        }
    }

    @Test
    @EnableFlags(FLAG_MODES_API)
    public void testConfigDiff_fieldDiffs_flagOn() throws Exception {
        // these two start the same
        ZenModeConfig c1 = new ZenModeConfig();
        ZenModeConfig c2 = new ZenModeConfig();

        // maps mapping field name -> expected output value as we set diffs
        ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
        ArrayMap<String, Object> expectedTo = new ArrayMap<>();
        List<Field> fieldsForDiff = getFieldsForDiffCheck(
                ZenModeConfig.class, ZEN_MODE_CONFIG_EXEMPT_FIELDS, false);
        generateFieldDiffs(c1, c2, fieldsForDiff, expectedFrom, expectedTo);

        ZenModeDiff.ConfigDiff d = new ZenModeDiff.ConfigDiff(c1, c2);
        assertTrue(d.hasDiff());

        // Now diff them and check that each of the fields has a diff
        for (Field f : fieldsForDiff) {
            String name = f.getName();
            assertNotNull("diff not found for field: " + name, d.getDiffForField(name));
            assertTrue(d.getDiffForField(name).hasDiff());
            assertTrue("unexpected field: " + name, expectedFrom.containsKey(name));
            assertTrue("unexpected field: " + name, expectedTo.containsKey(name));
            assertEquals(expectedFrom.get(name), d.getDiffForField(name).from());
            assertEquals(expectedTo.get(name), d.getDiffForField(name).to());
        }
    }

    @Test
    public void testConfigDiff_specialSenders() {
        // these two start the same
        ZenModeConfig c1 = new ZenModeConfig();
        ZenModeConfig c2 = new ZenModeConfig();

        // set c1 and c2 to have some different senders
        NotificationManager.Policy c1Policy = c1.toNotificationPolicy();
        c1.applyNotificationPolicy(new NotificationManager.Policy(
                c1Policy.priorityCategories, c1Policy.priorityCallSenders,
                c1Policy.PRIORITY_SENDERS_STARRED, c1Policy.suppressedVisualEffects,
                c1Policy.state, ZenPolicy.CONVERSATION_SENDERS_IMPORTANT));

        NotificationManager.Policy c2Policy = c1.toNotificationPolicy();
        c2.applyNotificationPolicy(new NotificationManager.Policy(
                c2Policy.priorityCategories, c2Policy.priorityCallSenders,
                c2Policy.PRIORITY_SENDERS_CONTACTS, c2Policy.suppressedVisualEffects,
                c2Policy.state, ZenPolicy.CONVERSATION_SENDERS_NONE));

        ZenModeDiff.ConfigDiff d = new ZenModeDiff.ConfigDiff(c1, c2);
        assertTrue(d.hasDiff());

        if (!Flags.modesUi()) {
            // Diff in top-level fields
            assertTrue(d.getDiffForField("allowMessagesFrom").hasDiff());
            assertTrue(d.getDiffForField("allowConversationsFrom").hasDiff());

            // Bonus testing of stringification of people senders and conversation senders
            assertTrue(d.toString().contains("allowMessagesFrom:stars->contacts"));
            assertTrue(d.toString().contains("allowConversationsFrom:important->none"));
        } else {
            RuleDiff r = d.getManualRuleDiff();
            assertNotNull(r);
            ZenModeDiff.FieldDiff p = r.getDiffForField(RuleDiff.FIELD_ZEN_POLICY);
            assertNotNull(p);
        }
    }

    @Test
    public void testConfigDiff_hasRuleDiffs() {
        // two default configs
        ZenModeConfig c1 = new ZenModeConfig();
        ZenModeConfig c2 = new ZenModeConfig();

        // two initially-identical rules
        ZenModeConfig.ZenRule r1 = makeRule();
        ZenModeConfig.ZenRule r2 = makeRule();

        // one that will become a manual rule
        ZenModeConfig.ZenRule m = makeRule();

        // Add r1 to c1, but not r2 to c2 yet -- expect a rule to be deleted
        c1.automaticRules.put(r1.id, r1);
        ZenModeDiff.ConfigDiff deleteRule = new ZenModeDiff.ConfigDiff(c1, c2);
        assertTrue(deleteRule.hasDiff());
        assertNotNull(deleteRule.getAllAutomaticRuleDiffs());
        assertTrue(deleteRule.getAllAutomaticRuleDiffs().containsKey("ruleId"));
        assertTrue(deleteRule.getAllAutomaticRuleDiffs().get("ruleId").wasRemoved());

        // Change r2 a little, add r2 to c2 as an automatic rule and m as a manual rule
        r2.component = null;
        r2.pkg = "different";
        c2.manualRule = m;
        c2.automaticRules.put(r2.id, r2);

        // Expect diffs in both manual rule (added) and automatic rule (changed)
        ZenModeDiff.ConfigDiff changed = new ZenModeDiff.ConfigDiff(c1, c2);
        assertTrue(changed.hasDiff());
        assertTrue(changed.getManualRuleDiff().hasDiff());

        ArrayMap<String, ZenModeDiff.RuleDiff> automaticDiffs = changed.getAllAutomaticRuleDiffs();
        assertNotNull(automaticDiffs);
        assertTrue(automaticDiffs.containsKey("ruleId"));
        assertNotNull(automaticDiffs.get("ruleId").getDiffForField("component"));
        assertNull(automaticDiffs.get("ruleId").getDiffForField("component").to());
        assertNotNull(automaticDiffs.get("ruleId").getDiffForField("pkg"));
        assertEquals("different", automaticDiffs.get("ruleId").getDiffForField("pkg").to());
    }

    // Helper method that merges the base exempt fields with fields that are flagged
    private Set getConfigExemptAndFlaggedFields() {
        Set merged = new HashSet();
        merged.addAll(ZEN_MODE_CONFIG_EXEMPT_FIELDS);
        merged.addAll(ZEN_MODE_CONFIG_FLAGGED_FIELDS);
        return merged;
    }

    // Helper methods for working with configs, policies, rules
    // Just makes a zen rule with fields filled in
    private ZenModeConfig.ZenRule makeRule() {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = new ComponentName("a", "a");
        rule.component = new ComponentName("b", "b");
        rule.conditionId = new Uri.Builder().scheme("hello").build();
        rule.condition = new Condition(rule.conditionId, "", Condition.STATE_TRUE);
        rule.enabled = true;
        rule.creationTime = 123;
        rule.id = "ruleId";
        rule.zenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        rule.modified = false;
        rule.name = "name";
        rule.setConditionOverride(ZenModeConfig.ZenRule.OVERRIDE_DEACTIVATE);
        rule.pkg = "a";
        if (android.app.Flags.modesApi()) {
            rule.allowManualInvocation = true;
            rule.type = AutomaticZenRule.TYPE_SCHEDULE_TIME;
            rule.iconResName = "res";
            rule.triggerDescription = "At night";
            rule.zenDeviceEffects = new ZenDeviceEffects.Builder()
                    .setShouldDimWallpaper(true)
                    .build();
            rule.userModifiedFields = AutomaticZenRule.FIELD_NAME;
        }
        return rule;
    }

    // Get the fields on which we would want to check a diff. The requirements are: not final or/
    // static (as these should/can never change), and not in a specific list that's exempted.
    private List<Field> getFieldsForDiffCheck(Class<?> c, Set<String> exemptNames,
                                              boolean includeFinal)
            throws SecurityException {
        Field[] fields = c.getDeclaredFields();
        ArrayList<Field> out = new ArrayList<>();

        for (Field field : fields) {
            // Check for exempt reasons
            // Anything in provided exemptNames is skipped.
            if (exemptNames.contains(field.getName())) {
                continue;
            }
            int m = field.getModifiers();
            if (Modifier.isStatic(m)) {
                continue;
            }
            if (!includeFinal && Modifier.isFinal(m)) {
                continue;
            }
            out.add(field);
        }
        return out;
    }

    // Generate a set of diffs for two ZenPolicy objects. Store the results in the provided
    // expectation maps.
    private void generateFieldDiffsForZenPolicy(ZenPolicy a, ZenPolicy b, List<Field> fields,
            ArrayMap<String, Object> expectedA, ArrayMap<String, Object> expectedB)
            throws Exception {
        // Loop through fields for which we want to check diffs, set a diff and keep track of
        // what we set.
        for (Field f : fields) {
            f.setAccessible(true);
            // Just double-check also that the fields actually are for the class declared
            assertEquals(f.getDeclaringClass(), a.getClass());
            Class<?> t = f.getType();

            if (int.class.equals(t)) {
                // these will not be valid for arbitrary int enums, but should suffice for a diff.
                f.setInt(a, 2);
                expectedA.put(f.getName(), 2);
                f.setInt(b, 1);
                expectedB.put(f.getName(), 1);
            } else if (List.class.equals(t)) {
                // Fieds mPriorityCategories and mVisualEffects store multiple values and
                // must be treated separately.
                List<Integer> aList = (ArrayList<Integer>) f.get(a);
                List<Integer> bList = (ArrayList<Integer>) f.get(b);
                if (f.getName().equals("mPriorityCategories")) {
                    // PRIORITY_CATEGORY_REMINDERS
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 0,
                            "mPriorityCategories_Reminders");
                    // PRIORITY_CATEGORY_EVENTS
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 1,
                            "mPriorityCategories_Events");
                    // PRIORITY_CATEGORY_MESSAGES
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 2,
                            "mPriorityCategories_Messages");
                    // PRIORITY_CATEGORY_CALLS
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 3,
                            "mPriorityCategories_Calls");
                    // PRIORITY_CATEGORY_REPEAT_CALLERS
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 4,
                            "mPriorityCategories_RepeatCallers");
                    // PRIORITY_CATEGORY_ALARMS
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 5,
                            "mPriorityCategories_Alarms");
                    // PRIORITY_CATEGORY_MEDIA
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 6,
                            "mPriorityCategories_Media");
                    // PRIORITY_CATEGORY_SYSTEM
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 7,
                            "mPriorityCategories_System");
                    // PRIORITY_CATEGORY_CONVERSATIONS
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 8,
                            "mPriorityCategories_Conversations");
                    // Assert that we've set every PriorityCategory enum value.
                    assertThat(Collections.frequency(aList, ZenPolicy.STATE_ALLOW))
                            .isEqualTo(ZenPolicy.NUM_PRIORITY_CATEGORIES);
                } else if (f.getName().equals("mVisualEffects")) {
                    // VISUAL_EFFECT_FULL_SCREEN_INTENT
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 0,
                            "mVisualEffects_FullScreenIntent");
                    // VISUAL_EFFECT_LIGHTS
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 1,
                            "mVisualEffects_Lights");
                    // VISUAL_EFFECT_PEEK
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 2,
                            "mVisualEffects_Peek");
                    // VISUAL_EFFECT_STATUS_BAR
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 3,
                            "mVisualEffects_StatusBar");
                    // VISUAL_EFFECT_BADGE
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 4,
                            "mVisualEffects_Badge");
                    // VISUAL_EFFECT_AMBIENT
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 5,
                            "mVisualEffects_Ambient");
                    // VISUAL_EFFECT_NOTIFICATION_LIST
                    setPolicyListValueDiff(aList, bList, expectedA, expectedB, 6,
                            "mVisualEffects_NotificationList");
                    // Assert that we've set every VisualeEffect enum value.
                    assertThat(Collections.frequency(aList, ZenPolicy.STATE_ALLOW))
                            .isEqualTo(ZenPolicy.NUM_VISUAL_EFFECTS);
                } else {
                    // Any other lists that are added should be added to the diff.
                    fail("could not generate field diffs for policy list: " + f.getName());
                }
            }
        }
    }

    // Helper function to create a diff in two list values at a given index, and record that
    // diff's values in the associated expected maps under the provided field name.
    private void setPolicyListValueDiff(List<Integer> aList, List<Integer> bList,
                                        ArrayMap<String, Object> expectedA,
                                        ArrayMap<String, Object> expectedB,
                                        int index, String fieldName) {
        aList.set(index, ZenPolicy.STATE_ALLOW);
        expectedA.put(fieldName, ZenPolicy.STATE_ALLOW);
        bList.set(index, ZenPolicy.STATE_DISALLOW);
        expectedB.put(fieldName, ZenPolicy.STATE_DISALLOW);
    }

    // Generate a set of generic diffs for the specified two objects and the fields to generate
    // diffs for, and store the results in the provided expectation maps to be able to check the
    // output later.
    private void generateFieldDiffs(Object a, Object b, List<Field> fields,
            ArrayMap<String, Object> expectedA, ArrayMap<String, Object> expectedB)
            throws Exception {
        // different classes passed in means bad input
        assertEquals(a.getClass(), b.getClass());

        // Loop through fields for which we want to check diffs, set a diff and keep track of
        // what we set.
        for (Field f : fields) {
            f.setAccessible(true);
            // Just double-check also that the fields actually are for the class declared
            assertEquals(f.getDeclaringClass(), a.getClass());
            Class<?> t = f.getType();
            // handle the full set of primitive types first
            if (boolean.class.equals(t)) {
                f.setBoolean(a, true);
                expectedA.put(f.getName(), true);
                f.setBoolean(b, false);
                expectedB.put(f.getName(), false);
            } else if (int.class.equals(t)) {
                // these are not actually valid going to be valid for arbitrary int enum fields, but
                // we just put something in there regardless.
                f.setInt(a, 2);
                expectedA.put(f.getName(), 2);
                f.setInt(b, 1);
                expectedB.put(f.getName(), 1);
            } else if (long.class.equals(t)) {
                f.setLong(a, 200L);
                expectedA.put(f.getName(), 200L);
                f.setLong(b, 100L);
                expectedB.put(f.getName(), 100L);
            } else if (t.isPrimitive()) {
                // This method doesn't yet handle other primitive types. If the relevant diff
                // classes gain new fields of these types, please add another clause here.
                fail("primitive type not handled by generateFieldDiffs: " + t.getName());
            } else if (String.class.equals(t)) {
                f.set(a, "string1");
                expectedA.put(f.getName(), "string1");
                f.set(b, "string2");
                expectedB.put(f.getName(), "string2");
            } else if (Set.class.equals(t)) {
                Set<String> aSet = Set.of("effect1");
                Set<String> bSet = Set.of("effect2");
                f.set(a, aSet);
                expectedA.put(f.getName(), aSet);
                f.set(b, bSet);
                expectedB.put(f.getName(), bSet);
            } else if (ZenDeviceEffects.class.equals(t)) {
                // Recurse into generating field diffs for ZenDeviceEffects.
                ZenDeviceEffects effects1 = new ZenDeviceEffects.Builder().build();
                ZenDeviceEffects effects2 = new ZenDeviceEffects.Builder().build();
                // maps mapping field name -> expected output value as we set diffs
                ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
                ArrayMap<String, Object> expectedTo = new ArrayMap<>();

                List<Field> fieldsForDiff = getFieldsForDiffCheck(
                        ZenDeviceEffects.class, Collections.emptySet() /*no exempt fields*/, true);
                generateFieldDiffs(effects1, effects2, fieldsForDiff, expectedFrom, expectedTo);
                f.set(a, effects1);
                expectedA.put(f.getName(), effects1);
                f.set(b, effects2);
                expectedB.put(f.getName(), effects2);
            } else if (ZenPolicy.class.equals(t)) {
                // Recurse into generating field diffs for ZenPolicy.
                ZenPolicy policy1 = new ZenPolicy.Builder().build();
                ZenPolicy policy2 = new ZenPolicy.Builder().build();
                // maps mapping field name -> expected output value as we set diffs
                ArrayMap<String, Object> expectedFrom = new ArrayMap<>();
                ArrayMap<String, Object> expectedTo = new ArrayMap<>();

                List<Field> fieldsForDiff = getFieldsForDiffCheck(ZenPolicy.class,
                        Collections.emptySet(), false);
                generateFieldDiffsForZenPolicy(policy1, policy2, fieldsForDiff, expectedFrom,
                        expectedTo);
                f.set(a, policy1);
                expectedA.put(f.getName(), policy1);
                f.set(b, policy2);
                expectedB.put(f.getName(), policy2);
            } else {
                // catch-all for other types: have the field be "added"
                f.set(a, null);
                expectedA.put(f.getName(), null);
                try {
                    f.set(b, newInstanceOf(t));
                    expectedB.put(f.getName(), newInstanceOf(t));
                } catch (Exception e) {
                    // No default constructor, or blithely attempting to construct something doesn't
                    // work for some reason. If the default value isn't null, then keep it.
                    if (f.get(b) != null) {
                        expectedB.put(f.getName(), f.get(b));
                    } else {
                        // If we can't even rely on that, fail. Have the test-writer special case
                        // something, as this is not able to be genericized.
                        fail("could not generically construct value for field: " + f.getName());
                    }
                }
            }
        }
    }

    private static Object newInstanceOf(Class<?> clazz) throws ReflectiveOperationException {
        try {
            Constructor<?> defaultConstructor = clazz.getDeclaredConstructor();
            return defaultConstructor.newInstance();
        } catch (Exception e) {
            // No default constructor, continue below.
        }

        // Look for a suitable builder.
        Optional<Class<?>> clazzBuilder =
                Arrays.stream(clazz.getDeclaredClasses())
                        .filter(maybeBuilder -> maybeBuilder.getSimpleName().equals("Builder"))
                        .filter(maybeBuilder ->
                                Arrays.stream(maybeBuilder.getMethods()).anyMatch(
                                        m -> m.getName().equals("build")
                                                && m.getParameterCount() == 0
                                                && m.getReturnType().equals(clazz)))
                        .findFirst();
        if (clazzBuilder.isPresent()) {
            Object builder = newInstanceOf(clazzBuilder.get());
            Method buildMethod = builder.getClass().getMethod("build");
            Object built = buildMethod.invoke(builder);
            assertThat(built).isInstanceOf(clazz);
            return built;
        }

        throw new ReflectiveOperationException(
                "Sorry! Couldn't figure out how to create an instance of " + clazz.getName());
    }
}
