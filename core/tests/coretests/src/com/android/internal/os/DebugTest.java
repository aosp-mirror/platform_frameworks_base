/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.os;

import static org.junit.Assert.assertTrue;

import android.os.Debug;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;

@SmallTest
@IgnoreUnderRavenwood(reason = "Requires ART support")
public class DebugTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private final static String EXPECTED_GET_CALLER =
            "com\\.android\\.internal\\.os\\.DebugTest\\.testGetCaller:\\d\\d";
    private final static String EXPECTED_GET_CALLERS =
            "com\\.android\\.internal\\.os\\.DebugTest.callDepth3:\\d\\d " +
            "com\\.android\\.internal\\.os\\.DebugTest.callDepth2:\\d\\d " +
            "com\\.android\\.internal\\.os\\.DebugTest.callDepth1:\\d\\d ";

    /**
     * @return String consisting of the caller to this method.
     */
    private String callDepth0() {
        return Debug.getCaller();
    }

    @Test
    public void testGetCaller() {
        assertTrue(callDepth0().matches(EXPECTED_GET_CALLER));
    }

    /**
     * @return String consisting of the callers to this method.
     */
    private String callDepth4() {
        return Debug.getCallers(3);
    }

    private String callDepth3() {
        return callDepth4();
    }

    private String callDepth2() {
        return callDepth3();
    }

    private String callDepth1() {
        return callDepth2();
    }

    @Test
    public void testGetCallers() {
        assertTrue(callDepth1().matches(EXPECTED_GET_CALLERS));
    }

    /**
     * Regression test for b/31943543. Note: must be run under CheckJNI to detect the issue.
     */
    @Test
    public void testGetMemoryInfo() {
        Debug.MemoryInfo info = new Debug.MemoryInfo();
        Debug.getMemoryInfo(-1, info);
    }
}
