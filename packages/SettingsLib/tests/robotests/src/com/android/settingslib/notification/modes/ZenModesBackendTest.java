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

package com.android.settingslib.notification.modes;

import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.service.notification.Condition.SOURCE_UNKNOWN;
import static android.service.notification.Condition.STATE_FALSE;
import static android.service.notification.Condition.STATE_TRUE;
import static android.service.notification.ZenPolicy.STATE_ALLOW;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AutomaticZenRule;
import android.app.Flags;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.content.Context;
import android.net.Uri;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenAdapters;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;

import com.android.settingslib.R;

import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowApplication;

import java.time.Duration;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@EnableFlags(Flags.FLAG_MODES_UI)
public class ZenModesBackendTest {

    private static final String ZEN_RULE_ID = "rule";
    private static final AutomaticZenRule ZEN_RULE =
            new AutomaticZenRule.Builder("Driving", Uri.parse("drive"))
                    .setType(AutomaticZenRule.TYPE_DRIVING)
                    .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                    .setZenPolicy(new ZenPolicy.Builder().allowAllSounds().build())
                    .build();

    private static final AutomaticZenRule MANUAL_DND_RULE =
            new AutomaticZenRule.Builder("Do Not Disturb", Uri.EMPTY)
                    .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                    .setZenPolicy(new ZenPolicy.Builder().allowAllSounds().build())
                    .build();

    @Mock
    private NotificationManager mNm;

    private Context mContext;
    private ZenModesBackend mBackend;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(
            SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT);

    // Helper methods to add active/inactive rule state to a config. Returns a copy.
    private ZenModeConfig configWithManualRule(ZenModeConfig base, boolean active) {
        ZenModeConfig out = base.copy();

        if (active) {
            out.manualRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
            out.manualRule.condition =
                    new Condition(out.manualRule.conditionId, "", STATE_TRUE, SOURCE_UNKNOWN);
        } else {
            out.manualRule.zenMode = ZEN_MODE_OFF;
            out.manualRule.condition =
                    new Condition(out.manualRule.conditionId, "", STATE_FALSE, SOURCE_UNKNOWN);
        }
        return out;
    }

    private ZenModeConfig configWithRule(ZenModeConfig base, String ruleId, AutomaticZenRule rule,
            boolean active) {
        ZenModeConfig out = base.copy();

        // Note that there are many other fields of zenRule, but here we only set the ones
        // relevant to determining whether or not it is active.
        ZenModeConfig.ZenRule zenRule = new ZenModeConfig.ZenRule();
        zenRule.pkg = "package";
        zenRule.enabled = active;
        zenRule.snoozing = false;
        zenRule.condition = new Condition(rule.getConditionId(), "",
                active ? Condition.STATE_TRUE : Condition.STATE_FALSE,
                Condition.SOURCE_USER_ACTION);
        out.automaticRules.put(ruleId, zenRule);

        return out;
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        ShadowApplication shadowApplication = ShadowApplication.getInstance();
        shadowApplication.setSystemService(Context.NOTIFICATION_SERVICE, mNm);

        mContext = RuntimeEnvironment.application;
        mBackend = new ZenModesBackend(mContext);

        // Default catch-all case with no data. This isn't realistic, but tests below that rely
        // on the config to get data on rules active will create those individually.
        when(mNm.getZenModeConfig()).thenReturn(new ZenModeConfig());
    }

    @Test
    public void getModes_containsManualDndAndZenRules() {
        AutomaticZenRule rule2 = new AutomaticZenRule.Builder("Bedtime", Uri.parse("bed"))
                .setType(AutomaticZenRule.TYPE_BEDTIME)
                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(new ZenPolicy.Builder().disallowAllSounds().build())
                .build();
        Policy dndPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS,
                Policy.PRIORITY_SENDERS_CONTACTS, Policy.PRIORITY_SENDERS_CONTACTS);
        when(mNm.getAutomaticZenRules()).thenReturn(
                ImmutableMap.of("rule1", ZEN_RULE, "rule2", rule2));
        ZenModeConfig config = new ZenModeConfig();
        config.applyNotificationPolicy(dndPolicy);
        assertThat(config.manualRule.zenPolicy.getPriorityCategoryAlarms()).isEqualTo(STATE_ALLOW);
        when(mNm.getZenModeConfig()).thenReturn(config);

        List<ZenMode> modes = mBackend.getModes();

        // all modes exist, but none of them are currently active
        ZenPolicy zenPolicy = ZenAdapters.notificationPolicyToZenPolicy(dndPolicy);
        assertThat(modes).containsExactly(
                        ZenMode.manualDndMode(
                                new AutomaticZenRule.Builder(
                                        mContext.getString(R.string.zen_mode_settings_title),
                                        Uri.EMPTY)
                                        .setType(AutomaticZenRule.TYPE_OTHER)
                                        .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                                        .setZenPolicy(zenPolicy)
                                        .setManualInvocationAllowed(true)
                                        .build(),
                                false),
                        new ZenMode("rule2", rule2, false),
                        new ZenMode("rule1", ZEN_RULE, false))
                .inOrder();
    }

    @Test
    public void getMode_manualDnd_returnsMode() {
        Policy dndPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS,
                Policy.PRIORITY_SENDERS_CONTACTS, Policy.PRIORITY_SENDERS_CONTACTS);
        ZenModeConfig config = new ZenModeConfig();
        config.applyNotificationPolicy(dndPolicy);
        when(mNm.getZenModeConfig()).thenReturn(config);

        ZenMode mode = mBackend.getMode(ZenMode.MANUAL_DND_MODE_ID);

        assertThat(mode).isEqualTo(
                ZenMode.manualDndMode(
                        new AutomaticZenRule.Builder(
                                mContext.getString(R.string.zen_mode_settings_title), Uri.EMPTY)
                                .setType(AutomaticZenRule.TYPE_OTHER)
                                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                                .setZenPolicy(ZenAdapters.notificationPolicyToZenPolicy(dndPolicy))
                                .setManualInvocationAllowed(true)
                                .build(), false));
    }

    @Test
    public void getMode_zenRule_returnsMode() {
        when(mNm.getAutomaticZenRule(eq(ZEN_RULE_ID))).thenReturn(ZEN_RULE);

        ZenMode mode = mBackend.getMode(ZEN_RULE_ID);

        assertThat(mode).isEqualTo(new ZenMode(ZEN_RULE_ID, ZEN_RULE, false));
    }

    @Test
    public void getMode_missingRule_returnsNull() {
        when(mNm.getAutomaticZenRule(any())).thenReturn(null);

        ZenMode mode = mBackend.getMode(ZEN_RULE_ID);

        assertThat(mode).isNull();
        verify(mNm).getAutomaticZenRule(eq(ZEN_RULE_ID));
    }

    @Test
    public void getMode_manualDnd_returnsCorrectActiveState() {
        // Set up a base config with an active rule to make sure we're looking at the correct info
        ZenModeConfig configWithActiveRule = configWithRule(new ZenModeConfig(), ZEN_RULE_ID,
                ZEN_RULE, true);

        // Equivalent to disallowAllSounds()
        Policy dndPolicy = new Policy(0, 0, 0);
        configWithActiveRule.applyNotificationPolicy(dndPolicy);
        when(mNm.getZenModeConfig()).thenReturn(configWithActiveRule);

        ZenMode mode = mBackend.getMode(ZenMode.MANUAL_DND_MODE_ID);

        // By default, manual rule is inactive
        assertThat(mode.isActive()).isFalse();

        // Now the returned config will represent the manual rule being active
        when(mNm.getZenModeConfig()).thenReturn(configWithManualRule(configWithActiveRule, true));
        ZenMode activeMode = mBackend.getMode(ZenMode.MANUAL_DND_MODE_ID);
        assertThat(activeMode.isActive()).isTrue();
    }

    @Test
    public void getMode_zenRule_returnsCorrectActiveState() {
        // Set up a base config that has an active manual rule and "rule2", to make sure we're
        // looking at the correct rule's info.
        ZenModeConfig configWithActiveRules = configWithRule(
                configWithManualRule(new ZenModeConfig(), true),  // active manual rule
                "rule2", ZEN_RULE, true);  // active rule 2

        when(mNm.getAutomaticZenRule(eq(ZEN_RULE_ID))).thenReturn(ZEN_RULE);
        when(mNm.getZenModeConfig()).thenReturn(
                configWithRule(configWithActiveRules, ZEN_RULE_ID, ZEN_RULE, false));

        // Round 1: the current config should indicate that the rule is not active
        ZenMode mode = mBackend.getMode(ZEN_RULE_ID);
        assertThat(mode.isActive()).isFalse();

        when(mNm.getZenModeConfig()).thenReturn(
                configWithRule(configWithActiveRules, ZEN_RULE_ID, ZEN_RULE, true));
        ZenMode activeMode = mBackend.getMode(ZEN_RULE_ID);
        assertThat(activeMode.isActive()).isTrue();
    }

    @Test
    public void updateMode_manualDnd_setsDeviceEffects() throws Exception {
        ZenMode manualDnd = ZenMode.manualDndMode(
                new AutomaticZenRule.Builder("DND", Uri.EMPTY)
                        .setZenPolicy(new ZenPolicy())
                        .setDeviceEffects(new ZenDeviceEffects.Builder()
                                .setShouldDimWallpaper(true)
                                .build())
                        .build(), false);

        mBackend.updateMode(manualDnd);

        verify(mNm).setManualZenRuleDeviceEffects(new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .build());
    }

    @Test
    public void updateMode_manualDnd_setsNotificationPolicy() {
        ZenMode manualDnd = ZenMode.manualDndMode(
                new AutomaticZenRule.Builder("DND", Uri.EMPTY)
                        .setZenPolicy(new ZenPolicy.Builder().allowAllSounds().build())
                        .build(), false);

        mBackend.updateMode(manualDnd);

        verify(mNm).setNotificationPolicy(eq(new ZenModeConfig().toNotificationPolicy(
                new ZenPolicy.Builder().allowAllSounds().build())), eq(true));
    }

    @Test
    public void updateMode_zenRule_updatesRule() {
        ZenMode ruleMode = new ZenMode("rule", ZEN_RULE, false);

        mBackend.updateMode(ruleMode);

        verify(mNm).updateAutomaticZenRule(eq("rule"), eq(ZEN_RULE), eq(true));
    }

    @Test
    public void activateMode_manualDnd_setsZenModeImportant() {
        mBackend.activateMode(ZenMode.manualDndMode(MANUAL_DND_RULE, false), null);

        verify(mNm).setZenMode(eq(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS), eq(null),
                any(), eq(true));
    }

    @Test
    public void activateMode_manualDndWithDuration_setsZenModeImportantWithCondition() {
        mBackend.activateMode(ZenMode.manualDndMode(MANUAL_DND_RULE, false),
                Duration.ofMinutes(30));

        verify(mNm).setZenMode(eq(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS),
                eq(ZenModeConfig.toTimeCondition(mContext, 30, 0, true).id),
                any(),
                eq(true));
    }

    @Test
    public void activateMode_zenRule_setsRuleStateActive() {
        mBackend.activateMode(new ZenMode(ZEN_RULE_ID, ZEN_RULE, false), null);

        verify(mNm).setAutomaticZenRuleState(eq(ZEN_RULE_ID),
                eq(new Condition(ZEN_RULE.getConditionId(), "", Condition.STATE_TRUE,
                        Condition.SOURCE_USER_ACTION)));
    }

    @Test
    public void activateMode_zenRuleWithDuration_fails() {
        assertThrows(IllegalArgumentException.class,
                () -> mBackend.activateMode(new ZenMode(ZEN_RULE_ID, ZEN_RULE, false),
                        Duration.ofMinutes(30)));
    }

    @Test
    public void deactivateMode_manualDnd_setsZenModeOff() {
        mBackend.deactivateMode(ZenMode.manualDndMode(MANUAL_DND_RULE, true));

        verify(mNm).setZenMode(eq(ZEN_MODE_OFF), eq(null), any(), eq(true));
    }

    @Test
    public void deactivateMode_zenRule_setsRuleStateInactive() {
        mBackend.deactivateMode(new ZenMode(ZEN_RULE_ID, ZEN_RULE, false));

        verify(mNm).setAutomaticZenRuleState(eq(ZEN_RULE_ID),
                eq(new Condition(ZEN_RULE.getConditionId(), "", Condition.STATE_FALSE,
                        Condition.SOURCE_USER_ACTION)));
    }

    @Test
    public void removeMode_zenRule_deletesRule() {
        mBackend.removeMode(new ZenMode(ZEN_RULE_ID, ZEN_RULE, false));

        verify(mNm).removeAutomaticZenRule(ZEN_RULE_ID, true);
    }

    @Test
    public void removeMode_manualDnd_fails() {
        assertThrows(IllegalArgumentException.class,
                () -> mBackend.removeMode(ZenMode.manualDndMode(MANUAL_DND_RULE, false)));
    }
}
