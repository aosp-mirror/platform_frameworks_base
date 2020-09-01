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

package com.android.server.wm.testing;

import static com.android.server.wm.testing.Assert.assertThrows;

import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AssertTest {

    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    @Test
    public void assertThrows_runsRunnable() {
        boolean[] ran = new boolean[] { false };
        assertThrows(TestException.class, () -> {
            ran[0] = true;
            throw new TestException();
        });
        assertTrue(ran[0]);
    }

    @Test
    public void assertThrows_failsIfNothingThrown() {
        mExpectedException.expect(AssertionError.class);
        assertThrows(TestException.class, () -> {
        });
    }

    @Test
    public void assertThrows_failsIfWrongExceptionThrown() {
        mExpectedException.expect(AssertionError.class);
        assertThrows(TestException.class, () -> {
            throw new RuntimeException();
        });
    }

    @Test
    public void assertThrows_succeedsIfGivenExceptionThrown() {
        assertThrows(TestException.class, () -> {
            throw new TestException();
        });
    }

    @Test
    public void assertThrows_succeedsIfSubExceptionThrown() {
        assertThrows(RuntimeException.class, () -> {
            throw new TestException();
        });
    }

    @Test
    public void assertThrows_rethrowsUnexpectedErrors() {
        mExpectedException.expect(TestError.class);
        assertThrows(TestException.class, () -> {
            throw new TestError();
        });
    }

    static class TestException extends RuntimeException {
    }

    static class TestError extends Error {
    }

}
