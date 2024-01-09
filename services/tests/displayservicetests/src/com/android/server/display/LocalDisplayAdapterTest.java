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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal.DisplayOffloader;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.SurfaceControl;
import android.view.SurfaceControl.RefreshRateRange;
import android.view.SurfaceControl.RefreshRateRanges;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.display.LocalDisplayAdapter.BacklightAdapter;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.mode.DisplayModeDirector;
import com.android.server.display.notifications.DisplayNotificationManager;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocalDisplayAdapterTest {
    private static final Long DISPLAY_MODEL = Long.valueOf(0xAAAAAAAAL);
    private static final int PORT_A = 0;
    private static final int PORT_B = 0x80;
    private static final int PORT_C = 0xFF;
    private static final float REFRESH_RATE = 60f;
    private static final RefreshRateRange REFRESH_RATE_RANGE =
            new RefreshRateRange(REFRESH_RATE, REFRESH_RATE);
    private static final RefreshRateRanges REFRESH_RATE_RANGES =
            new RefreshRateRanges(REFRESH_RATE_RANGE, REFRESH_RATE_RANGE);

    private static final long HANDLER_WAIT_MS = 100;

    private static final int[] HDR_TYPES = new int[]{1, 2};

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
    @Mock
    private DisplayNotificationManager mMockedDisplayNotificationManager;
    @Mock
    private DisplayManagerFlags mFlags;
    @Mock
    private DisplayPowerControllerInterface mMockedDisplayPowerController;

    private Handler mHandler;

    private DisplayOffloadSessionImpl mDisplayOffloadSession;

    private DisplayOffloader mDisplayOffloader;

    private TestListener mListener = new TestListener();

    private LinkedList<DisplayAddress.Physical> mAddresses = new LinkedList<>();

    private Injector mInjector;

    @Mock
    private LocalDisplayAdapter.SurfaceControlProxy mSurfaceControlProxy;
    private static final float[] DISPLAY_RANGE_NITS = { 2.685f, 478.5f };
    private static final int[] BACKLIGHT_RANGE = { 1, 255 };
    private static final float[] BACKLIGHT_RANGE_ZERO_TO_ONE = { 0.0f, 1.0f };
    private static final List<Integer> mDisplayOffloadSupportedStates
            = new ArrayList<>(List.of(Display.STATE_DOZE_SUSPEND));

    @Before
    public void setUp() throws Exception {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mHandler = new Handler(Looper.getMainLooper());
        doReturn(mMockedResources).when(mMockedContext).getResources();
        LocalServices.removeServiceForTest(LightsManager.class);
        LocalServices.addService(LightsManager.class, mMockedLightsManager);
        mInjector = new Injector();
        when(mSurfaceControlProxy.getBootDisplayModeSupport()).thenReturn(true);
        mAdapter = new LocalDisplayAdapter(mMockedSyncRoot, mMockedContext, mHandler,
                mListener, mFlags, mMockedDisplayNotificationManager, mInjector);
        spyOn(mAdapter);
        doReturn(mMockedContext).when(mAdapter).getOverlayContext();

        TypedArray mockNitsRange = createFloatTypedArray(DISPLAY_RANGE_NITS);
        when(mMockedResources.obtainTypedArray(R.array.config_screenBrightnessNits))
                .thenReturn(mockNitsRange);
        when(mMockedResources.getIntArray(R.array.config_screenBrightnessBacklight))
                .thenReturn(BACKLIGHT_RANGE);
        when(mMockedResources.getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMinimumFloat))
                .thenReturn(BACKLIGHT_RANGE_ZERO_TO_ONE[0]);
        when(mMockedResources.getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMaximumFloat))
                .thenReturn(BACKLIGHT_RANGE_ZERO_TO_ONE[1]);
        when(mMockedResources.getStringArray(R.array.config_displayUniqueIdArray))
                .thenReturn(new String[]{});
        TypedArray mockArray = mock(TypedArray.class);
        when(mockArray.length()).thenReturn(0);
        when(mMockedResources.obtainTypedArray(R.array.config_maskBuiltInDisplayCutoutArray))
                .thenReturn(mockArray);
        when(mMockedResources.obtainTypedArray(R.array.config_displayCutoutSideOverrideArray))
                .thenReturn(mockArray);
        when(mMockedResources.getStringArray(R.array.config_mainBuiltInDisplayCutoutSideOverride))
                .thenReturn(new String[]{});
        when(mMockedResources.obtainTypedArray(R.array.config_waterfallCutoutArray))
                .thenReturn(mockArray);
        when(mMockedResources.obtainTypedArray(R.array.config_roundedCornerRadiusArray))
                .thenReturn(mockArray);
        when(mMockedResources.obtainTypedArray(R.array.config_roundedCornerTopRadiusArray))
                .thenReturn(mockArray);
        when(mMockedResources.obtainTypedArray(R.array.config_roundedCornerBottomRadiusArray))
                .thenReturn(mockArray);
        when(mMockedResources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits))
                .thenReturn(mockArray);
        when(mMockedResources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLevels))
                .thenReturn(new int[]{});
        when(mMockedResources.obtainTypedArray(R.array.config_displayShapeArray))
                .thenReturn(mockArray);
        when(mMockedResources.obtainTypedArray(R.array.config_builtInDisplayIsRoundArray))
                .thenReturn(mockArray);
        when(mMockedResources.getIntArray(
            com.android.internal.R.array.config_brightnessThresholdsOfPeakRefreshRate))
            .thenReturn(new int[]{});
        when(mMockedResources.getIntArray(
            com.android.internal.R.array.config_ambientThresholdsOfPeakRefreshRate))
            .thenReturn(new int[]{});
        when(mMockedResources.getIntArray(
            com.android.internal.R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate))
            .thenReturn(new int[]{});
        when(mMockedResources.getIntArray(
            com.android.internal.R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate))
            .thenReturn(new int[]{});
        when(mMockedResources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLcdBacklightValues))
                .thenReturn(new int[]{});
        doReturn(true).when(mFlags).isDisplayOffloadEnabled();
        initDisplayOffloadSession();
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
        // This should be private
        assertDisplayPrivateFlag(mListener.addedDisplays.get(1).getDisplayDeviceInfoLocked(),
                PORT_B, true);
        // This should be public
        assertDisplayPrivateFlag(mListener.addedDisplays.get(2).getDisplayDeviceInfoLocked(),
                PORT_C, false);
    }

    @Test
    public void testSupportedDisplayModesGetOverriddenWhenDisplayIsUpdated()
            throws InterruptedException {
        SurfaceControl.DisplayMode displayMode = createFakeDisplayMode(0, 1920, 1080, 0);
        displayMode.supportedHdrTypes = new int[0];
        FakeDisplay display = new FakeDisplay(PORT_A, new SurfaceControl.DisplayMode[]{displayMode},
                0, 0);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        DisplayDevice displayDevice = mListener.addedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        Display.Mode[] supportedModes = displayDevice.getDisplayDeviceInfoLocked().supportedModes;
        Assert.assertEquals(1, supportedModes.length);
        Assert.assertEquals(0, supportedModes[0].getSupportedHdrTypes().length);

        displayMode.supportedHdrTypes = new int[]{3, 2};
        display.dynamicInfo.supportedDisplayModes = new SurfaceControl.DisplayMode[]{displayMode};
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        supportedModes = displayDevice.getDisplayDeviceInfoLocked().supportedModes;

        Assert.assertEquals(1, supportedModes.length);
        assertArrayEquals(new int[]{2, 3}, supportedModes[0].getSupportedHdrTypes());
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
        display.dynamicInfo.supportedDisplayModes = modes;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertTrue(mListener.traversalRequested);
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
                        createFakeDisplayMode(0, 1920, 1080, 60f, 0), new float[]{24f, 50f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1, 1920, 1080, 50f, 0), new float[]{24f, 60f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(2, 1920, 1080, 24f, 0), new float[]{50f, 60f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3, 3840, 2160, 60f, 0), new float[]{24f, 50f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(4, 3840, 2160, 50f, 0), new float[]{24f, 60f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(5, 3840, 2160, 24f, 0), new float[]{50f, 60f}),
        });

        testAlternativeRefreshRatesCommon(display, new DisplayModeWrapper[] {
                new DisplayModeWrapper(
                        createFakeDisplayMode(0, 1920, 1080, 60f, 0), new float[]{50f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1, 1920, 1080, 50f, 0), new float[]{60f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(2, 1920, 1080, 24f, 1), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3, 3840, 2160, 60f, 2), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(4, 3840, 2160, 50f, 3), new float[]{24f}),
                new DisplayModeWrapper(
                        createFakeDisplayMode(5, 3840, 2160, 24f, 3), new float[]{50f}),
        });

        testAlternativeRefreshRatesCommon(display, new DisplayModeWrapper[] {
                new DisplayModeWrapper(
                        createFakeDisplayMode(0, 1920, 1080, 60f, 0), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(1, 1920, 1080, 50f, 1), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(2, 1920, 1080, 24f, 2), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(3, 3840, 2160, 60f, 3), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(4, 3840, 2160, 50f, 4), new float[0]),
                new DisplayModeWrapper(
                        createFakeDisplayMode(5, 3840, 2160, 24f, 5), new float[0]),
        });
    }

    @Test
    public void testAfterDisplayChange_DefaultDisplayModeIsUpdated() throws Exception {
        SurfaceControl.DisplayMode displayMode = createFakeDisplayMode(0, 1920, 1080, 60f);
        SurfaceControl.DisplayMode[] modes =
                new SurfaceControl.DisplayMode[]{displayMode};
        FakeDisplay display = new FakeDisplay(PORT_A, modes, 0, displayMode.peakRefreshRate);
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
        assertThat(matches(defaultMode, displayMode)).isTrue();

        // Set the display mode to an unsupported mode
        SurfaceControl.DisplayMode displayMode2 = createFakeDisplayMode(1, 1920, 1080, 120f);
        mListener.addedDisplays.get(0).setUserPreferredDisplayModeLocked(
                new Display.Mode(displayMode2.width, displayMode2.height,
                        displayMode2.peakRefreshRate));
        updateAvailableDisplays();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(matches(defaultMode, displayMode2)).isFalse();

        // Change the display
        modes = new SurfaceControl.DisplayMode[]{displayMode, displayMode2};
        display.dynamicInfo.supportedDisplayModes = modes;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = mListener.addedDisplays.get(0).getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.supportedModes.length).isEqualTo(modes.length);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode2);

        defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(matches(defaultMode, displayMode2)).isTrue();
    }

    @Test
    public void testAfterDisplayChange_DisplayModesAreUpdated() throws Exception {
        SurfaceControl.DisplayMode displayMode = createFakeDisplayMode(0, 1920, 1080, 60f);
        SurfaceControl.DisplayMode[] modes =
                new SurfaceControl.DisplayMode[]{displayMode};
        FakeDisplay display = new FakeDisplay(PORT_A, modes, 0, displayMode.peakRefreshRate);
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
        assertThat(matches(defaultMode, displayMode)).isTrue();

        Display.Mode activeMode = getModeById(displayDeviceInfo, displayDeviceInfo.modeId);
        assertThat(matches(activeMode, displayMode)).isTrue();

        // Change the display
        SurfaceControl.DisplayMode addedDisplayInfo = createFakeDisplayMode(1, 3840, 2160, 60f);
        modes = new SurfaceControl.DisplayMode[]{displayMode, addedDisplayInfo};
        display.dynamicInfo.supportedDisplayModes = modes;
        display.dynamicInfo.activeDisplayModeId = 1;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.supportedModes.length).isEqualTo(modes.length);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode);
        assertModeIsSupported(displayDeviceInfo.supportedModes, addedDisplayInfo);

        activeMode = getModeById(displayDeviceInfo, displayDeviceInfo.modeId);
        assertThat(matches(activeMode, addedDisplayInfo)).isTrue();

        defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(matches(defaultMode, addedDisplayInfo)).isTrue();
    }

    @Test
    public void testAfterDisplayChange_ActiveModeIsUpdated() throws Exception {
        SurfaceControl.DisplayMode[] modes = new SurfaceControl.DisplayMode[]{
                createFakeDisplayMode(0, 1920, 1080, 60f),
                createFakeDisplayMode(1, 1920, 1080, 50f)
        };
        FakeDisplay display = new FakeDisplay(PORT_A, modes, /* activeMode */ 0, 60f);
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
        display.dynamicInfo.activeDisplayModeId = 1;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        activeMode = getModeById(displayDeviceInfo, displayDeviceInfo.modeId);
        assertThat(activeMode.matches(1920, 1080, 50f)).isTrue();
    }

    @Test
    public void testAfterDisplayChange_RenderFrameRateIsUpdated() throws Exception {
        SurfaceControl.DisplayMode[] modes = new SurfaceControl.DisplayMode[]{
                createFakeDisplayMode(0, 1920, 1080, 60f),
        };
        FakeDisplay display = new FakeDisplay(PORT_A, modes, /* activeMode */ 0,
                /* renderFrameRate */30f);
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
        assertEquals(Float.floatToIntBits(30f),
                Float.floatToIntBits(displayDeviceInfo.renderFrameRate));

        // Change the render frame rate
        display.dynamicInfo.renderFrameRate = 60f;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        activeMode = getModeById(displayDeviceInfo, displayDeviceInfo.modeId);
        assertThat(activeMode.matches(1920, 1080, 60f)).isTrue();
        assertEquals(Float.floatToIntBits(60f),
                Float.floatToIntBits(displayDeviceInfo.renderFrameRate));
    }

    @Test
    public void testAfterDisplayChange_HdrCapabilitiesAreUpdated() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        Display.HdrCapabilities initialHdrCapabilities = new Display.HdrCapabilities(new int[0],
                1000, 1000, 0);
        display.dynamicInfo.hdrCapabilities = initialHdrCapabilities;
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
        display.dynamicInfo.hdrCapabilities = changedHdrCapabilities;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.hdrCapabilities).isEqualTo(changedHdrCapabilities);
    }

    @Test
    public void testAfterDisplayChange_AllmSupportIsUpdated() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        display.dynamicInfo.autoLowLatencyModeSupported = true;
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays).isEmpty();

        DisplayDeviceInfo displayDeviceInfo = mListener.addedDisplays.get(0)
                .getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.allmSupported).isTrue();

        // Change the display
        display.dynamicInfo.autoLowLatencyModeSupported = false;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.allmSupported).isFalse();
    }

    @Test
    public void testAfterDisplayStateChanges_committedSetAfterState() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        // Turn off.
        Runnable changeStateRunnable = displayDevice.requestDisplayStateLocked(Display.STATE_OFF, 0,
                0, null);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);
        mListener.changedDisplays.clear();
        assertThat(displayDevice.getDisplayDeviceInfoLocked().state).isEqualTo(Display.STATE_OFF);
        assertThat(displayDevice.getDisplayDeviceInfoLocked().committedState).isNotEqualTo(
                Display.STATE_OFF);
        verify(mSurfaceControlProxy, never()).setDisplayPowerMode(display.token, Display.STATE_OFF);

        // Execute powerstate change.
        changeStateRunnable.run();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);


        // Verify that committed triggered a new change event and is set correctly.
        verify(mSurfaceControlProxy, never()).setDisplayPowerMode(display.token, Display.STATE_OFF);
        // We expect at least 1 update for the state change, but
        // could get a second update for the initial brightness change if a nits mapping
        // is available
        assertThat(mListener.changedDisplays.size()).isAnyOf(1, 2);
        assertThat(displayDevice.getDisplayDeviceInfoLocked().state).isEqualTo(Display.STATE_OFF);
        assertThat(displayDevice.getDisplayDeviceInfoLocked().committedState).isEqualTo(
                Display.STATE_OFF);
    }

    @Test
    public void testAfterDisplayChange_GameContentTypeSupportIsUpdated() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        display.dynamicInfo.gameContentTypeSupported = true;
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays).isEmpty();

        DisplayDeviceInfo displayDeviceInfo = mListener.addedDisplays.get(0)
                .getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.gameContentTypeSupported).isTrue();

        // Change the display
        display.dynamicInfo.gameContentTypeSupported = false;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.gameContentTypeSupported).isFalse();
    }

    @Test
    public void testAfterDisplayChange_ColorModesAreUpdated() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        final int[] initialColorModes = new int[]{Display.COLOR_MODE_BT709};
        display.dynamicInfo.supportedColorModes = initialColorModes;
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
        display.dynamicInfo.supportedColorModes = changedColorModes;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
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
                createFakeDisplayMode(0, 1920, 1080, 60f),
                createFakeDisplayMode(1, 1920, 1080, 50f)
        };
        final int activeMode = 0;
        FakeDisplay display = new FakeDisplay(PORT_A, modes, activeMode, 60f);
        display.desiredDisplayModeSpecs.defaultMode = 1;

        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        int baseModeId = Arrays.stream(displayDevice.getDisplayDeviceInfoLocked().supportedModes)
                .filter(mode -> mode.getRefreshRate() == 60f)
                .findFirst()
                .get()
                .getModeId();

        displayDevice.setDesiredDisplayModeSpecsLocked(
                new DisplayModeDirector.DesiredDisplayModeSpecs(
                        /*baseModeId*/ baseModeId,
                        /*allowGroupSwitching*/ false,
                        REFRESH_RATE_RANGES, REFRESH_RATE_RANGES
                ));
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        verify(mSurfaceControlProxy).setDesiredDisplayModeSpecs(display.token,
                new SurfaceControl.DesiredDisplayModeSpecs(
                        /* baseModeId */ 0,
                        /* allowGroupSwitching */ false,
                        REFRESH_RATE_RANGES, REFRESH_RATE_RANGES
                ));

        // Change the display
        display.dynamicInfo.supportedDisplayModes = new SurfaceControl.DisplayMode[]{
                createFakeDisplayMode(2, 1920, 1080, 60f)
        };
        display.dynamicInfo.activeDisplayModeId = 2;
        // SurfaceFlinger can return a stale defaultMode. Make sure this doesn't crash.
        display.desiredDisplayModeSpecs.defaultMode = 1;

        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);

        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();

        baseModeId = displayDevice.getDisplayDeviceInfoLocked().supportedModes[0].getModeId();

        // The traversal request will call setDesiredDisplayModeSpecsLocked on the display device
        displayDevice.setDesiredDisplayModeSpecsLocked(
                new DisplayModeDirector.DesiredDisplayModeSpecs(
                        /*baseModeId*/ baseModeId,
                        /*allowGroupSwitching*/ false,
                        REFRESH_RATE_RANGES, REFRESH_RATE_RANGES
                ));

        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        // Verify that this will reapply the desired modes.
        verify(mSurfaceControlProxy).setDesiredDisplayModeSpecs(display.token,
                new SurfaceControl.DesiredDisplayModeSpecs(
                        /* baseModeId */ 2,
                        /* allowGroupSwitching */ false,
                        REFRESH_RATE_RANGES, REFRESH_RATE_RANGES
                ));
    }

    @Test
    public void testBacklightAdapter_withSurfaceControlSupport() {
        final Binder displayToken = new Binder();

        when(mSurfaceControlProxy.getDisplayBrightnessSupport(displayToken)).thenReturn(true);

        // Test as default display
        BacklightAdapter ba = new BacklightAdapter(displayToken, true /*isDefault*/,
                mSurfaceControlProxy);
        ba.setBacklight(0.514f, 100f, 0.614f, 500f);
        verify(mSurfaceControlProxy).setDisplayBrightness(displayToken, 0.514f, 100f, 0.614f, 500f);

        // Test as not default display
        BacklightAdapter ba2 = new BacklightAdapter(displayToken, false /*isDefault*/,
                mSurfaceControlProxy);
        ba2.setBacklight(0.323f, 101f, 0.723f, 601f);
        verify(mSurfaceControlProxy).setDisplayBrightness(displayToken, 0.323f, 101f, 0.723f, 601f);
    }

    @Test
    public void testBacklightAdapter_withoutSourceControlSupport_defaultDisplay() {
        final Binder displayToken = new Binder();
        when(mSurfaceControlProxy.getDisplayBrightnessSupport(displayToken)).thenReturn(false);
        doReturn(mMockedBacklight).when(mMockedLightsManager)
                .getLight(LightsManager.LIGHT_ID_BACKLIGHT);

        BacklightAdapter ba = new BacklightAdapter(displayToken, true /*isDefault*/,
                mSurfaceControlProxy);
        ba.setBacklight(1f, 1f, 0.123f, 1f);
        verify(mMockedBacklight).setBrightness(0.123f);
    }

    @Test
    public void testBacklightAdapter_withoutSourceControlSupport_nonDefaultDisplay() {
        final Binder displayToken = new Binder();
        when(mSurfaceControlProxy.getDisplayBrightnessSupport(displayToken)).thenReturn(false);
        doReturn(mMockedBacklight).when(mMockedLightsManager)
                .getLight(LightsManager.LIGHT_ID_BACKLIGHT);

        BacklightAdapter ba = new BacklightAdapter(displayToken, false /*isDefault*/,
                mSurfaceControlProxy);
        ba.setBacklight(0.456f, 1f, 1f, 1f);

        // Adapter does not forward any brightness in this case.
        verify(mMockedBacklight, never()).setBrightness(anyFloat());
    }

    @Test
    public void testGetSystemPreferredDisplayMode() throws Exception {
        SurfaceControl.DisplayMode displayMode1 = createFakeDisplayMode(0, 1920, 1080, 60f);
        // system preferred mode
        SurfaceControl.DisplayMode displayMode2 = createFakeDisplayMode(1, 3840, 2160, 60f);
        // user preferred mode
        SurfaceControl.DisplayMode displayMode3 = createFakeDisplayMode(2, 1920, 1080, 30f);

        SurfaceControl.DisplayMode[] modes =
                new SurfaceControl.DisplayMode[]{displayMode1, displayMode2, displayMode3};
        FakeDisplay display = new FakeDisplay(PORT_A, modes, 0, 1);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays).isEmpty();

        DisplayDeviceInfo displayDeviceInfo = mListener.addedDisplays.get(
                0).getDisplayDeviceInfoLocked();
        assertThat(displayDeviceInfo.supportedModes.length).isEqualTo(modes.length);
        Display.Mode defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(matches(defaultMode, displayMode1)).isTrue();

        // Set the user preferred display mode
        mListener.addedDisplays.get(0).setUserPreferredDisplayModeLocked(
                new Display.Mode(
                        displayMode3.width, displayMode3.height, displayMode3.peakRefreshRate));
        updateAvailableDisplays();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        displayDeviceInfo = mListener.addedDisplays.get(
                0).getDisplayDeviceInfoLocked();
        defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(matches(defaultMode, displayMode3)).isTrue();

        // clear the user preferred mode
        mListener.addedDisplays.get(0).setUserPreferredDisplayModeLocked(null);
        updateAvailableDisplays();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        displayDeviceInfo = mListener.addedDisplays.get(
                0).getDisplayDeviceInfoLocked();
        defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(matches(defaultMode, displayMode2)).isTrue();

        // Change the display and add new system preferred mode
        SurfaceControl.DisplayMode addedDisplayInfo = createFakeDisplayMode(3, 2340, 1080, 20f);
        modes = new SurfaceControl.DisplayMode[]{
                displayMode1, displayMode2, displayMode3, addedDisplayInfo};
        display.dynamicInfo.supportedDisplayModes = modes;
        display.dynamicInfo.preferredBootDisplayMode = 3;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(3);

        DisplayDevice displayDevice = mListener.changedDisplays.get(0);
        displayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.supportedModes.length).isEqualTo(modes.length);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode1);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode2);
        assertModeIsSupported(displayDeviceInfo.supportedModes, addedDisplayInfo);

        assertThat(matches(displayDevice.getSystemPreferredDisplayModeLocked(), addedDisplayInfo))
                .isTrue();
    }

    @Test
    public void testGetAndSetDisplayModesDisambiguatesByVsyncRate() throws Exception {
        SurfaceControl.DisplayMode displayMode1 = createFakeDisplayMode(0, 1920, 1080, 60f, 120f);
        SurfaceControl.DisplayMode displayMode2 = createFakeDisplayMode(1, 1920, 1080, 60f, 60f);
        SurfaceControl.DisplayMode[] modes =
                new SurfaceControl.DisplayMode[]{displayMode1, displayMode2};
        FakeDisplay display = new FakeDisplay(PORT_A, modes, 0, 0);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays).isEmpty();

        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        DisplayDeviceInfo displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();
        assertThat(displayDeviceInfo.supportedModes.length).isEqualTo(modes.length);
        Display.Mode defaultMode = getModeById(displayDeviceInfo, displayDeviceInfo.defaultModeId);
        assertThat(matches(defaultMode, displayMode1)).isTrue();
        assertThat(matches(displayDevice.getSystemPreferredDisplayModeLocked(), displayMode1))
                .isTrue();

        display.dynamicInfo.preferredBootDisplayMode = 1;
        setUpDisplay(display);
        mInjector.getTransmitter().sendHotplug(display, /* connected */ true);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);

        assertTrue(mListener.traversalRequested);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        DisplayDevice changedDisplayDevice = mListener.changedDisplays.get(0);
        changedDisplayDevice.applyPendingDisplayDeviceInfoChangesLocked();
        displayDeviceInfo = changedDisplayDevice.getDisplayDeviceInfoLocked();

        assertThat(displayDeviceInfo.supportedModes.length).isEqualTo(modes.length);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode1);
        assertModeIsSupported(displayDeviceInfo.supportedModes, displayMode2);

        assertThat(
                matches(changedDisplayDevice.getSystemPreferredDisplayModeLocked(), displayMode2))
                .isTrue();
    }

    @Test
    public void testHdrSdrRatio_notifiesOnChange() throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        // Turn on / initialize
        assumeTrue(displayDevice.getDisplayDeviceConfig().hasSdrToHdrRatioSpline());
        Runnable changeStateRunnable = displayDevice.requestDisplayStateLocked(Display.STATE_ON, 0,
                0, null);
        changeStateRunnable.run();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        mListener.changedDisplays.clear();

        assertEquals(1.0f, displayDevice.getDisplayDeviceInfoLocked().hdrSdrRatio, 0.001f);

        // HDR time!
        Runnable goHdrRunnable = displayDevice.requestDisplayStateLocked(Display.STATE_ON, 1f,
                0, null);
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        // Display state didn't change, no listeners should have happened
        assertThat(mListener.changedDisplays.size()).isEqualTo(0);

        // Execute hdr change.
        goHdrRunnable.run();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        // Display state didn't change, expect to only get the HDR/SDR ratio change notification
        assertThat(mListener.changedDisplays.size()).isEqualTo(1);

        final float expectedRatio = DISPLAY_RANGE_NITS[1] / DISPLAY_RANGE_NITS[0];
        assertEquals(expectedRatio, displayDevice.getDisplayDeviceInfoLocked().hdrSdrRatio,
                0.001f);
    }

    @Test
    public void test_getDisplayDeviceInfoLocked_internalDisplay_usesCutoutAndCorners()
            throws Exception {
        setupCutoutAndRoundedCorners();
        FakeDisplay display = new FakeDisplay(PORT_A);
        display.info.isInternal = true;
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        // Turn on / initialize
        Runnable changeStateRunnable = displayDevice.requestDisplayStateLocked(Display.STATE_ON, 0,
                0, null);
        changeStateRunnable.run();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        mListener.changedDisplays.clear();

        DisplayDeviceInfo info = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(info.displayCutout).isNotNull();
        assertThat(info.displayCutout.getBoundingRectTop()).isEqualTo(new Rect(507, 33, 573, 99));
        assertThat(info.roundedCorners).isNotNull();
        assertThat(info.roundedCorners.getRoundedCorner(0).getRadius()).isEqualTo(5);
    }

    @Test public void test_getDisplayDeviceInfoLocked_externalDisplay_doesNotUseCutoutOrCorners()
            throws Exception {
        setupCutoutAndRoundedCorners();
        FakeDisplay display = new FakeDisplay(PORT_A);
        display.info.isInternal = false;
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        // Turn on / initialize
        Runnable changeStateRunnable = displayDevice.requestDisplayStateLocked(Display.STATE_ON, 0,
                0, null);
        changeStateRunnable.run();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        mListener.changedDisplays.clear();

        DisplayDeviceInfo info = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(info.displayCutout).isNull();
        assertThat(info.roundedCorners).isNull();
    }

    @Test
    public void test_createLocalExternalDisplay_displayManagementEnabled_shouldHaveDefaultGroup()
            throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        display.info.isInternal = false;
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        // Turn on / initialize
        Runnable changeStateRunnable = displayDevice.requestDisplayStateLocked(Display.STATE_ON, 0,
                0, null);
        changeStateRunnable.run();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        mListener.changedDisplays.clear();

        DisplayDeviceInfo info = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(info.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP).isEqualTo(0);
    }
    @Test
    public void test_createLocalExternalDisplay_displayManagementDisabled_shouldNotHaveOwnGroup()
            throws Exception {
        FakeDisplay display = new FakeDisplay(PORT_A);
        display.info.isInternal = false;
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        // Turn on / initialize
        Runnable changeStateRunnable = displayDevice.requestDisplayStateLocked(Display.STATE_ON, 0,
                0, null);
        changeStateRunnable.run();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        mListener.changedDisplays.clear();

        DisplayDeviceInfo info = displayDevice.getDisplayDeviceInfoLocked();

        assertThat(info.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP).isEqualTo(0);
    }


    @Test
    public void test_displayStateToSupportedState_DisplayOffloadStart()
            throws InterruptedException {
        // prepare a display.
        FakeDisplay display = new FakeDisplay(PORT_A);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        for (Integer supportedState : mDisplayOffloadSupportedStates) {
            Runnable changeStateRunnable = displayDevice.requestDisplayStateLocked(
                    supportedState, 0, 0, mDisplayOffloadSession);
            changeStateRunnable.run();
        }

        verify(mDisplayOffloader, times(mDisplayOffloadSupportedStates.size())).startOffload();
        assertTrue(mDisplayOffloadSession.isActive());
    }

    @Test
    public void test_displayStateToDozeFromDozeSuspend_DisplayOffloadStop()
            throws InterruptedException {
        // prepare a display.
        FakeDisplay display = new FakeDisplay(PORT_A);
        setUpDisplay(display);
        updateAvailableDisplays();
        mAdapter.registerLocked();
        waitForHandlerToComplete(mHandler, HANDLER_WAIT_MS);
        assertThat(mListener.addedDisplays.size()).isEqualTo(1);
        DisplayDevice displayDevice = mListener.addedDisplays.get(0);

        Runnable changeStateToDozeSuspendRunnable = displayDevice.requestDisplayStateLocked(
                Display.STATE_DOZE_SUSPEND, 0, 0, mDisplayOffloadSession);
        Runnable changeStateToDozeRunnable = displayDevice.requestDisplayStateLocked(
                Display.STATE_DOZE, 0, 0, mDisplayOffloadSession);
        changeStateToDozeSuspendRunnable.run();
        changeStateToDozeRunnable.run();

        verify(mDisplayOffloader).stopOffload();
        assertFalse(mDisplayOffloadSession.isActive());
        verify(mMockedDisplayPowerController).setBrightnessFromOffload(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
    }

    private void initDisplayOffloadSession() {
        mDisplayOffloader = spy(new DisplayOffloader() {
            @Override
            public boolean startOffload() {
                return true;
            }

            @Override
            public void stopOffload() {}

            @Override
            public void onBlockingScreenOn(Runnable unblocker) {}
        });

        mDisplayOffloadSession = new DisplayOffloadSessionImpl(mDisplayOffloader,
                mMockedDisplayPowerController);
    }

    private void setupCutoutAndRoundedCorners() {
        String sampleCutout = "M 507,66\n"
                + "a 33,33 0 1 0 66,0 33,33 0 1 0 -66,0\n"
                + "Z\n"
                + "@left\n";
        // Setup some default cutout
        when(mMockedResources.getString(
                com.android.internal.R.string.config_mainBuiltInDisplayCutout))
                .thenReturn(sampleCutout);
        when(mMockedResources.getDimensionPixelSize(
                com.android.internal.R.dimen.rounded_corner_radius)).thenReturn(5);
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
                x -> x.matches(mode.width, mode.height, mode.peakRefreshRate))).isTrue();
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
                .anyMatch(x -> x.matches(mode.width, mode.height, mode.peakRefreshRate)
                        && Arrays.equals(x.getAlternativeRefreshRates(), sortedAlternativeRates)))
                .isTrue();
    }


    private static class FakeDisplay {
        public final DisplayAddress.Physical address;
        public final IBinder token = new Binder();
        public final SurfaceControl.StaticDisplayInfo info;
        public SurfaceControl.DynamicDisplayInfo dynamicInfo =
                new SurfaceControl.DynamicDisplayInfo();
        {
            dynamicInfo.supportedColorModes = new int[]{ Display.COLOR_MODE_DEFAULT };
            dynamicInfo.hdrCapabilities = new Display.HdrCapabilities(new int[0],
                    1000, 1000, 0);
        }

        public SurfaceControl.DesiredDisplayModeSpecs desiredDisplayModeSpecs =
                new SurfaceControl.DesiredDisplayModeSpecs(
                        /* defaultMode */ 0,
                        /* allowGroupSwitching */ false,
                        REFRESH_RATE_RANGES, REFRESH_RATE_RANGES
                );

        private FakeDisplay(int port) {
            address = createDisplayAddress(port);
            info = createFakeDisplayInfo();
            dynamicInfo.supportedDisplayModes = new SurfaceControl.DisplayMode[]{
                    createFakeDisplayMode(0, 800, 600, 60f)
            };
            dynamicInfo.activeDisplayModeId = 0;
        }

        private FakeDisplay(int port, SurfaceControl.DisplayMode[] modes, int activeMode,
                float renderFrameRate) {
            address = createDisplayAddress(port);
            info = createFakeDisplayInfo();
            dynamicInfo.supportedDisplayModes = modes;
            dynamicInfo.activeDisplayModeId = activeMode;
            dynamicInfo.renderFrameRate = renderFrameRate;
        }

        private FakeDisplay(int port, SurfaceControl.DisplayMode[] modes, int activeMode,
                int preferredMode) {
            address = createDisplayAddress(port);
            info = createFakeDisplayInfo();
            dynamicInfo.supportedDisplayModes = modes;
            dynamicInfo.activeDisplayModeId = activeMode;
            dynamicInfo.preferredBootDisplayMode = preferredMode;
        }

    }

    private void setUpDisplay(FakeDisplay display) {
        mAddresses.add(display.address);
        when(mSurfaceControlProxy.getPhysicalDisplayToken(display.address.getPhysicalDisplayId()))
                .thenReturn(display.token);
        when(mSurfaceControlProxy.getStaticDisplayInfo(display.address.getPhysicalDisplayId()))
                .thenReturn(display.info);
        when(mSurfaceControlProxy.getDynamicDisplayInfo(display.address.getPhysicalDisplayId()))
                .thenReturn(display.dynamicInfo);
        when(mSurfaceControlProxy.getDesiredDisplayModeSpecs(display.token))
                .thenReturn(display.desiredDisplayModeSpecs);
    }

    private void updateAvailableDisplays() {
        long[] ids = new long[mAddresses.size()];
        int i = 0;
        for (DisplayAddress.Physical address : mAddresses) {
            ids[i] = address.getPhysicalDisplayId();
            i++;
        }
        when(mSurfaceControlProxy.getPhysicalDisplayIds()).thenReturn(ids);
    }

    private static DisplayAddress.Physical createDisplayAddress(int port) {
        return DisplayAddress.fromPortAndModel(port, DISPLAY_MODEL);
    }

    private static SurfaceControl.StaticDisplayInfo createFakeDisplayInfo() {
        final SurfaceControl.StaticDisplayInfo info = new SurfaceControl.StaticDisplayInfo();
        info.density = 100;
        return info;
    }

    private static SurfaceControl.DisplayMode createFakeDisplayMode(int id, int width, int height,
            float refreshRate) {
        return createFakeDisplayMode(id, width, height, refreshRate, refreshRate);
    }

    private static SurfaceControl.DisplayMode createFakeDisplayMode(int id, int width, int height,
                                                                   float refreshRate,
                                                                    float vsyncRate) {
        return createFakeDisplayMode(id, width, height, refreshRate, vsyncRate, /* group */ 0);
    }

    private static SurfaceControl.DisplayMode createFakeDisplayMode(int id, int width, int height,
                                                                    float refreshRate, int group) {
        return createFakeDisplayMode(id, width, height, refreshRate, refreshRate, group);
    }

    private static SurfaceControl.DisplayMode createFakeDisplayMode(int id, int width, int height,
            float refreshRate, float vsyncRate, int group) {
        final SurfaceControl.DisplayMode mode = new SurfaceControl.DisplayMode();
        mode.id = id;
        mode.width = width;
        mode.height = height;
        mode.peakRefreshRate = refreshRate;
        mode.vsyncRate = vsyncRate;
        mode.xDpi = 100;
        mode.yDpi = 100;
        mode.group = group;
        mode.supportedHdrTypes = HDR_TYPES;
        return mode;
    }

    private static void waitForHandlerToComplete(Handler handler, long waitTimeMs)
            throws InterruptedException {
        final CountDownLatch fence = new CountDownLatch(1);
        handler.post(fence::countDown);
        assertTrue(fence.await(waitTimeMs, TimeUnit.MILLISECONDS));
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

        @Override
        public LocalDisplayAdapter.SurfaceControlProxy getSurfaceControlProxy() {
            return mSurfaceControlProxy;
        }

        // Instead of using DisplayDeviceConfig.create(context, physicalDisplayId, isFirstDisplay)
        // we should use DisplayDeviceConfig.create(context, isFirstDisplay) for the test to ensure
        // that real device DisplayDeviceConfig is not loaded for FakeDisplay and we are getting
        // consistent behaviour. Please also note that context passed to this method, is
        // mMockContext and values will be loaded from mMockResources.
        @Override
        public DisplayDeviceConfig createDisplayDeviceConfig(Context context,
                long physicalDisplayId, boolean isFirstDisplay, DisplayManagerFlags flags) {
            return DisplayDeviceConfig.create(context, isFirstDisplay, flags);
        }
    }

    private class TestListener implements DisplayAdapter.Listener {
        public ArrayList<DisplayDevice> addedDisplays = new ArrayList<>();
        public ArrayList<DisplayDevice> changedDisplays = new ArrayList<>();
        public boolean traversalRequested = false;

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
            traversalRequested = true;
        }
    }

    private TypedArray createFloatTypedArray(float[] vals) {
        TypedArray mockArray = mock(TypedArray.class);
        when(mockArray.length()).thenAnswer(invocation -> {
            return vals.length;
        });
        when(mockArray.getFloat(anyInt(), anyFloat())).thenAnswer(invocation -> {
            final float def = (float) invocation.getArguments()[1];
            if (vals == null) {
                return def;
            }
            int idx = (int) invocation.getArguments()[0];
            if (idx >= 0 && idx < vals.length) {
                return vals[idx];
            } else {
                return def;
            }
        });
        return mockArray;
    }

    private boolean matches(Display.Mode a, SurfaceControl.DisplayMode b) {
        return a.getPhysicalWidth() == b.width && a.getPhysicalHeight() == b.height
                && Float.floatToIntBits(a.getRefreshRate())
                        == Float.floatToIntBits(b.peakRefreshRate)
                && Float.floatToIntBits(a.getVsyncRate())
                        == Float.floatToIntBits(b.vsyncRate);
    }
}
