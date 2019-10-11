/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.DisplayAddress;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.LocalServices;
import com.android.server.lights.LightsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedList;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocalDisplayAdapterTest {
    private static final long HANDLER_WAIT_MS = 100;

    private static final int PHYSICAL_DISPLAY_ID_MODEL_SHIFT = 8;

    private StaticMockitoSession mMockitoSession;

    private LocalDisplayAdapter mAdapter;

    @Mock
    private DisplayManagerService.SyncRoot mMockedSyncRoot;
    @Mock
    private Context mMockedContext;
    @Mock
    private Resources mMockedResources;
    @Mock
    private LightsManager mMockedLightsManager;

    private Handler mHandler;

    private TestListener mListener = new TestListener();

    private LinkedList<Long> mDisplayIds = new LinkedList<>();

    @Before
    public void setUp() throws Exception {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .mockStatic(SurfaceControl.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mHandler = new Handler(Looper.getMainLooper());
        doReturn(mMockedResources).when(mMockedContext).getResources();
        LocalServices.removeServiceForTest(LightsManager.class);
        LocalServices.addService(LightsManager.class, mMockedLightsManager);
        mAdapter = new LocalDisplayAdapter(mMockedSyncRoot, mMockedContext, mHandler,
                mListener);
        spyOn(mAdapter);
        doReturn(mMockedContext).when(mAdapter).getOverlayContext();
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    /**
     * Confirm that display is marked as private when it is listed in
     * com.android.internal.R.array.config_localPrivateDisplayPorts.
     */
    @Test
    public void testPrivateDisplay() throws Exception {
        // needs default one always
        final long displayId0 = 0;
        setUpDisplay(new DisplayConfig(displayId0, createDummyDisplayInfo()));
        final long displayId1 = 1;
        setUpDisplay(new DisplayConfig(displayId1, createDummyDisplayInfo()));
        final long displayId2 = 2;
        setUpDisplay(new DisplayConfig(displayId2, createDummyDisplayInfo()));
        updateAvailableDisplays();
        // display 1 should be marked as private while display 2 is not.
        doReturn(new int[]{(int) displayId1}).when(mMockedResources)
                .getIntArray(com.android.internal.R.array.config_localPrivateDisplayPorts);
        mAdapter.registerLocked();

        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        // This should be public
        assertDisplay(mListener.addedDisplays.get(0).getDisplayDeviceInfoLocked(), displayId0,
                false);
        // This should be private
        assertDisplay(mListener.addedDisplays.get(1).getDisplayDeviceInfoLocked(), displayId1,
                true);
        // This should be public
        assertDisplay(mListener.addedDisplays.get(2).getDisplayDeviceInfoLocked(), displayId2,
                false);
    }

    /**
     * Confirm that all local displays are public when config_localPrivateDisplayPorts is empty.
     */
    @Test
    public void testPublicDisplaysForNoConfigLocalPrivateDisplayPorts() throws Exception {
        // needs default one always
        final long displayId0 = 0;
        setUpDisplay(new DisplayConfig(displayId0, createDummyDisplayInfo()));
        final long displayId1 = 1;
        setUpDisplay(new DisplayConfig(displayId1, createDummyDisplayInfo()));
        updateAvailableDisplays();
        // config_localPrivateDisplayPorts is null
        mAdapter.registerLocked();

        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        // This should be public
        assertDisplay(mListener.addedDisplays.get(0).getDisplayDeviceInfoLocked(), displayId0,
                false);
        // This should be public
        assertDisplay(mListener.addedDisplays.get(1).getDisplayDeviceInfoLocked(), displayId1,
                false);
    }

    private void assertDisplay(DisplayDeviceInfo info, long expectedPort, boolean shouldBePrivate) {
        DisplayAddress.Physical physical = (DisplayAddress.Physical) info.address;
        assertNotNull(physical);
        assertEquals(expectedPort, physical.getPort());
        assertEquals(shouldBePrivate, (info.flags & DisplayDeviceInfo.FLAG_PRIVATE) != 0);
    }

    private class DisplayConfig {
        public final long displayId;
        public final IBinder displayToken = new Binder();
        public final SurfaceControl.PhysicalDisplayInfo displayInfo;

        private DisplayConfig(long displayId, SurfaceControl.PhysicalDisplayInfo displayInfo) {
            this.displayId = displayId | (0x1 << PHYSICAL_DISPLAY_ID_MODEL_SHIFT);
            this.displayInfo = displayInfo;
        }
    }

    private void setUpDisplay(DisplayConfig config) {
        mDisplayIds.add(config.displayId);
        doReturn(config.displayToken).when(
                () -> SurfaceControl.getPhysicalDisplayToken(config.displayId));
        doReturn(new SurfaceControl.PhysicalDisplayInfo[]{
                config.displayInfo
        }).when(() -> SurfaceControl.getDisplayConfigs(config.displayToken));
        doReturn(0).when(() -> SurfaceControl.getActiveConfig(config.displayToken));
        doReturn(0).when(() -> SurfaceControl.getActiveColorMode(config.displayToken));
        doReturn(new int[]{
                0
        }).when(() -> SurfaceControl.getDisplayColorModes(config.displayToken));
        doReturn(new int[]{
                0
        }).when(() -> SurfaceControl.getAllowedDisplayConfigs(config.displayToken));
    }

    private void updateAvailableDisplays() {
        long[] ids = new long[mDisplayIds.size()];
        int i = 0;
        for (long id : mDisplayIds) {
            ids[i] = id;
            i++;
        }
        doReturn(ids).when(() -> SurfaceControl.getPhysicalDisplayIds());
    }

    private SurfaceControl.PhysicalDisplayInfo createDummyDisplayInfo() {
        SurfaceControl.PhysicalDisplayInfo info = new SurfaceControl.PhysicalDisplayInfo();
        info.density = 100;
        info.xDpi = 100;
        info.yDpi = 100;
        info.secure = false;
        info.width = 800;
        info.height = 600;

        return info;
    }

    private void waitForHandlerToComplete(Handler handler, long waitTimeMs)
            throws InterruptedException {
        final Object lock = new Object();
        synchronized (lock) {
            handler.post(() -> {
                synchronized (lock) {
                    lock.notify();
                }
            });
            lock.wait(waitTimeMs);
        }
    }

    private class TestListener implements DisplayAdapter.Listener {
        public ArrayList<DisplayDevice> addedDisplays = new ArrayList<>();

        @Override
        public void onDisplayDeviceEvent(DisplayDevice device, int event) {
            if (event == DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED) {
                addedDisplays.add(device);
            }
        }

        @Override
        public void onTraversalRequested() {

        }
    }
}
