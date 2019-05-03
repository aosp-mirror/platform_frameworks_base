/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyZeroInteractions;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the {@link TaskStack} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:AnimatingAppWindowTokenRegistryTest
 */
@SmallTest
@Presubmit
public class AnimatingAppWindowTokenRegistryTest extends WindowTestsBase {

    @Mock
    AnimationAdapter mAdapter;

    @Mock
    Runnable mMockEndDeferFinishCallback1;
    @Mock
    Runnable mMockEndDeferFinishCallback2;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testDeferring() {
        final AppWindowToken window1 = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final AppWindowToken window2 = createAppWindow(window1.getTask(), ACTIVITY_TYPE_STANDARD,
                "window2").mAppToken;
        final AnimatingAppWindowTokenRegistry registry =
                window1.getStack().getAnimatingAppWindowTokenRegistry();

        window1.startAnimation(window1.getPendingTransaction(), mAdapter, false /* hidden */);
        window2.startAnimation(window1.getPendingTransaction(), mAdapter, false /* hidden */);
        assertTrue(window1.isSelfAnimating());
        assertTrue(window2.isSelfAnimating());

        // Make sure that first animation finish is deferred, second one is not deferred, and first
        // one gets cancelled.
        assertTrue(registry.notifyAboutToFinish(window1, mMockEndDeferFinishCallback1));
        assertFalse(registry.notifyAboutToFinish(window2, mMockEndDeferFinishCallback2));
        verify(mMockEndDeferFinishCallback1).run();
        verifyZeroInteractions(mMockEndDeferFinishCallback2);
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testContainerRemoved() {
        final AppWindowToken window1 = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final AppWindowToken window2 = createAppWindow(window1.getTask(), ACTIVITY_TYPE_STANDARD,
                "window2").mAppToken;
        final AnimatingAppWindowTokenRegistry registry =
                window1.getStack().getAnimatingAppWindowTokenRegistry();

        window1.startAnimation(window1.getPendingTransaction(), mAdapter, false /* hidden */);
        window2.startAnimation(window1.getPendingTransaction(), mAdapter, false /* hidden */);
        assertTrue(window1.isSelfAnimating());
        assertTrue(window2.isSelfAnimating());

        // Make sure that first animation finish is deferred, and removing the second window stops
        // finishes all pending deferred finishings.
        registry.notifyAboutToFinish(window1, mMockEndDeferFinishCallback1);
        window2.setParent(null);
        verify(mMockEndDeferFinishCallback1).run();
    }
}
