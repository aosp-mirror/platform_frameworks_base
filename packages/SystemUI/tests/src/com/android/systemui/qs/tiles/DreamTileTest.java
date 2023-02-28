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
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
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
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class DreamTileTest extends SysuiTestCase {

    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSHost mHost;
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
    @Mock
    private UserTracker mUserTracker;

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

        mTile = spy(constructTileForTest(true, false));

        mTestableLooper.processAllMessages();
        mTile.initialize();
    }

    @After
    public void tearDown() {
        mTile.destroy();
        mTestableLooper.processAllMessages();
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

    @Test
    public void testUserAvailability() {
        DreamTile unsupportedTile = constructTileForTest(false, true);
        assertFalse(unsupportedTile.isAvailable());

        DreamTile supportedTileAllUsers = constructTileForTest(true, false);

        UserHandle systemUserHandle = mock(UserHandle.class);
        when(systemUserHandle.isSystem()).thenReturn(true);

        UserHandle nonSystemUserHandle = mock(UserHandle.class);
        when(nonSystemUserHandle.isSystem()).thenReturn(false);

        when(mUserTracker.getUserHandle()).thenReturn(systemUserHandle);
        assertTrue(supportedTileAllUsers.isAvailable());
        when(mUserTracker.getUserHandle()).thenReturn(nonSystemUserHandle);
        assertTrue(supportedTileAllUsers.isAvailable());

        DreamTile supportedTileOnlySystemUser = constructTileForTest(true, true);
        when(mUserTracker.getUserHandle()).thenReturn(systemUserHandle);
        assertTrue(supportedTileOnlySystemUser.isAvailable());
        when(mUserTracker.getUserHandle()).thenReturn(nonSystemUserHandle);
        assertFalse(supportedTileOnlySystemUser.isAvailable());
    }

    @Test
    public void testIconDockState() {
        final DreamTile dockedTile = constructTileForTest(true, false);

        final ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        dockedTile.handleSetListening(true);
        verify(mBroadcastDispatcher).registerReceiver(receiverCaptor.capture(), any());
        final BroadcastReceiver receiver = receiverCaptor.getValue();

        Intent dockIntent = new Intent(Intent.ACTION_DOCK_EVENT);
        dockIntent.putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_DESK);
        receiver.onReceive(mContext, dockIntent);
        mTestableLooper.processAllMessages();
        assertEquals(QSTileImpl.ResourceIcon.get(R.drawable.ic_qs_screen_saver),
                dockedTile.getState().icon);

        dockIntent.putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED);
        receiver.onReceive(mContext, dockIntent);
        mTestableLooper.processAllMessages();
        assertEquals(QSTileImpl.ResourceIcon.get(R.drawable.ic_qs_screen_saver_undocked),
                dockedTile.getState().icon);
    }

    private void setScreensaverEnabled(boolean enabled) {
        mSecureSettings.putIntForUser(Settings.Secure.SCREENSAVER_ENABLED, enabled ? 1 : 0,
                DEFAULT_USER);
    }

    private DreamTile constructTileForTest(boolean dreamSupported,
            boolean dreamOnlyEnabledForSystemUser) {
        return new DreamTile(
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
                mBroadcastDispatcher,
                mUserTracker,
                dreamSupported, dreamOnlyEnabledForSystemUser);
    }
}
