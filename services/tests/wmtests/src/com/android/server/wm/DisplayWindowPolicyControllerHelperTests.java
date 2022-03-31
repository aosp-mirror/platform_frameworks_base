/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;

import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.UserHandle;
import android.util.ArraySet;
import android.view.Display;
import android.window.DisplayWindowPolicyController;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;

/**
 * Tests for the {@link DisplayWindowPolicyControllerHelper} class.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayWindowPolicyControllerHelperTests
 */
@RunWith(WindowTestRunner.class)
public class DisplayWindowPolicyControllerHelperTests extends WindowTestsBase {
    private static final int TEST_USER_0_ID = 0;
    private static final int TEST_USER_1_ID = 10;

    private TestDisplayWindowPolicyController mDwpc = new TestDisplayWindowPolicyController();
    private DisplayContent mSecondaryDisplay;

    @Before
    public void setUp() {
        doReturn(mDwpc).when(mWm.mDisplayManagerInternal)
                .getDisplayWindowPolicyController(anyInt());
        mSecondaryDisplay = createNewDisplay();
        assertNotEquals(Display.DEFAULT_DISPLAY, mSecondaryDisplay.getDisplayId());
        assertTrue(mSecondaryDisplay.mDwpcHelper.hasController());
    }

    @Test
    public void testOnRunningActivityChanged() {
        final ActivityRecord activity1 = launchActivityOnDisplay(mSecondaryDisplay, TEST_USER_0_ID);
        verifyTopActivityAndRunningUid(activity1,
                true /* expectedUid0 */, false /* expectedUid1 */);
        final ActivityRecord activity2 = launchActivityOnDisplay(mSecondaryDisplay, TEST_USER_1_ID);
        verifyTopActivityAndRunningUid(activity2,
                true /* expectedUid0 */, true /* expectedUid1 */);
        final ActivityRecord activity3 = launchActivityOnDisplay(mSecondaryDisplay, TEST_USER_0_ID);
        verifyTopActivityAndRunningUid(activity3,
                true /* expectedUid0 */, true /* expectedUid1 */);

        activity3.finishing = true;
        verifyTopActivityAndRunningUid(activity2,
                true /* expectedUid0 */, true /* expectedUid1 */);

        activity2.finishing = true;
        verifyTopActivityAndRunningUid(activity1,
                true /* expectedUid0 */, false /* expectedUid1 */);

        activity1.finishing = true;
        verifyTopActivityAndRunningUid(null /* expectedTopActivity */,
                false /* expectedUid0 */, false /* expectedUid1 */);
    }

    private void verifyTopActivityAndRunningUid(ActivityRecord expectedTopActivity,
            boolean expectedUid0, boolean expectedUid1) {
        mSecondaryDisplay.onRunningActivityChanged();
        int uidAmount = (expectedUid0 && expectedUid1) ? 2 : (expectedUid0 || expectedUid1) ? 1 : 0;
        assertEquals(expectedTopActivity == null ? null :
                expectedTopActivity.info.getComponentName(), mDwpc.mTopActivity);
        assertEquals(expectedTopActivity == null ? UserHandle.USER_NULL :
                expectedTopActivity.info.applicationInfo.uid, mDwpc.mTopActivityUid);
        assertEquals(uidAmount, mDwpc.mRunningUids.size());
        assertTrue(mDwpc.mRunningUids.contains(TEST_USER_0_ID) == expectedUid0);
        assertTrue(mDwpc.mRunningUids.contains(TEST_USER_1_ID) == expectedUid1);

    }

    private ActivityRecord launchActivityOnDisplay(DisplayContent display, int uid) {
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(display)
                .setUserId(uid)
                .build();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(task)
                .setUid(uid)
                .setOnTop(true)
                .build();
        return activity;
    }

    @Test
    public void testIsWindowingModeSupported_noController_returnTrueForAnyWindowingMode() {
        doReturn(null).when(mWm.mDisplayManagerInternal)
                .getDisplayWindowPolicyController(anyInt());
        mSecondaryDisplay = createNewDisplay();
        assertFalse(mSecondaryDisplay.mDwpcHelper.hasController());

        assertTrue(mSecondaryDisplay.mDwpcHelper.isWindowingModeSupported(WINDOWING_MODE_PINNED));
        assertTrue(
                mSecondaryDisplay.mDwpcHelper.isWindowingModeSupported(WINDOWING_MODE_FULLSCREEN));
    }

    @Test
    public void testIsWindowingModeSupported_withoutSettingSupportedMode_returnFalse() {
        assertFalse(mSecondaryDisplay.mDwpcHelper.isWindowingModeSupported(WINDOWING_MODE_PINNED));
    }

    @Test
    public void testIsWindowingModeSupported_withoutSupportedMode_defaultSupportFullScreen() {
        assertTrue(
                mSecondaryDisplay.mDwpcHelper.isWindowingModeSupported(WINDOWING_MODE_FULLSCREEN));
    }

    @Test
    public void testIsWindowingModeSupported_setPinnedMode_returnTrue() {
        Set<Integer> supportedWindowingMode = new ArraySet<>();
        supportedWindowingMode.add(WINDOWING_MODE_PINNED);

        mDwpc.setSupportedWindowingModes(supportedWindowingMode);

        assertTrue(mSecondaryDisplay.mDwpcHelper.isWindowingModeSupported(WINDOWING_MODE_PINNED));
    }

    private class TestDisplayWindowPolicyController extends DisplayWindowPolicyController {

        ComponentName mTopActivity = null;
        int mTopActivityUid = UserHandle.USER_NULL;
        ArraySet<Integer> mRunningUids = new ArraySet<>();

        @Override
        public boolean canContainActivities(@NonNull List<ActivityInfo> activities,
                @WindowConfiguration.WindowingMode int windowingMode) {
            return false;
        }

        @Override
        public boolean keepActivityOnWindowFlagsChanged(ActivityInfo activityInfo, int windowFlags,
                int systemWindowFlags) {
            return false;
        }

        @Override
        public void onTopActivityChanged(ComponentName topActivity, int uid) {
            super.onTopActivityChanged(topActivity, uid);
            mTopActivity = topActivity;
            mTopActivityUid = uid;
        }

        @Override
        public void onRunningAppsChanged(ArraySet<Integer> runningUids) {
            super.onRunningAppsChanged(runningUids);
            mRunningUids.clear();
            mRunningUids.addAll(runningUids);
        }
    }
}
