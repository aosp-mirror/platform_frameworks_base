/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class ProcessCpuTrackerTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testGetCpuTime() throws Exception {
        final ProcessCpuTracker tracker = new ProcessCpuTracker(false);
        assertThat(tracker.getCpuTimeForPid(android.os.Process.myPid())).isGreaterThan(0L);
    }

    @Test
    public void testGetCpuDelayTime() throws Exception {
        final ProcessCpuTracker tracker = new ProcessCpuTracker(false);
        assertThat(tracker.getCpuDelayTimeForPid(android.os.Process.myPid())).isGreaterThan(0L);
    }
}
