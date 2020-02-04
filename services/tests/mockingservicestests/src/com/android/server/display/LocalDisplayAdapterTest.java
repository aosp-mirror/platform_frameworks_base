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
    private static final Long DISPLAY_MODEL = Long.valueOf(0xAAAAAAAAL);
    private static final int PORT_A = 0;
    private static final int PORT_B = 0x80;
    private static final int PORT_C = 0xFF;

    private static final long HANDLER_WAIT_MS = 100;

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

    private LinkedList<DisplayAddress.Physical> mAddresses = new LinkedList<>();

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
        setUpDisplay(new FakeDisplay(PORT_A));
        setUpDisplay(new FakeDisplay(PORT_B));
        setUpDisplay(new FakeDisplay(PORT_C));
        updateAvailableDisplays();

        doReturn(new int[]{ PORT_B }).when(mMockedResources)
                .getIntArray(com.android.internal.R.array.config_localPrivateDisplayPorts);
        mAdapter.registerLocked();

        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        // This should be public
        assertDisplayPrivateFlag(mListener.addedDisplays.get(0).getDisplayDeviceInfoLocked(),
                PORT_A, false);
        // This should be public
        assertDisplayPrivateFlag(mListener.addedDisplays.get(1).getDisplayDeviceInfoLocked(),
                PORT_B, true);
        // This should be public
        assertDisplayPrivateFlag(mListener.addedDisplays.get(2).getDisplayDeviceInfoLocked(),
                PORT_C, false);
    }

    /**
     * Confirm that all local displays are public when config_localPrivateDisplayPorts is empty.
     */
    @Test
    public void testPublicDisplaysForNoConfigLocalPrivateDisplayPorts() throws Exception {
        setUpDisplay(new FakeDisplay(PORT_A));
        setUpDisplay(new FakeDisplay(PORT_C));
        updateAvailableDisplays();

        // config_localPrivateDisplayPorts is null
        mAdapter.registerLocked();

        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        // This should be public
        assertDisplayPrivateFlag(mListener.addedDisplays.get(0).getDisplayDeviceInfoLocked(),
                PORT_A, false);
        // This should be public
        assertDisplayPrivateFlag(mListener.addedDisplays.get(1).getDisplayDeviceInfoLocked(),
                PORT_C, false);
    }

    private static void assertDisplayPrivateFlag(
            DisplayDeviceInfo info, int expectedPort, boolean shouldBePrivate) {
        final DisplayAddress.Physical address = (DisplayAddress.Physical) info.address;
        assertNotNull(address);
        assertEquals((byte) expectedPort, address.getPort());
        assertEquals(DISPLAY_MODEL, address.getModel());
        assertEquals(shouldBePrivate, (info.flags & DisplayDeviceInfo.FLAG_PRIVATE) != 0);
    }

    /**
     * Confirm that external display uses physical density.
     */
    @Test
    public void testDpiValues() throws Exception {
        setUpDisplay(new FakeDisplay(PORT_A));
        setUpDisplay(new FakeDisplay(PORT_B));
        updateAvailableDisplays();
        mAdapter.registerLocked();

        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertDisplayDpi(
                mListener.addedDisplays.get(0).getDisplayDeviceInfoLocked(), PORT_A, 100, 100,
                16000);
        assertDisplayDpi(
                mListener.addedDisplays.get(1).getDisplayDeviceInfoLocked(), PORT_B, 100, 100,
                16000);
    }

    private void assertDisplayDpi(DisplayDeviceInfo info, int expectedPort,
                                  float expectedXdpi,
                                  float expectedYDpi,
                                  int expectedDensityDpi) {
        final DisplayAddress.Physical physical = (DisplayAddress.Physical) info.address;
        assertNotNull(physical);
        assertEquals((byte) expectedPort, physical.getPort());
        assertEquals(expectedXdpi, info.xDpi, 0.01);
        assertEquals(expectedYDpi, info.yDpi, 0.01);
        assertEquals(expectedDensityDpi, info.densityDpi);
    }

    private static class FakeDisplay {
        public final DisplayAddress.Physical address;
        public final IBinder token = new Binder();
        public final SurfaceControl.DisplayInfo info;
        public final SurfaceControl.DisplayConfig config;

        private FakeDisplay(int port) {
            this.address = createDisplayAddress(port);
            this.info = createFakeDisplayInfo();
            this.config = createFakeDisplayConfig();
        }
    }

    private void setUpDisplay(FakeDisplay display) {
        mAddresses.add(display.address);
        doReturn(display.token).when(() ->
                SurfaceControl.getPhysicalDisplayToken(display.address.getPhysicalDisplayId()));
        doReturn(display.info).when(() -> SurfaceControl.getDisplayInfo(display.token));
        doReturn(new SurfaceControl.DisplayConfig[] { display.config }).when(
                () -> SurfaceControl.getDisplayConfigs(display.token));
        doReturn(0).when(() -> SurfaceControl.getActiveConfig(display.token));
        doReturn(0).when(() -> SurfaceControl.getActiveColorMode(display.token));
        doReturn(new int[] { 0 }).when(
                () -> SurfaceControl.getDisplayColorModes(display.token));
        doReturn(new SurfaceControl.DesiredDisplayConfigSpecs(0, 60.f, 60.f))
                .when(() -> SurfaceControl.getDesiredDisplayConfigSpecs(display.token));
    }

    private void updateAvailableDisplays() {
        long[] ids = new long[mAddresses.size()];
        int i = 0;
        for (DisplayAddress.Physical address : mAddresses) {
            ids[i] = address.getPhysicalDisplayId();
            i++;
        }
        doReturn(ids).when(() -> SurfaceControl.getPhysicalDisplayIds());
    }

    private static DisplayAddress.Physical createDisplayAddress(int port) {
        return DisplayAddress.fromPortAndModel((byte) port, DISPLAY_MODEL);
    }

    private static SurfaceControl.DisplayInfo createFakeDisplayInfo() {
        final SurfaceControl.DisplayInfo info = new SurfaceControl.DisplayInfo();
        info.density = 100;
        return info;
    }

    private static SurfaceControl.DisplayConfig createFakeDisplayConfig() {
        final SurfaceControl.DisplayConfig config = new SurfaceControl.DisplayConfig();
        config.width = 800;
        config.height = 600;
        config.xDpi = 100;
        config.yDpi = 100;
        return config;
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
