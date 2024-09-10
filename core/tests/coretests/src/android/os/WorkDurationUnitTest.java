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

package android.os;

import static org.junit.Assert.assertThrows;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.RavenwoodFlagsValueProvider;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = WorkDuration.class)
public class WorkDurationUnitTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    // Required for RequiresFlagsEnabled and RequiresFlagsDisabled annotations to take effect.
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = RavenwoodRule.isOnRavenwood()
            ? RavenwoodFlagsValueProvider.createAllOnCheckFlagsRule()
            : DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ADPF_GPU_REPORT_ACTUAL_WORK_DURATION)
    public void testWorkDurationSetters_IllegalArgument() {
        WorkDuration workDuration = new WorkDuration();
        assertThrows(IllegalArgumentException.class, () -> {
            workDuration.setWorkPeriodStartTimestampNanos(-1);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            workDuration.setWorkPeriodStartTimestampNanos(0);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            workDuration.setActualTotalDurationNanos(-1);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            workDuration.setActualTotalDurationNanos(0);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            workDuration.setActualCpuDurationNanos(-1);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            workDuration.setActualCpuDurationNanos(0);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            workDuration.setActualGpuDurationNanos(-1);
        });
    }
}
