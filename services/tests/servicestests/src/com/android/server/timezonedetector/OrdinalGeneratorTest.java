/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.timezonedetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.function.Function;

@RunWith(AndroidJUnit4.class)
public class OrdinalGeneratorTest {

    @Test
    public void testOrdinal_withIdentityFunction() {
        OrdinalGenerator<String> ordinalGenerator = new OrdinalGenerator<>(Function.identity());
        int oneOrd = ordinalGenerator.ordinal("One");
        int twoOrd = ordinalGenerator.ordinal("Two");
        assertNotEquals(oneOrd, twoOrd);

        assertEquals(oneOrd, ordinalGenerator.ordinal("One"));
        assertEquals(twoOrd, ordinalGenerator.ordinal("Two"));

        int threeOrd = ordinalGenerator.ordinal("Three");
        assertNotEquals(oneOrd, threeOrd);
        assertNotEquals(twoOrd, threeOrd);
    }

    @Test
    public void testOrdinals_withIdentityFunction() {
        OrdinalGenerator<String> ordinalGenerator = new OrdinalGenerator<>(Function.identity());
        int[] oneTwoOrds = ordinalGenerator.ordinals(Arrays.asList("One", "Two"));
        int[] twoThreeOrds = ordinalGenerator.ordinals(Arrays.asList("Two", "Three"));
        assertEquals(oneTwoOrds[0], ordinalGenerator.ordinal("One"));
        assertEquals(oneTwoOrds[1], ordinalGenerator.ordinal("Two"));
        assertEquals(twoThreeOrds[0], ordinalGenerator.ordinal("Two"));
        assertEquals(twoThreeOrds[1], ordinalGenerator.ordinal("Three"));
    }

    @Test
    public void testOrdinal_withCanonicalizationFunction() {
        OrdinalGenerator<String> ordinalGenerator = new OrdinalGenerator<>(String::toLowerCase);

        int oneOrd = ordinalGenerator.ordinal("One");
        int twoOrd = ordinalGenerator.ordinal("Two");
        assertNotEquals(oneOrd, twoOrd);

        assertEquals(oneOrd, ordinalGenerator.ordinal("ONE"));
        assertEquals(twoOrd, ordinalGenerator.ordinal("two"));

        int threeOrd = ordinalGenerator.ordinal("Three");
        assertNotEquals(oneOrd, threeOrd);
        assertNotEquals(twoOrd, threeOrd);
    }

    @Test
    public void testOrdinals_withCanonicalizationFunction() {
        OrdinalGenerator<String> ordinalGenerator = new OrdinalGenerator<>(String::toLowerCase);

        int[] oneTwoOrds = ordinalGenerator.ordinals(Arrays.asList("One", "Two"));
        int[] twoThreeOrds = ordinalGenerator.ordinals(Arrays.asList("Two", "Three"));

        assertEquals(oneTwoOrds[0], ordinalGenerator.ordinal("ONE"));
        assertEquals(oneTwoOrds[1], ordinalGenerator.ordinal("two"));
        assertEquals(twoThreeOrds[0], ordinalGenerator.ordinal("TWO"));
        assertEquals(twoThreeOrds[1], ordinalGenerator.ordinal("threE"));
    }
}
