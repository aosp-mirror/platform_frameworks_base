/**
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

package com.android.systemui.volume;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.LayoutInflater;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.ZenModeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ZenModePanelTest extends SysuiTestCase {

    ZenModePanel mPanel;
    ZenModeController mController;
    Uri mForeverId;

    @Before
    public void setup() throws Exception {
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mPanel = (ZenModePanel) layoutInflater.inflate(com.android.systemui.R.layout.zen_mode_panel,
                null);
        mController = mock(ZenModeController.class);
        mForeverId = Condition.newId(mContext).appendPath("forever").build();

        mPanel.init(mController);
    }

    private void assertProperConditionTagTypes(boolean hasAlarm) {
        final int N = mPanel.getVisibleConditions();
        assertEquals(hasAlarm ? 3 : 2, N);

        assertEquals(mForeverId, mPanel.getConditionTagAt(0).condition.id);
        assertTrue(ZenModeConfig.isValidCountdownConditionId(
                mPanel.getConditionTagAt(1).condition.id));
        assertFalse(ZenModeConfig.isValidCountdownToAlarmConditionId(
                mPanel.getConditionTagAt(1).condition.id));
        if (hasAlarm) {
            assertTrue(ZenModeConfig.isValidCountdownToAlarmConditionId(
                    mPanel.getConditionTagAt(2).condition.id));
        }
    }

    @Test
    public void testHandleUpdateConditions_foreverSelected_alarmExists() {
         Condition forever = new Condition(mForeverId, "", Condition.STATE_TRUE);

        when(mController.getNextAlarm()).thenReturn(System.currentTimeMillis() + 1000);

        mPanel.handleUpdateConditions(forever);
        assertProperConditionTagTypes(true);
        assertTrue(mPanel.getConditionTagAt(0).rb.isChecked());
    }

    @Test
    public void testHandleUpdateConditions_foreverSelected_noAlarm() {
        Uri foreverId = Condition.newId(mContext).appendPath("forever").build();
        Condition forever = new Condition(foreverId, "", Condition.STATE_TRUE);

        when(mController.getNextAlarm()).thenReturn((long) 0);

        mPanel.handleUpdateConditions(forever);
        assertProperConditionTagTypes(false);
        assertEquals(foreverId, mPanel.getConditionTagAt(0).condition.id);
    }

    @Test
    public void testHandleUpdateConditions_countdownSelected_alarmExists() {
        Uri foreverId = Condition.newId(mContext).appendPath("forever").build();

        Condition countdown = new Condition(ZenModeConfig.toCountdownConditionId(
                System.currentTimeMillis() + (3 * 60 * 60 * 1000) + 4000, false),
                "", Condition.STATE_TRUE);

        when(mController.getNextAlarm()).thenReturn(System.currentTimeMillis() + 1000);

        mPanel.handleUpdateConditions(countdown);
        assertProperConditionTagTypes(true);
        assertTrue(mPanel.getConditionTagAt(1).rb.isChecked());
    }

    @Test
    public void testHandleUpdateConditions_countdownSelected_noAlarm() {
        Uri foreverId = Condition.newId(mContext).appendPath("forever").build();

        Condition countdown = new Condition(ZenModeConfig.toCountdownConditionId(
                System.currentTimeMillis() + (3 * 60 * 60 * 1000) + 4000, false),
                "", Condition.STATE_TRUE);

        when(mController.getNextAlarm()).thenReturn((long) 0);

        mPanel.handleUpdateConditions(countdown);
        assertProperConditionTagTypes(false);
        assertTrue(mPanel.getConditionTagAt(1).rb.isChecked());
    }

    @Test
    public void testHandleUpdateConditions_nextAlarmSelected() {
        Uri foreverId = Condition.newId(mContext).appendPath("forever").build();

        Condition alarm = new Condition(ZenModeConfig.toCountdownConditionId(
                System.currentTimeMillis() + 1000, true),
                "", Condition.STATE_TRUE);
        when(mController.getNextAlarm()).thenReturn(System.currentTimeMillis() + 9000);

        mPanel.handleUpdateConditions(alarm);

        assertProperConditionTagTypes(true);
        assertEquals(alarm, mPanel.getConditionTagAt(2).condition);
        assertTrue(mPanel.getConditionTagAt(2).rb.isChecked());
    }

    @Test
    public void testHandleUpdateConditions_foreverSelected_alarmConditionDoesNotChangeIfAttached() {
        Uri foreverId = Condition.newId(mContext).appendPath("forever").build();
        Condition forever = new Condition(foreverId, "", Condition.STATE_TRUE);

        Condition alarm = new Condition(ZenModeConfig.toCountdownConditionId(
                System.currentTimeMillis() + 9000, true),
                "", Condition.STATE_TRUE);
        when(mController.getNextAlarm()).thenReturn(System.currentTimeMillis() + 1000);

        mPanel.handleUpdateConditions(alarm);
        mPanel.setAttached(true);
        mPanel.handleUpdateConditions(forever);

        assertProperConditionTagTypes(true);
        assertEquals(alarm, mPanel.getConditionTagAt(2).condition);
        assertTrue(mPanel.getConditionTagAt(0).rb.isChecked());
    }

    @Test
    public void testHandleUpdateConditions_foreverSelected_timeConditionDoesNotChangeIfAttached() {
        Uri foreverId = Condition.newId(mContext).appendPath("forever").build();
        Condition forever = new Condition(foreverId, "", Condition.STATE_TRUE);

        Condition countdown = new Condition(ZenModeConfig.toCountdownConditionId(
                System.currentTimeMillis() + (3 * 60 * 60 * 1000) + 4000, false),
                "", Condition.STATE_TRUE);
        when(mController.getNextAlarm()).thenReturn((long) 0);

        mPanel.handleUpdateConditions(countdown);
        mPanel.setAttached(true);
        mPanel.handleUpdateConditions(forever);

        assertProperConditionTagTypes(false);
        assertEquals(countdown, mPanel.getConditionTagAt(1).condition);
        assertTrue(mPanel.getConditionTagAt(0).rb.isChecked());
    }

    @Test
    @UiThreadTest
    public void testHandleUpdateManualRule_stillSelectedAfterModeChange() {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();

        Condition alarm = new Condition(ZenModeConfig.toCountdownConditionId(
                System.currentTimeMillis() + 1000, true),
                "", Condition.STATE_TRUE);

        rule.condition = alarm;
        rule.conditionId = alarm.id;
        rule.enabled = true;
        rule.zenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;

        mPanel.handleUpdateManualRule(rule);

        assertProperConditionTagTypes(true);
        assertEquals(alarm, mPanel.getConditionTagAt(2).condition);
        assertTrue(mPanel.getConditionTagAt(2).rb.isChecked());

        assertEquals(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                mPanel.getSelectedZen(Settings.Global.ZEN_MODE_OFF));

        rule.zenMode = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;

        mPanel.handleUpdateManualRule(rule);

        assertProperConditionTagTypes(true);
        assertEquals(alarm, mPanel.getConditionTagAt(2).condition);
        assertTrue(mPanel.getConditionTagAt(2).rb.isChecked());

        assertEquals(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS,
                mPanel.getSelectedZen(Settings.Global.ZEN_MODE_OFF));
    }
}
