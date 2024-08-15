/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.util;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FloatMathTest {
    private static float DELTA = 0.0f;

    private static float[] TEST_VALUES = new float[] {
            0,
            1,
            30,
            90,
            123,
            360,
            1000,
    };

    @Test
    public void testSqrt() {
        for (float value : TEST_VALUES) {
            assertEquals((float) Math.sqrt(value), FloatMath.sqrt(value), DELTA);
        }
    }

    @Test
    public void testFloor() {
        for (float value : TEST_VALUES) {
            assertEquals((float) Math.floor(value), FloatMath.floor(value), DELTA);
        }
    }

    @Test
    public void testCeil() {
        for (float value : TEST_VALUES) {
            assertEquals((float) Math.ceil(value), FloatMath.ceil(value), DELTA);
        }
    }

    @Test
    public void testCos() {
        for (float value : TEST_VALUES) {
            assertEquals((float) Math.cos(value), FloatMath.cos(value), DELTA);
        }
    }

    @Test
    public void testSin() {
        for (float value : TEST_VALUES) {
            assertEquals((float) Math.sin(value), FloatMath.sin(value), DELTA);
        }
    }

    @Test
    public void testExp() {
        for (float value : TEST_VALUES) {
            assertEquals((float) Math.exp(value), FloatMath.exp(value), DELTA);
        }
    }

    @Test
    public void testPow() {
        for (float value : TEST_VALUES) {
            assertEquals((float) Math.pow(value, value), FloatMath.pow(value, value), DELTA);
        }
    }

    @Test
    public void testHypot() {
        for (float value : TEST_VALUES) {
            assertEquals((float) Math.hypot(value, value), FloatMath.hypot(value, value), DELTA);
        }
    }
}
