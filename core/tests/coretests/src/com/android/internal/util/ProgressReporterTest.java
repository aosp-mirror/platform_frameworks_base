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

package com.android.internal.util;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = ProgressReporter.class)
public class ProgressReporterTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private ProgressReporter r;

    @Before
    public void setUp() throws Exception {
        r = new ProgressReporter(0);
    }

    private void assertProgress(int expected) {
        assertEquals(expected, r.getProgress());
    }

    private void assertRange(int start, int len) {
        final int[] range = r.getSegmentRange();
        assertEquals("start", start, range[0]);
        assertEquals("len", len, range[1]);
    }

    @Test
    public void testBasic() throws Exception {
        assertProgress(0);

        r.setProgress(20);
        assertProgress(20);

        r.setProgress(-20);
        assertProgress(0);

        r.setProgress(1024);
        assertProgress(100);
    }

    @Test
    public void testSegment() throws Exception {
        r.setProgress(20);
        assertProgress(20);

        final int[] lastRange = r.startSegment(40);
        {
            assertProgress(20);

            r.setProgress(50);
            assertProgress(40);
        }
        r.endSegment(lastRange);
        assertProgress(60);

        r.setProgress(80);
        assertProgress(80);
    }

    @Test
    public void testSegmentOvershoot() throws Exception {
        r.setProgress(20);
        assertProgress(20);

        final int[] lastRange = r.startSegment(40);
        {
            r.setProgress(-100, 2);
            assertProgress(20);

            r.setProgress(1, 2);
            assertProgress(40);

            r.setProgress(100, 2);
            assertProgress(60);
        }
        r.endSegment(lastRange);
        assertProgress(60);
    }

    @Test
    public void testSegmentNested() throws Exception {
        r.setProgress(20);
        assertProgress(20);
        assertRange(0, 100);

        final int[] lastRange = r.startSegment(40);
        assertRange(20, 40);
        {
            r.setProgress(50);
            assertProgress(40);

            final int[] lastRange2 = r.startSegment(25);
            assertRange(40, 10);
            {
                r.setProgress(0);
                assertProgress(40);

                r.setProgress(50);
                assertProgress(45);

                r.setProgress(100);
                assertProgress(50);
            }
            r.endSegment(lastRange2);
            assertProgress(50);
        }
        r.endSegment(lastRange);
        assertProgress(60);
    }
}
