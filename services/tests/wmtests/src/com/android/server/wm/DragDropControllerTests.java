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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.platform.test.annotations.Presubmit;
import android.view.InputChannel;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for the {@link DragDropController} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:DragDropControllerTests
 */
@SmallTest
@Presubmit
public class DragDropControllerTests extends WindowTestsBase {
    private static final int TIMEOUT_MS = 3000;
    private TestDragDropController mTarget;
    private WindowState mWindow;
    private IBinder mToken;

    static class TestDragDropController extends DragDropController {
        private Runnable mCloseCallback;

        TestDragDropController(WindowManagerService service, Looper looper) {
            super(service, looper);
        }

        void setOnClosedCallbackLocked(Runnable runnable) {
            assertTrue(dragDropActiveLocked());
            mCloseCallback = runnable;
        }

        @Override
        void onDragStateClosedLocked(DragState dragState) {
            super.onDragStateClosedLocked(dragState);
            if (mCloseCallback != null) {
                mCloseCallback.run();
                mCloseCallback = null;
            }
        }
    }

    /**
     * Creates a window state which can be used as a drop target.
     */
    private WindowState createDropTargetWindow(String name, int ownerId) {
        final WindowTestUtils.TestAppWindowToken token = WindowTestUtils.createTestAppWindowToken(
                mDisplayContent);
        final TaskStack stack = createTaskStackOnDisplay(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task task = createTaskInStack(stack, ownerId);
        task.addChild(token, 0);

        final WindowState window = createWindow(
                null, TYPE_BASE_APPLICATION, token, name, ownerId, false);
        window.mInputChannel = new InputChannel();
        window.mHasSurface = true;
        return window;
    }

    @BeforeClass
    public static void setUpOnce() {
        final UserManagerInternal userManager = mock(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, userManager);
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
    }

    @Before
    public void setUp() throws Exception {
        mTarget = new TestDragDropController(mWm, mWm.mH.getLooper());
        mDisplayContent = spy(mDisplayContent);
        mWindow = createDropTargetWindow("Drag test window", 0);
        doReturn(mWindow).when(mDisplayContent).getTouchableWinAtPointLocked(0, 0);

        synchronized (mWm.mGlobalLock) {
            mWm.mWindowMap.put(mWindow.mClient.asBinder(), mWindow);
        }
    }

    @After
    public void tearDown() throws Exception {
        final CountDownLatch latch;
        synchronized (mWm.mGlobalLock) {
            if (!mTarget.dragDropActiveLocked()) {
                return;
            }
            if (mToken != null) {
                mTarget.cancelDragAndDrop(mToken);
            }
            latch = new CountDownLatch(1);
            mTarget.setOnClosedCallbackLocked(latch::countDown);
        }
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testDragFlow() {
        dragFlow(0, ClipData.newPlainText("label", "Test"), 0, 0);
    }

    @Test
    public void testPerformDrag_NullDataWithGrantUri() {
        dragFlow(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ, null, 0, 0);
    }

    @Test
    public void testPerformDrag_NullDataToOtherUser() {
        final WindowState otherUsersWindow =
                createDropTargetWindow("Other user's window", 1 * UserHandle.PER_USER_RANGE);
        doReturn(otherUsersWindow).when(mDisplayContent).getTouchableWinAtPointLocked(10, 10);

        dragFlow(0, null, 10, 10);
    }

    private void dragFlow(int flag, ClipData data, float dropX, float dropY) {
        final SurfaceSession appSession = new SurfaceSession();
        try {
            final SurfaceControl surface = new SurfaceControl.Builder(appSession)
                    .setName("drag surface")
                    .setBufferSize(100, 100)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .build();

            mToken = mTarget.performDrag(
                    new SurfaceSession(), 0, 0, mWindow.mClient, flag, surface, 0, 0, 0, 0, 0,
                    data);
            assertNotNull(mToken);

            mTarget.handleMotionEvent(false, dropX, dropY);
            mToken = mWindow.mClient.asBinder();
        } finally {
            appSession.kill();
        }
    }
}
