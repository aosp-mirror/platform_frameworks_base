/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link AppTransition}.
 *
 * Build/Install/Run:
 *  atest WmTests:UnknownAppVisibilityControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class UnknownAppVisibilityControllerTest extends WindowTestsBase {

    @Before
    public void setUp() throws Exception {
        mDisplayContent.mUnknownAppVisibilityController.clear();
    }

    @Test
    public void testFlow() {
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(activity);
        mDisplayContent.mUnknownAppVisibilityController.notifyAppResumedFinished(activity);
        mDisplayContent.mUnknownAppVisibilityController.notifyRelayouted(activity);

        // Make sure our handler processed the message.
        waitHandlerIdle(mWm.mH);
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testSkipResume() {
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        activity.mLaunchTaskBehind = true;
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(activity);
        mDisplayContent.mUnknownAppVisibilityController.notifyRelayouted(activity);

        // Make sure our handler processed the message.
        waitHandlerIdle(mWm.mH);
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testMultiple() {
        final ActivityRecord activity1 = createNonAttachedActivityRecord(mDisplayContent);
        final ActivityRecord activity2 = createNonAttachedActivityRecord(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(activity1);
        mDisplayContent.mUnknownAppVisibilityController.notifyAppResumedFinished(activity1);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(activity2);
        mDisplayContent.mUnknownAppVisibilityController.notifyRelayouted(activity1);
        mDisplayContent.mUnknownAppVisibilityController.notifyAppResumedFinished(activity2);
        mDisplayContent.mUnknownAppVisibilityController.notifyRelayouted(activity2);

        // Make sure our handler processed the message.
        waitHandlerIdle(mWm.mH);
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testClear() {
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(activity);
        mDisplayContent.mUnknownAppVisibilityController.clear();
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testRemoveFinishingInvisibleActivityFromUnknown() {
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(activity);
        activity.finishing = true;
        activity.mVisibleRequested = true;
        activity.setVisibility(false, false);
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testAppRemoved() {
        final ActivityRecord activity = createNonAttachedActivityRecord(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(activity);
        mDisplayContent.mUnknownAppVisibilityController.appRemovedOrHidden(activity);
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }
}
