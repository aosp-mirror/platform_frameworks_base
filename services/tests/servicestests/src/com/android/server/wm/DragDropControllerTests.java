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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.ClipData;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.InputChannel;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link DragDropController} class.
 *
 * atest com.android.server.wm.DragDropControllerTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public class DragDropControllerTests extends WindowTestsBase {
    private static final int TIMEOUT_MS = 3000;
    private TestDragDropController mTarget;
    private WindowState mWindow;
    private IBinder mToken;

    static class TestDragDropController extends DragDropController {
        @GuardedBy("sWm.mWindowMap")
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
        final TaskStack stack = createStackControllerOnStackOnDisplay(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, mDisplayContent).mContainer;
        final Task task = createTaskInStack(stack, ownerId);
        task.addChild(token, 0);

        final WindowState window = createWindow(
                null, TYPE_BASE_APPLICATION, token, name, ownerId, false);
        window.mInputChannel = new InputChannel();
        window.mHasSurface = true;
        return window;
    }

    @Before
    public void setUp() throws Exception {
        final UserManagerInternal userManager = mock(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, userManager);

        super.setUp();

        mTarget = new TestDragDropController(sWm, sWm.mH.getLooper());
        mDisplayContent = spy(mDisplayContent);
        mWindow = createDropTargetWindow("Drag test window", 0);
        when(mDisplayContent.getTouchableWinAtPointLocked(0, 0)).thenReturn(mWindow);
        when(sWm.mInputManager.transferTouchFocus(any(), any())).thenReturn(true);

        synchronized (sWm.mWindowMap) {
            sWm.mWindowMap.put(mWindow.mClient.asBinder(), mWindow);
        }
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        final CountDownLatch latch;
        synchronized (sWm.mWindowMap) {
            if (!mTarget.dragDropActiveLocked()) {
                return;
            }
            if (mToken != null) {
                mTarget.cancelDragAndDrop(mToken);
            }
            latch = new CountDownLatch(1);
            mTarget.setOnClosedCallbackLocked(() -> {
                latch.countDown();
            });
        }
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testDragFlow() throws Exception {
        dragFlow(0, ClipData.newPlainText("label", "Test"), 0, 0);
    }

    @Test
    public void testPerformDrag_NullDataWithGrantUri() throws Exception {
        dragFlow(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ, null, 0, 0);
    }

    @Test
    public void testPerformDrag_NullDataToOtherUser() throws Exception {
        final WindowState otherUsersWindow =
                createDropTargetWindow("Other user's window", 1 * UserHandle.PER_USER_RANGE);
        when(mDisplayContent.getTouchableWinAtPointLocked(10, 10))
                .thenReturn(otherUsersWindow);

        dragFlow(0, null, 10, 10);
    }

    private void dragFlow(int flag, ClipData data, float dropX, float dropY) {
        final SurfaceSession appSession = new SurfaceSession();
        try {
            final SurfaceControl surface = new SurfaceControl.Builder(appSession)
                    .setName("drag surface")
                    .setSize(100, 100)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .build();

            assertTrue(sWm.mInputManager.transferTouchFocus(null, null));
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
