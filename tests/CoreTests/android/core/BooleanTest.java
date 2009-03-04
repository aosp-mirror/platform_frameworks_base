/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests some basic functionality of Booleans.
 */
public class BooleanTest extends TestCase {

    @SmallTest
    public void testBoolean() throws Exception {
        Boolean a = new Boolean(true);
        Boolean b = new Boolean("True");
        Boolean c = new Boolean(false);
        Boolean d = new Boolean("Yes");

        assertEquals(a, b);
        assertEquals(c, d);
        assertTrue(a.booleanValue());
        assertFalse(c.booleanValue());
        assertEquals("true", a.toString());
        assertEquals("false", c.toString());
        assertEquals(Boolean.TRUE, a);
        assertEquals(Boolean.FALSE, c);
        assertSame(Boolean.valueOf(true), Boolean.TRUE);
        assertSame(Boolean.valueOf(false), Boolean.FALSE);
    }
}

