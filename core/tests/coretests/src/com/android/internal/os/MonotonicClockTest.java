/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MonotonicClockTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private final MockClock mClock = new MockClock();
    private File mFile;

    @Before
    public void setup() throws IOException {
        File systemDir = Files.createTempDirectory("MonotonicClockTest").toFile();
        mFile = new File(systemDir, "test_monotonic_clock.xml");
        if (mFile.exists()) {
            assertThat(mFile.delete()).isTrue();
        }
    }

    @Test
    public void persistence() throws IOException {
        MonotonicClock monotonicClock = new MonotonicClock(mFile, 1000, mClock);
        mClock.realtime = 234;

        assertThat(monotonicClock.monotonicTime()).isEqualTo(1234);

        monotonicClock.write();

        mClock.realtime = 42;
        MonotonicClock newMonotonicClock = new MonotonicClock(mFile, 0, mClock);

        mClock.realtime = 2000;
        assertThat(newMonotonicClock.monotonicTime()).isEqualTo(1234 - 42 + 2000);
    }

    @Test
    public void constructor() {
        MonotonicClock monotonicClock = new MonotonicClock(null, 1000, mClock);
        mClock.realtime = 234;

        assertThat(monotonicClock.monotonicTime()).isEqualTo(1234);
    }

    @Test
    public void corruptedFile() throws IOException {
        // Create an invalid binary XML file to cause IOException: "Unexpected magic number"
        try (FileWriter w = new FileWriter(mFile)) {
            w.write("garbage");
        }

        MonotonicClock monotonicClock = new MonotonicClock(mFile, 1000, mClock);
        mClock.realtime = 234;

        assertThat(monotonicClock.monotonicTime()).isEqualTo(1234);
    }
}
