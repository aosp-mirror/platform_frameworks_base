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
            int defaultTotal, int defaultMaxBg, int defaultMinBg,
            int expectedTotal, int expectedMaxBg, int expectedMinBg) throws Exception {
        resetConfig();
        if (config != null) {
            DeviceConfig.setProperties(config);
        }

        final WorkTypeConfig counts = new WorkTypeConfig("test",
                defaultTotal,
                // defaultMin
                List.of(Pair.create(WORK_TYPE_TOP, defaultTotal - defaultMaxBg),
                        Pair.create(WORK_TYPE_BG, defaultMinBg)),
                // defaultMax
                List.of(Pair.create(WORK_TYPE_BG, defaultMaxBg)));

        counts.update(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_JOB_SCHEDULER));

        Assert.assertEquals(expectedTotal, counts.getMaxTotal());
        Assert.assertEquals(expectedMaxBg, counts.getMax(WORK_TYPE_BG));
        Assert.assertEquals(expectedMinBg, counts.getMinReserved(WORK_TYPE_BG));
    }

    @Test
    public void test() throws Exception {
        // Tests with various combinations.
        check(null, /*default*/ 5, 1, 0, /*expected*/ 5, 1, 0);
        check(null, /*default*/ 5, 0, 0, /*expected*/ 5, 1, 0);
        check(null, /*default*/ 0, 0, 0, /*expected*/ 1, 1, 0);
        check(null, /*default*/ -1, -1, -1, /*expected*/ 1, 1, 0);
        check(null, /*default*/ 5, 5, 5, /*expected*/ 5, 5, 4);
        check(null, /*default*/ 6, 5, 6, /*expected*/ 6, 5, 5);
        check(null, /*default*/ 4, 5, 6, /*expected*/ 4, 4, 3);
        check(null, /*default*/ 5, 1, 1, /*expected*/ 5, 1, 1);
        check(null, /*default*/ 15, 15, 15, /*expected*/ 15, 15, 14);
        check(null, /*default*/ 16, 16, 16, /*expected*/ 16, 16, 15);
        check(null, /*default*/ 20, 20, 20, /*expected*/ 16, 16, 15);

        // Test for overriding with a setting string.
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 5)
                        .setInt(KEY_MAX_BG, 4)
                        .setInt(KEY_MIN_BG, 3)
                        .build(),
                /*default*/ 9, 9, 9, /*expected*/ 5, 4, 3);
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 5).build(),
                /*default*/ 9, 9, 9, /*expected*/ 5, 5, 4);
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_BG, 4).build(),
                /*default*/ 9, 9, 9, /*expected*/ 9, 4, 4);
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MIN_BG, 3).build(),
                /*default*/ 9, 9, 9, /*expected*/ 9, 9, 3);
    }
}
