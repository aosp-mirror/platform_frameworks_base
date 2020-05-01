/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs.external;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSFactoryImpl;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.tuner.TunerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class TileServicesTest extends SysuiTestCase {
    private static int NUM_FAKES = TileServices.DEFAULT_MAX_BOUND * 2;

    private TileServices mTileService;
    private ArrayList<TileServiceManager> mManagers;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private StatusBarIconController mStatusBarIconController;
    @Mock
    private QSFactoryImpl mQSFactory;
    @Mock
    private PluginManager mPluginManager;
    @Mock
    private  TunerService mTunerService;
    @Mock
    private AutoTileManager mAutoTileManager;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private UiEventLogger mUiEventLogger;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(BluetoothController.class);
        mManagers = new ArrayList<>();
        QSTileHost host = new QSTileHost(mContext,
                mStatusBarIconController,
                mQSFactory,
                new Handler(),
                Looper.myLooper(),
                mPluginManager,
                mTunerService,
                () -> mAutoTileManager,
                mDumpManager,
                mBroadcastDispatcher,
                Optional.of(mStatusBar),
                mQSLogger,
                mUiEventLogger);
        mTileService = new TestTileServices(host, Looper.getMainLooper(), mBroadcastDispatcher);
    }

    @After
    public void tearDown() throws Exception {
        mTileService.getHost().destroy();
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testRecalculateBindAllowance() {
        // Add some fake tiles.
        for (int i = 0; i < NUM_FAKES; i++) {
            mTileService.getTileWrapper(mock(CustomTile.class));
        }
        assertEquals(NUM_FAKES, mManagers.size());

        for (int i = 0; i < NUM_FAKES; i++) {
            when(mManagers.get(i).getBindPriority()).thenReturn(i);
        }
        mTileService.recalculateBindAllowance();
        for (int i = 0; i < NUM_FAKES; i++) {
            Mockito.verify(mManagers.get(i), Mockito.times(1)).calculateBindPriority(
                    Mockito.anyLong());
            ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
            Mockito.verify(mManagers.get(i), Mockito.times(1)).setBindAllowed(captor.capture());

            assertEquals("" + i + "th service", i >= (NUM_FAKES - TileServices.DEFAULT_MAX_BOUND),
                    (boolean) captor.getValue());
        }
    }

    @Test
    public void testSetMemoryPressure() {
        testRecalculateBindAllowance();
        mTileService.setMemoryPressure(true);

        for (int i = 0; i < NUM_FAKES; i++) {
            ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
            Mockito.verify(mManagers.get(i), Mockito.times(2)).setBindAllowed(captor.capture());

            assertEquals("" + i + "th service", i >= (NUM_FAKES - TileServices.REDUCED_MAX_BOUND),
                    (boolean) captor.getValue());
        }
    }

    @Test
    public void testCalcFew() {
        for (int i = 0; i < TileServices.DEFAULT_MAX_BOUND - 1; i++) {
            mTileService.getTileWrapper(mock(CustomTile.class));
        }
        mTileService.recalculateBindAllowance();

        for (int i = 0; i < TileServices.DEFAULT_MAX_BOUND - 1; i++) {
            // Shouldn't get bind prioirities calculated when there are less than the max services.
            Mockito.verify(mManagers.get(i), Mockito.never()).calculateBindPriority(
                    Mockito.anyLong());

            // All should be bound since there are less than the max services.
            ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
            Mockito.verify(mManagers.get(i), Mockito.times(1)).setBindAllowed(captor.capture());

            assertTrue(captor.getValue());
        }
    }

    private class TestTileServices extends TileServices {
        TestTileServices(QSTileHost host, Looper looper,
                BroadcastDispatcher broadcastDispatcher) {
            super(host, looper, broadcastDispatcher);
        }

        @Override
        protected TileServiceManager onCreateTileService(ComponentName component, Tile qsTile,
                BroadcastDispatcher broadcastDispatcher) {
            TileServiceManager manager = mock(TileServiceManager.class);
            mManagers.add(manager);
            when(manager.isLifecycleStarted()).thenReturn(true);
            return manager;
        }
    }
}
