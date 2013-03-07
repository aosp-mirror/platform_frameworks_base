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

package android.text.format;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

public class DateUtilsTest extends TestCase {
    // This test is not in CTS because formatDuration is @hidden.
    @SmallTest
    public void test_formatDuration_seconds() throws Exception {
        assertEquals("0 seconds", DateUtils.formatDuration(0));
        assertEquals("0 seconds", DateUtils.formatDuration(1));
        assertEquals("0 seconds", DateUtils.formatDuration(499));
        assertEquals("1 second", DateUtils.formatDuration(500));
        assertEquals("1 second", DateUtils.formatDuration(1000));
        assertEquals("2 seconds", DateUtils.formatDuration(1500));
    }

    // This test is not in CTS because formatDuration is @hidden.
    @SmallTest
    public void test_formatDuration_Minutes() throws Exception {
        assertEquals("59 seconds", DateUtils.formatDuration(59000));
        assertEquals("60 seconds", DateUtils.formatDuration(59500));
        assertEquals("1 minute", DateUtils.formatDuration(60000));
        assertEquals("2 minutes", DateUtils.formatDuration(120000));
    }

    // This test is not in CTS because formatDuration is @hidden.
    @SmallTest
    public void test_formatDuration_Hours() throws Exception {
        assertEquals("59 minutes", DateUtils.formatDuration(3540000));
        assertEquals("1 hour", DateUtils.formatDuration(3600000));
        assertEquals("48 hours", DateUtils.formatDuration(172800000));
    }
}
