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
 * Tests basic behavior of enums.
 */
public class EnumTest extends TestCase {
    enum MyEnum {
        ZERO, ONE, TWO, THREE, FOUR {boolean isFour() {
        return true;
    }};

        boolean isFour() {
            return false;
        }
    }

    enum MyEnumTwo {
        FIVE, SIX
    }

    @SmallTest
    public void testEnum() throws Exception {
        assertTrue(MyEnum.ZERO.compareTo(MyEnum.ONE) < 0);
        assertEquals(MyEnum.ZERO, MyEnum.ZERO);
        assertTrue(MyEnum.TWO.compareTo(MyEnum.ONE) > 0);
        assertTrue(MyEnum.FOUR.compareTo(MyEnum.ONE) > 0);

        assertEquals("ONE", MyEnum.ONE.name());
        assertSame(MyEnum.ONE.getDeclaringClass(), MyEnum.class);
        assertSame(MyEnum.FOUR.getDeclaringClass(), MyEnum.class);

        assertTrue(MyEnum.FOUR.isFour());

        MyEnum e;

        e = MyEnum.ZERO;

        switch (e) {
            case ZERO:
                break;
            default:
                fail("wrong switch");
        }
    }
}
