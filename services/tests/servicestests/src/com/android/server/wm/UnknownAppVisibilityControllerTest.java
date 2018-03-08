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
 * limitations under the License
 */

package com.android.server.wm;

import static junit.framework.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link AppTransition}.
 *
 * runtest frameworks-services -c com.android.server.wm.UnknownAppVisibilityControllerTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class UnknownAppVisibilityControllerTest extends WindowTestsBase {

    @Before
    public void setUp() throws Exception {
        super.setUp();
        sWm.mUnknownAppVisibilityController.clear();
    }

    @Test
    public void testFlow() throws Exception {
        final AppWindowToken token = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        sWm.mUnknownAppVisibilityController.notifyLaunched(token);
        sWm.mUnknownAppVisibilityController.notifyAppResumedFinished(token);
        sWm.mUnknownAppVisibilityController.notifyRelayouted(token);

        // Make sure our handler processed the message.
        Thread.sleep(100);
        assertTrue(sWm.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testMultiple() throws Exception {
        final AppWindowToken token1 = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        final AppWindowToken token2 = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        sWm.mUnknownAppVisibilityController.notifyLaunched(token1);
        sWm.mUnknownAppVisibilityController.notifyAppResumedFinished(token1);
        sWm.mUnknownAppVisibilityController.notifyLaunched(token2);
        sWm.mUnknownAppVisibilityController.notifyRelayouted(token1);
        sWm.mUnknownAppVisibilityController.notifyAppResumedFinished(token2);
        sWm.mUnknownAppVisibilityController.notifyRelayouted(token2);

        // Make sure our handler processed the message.
        Thread.sleep(100);
        assertTrue(sWm.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testClear() throws Exception {
        final AppWindowToken token = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        sWm.mUnknownAppVisibilityController.notifyLaunched(token);
        sWm.mUnknownAppVisibilityController.clear();;
        assertTrue(sWm.mUnknownAppVisibilityController.allResolved());
    }

    @Test
    public void testAppRemoved() throws Exception {
        final AppWindowToken token = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        sWm.mUnknownAppVisibilityController.notifyLaunched(token);
        sWm.mUnknownAppVisibilityController.appRemovedOrHidden(token);
        assertTrue(sWm.mUnknownAppVisibilityController.allResolved());
    }
}
