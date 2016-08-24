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

import android.content.ComponentName;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.test.suitebuilder.annotation.SmallTest;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.NetworkController;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;

@SmallTest
public class TileServicesTests extends SysuiTestCase {
    private static int NUM_FAKES = TileServices.DEFAULT_MAX_BOUND * 2;

    private TileServices mTileService;
    private ArrayList<TileServiceManager> mManagers;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mManagers = new ArrayList<>();
        final NetworkController networkController = Mockito.mock(NetworkController.class);
        Mockito.when(networkController.getDataSaverController()).thenReturn(
                Mockito.mock(DataSaverController.class));
        QSTileHost host = new QSTileHost(mContext, null, null, null, null,
                networkController, null,
                Mockito.mock(HotspotController.class), null,
                null, null, null, null, null, null, null, null);
        mTileService = new TestTileServices(host, Looper.myLooper());
    }

    public void testRecalculateBindAllowance() {
        // Add some fake tiles.
        for (int i = 0; i < NUM_FAKES; i++) {
            mTileService.getTileWrapper(Mockito.mock(CustomTile.class));
        }
        assertEquals(NUM_FAKES, mManagers.size());

        for (int i = 0; i < NUM_FAKES; i++) {
            Mockito.when(mManagers.get(i).getBindPriority()).thenReturn(i);
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

    public void testCalcFew() {
        for (int i = 0; i < TileServices.DEFAULT_MAX_BOUND - 1; i++) {
            mTileService.getTileWrapper(Mockito.mock(CustomTile.class));
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
        public TestTileServices(QSTileHost host, Looper looper) {
            super(host, looper);
        }

        @Override
        protected TileServiceManager onCreateTileService(ComponentName component, Tile qsTile) {
            TileServiceManager manager = Mockito.mock(TileServiceManager.class);
            mManagers.add(manager);
            return manager;
        }
    }
}
