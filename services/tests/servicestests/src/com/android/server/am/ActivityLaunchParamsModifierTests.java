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

package com.android.server.am;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_DONE;
import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.am.LaunchParamsController.LaunchParams;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for exercising resizing bounds due to activity options.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ActivityLaunchParamsModifierTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityLaunchParamsModifierTests extends ActivityTestsBase {
    private ActivityLaunchParamsModifier mModifier;
    private ActivityManagerService mService;
    private ActivityStack mStack;
    private TaskRecord mTask;
    private ActivityRecord mActivity;

    private LaunchParams mCurrent;
    private LaunchParams mResult;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mService = createActivityManagerService();
        mModifier = new ActivityLaunchParamsModifier(mService.mStackSupervisor);
        mCurrent = new LaunchParams();
        mResult = new LaunchParams();


        mStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mTask = new TaskBuilder(mService.mStackSupervisor).setStack(mStack).build();
        mActivity = new ActivityBuilder(mService).setTask(mTask).build();
    }


    @Test
    public void testSkippedInvocations() throws Exception {
        // No specified activity should be ignored
        assertEquals(RESULT_SKIP, mModifier.onCalculate(null /*task*/, null /*layout*/,
                null /*activity*/, null /*source*/, null /*options*/, mCurrent, mResult));

        // No specified activity options should be ignored
        assertEquals(RESULT_SKIP, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, null /*options*/, mCurrent, mResult));

        // launch bounds specified should be ignored.
        final ActivityOptions options = ActivityOptions.makeBasic();
        assertEquals(RESULT_SKIP, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, options /*options*/, mCurrent, mResult));

        // Non-resizeable records should be ignored
        mActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
        assertFalse(mActivity.isResizeable());
        assertEquals(RESULT_SKIP, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, options /*options*/, mCurrent, mResult));

        // make record resizeable
        mActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;
        assertTrue(mActivity.isResizeable());

        assertEquals(RESULT_SKIP, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, options /*options*/, mCurrent, mResult));

        // Does not support freeform
        mService.mSupportsFreeformWindowManagement = false;
        assertFalse(mService.mStackSupervisor.canUseActivityOptionsLaunchBounds(options));
        assertEquals(RESULT_SKIP, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, options /*options*/, mCurrent, mResult));

        mService.mSupportsFreeformWindowManagement = true;
        options.setLaunchBounds(new Rect());
        assertTrue(mService.mStackSupervisor.canUseActivityOptionsLaunchBounds(options));

        // Invalid bounds
        assertEquals(RESULT_SKIP, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, options /*options*/, mCurrent, mResult));
        options.setLaunchBounds(new Rect(0, 0, -1, -1));
        assertEquals(RESULT_SKIP, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, options /*options*/, mCurrent, mResult));

        // Valid bounds should cause the positioner to be applied.
        options.setLaunchBounds(new Rect(0, 0, 100, 100));
        assertEquals(RESULT_DONE, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, options /*options*/, mCurrent, mResult));
    }

    @Test
    public void testBoundsExtraction() throws Exception {
        // Make activity resizeable and enable freeform mode.
        mActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;
        mService.mSupportsFreeformWindowManagement = true;

        ActivityOptions options = ActivityOptions.makeBasic();
        final Rect proposedBounds = new Rect(20, 30, 45, 40);
        options.setLaunchBounds(proposedBounds);

        assertEquals(RESULT_DONE, mModifier.onCalculate(null /*task*/, null /*layout*/,
                mActivity, null /*source*/, options /*options*/, mCurrent, mResult));
        assertEquals(mResult.mBounds, proposedBounds);
    }
}
