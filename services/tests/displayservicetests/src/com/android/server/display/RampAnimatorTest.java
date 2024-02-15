/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display;

import static org.junit.Assert.assertEquals;

import android.util.FloatProperty;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class RampAnimatorTest {

    private static final float FLOAT_TOLERANCE = 0.0000001f;

    private RampAnimator<TestObject> mRampAnimator;

    private final TestObject mTestObject = new TestObject();

    private final FloatProperty<TestObject> mTestProperty = new FloatProperty<>("mValue") {
        @Override
        public void setValue(TestObject object, float value) {
            object.mValue = value;
        }

        @Override
        public Float get(TestObject object) {
            return object.mValue;
        }
    };

    @Before
    public void setUp() {
        mRampAnimator = new RampAnimator<>(mTestObject, mTestProperty, () -> 0);
    }

    @Test
    public void testInitialValueUsedInLastAnimationStep() {
        mRampAnimator.setAnimationTarget(0.67f, 0.1f, false);

        assertEquals(0.67f, mTestObject.mValue, 0);
    }

    @Test
    public void testAnimationStep_respectTimeLimits() {
        // animation is limited to 2s
        mRampAnimator.setAnimationTimeLimits(2_000, 2_000);
        // initial brightness value, applied immediately, in HLG = 0.8716434
        mRampAnimator.setAnimationTarget(0.5f, 0.1f, false);
        // expected brightness, in HLG = 0.9057269
        // delta = 0.0340835, duration = 3.40835s > 2s
        // new rate = delta/2 = 0.01704175 u/s
        mRampAnimator.setAnimationTarget(0.6f, 0.01f, false);
        // animation step = 1s, new HGL = 0.88868515
        mRampAnimator.performNextAnimationStep(1_000_000_000);
        // converted back to Linear
        assertEquals(0.54761934f, mTestObject.mValue, FLOAT_TOLERANCE);
    }

    @Test
    public void testAnimationStep_ignoreTimeLimits() {
        // animation is limited to 2s
        mRampAnimator.setAnimationTimeLimits(2_000, 2_000);
        // initial brightness value, applied immediately, in HLG = 0.8716434
        mRampAnimator.setAnimationTarget(0.5f, 0.1f, false);
        // rate = 0.01f, time limits are ignored
        mRampAnimator.setAnimationTarget(0.6f, 0.01f, true);
        // animation step = 1s, new HGL = 0.8816434
        mRampAnimator.performNextAnimationStep(1_000_000_000);
        // converted back to Linear
        assertEquals(0.52739114f, mTestObject.mValue, FLOAT_TOLERANCE);
    }

    private static class TestObject {
        private float mValue;
    }
}
