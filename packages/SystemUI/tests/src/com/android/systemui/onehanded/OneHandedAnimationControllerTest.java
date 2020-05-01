/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.onehanded;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests against {@link OneHandedAnimationController} to ensure that it sends the right
 * callbacks
 * depending on the various interactions.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class OneHandedAnimationControllerTest extends OneHandedTestCase {
    private static final int TEST_BOUNDS_WIDTH = 1000;
    private static final int TEST_BOUNDS_HEIGHT = 1000;

    OneHandedAnimationController mOneHandedAnimationController;

    @Mock
    private SurfaceControl mMockLeash;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mOneHandedAnimationController = new OneHandedAnimationController(mContext,
                new OneHandedSurfaceTransactionHelper(mContext));
    }

    @Test
    public void testGetAnimator_withSameBounds_returnTranslateAnimator() {
        final Rect originalBounds = new Rect(0, 0, TEST_BOUNDS_WIDTH, TEST_BOUNDS_HEIGHT);
        final Rect destinationBounds = originalBounds;
        destinationBounds.offset(0, 300);
        final OneHandedAnimationController.OneHandedTransitionAnimator animator =
                mOneHandedAnimationController
                        .getAnimator(mMockLeash, originalBounds, destinationBounds);

        assertEquals("Expected translate animation",
                OneHandedAnimationController.ANIM_TYPE_TRANSLATE, animator.getAnimationType());
    }
}
