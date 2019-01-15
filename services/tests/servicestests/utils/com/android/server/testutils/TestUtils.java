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
 * limitations under the License.
 */
package com.android.server.testutils;

import android.test.MoreAsserts;

import junit.framework.Assert;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

public class TestUtils {
    private TestUtils() {
    }

    public interface ExceptionRunnable {
        void run() throws Exception;
    }

    public static void assertExpectException(Class<? extends Throwable> expectedExceptionType,
            String expectedExceptionMessageRegex, ExceptionRunnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            Assert.assertTrue(
                    "Expected exception type was " + expectedExceptionType.getName()
                    + " but caught " + e.getClass().getName(),
                    expectedExceptionType.isAssignableFrom(e.getClass()));
            if (expectedExceptionMessageRegex != null) {
                MoreAsserts.assertContainsRegex(expectedExceptionMessageRegex, e.getMessage());
            }
            return; // Pass.
        }
        Assert.fail("Expected exception type " + expectedExceptionType.getName()
                + " was not thrown");
    }

    /**
     * EasyMock-style "strict" mock that throws immediately on any interaction that was not
     * explicitly allowed.
     *
     * You can allow certain method calls on a whitelist basis by stubbing them e.g. with
     * {@link Mockito#doAnswer}, {@link Mockito#doNothing}, etc.
     */
    public static <T> T strictMock(Class<T> c) {
        return Mockito.mock(c, (Answer) invocation -> {
            throw new AssertionError("Unexpected invocation: " + invocation);
        });
    }
}
