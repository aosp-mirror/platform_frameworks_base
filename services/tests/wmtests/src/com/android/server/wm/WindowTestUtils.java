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

import static android.app.AppOpsManager.OP_NONE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import android.os.IBinder;
import android.view.IWindow;
import android.view.WindowManager;

import com.android.server.wm.ActivityTestsBase.ActivityBuilder;

/**
 * A collection of static functions that provide access to WindowManager related test functionality.
 */
class WindowTestUtils {

    /** Creates a {@link Task} and adds it to the specified {@link ActivityStack}. */
    static Task createTaskInStack(WindowManagerService service, ActivityStack stack, int userId) {
        final Task task = new ActivityTestsBase.TaskBuilder(stack.mStackSupervisor)
                .setUserId(userId)
                .setStack(stack)
                .build();
        return task;
    }

    /** Creates an {@link ActivityRecord} and adds it to the specified {@link Task}. */
    static ActivityRecord createActivityRecordInTask(DisplayContent dc, Task task) {
        final ActivityRecord activity = createTestActivityRecord(dc);
        task.addChild(activity, POSITION_TOP);
        return activity;
    }

    static ActivityRecord createTestActivityRecord(ActivityStack stack) {
        final ActivityRecord activity = new ActivityTestsBase.ActivityBuilder(stack.mAtmService)
                .setStack(stack)
                .setCreateTask(true)
                .build();
        postCreateActivitySetup(activity, stack.getDisplayContent());
        return activity;
    }

    static ActivityRecord createTestActivityRecord(DisplayContent dc) {
        final ActivityRecord activity = new ActivityBuilder(dc.mWmService.mAtmService).build();
        postCreateActivitySetup(activity, dc);
        return activity;
    }

    private static void postCreateActivitySetup(ActivityRecord activity, DisplayContent dc) {
        activity.onDisplayChanged(dc);
        activity.setOccludesParent(true);
        activity.setVisible(true);
        activity.mVisibleRequested = true;
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc) {
        return createTestWindowToken(type, dc, false /* persistOnEmpty */);
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc,
            boolean persistOnEmpty) {
        SystemServicesTestRule.checkHoldsLock(dc.mWmService.mGlobalLock);

        return new TestWindowToken(type, dc, persistOnEmpty);
    }

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    static class TestWindowToken extends WindowToken {

        private TestWindowToken(int type, DisplayContent dc, boolean persistOnEmpty) {
            super(dc.mWmService, mock(IBinder.class), type, persistOnEmpty, dc,
                    false /* ownerCanManageAppTokens */);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }
    }

    /** Used to track resize reports. */
    static class TestWindowState extends WindowState {
        boolean mResizeReported;

        TestWindowState(WindowManagerService service, Session session, IWindow window,
                WindowManager.LayoutParams attrs, WindowToken token) {
            super(service, session, window, token, null, OP_NONE, 0, attrs, 0, 0, 0,
                    false /* ownerCanAddInternalSystemWindow */);
        }

        @Override
        void reportResized() {
            super.reportResized();
            mResizeReported = true;
        }

        @Override
        public boolean isGoneForLayoutLw() {
            return false;
        }

        @Override
        void updateResizingWindowIfNeeded() {
            // Used in AppWindowTokenTests#testLandscapeSeascapeRotationRelayout to deceive
            // the system that it can actually update the window.
            boolean hadSurface = mHasSurface;
            mHasSurface = true;

            super.updateResizingWindowIfNeeded();

            mHasSurface = hadSurface;
        }
    }
}
