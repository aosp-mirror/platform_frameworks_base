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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quicksettings.IQSTileService;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.pipeline.data.repository.CustomTileAddedRepository;
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class TileServicesTest extends SysuiTestCase {
    private static int NUM_FAKES = TileServices.DEFAULT_MAX_BOUND * 2;

    private static final ComponentName TEST_COMPONENT =
            ComponentName.unflattenFromString("pkg/.cls");

    private TileServices mTileService;
    private TestableLooper mTestableLooper;
    private ArrayList<TileServiceManager> mManagers;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private StatusBarIconController mStatusBarIconController;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private TileServiceRequestController.Builder mTileServiceRequestControllerBuilder;
    @Mock
    private TileServiceRequestController mTileServiceRequestController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private TileLifecycleManager.Factory mTileLifecycleManagerFactory;
    @Mock
    private TileLifecycleManager mTileLifecycleManager;
    @Mock
    private QSHost mQSHost;
    @Mock
    private PanelInteractor mPanelInteractor;
    @Captor
    private ArgumentCaptor<CommandQueue.Callbacks> mCallbacksArgumentCaptor;
    @Mock
    private CustomTileAddedRepository mCustomTileAddedRepository;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mManagers = new ArrayList<>();
        mTestableLooper = TestableLooper.get(this);

        when(mTileServiceRequestControllerBuilder.create(any()))
                .thenReturn(mTileServiceRequestController);
        when(mTileLifecycleManagerFactory.create(any(Intent.class), any(UserHandle.class)))
                .thenReturn(mTileLifecycleManager);
        when(mQSHost.getContext()).thenReturn(mContext);

        Provider<Handler> provider = () -> new Handler(mTestableLooper.getLooper());

        mTileService = new TestTileServices(mQSHost, provider, mBroadcastDispatcher,
                mUserTracker, mKeyguardStateController, mCommandQueue, mStatusBarIconController,
                mPanelInteractor, mCustomTileAddedRepository,
                new FakeExecutor(new FakeSystemClock()));
    }

    @After
    public void tearDown() throws Exception {
        mTileService.destroy();
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
            verify(mManagers.get(i), times(1)).calculateBindPriority(anyLong());
            ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
            verify(mManagers.get(i), times(1)).setBindAllowed(captor.capture());

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
            verify(mManagers.get(i), times(2)).setBindAllowed(captor.capture());

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
            verify(mManagers.get(i), never()).calculateBindPriority(
                    anyLong());

            // All should be bound since there are less than the max services.
            ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
            verify(mManagers.get(i), times(1)).setBindAllowed(captor.capture());

            assertTrue(captor.getValue());
        }
    }

    @Test
    public void testRegisterCommand() {
        verify(mCommandQueue).addCallback(any());
    }

    @Test
    public void testRequestListeningStatusCommand() throws RemoteException {
        ArgumentCaptor<CommandQueue.Callbacks> captor =
                ArgumentCaptor.forClass(CommandQueue.Callbacks.class);
        verify(mCommandQueue).addCallback(captor.capture());

        CustomTile mockTile = mock(CustomTile.class);
        when(mockTile.getComponent()).thenReturn(TEST_COMPONENT);

        TileServiceManager manager = mTileService.getTileWrapper(mockTile);
        when(manager.isActiveTile()).thenReturn(true);
        when(manager.getTileService()).thenReturn(mock(IQSTileService.class));

        captor.getValue().requestTileServiceListeningState(TEST_COMPONENT);
        mTestableLooper.processAllMessages();
        verify(manager).setBindRequested(true);
        verify(manager.getTileService()).onStartListening();
    }

    @Test
    public void testValidCustomTileStartsActivity() {
        CustomTile tile = mock(CustomTile.class);
        PendingIntent pi = mock(PendingIntent.class);
        ComponentName componentName = mock(ComponentName.class);
        when(tile.getComponent()).thenReturn(componentName);
        when(componentName.getPackageName()).thenReturn(this.getContext().getPackageName());

        mTileService.startActivity(tile, pi);

        verify(tile).startActivityAndCollapse(pi);
    }

    @Test
    public void testInvalidCustomTileDoesNotStartActivity() {
        CustomTile tile = mock(CustomTile.class);
        PendingIntent pi = mock(PendingIntent.class);
        ComponentName componentName = mock(ComponentName.class);
        when(tile.getComponent()).thenReturn(componentName);
        when(componentName.getPackageName()).thenReturn("invalid.package.name");

        Assert.assertThrows(SecurityException.class, () -> mTileService.startActivity(tile, pi));

        verify(tile, never()).startActivityAndCollapse(pi);
    }

    @Test
    public void testOnStartActivityCollapsesPanel() {
        CustomTile tile = mock(CustomTile.class);
        ComponentName componentName = mock(ComponentName.class);
        when(tile.getComponent()).thenReturn(componentName);
        when(componentName.getPackageName()).thenReturn(this.getContext().getPackageName());
        TileServiceManager manager = mTileService.getTileWrapper(tile);

        mTileService.onStartActivity(manager.getToken());
        verify(mPanelInteractor).forceCollapsePanels();
    }

    @Test
    public void testOnShowDialogCollapsesPanel() {
        CustomTile tile = mock(CustomTile.class);
        ComponentName componentName = mock(ComponentName.class);
        when(tile.getComponent()).thenReturn(componentName);
        when(componentName.getPackageName()).thenReturn(this.getContext().getPackageName());
        TileServiceManager manager = mTileService.getTileWrapper(tile);

        mTileService.onShowDialog(manager.getToken());
        verify(mPanelInteractor).forceCollapsePanels();
    }

    @Test
    public void tileFreedForCorrectUser() throws RemoteException {
        verify(mCommandQueue).addCallback(mCallbacksArgumentCaptor.capture());

        ComponentName componentName = new ComponentName("pkg", "cls");
        CustomTile tileUser0 = mock(CustomTile.class);
        CustomTile tileUser1 = mock(CustomTile.class);

        when(tileUser0.getComponent()).thenReturn(componentName);
        when(tileUser1.getComponent()).thenReturn(componentName);
        when(tileUser0.getUser()).thenReturn(0);
        when(tileUser1.getUser()).thenReturn(1);

        // Create a tile for user 0
        TileServiceManager manager0 = mTileService.getTileWrapper(tileUser0);
        when(manager0.isActiveTile()).thenReturn(true);
        // Then create a tile for user 1
        TileServiceManager manager1 = mTileService.getTileWrapper(tileUser1);
        when(manager1.isActiveTile()).thenReturn(true);

        // When the tile for user 0 gets freed
        mTileService.freeService(tileUser0, manager0);
        // and the user is 1
        when(mUserTracker.getUserId()).thenReturn(1);

        // a call to requestListeningState
        mCallbacksArgumentCaptor.getValue().requestTileServiceListeningState(componentName);
        mTestableLooper.processAllMessages();

        // will call in the correct tile
        verify(manager1).setBindRequested(true);
        // and set it to listening
        verify(manager1.getTileService()).onStartListening();
    }

    private class TestTileServices extends TileServices {
        TestTileServices(QSHost host, Provider<Handler> handlerProvider,
                BroadcastDispatcher broadcastDispatcher, UserTracker userTracker,
                KeyguardStateController keyguardStateController, CommandQueue commandQueue,
                StatusBarIconController statusBarIconController, PanelInteractor panelInteractor,
                CustomTileAddedRepository customTileAddedRepository, DelayableExecutor executor) {
            super(host, handlerProvider, broadcastDispatcher, userTracker, keyguardStateController,
                    commandQueue, statusBarIconController, panelInteractor,
                    mTileLifecycleManagerFactory, customTileAddedRepository, executor);
        }

        @Override
        protected TileServiceManager onCreateTileService(
                ComponentName component, BroadcastDispatcher broadcastDispatcher) {
            TileServiceManager manager = mock(TileServiceManager.class);
            mManagers.add(manager);
            when(manager.isLifecycleStarted()).thenReturn(true);
            Binder b = new Binder();
            when(manager.getToken()).thenReturn(b);
            IQSTileService service = mock(IQSTileService.class);
            when(manager.getTileService()).thenReturn(service);
            return manager;
        }
    }
}
