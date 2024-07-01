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

import static android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_NONE;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;

import static com.google.common.truth.Truth.assertThat;

import android.app.AutomaticZenRule;
import android.net.Uri;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ZenModeTest {

    private static final ZenPolicy ZEN_POLICY = new ZenPolicy.Builder().allowAllSounds().build();

    private static final AutomaticZenRule ZEN_RULE =
            new AutomaticZenRule.Builder("Driving", Uri.parse("drive"))
                    .setPackage("com.some.driving.thing")
                    .setType(AutomaticZenRule.TYPE_DRIVING)
                    .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                    .setZenPolicy(ZEN_POLICY)
                    .build();

    @Test
    public void testBasicMethods() {
        ZenMode zenMode = new ZenMode("id", ZEN_RULE, zenConfigRuleFor(ZEN_RULE, true));

        assertThat(zenMode.getId()).isEqualTo("id");
        assertThat(zenMode.getRule()).isEqualTo(ZEN_RULE);
        assertThat(zenMode.isManualDnd()).isFalse();
        assertThat(zenMode.canBeDeleted()).isTrue();
        assertThat(zenMode.isActive()).isTrue();

        ZenMode manualMode = ZenMode.manualDndMode(ZEN_RULE, false);
        assertThat(manualMode.getId()).isEqualTo(ZenMode.MANUAL_DND_MODE_ID);
        assertThat(manualMode.isManualDnd()).isTrue();
        assertThat(manualMode.canBeDeleted()).isFalse();
        assertThat(manualMode.isActive()).isFalse();
    }

    @Test
    public void constructor_enabledRule_statusEnabled() {
        AutomaticZenRule azr = new AutomaticZenRule.Builder(ZEN_RULE).setEnabled(true).build();
        ZenModeConfig.ZenRule configZenRule = zenConfigRuleFor(azr, false);

        ZenMode mode = new ZenMode("id", azr, configZenRule);
        assertThat(mode.getStatus()).isEqualTo(ZenMode.Status.ENABLED);
        assertThat(mode.isActive()).isFalse();
    }

    @Test
    public void constructor_activeRule_statusActive() {
        AutomaticZenRule azr = new AutomaticZenRule.Builder(ZEN_RULE).setEnabled(true).build();
        ZenModeConfig.ZenRule configZenRule = zenConfigRuleFor(azr, true);

        ZenMode mode = new ZenMode("id", azr, configZenRule);
        assertThat(mode.getStatus()).isEqualTo(ZenMode.Status.ENABLED_AND_ACTIVE);
        assertThat(mode.isActive()).isTrue();
    }

    @Test
    public void constructor_disabledRuleByUser_statusDisabledByUser() {
        AutomaticZenRule azr = new AutomaticZenRule.Builder(ZEN_RULE).setEnabled(false).build();
        ZenModeConfig.ZenRule configZenRule = zenConfigRuleFor(azr, false);
        configZenRule.disabledOrigin = ZenModeConfig.UPDATE_ORIGIN_USER;

        ZenMode mode = new ZenMode("id", azr, configZenRule);
        assertThat(mode.getStatus()).isEqualTo(ZenMode.Status.DISABLED_BY_USER);
    }

    @Test
    public void constructor_disabledRuleByOther_statusDisabledByOther() {
        AutomaticZenRule azr = new AutomaticZenRule.Builder(ZEN_RULE).setEnabled(false).build();
        ZenModeConfig.ZenRule configZenRule = zenConfigRuleFor(azr, false);
        configZenRule.disabledOrigin = ZenModeConfig.UPDATE_ORIGIN_APP;

        ZenMode mode = new ZenMode("id", azr, configZenRule);
        assertThat(mode.getStatus()).isEqualTo(ZenMode.Status.DISABLED_BY_OTHER);
    }

    @Test
    public void getPolicy_interruptionFilterPriority_returnsZenPolicy() {
        AutomaticZenRule azr = new AutomaticZenRule.Builder("Rule", Uri.EMPTY)
                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(ZEN_POLICY)
                .build();
        ZenMode zenMode = new ZenMode("id", azr, zenConfigRuleFor(azr, false));

        assertThat(zenMode.getPolicy()).isEqualTo(ZEN_POLICY);
    }

    @Test
    public void getPolicy_interruptionFilterAlarms_returnsPolicyAllowingAlarms() {
        AutomaticZenRule azr = new AutomaticZenRule.Builder("Rule", Uri.EMPTY)
                .setInterruptionFilter(INTERRUPTION_FILTER_ALARMS)
                .setZenPolicy(ZEN_POLICY) // should be ignored
                .build();
        ZenMode zenMode = new ZenMode("id", azr, zenConfigRuleFor(azr, false));

        assertThat(zenMode.getPolicy()).isEqualTo(
                new ZenPolicy.Builder()
                        .disallowAllSounds()
                        .allowAlarms(true)
                        .allowMedia(true)
                        .allowPriorityChannels(false)
                        .build());
    }

    @Test
    public void getPolicy_interruptionFilterNone_returnsPolicyAllowingNothing() {
        AutomaticZenRule azr = new AutomaticZenRule.Builder("Rule", Uri.EMPTY)
                .setInterruptionFilter(INTERRUPTION_FILTER_NONE)
                .setZenPolicy(ZEN_POLICY) // should be ignored
                .build();
        ZenMode zenMode = new ZenMode("id", azr, zenConfigRuleFor(azr, false));

        assertThat(zenMode.getPolicy()).isEqualTo(
                new ZenPolicy.Builder()
                        .disallowAllSounds()
                        .hideAllVisualEffects()
                        .allowPriorityChannels(false)
                        .build());
    }

    @Test
    public void setPolicy_setsInterruptionFilterPriority() {
        AutomaticZenRule azr = new AutomaticZenRule.Builder("Rule", Uri.EMPTY)
                .setInterruptionFilter(INTERRUPTION_FILTER_ALARMS)
                .build();
        ZenMode zenMode = new ZenMode("id", azr, zenConfigRuleFor(azr, false));

        zenMode.setPolicy(ZEN_POLICY);

        assertThat(zenMode.getRule().getInterruptionFilter()).isEqualTo(
                INTERRUPTION_FILTER_PRIORITY);
        assertThat(zenMode.getPolicy()).isEqualTo(ZEN_POLICY);
        assertThat(zenMode.getRule().getZenPolicy()).isEqualTo(ZEN_POLICY);
    }

    private static ZenModeConfig.ZenRule zenConfigRuleFor(AutomaticZenRule azr, boolean isActive) {
        ZenModeConfig.ZenRule zenRule = new ZenModeConfig.ZenRule();
        zenRule.pkg = azr.getPackageName();
        zenRule.conditionId = azr.getConditionId();
        zenRule.enabled = azr.isEnabled();
        if (isActive) {
            zenRule.condition = new Condition(azr.getConditionId(), "active", Condition.STATE_TRUE);
        }
        return zenRule;
    }
}
