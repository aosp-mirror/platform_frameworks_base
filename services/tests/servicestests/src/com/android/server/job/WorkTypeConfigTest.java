/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.job;

import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BG;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_TOP;

import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.provider.DeviceConfig;
import android.util.Pair;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.job.JobConcurrencyManager.WorkTypeConfig;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WorkTypeConfigTest {
    private static final String KEY_MAX_TOTAL = "concurrency_max_total_test";
    private static final String KEY_MAX_TOP = "concurrency_max_top_test";
    private static final String KEY_MAX_BG = "concurrency_max_bg_test";
    private static final String KEY_MIN_TOP = "concurrency_min_top_test";
    private static final String KEY_MIN_BG = "concurrency_min_bg_test";

    @After
    public void tearDown() throws Exception {
        resetConfig();
    }

    private void resetConfig() {
        // DeviceConfig.resetToDefaults() doesn't work here. Need to reset constants manually.
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_TOTAL, "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_TOP, "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_BG, "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MIN_TOP, "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MIN_BG, "", false);
    }

    private void check(@Nullable DeviceConfig.Properties config,
            int defaultTotal,
            @Nullable Pair<Integer, Integer> defaultTopLimits,
            @Nullable Pair<Integer, Integer> defaultBgLimits,
            boolean expectedValid, int expectedTotal,
            @NonNull Pair<Integer, Integer> expectedTopLimits,
            @NonNull Pair<Integer, Integer> expectedBgLimits) throws Exception {
        resetConfig();
        if (config != null) {
            DeviceConfig.setProperties(config);
        }

        List<Pair<Integer, Integer>> defaultMin = new ArrayList<>();
        List<Pair<Integer, Integer>> defaultMax = new ArrayList<>();
        Integer val;
        if (defaultTopLimits != null) {
            if ((val = defaultTopLimits.first) != null) {
                defaultMin.add(Pair.create(WORK_TYPE_TOP, val));
            }
            if ((val = defaultTopLimits.second) != null) {
                defaultMax.add(Pair.create(WORK_TYPE_TOP, val));
            }
        }
        if (defaultBgLimits != null) {
            if ((val = defaultBgLimits.first) != null) {
                defaultMin.add(Pair.create(WORK_TYPE_BG, val));
            }
            if ((val = defaultBgLimits.second) != null) {
                defaultMax.add(Pair.create(WORK_TYPE_BG, val));
            }
        }

        final WorkTypeConfig counts;
        try {
            counts = new WorkTypeConfig("test",
                    defaultTotal, defaultMin, defaultMax);
            if (!expectedValid) {
                fail("Invalid config successfully created");
                return;
            }
        } catch (IllegalArgumentException e) {
            if (expectedValid) {
                throw e;
            } else {
                // Success
                return;
            }
        }

        counts.update(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_JOB_SCHEDULER));

        Assert.assertEquals(expectedTotal, counts.getMaxTotal());
        Assert.assertEquals((int) expectedTopLimits.first, counts.getMinReserved(WORK_TYPE_TOP));
        Assert.assertEquals((int) expectedTopLimits.second, counts.getMax(WORK_TYPE_TOP));
        Assert.assertEquals((int) expectedBgLimits.first, counts.getMinReserved(WORK_TYPE_BG));
        Assert.assertEquals((int) expectedBgLimits.second, counts.getMax(WORK_TYPE_BG));
    }

    @Test
    public void test() throws Exception {
        // Tests with various combinations.
        check(null, /*default*/ 5, Pair.create(4, null), Pair.create(0, 1),
                /*expected*/ true, 5, Pair.create(4, 5), Pair.create(0, 1));
        check(null, /*default*/ 5, Pair.create(5, null), Pair.create(0, 0),
                /*expected*/ true, 5, Pair.create(5, 5), Pair.create(0, 1));
        check(null, /*default*/ 0, Pair.create(5, null), Pair.create(0, 0),
                /*expected*/ false, 1, Pair.create(1, 1), Pair.create(0, 1));
        check(null, /*default*/ -1, null, Pair.create(-1, -1),
                /*expected*/ false, 1, Pair.create(1, 1), Pair.create(0, 1));
        check(null, /*default*/ 5, null, Pair.create(5, 5),
                /*expected*/ true, 5, Pair.create(1, 5), Pair.create(4, 5));
        check(null, /*default*/ 6, Pair.create(1, null), Pair.create(6, 5),
                /*expected*/ false, 6, Pair.create(1, 6), Pair.create(5, 5));
        check(null, /*default*/ 4, null, Pair.create(6, 5),
                /*expected*/ false, 4, Pair.create(1, 4), Pair.create(3, 4));
        check(null, /*default*/ 5, Pair.create(4, null), Pair.create(1, 1),
                /*expected*/ true, 5, Pair.create(4, 5), Pair.create(1, 1));
        check(null, /*default*/ 15, null, Pair.create(15, 15),
                /*expected*/ true, 15, Pair.create(1, 15), Pair.create(14, 15));
        check(null, /*default*/ 16, null, Pair.create(16, 16),
                /*expected*/ true, 16, Pair.create(1, 16), Pair.create(15, 16));
        check(null, /*default*/ 20, null, Pair.create(20, 20),
                /*expected*/ false, 16, Pair.create(1, 16), Pair.create(15, 16));
        check(null, /*default*/ 20, null, Pair.create(16, 16),
                /*expected*/ true, 16, Pair.create(1, 16), Pair.create(15, 16));

        // Test for overriding with a setting string.
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 5)
                        .setInt(KEY_MAX_BG, 4)
                        .setInt(KEY_MIN_BG, 3)
                        .build(),
                /*default*/ 9, null, Pair.create(9, 9),
                /*expected*/ true, 5, Pair.create(1, 5), Pair.create(3, 4));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 5).build(),
                /*default*/ 9, null, Pair.create(9, 9),
                /*expected*/ true, 5, Pair.create(1, 5), Pair.create(4, 5));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_BG, 4).build(),
                /*default*/ 9, null, Pair.create(9, 9),
                /*expected*/ true, 9, Pair.create(1, 9), Pair.create(4, 4));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MIN_BG, 3).build(),
                /*default*/ 9, null, Pair.create(9, 9),
                /*expected*/ true, 9, Pair.create(1, 9), Pair.create(3, 9));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 20)
                        .setInt(KEY_MAX_BG, 20)
                        .setInt(KEY_MIN_BG, 8)
                        .build(),
                /*default*/ 9, null, Pair.create(9, 9),
                /*expected*/ true, 16, Pair.create(1, 16), Pair.create(8, 16));
    }
}
