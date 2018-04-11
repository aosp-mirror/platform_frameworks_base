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
 * limitations under the License
 */

package com.android.server.display;

import android.content.Context;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.Curve;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayViewport;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.input.InputManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.SurfaceControl;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.display.DisplayDeviceInfo;
import com.android.server.display.DisplayManagerService.SyncRoot;
import com.android.server.display.VirtualDisplayAdapter.SurfaceControlDisplayFactory;
import com.android.server.lights.LightsManager;
import com.android.server.wm.WindowManagerInternal;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@SmallTest
public class DisplayManagerServiceTest extends AndroidTestCase {
    private static final int MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS = 1;
    private static final long SHORT_DEFAULT_DISPLAY_TIMEOUT_MILLIS = 10;

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
    private final DisplayManagerService.Injector mBasicInjector =
            new DisplayManagerService.Injector() {
                @Override
                VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot,
                        Context context, Handler handler,
                        DisplayAdapter.Listener displayAdapterListener) {
                    return new VirtualDisplayAdapter(syncRoot, context, handler,
                            displayAdapterListener,
                            (String name, boolean secure) -> mMockDisplayToken);
                }
            };

    @Mock InputManagerInternal mMockInputManagerInternal;
    @Mock IVirtualDisplayCallback.Stub mMockAppToken;
    @Mock WindowManagerInternal mMockWindowManagerInternal;
    @Mock LightsManager mMockLightsManager;
    @Mock VirtualDisplayAdapter mMockVirtualDisplayAdapter;
    @Mock IBinder mMockDisplayToken;

    @Override
    protected void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mMockInputManagerInternal);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mMockWindowManagerInternal);
        LocalServices.removeServiceForTest(LightsManager.class);
        LocalServices.addService(LightsManager.class, mMockLightsManager);
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testCreateVirtualDisplay_sentToInputManager() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mBasicInjector);
        registerDefaultDisplays(displayManager);
        displayManager.systemReady(false /* safeMode */, true /* onlyCore */);
        displayManager.windowManagerAndInputReady();

        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService bs = displayManager.new BinderService();

        String uniqueId = "uniqueId --- Test";
        String uniqueIdPrefix = "virtual:" + mContext.getPackageName() + ":";
        int width = 600;
        int height = 800;
        int dpi = 320;
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH;

        when(mMockAppToken.asBinder()).thenReturn(mMockAppToken);
        int displayId = bs.createVirtualDisplay(mMockAppToken /* callback */,
                null /* projection */, "com.android.frameworks.servicestests",
                "Test Virtual Display", width, height, dpi, null /* surface */, flags /* flags */,
                uniqueId);

        displayManager.performTraversalInternal(mock(SurfaceControl.Transaction.class));

        // flush the handler
        displayManager.getDisplayHandler().runWithScissors(() -> {}, 0 /* now */);

        ArgumentCaptor<List<DisplayViewport>> virtualViewportCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mMockInputManagerInternal).setDisplayViewports(
                any(), any(), virtualViewportCaptor.capture());

        assertEquals(1, virtualViewportCaptor.getValue().size());
        DisplayViewport dv = virtualViewportCaptor.getValue().get(0);
        assertEquals(height, dv.deviceHeight);
        assertEquals(width, dv.deviceWidth);
        assertEquals(uniqueIdPrefix + uniqueId, dv.uniqueId);
        assertEquals(displayId, dv.displayId);
    }

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
        int displayId = bs.createVirtualDisplay(mMockAppToken /* callback */,
                null /* projection */, "com.android.frameworks.servicestests",
                "Test Virtual Display", width, height, dpi, null /* surface */, flags /* flags */,
                uniqueId);

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
    public void testStartVirtualDisplayWithDefaultDisplay_Succeeds() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        registerDefaultDisplays(displayManager);
        displayManager.onBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
    }

    /**
     * Tests that we get a Runtime exception when we cannot initialize the default display.
     */
    public void testStartVirtualDisplayWithDefDisplay_NoDefaultDisplay() throws Exception {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        Handler handler = displayManager.getDisplayHandler();
        handler.runWithScissors(() -> {}, 0 /* now */);

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
    public void testNullBrightnessConfiguration() {
        DisplayManagerService displayManager =
                new DisplayManagerService(mContext, mShortMockedInjector);
        displayManager.validateBrightnessConfiguration(null);
    }

    private void registerDefaultDisplays(DisplayManagerService displayManager) {
        Handler handler = displayManager.getDisplayHandler();
        // Would prefer to call displayManager.onStart() directly here but it performs binderService
        // registration which triggers security exceptions when running from a test.
        handler.sendEmptyMessage(MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS);
        // flush the handler
        handler.runWithScissors(() -> {}, 0 /* now */);
    }
}
