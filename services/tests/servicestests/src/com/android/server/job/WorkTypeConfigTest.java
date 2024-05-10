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
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_UI;
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

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WorkTypeConfigTest {
    private static final String KEY_MAX_TOTAL = "concurrency_max_total_test";
    private static final String KEY_MAX_RATIO_TOP = "concurrency_max_ratio_top_test";
    private static final String KEY_MAX_RATIO_FGS = "concurrency_max_ratio_fgs_test";
    private static final String KEY_MAX_RATIO_UI = "concurrency_max_ratio_ui_test";
    private static final String KEY_MAX_RATIO_EJ = "concurrency_max_ratio_ej_test";
    private static final String KEY_MAX_RATIO_BG = "concurrency_max_ratio_bg_test";
    private static final String KEY_MAX_RATIO_BGUSER_IMPORTANT =
            "concurrency_max_ratio_bguser_important_test";
    private static final String KEY_MAX_RATIO_BGUSER = "concurrency_max_ratio_bguser_test";
    private static final String KEY_MIN_RATIO_TOP = "concurrency_min_ratio_top_test";
    private static final String KEY_MIN_RATIO_FGS = "concurrency_min_ratio_fgs_test";
    private static final String KEY_MIN_RATIO_UI = "concurrency_min_ratio_ui_test";
    private static final String KEY_MIN_RATIO_EJ = "concurrency_min_ratio_ej_test";
    private static final String KEY_MIN_RATIO_BG = "concurrency_min_ratio_bg_test";
    private static final String KEY_MIN_RATIO_BGUSER_IMPORTANT =
            "concurrency_min_ratio_bguser_important_test";
    private static final String KEY_MIN_RATIO_BGUSER = "concurrency_min_ratio_bguser_test";

    private void check(@Nullable DeviceConfig.Properties config,
            int defaultLimit,
            int defaultTotal,
            @NonNull List<Pair<Integer, Float>> defaultMinRatios,
            @NonNull List<Pair<Integer, Float>> defaultMaxRatios,
            boolean expectedValid, int expectedTotal,
            @NonNull List<Pair<Integer, Integer>> expectedMinLimits,
            @NonNull List<Pair<Integer, Integer>> expectedMaxLimits) throws Exception {

        final WorkTypeConfig counts;
        try {
            counts = new WorkTypeConfig("test",
                    defaultLimit, defaultTotal, defaultMinRatios, defaultMaxRatios);
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

        if (config != null) {
            counts.update(config, defaultLimit);
        }

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
        check(null, /* limit */ 16, /*default*/ 13,
                /* min */ List.of(),
                /* max */ List.of(),
                /*expected*/ true, 13,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_EJ, 0),
                        Pair.create(WORK_TYPE_BG, 0), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 13), Pair.create(WORK_TYPE_EJ, 13),
                        Pair.create(WORK_TYPE_BG, 13), Pair.create(WORK_TYPE_BGUSER, 13)));
        check(null, /* limit */ 16, /*default*/ 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, .8f), Pair.create(WORK_TYPE_BG, 0f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, .2f)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /* limit */ 16, /*default*/ 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1f),
                        Pair.create(WORK_TYPE_BG, 0f), Pair.create(WORK_TYPE_BGUSER, 0f)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 0f), Pair.create(WORK_TYPE_BGUSER, .2f)),
                /*expected*/ false, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 0), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 1)));
        check(null, /* limit */ 16, /*default*/ 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, .99f),
                        Pair.create(WORK_TYPE_BG, 0f), Pair.create(WORK_TYPE_BGUSER, 0f)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, .01f), Pair.create(WORK_TYPE_BGUSER, .2f)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4),
                        Pair.create(WORK_TYPE_BG, 0), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 1)));
        check(null, /* limit */ 16, /*default*/ 0,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1f), Pair.create(WORK_TYPE_BG, 0f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 0f)),
                /*expected*/ false, 1,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /* limit */ 16, /*default*/ -1,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, -1f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, -1f)),
                /*expected*/ false, 1,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /* limit */ 16, /*default*/ 5,
                /* min */ List.of(
                        Pair.create(WORK_TYPE_BG, 1f), Pair.create(WORK_TYPE_BGUSER, 0f)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 1f), Pair.create(WORK_TYPE_BGUSER, 1f)),
                /*expected*/ false, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 5)));
        check(null, /* limit */ 16, /*default*/ 5,
                /* min */ List.of(
                        Pair.create(WORK_TYPE_BG, .99f), Pair.create(WORK_TYPE_BGUSER, 0f)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 1f), Pair.create(WORK_TYPE_BGUSER, 1f)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 5)));
        check(null, /* limit */ 16, /*default*/ 6,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1.0f / 6),
                        Pair.create(WORK_TYPE_BG, 1f), Pair.create(WORK_TYPE_BGUSER, 1.0f / 3)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 1f), Pair.create(WORK_TYPE_BGUSER, 1.0f / 6)),
                /*expected*/ false, 6,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 6),
                        Pair.create(WORK_TYPE_BG, 5), Pair.create(WORK_TYPE_BGUSER, 1)));
        check(null, /* limit */ 16, /*default*/ 4,
                /* min */ List.of(
                        Pair.create(WORK_TYPE_BG, 1.5f), Pair.create(WORK_TYPE_BGUSER, 1.5f)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 1.25f), Pair.create(WORK_TYPE_BGUSER, 1.25f)),
                /*expected*/ false, 4,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 3), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 4),
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 4)));
        check(null, /* limit */ 16, /*default*/ 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, .8f), Pair.create(WORK_TYPE_BG, .2f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, .2f)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /* limit */ 16, /*default*/ 10,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, .4f), Pair.create(WORK_TYPE_EJ, .3f),
                        Pair.create(WORK_TYPE_BG, .1f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, .1f)),
                /*expected*/ true, 10,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_EJ, 3),
                        Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 1)));
        check(null, /* limit */ 16, /*default*/ 10,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, .3f), Pair.create(WORK_TYPE_FGS, .2f),
                        Pair.create(WORK_TYPE_EJ, .1f), Pair.create(WORK_TYPE_BG, .1f)),
                /* max */ List.of(Pair.create(WORK_TYPE_FGS, .3f)),
                /*expected*/ true, 10,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 3), Pair.create(WORK_TYPE_FGS, 2),
                        Pair.create(WORK_TYPE_EJ, 1), Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_FGS, 3)));
        check(null, /* limit */ 16, /*default*/ 15,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .95f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 15,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 14)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 15), Pair.create(WORK_TYPE_BG, 15)));
        check(null, /* limit */ 16, /*default*/ 16,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 15)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_BG, 16)));
        check(null, /* limit */ 16, /*default*/ 20,
                /* min */ List.of(
                        Pair.create(WORK_TYPE_BG, .99f), Pair.create(WORK_TYPE_BGUSER, .5f)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 1f), Pair.create(WORK_TYPE_BGUSER, 1f)),
                /*expected*/ false, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 15), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 16),
                        Pair.create(WORK_TYPE_BG, 16), Pair.create(WORK_TYPE_BGUSER, 16)));
        check(null, /* limit */ 76, /*default*/ 80,
                /* min */ List.of(
                        Pair.create(WORK_TYPE_BG, .98f), Pair.create(WORK_TYPE_BGUSER, .9f)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 1f), Pair.create(WORK_TYPE_BGUSER, 1f)),
                /*expected*/ false, 64,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1),
                        Pair.create(WORK_TYPE_BG, 63), Pair.create(WORK_TYPE_BGUSER, 0)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 64),
                        Pair.create(WORK_TYPE_BG, 64), Pair.create(WORK_TYPE_BGUSER, 64)));
        check(null, /* limit */ 16, /*default*/ 20,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 15)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_BG, 16)));

        // Test for overriding with a setting string.
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 5)
                        .setFloat(KEY_MAX_RATIO_BG, .8f)
                        .setFloat(KEY_MIN_RATIO_BG, .6f)
                        .build(),
                /* limit */ 16,
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 1f), Pair.create(WORK_TYPE_BGUSER, .4f)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 3)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5),
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2)));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 5).build(),
                /* limit */ 16,
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 5,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 4)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 5), Pair.create(WORK_TYPE_BG, 5)));

        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setFloat(KEY_MAX_RATIO_BG, 4.0f / 9).build(),
                /* limit */ 16,
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 9,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 4)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 9), Pair.create(WORK_TYPE_BG, 4)));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setFloat(KEY_MIN_RATIO_BG, 1.0f / 3).build(),
                /* limit */ 16,
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 9,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 3)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 9), Pair.create(WORK_TYPE_BG, 9)));
        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 20)
                        .setFloat(KEY_MAX_RATIO_EJ, .25f)
                        .setFloat(KEY_MIN_RATIO_EJ, .1f)
                        .setFloat(KEY_MAX_RATIO_BG, .8f)
                        .setFloat(KEY_MIN_RATIO_BG, .4f)
                        .build(),
                /* limit */ 16,
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_EJ, 1),
                        Pair.create(WORK_TYPE_BG, 6)),
                /* max */
                List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_EJ, 4),
                        Pair.create(WORK_TYPE_BG, 12)));

        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 20)
                        .setFloat(KEY_MAX_RATIO_BG, 1f)
                        .setFloat(KEY_MIN_RATIO_BG, .4f)
                        .build(),
                /* limit */ 16,
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 16,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_BG, 6)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 16), Pair.create(WORK_TYPE_BG, 16)));

        check(new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER)
                        .setInt(KEY_MAX_TOTAL, 32)
                        .setFloat(KEY_MAX_RATIO_TOP, 1f)
                        .setFloat(KEY_MIN_RATIO_TOP, 1.0f / 32)
                        .setFloat(KEY_MAX_RATIO_FGS, 15.0f / 32)
                        .setFloat(KEY_MIN_RATIO_FGS, 2.0f / 32)
                        .setFloat(KEY_MAX_RATIO_UI, 10.0f / 32)
                        .setFloat(KEY_MIN_RATIO_UI, 4.0f / 32)
                        .setFloat(KEY_MAX_RATIO_EJ, 14.0f / 32)
                        .setFloat(KEY_MIN_RATIO_EJ, 3.0f / 32)
                        .setFloat(KEY_MAX_RATIO_BG, 13.0f / 32)
                        .setFloat(KEY_MIN_RATIO_BG, 3.0f / 32)
                        .setFloat(KEY_MAX_RATIO_BGUSER_IMPORTANT, 12.0f / 32)
                        .setFloat(KEY_MIN_RATIO_BGUSER_IMPORTANT, 2.0f / 32)
                        .setFloat(KEY_MAX_RATIO_BGUSER, 11.0f / 32)
                        .setFloat(KEY_MIN_RATIO_BGUSER, 2.0f / 32)
                        .build(),
                /* limit */ 32,
                /*default*/ 9,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, .99f)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1f)),
                /*expected*/ true, 32,
                /* min */ List.of(Pair.create(WORK_TYPE_TOP, 1), Pair.create(WORK_TYPE_FGS, 2),
                        Pair.create(WORK_TYPE_UI, 4),
                        Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 3),
                        Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 2),
                        Pair.create(WORK_TYPE_BGUSER, 2)),
                /* max */
                List.of(Pair.create(WORK_TYPE_TOP, 32), Pair.create(WORK_TYPE_FGS, 15),
                        Pair.create(WORK_TYPE_UI, 10),
                        Pair.create(WORK_TYPE_EJ, 14), Pair.create(WORK_TYPE_BG, 13),
                        Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 12),
                        Pair.create(WORK_TYPE_BGUSER, 11)));
    }
}
