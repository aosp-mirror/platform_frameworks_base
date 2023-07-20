/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.servertransaction;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import android.content.res.Configuration;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.window.WindowTokenClient;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link WindowTokenClientController}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:WindowTokenClientControllerTest
 */
@SmallTest
@Presubmit
public class WindowTokenClientControllerTest {

    @Mock
    private IWindowManager mWindowManagerService;
    @Mock
    private WindowTokenClient mWindowTokenClient;
    @Mock
    private IBinder mClientToken;
    @Mock
    private IBinder mWindowToken;
    // Can't mock final class.
    private final Configuration mConfiguration = new Configuration();

    private IWindowManager mOriginalWindowManagerService;

    private WindowTokenClientController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mOriginalWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        WindowManagerGlobal.overrideWindowManagerServiceForTesting(mWindowManagerService);
        doReturn(mClientToken).when(mWindowTokenClient).asBinder();
        mController = spy(WindowTokenClientController.getInstance());
    }

    @After
    public void tearDown() {
        WindowManagerGlobal.overrideWindowManagerServiceForTesting(mOriginalWindowManagerService);
    }

    @Test
    public void testAttachToDisplayArea() throws RemoteException {
        doReturn(null).when(mWindowManagerService).attachWindowContextToDisplayArea(
                any(), anyInt(), anyInt(), any());

        assertFalse(mController.attachToDisplayArea(mWindowTokenClient, TYPE_APPLICATION_OVERLAY,
                DEFAULT_DISPLAY, null /* options */));
        verify(mWindowManagerService).attachWindowContextToDisplayArea(mWindowTokenClient,
                TYPE_APPLICATION_OVERLAY, DEFAULT_DISPLAY, null /* options */);
        verify(mWindowTokenClient, never()).onConfigurationChanged(any(), anyInt(), anyBoolean());

        doReturn(mConfiguration).when(mWindowManagerService).attachWindowContextToDisplayArea(
                any(), anyInt(), anyInt(), any());

        assertTrue(mController.attachToDisplayArea(mWindowTokenClient, TYPE_APPLICATION_OVERLAY,
                DEFAULT_DISPLAY, null /* options */));
        verify(mWindowTokenClient).onConfigurationChanged(mConfiguration, DEFAULT_DISPLAY,
                false /* shouldReportConfigChange */);
    }

    @Test
    public void testAttachToDisplayArea_detachIfNeeded() throws RemoteException {
        mController.detachIfNeeded(mWindowTokenClient);

        verify(mWindowManagerService, never()).detachWindowContextFromWindowContainer(any());

        doReturn(null).when(mWindowManagerService).attachWindowContextToDisplayArea(
                any(), anyInt(), anyInt(), any());
        mController.attachToDisplayArea(mWindowTokenClient, TYPE_APPLICATION_OVERLAY,
                DEFAULT_DISPLAY, null /* options */);
        mController.detachIfNeeded(mWindowTokenClient);

        verify(mWindowManagerService, never()).detachWindowContextFromWindowContainer(any());

        doReturn(mConfiguration).when(mWindowManagerService).attachWindowContextToDisplayArea(
                any(), anyInt(), anyInt(), any());
        mController.attachToDisplayArea(mWindowTokenClient, TYPE_APPLICATION_OVERLAY,
                DEFAULT_DISPLAY, null /* options */);
        mController.detachIfNeeded(mWindowTokenClient);

        verify(mWindowManagerService).detachWindowContextFromWindowContainer(any());
    }

    @Test
    public void testAttachToDisplayContent() throws RemoteException {
        doReturn(null).when(mWindowManagerService).attachToDisplayContent(
                any(), anyInt());

        assertFalse(mController.attachToDisplayContent(mWindowTokenClient, DEFAULT_DISPLAY));
        verify(mWindowManagerService).attachToDisplayContent(mWindowTokenClient, DEFAULT_DISPLAY);
        verify(mWindowTokenClient, never()).onConfigurationChanged(any(), anyInt(), anyBoolean());

        doReturn(mConfiguration).when(mWindowManagerService).attachToDisplayContent(
                any(), anyInt());

        assertTrue(mController.attachToDisplayContent(mWindowTokenClient, DEFAULT_DISPLAY));
        verify(mWindowTokenClient).onConfigurationChanged(mConfiguration, DEFAULT_DISPLAY,
                false /* shouldReportConfigChange */);
    }

    @Test
    public void testAttachToDisplayContent_detachIfNeeded() throws RemoteException {
        mController.detachIfNeeded(mWindowTokenClient);

        verify(mWindowManagerService, never()).detachWindowContextFromWindowContainer(any());

        doReturn(null).when(mWindowManagerService).attachToDisplayContent(
                any(), anyInt());
        mController.attachToDisplayContent(mWindowTokenClient, DEFAULT_DISPLAY);
        mController.detachIfNeeded(mWindowTokenClient);

        verify(mWindowManagerService, never()).detachWindowContextFromWindowContainer(any());

        doReturn(mConfiguration).when(mWindowManagerService).attachToDisplayContent(
                any(), anyInt());
        mController.attachToDisplayContent(mWindowTokenClient, DEFAULT_DISPLAY);
        mController.detachIfNeeded(mWindowTokenClient);

        verify(mWindowManagerService).detachWindowContextFromWindowContainer(any());
    }

    @Test
    public void testAttachToWindowToken() throws RemoteException {
        mController.attachToWindowToken(mWindowTokenClient, mWindowToken);

        verify(mWindowManagerService).attachWindowContextToWindowToken(mWindowTokenClient,
                mWindowToken);
    }

    @Test
    public void testAttachToWindowToken_detachIfNeeded() throws RemoteException {
        mController.detachIfNeeded(mWindowTokenClient);

        verify(mWindowManagerService, never()).detachWindowContextFromWindowContainer(any());

        mController.attachToWindowToken(mWindowTokenClient, mWindowToken);
        mController.detachIfNeeded(mWindowTokenClient);

        verify(mWindowManagerService).detachWindowContextFromWindowContainer(any());
    }

    @Test
    public void testOnWindowContextConfigurationChanged() {
        mController.onWindowContextConfigurationChanged(
                mClientToken, mConfiguration, DEFAULT_DISPLAY);

        verify(mWindowTokenClient, never()).onConfigurationChanged(any(), anyInt());

        mController.attachToWindowToken(mWindowTokenClient, mWindowToken);

        mController.onWindowContextConfigurationChanged(
                mClientToken, mConfiguration, DEFAULT_DISPLAY);

        verify(mWindowTokenClient).onConfigurationChanged(mConfiguration, DEFAULT_DISPLAY);
    }

    @Test
    public void testOnWindowContextWindowRemoved() {
        mController.onWindowContextWindowRemoved(mClientToken);

        verify(mWindowTokenClient, never()).onWindowTokenRemoved();

        mController.attachToWindowToken(mWindowTokenClient, mWindowToken);

        mController.onWindowContextWindowRemoved(mClientToken);

        verify(mWindowTokenClient).onWindowTokenRemoved();
    }
}
