/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.notification;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Fragment;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.service.notification.Condition;
import android.view.LayoutInflater;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class EnableZenModeDialogTest {
    private EnableZenModeDialog mController;

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private Fragment mFragment;
    @Mock
    private NotificationManager mNotificationManager;

    private Context mShadowContext;
    private LayoutInflater mLayoutInflater;
    private Condition mCountdownCondition;
    private Condition mAlarmCondition;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mShadowContext = RuntimeEnvironment.application;
        when(mContext.getApplicationContext()).thenReturn(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        when(mFragment.getContext()).thenReturn(mShadowContext);
        mLayoutInflater = LayoutInflater.from(mShadowContext);

        mController = spy(new EnableZenModeDialog(mContext));
        mController.mContext = mContext;
        mController.mLayoutInflater = mLayoutInflater;
        mController.mForeverId =  Condition.newId(mContext).appendPath("forever").build();
        when(mContext.getString(com.android.internal.R.string.zen_mode_forever))
                .thenReturn("testSummary");
        NotificationManager.Policy alarmsEnabledPolicy = new NotificationManager.Policy(
                NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0, 0);
        doReturn(alarmsEnabledPolicy).when(mNotificationManager).getNotificationPolicy();
        mController.mNotificationManager = mNotificationManager;
        mController.getContentView();

        // these methods use static calls to ZenModeConfig which would normally fail in robotests,
        // so instead do nothing:
        doNothing().when(mController).bindGenericCountdown();
        doReturn(null).when(mController).getTimeUntilNextAlarmCondition();
        doReturn(0L).when(mController).getNextAlarm();
        doNothing().when(mController).bindNextAlarm(any());

        // as a result of doing nothing above, must bind manually:
        Uri alarm =  Condition.newId(mContext).appendPath("alarm").build();
        mAlarmCondition = new Condition(alarm, "alarm", "", "", 0, 0, 0);
        Uri countdown =  Condition.newId(mContext).appendPath("countdown").build();
        mCountdownCondition = new Condition(countdown, "countdown", "", "", 0, 0, 0);
        mController.bind(mCountdownCondition,
                mController.mZenRadioGroupContent.getChildAt(
                        EnableZenModeDialog.COUNTDOWN_CONDITION_INDEX),
                EnableZenModeDialog.COUNTDOWN_CONDITION_INDEX);
        mController.bind(mAlarmCondition,
                mController.mZenRadioGroupContent.getChildAt(
                        EnableZenModeDialog.COUNTDOWN_ALARM_CONDITION_INDEX),
                EnableZenModeDialog.COUNTDOWN_ALARM_CONDITION_INDEX);
    }

    @Test
    public void testForeverChecked() {
        mController.bindConditions(mController.forever());

        assertTrue(mController.getConditionTagAt(EnableZenModeDialog.FOREVER_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(EnableZenModeDialog.COUNTDOWN_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(
                EnableZenModeDialog.COUNTDOWN_ALARM_CONDITION_INDEX).rb.isChecked());
    }

    @Test
    public void testNoneChecked() {
        mController.bindConditions(null);
        assertFalse(mController.getConditionTagAt(EnableZenModeDialog.FOREVER_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(EnableZenModeDialog.COUNTDOWN_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(
                EnableZenModeDialog.COUNTDOWN_ALARM_CONDITION_INDEX).rb.isChecked());
    }

    @Test
    public void testAlarmChecked() {
        doReturn(false).when(mController).isCountdown(mAlarmCondition);
        doReturn(true).when(mController).isAlarm(mAlarmCondition);

        mController.bindConditions(mAlarmCondition);
        assertFalse(mController.getConditionTagAt(EnableZenModeDialog.FOREVER_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(EnableZenModeDialog.COUNTDOWN_CONDITION_INDEX).rb
                .isChecked());
        assertTrue(mController.getConditionTagAt(
                EnableZenModeDialog.COUNTDOWN_ALARM_CONDITION_INDEX).rb.isChecked());
    }

    @Test
    public void testCountdownChecked() {
        doReturn(false).when(mController).isAlarm(mCountdownCondition);
        doReturn(true).when(mController).isCountdown(mCountdownCondition);

        mController.bindConditions(mCountdownCondition);
        assertFalse(mController.getConditionTagAt(EnableZenModeDialog.FOREVER_CONDITION_INDEX).rb
                .isChecked());
        assertTrue(mController.getConditionTagAt(EnableZenModeDialog.COUNTDOWN_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(
                EnableZenModeDialog.COUNTDOWN_ALARM_CONDITION_INDEX).rb.isChecked());
    }

    @Test
    public void testNoAlarmWarning() {
        // setup alarm
        long now = System.currentTimeMillis();
        doReturn(now + 100000).when(mController).getNextAlarm();
        doReturn("").when(mController).getTime(anyLong(), anyLong());

        // allow alarms
        when(mNotificationManager.getNotificationPolicy()).thenReturn(
                new NotificationManager.Policy(
                        NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0, 0));

        // alarm warning should be null
        assertNull(mController.computeAlarmWarningText(null));
    }

    @Test
    public void testAlarmWarning() {
        // setup alarm
        long now = System.currentTimeMillis();
        doReturn(now + 1000000).when(mController).getNextAlarm();
        doReturn("").when(mController).getTime(anyLong(), anyLong());

        // don't allow alarms to bypass dnd
        when(mNotificationManager.getNotificationPolicy()).thenReturn(
                new NotificationManager.Policy(0, 0, 0, 0));

        // return a string if mResources is asked to retrieve a string
        when(mResources.getString(anyInt(), anyString())).thenReturn("");

        // alarm warning should NOT be null
        assertNotNull(mController.computeAlarmWarningText(null));
    }
}