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

package com.android.mediatranscodingtest;

/*
 * Test for ApplicationMediaCapabilities in the media framework.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaTranscodingTest
     make mediatranscodingtest

     adb install -r testcases/mediatranscodingtest/arm64/mediatranscodingtest.apk

     adb shell am instrument -e class \
     com.android.mediatranscodingtest.MediaCapabilityTest \
     -w com.android.mediatranscodingtest/.MediaTranscodingTestRunner
 *
 */

import static org.testng.Assert.assertThrows;

import android.media.ApplicationMediaCapabilities;
import android.test.ActivityInstrumentationTestCase2;

import org.junit.Test;

public class ApplicationMediaCapabilitiesTest extends
        ActivityInstrumentationTestCase2<MediaTranscodingTest> {
    private static final String TAG = "MediaCapabilityTest";

    public ApplicationMediaCapabilitiesTest() {
        super("com.android.MediaCapabilityTest", MediaTranscodingTest.class);
    }

    @Test
    public void testSetSupportHevc() throws Exception {
        ApplicationMediaCapabilities capability =
                new ApplicationMediaCapabilities.Builder().setHevcSupported(true).build();
        assertTrue(capability.isHevcSupported());

        ApplicationMediaCapabilities capability2 =
                new ApplicationMediaCapabilities.Builder().setHevcSupported(false).build();
        assertFalse(capability2.isHevcSupported());
    }

    @Test
    public void testSetSupportHdr() throws Exception {
        ApplicationMediaCapabilities capability =
                new ApplicationMediaCapabilities.Builder().setHdrSupported(true).setHevcSupported(
                        true).build();
        assertEquals(true, capability.isHdrSupported());
    }

    @Test
    public void testSetSupportSlowMotion() throws Exception {
        ApplicationMediaCapabilities capability =
                new ApplicationMediaCapabilities.Builder().setSlowMotionSupported(
                        true).build();
        assertTrue(capability.isSlowMotionSupported());
    }

    @Test
    public void testBuilder() throws Exception {
        ApplicationMediaCapabilities capability =
                new ApplicationMediaCapabilities.Builder().setHdrSupported(
                        true).setHevcSupported(true).setSlowMotionSupported(true).build();
        assertTrue(capability.isHdrSupported());
        assertTrue(capability.isSlowMotionSupported());
        assertTrue(capability.isSlowMotionSupported());
    }

    @Test
    public void testSupportHdrWithoutSupportHevc() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            ApplicationMediaCapabilities capability =
                    new ApplicationMediaCapabilities.Builder().setHdrSupported(
                            true).setHevcSupported(false).build();
        });
    }
}
