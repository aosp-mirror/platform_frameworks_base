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

package android.util;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.util.HexDump;

import dalvik.system.VMRuntime;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = CharsetUtils.class)
public class CharsetUtilsTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private byte[] dest;
    private long destPtr;

    @Before
    public void setUp() {
        if (!RavenwoodRule.isUnderRavenwood()) {
            dest = (byte[]) VMRuntime.getRuntime().newNonMovableArray(byte.class, 8);
            destPtr = VMRuntime.getRuntime().addressOf(dest);
        }
    }

    @Test
    public void testModifiedUtf8_Empty() {
        assertEquals(0, CharsetUtils.toModifiedUtf8Bytes("", destPtr, 0, dest.length));
        assertEquals("0000000000000000", HexDump.toHexString(dest));
        assertEquals("", CharsetUtils.fromModifiedUtf8Bytes(destPtr, 0, 0));
    }

    @Test
    public void testModifiedUtf8_Null() {
        assertEquals(4, CharsetUtils.toModifiedUtf8Bytes("!\0!", destPtr, 0, dest.length));
        assertEquals("21C0802100000000", HexDump.toHexString(dest));
        assertEquals("!\0!", CharsetUtils.fromModifiedUtf8Bytes(destPtr, 0, 4));
    }

    @Test
    public void testModifiedUtf8_Simple() {
        assertEquals(7, CharsetUtils.toModifiedUtf8Bytes("example", destPtr, 0, dest.length));
        assertEquals("6578616D706C6500", HexDump.toHexString(dest));
        assertEquals("example", CharsetUtils.fromModifiedUtf8Bytes(destPtr, 0, 7));
    }

    @Test
    public void testModifiedUtf8_Complex() {
        assertEquals(3, CharsetUtils.toModifiedUtf8Bytes("☃", destPtr, 4, dest.length));
        assertEquals("00000000E2988300", HexDump.toHexString(dest));
        assertEquals("☃", CharsetUtils.fromModifiedUtf8Bytes(destPtr, 4, 3));
    }

    @Test
    public void testModifiedUtf8_Bounds() {
        assertEquals(-3, CharsetUtils.toModifiedUtf8Bytes("foo", destPtr, 0, 0));
        assertEquals(-3, CharsetUtils.toModifiedUtf8Bytes("foo", destPtr, 0, 2));
        assertEquals(-3, CharsetUtils.toModifiedUtf8Bytes("foo", destPtr, -2, 8));
        assertEquals(-3, CharsetUtils.toModifiedUtf8Bytes("foo", destPtr, 6, 8));
        assertEquals(-3, CharsetUtils.toModifiedUtf8Bytes("foo", destPtr, 10, 8));
    }

    @Test
    public void testModifiedUtf8_Overwrite() {
        assertEquals(5, CharsetUtils.toModifiedUtf8Bytes("!!!!!", destPtr, 0, dest.length));
        assertEquals(3, CharsetUtils.toModifiedUtf8Bytes("...", destPtr, 0, dest.length));
        assertEquals(1, CharsetUtils.toModifiedUtf8Bytes("?", destPtr, 0, dest.length));
        assertEquals("3F002E0021000000", HexDump.toHexString(dest));
    }
}
