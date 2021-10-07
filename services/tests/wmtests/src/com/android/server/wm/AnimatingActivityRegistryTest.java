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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyZeroInteractions;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the {@link ActivityStack} class.
 *
 * Build/Install/Run:
 *  atest WmTests:AnimatingActivityRegistryTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AnimatingActivityRegistryTest extends WindowTestsBase {

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
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        final ActivityRecord activity2 = createAppWindow(activity1.getTask(), ACTIVITY_TYPE_STANDARD,
                "activity2").mActivityRecord;
        final AnimatingActivityRegistry registry =
                activity1.getRootTask().getAnimatingActivityRegistry();

        activity1.startAnimation(activity1.getPendingTransaction(), mAdapter, false /* hidden */,
                ANIMATION_TYPE_APP_TRANSITION);
        activity2.startAnimation(activity1.getPendingTransaction(), mAdapter, false /* hidden */,
                ANIMATION_TYPE_APP_TRANSITION);
        assertTrue(activity1.isAnimating(TRANSITION));
        assertTrue(activity2.isAnimating(TRANSITION));

        // Make sure that first animation finish is deferred, second one is not deferred, and first
        // one gets cancelled.
        assertTrue(registry.notifyAboutToFinish(activity1, mMockEndDeferFinishCallback1));
        assertFalse(registry.notifyAboutToFinish(activity2, mMockEndDeferFinishCallback2));
        verify(mMockEndDeferFinishCallback1).run();
        verifyZeroInteractions(mMockEndDeferFinishCallback2);
    }

    @Test
    public void testContainerRemoved() {
        final ActivityRecord window1 = createActivityRecord(mDisplayContent);
        final ActivityRecord window2 = createAppWindow(window1.getTask(), ACTIVITY_TYPE_STANDARD,
                "window2").mActivityRecord;
        final AnimatingActivityRegistry registry =
                window1.getRootTask().getAnimatingActivityRegistry();

        window1.startAnimation(window1.getPendingTransaction(), mAdapter, false /* hidden */,
                ANIMATION_TYPE_APP_TRANSITION);
        window2.startAnimation(window1.getPendingTransaction(), mAdapter, false /* hidden */,
                ANIMATION_TYPE_APP_TRANSITION);
        assertTrue(window1.isAnimating(TRANSITION));
        assertTrue(window2.isAnimating(TRANSITION));

        // Make sure that first animation finish is deferred, and removing the second window stops
        // finishes all pending deferred finishings.
        registry.notifyAboutToFinish(window1, mMockEndDeferFinishCallback1);
        window2.setParent(null);
        verify(mMockEndDeferFinishCallback1).run();
    }
}
