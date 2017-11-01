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

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.app.NightDisplayController;
import com.android.systemui.Prefs;
import com.android.systemui.Prefs.Key;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.QSTileHost;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AutoTileManagerTest extends SysuiTestCase {

    private QSTileHost mQsTileHost;
    private AutoTileManager mAutoTileManager;

    @Before
    public void setUp() throws Exception {
        Prefs.putBoolean(mContext, Key.QS_NIGHTDISPLAY_ADDED, false);
        mQsTileHost = Mockito.mock(QSTileHost.class);
        mAutoTileManager = new AutoTileManager(mContext, mQsTileHost);
    }

    @After
    public void tearDown() throws Exception {
        mAutoTileManager = null;
    }

    @Test
    public void nightTileAdded_whenActivated() {
        if (!NightDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onActivated(true);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenDeactivated() {
        if (!NightDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onActivated(false);
        verify(mQsTileHost, never()).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeTwilight() {
        if (!NightDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                NightDisplayController.AUTO_MODE_TWILIGHT);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeCustom() {
        if (!NightDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                NightDisplayController.AUTO_MODE_CUSTOM);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenNightModeDisabled() {
        if (!NightDisplayController.isAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                NightDisplayController.AUTO_MODE_DISABLED);
        verify(mQsTileHost, never()).addTile("night");
    }
}
