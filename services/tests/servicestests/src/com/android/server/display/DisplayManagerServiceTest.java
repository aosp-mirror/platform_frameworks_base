/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.server.display.VirtualDisplayAdapter.UNIQUE_ID_PREFIX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PropertyInvalidatedCache;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.Curve;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayViewport;
import android.hardware.display.DisplayedContentSample;
import android.hardware.display.DisplayedContentSamplingAttributes;
import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.InputManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.MessageQueue;
import android.os.Process;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayEventReceiver;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.display.DisplayManagerService.SyncRoot;
import com.android.server.lights.LightsManager;
import com.android.server.sensors.SensorManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import com.google.common.collect.ImmutableMap;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DisplayManagerServiceTest {
    private static final int MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS = 1;
    private static final long SHORT_DEFAULT_DISPLAY_TIMEOUT_MILLIS = 10;
    private static final String VIRTUAL_DISPLAY_NAME = "Test Virtual Display";
    private static final String PACKAGE_NAME = "com.android.frameworks.servicestests";
    private static final long STANDARD_DISPLAY_EVENTS = DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                    | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private Context mContext;

    private final DisplayManagerService.Injector mShortMockedInjector =
            new DisplayManagerService.Injector() {
                @Override
                VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot,
                        Context context, Handler handler, DisplayAdapter.Listener listener) {
                    return mMockVirtualDisplayAdapter;
                }

                @Override
                long getDefaultDisplayDelayTimeout() {
                    return SHORT_DEFAULT_DISPLAY_TIMEOUT_MILLIS;
                }
            };

    class BasicInjector extends DisplayManagerService.Injector {
        @Override
        VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot, Context context,
                Handler handler, DisplayAdapter.Listener displayAdapterListener) {
            return new VirtualDisplayAdapter(syncRoot, context, handler, displayAdapterListener,
                    (String name, boolean secure) -> mMockDisplayToken);
        }
    }

    private final DisplayManagerService.Injector mBasicInjector = new BasicInjector();

    private final DisplayManagerService.Injector mAllowNonNativeRefreshRateOverrideInjector =
            new BasicInjector() {
                @Override
                boolean getAllowNonNativeRefreshRateOverride() {
                    return true;
                }
            };

    private final DisplayManagerService.Injector mDenyNonNativeRefreshRateOverrideInjector =
            new BasicInjector() {
                @Override
                boolean getAllowNonNativeRefreshRateOverride() {
                    return false;
                }
            };

    @Mock InputManagerInternal mMockInputManagerInternal;
    @Mock IVirtualDisplayCallback.Stub mMockAppToken;
    @Mock IVirtualDisplayCallback.Stub mMockAppToken2;
    @Mock WindowManagerInternal mMockWindowManagerInternal;
    @Mock LightsManager mMockLightsManager;
    @Mock VirtualDisplayAdapter mMockVirtualDisplayAdapter;
    @Mock IBinder mMockDisplayToken;
    @Mock SensorManagerInternal mMockSensorManagerInternal;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mMockInputManagerInternal);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mMockWindowManagerInternal);
        LocalServices.removeServiceForTest(LightsManager.class);
        LocalServices.addService(LightsManager.class, mMockLightsManager);
        LocalServices.removeServiceForTest(SensorManagerInternal.class);
        LocalServices.addService(SensorManagerInternal.class, mMockSensorManagerInternal);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();
    }

    @Test
    public void testCreateVirtualDisplay_sentToInputManager() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.systemReady(false /* safeMode */, true /* onlyCore */);
        displayManager.windowManagerAndInputReady();

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService bs = displayManager.new BinderService();

        String uniqueId = "uniqueId --- Test";
        String uniqueIdPrefix = UNIQUE_ID_PREFIX + mContext.getPackageName() + ":";
        int width = 600;
        int height = 800;
        int dpi = 320;
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH;

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi);
        builder.setUniqueId(uniqueId);
        builder.setFlags(flags);
        int displayId = bs.createVirtualDisplay(builder.build(), mMockAppToken /* callback */,
                null /* projection */, PACKAGE_NAME);

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        ArgumentCaptor<List<DisplayViewport>> viewportCaptor = ArgumentCaptor.forClass(List.class);
        verify(mMockInputManagerInternal).setDisplayViewports(viewportCaptor.capture());
        List<DisplayViewport> viewports = viewportCaptor.getValue();

        // Expect to receive at least 2 viewports: at least 1 internal, and 1 virtual
        assertTrue(viewports.size() >= 2);

        DisplayViewport virtualViewport = null;
        DisplayViewport internalViewport = null;
        for (int i = 0; i < viewports.size(); i++) {
            DisplayViewport v = viewports.get(i);
            switch (v.type) {
                case DisplayViewport.VIEWPORT_INTERNAL: {
                    // If more than one internal viewport, this will get overwritten several times,
                    // which for the purposes of this test is fine.
                    internalViewport = v;
                    assertTrue(internalViewport.valid);
                    break;
                }
                case DisplayViewport.VIEWPORT_EXTERNAL: {
                    // External view port is present for auto devices in the form of instrument
                    // cluster.
                    break;
                }
                case DisplayViewport.VIEWPORT_VIRTUAL: {
                    virtualViewport = v;
                    break;
                }
            }
        }
        // INTERNAL viewport gets created upon access.
        assertNotNull(internalViewport);
        assertNotNull(virtualViewport);

        // VIRTUAL
        assertEquals(height, virtualViewport.deviceHeight);
        assertEquals(width, virtualViewport.deviceWidth);
        assertEquals(uniqueIdPrefix + uniqueId, virtualViewport.uniqueId);
        assertEquals(displayId, virtualViewport.displayId);
    }

    @Test
    public void testPhysicalViewports() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.systemReady(false /* safeMode */, true /* onlyCore */);
        displayManager.windowManagerAndInputReady();

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService bs = displayManager.new BinderService();

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        final int displayIds[] = bs.getDisplayIds();
        final int size = displayIds.length;
        assertTrue(size > 0);

        Map<Integer, Integer> expectedDisplayTypeToViewPortTypeMapping = ImmutableMap.of(
                Display.TYPE_INTERNAL, DisplayViewport.VIEWPORT_INTERNAL,
                Display.TYPE_EXTERNAL, DisplayViewport.VIEWPORT_EXTERNAL
        );
        for (int i = 0; i < size; i++) {
            DisplayInfo info = bs.getDisplayInfo(displayIds[i]);
            assertTrue(expectedDisplayTypeToViewPortTypeMapping.keySet().contains(info.type));
        }

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        ArgumentCaptor<List<DisplayViewport>> viewportCaptor = ArgumentCaptor.forClass(List.class);
        verify(mMockInputManagerInternal).setDisplayViewports(viewportCaptor.capture());
        List<DisplayViewport> viewports = viewportCaptor.getValue();

        // Due to the nature of foldables, we may have a different number of viewports than
        // displays, just verify there's at least one.
        final int viewportSize = viewports.size();
        assertTrue(viewportSize > 0);

        // Now verify that each viewport's displayId is valid.
        Arrays.sort(displayIds);
        for (int i = 0; i < viewportSize; i++) {
            DisplayViewport viewport = viewports.get(i);
            assertNotNull(viewport);
            DisplayInfo displayInfo = bs.getDisplayInfo(viewport.displayId);
            assertTrue(expectedDisplayTypeToViewPortTypeMapping.get(displayInfo.type)
                    == viewport.type);
            assertTrue(viewport.valid);
            assertTrue(Arrays.binarySearch(displayIds, viewport.displayId) >= 0);
        }
    }

    @Test
    public void testCreateVirtualDisplayRotatesWithContent() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService bs = displayManager.new BinderService();

        String uniqueId = "uniqueId --- Rotates With Content Test";
        int width = 600;
        int height = 800;
        int dpi = 320;
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi);
        builder.setFlags(flags);
        builder.setUniqueId(uniqueId);
        int displayId = bs.createVirtualDisplay(builder.build(), mMockAppToken /* callback */,
                null /* projection */, PACKAGE_NAME);

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        DisplayDeviceInfo ddi = displayManager.getDisplayDeviceInfoInternal(displayId);
        assertNotNull(ddi);
        assertTrue((ddi.flags & DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT) != 0);
    }

    /**
     * Tests that the virtual display is created along-side the default display.
     */
    @Test
    public void testStartVirtualDisplayWithDefaultDisplay_Succeeds() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
    }

    /**
     * Tests that there should be a display change notification to WindowManager to update its own
     * internal state for things like display cutout when nonOverrideDisplayInfo is changed.
     */
    @Test
    public void testShouldNotifyChangeWhenNonOverrideDisplayInfoChanged() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

        // Add the FakeDisplayDevice
        FakeDisplayDevice displayDevice = new FakeDisplayDevice();
        DisplayDeviceInfo displayDeviceInfo = new DisplayDeviceInfo();
        displayDeviceInfo.width = 100;
        displayDeviceInfo.height = 200;
        displayDeviceInfo.supportedModes = new Display.Mode[1];
        displayDeviceInfo.supportedModes[0] = new Display.Mode(1, 100, 200, 60f);
        displayDeviceInfo.modeId = 1;
        final Rect zeroRect = new Rect();
        displayDeviceInfo.displayCutout = new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                zeroRect, new Rect(0, 0, 10, 10), zeroRect, zeroRect);
        displayDeviceInfo.flags = DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY;
        displayDevice.setDisplayDeviceInfo(displayDeviceInfo);
        displayManager.getDisplayDeviceRepository()
                .onDisplayDeviceEvent(displayDevice, DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED);

        // Find the display id of the added FakeDisplayDevice
        DisplayManagerService.BinderService bs = displayManager.new BinderService();
        int displayId = getDisplayIdForDisplayDevice(displayManager, bs, displayDevice);
        // Setup override DisplayInfo
        DisplayInfo overrideInfo = bs.getDisplayInfo(displayId);
        displayManager.setDisplayInfoOverrideFromWindowManagerInternal(displayId, overrideInfo);

        FakeDisplayManagerCallback callback = registerDisplayListenerCallback(
                displayManager, bs, displayDevice);

        // Simulate DisplayDevice change
        DisplayDeviceInfo displayDeviceInfo2 = new DisplayDeviceInfo();
        displayDeviceInfo2.copyFrom(displayDeviceInfo);
        displayDeviceInfo2.displayCutout = null;
        displayDevice.setDisplayDeviceInfo(displayDeviceInfo2);
        displayManager.getDisplayDeviceRepository()
                .onDisplayDeviceEvent(displayDevice, DisplayAdapter.DISPLAY_DEVICE_EVENT_CHANGED);

        Handler handler = displayManager.getDisplayHandler();
        waitForIdleHandler(handler);
        assertTrue(callback.mDisplayChangedCalled);
    }

    /**
     * Tests that we get a Runtime exception when we cannot initialize the default display.
     */
    @Test
    public void testStartVirtualDisplayWithDefDisplay_NoDefaultDisplay() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        Handler handler = displayManager.getDisplayHandler();
        waitForIdleHandler(handler);

        try {
            displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
        } catch (RuntimeException e) {
            return;
        }
        fail("Expected DisplayManager to throw RuntimeException when it cannot initialize the"
                + " default display");
    }

    /**
     * Tests that we get a Runtime exception when we cannot initialize the virtual display.
     */
    @Test
    public void testStartVirtualDisplayWithDefDisplay_NoVirtualDisplayAdapter() throws Exception {
        DisplayManagerService displayManager = new DisplayManagerService(mContext,
                new DisplayManagerService.Injector() {
                    @Override
                    VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot,
                            Context context, Handler handler, DisplayAdapter.Listener listener) {
                        return null;  // return null for the adapter.  This should cause a failure.
                    }

                    @Override
                    long getDefaultDisplayDelayTimeout() {
                        return SHORT_DEFAULT_DISPLAY_TIMEOUT_MILLIS;
                    }
                });
        try {
            displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
        } catch (RuntimeException e) {
            return;
        }
        fail("Expected DisplayManager to throw RuntimeException when it cannot initialize the"
                + " virtual display adapter");
    }

    /**
     * Tests that an exception is raised for too dark a brightness configuration.
     */
    @Test
    public void testTooDarkBrightnessConfigurationThrowException() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        Curve minimumBrightnessCurve = displayManager.getMinimumBrightnessCurveInternal();
        float[] lux = minimumBrightnessCurve.getX();
        float[] minimumNits = minimumBrightnessCurve.getY();
        float[] nits = new float[minimumNits.length];
        // For every control point, assert that making it slightly lower than the minimum throws an
        // exception.
        for (int i = 0; i < nits.length; i++) {
            for (int j = 0; j < nits.length; j++) {
                nits[j] = minimumNits[j];
                if (j == i) {
                    nits[j] -= 0.1f;
                }
                if (nits[j] < 0) {
                    nits[j] = 0;
                }
            }
            BrightnessConfiguration config =
                    new BrightnessConfiguration.Builder(lux, nits).build();
            Exception thrown = null;
            try {
                displayManager.validateBrightnessConfiguration(config);
            } catch (IllegalArgumentException e) {
                thrown = e;
            }
            assertNotNull("Building too dark a brightness configuration must throw an exception");
        }
    }

    /**
     * Tests that no exception is raised for not too dark a brightness configuration.
     */
    @Test
    public void testBrightEnoughBrightnessConfigurationDoesNotThrowException() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        Curve minimumBrightnessCurve = displayManager.getMinimumBrightnessCurveInternal();
        float[] lux = minimumBrightnessCurve.getX();
        float[] nits = minimumBrightnessCurve.getY();
        BrightnessConfiguration config = new BrightnessConfiguration.Builder(lux, nits).build();
        displayManager.validateBrightnessConfiguration(config);
    }

    /**
     * Tests that null brightness configurations are alright.
     */
    @Test
    public void testNullBrightnessConfiguration() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        displayManager.validateBrightnessConfiguration(null);
    }

    /**
     * Tests that collection of display color sampling results are sensible.
     */
    @Test
    public void testDisplayedContentSampling() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        registerDefaultDisplays(displayManager);

        DisplayDeviceInfo ddi = displayManager.getDisplayDeviceInfoInternal(0);
        assertNotNull(ddi);

        DisplayedContentSamplingAttributes attr =
                displayManager.getDisplayedContentSamplingAttributesInternal(0);
        if (attr == null) return; //sampling not supported on device, skip remainder of test.

        boolean enabled = displayManager.setDisplayedContentSamplingEnabledInternal(0, true, 0, 0);
        assertTrue(enabled);

        displayManager.setDisplayedContentSamplingEnabledInternal(0, false, 0, 0);
        DisplayedContentSample sample = displayManager.getDisplayedContentSampleInternal(0, 0, 0);
        assertNotNull(sample);

        long numPixels = ddi.width * ddi.height * sample.getNumFrames();
        long[] samples = sample.getSampleComponent(DisplayedContentSample.ColorComponent.CHANNEL0);
        assertTrue(samples.length == 0 || LongStream.of(samples).sum() == numPixels);

        samples = sample.getSampleComponent(DisplayedContentSample.ColorComponent.CHANNEL1);
        assertTrue(samples.length == 0 || LongStream.of(samples).sum() == numPixels);

        samples = sample.getSampleComponent(DisplayedContentSample.ColorComponent.CHANNEL2);
        assertTrue(samples.length == 0 || LongStream.of(samples).sum() == numPixels);

        samples = sample.getSampleComponent(DisplayedContentSample.ColorComponent.CHANNEL3);
        assertTrue(samples.length == 0 || LongStream.of(samples).sum() == numPixels);
    }

    /**
     * Tests that the virtual display is created with
     * {@link VirtualDisplayConfig.Builder#setDisplayIdToMirror(int)}
     */
    @Test
    @FlakyTest(bugId = 127687569)
    public void testCreateVirtualDisplay_displayIdToMirror() throws Exception {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService binderService = displayManager.new BinderService();

        final String uniqueId = "uniqueId --- displayIdToMirrorTest";
        final int width = 600;
        final int height = 800;
        final int dpi = 320;

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi);
        builder.setUniqueId(uniqueId);
        final int firstDisplayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, null /* projection */, PACKAGE_NAME);

        // The second virtual display requests to mirror the first virtual display.
        final String uniqueId2 = "uniqueId --- displayIdToMirrorTest #2";
        when(mMockAppToken2.asBinder()).thenReturn(mMockAppToken2);
        final VirtualDisplayConfig.Builder builder2 = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi).setUniqueId(uniqueId2);
        builder2.setUniqueId(uniqueId2);
        builder2.setDisplayIdToMirror(firstDisplayId);
        final int secondDisplayId = binderService.createVirtualDisplay(builder2.build(),
                mMockAppToken2 /* callback */, null /* projection */, PACKAGE_NAME);
        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        // The displayId to mirror should be a default display if there is none initially.
        assertEquals(displayManager.getDisplayIdToMirrorInternal(firstDisplayId),
                Display.DEFAULT_DISPLAY);
        assertEquals(displayManager.getDisplayIdToMirrorInternal(secondDisplayId),
                firstDisplayId);
    }

    /**
     * Tests that the virtual display is created with
     * {@link VirtualDisplayConfig.Builder#setSurface(Surface)}
     */
    @Test
    @FlakyTest(bugId = 127687569)
    public void testCreateVirtualDisplay_setSurface() throws Exception {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService binderService = displayManager.new BinderService();

        final String uniqueId = "uniqueId --- setSurface";
        final int width = 600;
        final int height = 800;
        final int dpi = 320;
        final Surface surface = new Surface();

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi);
        builder.setSurface(surface);
        builder.setUniqueId(uniqueId);
        final int displayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, null /* projection */, PACKAGE_NAME);

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        assertEquals(displayManager.getVirtualDisplaySurfaceInternal(mMockAppToken), surface);
    }

    /**
     * Tests that there is a display change notification if the frame rate override
     * list is updated.
     */
    @Test
    public void testShouldNotifyChangeWhenDisplayInfoFrameRateOverrideChanged() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager, new float[]{60f});
        FakeDisplayManagerCallback callback = registerDisplayListenerCallback(displayManager,
                displayManagerBinderService, displayDevice);

        int myUid = Process.myUid();
        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(myUid, 30f),
                });
        assertTrue(callback.mDisplayChangedCalled);
        callback.clear();

        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(myUid, 30f),
                        new DisplayEventReceiver.FrameRateOverride(1234, 30f),
                });
        assertFalse(callback.mDisplayChangedCalled);

        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(myUid, 20f),
                        new DisplayEventReceiver.FrameRateOverride(1234, 30f),
                        new DisplayEventReceiver.FrameRateOverride(5678, 30f),
                });
        assertTrue(callback.mDisplayChangedCalled);
        callback.clear();

        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(1234, 30f),
                        new DisplayEventReceiver.FrameRateOverride(5678, 30f),
                });
        assertTrue(callback.mDisplayChangedCalled);
        callback.clear();

        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(5678, 30f),
                });
        assertFalse(callback.mDisplayChangedCalled);
    }

    /**
     * Tests that the DisplayInfo is updated correctly with a frame rate override
     */
    @Test
    public void testDisplayInfoFrameRateOverride() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager,
                new float[]{60f, 30f, 20f});
        int displayId = getDisplayIdForDisplayDevice(displayManager, displayManagerBinderService,
                displayDevice);
        DisplayInfo displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        assertEquals(60f, displayInfo.getRefreshRate(), 0.01f);

        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(
                                Process.myUid(), 20f),
                        new DisplayEventReceiver.FrameRateOverride(
                                Process.myUid() + 1, 30f)
                });
        displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        assertEquals(20f, displayInfo.getRefreshRate(), 0.01f);

        // Changing the mode to 30Hz should not override the refresh rate to 20Hz anymore
        // as 20 is not a divider of 30.
        updateModeId(displayManager, displayDevice, 2);
        displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        assertEquals(30f, displayInfo.getRefreshRate(), 0.01f);
    }

    /**
     * Tests that the frame rate override is updated accordingly to the
     * allowNonNativeRefreshRateOverride policy.
     */
    @Test
    public void testDisplayInfoNonNativeFrameRateOverride() throws Exception {
        testDisplayInfoNonNativeFrameRateOverride(mDenyNonNativeRefreshRateOverrideInjector);
        testDisplayInfoNonNativeFrameRateOverride(mAllowNonNativeRefreshRateOverrideInjector);
    }

    /**
     * Tests that the mode reflects the frame rate override is in compat mode
     */
    @Test
    @DisableCompatChanges({DisplayManagerService.DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE})
    public  void testDisplayInfoFrameRateOverrideModeCompat() throws Exception {
        testDisplayInfoFrameRateOverrideModeCompat(/*compatChangeEnabled*/ false);
    }

    /**
     * Tests that the mode reflects the physical display refresh rate when not in compat mode.
     */
    @Test
    @EnableCompatChanges({DisplayManagerService.DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE})
    public  void testDisplayInfoFrameRateOverrideMode() throws Exception {
        testDisplayInfoFrameRateOverrideModeCompat(/*compatChangeEnabled*/ true);
    }

    /**
     * Tests that the mode reflects the frame rate override is in compat mode and accordingly to the
     * allowNonNativeRefreshRateOverride policy.
     */
    @Test
    @DisableCompatChanges({DisplayManagerService.DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE})
    public void testDisplayInfoNonNativeFrameRateOverrideModeCompat() throws Exception {
        testDisplayInfoNonNativeFrameRateOverrideMode(mDenyNonNativeRefreshRateOverrideInjector,
                /*compatChangeEnabled*/ false);
        testDisplayInfoNonNativeFrameRateOverrideMode(mAllowNonNativeRefreshRateOverrideInjector,
                /*compatChangeEnabled*/  false);
    }

    /**
     * Tests that the mode reflects the physical display refresh rate when not in compat mode.
     */
    @Test
    @EnableCompatChanges({DisplayManagerService.DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE})
    public void testDisplayInfoNonNativeFrameRateOverrideMode() throws Exception {
        testDisplayInfoNonNativeFrameRateOverrideMode(mDenyNonNativeRefreshRateOverrideInjector,
                /*compatChangeEnabled*/  true);
        testDisplayInfoNonNativeFrameRateOverrideMode(mAllowNonNativeRefreshRateOverrideInjector,
                /*compatChangeEnabled*/  true);
    }

    /**
     * Tests that EVENT_DISPLAY_ADDED is sent when a display is added.
     */
    @Test
    public void testShouldNotifyDisplayAdded_WhenNewDisplayDeviceIsAdded() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();

        Handler handler = displayManager.getDisplayHandler();
        waitForIdleHandler(handler);

        // register display listener callback
        FakeDisplayManagerCallback callback = new FakeDisplayManagerCallback();
        displayManagerBinderService.registerCallbackWithEventMask(
                callback, STANDARD_DISPLAY_EVENTS);

        waitForIdleHandler(handler);

        createFakeDisplayDevice(displayManager, new float[]{60f});

        waitForIdleHandler(handler);

        assertFalse(callback.mDisplayChangedCalled);
        assertFalse(callback.mDisplayRemovedCalled);
        assertTrue(callback.mDisplayAddedCalled);
    }

    /**
     * Tests that EVENT_DISPLAY_ADDED is not sent when a display is added and the
     * client has a callback which is not subscribed to this event type.
     */
    @Test
    public void testShouldNotNotifyDisplayAdded_WhenClientIsNotSubscribed() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();

        Handler handler = displayManager.getDisplayHandler();
        waitForIdleHandler(handler);

        // register display listener callback
        FakeDisplayManagerCallback callback = new FakeDisplayManagerCallback();
        long allEventsExceptDisplayAdded = STANDARD_DISPLAY_EVENTS
                & ~DisplayManager.EVENT_FLAG_DISPLAY_ADDED;
        displayManagerBinderService.registerCallbackWithEventMask(callback,
                allEventsExceptDisplayAdded);

        waitForIdleHandler(handler);

        createFakeDisplayDevice(displayManager, new float[]{60f});

        waitForIdleHandler(handler);

        assertFalse(callback.mDisplayChangedCalled);
        assertFalse(callback.mDisplayRemovedCalled);
        assertFalse(callback.mDisplayAddedCalled);
    }

    /**
     * Tests that EVENT_DISPLAY_REMOVED is sent when a display is removed.
     */
    @Test
    public void testShouldNotifyDisplayRemoved_WhenDisplayDeviceIsRemoved() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();

        Handler handler = displayManager.getDisplayHandler();
        waitForIdleHandler(handler);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager,
                new float[]{60f});

        waitForIdleHandler(handler);

        FakeDisplayManagerCallback callback = registerDisplayListenerCallback(
                displayManager, displayManagerBinderService, displayDevice);

        waitForIdleHandler(handler);

        displayManager.getDisplayDeviceRepository()
                .onDisplayDeviceEvent(displayDevice, DisplayAdapter.DISPLAY_DEVICE_EVENT_REMOVED);

        waitForIdleHandler(handler);

        assertFalse(callback.mDisplayChangedCalled);
        assertTrue(callback.mDisplayRemovedCalled);
        assertFalse(callback.mDisplayAddedCalled);
    }

    /**
     * Tests that EVENT_DISPLAY_REMOVED is not sent when a display is added and the
     * client has a callback which is not subscribed to this event type.
     */
    @Test
    public void testShouldNotNotifyDisplayRemoved_WhenClientIsNotSubscribed() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();

        Handler handler = displayManager.getDisplayHandler();
        waitForIdleHandler(handler);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager,
                new float[]{60f});

        waitForIdleHandler(handler);

        FakeDisplayManagerCallback callback = new FakeDisplayManagerCallback();
        long allEventsExceptDisplayRemoved = STANDARD_DISPLAY_EVENTS
                & ~DisplayManager.EVENT_FLAG_DISPLAY_REMOVED;
        displayManagerBinderService.registerCallbackWithEventMask(callback,
                allEventsExceptDisplayRemoved);

        waitForIdleHandler(handler);

        displayManager.getDisplayDeviceRepository()
                .onDisplayDeviceEvent(displayDevice, DisplayAdapter.DISPLAY_DEVICE_EVENT_REMOVED);

        waitForIdleHandler(handler);

        assertFalse(callback.mDisplayChangedCalled);
        assertFalse(callback.mDisplayRemovedCalled);
        assertFalse(callback.mDisplayAddedCalled);
    }

    private void testDisplayInfoFrameRateOverrideModeCompat(boolean compatChangeEnabled)
            throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager,
                new float[]{60f, 30f, 20f});
        int displayId = getDisplayIdForDisplayDevice(displayManager, displayManagerBinderService,
                displayDevice);
        DisplayInfo displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        assertEquals(60f, displayInfo.getRefreshRate(), 0.01f);

        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(
                                Process.myUid(), 20f),
                        new DisplayEventReceiver.FrameRateOverride(
                                Process.myUid() + 1, 30f)
                });
        displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        assertEquals(20f, displayInfo.getRefreshRate(), 0.01f);
        Display.Mode expectedMode;
        if (compatChangeEnabled) {
            expectedMode = new Display.Mode(1, 100, 200, 60f);
        } else {
            expectedMode = new Display.Mode(3, 100, 200, 20f);
        }
        assertEquals(expectedMode, displayInfo.getMode());
    }

    private void testDisplayInfoNonNativeFrameRateOverrideMode(
            DisplayManagerService.Injector injector, boolean compatChangeEnabled) {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, injector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager,
                new float[]{60f});
        int displayId = getDisplayIdForDisplayDevice(displayManager, displayManagerBinderService,
                displayDevice);
        DisplayInfo displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        assertEquals(60f, displayInfo.getRefreshRate(), 0.01f);

        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(
                                Process.myUid(), 20f)
                });
        displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        Display.Mode expectedMode;
        if (compatChangeEnabled) {
            expectedMode = new Display.Mode(1, 100, 200, 60f);
        } else if (injector.getAllowNonNativeRefreshRateOverride()) {
            expectedMode = new Display.Mode(255, 100, 200, 20f);
        } else {
            expectedMode = new Display.Mode(1, 100, 200, 60f);
        }
        assertEquals(expectedMode, displayInfo.getMode());
    }

    private void testDisplayInfoNonNativeFrameRateOverride(
            DisplayManagerService.Injector injector) {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, injector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager,
                new float[]{60f});
        int displayId = getDisplayIdForDisplayDevice(displayManager, displayManagerBinderService,
                displayDevice);
        DisplayInfo displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        assertEquals(60f, displayInfo.getRefreshRate(), 0.01f);

        updateFrameRateOverride(displayManager, displayDevice,
                new DisplayEventReceiver.FrameRateOverride[]{
                        new DisplayEventReceiver.FrameRateOverride(
                                Process.myUid(), 20f)
                });
        displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        float expectedRefreshRate = injector.getAllowNonNativeRefreshRateOverride() ? 20f : 60f;
        assertEquals(expectedRefreshRate, displayInfo.getRefreshRate(), 0.01f);
    }

    private int getDisplayIdForDisplayDevice(
            DisplayManagerService displayManager,
            DisplayManagerService.BinderService displayManagerBinderService,
            FakeDisplayDevice displayDevice) {

        final int[] displayIds = displayManagerBinderService.getDisplayIds();
        assertTrue(displayIds.length > 0);
        int displayId = Display.INVALID_DISPLAY;
        for (int i = 0; i < displayIds.length; i++) {
            DisplayDeviceInfo ddi = displayManager.getDisplayDeviceInfoInternal(displayIds[i]);
            if (displayDevice.getDisplayDeviceInfoLocked().equals(ddi)) {
                displayId = displayIds[i];
                break;
            }
        }
        assertFalse(displayId == Display.INVALID_DISPLAY);
        return displayId;
    }

    private void updateDisplayDeviceInfo(DisplayManagerService displayManager,
            FakeDisplayDevice displayDevice,
            DisplayDeviceInfo displayDeviceInfo) {
        displayDevice.setDisplayDeviceInfo(displayDeviceInfo);
        displayManager.getDisplayDeviceRepository()
                .onDisplayDeviceEvent(displayDevice, DisplayAdapter.DISPLAY_DEVICE_EVENT_CHANGED);
        Handler handler = displayManager.getDisplayHandler();
        waitForIdleHandler(handler);
    }

    private void updateFrameRateOverride(DisplayManagerService displayManager,
            FakeDisplayDevice displayDevice,
            DisplayEventReceiver.FrameRateOverride[] frameRateOverrides) {
        DisplayDeviceInfo displayDeviceInfo = new DisplayDeviceInfo();
        displayDeviceInfo.copyFrom(displayDevice.getDisplayDeviceInfoLocked());
        displayDeviceInfo.frameRateOverrides = frameRateOverrides;
        updateDisplayDeviceInfo(displayManager, displayDevice, displayDeviceInfo);
    }

    private void updateModeId(DisplayManagerService displayManager,
            FakeDisplayDevice displayDevice,
            int modeId) {
        DisplayDeviceInfo displayDeviceInfo = new DisplayDeviceInfo();
        displayDeviceInfo.copyFrom(displayDevice.getDisplayDeviceInfoLocked());
        displayDeviceInfo.modeId = modeId;
        updateDisplayDeviceInfo(displayManager, displayDevice, displayDeviceInfo);
    }

    private FakeDisplayManagerCallback registerDisplayListenerCallback(
            DisplayManagerService displayManager,
            DisplayManagerService.BinderService displayManagerBinderService,
            FakeDisplayDevice displayDevice) {
        // Find the display id of the added FakeDisplayDevice
        int displayId = getDisplayIdForDisplayDevice(displayManager, displayManagerBinderService,
                displayDevice);

        Handler handler = displayManager.getDisplayHandler();
        waitForIdleHandler(handler);

        // register display listener callback
        FakeDisplayManagerCallback callback = new FakeDisplayManagerCallback(displayId);
        displayManagerBinderService.registerCallbackWithEventMask(
                callback, STANDARD_DISPLAY_EVENTS);
        return callback;
    }

    private FakeDisplayDevice createFakeDisplayDevice(DisplayManagerService displayManager,
            float[] refreshRates) {
        FakeDisplayDevice displayDevice = new FakeDisplayDevice();
        DisplayDeviceInfo displayDeviceInfo = new DisplayDeviceInfo();
        int width = 100;
        int height = 200;
        displayDeviceInfo.supportedModes = new Display.Mode[refreshRates.length];
        for (int i = 0; i < refreshRates.length; i++) {
            displayDeviceInfo.supportedModes[i] =
                    new Display.Mode(i + 1, width, height, refreshRates[i]);
        }
        displayDeviceInfo.modeId = 1;
        displayDeviceInfo.width = width;
        displayDeviceInfo.height = height;
        final Rect zeroRect = new Rect();
        displayDeviceInfo.displayCutout = new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                zeroRect, new Rect(0, 0, 10, 10), zeroRect, zeroRect);
        displayDeviceInfo.flags = DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY;
        displayDevice.setDisplayDeviceInfo(displayDeviceInfo);
        displayManager.getDisplayDeviceRepository()
                .onDisplayDeviceEvent(displayDevice, DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED);
        return displayDevice;
    }

    private void registerDefaultDisplays(DisplayManagerService displayManager) {
        Handler handler = displayManager.getDisplayHandler();
        // Would prefer to call displayManager.onStart() directly here but it performs binderService
        // registration which triggers security exceptions when running from a test.
        handler.sendEmptyMessage(MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS);
        waitForIdleHandler(handler);
    }

    private void waitForIdleHandler(Handler handler) {
        waitForIdleHandler(handler, Duration.ofSeconds(1));
    }

    private void waitForIdleHandler(Handler handler, Duration timeout) {
        final MessageQueue queue = handler.getLooper().getQueue();
        final CountDownLatch latch = new CountDownLatch(1);
        queue.addIdleHandler(() -> {
            latch.countDown();
            // Remove idle handler
            return false;
        });
        try {
            latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail("Interrupted unexpectedly: " + e);
        }
    }

    private class FakeDisplayManagerCallback extends IDisplayManagerCallback.Stub {
        int mDisplayId;
        boolean mDisplayAddedCalled = false;
        boolean mDisplayChangedCalled = false;
        boolean mDisplayRemovedCalled = false;

        FakeDisplayManagerCallback(int displayId) {
            mDisplayId = displayId;
        }

        FakeDisplayManagerCallback() {
            mDisplayId = -1;
        }

        @Override
        public void onDisplayEvent(int displayId, int event) {
            if (mDisplayId != -1 && displayId != mDisplayId) {
                return;
            }

            if (event == DisplayManagerGlobal.EVENT_DISPLAY_ADDED) {
                mDisplayAddedCalled = true;
            }

            if (event == DisplayManagerGlobal.EVENT_DISPLAY_CHANGED) {
                mDisplayChangedCalled = true;
            }

            if (event == DisplayManagerGlobal.EVENT_DISPLAY_REMOVED) {
                mDisplayRemovedCalled = true;
            }
        }

        public void clear() {
            mDisplayAddedCalled = false;
            mDisplayChangedCalled = false;
            mDisplayRemovedCalled = false;
        }
    }

    private class FakeDisplayDevice extends DisplayDevice {
        private DisplayDeviceInfo mDisplayDeviceInfo;

        FakeDisplayDevice() {
            super(null, null, "", mContext);
        }

        public void setDisplayDeviceInfo(DisplayDeviceInfo displayDeviceInfo) {
            mDisplayDeviceInfo = displayDeviceInfo;
        }

        @Override
        public boolean hasStableUniqueId() {
            return false;
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            return mDisplayDeviceInfo;
        }
    }
}
