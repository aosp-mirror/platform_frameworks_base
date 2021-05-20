/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.accessibilityservice;

import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityEvent;
import android.window.WindowTokenClient;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for AccessibilityService.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AccessibilityServiceTest {
    private static final String TAG = "AccessibilityServiceTest";
    private static final int CONNECTION_ID = 1;
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams(
            TYPE_ACCESSIBILITY_OVERLAY);

    private static class AccessibilityServiceTestClass extends AccessibilityService {
        private IAccessibilityServiceClient mCallback;
        private Looper mLooper;

        AccessibilityServiceTestClass() {
            super();
            Context context = ApplicationProvider.getApplicationContext();
            final Display display = context.getSystemService(DisplayManager.class)
                    .getDisplay(DEFAULT_DISPLAY);

            attachBaseContext(context.createTokenContext(new WindowTokenClient(), display));
            mLooper = InstrumentationRegistry.getContext().getMainLooper();
        }

        public void setupCallback(IAccessibilityServiceClient callback) {
            mCallback = callback;
        }

        public Looper getMainLooper() {
            return mLooper;
        }

        public void onAccessibilityEvent(AccessibilityEvent event) { }
        public void onInterrupt() { }

        @Override
        public void onSystemActionsChanged() {
            try {
                if (mCallback != null) mCallback.onSystemActionsChanged();
            } catch (RemoteException e) {
            }
        }
    }

    private @Mock IAccessibilityServiceClient  mMockClientForCallback;
    private @Mock IAccessibilityServiceConnection mMockConnection;
    private @Mock IBinder mMockIBinder;
    private IAccessibilityServiceClient mServiceInterface;
    private AccessibilityServiceTestClass mService;
    private final SparseArray<IBinder> mWindowTokens = new SparseArray<>();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mService = new AccessibilityServiceTestClass();
        mService.onCreate();
        mService.setupCallback(mMockClientForCallback);
        mServiceInterface = (IAccessibilityServiceClient) mService.onBind(new Intent());
        mServiceInterface.init(mMockConnection, CONNECTION_ID, mMockIBinder);
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            final int displayId = (int) args[0];
            final IBinder token = new Binder();
            WindowManagerGlobal.getWindowManagerService().addWindowToken(token,
                    TYPE_ACCESSIBILITY_OVERLAY, displayId, null /* options */);
            mWindowTokens.put(displayId, token);
            return token;
        }).when(mMockConnection).getOverlayWindowToken(anyInt());
    }

    @After
    public void tearDown() throws Exception {
        for (int i = mWindowTokens.size() - 1; i >= 0; --i) {
            WindowManagerGlobal.getWindowManagerService().removeWindowToken(
                    mWindowTokens.valueAt(i), mWindowTokens.keyAt(i));
        }
    }

    @Test
    public void testOnSystemActionsChanged() throws RemoteException {
        mServiceInterface.onSystemActionsChanged();

        verify(mMockClientForCallback).onSystemActionsChanged();
    }

    @Test
    public void testGetSystemActions() throws RemoteException {
        mService.getSystemActions();

        verify(mMockConnection).getSystemActions();
    }

    @Test
    public void testAddViewWithA11yServiceDerivedDisplayContext() throws Exception {
        try (VirtualDisplaySession session = new VirtualDisplaySession()) {
            final Context context = mService.createDisplayContext(session.getDisplay());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> context.getSystemService(WindowManager.class)
                            .addView(new View(context), mParams)
            );
        }
    }

    @Test
    public void testAddViewWithA11yServiceDerivedWindowContext() throws Exception {
        try (VirtualDisplaySession session = new VirtualDisplaySession()) {
            final Context context = mService.createDisplayContext(session.getDisplay())
                    .createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null /* options */);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> context.getSystemService(WindowManager.class)
                            .addView(new View(context), mParams)
            );
        }
    }

    @Test
    public void testAddViewWithA11yServiceDerivedWindowContextWithDisplay() throws Exception {
        try (VirtualDisplaySession session = new VirtualDisplaySession()) {
            final Context context = mService.createWindowContext(session.getDisplay(),
                    TYPE_ACCESSIBILITY_OVERLAY, null /* options */);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> context.getSystemService(WindowManager.class)
                            .addView(new View(context), mParams)
            );
        }
    }

    @Test(expected = WindowManager.BadTokenException.class)
    public void testAddViewWithA11yServiceDerivedWindowContextWithDifferentType()
            throws Exception {
        try (VirtualDisplaySession session = new VirtualDisplaySession()) {
            final Context context = mService.createWindowContext(session.getDisplay(),
                    TYPE_APPLICATION_OVERLAY, null /* options */);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> context.getSystemService(WindowManager.class)
                            .addView(new View(context), mParams)
            );
        }
    }


    private static class VirtualDisplaySession implements AutoCloseable {
        private final VirtualDisplay mVirtualDisplay;

        VirtualDisplaySession() {
            final DisplayManager displayManager = ApplicationProvider.getApplicationContext()
                    .getSystemService(DisplayManager.class);
            final int width = 800;
            final int height = 480;
            final int density = 160;
            ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
                    2 /* maxImages */);
            mVirtualDisplay = displayManager.createVirtualDisplay(
                    TAG, width, height, density, reader.getSurface(),
                    VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        }

        private Display getDisplay() {
            return mVirtualDisplay.getDisplay();
        }

        @Override
        public void close() throws Exception {
            mVirtualDisplay.release();
        }
    }
}
