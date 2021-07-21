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
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BGUSER;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BGUSER_IMPORTANT;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_EJ;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_FGS;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_TOP;
import static com.android.server.job.JobConcurrencyManager.workTypeToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.provider.DeviceConfig;
import android.util.Pair;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.job.JobConcurrencyManager.WorkTypeConfig;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WorkTypeConfigTest {
    private static final String KEY_MAX_TOTAL = "concurrency_max_total_test";
    private static final String KEY_MAX_TOP = "concurrency_max_top_test";
    private static final String KEY_MAX_FGS = "concurrency_max_fgs_test";
    private static final String KEY_MAX_EJ = "concurrency_max_ej_test";
    private static final String KEY_MAX_BG = "concurrency_max_bg_test";
    private static final String KEY_MAX_BGUSER_IMPORTANT = "concurrency_max_bguser_important_test";
    private static final String KEY_MAX_BGUSER = "concurrency_max_bguser_test";
    private static final String KEY_MIN_TOP = "concurrency_min_top_test";
    private static final String KEY_MIN_FGS = "concurrency_min_fgs_test";
    private static final String KEY_MIN_EJ = "concurrency_min_ej_test";
    private static final String KEY_MIN_BG = "concurrency_min_bg_test";
    private static final String KEY_MIN_BGUSER_IMPORTANT = "concurrency_min_bguser_important_test";
    private static final String KEY_MIN_BGUSER = "concurrency_min_bguser_test";

    @After
    public void tearDown() throws Exception {
        resetConfig();
    }

    private void resetConfig() {
        // DeviceConfig.resetToDefaults() doesn't work here. Need to reset constants manually.
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_TOTAL, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_TOP, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_FGS, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_EJ, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_BG, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                KEY_MAX_BGUSER_IMPORTANT, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MAX_BGUSER, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MIN_TOP, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MIN_FGS, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MIN_EJ, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MIN_BG, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                KEY_MIN_BGUSER_IMPORTANT, null, false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_JOB_SCHEDULER, KEY_MIN_BGUSER, null, false);
    }

    private void check(@Nullable DeviceConfig.Properties config,
            int defaultTotal,
            @NonNull List<Pair<Integer, Integer>> defaultMin,
            @NonNull List<Pair<Integer, Integer>> defaultMax,
            boolean expectedValid, int expectedTotal,
            @NonNull List<Pair<Integer, Integer>> expectedMinLimits,
            @NonNull List<Pair<Integer, Integer>> expectedMaxLimits) throws Exception {
        resetConfig();
        if (config != null) {
            DeviceConfig.setProperties(config);
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

        assertEquals(expectedTotal, counts.getMaxTotal());
        for (Pair<Integer, Integer> min : expectedMinLimits) {
            assertEquals("Incorrect min value for " + workTypeToString(min.first),
                    (int) min.second, counts.getMinReserved(min.first));
        }
        for (Pair<Integer, Integer> max : expectedMaxLimits) {
            assertEquals("Incorrect max value for " + workTypeToString(max.first),
                    (int) max.second, counts.getMax(max.first));
        }
    }

    @Test
    public void test() throws Exception {
        // Tests with various combinations.
        check(null, /*default*/ 13,
                /* min */ List.of(),
                /* max */ List.of(),
                /*expected*/ true, 13,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_EJ, 0),
                        Pair.create(WORK_TYPE_BG, 0), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 13), Pair.create(WORK_TYPE_EJ, 13),
                        Pair.create(WORK_TYPE_BG, 13), Pair.create(WORK_TYPE_BGUSER, 13)));
        check(null, /*default*/ 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /*default*/ 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 0), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 0), Pair.create(WORK_TYPE_BGUSER, 1)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 0), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 1)));
        check(null, /*default*/ 0,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 5), Pair.create(WORK_TYPE_BG, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 0)),
                /*expected*/ false, 1,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /*default*/ -1,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, -1)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, -1)),
                /*expected*/ false, 1,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /*default*/ 5,
                /* min */ List.of(
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 5)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 5)));
        check(null, /*default*/ 6,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 6), Pair.create(WORK_TYPE_BGUSER, 2)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 1)),
                /*expected*/ false, 6,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 6),
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 1)));
        check(null, /*default*/ 4,
                /* min */ List.of(
                        Pair.create(WORK_TYPE_BG, 6), Pair.create(WORK_TYPE_BGUSER, 6)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 5)),
                /*expected*/ false, 4,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 3), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 4),
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 4)));
        check(null, /*default*/ 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /*default*/ 10,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_EJ, 3),
                        Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1)),
                /*expected*/ true, 10,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_EJ, 3),
                        Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /*default*/ 10,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 3), Pair.create(WORK_TYPE_FGS, 2),
                        Pair.create(WORK_TYPE_EJ, 1), Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_FGS, 3)),
                /*expected*/ true, 10,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 3), Pair.create(WORK_TYPE_FGS, 2),
                        Pair.create(WORK_TYPE_EJ, 1), Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_FGS, 3)));
        check(null, /*default*/ 15,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 15)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 15)),
                /*expected*/ true, 15,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 14)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 15), Pair.create(WORK_TYPE_BG, 15)));
        check(null, /*default*/ 16,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 16)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 16)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 15)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_BG, 16)));
        check(null, /*default*/ 20,
                /* min */ List.of(
                        Pair.create(WORK_TYPE_BG, 20), Pair.create(WORK_TYPE_BGUSER, 10)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 20), Pair.create(WORK_TYPE_BGUSER, 20)),
                /*expected*/ false, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 15), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 16),
                        Pair.create(WORK_TYPE_BG, 16), Pair.create(WORK_TYPE_BGUSER, 16)));
        check(null, /*default*/ 20,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 16)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 16)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 15)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_BG, 16)));

        // Test for overriding with a setting string.
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 5)
                        .setInt(KEY_MAX_BG, 4)
                        .setInt(KEY_MIN_BG, 3)
                        .build(),
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 9), Pair.create(WORK_TYPE_BGUSER, 2)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 3)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2)));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 5).build(),
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 4)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5), Pair.create(WORK_TYPE_BG, 5)));

        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_BG, 4).build(),
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /*expected*/ true, 9,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 4)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 9), Pair.create(WORK_TYPE_BG, 4)));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MIN_BG, 3).build(),
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /*expected*/ true, 9,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 3)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 9), Pair.create(WORK_TYPE_BG, 9)));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 20)
                        .setInt(KEY_MAX_EJ, 5)
                        .setInt(KEY_MIN_EJ, 2)
                        .setInt(KEY_MAX_BG, 16)
                        .setInt(KEY_MIN_BG, 8)
                        .build(),
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_EJ, 2),
                        Pair.create(WORK_TYPE_BG, 8)),
                /* max */
                List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_EJ, 5),
                        Pair.create(WORK_TYPE_BG, 16)));

        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 20)
                        .setInt(KEY_MAX_BG, 20)
                        .setInt(KEY_MIN_BG, 8)
                        .build(),
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 8)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_BG, 16)));

        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 16)
                        .setInt(KEY_MAX_TOP, 16)
                        .setInt(KEY_MIN_TOP, 1)
                        .setInt(KEY_MAX_FGS, 15)
                        .setInt(KEY_MIN_FGS, 2)
                        .setInt(KEY_MAX_EJ, 14)
                        .setInt(KEY_MIN_EJ, 3)
                        .setInt(KEY_MAX_BG, 13)
                        .setInt(KEY_MIN_BG, 4)
                        .setInt(KEY_MAX_BGUSER_IMPORTANT, 12)
                        .setInt(KEY_MIN_BGUSER_IMPORTANT, 5)
                        .setInt(KEY_MAX_BGUSER, 11)
                        .setInt(KEY_MIN_BGUSER, 6)
                        .build(),
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 9)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_FGS, 2),
                        Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 4),
                        Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 5),
                        Pair.create(WORK_TYPE_BGUSER, 6)),
                /* max */
                List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_FGS, 15),
                        Pair.create(WORK_TYPE_EJ, 14), Pair.create(WORK_TYPE_BG, 13),
                        Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 12),
                        Pair.create(WORK_TYPE_BGUSER, 11)));
    }
}
