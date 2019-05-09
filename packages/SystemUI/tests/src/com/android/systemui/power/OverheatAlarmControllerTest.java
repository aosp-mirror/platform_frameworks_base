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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class OverheatAlarmControllerTest extends SysuiTestCase{
    OverheatAlarmController mOverheatAlarmController;
    OverheatAlarmController mSpyOverheatAlarmController;

    @Before
    public void setUp() {
        mOverheatAlarmController = OverheatAlarmController.getInstance(mContext);
        mSpyOverheatAlarmController = spy(mOverheatAlarmController);
    }

    @After
    public void tearDown() {
        mSpyOverheatAlarmController.stopAlarm();
    }

    @Test
    public void testGetInstance() {
        assertThat(mOverheatAlarmController).isNotNull();
    }

    @Test
    public void testStartAlarm_PlaySound() {
        mSpyOverheatAlarmController.startAlarm(mContext);
        verify(mSpyOverheatAlarmController).playSound(mContext);
    }

    @Test
    public void testStartAlarm_InitVibrate() {
        mSpyOverheatAlarmController.startAlarm(mContext);
        verify(mSpyOverheatAlarmController).startVibrate();
    }

    @Test
    public void testStartAlarm_StartVibrate() {
        mSpyOverheatAlarmController.startAlarm(mContext);
        verify(mSpyOverheatAlarmController).performVibrate();
    }

    @Test
    public void testStopAlarm_StopPlayer() {
        mSpyOverheatAlarmController.stopAlarm();
        verify(mSpyOverheatAlarmController).stopPlayer();
    }

    @Test
    public void testStopAlarm_StopVibrate() {
        mSpyOverheatAlarmController.stopAlarm();
        verify(mSpyOverheatAlarmController).stopVibrate();
    }
}
