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
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayViewport;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.input.InputManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.SurfaceControl;
import android.view.WindowManagerInternal;

import com.android.server.LocalServices;
import com.android.server.display.DisplayManagerService.SyncRoot;
import com.android.server.display.VirtualDisplayAdapter.SurfaceControlDisplayFactory;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SmallTest
public class DisplayManagerServiceTest extends AndroidTestCase {
    private Handler mHandler;
    private DisplayManagerService mDisplayManager;
    @Mock InputManagerInternal mMockInputManagerInternal;
    @Mock IVirtualDisplayCallback.Stub mMockAppToken;
    @Mock WindowManagerInternal mMockWindowManagerInternal;
    @Mock VirtualDisplayAdapter mMockVirtualDisplayAdapter;
    @Mock IBinder mMockDisplayToken;

    @Override
    protected void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDisplayManager = new DisplayManagerService(mContext,
        new DisplayManagerService.Injector() {
            @Override
            VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot, Context context,
                    Handler handler, DisplayAdapter.Listener displayAdapterListener) {
                return new VirtualDisplayAdapter(syncRoot, context, handler, displayAdapterListener,
                        (String name, boolean secure) -> mMockDisplayToken);
            }
        });
        mHandler = mDisplayManager.getDisplayHandler();

        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mMockInputManagerInternal);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mMockWindowManagerInternal);

        mDisplayManager.systemReady(false /* safeMode */, false /* onlyCore */);
        mDisplayManager.windowManagerAndInputReady();
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testCreateVirtualDisplay_sentToInputManager() throws Exception {
        // This is effectively the DisplayManager service published to ServiceManager.
        DisplayManagerService.BinderService bs = mDisplayManager.new BinderService();

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

        mDisplayManager.performTraversalInTransactionFromWindowManagerInternal();

        // flush the handler
        mHandler.runWithScissors(() -> {}, 0 /* now */);

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
}
