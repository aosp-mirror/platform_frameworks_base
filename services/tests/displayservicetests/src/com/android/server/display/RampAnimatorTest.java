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
        mRampAnimator = new RampAnimator<>(mTestObject, mTestProperty);
    }

    @Test
    public void testInitialValueUsedInLastAnimationStep() {
        mRampAnimator.setAnimationTarget(0.67f, 0.1f);

        assertEquals(0.67f, mTestObject.mValue, 0);
    }

    private static class TestObject {
        private float mValue;
    }
}
