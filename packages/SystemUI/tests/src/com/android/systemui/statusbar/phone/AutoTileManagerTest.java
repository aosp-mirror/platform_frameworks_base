/*
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager.AlarmClockInfo;
import android.os.Handler;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import com.android.internal.app.ColorDisplayController;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AutoTileManagerTest extends SysuiTestCase {

    @Mock private QSTileHost mQsTileHost;
    @Mock private AutoAddTracker mAutoAddTracker;
    @Captor private ArgumentCaptor<NextAlarmChangeCallback> mAlarmCallback;

    private AutoTileManager mAutoTileManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(NextAlarmController.class);
        mAutoTileManager = new AutoTileManager(mContext, mAutoAddTracker,
            mQsTileHost, new Handler(TestableLooper.get(this).getLooper()));
        verify(Dependency.get(NextAlarmController.class))
            .addCallback(mAlarmCallback.capture());
    }

    @Test
    public void nightTileAdded_whenActivated() {
        if (!ColorDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mColorDisplayCallback.onActivated(true);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenDeactivated() {
        if (!ColorDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mColorDisplayCallback.onActivated(false);
        verify(mQsTileHost, never()).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeTwilight() {
        if (!ColorDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mColorDisplayCallback.onAutoModeChanged(
                ColorDisplayController.AUTO_MODE_TWILIGHT);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeCustom() {
        if (!ColorDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mColorDisplayCallback.onAutoModeChanged(
                ColorDisplayController.AUTO_MODE_CUSTOM);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenNightModeDisabled() {
        if (!ColorDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mColorDisplayCallback.onAutoModeChanged(
                ColorDisplayController.AUTO_MODE_DISABLED);
        verify(mQsTileHost, never()).addTile("night");
    }

    @Test
    public void alarmTileAdded_whenAlarmSet() {
        mAlarmCallback.getValue().onNextAlarmChanged(new AlarmClockInfo(0, null));

        verify(mQsTileHost).addTile("alarm");
        verify(mAutoAddTracker).setTileAdded("alarm");
    }

    @Test
    public void alarmTileNotAdded_whenAlarmNotSet() {
        mAlarmCallback.getValue().onNextAlarmChanged(null);

        verify(mQsTileHost, never()).addTile("alarm");
        verify(mAutoAddTracker, never()).setTileAdded("alarm");
    }

    @Test
    public void alarmTileNotAdded_whenAlreadyAdded() {
        when(mAutoAddTracker.isAdded("alarm")).thenReturn(true);

        mAlarmCallback.getValue().onNextAlarmChanged(new AlarmClockInfo(0, null));

        verify(mQsTileHost, never()).addTile("alarm");
        verify(mAutoAddTracker, never()).setTileAdded("alarm");
    }
}
