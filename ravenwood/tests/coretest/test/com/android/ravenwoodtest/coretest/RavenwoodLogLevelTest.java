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
package com.android.ravenwoodtest.coretest;

import static org.junit.Assert.assertEquals;

import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Log;

import com.android.ravenwood.common.RavenwoodCommonUtils;

import org.junit.Test;

public class RavenwoodLogLevelTest {
    /**
     * Assert that the `priority` is loggable, but one level below is not.
     */
    private void assertBarelyLoggable(String tag, int priority) {
        assertEquals(true, Log.isLoggable(tag, priority));
        assertEquals(false, Log.isLoggable(tag, priority - 1));
    }

    @Test
    public void testDefaultLogTags() {
        RavenwoodRule.setAndroidLogTags(null);

        // Info should always be loggable.
        assertEquals(true, Log.isLoggable("TAG1", Log.INFO));
        assertEquals(true, Log.isLoggable("TAG2", Log.INFO));

        assertEquals(true, Log.isLoggable("TAG1", Log.DEBUG));
        assertEquals(true, Log.isLoggable("TAG2", Log.VERBOSE));
    }

    @Test
    public void testAllVerbose() {
        RavenwoodRule.setAndroidLogTags("*:V");

        assertEquals(true, Log.isLoggable("TAG1", Log.INFO));
        assertEquals(true, Log.isLoggable("TAG2", Log.INFO));

        assertEquals(true, Log.isLoggable("TAG1", Log.DEBUG));
        assertEquals(true, Log.isLoggable("TAG2", Log.VERBOSE));
    }

    @Test
    public void testAllSilent() {
        RavenwoodRule.setAndroidLogTags("*:S");

        assertEquals(false, Log.isLoggable("TAG1", Log.ASSERT));
        assertEquals(false, Log.isLoggable("TAG2", Log.ASSERT));
    }

    @Test
    public void testComplex() {
        RavenwoodRule.setAndroidLogTags("TAG1:W TAG2:D *:I");

        assertBarelyLoggable("TAG1", Log.WARN);
        assertBarelyLoggable("TAG2", Log.DEBUG);
        assertBarelyLoggable("TAG3", Log.INFO);
    }

    @Test
    public void testAllVerbose_setLogLevel() {
        RavenwoodRule.setAndroidLogTags(null);
        RavenwoodRule.setLogLevel(null, Log.VERBOSE);

        assertEquals(true, Log.isLoggable("TAG1", Log.INFO));
        assertEquals(true, Log.isLoggable("TAG2", Log.INFO));

        assertEquals(true, Log.isLoggable("TAG1", Log.DEBUG));
        assertEquals(true, Log.isLoggable("TAG2", Log.VERBOSE));
    }

    @Test
    public void testAllSilent_setLogLevel() {
        RavenwoodRule.setAndroidLogTags(null);
        RavenwoodRule.setLogLevel(null, Log.ASSERT + 1);

        assertEquals(false, Log.isLoggable("TAG1", Log.ASSERT));
        assertEquals(false, Log.isLoggable("TAG2", Log.ASSERT));
    }

    @Test
    public void testComplex_setLogLevel() {
        RavenwoodRule.setAndroidLogTags(null);
        RavenwoodRule.setLogLevel(null, Log.INFO);
        RavenwoodRule.setLogLevel("TAG1", Log.WARN);
        RavenwoodRule.setLogLevel("TAG2", Log.DEBUG);

        assertBarelyLoggable("TAG1", Log.WARN);
        assertBarelyLoggable("TAG2", Log.DEBUG);
        assertBarelyLoggable("TAG3", Log.INFO);
    }
}
