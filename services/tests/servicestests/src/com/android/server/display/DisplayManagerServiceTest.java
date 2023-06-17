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

import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
import static android.view.ContentRecordingSession.RECORD_CONTENT_DISPLAY;
import static android.view.ContentRecordingSession.RECORD_CONTENT_TASK;

import static com.android.server.display.VirtualDisplayAdapter.UNIQUE_ID_PREFIX;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PropertyInvalidatedCache;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.Curve;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayViewport;
import android.hardware.display.DisplayedContentSample;
import android.hardware.display.DisplayedContentSamplingAttributes;
import android.hardware.display.HdrConversionMode;
import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.MessageQueue;
import android.os.Process;
import android.os.RemoteException;
import android.view.ContentRecordingSession;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayEventReceiver;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.DisplayWindowPolicyController;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.display.DisplayManagerService.DeviceStateListener;
import com.android.server.display.DisplayManagerService.SyncRoot;
import com.android.server.input.InputManagerInternal;
import com.android.server.lights.LightsManager;
import com.android.server.sensors.SensorManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DisplayManagerServiceTest {
    private static final int MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS = 1;
    private static final long SHORT_DEFAULT_DISPLAY_TIMEOUT_MILLIS = 10;

    private static final float FLOAT_TOLERANCE = 0.01f;

    private static final String VIRTUAL_DISPLAY_NAME = "Test Virtual Display";
    private static final String PACKAGE_NAME = "com.android.frameworks.servicestests";
    private static final long STANDARD_DISPLAY_EVENTS = DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                    | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private Context mContext;

    private int mHdrConversionMode;

    private int mPreferredHdrOutputType;

    private final DisplayManagerService.Injector mShortMockedInjector =
            new DisplayManagerService.Injector() {
                @Override
                VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot,
                        Context context, Handler handler, DisplayAdapter.Listener listener) {
                    return mMockVirtualDisplayAdapter;
                }

                @Override
                LocalDisplayAdapter getLocalDisplayAdapter(SyncRoot syncRoot, Context context,
                        Handler handler, DisplayAdapter.Listener displayAdapterListener) {
                    return new LocalDisplayAdapter(syncRoot, context, handler,
                            displayAdapterListener, new LocalDisplayAdapter.Injector() {
                        @Override
                        public LocalDisplayAdapter.SurfaceControlProxy getSurfaceControlProxy() {
                            return mSurfaceControlProxy;
                        }
                    });
                }

                @Override
                long getDefaultDisplayDelayTimeout() {
                    return SHORT_DEFAULT_DISPLAY_TIMEOUT_MILLIS;
                }
            };

    class BasicInjector extends DisplayManagerService.Injector {
        @Override
        IMediaProjectionManager getProjectionService() {
            return mMockProjectionService;
        }

        @Override
        VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot, Context context,
                Handler handler, DisplayAdapter.Listener displayAdapterListener) {
            return new VirtualDisplayAdapter(syncRoot, context, handler, displayAdapterListener,
                    new VirtualDisplayAdapter.SurfaceControlDisplayFactory() {
                        @Override
                        public IBinder createDisplay(String name, boolean secure,
                                float requestedRefreshRate) {
                            return mMockDisplayToken;
                        }

                        @Override
                        public void destroyDisplay(IBinder displayToken) {
                        }
                    });
        }

        @Override
        LocalDisplayAdapter getLocalDisplayAdapter(SyncRoot syncRoot, Context context,
                Handler handler, DisplayAdapter.Listener displayAdapterListener) {
            return new LocalDisplayAdapter(syncRoot, context, handler,
                    displayAdapterListener, new LocalDisplayAdapter.Injector() {
                        @Override
                        public LocalDisplayAdapter.SurfaceControlProxy getSurfaceControlProxy() {
                            return mSurfaceControlProxy;
                        }
                    });
        }

        @Override
        int setHdrConversionMode(int conversionMode, int preferredHdrOutputType,
                int[] autoHdrTypes) {
            mHdrConversionMode = conversionMode;
            mPreferredHdrOutputType = preferredHdrOutputType;
            return Display.HdrCapabilities.HDR_TYPE_INVALID;
        }

        @Override
        int[] getSupportedHdrOutputTypes() {
            return new int[]{};
        }

        @Override
        int[] getHdrOutputTypesWithLatency() {
            return new int[]{Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION};
        }

        boolean getHdrOutputConversionSupport() {
            return true;
        }
   }

    private final DisplayManagerService.Injector mBasicInjector = new BasicInjector();

    @Mock IMediaProjectionManager mMockProjectionService;
    @Mock IVirtualDeviceManager mIVirtualDeviceManager;
    @Mock InputManagerInternal mMockInputManagerInternal;
    @Mock VirtualDeviceManagerInternal mMockVirtualDeviceManagerInternal;
    @Mock IVirtualDisplayCallback.Stub mMockAppToken;
    @Mock IVirtualDisplayCallback.Stub mMockAppToken2;
    @Mock WindowManagerInternal mMockWindowManagerInternal;
    @Mock LightsManager mMockLightsManager;
    @Mock VirtualDisplayAdapter mMockVirtualDisplayAdapter;
    @Mock LocalDisplayAdapter.SurfaceControlProxy mSurfaceControlProxy;
    @Mock IBinder mMockDisplayToken;
    @Mock SensorManagerInternal mMockSensorManagerInternal;

    @Mock SensorManager mSensorManager;

    @Mock DisplayDeviceConfig mMockDisplayDeviceConfig;

    @Captor ArgumentCaptor<ContentRecordingSession> mContentRecordingSessionCaptor;

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
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        LocalServices.addService(
                VirtualDeviceManagerInternal.class, mMockVirtualDeviceManagerInternal);
        // TODO: b/287945043
        mContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));

        VirtualDeviceManager vdm = new VirtualDeviceManager(mIVirtualDeviceManager, mContext);
        when(mContext.getSystemService(VirtualDeviceManager.class)).thenReturn(vdm);
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();
        setUpDisplay();
    }

    private void setUpDisplay() {
        long[] ids = new long[] {100};
        when(mSurfaceControlProxy.getPhysicalDisplayIds()).thenReturn(ids);
        when(mSurfaceControlProxy.getPhysicalDisplayToken(anyLong()))
                .thenReturn(mMockDisplayToken);
        SurfaceControl.StaticDisplayInfo staticDisplayInfo = new SurfaceControl.StaticDisplayInfo();
        staticDisplayInfo.isInternal = true;
        when(mSurfaceControlProxy.getStaticDisplayInfo(anyLong()))
                .thenReturn(staticDisplayInfo);
        SurfaceControl.DynamicDisplayInfo dynamicDisplayMode =
                new SurfaceControl.DynamicDisplayInfo();
        SurfaceControl.DisplayMode displayMode = new SurfaceControl.DisplayMode();
        displayMode.width = 100;
        displayMode.height = 200;
        displayMode.supportedHdrTypes = new int[]{1, 2};
        dynamicDisplayMode.supportedDisplayModes = new SurfaceControl.DisplayMode[] {displayMode};
        when(mSurfaceControlProxy.getDynamicDisplayInfo(anyLong()))
                .thenReturn(dynamicDisplayMode);
        when(mSurfaceControlProxy.getDesiredDisplayModeSpecs(mMockDisplayToken))
                .thenReturn(new SurfaceControl.DesiredDisplayModeSpecs());
    }

    @Test
    public void testCreateVirtualDisplay_sentToInputManager() throws RemoteException {
        // This is to update the display device config such that DisplayManagerService can ignore
        // the usage of SensorManager, which is available only after the PowerManagerService
        // is ready.
        resetConfigToIgnoreSensorManager(mContext);
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.systemReady(false /* safeMode */);
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
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

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
        // This is to update the display device config such that DisplayManagerService can ignore
        // the usage of SensorManager, which is available only after the PowerManagerService
        // is ready.
        resetConfigToIgnoreSensorManager(mContext);
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.systemReady(false /* safeMode */);
        displayManager.windowManagerAndInputReady();

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService bs = displayManager.new BinderService();

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        final int[] displayIds = bs.getDisplayIds(/* includeDisabled= */ true);
        final int size = displayIds.length;
        assertTrue(size > 0);

        Map<Integer, Integer> expectedDisplayTypeToViewPortTypeMapping = Map.of(
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
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        DisplayDeviceInfo ddi = displayManager.getDisplayDeviceInfoInternal(displayId);
        assertNotNull(ddi);
        assertTrue((ddi.flags & DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT) != 0);
    }

    @Test
    public void testCreateVirtualDisplayOwnFocus() throws RemoteException {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService bs = displayManager.new BinderService();

        String uniqueId = "uniqueId --- Own Focus Test";
        int width = 600;
        int height = 800;
        int dpi = 320;
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;

        when(mContext.checkCallingPermission(ADD_TRUSTED_DISPLAY)).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi);
        builder.setFlags(flags);
        builder.setUniqueId(uniqueId);
        int displayId = bs.createVirtualDisplay(builder.build(), /* callback= */ mMockAppToken,
                /* projection= */ null, PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, /* now= */ 0);

        DisplayDeviceInfo ddi = displayManager.getDisplayDeviceInfoInternal(displayId);
        assertNotNull(ddi);
        assertTrue((ddi.flags & DisplayDeviceInfo.FLAG_OWN_FOCUS) != 0);
    }

    @Test
    public void testCreateVirtualDisplayOwnFocus_nonTrustedDisplay() throws RemoteException {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService bs = displayManager.new BinderService();

        String uniqueId = "uniqueId --- Own Focus Test -- nonTrustedDisplay";
        int width = 600;
        int height = 800;
        int dpi = 320;
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi);
        builder.setFlags(flags);
        builder.setUniqueId(uniqueId);
        int displayId = bs.createVirtualDisplay(builder.build(), /* callback= */ mMockAppToken,
                /* projection= */ null, PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, /* now= */ 0);

        DisplayDeviceInfo ddi = displayManager.getDisplayDeviceInfoInternal(displayId);
        assertNotNull(ddi);
        assertTrue((ddi.flags & DisplayDeviceInfo.FLAG_OWN_FOCUS) == 0);
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
     * Tests that we send the device state to window manager
     */
    @Test
    public void testOnStateChanged_sendsStateChangedEventToWm() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        registerDefaultDisplays(displayManager);
        displayManager.windowManagerAndInputReady();
        displayManager.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        DeviceStateListener listener = displayManager.new DeviceStateListener();
        Handler handler = displayManager.getDisplayHandler();
        IDisplayManagerCallback displayChangesCallback = registerDisplayChangeCallback(
                displayManager);

        listener.onStateChanged(123);
        waitForIdleHandler(handler);

        InOrder inOrder = inOrder(mMockWindowManagerInternal, displayChangesCallback);
        // Verify there are no display events before WM call
        inOrder.verify(displayChangesCallback, never()).onDisplayEvent(anyInt(), anyInt());
        inOrder.verify(mMockWindowManagerInternal).onDisplayManagerReceivedDeviceState(123);
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
        displayDeviceInfo.flags = DisplayDeviceInfo.FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY;
        displayDeviceInfo.address = new TestUtils.TestDisplayAddress();
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
        DisplayManagerService.LocalService localDisplayManager = displayManager.new LocalService();

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
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        // The second virtual display requests to mirror the first virtual display.
        final String uniqueId2 = "uniqueId --- displayIdToMirrorTest #2";
        when(mMockAppToken2.asBinder()).thenReturn(mMockAppToken2);
        final VirtualDisplayConfig.Builder builder2 = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi).setUniqueId(uniqueId2);
        builder2.setUniqueId(uniqueId2);
        builder2.setDisplayIdToMirror(firstDisplayId);
        final int secondDisplayId = binderService.createVirtualDisplay(builder2.build(),
                mMockAppToken2 /* callback */, null /* projection */,
                PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));
        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        // The displayId to mirror should be a default display if there is none initially.
        assertEquals(localDisplayManager.getDisplayIdToMirror(firstDisplayId),
                Display.DEFAULT_DISPLAY);
        assertEquals(localDisplayManager.getDisplayIdToMirror(secondDisplayId),
                firstDisplayId);
    }

    /** Tests that the virtual device is created in a device display group. */
    @Test
    public void createVirtualDisplay_addsDisplaysToDeviceDisplayGroups() throws Exception {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        DisplayManagerInternal localService = displayManager.new LocalService();

        registerDefaultDisplays(displayManager);
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        IVirtualDevice virtualDevice = mock(IVirtualDevice.class);
        when(virtualDevice.getDeviceId()).thenReturn(1);
        when(mIVirtualDeviceManager.isValidVirtualDeviceId(1)).thenReturn(true);
        // Create a first virtual display. A display group should be created for this display on the
        // virtual device.
        final VirtualDisplayConfig.Builder builder1 =
                new VirtualDisplayConfig.Builder(VIRTUAL_DISPLAY_NAME, 600, 800, 320)
                        .setUniqueId("uniqueId --- device display group 1");

        int displayId1 =
                localService.createVirtualDisplay(
                        builder1.build(),
                        mMockAppToken /* callback */,
                        virtualDevice /* virtualDeviceToken */,
                        mock(DisplayWindowPolicyController.class),
                        PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));
        int displayGroupId1 = localService.getDisplayInfo(displayId1).displayGroupId;

        // Create a second virtual display. This should be added to the previously created display
        // group.
        final VirtualDisplayConfig.Builder builder2 =
                new VirtualDisplayConfig.Builder(VIRTUAL_DISPLAY_NAME, 600, 800, 320)
                        .setUniqueId("uniqueId --- device display group 1");

        int displayId2 =
                localService.createVirtualDisplay(
                        builder2.build(),
                        mMockAppToken /* callback */,
                        virtualDevice /* virtualDeviceToken */,
                        mock(DisplayWindowPolicyController.class),
                        PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));
        int displayGroupId2 = localService.getDisplayInfo(displayId2).displayGroupId;

        assertEquals(
                "Both displays should be added to the same displayGroup.",
                displayGroupId1,
                displayGroupId2);
    }

    /**
     * Tests that the virtual display is not added to the device display group when
     * VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP is set.
     */
    @Test
    public void createVirtualDisplay_addsDisplaysToOwnDisplayGroups() throws Exception {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        DisplayManagerInternal localService = displayManager.new LocalService();

        registerDefaultDisplays(displayManager);
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        IVirtualDevice virtualDevice = mock(IVirtualDevice.class);
        when(virtualDevice.getDeviceId()).thenReturn(1);
        when(mIVirtualDeviceManager.isValidVirtualDeviceId(1)).thenReturn(true);

        // Create a first virtual display. A display group should be created for this display on the
        // virtual device.
        final VirtualDisplayConfig.Builder builder1 =
                new VirtualDisplayConfig.Builder(VIRTUAL_DISPLAY_NAME, 600, 800, 320)
                        .setUniqueId("uniqueId --- device display group");

        int displayId1 =
                localService.createVirtualDisplay(
                        builder1.build(),
                        mMockAppToken /* callback */,
                        virtualDevice /* virtualDeviceToken */,
                        mock(DisplayWindowPolicyController.class),
                        PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));
        int displayGroupId1 = localService.getDisplayInfo(displayId1).displayGroupId;

        // Create a second virtual display. With the flag VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP,
        // the display should not be added to the previously created display group.
        final VirtualDisplayConfig.Builder builder2 =
                new VirtualDisplayConfig.Builder(VIRTUAL_DISPLAY_NAME, 600, 800, 320)
                        .setFlags(VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP)
                        .setUniqueId("uniqueId --- own display group");

        when(mIVirtualDeviceManager.isValidVirtualDeviceId(1)).thenReturn(true);

        int displayId2 =
                localService.createVirtualDisplay(
                        builder2.build(),
                        mMockAppToken /* callback */,
                        virtualDevice /* virtualDeviceToken */,
                        mock(DisplayWindowPolicyController.class),
                        PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));
        int displayGroupId2 = localService.getDisplayInfo(displayId2).displayGroupId;

        assertNotEquals(
                "Display 1 should be in the device display group and display 2 in its own display"
                        + " group.",
                displayGroupId1,
                displayGroupId2);
    }

    @Test
    public void displaysInDeviceOrOwnDisplayGroupShouldPreserveAlwaysUnlockedFlag()
            throws Exception {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        DisplayManagerInternal localService = displayManager.new LocalService();

        registerDefaultDisplays(displayManager);
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        IVirtualDevice virtualDevice = mock(IVirtualDevice.class);
        when(virtualDevice.getDeviceId()).thenReturn(1);
        when(mIVirtualDeviceManager.isValidVirtualDeviceId(1)).thenReturn(true);

        // Allow an ALWAYS_UNLOCKED display to be created.
        when(mContext.checkCallingPermission(ADD_TRUSTED_DISPLAY))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        when(mContext.checkCallingPermission(ADD_ALWAYS_UNLOCKED_DISPLAY))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        // Create a virtual display in a device display group.
        final VirtualDisplayConfig deviceDisplayGroupDisplayConfig =
                new VirtualDisplayConfig.Builder(VIRTUAL_DISPLAY_NAME, 600, 800, 320)
                        .setUniqueId("uniqueId --- device display group 1")
                        .setFlags(VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED)
                        .build();

        int deviceDisplayGroupDisplayId =
                localService.createVirtualDisplay(
                        deviceDisplayGroupDisplayConfig,
                        mMockAppToken /* callback */,
                        virtualDevice /* virtualDeviceToken */,
                        mock(DisplayWindowPolicyController.class),
                        PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        // Check that FLAG_ALWAYS_UNLOCKED is set.
        assertNotEquals(
                "FLAG_ALWAYS_UNLOCKED should be set for displays created in a device display"
                        + " group.",
                (displayManager.getDisplayDeviceInfoInternal(deviceDisplayGroupDisplayId).flags
                        & DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED),
                0);

        // Create a virtual display in its own display group.
        final VirtualDisplayConfig ownDisplayGroupConfig =
                new VirtualDisplayConfig.Builder(VIRTUAL_DISPLAY_NAME, 600, 800, 320)
                        .setUniqueId("uniqueId --- own display group 1")
                        .setFlags(
                                VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                                        | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP)
                        .build();

        int ownDisplayGroupDisplayId =
                localService.createVirtualDisplay(
                        ownDisplayGroupConfig,
                        mMockAppToken /* callback */,
                        virtualDevice /* virtualDeviceToken */,
                        mock(DisplayWindowPolicyController.class),
                        PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        // Check that FLAG_ALWAYS_UNLOCKED is set.
        assertNotEquals(
                "FLAG_ALWAYS_UNLOCKED should be set for displays created in their own display"
                        + " group.",
                (displayManager.getDisplayDeviceInfoInternal(ownDisplayGroupDisplayId).flags
                        & DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED),
                0);

        // Create a virtual display in a device display group.
        final VirtualDisplayConfig defaultDisplayGroupConfig =
                new VirtualDisplayConfig.Builder(VIRTUAL_DISPLAY_NAME, 600, 800, 320)
                        .setUniqueId("uniqueId --- default display group 1")
                        .setFlags(VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED)
                        .build();

        int defaultDisplayGroupDisplayId =
                localService.createVirtualDisplay(
                        defaultDisplayGroupConfig,
                        mMockAppToken /* callback */,
                        null /* virtualDeviceToken */,
                        mock(DisplayWindowPolicyController.class),
                        PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        // Check that FLAG_ALWAYS_UNLOCKED is not set.
        assertEquals(
                "FLAG_ALWAYS_UNLOCKED should not be set for displays created in the default"
                        + " display group.",
                (displayManager.getDisplayDeviceInfoInternal(defaultDisplayGroupDisplayId).flags
                        & DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED),
                0);
    }

    @Test
    public void testGetDisplayIdToMirror() throws Exception {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        DisplayManagerService.LocalService localDisplayManager = displayManager.new LocalService();

        final String uniqueId = "uniqueId --- displayIdToMirrorTest";
        final int width = 600;
        final int height = 800;
        final int dpi = 320;

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi)
                .setUniqueId(uniqueId)
                .setFlags(VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        final int firstDisplayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, null /* projection */, PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        // The second virtual display requests to mirror the first virtual display.
        final String uniqueId2 = "uniqueId --- displayIdToMirrorTest #2";
        when(mMockAppToken2.asBinder()).thenReturn(mMockAppToken2);
        final VirtualDisplayConfig.Builder builder2 = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, width, height, dpi)
                .setUniqueId(uniqueId2)
                .setWindowManagerMirroringEnabled(true);
        final int secondDisplayId = binderService.createVirtualDisplay(builder2.build(),
                mMockAppToken2 /* callback */, null /* projection */,
                PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));
        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        // The displayId to mirror should be a invalid since the display had flag OWN_CONTENT_ONLY
        assertEquals(localDisplayManager.getDisplayIdToMirror(firstDisplayId),
                Display.INVALID_DISPLAY);
        // The second display has mirroring managed by WindowManager so the mirror displayId should
        // be invalid.
        assertEquals(localDisplayManager.getDisplayIdToMirror(secondDisplayId),
                Display.INVALID_DISPLAY);
    }

    @Test
    public void testCreateVirtualDisplay_isValidProjection_notValid()
            throws RemoteException {
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        IMediaProjection projection = mock(IMediaProjection.class);
        doReturn(false).when(projection).isValid();
        when(mMockProjectionService
                .setContentRecordingSession(any(ContentRecordingSession.class), eq(projection)))
                .thenReturn(true);
        doReturn(true).when(mMockProjectionService).isCurrentProjection(eq(projection));

        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setUniqueId("uniqueId --- isValid false");

        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.windowManagerAndInputReady();

        // Pass in a non-null projection.
        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        final int displayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, projection, PACKAGE_NAME);

        // VirtualDisplay is created for mirroring.
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        verify(mMockProjectionService, atLeastOnce()).setContentRecordingSession(
                any(ContentRecordingSession.class), nullable(IMediaProjection.class));
        // But mirroring doesn't begin.
        verify(mMockProjectionService, atLeastOnce()).setContentRecordingSession(
                mContentRecordingSessionCaptor.capture(), nullable(IMediaProjection.class));
        ContentRecordingSession session = mContentRecordingSessionCaptor.getValue();
        assertThat(session.isWaitingForConsent()).isTrue();
    }

    @Test
    public void testCreateVirtualDisplay_setContentRecordingSessionSuccess()
            throws RemoteException {
        final int displayToRecord = 50;
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        IMediaProjection projection = mock(IMediaProjection.class);
        doReturn(true).when(projection).isValid();
        when(mMockProjectionService
                .setContentRecordingSession(any(ContentRecordingSession.class), eq(projection)))
                .thenReturn(true);
        doReturn(true).when(mMockProjectionService).isCurrentProjection(eq(projection));

        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setUniqueId("uniqueId --- setContentRecordingSession true");
        builder.setDisplayIdToMirror(displayToRecord);

        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.windowManagerAndInputReady();

        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        final int displayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, projection, PACKAGE_NAME);

        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        verify(mMockProjectionService, atLeastOnce()).setContentRecordingSession(
                mContentRecordingSessionCaptor.capture(), nullable(IMediaProjection.class));
        ContentRecordingSession session = mContentRecordingSessionCaptor.getValue();
        assertThat(session.getContentToRecord()).isEqualTo(RECORD_CONTENT_DISPLAY);
        assertThat(session.getVirtualDisplayId()).isEqualTo(displayId);
        assertThat(session.getDisplayToRecord()).isEqualTo(displayToRecord);
        assertThat(session.isWaitingForConsent()).isFalse();
    }

    @Test
    public void testCreateVirtualDisplay_setContentRecordingSessionFail() throws RemoteException {
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        IMediaProjection projection = mock(IMediaProjection.class);
        doReturn(true).when(projection).isValid();
        when(mMockProjectionService
                .setContentRecordingSession(any(ContentRecordingSession.class), eq(projection)))
                .thenReturn(false);
        doReturn(true).when(mMockProjectionService).isCurrentProjection(eq(projection));

        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setUniqueId("uniqueId --- setContentRecordingSession false");

        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.windowManagerAndInputReady();

        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        final int displayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, projection, PACKAGE_NAME);

        assertThat(displayId).isEqualTo(Display.INVALID_DISPLAY);
    }

    @Test
    public void testCreateVirtualDisplay_setContentRecordingSession_taskSession()
            throws RemoteException {
        final int displayToRecord = 50;
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        IMediaProjection projection = mock(IMediaProjection.class);
        doReturn(true).when(projection).isValid();
        when(mMockProjectionService
                .setContentRecordingSession(any(ContentRecordingSession.class), eq(projection)))
                .thenReturn(true);
        doReturn(mock(IBinder.class)).when(projection).getLaunchCookie();
        doReturn(true).when(mMockProjectionService).isCurrentProjection(eq(projection));

        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setUniqueId("uniqueId --- setContentRecordingSession false");
        builder.setDisplayIdToMirror(displayToRecord);

        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.windowManagerAndInputReady();

        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        final int displayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, projection, PACKAGE_NAME);

        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        verify(mMockProjectionService, atLeastOnce()).setContentRecordingSession(
                mContentRecordingSessionCaptor.capture(), nullable(IMediaProjection.class));
        ContentRecordingSession session = mContentRecordingSessionCaptor.getValue();
        assertThat(session.getContentToRecord()).isEqualTo(RECORD_CONTENT_TASK);
        assertThat(session.getVirtualDisplayId()).isEqualTo(displayId);
        assertThat(session.getTokenToRecord()).isNotNull();
    }

    @Test
    public void testCreateVirtualDisplay_setContentRecordingSession_noProjection_noFlags()
            throws RemoteException {
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        // Set no flags for the VirtualDisplay.
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setUniqueId("uniqueId --- setContentRecordingSession false");

        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.windowManagerAndInputReady();

        // Pass in a null projection.
        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        final int displayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, null /* projection */, PACKAGE_NAME);

        // VirtualDisplay is created but not for mirroring.
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        verify(mMockProjectionService, never()).setContentRecordingSession(
                any(ContentRecordingSession.class), nullable(IMediaProjection.class));
    }

    @Test
    public void testCreateVirtualDisplay_setContentRecordingSession_noProjection_noMirroringFlag()
            throws RemoteException {
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        // Set a non-mirroring flag for the VirtualDisplay.
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setUniqueId("uniqueId --- setContentRecordingSession false");
        builder.setFlags(VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);

        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.windowManagerAndInputReady();

        // Pass in a null projection.
        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        final int displayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, null /* projection */, PACKAGE_NAME);

        // VirtualDisplay is created but not for mirroring.
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        verify(mMockProjectionService, never()).setContentRecordingSession(
                any(ContentRecordingSession.class), nullable(IMediaProjection.class));
    }

    @Test
    public void testCreateVirtualDisplay_setContentRecordingSession_projection_noMirroringFlag()
            throws RemoteException {
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        IMediaProjection projection = mock(IMediaProjection.class);
        doReturn(true).when(projection).isValid();
        when(mMockProjectionService
                .setContentRecordingSession(any(ContentRecordingSession.class), eq(projection)))
                .thenReturn(true);
        doReturn(true).when(mMockProjectionService).isCurrentProjection(eq(projection));

        // Set no flags for the VirtualDisplay.
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setUniqueId("uniqueId --- setContentRecordingSession false");

        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.windowManagerAndInputReady();

        // Pass in a non-null projection.
        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        final int displayId = binderService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, projection, PACKAGE_NAME);

        // VirtualDisplay is created for mirroring.
        assertThat(displayId).isNotEqualTo(Display.INVALID_DISPLAY);
        verify(mMockProjectionService, atLeastOnce()).setContentRecordingSession(
                any(ContentRecordingSession.class), nullable(IMediaProjection.class));
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
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        assertEquals(displayManager.getVirtualDisplaySurfaceInternal(mMockAppToken), surface);
    }

    /**
     * Tests that specifying VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP is allowed when the permission
     * ADD_TRUSTED_DISPLAY is granted.
     */
    @Test
    public void testOwnDisplayGroup_allowCreationWithAddTrustedDisplayPermission()
            throws RemoteException {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);

        DisplayManagerService.BinderService bs = displayManager.new BinderService();
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        when(mContext.checkCallingPermission(ADD_TRUSTED_DISPLAY)).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP);
        builder.setUniqueId("uniqueId --- OWN_DISPLAY_GROUP");

        int displayId = bs.createVirtualDisplay(builder.build(), mMockAppToken /* callback */,
                null /* projection */, PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));
        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);
        DisplayDeviceInfo ddi = displayManager.getDisplayDeviceInfoInternal(displayId);
        assertNotNull(ddi);
        assertNotEquals(0, ddi.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP);
    }

    /**
     * Tests that specifying VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP is blocked when the permission
     * ADD_TRUSTED_DISPLAY is denied.
     */
    @Test
    public void testOwnDisplayGroup_withoutAddTrustedDisplayPermission_throwsSecurityException() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);

        DisplayManagerService.BinderService bs = displayManager.new BinderService();
        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        when(mContext.checkCallingPermission(ADD_TRUSTED_DISPLAY)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP);
        builder.setUniqueId("uniqueId --- OWN_DISPLAY_GROUP");

        try {
            bs.createVirtualDisplay(builder.build(), mMockAppToken /* callback */,
                    null /* projection */, PACKAGE_NAME);
            fail("Creating virtual display with VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP without "
                    + "ADD_TRUSTED_DISPLAY permission should throw SecurityException.");
        } catch (SecurityException e) {
            // SecurityException is expected
        }
    }

    /**
     * Tests that specifying VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP is allowed when called with
     * a virtual device, even if ADD_TRUSTED_DISPLAY is not granted.
     */
    @Test
    public void testOwnDisplayGroup_allowCreationWithVirtualDevice()  throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        DisplayManagerInternal localService = displayManager.new LocalService();

        registerDefaultDisplays(displayManager);

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);

        when(mContext.checkCallingPermission(ADD_TRUSTED_DISPLAY)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                VIRTUAL_DISPLAY_NAME, 600, 800, 320);
        builder.setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP);
        builder.setUniqueId("uniqueId --- OWN_DISPLAY_GROUP");

        IVirtualDevice virtualDevice = mock(IVirtualDevice.class);
        when(virtualDevice.getDeviceId()).thenReturn(1);
        when(mIVirtualDeviceManager.isValidVirtualDeviceId(1)).thenReturn(true);

        int displayId = localService.createVirtualDisplay(builder.build(),
                mMockAppToken /* callback */, virtualDevice /* virtualDeviceToken */,
                mock(DisplayWindowPolicyController.class), PACKAGE_NAME);
        verify(mMockProjectionService, never()).setContentRecordingSession(any(),
                nullable(IMediaProjection.class));
        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);
        DisplayDeviceInfo ddi = displayManager.getDisplayDeviceInfoInternal(displayId);
        assertNotNull(ddi);
        assertNotEquals(0, ddi.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP);
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
     * Tests that the frame rate override is returning the correct value from
     * DisplayInfo#getRefreshRate
     */
    @Test
    public void testDisplayInfoNonNativeFrameRateOverride() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
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
        assertEquals(20f, displayInfo.getRefreshRate(), 0.01f);
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
        testDisplayInfoNonNativeFrameRateOverrideMode(/*compatChangeEnabled*/ false);
    }

    /**
     * Tests that the mode reflects the physical display refresh rate when not in compat mode.
     */
    @Test
    @EnableCompatChanges({DisplayManagerService.DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE})
    public void testDisplayInfoNonNativeFrameRateOverrideMode() throws Exception {
        testDisplayInfoNonNativeFrameRateOverrideMode(/*compatChangeEnabled*/ true);
    }

    /**
     * Tests that there is a display change notification if the render frame rate is updated
     */
    @Test
    public void testShouldNotifyChangeWhenDisplayInfoRenderFrameRateChanged() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager, new float[]{60f});
        FakeDisplayManagerCallback callback = registerDisplayListenerCallback(displayManager,
                displayManagerBinderService, displayDevice);

        updateRenderFrameRate(displayManager, displayDevice, 30f);
        assertTrue(callback.mDisplayChangedCalled);
        callback.clear();

        updateRenderFrameRate(displayManager, displayDevice, 30f);
        assertFalse(callback.mDisplayChangedCalled);

        updateRenderFrameRate(displayManager, displayDevice, 20f);
        assertTrue(callback.mDisplayChangedCalled);
        callback.clear();
    }

    /**
     * Tests that the DisplayInfo is updated correctly with a render frame rate
     */
    @Test
    public void testDisplayInfoRenderFrameRate() throws Exception {
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

        updateRenderFrameRate(displayManager, displayDevice, 20f);
        displayInfo = displayManagerBinderService.getDisplayInfo(displayId);
        assertEquals(20f, displayInfo.getRefreshRate(), 0.01f);
    }

    /**
     * Tests that the mode reflects the render frame rate is in compat mode
     */
    @Test
    @DisableCompatChanges({DisplayManagerService.DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE})
    public  void testDisplayInfoRenderFrameRateModeCompat() throws Exception {
        testDisplayInfoRenderFrameRateModeCompat(/*compatChangeEnabled*/ false);
    }

    /**
     * Tests that the mode reflects the physical display refresh rate when not in compat mode.
     */
    @Test
    @EnableCompatChanges({DisplayManagerService.DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE})
    public  void testDisplayInfoRenderFrameRateMode() throws Exception {
        testDisplayInfoRenderFrameRateModeCompat(/*compatChangeEnabled*/ true);
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



    @Test
    public void testSettingTwoBrightnessConfigurationsOnMultiDisplay() {
        Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);

        // get the first two internal displays
        Display[] displays = displayManager.getDisplays();
        Display internalDisplayOne = null;
        Display internalDisplayTwo = null;
        for (Display display : displays) {
            if (display.getType() == Display.TYPE_INTERNAL) {
                if (internalDisplayOne == null) {
                    internalDisplayOne = display;
                } else {
                    internalDisplayTwo = display;
                    break;
                }
            }
        }

        // return if there are fewer than 2 displays on this device
        if (internalDisplayOne == null || internalDisplayTwo == null) {
            return;
        }

        final String uniqueDisplayIdOne = internalDisplayOne.getUniqueId();
        final String uniqueDisplayIdTwo = internalDisplayTwo.getUniqueId();

        BrightnessConfiguration configOne =
                new BrightnessConfiguration.Builder(
                        new float[]{0.0f, 12345.0f}, new float[]{15.0f, 400.0f})
                        .setDescription("model:1").build();
        BrightnessConfiguration configTwo =
                new BrightnessConfiguration.Builder(
                        new float[]{0.0f, 6789.0f}, new float[]{12.0f, 300.0f})
                        .setDescription("model:2").build();

        displayManager.setBrightnessConfigurationForDisplay(configOne,
                uniqueDisplayIdOne);
        displayManager.setBrightnessConfigurationForDisplay(configTwo,
                uniqueDisplayIdTwo);

        BrightnessConfiguration configFromOne =
                displayManager.getBrightnessConfigurationForDisplay(uniqueDisplayIdOne);
        BrightnessConfiguration configFromTwo =
                displayManager.getBrightnessConfigurationForDisplay(uniqueDisplayIdTwo);

        assertNotNull(configFromOne);
        assertEquals(configOne, configFromOne);
        assertEquals(configTwo, configFromTwo);

    }

    @Test
    public void testHdrConversionModeEquals() {
        assertEquals(
                new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_FORCE, 2),
                new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_FORCE, 2));
        assertNotEquals(
                new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_FORCE, 2),
                new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_FORCE, 3));
        assertEquals(
                new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_SYSTEM),
                new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_SYSTEM));
        assertNotEquals(
                new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_FORCE, 2),
                new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_SYSTEM));
    }

    @Test
    public void testCreateHdrConversionMode_withInvalidArguments_throwsException() {
        assertThrows(
                "preferredHdrOutputType must not be set if the conversion mode is "
                        + "HDR_CONVERSION_PASSTHROUGH",
                IllegalArgumentException.class,
                () -> new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_PASSTHROUGH,
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION));
    }

    @Test
    public void testSetHdrConversionModeInternal_withInvalidArguments_throwsException() {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        assertThrows("Expected DisplayManager to throw IllegalArgumentException when "
                        + "preferredHdrOutputType is set and the conversion mode is "
                        + "HDR_CONVERSION_SYSTEM",
                IllegalArgumentException.class,
                () -> displayManager.setHdrConversionModeInternal(new HdrConversionMode(
                        HdrConversionMode.HDR_CONVERSION_SYSTEM,
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)));
    }

    @Test
    public void testSetAndGetHdrConversionModeInternal() {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        final HdrConversionMode mode = new HdrConversionMode(
                HdrConversionMode.HDR_CONVERSION_FORCE,
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION);
        displayManager.setHdrConversionModeInternal(mode);
        assertEquals(mode, displayManager.getHdrConversionModeSettingInternal());
        assertEquals(mode.getConversionMode(), mHdrConversionMode);
        assertEquals(mode.getPreferredHdrOutputType(), mPreferredHdrOutputType);
    }

    @Test
    public void testHdrConversionMode_withMinimalPostProcessing() {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager,
                new float[]{60f, 30f, 20f});
        int displayId = getDisplayIdForDisplayDevice(displayManager, displayManagerBinderService,
                displayDevice);

        final HdrConversionMode mode = new HdrConversionMode(
                HdrConversionMode.HDR_CONVERSION_FORCE,
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION);
        displayManager.setHdrConversionModeInternal(mode);
        assertEquals(mode, displayManager.getHdrConversionModeSettingInternal());

        displayManager.setMinimalPostProcessingAllowed(true);
        displayManager.setDisplayPropertiesInternal(displayId, false /* hasContent */,
                30.0f /* requestedRefreshRate */,
                displayDevice.getDisplayDeviceInfoLocked().modeId /* requestedModeId */,
                30.0f /* requestedMinRefreshRate */, 120.0f /* requestedMaxRefreshRate */,
                true /* preferMinimalPostProcessing */, false /* disableHdrConversion */,
                true /* inTraversal */);

        assertEquals(new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_PASSTHROUGH),
                displayManager.getHdrConversionModeInternal());

        displayManager.setDisplayPropertiesInternal(displayId, false /* hasContent */,
                30.0f /* requestedRefreshRate */,
                displayDevice.getDisplayDeviceInfoLocked().modeId /* requestedModeId */,
                30.0f /* requestedMinRefreshRate */, 120.0f /* requestedMaxRefreshRate */,
                false /* preferMinimalPostProcessing */, false /* disableHdrConversion */,
                true /* inTraversal */);
        assertEquals(mode, displayManager.getHdrConversionModeInternal());
    }

    @Test
    public void testReturnsRefreshRateForDisplayAndSensor_proximitySensorSet() {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        DisplayManagerInternal localService = displayManager.new LocalService();
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        displayManager.overrideSensorManager(mSensorManager);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager, new float[]{60f});
        displayDevice.mDisplayDeviceConfig = mMockDisplayDeviceConfig;
        int displayId = getDisplayIdForDisplayDevice(displayManager, displayManagerBinderService,
                displayDevice);

        String testSensorName = "testName";
        String testSensorType = "testType";
        Sensor testSensor = TestUtils.createSensor(testSensorType, testSensorName);

        DisplayDeviceConfig.SensorData sensorData = new DisplayDeviceConfig.SensorData();
        sensorData.type = testSensorType;
        sensorData.name = testSensorName;
        sensorData.minRefreshRate = 10f;
        sensorData.maxRefreshRate = 100f;

        when(mMockDisplayDeviceConfig.getProximitySensor()).thenReturn(sensorData);
        when(mSensorManager.getSensorList(Sensor.TYPE_ALL)).thenReturn(Collections.singletonList(
                testSensor));

        SurfaceControl.RefreshRateRange result = localService.getRefreshRateForDisplayAndSensor(
                displayId, testSensorName, testSensorType);

        assertNotNull(result);
        assertEquals(result.min, sensorData.minRefreshRate, FLOAT_TOLERANCE);
        assertEquals(result.max, sensorData.maxRefreshRate, FLOAT_TOLERANCE);
    }

    @Test
    public void testReturnsRefreshRateForDisplayAndSensor_proximitySensorNotSet() {
        DisplayManagerService displayManager = new DisplayManagerService(mContext, mBasicInjector);
        DisplayManagerInternal localService = displayManager.new LocalService();
        DisplayManagerService.BinderService displayManagerBinderService =
                displayManager.new BinderService();
        displayManager.overrideSensorManager(mSensorManager);

        FakeDisplayDevice displayDevice = createFakeDisplayDevice(displayManager, new float[]{60f});
        displayDevice.mDisplayDeviceConfig = mMockDisplayDeviceConfig;
        int displayId = getDisplayIdForDisplayDevice(displayManager, displayManagerBinderService,
                displayDevice);

        String testSensorName = "testName";
        String testSensorType = "testType";
        Sensor testSensor = TestUtils.createSensor(testSensorType, testSensorName);

        DisplayDeviceConfig.SensorData sensorData = new DisplayDeviceConfig.SensorData();
        sensorData.type = testSensorType;
        sensorData.name = testSensorName;
        sensorData.minRefreshRate = 10f;
        sensorData.maxRefreshRate = 100f;

        when(mMockDisplayDeviceConfig.getProximitySensor()).thenReturn(null);
        when(mSensorManager.getSensorList(Sensor.TYPE_ALL)).thenReturn(Collections.singletonList(
                testSensor));

        SurfaceControl.RefreshRateRange result = localService.getRefreshRateForDisplayAndSensor(
                displayId, testSensorName, testSensorType);

        assertNull(result);
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

    private void testDisplayInfoRenderFrameRateModeCompat(boolean compatChangeEnabled)
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

        updateRenderFrameRate(displayManager, displayDevice, 20f);
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

    private void testDisplayInfoNonNativeFrameRateOverrideMode(boolean compatChangeEnabled) {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
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
        } else {
            expectedMode = new Display.Mode(255, 100, 200, 20f);
        }
        assertEquals(expectedMode, displayInfo.getMode());
    }

    private int getDisplayIdForDisplayDevice(
            DisplayManagerService displayManager,
            DisplayManagerService.BinderService displayManagerBinderService,
            FakeDisplayDevice displayDevice) {

        final int[] displayIds = displayManagerBinderService.getDisplayIds(
                /* includeDisabled= */ true);
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

    private void updateRenderFrameRate(DisplayManagerService displayManager,
            FakeDisplayDevice displayDevice,
            float renderFrameRate) {
        DisplayDeviceInfo displayDeviceInfo = new DisplayDeviceInfo();
        displayDeviceInfo.copyFrom(displayDevice.getDisplayDeviceInfoLocked());
        displayDeviceInfo.renderFrameRate = renderFrameRate;
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

    private IDisplayManagerCallback registerDisplayChangeCallback(
            DisplayManagerService displayManager) {
        IDisplayManagerCallback displayChangesCallback = mock(IDisplayManagerCallback.class);
        when(displayChangesCallback.asBinder()).thenReturn(new Binder());
        DisplayManagerService.BinderService binderService = displayManager.new BinderService();
        binderService.registerCallback(displayChangesCallback);
        return displayChangesCallback;
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
        displayDeviceInfo.renderFrameRate = displayDeviceInfo.supportedModes[0].getRefreshRate();
        displayDeviceInfo.width = width;
        displayDeviceInfo.height = height;
        final Rect zeroRect = new Rect();
        displayDeviceInfo.displayCutout = new DisplayCutout(
                Insets.of(0, 10, 0, 0),
                zeroRect, new Rect(0, 0, 10, 10), zeroRect, zeroRect);
        displayDeviceInfo.flags = DisplayDeviceInfo.FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY;
        displayDeviceInfo.address = new TestUtils.TestDisplayAddress();
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

    private void resetConfigToIgnoreSensorManager(Context context) {
        final Resources res = Mockito.spy(context.getResources());
        doReturn(new int[]{-1}).when(res).getIntArray(R.array
                .config_ambientThresholdsOfPeakRefreshRate);
        doReturn(new int[]{-1}).when(res).getIntArray(R.array
                .config_brightnessThresholdsOfPeakRefreshRate);
        doReturn(new int[]{-1}).when(res).getIntArray(R.array
                .config_highDisplayBrightnessThresholdsOfFixedRefreshRate);
        doReturn(new int[]{-1}).when(res).getIntArray(R.array
                .config_highAmbientBrightnessThresholdsOfFixedRefreshRate);

        when(context.getResources()).thenReturn(res);
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
