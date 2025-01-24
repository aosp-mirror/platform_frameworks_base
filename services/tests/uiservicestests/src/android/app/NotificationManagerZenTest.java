/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.app;

import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationSystemUtil.runAsSystemUi;
import static android.app.NotificationSystemUtil.toggleNotificationPolicyAccess;
import static android.service.notification.Condition.STATE_FALSE;
import static android.service.notification.Condition.STATE_TRUE;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.service.notification.Condition;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class NotificationManagerZenTest {

    private Context mContext;
    private NotificationManager mNotificationManager;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        toggleNotificationPolicyAccess(mContext, mContext.getPackageName(), true);
        runAsSystemUi(() -> mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL));
        removeAutomaticZenRules();
    }

    @After
    public void tearDown() {
        runAsSystemUi(() -> mNotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL));
        removeAutomaticZenRules();
    }

    private void removeAutomaticZenRules() {
        // Delete AZRs created by this test (query "as app", then delete "as system" so they are
        // not preserved to be restored later).
        Map<String, AutomaticZenRule> rules = mNotificationManager.getAutomaticZenRules();
        runAsSystemUi(() -> {
            for (String ruleId : rules.keySet()) {
                mNotificationManager.removeAutomaticZenRule(ruleId);
            }
        });
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void setAutomaticZenRuleState_manualActivation() {
        AutomaticZenRule ruleToCreate = createZenRule("rule");
        String ruleId = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        Condition manualActivate = new Condition(ruleToCreate.getConditionId(), "manual-on",
                STATE_TRUE, Condition.SOURCE_USER_ACTION);
        Condition manualDeactivate = new Condition(ruleToCreate.getConditionId(), "manual-off",
                STATE_FALSE, Condition.SOURCE_USER_ACTION);
        Condition autoActivate = new Condition(ruleToCreate.getConditionId(), "auto-on",
                STATE_TRUE);
        Condition autoDeactivate = new Condition(ruleToCreate.getConditionId(), "auto-off",
                STATE_FALSE);

        // User manually activates -> it's active.
        runAsSystemUi(
                () -> mNotificationManager.setAutomaticZenRuleState(ruleId, manualActivate));
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // User manually deactivates -> it's inactive.
        runAsSystemUi(
                () -> mNotificationManager.setAutomaticZenRuleState(ruleId, manualDeactivate));
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);

        // And app can activate and deactivate.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoDeactivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void setAutomaticZenRuleState_manualDeactivation() {
        AutomaticZenRule ruleToCreate = createZenRule("rule");
        String ruleId = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        Condition manualActivate = new Condition(ruleToCreate.getConditionId(), "manual-on",
                STATE_TRUE, Condition.SOURCE_USER_ACTION);
        Condition manualDeactivate = new Condition(ruleToCreate.getConditionId(), "manual-off",
                STATE_FALSE, Condition.SOURCE_USER_ACTION);
        Condition autoActivate = new Condition(ruleToCreate.getConditionId(), "auto-on",
                STATE_TRUE);
        Condition autoDeactivate = new Condition(ruleToCreate.getConditionId(), "auto-off",
                STATE_FALSE);

        // App activates rule.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // User manually deactivates -> it's inactive.
        runAsSystemUi(
                () -> mNotificationManager.setAutomaticZenRuleState(ruleId, manualDeactivate));
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);

        // User manually reactivates -> it's active.
        runAsSystemUi(
                () -> mNotificationManager.setAutomaticZenRuleState(ruleId, manualActivate));
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // That manual activation removed the override-deactivate, but didn't put an
        // override-activate, so app can deactivate when its natural schedule ends.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoDeactivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void setAutomaticZenRuleState_respectsManuallyActivated() {
        AutomaticZenRule ruleToCreate = createZenRule("rule");
        String ruleId = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        Condition manualActivate = new Condition(ruleToCreate.getConditionId(), "manual-on",
                STATE_TRUE, Condition.SOURCE_USER_ACTION);
        Condition autoActivate = new Condition(ruleToCreate.getConditionId(), "auto-on",
                STATE_TRUE);
        Condition autoDeactivate = new Condition(ruleToCreate.getConditionId(), "auto-off",
                STATE_FALSE);

        // App thinks rule should be inactive.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoDeactivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);

        // Manually activate -> it's active.
        runAsSystemUi(() -> mNotificationManager.setAutomaticZenRuleState(ruleId, manualActivate));
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // App says it should be inactive, but it's ignored.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoDeactivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // App says it should be active. No change now...
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // ... but when the app wants to deactivate next time, it works.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoDeactivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void setAutomaticZenRuleState_respectsManuallyDeactivated() {
        AutomaticZenRule ruleToCreate = createZenRule("rule");
        String ruleId = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        Condition manualDeactivate = new Condition(ruleToCreate.getConditionId(), "manual-off",
                STATE_FALSE, Condition.SOURCE_USER_ACTION);
        Condition autoActivate = new Condition(ruleToCreate.getConditionId(), "auto-on",
                STATE_TRUE);
        Condition autoDeactivate = new Condition(ruleToCreate.getConditionId(), "auto-off",
                STATE_FALSE);

        // App activates rule.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // User manually deactivates -> it's inactive.
        runAsSystemUi(
                () -> mNotificationManager.setAutomaticZenRuleState(ruleId, manualDeactivate));
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);

        // App says it should be active, but it's ignored.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);

        // App says it should be inactive. No change now...
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoDeactivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);

        // ... but when the app wants to activate next time, it works.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void setAutomaticZenRuleState_manualActivationFromApp() {
        AutomaticZenRule ruleToCreate = createZenRule("rule");
        String ruleId = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        Condition manualActivate = new Condition(ruleToCreate.getConditionId(), "manual-off",
                STATE_TRUE, Condition.SOURCE_USER_ACTION);
        Condition manualDeactivate = new Condition(ruleToCreate.getConditionId(), "manual-off",
                STATE_FALSE, Condition.SOURCE_USER_ACTION);
        Condition autoActivate = new Condition(ruleToCreate.getConditionId(), "auto-on",
                STATE_TRUE);
        Condition autoDeactivate = new Condition(ruleToCreate.getConditionId(), "auto-off",
                STATE_FALSE);

        // App activates rule.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // User manually deactivates from SysUI -> it's inactive.
        runAsSystemUi(
                () -> mNotificationManager.setAutomaticZenRuleState(ruleId, manualDeactivate));
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);

        // User manually activates from App -> it's active.
        mNotificationManager.setAutomaticZenRuleState(ruleId, manualActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // And app can automatically deactivate it later.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoDeactivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void setAutomaticZenRuleState_manualDeactivationFromApp() {
        AutomaticZenRule ruleToCreate = createZenRule("rule");
        String ruleId = mNotificationManager.addAutomaticZenRule(ruleToCreate);
        Condition manualActivate = new Condition(ruleToCreate.getConditionId(), "manual-off",
                STATE_TRUE, Condition.SOURCE_USER_ACTION);
        Condition manualDeactivate = new Condition(ruleToCreate.getConditionId(), "manual-off",
                STATE_FALSE, Condition.SOURCE_USER_ACTION);
        Condition autoActivate = new Condition(ruleToCreate.getConditionId(), "auto-on",
                STATE_TRUE);

        // User manually activates from SysUI -> it's active.
        runAsSystemUi(
                () -> mNotificationManager.setAutomaticZenRuleState(ruleId, manualActivate));
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);

        // User manually deactivates from App -> it's inactive.
        mNotificationManager.setAutomaticZenRuleState(ruleId, manualDeactivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_FALSE);

        // And app can automatically activate it later.
        mNotificationManager.setAutomaticZenRuleState(ruleId, autoActivate);
        assertThat(mNotificationManager.getAutomaticZenRuleState(ruleId)).isEqualTo(STATE_TRUE);
    }

    private AutomaticZenRule createZenRule(String name) {
        return createZenRule(name, NotificationManager.INTERRUPTION_FILTER_PRIORITY);
    }

    private AutomaticZenRule createZenRule(String name, int filter) {
        return new AutomaticZenRule(name, null,
                new ComponentName(mContext, ExampleActivity.class),
                new Uri.Builder().scheme("scheme")
                        .appendPath("path")
                        .appendQueryParameter("fake_rule", "fake_value")
                        .build(), null, filter, true);
    }
}
