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

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.os.SystemClock;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class OverheatAlarmDialogTest extends SysuiTestCase {

    private OverheatAlarmDialog mDialog, mSpyDialog;

    @Before
    public void setup() {
        mDialog = new OverheatAlarmDialog(mContext);
        mSpyDialog = spy(mDialog);
    }

    @After
    public void tearDown() {
        mSpyDialog = mDialog = null;
    }

    @Test
    public void testFlagShowForAllUsers() {
        assertThat((mDialog.getWindow().getAttributes().privateFlags
                & WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS) != 0).isTrue();
    }

    @Test
    public void testFlagShowWhenLocked() {
        assertThat((mDialog.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0).isTrue();
    }

    @Test
    public void testFlagTurnScreenOn() {
        assertThat((mDialog.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) != 0).isTrue();
    }

    @Test
    public void testFlagKeepScreenOn() {
        assertThat((mDialog.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0).isTrue();
    }

    @Test
    public void testTouchOutsideDialog_NotifyAlarmBeepSoundIntent_ShouldStopBeepSound() {
        final long currentTime = SystemClock.uptimeMillis();
        mSpyDialog.show();
        MotionEvent ev = createMotionEvent(MotionEvent.ACTION_DOWN, currentTime, 0, 0);
        mSpyDialog.dispatchTouchEvent(ev);

        verify(mSpyDialog, atLeastOnce()).notifyAlarmBeepSoundChange();
        mSpyDialog.dismiss();
    }

    @Test
    public void testPressBackKey_NotifyAlarmBeepSoundIntent_ShouldStopBeepSound() {
        mSpyDialog.show();
        KeyEvent ev = new KeyEvent(0, 0, MotionEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0,
                0);
        mSpyDialog.dispatchKeyEvent(ev);

        verify(mSpyDialog, atLeastOnce()).notifyAlarmBeepSoundChange();
        mSpyDialog.dismiss();
    }

    @Test
    public void testPressVolumeUp_NotifyAlarmBeepSoundIntent_ShouldStopBeepSound() {
        mSpyDialog.show();
        KeyEvent ev = new KeyEvent(0, 0, MotionEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP, 0, 0);
        mSpyDialog.dispatchKeyEvent(ev);

        verify(mSpyDialog, atLeastOnce()).notifyAlarmBeepSoundChange();
        mSpyDialog.dismiss();
    }

    @Test
    public void testPressVolumeDown_NotifyAlarmBeepSoundIntent_ShouldStopBeepSound() {
        mSpyDialog.show();
        KeyEvent ev = new KeyEvent(0, 0, MotionEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0);
        mSpyDialog.dispatchKeyEvent(ev);

        verify(mSpyDialog, atLeastOnce()).notifyAlarmBeepSoundChange();
        mSpyDialog.dismiss();
    }

    private MotionEvent createMotionEvent(int action, long eventTime, float x, float y) {
        return MotionEvent.obtain(0, eventTime, action, x, y, 0);
    }
}
