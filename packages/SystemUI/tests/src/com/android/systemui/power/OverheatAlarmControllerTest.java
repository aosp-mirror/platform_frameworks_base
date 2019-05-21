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

package com.android.systemui.power;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.PowerManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class OverheatAlarmControllerTest extends SysuiTestCase {
    @Mock
    Context mMockContext;
    @Mock
    PowerManager mMockPowerManager;

    OverheatAlarmController mSpyOverheatAlarmController;

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);
        mMockPowerManager = mock(PowerManager.class);
        mSpyOverheatAlarmController = spy(new OverheatAlarmController(mMockContext));
        when(mMockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mMockPowerManager);
    }

    @After
    public void tearDown() {
        mMockContext = null;
        mMockPowerManager = null;
        mSpyOverheatAlarmController = null;
    }

    @Test
    public void testStartAlarm_shouldPlaySound() {
        mSpyOverheatAlarmController.startAlarm(mMockContext);
        verify(mSpyOverheatAlarmController).playSound(mMockContext);
    }

    @Test
    public void testStartAlarm_shouldStartVibrate() {
        mSpyOverheatAlarmController.startAlarm(mMockContext);
        verify(mSpyOverheatAlarmController).startVibrate();
    }

    @Test
    public void testStartVibrate_shouldPerformVibrate() {
        mSpyOverheatAlarmController.startVibrate();
        verify(mSpyOverheatAlarmController).performVibrate();
    }

    @Test
    public void testStopAlarm_shouldStopPlay() {
        mSpyOverheatAlarmController.stopAlarm();
        verify(mSpyOverheatAlarmController).stopPlayer();
    }

    @Test
    public void testStopAlarm_shouldStopVibrate() {
        mSpyOverheatAlarmController.stopAlarm();
        verify(mSpyOverheatAlarmController).stopVibrate();
    }
}
