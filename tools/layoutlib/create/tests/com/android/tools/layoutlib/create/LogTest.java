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

package com.android.tools.layoutlib.create;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LogTest {

    private MockLog mLog;

    @Before
    public void setUp() throws Exception {
        mLog = new MockLog();
    }

    @After
    public void tearDown() throws Exception {
        // pass
    }

    @Test
    public void testDebug() {
        assertEquals("", mLog.getOut());
        assertEquals("", mLog.getErr());

        mLog.setVerbose(false);
        mLog.debug("Test %d", 42);
        assertEquals("", mLog.getOut());

        mLog.setVerbose(true);
        mLog.debug("Test %d", 42);

        assertEquals("Test 42\n", mLog.getOut());
        assertEquals("", mLog.getErr());
    }

    @Test
    public void testInfo() {
        assertEquals("", mLog.getOut());
        assertEquals("", mLog.getErr());

        mLog.info("Test %d", 43);

        assertEquals("Test 43\n", mLog.getOut());
        assertEquals("", mLog.getErr());
    }

    @Test
    public void testError() {
        assertEquals("", mLog.getOut());
        assertEquals("", mLog.getErr());

        mLog.error("Test %d", 44);

        assertEquals("", mLog.getOut());
        assertEquals("Test 44\n", mLog.getErr());
    }

    @Test
    public void testException() {
        assertEquals("", mLog.getOut());
        assertEquals("", mLog.getErr());

        Exception e = new Exception("My Exception");
        mLog.exception(e, "Test %d", 44);

        assertEquals("", mLog.getOut());
        assertTrue(mLog.getErr().startsWith("Test 44\njava.lang.Exception: My Exception"));
    }
}
