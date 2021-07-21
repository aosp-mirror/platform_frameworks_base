/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.util;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public final class StringPoolTest extends AndroidTestCase {

    public void testStringPool() {
        StringPool stringPool = new StringPool();
        String bcd = stringPool.get(new char[] { 'a', 'b', 'c', 'd', 'e' }, 1, 3);
        assertEquals("bcd", bcd);
        assertSame(bcd, stringPool.get(new char[] { 'a', 'b', 'c', 'd', 'e' }, 1, 3));
    }

    public void testHashCollision() {
        StringPool stringPool = new StringPool();
        char[] a = { (char) 1, (char) 0 };
        char[] b = { (char) 0, (char) 31 };
        assertEquals(new String(a).hashCode(), new String(b).hashCode());

        String aString = stringPool.get(a, 0, 2);
        assertEquals(new String(a), aString);
        String bString = stringPool.get(b, 0, 2);
        assertEquals(new String(b), bString);
        assertSame(bString, stringPool.get(b, 0, 2));
        assertNotSame(aString, stringPool.get(a, 0, 2));
    }
}
