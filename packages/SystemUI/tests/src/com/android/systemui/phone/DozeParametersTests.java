/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.phone;

import com.android.systemui.statusbar.phone.DozeParameters.IntInOutMatcher;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public class DozeParametersTests extends AndroidTestCase {

    public void test_inOutMatcher_defaultIn() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("*");

        assertTrue(intInOutMatcher.isIn(1));
        assertTrue(intInOutMatcher.isIn(-1));
        assertTrue(intInOutMatcher.isIn(0));
    }

    public void test_inOutMatcher_defaultOut() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("!*");

        assertFalse(intInOutMatcher.isIn(1));
        assertFalse(intInOutMatcher.isIn(-1));
        assertFalse(intInOutMatcher.isIn(0));
    }

    public void test_inOutMatcher_someIn() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("1,2,3,!*");

        assertTrue(intInOutMatcher.isIn(1));
        assertTrue(intInOutMatcher.isIn(2));
        assertTrue(intInOutMatcher.isIn(3));

        assertFalse(intInOutMatcher.isIn(0));
        assertFalse(intInOutMatcher.isIn(4));
    }

    public void test_inOutMatcher_someOut() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("!1,!2,!3,*");

        assertFalse(intInOutMatcher.isIn(1));
        assertFalse(intInOutMatcher.isIn(2));
        assertFalse(intInOutMatcher.isIn(3));

        assertTrue(intInOutMatcher.isIn(0));
        assertTrue(intInOutMatcher.isIn(4));
    }

    public void test_inOutMatcher_mixed() {
        IntInOutMatcher intInOutMatcher = new IntInOutMatcher("!1,2,!3,*");

        assertFalse(intInOutMatcher.isIn(1));
        assertTrue(intInOutMatcher.isIn(2));
        assertFalse(intInOutMatcher.isIn(3));

        assertTrue(intInOutMatcher.isIn(0));
        assertTrue(intInOutMatcher.isIn(4));
    }

    public void test_inOutMatcher_failEmpty() {
        try {
            new IntInOutMatcher("");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failNull() {
        try {
            new IntInOutMatcher(null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failEmptyClause() {
        try {
            new IntInOutMatcher("!1,*,");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failDuplicate() {
        try {
            new IntInOutMatcher("!1,*,!1");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failDuplicateDefault() {
        try {
            new IntInOutMatcher("!1,*,*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failMalformedNot() {
        try {
            new IntInOutMatcher("!,*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failText() {
        try {
            new IntInOutMatcher("!abc,*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failContradiction() {
        try {
            new IntInOutMatcher("1,!1,*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failContradictionDefault() {
        try {
            new IntInOutMatcher("1,*,!*");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void test_inOutMatcher_failMissingDefault() {
        try {
            new IntInOutMatcher("1");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

}