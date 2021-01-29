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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.never;

import android.content.Context;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.LocalServices;
import com.android.server.display.LocalDisplayAdapter.BacklightAdapter;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
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
    @Mock
    private LogicalLight mMockedBacklight;

    private Handler mHandler;

    private TestListener mListener = new TestListener();

    private LinkedList<DisplayAddress.Physical> mAddresses = new LinkedList<>();

    private Injector mInjector;

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
        mInjector = new Injector();
        mAdapter = new LocalDisplayAdapter(mMockedSyncRoot, mMockedContext, mHandler,
                mListener, mInjector);
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
        assertEquals(expectedPort, address.getPort());
        assertEquals(DISPLAY_MODEL, address.getModel());
        assertEquals(shouldBePrivate, (info.flags & DisplayDeviceInfo.FLAG_PRIVATE) != 0);
    }

    /**
     * Confirm that external display uses physical density.
     */
    @Test
    public void testDpiValues() throws Exception {
        // needs default one always
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

    private static class DisplayModeWrapper {
        public SurfaceControl.DisplayMode mode;
        public float[] expectedAlternativeRefreshRates;

        DisplayModeWrapper(SurfaceControl.DisplayMode mode,
                float[] expectedAlternativeRefreshRates) {
            this.mode = mode;
            this.expectedAlternativeRefreshRates = expectedAlternativeRefreshRates;
        }
    }

    /**
     * Updates the <code>display</code> using the given <code>modes</code> and then checks if the
     * <code>expectedAlternativeRefreshRates</code> are present for each of the
     * <code>modes</code>.
     */
    private void testAlternativeRefreshRatesCommon(FakeDisplay display,
                DisplayModeWrapper[] wrappedModes)
            throws InterruptedException {
        // Update the display.
        SurfaceControl.DisplayMode[] modes = new SurfaceControl.DisplayMode[wrappedModes.length];
        for (int i = 0; i < wrappedModes.length; i++) {
            modes[i] = wrappedModes[i].mode;
        }
        display.modes = modes;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.changedDisplays.size()).isGreaterThan(0);

        // Verify the supported modes are updated accordingly.
        DisplayDevice displayDevice =
                mListener.changedDisplays.get(mListener.changedDisplays.size() - 1);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        Display.Mode[] supportedModes = displayDevice.getDisplayDeviceInfoLocked().supportedModes;
        assertThat(supportedModes.length).isEqualTo(modes.length);

        for (int i = 0; i < wrappedModes.length; i++) {
            assertModeIsSupported(supportedModes, modes[i],
                    wrappedModes[i].expectedAlternativeRefreshRates);
        }
    }

    @Test
    public void testAfterDisplayChange_AlternativeRefreshRatesAreUpdated() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        testAlternativeRefreshRatesCommon(display, new DisplayModeWrapper[] {
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 60f, 0), new float[]{24f, 50f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 50f, 0), new float[]{24f, 60f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 24f, 0), new float[]{50f, 60f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 60f, 0), new float[]{24f, 50f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 50f, 0), new float[]{24f, 60f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 24f, 0), new float[]{50f, 60f}),
        });

        testAlternativeRefreshRatesCommon(display, new DisplayModeWrapper[] {
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 60f, 0), new float[]{50f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 50f, 0), new float[]{60f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 24f, 1), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 60f, 2), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 50f, 3), new float[]{24f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 24f, 3), new float[]{50f}),
        });

        testAlternativeRefreshRatesCommon(display, new DisplayModeWrapper[] {
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 60f, 0), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 50f, 1), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1920, 1080, 24f, 2), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 60f, 3), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 50f, 4), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3840, 2160, 24f, 5), new float[0]),
        });
    }

    @Test
    public void testAfterDisplayChange_DisplayModesAreUpdated() throws Exception {
        SurfaceControl.DisplayMode displayMode = createFakeDisplayMode(1920, 1080, 60f);
        SurfaceControl.DisplayMode[] modes =
                new SurfaceControl.DisplayMode[]{displayMode};
        FakeDisplay display = new FakeDisplay(PORT_A, modes, 0);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays).isEmpty();

        DisplayDeviceInfo displayDeviceInfo = mListener.addedDisplays.get(
                0).getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.supportedModes.length).isEqualTo(modes.length);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode);

        Display.Mode defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(defaultMode.matches(displayMode.width, displayMode.height,
                displayMode.refreshRate)).isTrue();

        Display.Mode activeMode = getModeById(displayDeviceInfo, displayDeviceInfo.modeId);
        assertThat(activeMode.matches(displayMode.width, displayMode.height,
                displayMode.refreshRate)).isTrue();

        // Change the display
        SurfaceControl.DisplayMode addedDisplayInfo = createFakeDisplayMode(3840, 2160,
                60f);
        modes = new SurfaceControl.DisplayMode[]{displayMode, addedDisplayInfo};
        display.modes = modes;
        display.activeMode = 1;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(SurfaceControl.getActiveDisplayMode(display.token)).isEqualTo(1);
        assertThat(SurfaceControl.getDisplayModes(display.token).length).isEqualTo(2);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.supportedModes.length).isEqualTo(modes.length);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode);
        assertModeIsSupported(displayDeviceInfo.supportedModes, addedDisplayInfo);

        activeMode = getModeById(displayDeviceInfo, displayDeviceInfo.modeId);
        assertThat(activeMode.matches(addedDisplayInfo.width, addedDisplayInfo.height,
                addedDisplayInfo.refreshRate)).isTrue();

        defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(defaultMode.matches(addedDisplayInfo.width, addedDisplayInfo.height,
                addedDisplayInfo.refreshRate)).isTrue();
    }

    @Test
    public void testAfterDisplayChange_ActiveModeIsUpdated() throws Exception {
        SurfaceControl.DisplayMode[] modes = new SurfaceControl.DisplayMode[]{
                createFakeDisplayMode(1920, 1080, 60f),
                createFakeDisplayMode(1920, 1080, 50f)
        };
        FakeDisplay display = new FakeDisplay(PORT_A, modes, /* activeMode */ 0);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays).isEmpty();

        DisplayDeviceInfo displayDeviceInfo = mListener.addedDisplays.get(0)
                .getDisplayDeviceInfoLocked();

        Display.Mode activeMode = getModeById(displayDeviceInfo, displayDeviceInfo.modeId);
        assertThat(activeMode.matches(1920, 1080, 60f)).isTrue();

        // Change the display
        display.activeMode = 1;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(SurfaceControl.getActiveDisplayMode(display.token)).isEqualTo(1);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        activeMode = getModeById(displayDeviceInfo, displayDeviceInfo.modeId);
        assertThat(activeMode.matches(1920, 1080, 50f)).isTrue();
    }

    @Test
    public void testAfterDisplayChange_HdrCapabilitiesAreUpdated() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        Display.HdrCapabilities initialHdrCapabilities = new Display.HdrCapabilities(new int[0],
                1000, 1000, 0);
        display.hdrCapabilities = initialHdrCapabilities;
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays).isEmpty();

        DisplayDeviceInfo displayDeviceInfo = mListener.addedDisplays.get(
                0).getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.hdrCapabilities).isEqualTo(initialHdrCapabilities);

        // Change the display
        Display.HdrCapabilities changedHdrCapabilities = new Display.HdrCapabilities(
                new int[Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS], 1000, 1000, 0);
        display.hdrCapabilities = changedHdrCapabilities;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.hdrCapabilities).isEqualTo(changedHdrCapabilities);
    }

    @Test
    public void testAfterDisplayChange_ColorModesAreUpdated() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        final int[] initialColorModes = new int[]{Display.COLOR_MODE_BT709};
        display.colorModes = initialColorModes;
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays).isEmpty();

        DisplayDeviceInfo displayDeviceInfo = mListener.addedDisplays.get(0)
                .getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.colorMode).isEqualTo(Display.COLOR_MODE_BT709);
        assertThat(displayDeviceInfo.supportedColorModes).isEqualTo(initialColorModes);

        // Change the display
        final int[] changedColorModes = new int[]{Display.COLOR_MODE_DEFAULT};
        display.colorModes = changedColorModes;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.colorMode).isEqualTo(Display.COLOR_MODE_DEFAULT);
        assertThat(displayDeviceInfo.supportedColorModes).isEqualTo(changedColorModes);
    }

    @Test
    public void testDisplayChange_withStaleDesiredDisplayModeSpecs() throws Exception {
        SurfaceControl.DisplayMode[] modes = new SurfaceControl.DisplayMode[]{
                createFakeDisplayMode(1920, 1080, 60f),
                createFakeDisplayMode(1920, 1080, 50f)
        };
        final int activeMode = 0;
        FakeDisplay display = new FakeDisplay(PORT_A, modes, activeMode);
        display.desiredDisplayModeSpecs.defaultMode = 1;

        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        // Change the display
        display.modes = new SurfaceControl.DisplayMode[]{
                createFakeDisplayMode(1920, 1080, 60f)
        };
        // SurfaceFlinger can return a stale defaultMode. Make sure this doesn't
        // trigger ArrayOutOfBoundsException.
        display.desiredDisplayModeSpecs.defaultMode = 1;

        setUpDisplay(display);
        updateAvailableDisplays();
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
    }

    @Test
    public void testBacklightAdapter_withSurfaceControlSupport() {
        final Binder displayToken = new Binder();
        doReturn(true).when(() -> SurfaceControl.getDisplayBrightnessSupport(displayToken));

        // Test as default display
        BacklightAdapter ba = new BacklightAdapter(displayToken, true /*isDefault*/);
        ba.setBrightness(0.514f);
        verify(() -> SurfaceControl.setDisplayBrightness(displayToken, 0.514f));

        // Test as not default display
        BacklightAdapter ba2 = new BacklightAdapter(displayToken,
                false /*isDefault*/);
        ba2.setBrightness(0.323f);
        verify(() -> SurfaceControl.setDisplayBrightness(displayToken, 0.323f));
    }

    @Test
    public void testBacklightAdapter_withoutSourceControlSupport_defaultDisplay() {
        final Binder displayToken = new Binder();
        doReturn(false).when(() -> SurfaceControl.getDisplayBrightnessSupport(displayToken));
        doReturn(mMockedBacklight).when(mMockedLightsManager)
                .getLight(LightsManager.LIGHT_ID_BACKLIGHT);

        BacklightAdapter ba = new BacklightAdapter(displayToken, true /*isDefault*/);
        ba.setBrightness(0.123f);
        verify(mMockedBacklight).setBrightness(0.123f);
    }

    @Test
    public void testBacklightAdapter_withoutSourceControlSupport_nonDefaultDisplay() {
        final Binder displayToken = new Binder();
        doReturn(false).when(() -> SurfaceControl.getDisplayBrightnessSupport(displayToken));
        doReturn(mMockedBacklight).when(mMockedLightsManager)
                .getLight(LightsManager.LIGHT_ID_BACKLIGHT);

        BacklightAdapter ba = new BacklightAdapter(displayToken, false /*isDefault*/);
        ba.setBrightness(0.456f);

        // Adapter does not forward any brightness in this case.
        verify(mMockedBacklight, never()).setBrightness(anyFloat());
    }

    private void assertDisplayDpi(DisplayDeviceInfo info, int expectedPort,
                                  float expectedXdpi,
                                  float expectedYDpi,
                                  int expectedDensityDpi) {
        final DisplayAddress.Physical physical = (DisplayAddress.Physical) info.address;
        assertNotNull(physical);
        assertEquals(expectedPort, physical.getPort());
        assertEquals(expectedXdpi, info.xDpi, 0.01);
        assertEquals(expectedYDpi, info.yDpi, 0.01);
        assertEquals(expectedDensityDpi, info.densityDpi);
    }

    private Display.Mode getModeById(DisplayDeviceInfo displayDeviceInfo, int modeId) {
        return Arrays.stream(displayDeviceInfo.supportedModes)
                .filter(mode -> mode.getModeId() == modeId)
                .findFirst()
                .get();
    }

    private void assertModeIsSupported(Display.Mode[] supportedModes,
            SurfaceControl.DisplayMode mode) {
        assertThat(Arrays.stream(supportedModes).anyMatch(
                x -> x.matches(mode.width, mode.height, mode.refreshRate))).isTrue();
    }

    private void assertModeIsSupported(Display.Mode[] supportedModes,
            SurfaceControl.DisplayMode mode, float[] alternativeRefreshRates) {
        float[] sortedAlternativeRates =
                Arrays.copyOf(alternativeRefreshRates, alternativeRefreshRates.length);
        Arrays.sort(sortedAlternativeRates);

        String message = "Expected " + mode + " with alternativeRefreshRates = "
                + Arrays.toString(alternativeRefreshRates) + " to be in list of supported modes = "
                + Arrays.toString(supportedModes);
        Truth.assertWithMessage(message)
            .that(Arrays.stream(supportedModes)
                .anyMatch(x -> x.matches(mode.width, mode.height, mode.refreshRate)
                        && Arrays.equals(x.getAlternativeRefreshRates(), sortedAlternativeRates)))
                .isTrue();
    }


    private static class FakeDisplay {
        public final DisplayAddress.Physical address;
        public final IBinder token = new Binder();
        public final SurfaceControl.DisplayInfo info;
        public SurfaceControl.DisplayMode[] modes;
        public int activeMode;
        public int[] colorModes = new int[]{ Display.COLOR_MODE_DEFAULT };
        public Display.HdrCapabilities hdrCapabilities = new Display.HdrCapabilities(new int[0],
                1000, 1000, 0);
        public SurfaceControl.DesiredDisplayModeSpecs desiredDisplayModeSpecs =
                new SurfaceControl.DesiredDisplayModeSpecs(/* defaultMode */ 0,
                    /* allowGroupSwitching */ false,
                    /* primaryRefreshRateMin */ 60.f,
                    /* primaryRefreshRateMax */ 60.f,
                    /* appRefreshRateMin */ 60.f,
                    /* appRefreshRateMax */60.f);

        private FakeDisplay(int port) {
            this.address = createDisplayAddress(port);
            this.info = createFakeDisplayInfo();
            this.modes = new SurfaceControl.DisplayMode[]{
                    createFakeDisplayMode(800, 600, 60f)
            };
            this.activeMode = 0;
        }

        private FakeDisplay(int port, SurfaceControl.DisplayMode[] modes, int activeMode) {
            this.address = createDisplayAddress(port);
            this.info = createFakeDisplayInfo();
            this.modes = modes;
            this.activeMode = activeMode;
        }
    }

    private void setUpDisplay(FakeDisplay display) {
        mAddresses.add(display.address);
        doReturn(display.token).when(() ->
                SurfaceControl.getPhysicalDisplayToken(display.address.getPhysicalDisplayId()));
        doReturn(display.info).when(() -> SurfaceControl.getDisplayInfo(display.token));
        doReturn(display.modes).when(
                () -> SurfaceControl.getDisplayModes(display.token));
        doReturn(display.activeMode).when(() -> SurfaceControl.getActiveDisplayMode(display.token));
        doReturn(0).when(() -> SurfaceControl.getActiveColorMode(display.token));
        doReturn(display.colorModes).when(
                () -> SurfaceControl.getDisplayColorModes(display.token));
        doReturn(display.hdrCapabilities).when(
                () -> SurfaceControl.getHdrCapabilities(display.token));
        doReturn(display.desiredDisplayModeSpecs)
                .when(() -> SurfaceControl.getDesiredDisplayModeSpecs(display.token));
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
        return DisplayAddress.fromPortAndModel(port, DISPLAY_MODEL);
    }

    private static SurfaceControl.DisplayInfo createFakeDisplayInfo() {
        final SurfaceControl.DisplayInfo info = new SurfaceControl.DisplayInfo();
        info.density = 100;
        return info;
    }

    private static SurfaceControl.DisplayMode createFakeDisplayMode(int width, int height,
            float refreshRate) {
        return createFakeDisplayMode(width, height, refreshRate, 0);
    }

    private static SurfaceControl.DisplayMode createFakeDisplayMode(int width, int height,
            float refreshRate, int group) {
        final SurfaceControl.DisplayMode mode = new SurfaceControl.DisplayMode();
        mode.width = width;
        mode.height = height;
        mode.refreshRate = refreshRate;
        mode.xDpi = 100;
        mode.yDpi = 100;
        mode.group = group;
        return mode;
    }

    private static void waitForHandlerToComplete(Handler handler, long waitTimeMs)
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

    private class HotplugTransmitter {
        private final Handler mHandler;
        private final LocalDisplayAdapter.DisplayEventListener mListener;

        HotplugTransmitter(Looper looper, LocalDisplayAdapter.DisplayEventListener listener) {
            mHandler = new Handler(looper);
            mListener = listener;
        }

        public void sendHotplug(FakeDisplay display, boolean connected)
                throws InterruptedException {
            mHandler.post(() -> mListener.onHotplug(/* timestampNanos = */ 0,
                    display.address.getPhysicalDisplayId(), connected));
            waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        }
    }

    private class Injector extends LocalDisplayAdapter.Injector {
        private HotplugTransmitter mTransmitter;
        @Override
        public void setDisplayEventListenerLocked(Looper looper,
                LocalDisplayAdapter.DisplayEventListener listener) {
            mTransmitter = new HotplugTransmitter(looper, listener);
        }
        public HotplugTransmitter getTransmitter() {
            return mTransmitter;
        }
    }

    private class TestListener implements DisplayAdapter.Listener {
        public ArrayList<DisplayDevice> addedDisplays = new ArrayList<>();
        public ArrayList<DisplayDevice> changedDisplays = new ArrayList<>();

        @Override
        public void onDisplayDeviceEvent(DisplayDevice device, int event) {
            if (event == DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED) {
                addedDisplays.add(device);
            } else if (event == DisplayAdapter.DISPLAY_DEVICE_EVENT_CHANGED) {
                changedDisplays.add(device);
            }
        }

        @Override
        public void onTraversalRequested() {
        }
    }
}
