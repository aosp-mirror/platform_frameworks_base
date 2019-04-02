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

/**
 * Test class for {@link AppTransition}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:UnknownAppVisibilityControllerTest
 */
@SmallTest
@Presubmit
public class UnknownAppVisibilityControllerTest extends WindowTestsBase {

    @Before
    public void setUp() throws Exception {
        mDisplayContent.mUnknownAppVisibilityController.clear();
    }

    @Test
    public void testFlow() {
        final AppWindowToken token = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(token);
        mDisplayContent.mUnknownAppVisibilityController.notifyAppResumedFinished(token);
        mDisplayContent.mUnknownAppVisibilityController.notifyRelayouted(token);

        // Make sure our handler processed the message.
        mWm.mH.runWithScissors(() -> { }, 0);
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testMultiple() {
        final AppWindowToken token1 = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        final AppWindowToken token2 = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(token1);
        mDisplayContent.mUnknownAppVisibilityController.notifyAppResumedFinished(token1);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(token2);
        mDisplayContent.mUnknownAppVisibilityController.notifyRelayouted(token1);
        mDisplayContent.mUnknownAppVisibilityController.notifyAppResumedFinished(token2);
        mDisplayContent.mUnknownAppVisibilityController.notifyRelayouted(token2);

        // Make sure our handler processed the message.
        mWm.mH.runWithScissors(() -> { }, 0);
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testClear() {
        final AppWindowToken token = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(token);
        mDisplayContent.mUnknownAppVisibilityController.clear();
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testAppRemoved() {
        final AppWindowToken token = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(token);
        mDisplayContent.mUnknownAppVisibilityController.appRemovedOrHidden(token);
        assertTrue(mDisplayContent.mUnknownAppVisibilityController.allResolved());
    }
}
