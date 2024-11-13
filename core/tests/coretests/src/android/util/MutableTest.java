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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MutableTest {
    @Test
    public void testMutableBoolean() {
        MutableBoolean mut = new MutableBoolean(false);
        assertFalse(mut.value);
        mut = new MutableBoolean(true);
        assertTrue(mut.value);
    }

    @Test
    public void testMutableByte() {
        MutableByte mut = new MutableByte((byte) 127);
        assertEquals(127, mut.value);
        mut = new MutableByte((byte) -128);
        assertEquals(-128, mut.value);
    }

    @Test
    public void testMutableChar() {
        MutableChar mut = new MutableChar('a');
        assertEquals('a', mut.value);
        mut = new MutableChar('b');
        assertEquals('b', mut.value);
    }

    @Test
    public void testMutableDouble() {
        MutableDouble mut = new MutableDouble(0);
        assertEquals(0, mut.value, 0);
        mut = new MutableDouble(Double.MAX_VALUE);
        assertEquals(Double.MAX_VALUE, mut.value, 0);
    }

    @Test
    public void testMutableFloat() {
        MutableFloat mut = new MutableFloat(0f);
        assertEquals(0f, mut.value, 0);
        mut = new MutableFloat(Float.MAX_VALUE);
        assertEquals(Float.MAX_VALUE, mut.value, 0);
    }

    @Test
    public void testMutableShort() {
        MutableShort mut = new MutableShort((short) 0);
        assertEquals(0, mut.value);
        mut = new MutableShort(Short.MAX_VALUE);
        assertEquals(Short.MAX_VALUE, mut.value);
    }

    @Test
    public void testMutableInt() {
        MutableInt mut = new MutableInt(42);
        assertEquals(42, mut.value);
        mut = new MutableInt(21);
        assertEquals(21, mut.value);
    }

    @Test
    public void testMutableLong() {
        MutableLong mut = new MutableLong(42L);
        assertEquals(42L, mut.value);
        mut = new MutableLong(21L);
        assertEquals(21L, mut.value);
    }
}
