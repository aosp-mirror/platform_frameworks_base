/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static junit.framework.TestCase.assertEquals;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class DreamTileTest extends SysuiTestCase {

    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSTileHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private IDreamManager mDreamManager;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    private TestableLooper mTestableLooper;

    private DreamTile mTile;

    private SecureSettings mSecureSettings;

    private static final ComponentName COLORS_DREAM_COMPONENT_NAME = new ComponentName(
            "com.android.dreams", ".Colors");

    private static final int DEFAULT_USER = 0;

    private final String mExpectedTileLabel = mContext.getResources().getString(
            R.string.quick_settings_screensaver_label);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSecureSettings = new FakeSettings();
        mTestableLooper = TestableLooper.get(this);

        when(mHost.getUserId()).thenReturn(DEFAULT_USER);
        when(mHost.getContext()).thenReturn(mContext);

        mTile = spy(new DreamTile(
                mHost,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mDreamManager,
                mSecureSettings,
                mBroadcastDispatcher));

        mTestableLooper.processAllMessages();
        mTile.initialize();
    }

    @Test
    public void testNotAvailable() throws RemoteException {
        // Should not be available if screensaver is disabled
        setScreensaverEnabled(false);

        mTile.refreshState();
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_UNAVAILABLE, mTile.getState().state);

        // Should not be available if component is not set
        mSecureSettings.putInt(Settings.Secure.SCREENSAVER_ENABLED, 1);
        when(mDreamManager.getDreamComponents()).thenReturn(null);

        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_UNAVAILABLE, mTile.getState().state);
        assertEquals(mExpectedTileLabel, mTile.getState().contentDescription);
    }

    @Test
    public void testInactiveWhenDreaming() throws RemoteException {
        setScreensaverEnabled(true);

        when(mDreamManager.getDreamComponents()).thenReturn(new ComponentName[]{
                COLORS_DREAM_COMPONENT_NAME
        });
        when(mDreamManager.isDreaming()).thenReturn(false);

        mTile.refreshState();
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_INACTIVE, mTile.getState().state);
    }

    @Test
    public void testActive() throws RemoteException {
        setScreensaverEnabled(true);

        when(mDreamManager.getDreamComponents()).thenReturn(new ComponentName[]{
                COLORS_DREAM_COMPONENT_NAME
        });
        when(mDreamManager.isDreaming()).thenReturn(true);

        mTile.refreshState();
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_ACTIVE, mTile.getState().state);
    }

    @Test
    public void testClick() throws RemoteException {
        // Set the AOSP dream enabled as the base setup.
        setScreensaverEnabled(true);
        when(mDreamManager.getDreamComponents()).thenReturn(new ComponentName[]{
                COLORS_DREAM_COMPONENT_NAME
        });
        when(mDreamManager.isDreaming()).thenReturn(false);

        mTile.refreshState();
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_INACTIVE, mTile.getState().state);

        // Now click
        mTile.handleClick(null /* view */);

        verify(mDreamManager).dream();

        when(mDreamManager.isDreaming()).thenReturn(true);
        mTile.refreshState();
        mTestableLooper.processAllMessages();
        assertEquals(Tile.STATE_ACTIVE, mTile.getState().state);

        // Click again to see that other method is called
        mTile.handleClick(null /* view */);

        verify(mDreamManager).awaken();
    }

    @Test
    public void testContentDescription() {
        assertEquals(mExpectedTileLabel, mTile.getContentDescription(null));

        final String testDreamName = "MyDream";
        assertEquals(mExpectedTileLabel + ", " + testDreamName,
                mTile.getContentDescription(testDreamName));
    }

    private void setScreensaverEnabled(boolean enabled) {
        mSecureSettings.putIntForUser(Settings.Secure.SCREENSAVER_ENABLED, enabled ? 1 : 0,
                DEFAULT_USER);
    }
}
