/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.when;

import android.os.Handler;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.FakeSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class ReduceBrightColorsTileTest extends SysuiTestCase {
    @Mock
    private QSTileHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private UserTracker mUserTracker;

    private FakeSettings mFakeSettings;
    private TestableLooper mTestableLooper;
    private ReduceBrightColorsTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);
        mFakeSettings = new FakeSettings();

        mTile = new ReduceBrightColorsTile(
                true,
                mHost,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mUserTracker,
                mFakeSettings
        );
    }

    @Test
    public void testNotActive() {
        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals(Tile.STATE_INACTIVE, mTile.getState().state);
        assertEquals(mTile.getState().label.toString(),
                mContext.getString(R.string.quick_settings_reduce_bright_colors_label));
    }

    @Test
    public void testActive() {
        mFakeSettings.putIntForUser(
                Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                1,
                mUserTracker.getUserId());
        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertActiveState();
    }

    @Test
    public void testActive_clicked_isActive() {
        mTile.refreshState();
        mTestableLooper.processAllMessages();
        // Validity check
        assertEquals(Tile.STATE_INACTIVE, mTile.getState().state);

        mTile.handleClick();
        mTile.refreshState();
        mTestableLooper.processAllMessages();

        assertActiveState();
    }

    private void assertActiveState() {
        assertEquals(Tile.STATE_ACTIVE, mTile.getState().state);
        assertEquals(mTile.getState().label.toString(),
                mContext.getString(R.string.quick_settings_reduce_bright_colors_label));
    }
}
