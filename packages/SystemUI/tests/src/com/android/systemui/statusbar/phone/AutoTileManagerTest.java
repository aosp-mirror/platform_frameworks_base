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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.hardware.display.ColorDisplayManager;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.HotspotController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AutoTileManagerTest extends SysuiTestCase {

    @Mock private QSTileHost mQsTileHost;
    @Mock private AutoAddTracker mAutoAddTracker;

    private AutoTileManager mAutoTileManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mAutoTileManager = new AutoTileManager(mContext, mAutoAddTracker, mQsTileHost,
                Handler.createAsync(TestableLooper.get(this).getLooper()),
                mock(HotspotController.class),
                mock(DataSaverController.class),
                mock(ManagedProfileController.class),
                mock(NightDisplayListener.class));
    }

    @Test
    public void nightTileAdded_whenActivated() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onActivated(true);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenDeactivated() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onActivated(false);
        verify(mQsTileHost, never()).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeTwilight() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_TWILIGHT);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeCustom() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_CUSTOM_TIME);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenNightModeDisabled() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_DISABLED);
        verify(mQsTileHost, never()).addTile("night");
    }
}
