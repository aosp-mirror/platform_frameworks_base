/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.systemui.statusbar.phone.AutoTileManager.SAVER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
@RunWithLooper
@SmallTest
public class AutoAddTrackerTest extends SysuiTestCase {

    private static final int END_POSITION = -1;
    private static final int USER = 0;

    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private QSHost mQSHost;
    @Mock
    private DumpManager mDumpManager;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverArgumentCaptor;
    @Captor
    private ArgumentCaptor<IntentFilter> mIntentFilterArgumentCaptor;

    private Executor mBackgroundExecutor = Runnable::run; // Direct executor
    private AutoAddTracker mAutoTracker;
    private SecureSettings mSecureSettings;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mSecureSettings = new FakeSettings();

        mSecureSettings.putStringForUser(Secure.QS_AUTO_ADDED_TILES, null, USER);

        mAutoTracker = createAutoAddTracker(USER);
        mAutoTracker.initialize();
    }

    @Test
    public void testChangeFromBackup() {
        assertFalse(mAutoTracker.isAdded(SAVER));

        mSecureSettings.putStringForUser(Secure.QS_AUTO_ADDED_TILES, SAVER, USER);

        assertTrue(mAutoTracker.isAdded(SAVER));

        mAutoTracker.destroy();
    }

    @Test
    public void testSetAdded() {
        assertFalse(mAutoTracker.isAdded(SAVER));
        mAutoTracker.setTileAdded(SAVER);

        assertTrue(mAutoTracker.isAdded(SAVER));

        mAutoTracker.destroy();
    }

    @Test
    public void testPersist() {
        assertFalse(mAutoTracker.isAdded(SAVER));
        mAutoTracker.setTileAdded(SAVER);

        mAutoTracker.destroy();
        mAutoTracker = createAutoAddTracker(USER);
        mAutoTracker.initialize();

        assertTrue(mAutoTracker.isAdded(SAVER));

        mAutoTracker.destroy();
    }

    @Test
    public void testIndependentUsers() {
        mAutoTracker.setTileAdded(SAVER);

        mAutoTracker = createAutoAddTracker(USER + 1);
        mAutoTracker.initialize();
        assertFalse(mAutoTracker.isAdded(SAVER));
    }

    @Test
    public void testChangeUser() {
        mAutoTracker.setTileAdded(SAVER);

        mAutoTracker = createAutoAddTracker(USER + 1);
        mAutoTracker.changeUser(UserHandle.of(USER));
        assertTrue(mAutoTracker.isAdded(SAVER));
    }

    @Test
    public void testRestoredTilePositionPreserved() {
        verify(mBroadcastDispatcher).registerReceiver(
                mBroadcastReceiverArgumentCaptor.capture(), any(), any(), any(), anyInt(), any());
        String restoredTiles = "saver,internet,work,cast";
        Intent restoreTilesIntent = makeRestoreIntent(Secure.QS_TILES, null, restoredTiles);

        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreTilesIntent);

        assertEquals(2, mAutoTracker.getRestoredTilePosition("work"));
    }

    @Test
    public void testNoRestoredTileReturnsEndPosition() {
        verify(mBroadcastDispatcher).registerReceiver(
                mBroadcastReceiverArgumentCaptor.capture(), any(), any(), any(), anyInt(), any());
        Intent restoreTilesIntent = makeRestoreIntent(Secure.QS_TILES, null, null);

        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreTilesIntent);

        assertEquals(END_POSITION, mAutoTracker.getRestoredTilePosition("work"));
    }

    @Test
    public void testBroadcastReceiverRegistered() {
        verify(mBroadcastDispatcher).registerReceiver(
                any(), mIntentFilterArgumentCaptor.capture(), any(), eq(UserHandle.of(USER)),
                anyInt(), any());

        assertTrue(
                mIntentFilterArgumentCaptor.getValue().hasAction(Intent.ACTION_SETTING_RESTORED));
    }

    @Test
    public void testBroadcastReceiverChangesWithUser() {
        mAutoTracker.changeUser(UserHandle.of(USER + 1));

        InOrder inOrder = Mockito.inOrder(mBroadcastDispatcher);
        inOrder.verify(mBroadcastDispatcher).unregisterReceiver(any());
        inOrder.verify(mBroadcastDispatcher)
                .registerReceiver(any(), any(), any(), eq(UserHandle.of(USER + 1)), anyInt(),
                        any());
    }

    @Test
    public void testSettingRestoredWithTilesNotRemovedInSource_noAutoAddedInTarget() {
        verify(mBroadcastDispatcher).registerReceiver(
                mBroadcastReceiverArgumentCaptor.capture(), any(), any(), any(), anyInt(), any());

        // These tiles were present in the original device
        String restoredTiles = "saver,work,internet,cast";
        Intent restoreTilesIntent = makeRestoreIntent(Secure.QS_TILES, null, restoredTiles);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreTilesIntent);

        // And these tiles have been auto-added in the original device
        // (no auto-added before restore)
        String restoredAutoAddTiles = "work";
        Intent restoreAutoAddTilesIntent =
                makeRestoreIntent(Secure.QS_AUTO_ADDED_TILES, null, restoredAutoAddTiles);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreAutoAddTilesIntent);

        // Then, don't remove any current tiles
        verify(mQSHost, never()).removeTiles(any());
        assertEquals(restoredAutoAddTiles,
                mSecureSettings.getStringForUser(Secure.QS_AUTO_ADDED_TILES, USER));
    }

    @Test
    public void testSettingRestoredWithTilesRemovedInSource_noAutoAddedInTarget() {
        verify(mBroadcastDispatcher)
                .registerReceiver(mBroadcastReceiverArgumentCaptor.capture(), any(), any(), any(),
                        anyInt(), any());

        // These tiles were present in the original device
        String restoredTiles = "saver,internet,cast";
        Intent restoreTilesIntent = makeRestoreIntent(Secure.QS_TILES, null, restoredTiles);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreTilesIntent);

        // And these tiles have been auto-added in the original device
        // (no auto-added before restore)
        String restoredAutoAddTiles = "work";
        Intent restoreAutoAddTilesIntent =
                makeRestoreIntent(Secure.QS_AUTO_ADDED_TILES, null, restoredAutoAddTiles);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreAutoAddTilesIntent);

        // Then, remove work tile
        verify(mQSHost).removeTiles(List.of("work"));
        assertEquals(restoredAutoAddTiles,
                mSecureSettings.getStringForUser(Secure.QS_AUTO_ADDED_TILES, USER));
    }

    @Test
    public void testSettingRestoredWithTilesRemovedInSource_sameAutoAddedinTarget() {
        verify(mBroadcastDispatcher)
                .registerReceiver(mBroadcastReceiverArgumentCaptor.capture(), any(), any(), any(),
                        anyInt(), any());

        // These tiles were present in the original device
        String restoredTiles = "saver,internet,cast";
        Intent restoreTilesIntent =
                makeRestoreIntent(Secure.QS_TILES, "saver, internet, cast, work", restoredTiles);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreTilesIntent);

        // And these tiles have been auto-added in the original device
        // (no auto-added before restore)
        String restoredAutoAddTiles = "work";
        Intent restoreAutoAddTilesIntent =
                makeRestoreIntent(Secure.QS_AUTO_ADDED_TILES, "work", restoredAutoAddTiles);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreAutoAddTilesIntent);

        // Then, remove work tile
        verify(mQSHost).removeTiles(List.of("work"));
        assertEquals(restoredAutoAddTiles,
                mSecureSettings.getStringForUser(Secure.QS_AUTO_ADDED_TILES, USER));
    }

    @Test
    public void testSettingRestoredWithTilesRemovedInSource_othersAutoAddedinTarget() {
        verify(mBroadcastDispatcher)
                .registerReceiver(mBroadcastReceiverArgumentCaptor.capture(), any(), any(), any(),
                        anyInt(), any());

        // These tiles were present in the original device
        String restoredTiles = "saver,internet,cast";
        Intent restoreTilesIntent =
                makeRestoreIntent(Secure.QS_TILES, "saver, internet, cast, work", restoredTiles);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreTilesIntent);

        // And these tiles have been auto-added in the original device
        // (no auto-added before restore)
        String restoredAutoAddTiles = "work";
        Intent restoreAutoAddTilesIntent =
                makeRestoreIntent(Secure.QS_AUTO_ADDED_TILES, "inversion", restoredAutoAddTiles);
        mBroadcastReceiverArgumentCaptor.getValue().onReceive(mContext, restoreAutoAddTilesIntent);

        // Then, remove work tile
        verify(mQSHost).removeTiles(List.of("work"));

        String setting = mSecureSettings.getStringForUser(Secure.QS_AUTO_ADDED_TILES, USER);
        assertEquals(2, setting.split(",").length);
        assertTrue(setting.contains("work"));
        assertTrue(setting.contains("inversion"));
    }


    private Intent makeRestoreIntent(
            String settingName, String previousValue, String restoredValue) {
        Intent intent = new Intent(Intent.ACTION_SETTING_RESTORED);
        intent.putExtra(Intent.EXTRA_SETTING_NAME, settingName);
        intent.putExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE, previousValue);
        intent.putExtra(Intent.EXTRA_SETTING_NEW_VALUE, restoredValue);
        return intent;
    }

    private AutoAddTracker createAutoAddTracker(int user) {
        // Null handler wil dispatch sync.
        return new AutoAddTracker(
                mSecureSettings,
                mBroadcastDispatcher,
                mQSHost,
                mDumpManager,
                null,
                mBackgroundExecutor,
                user
        );
    }
}
