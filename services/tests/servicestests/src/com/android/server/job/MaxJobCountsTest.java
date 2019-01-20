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

import android.util.KeyValueListParser;

import com.android.server.job.JobSchedulerService.MaxJobCounts;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MaxJobCountsTest {

    private void check(String config,
            int defaultTotal, int defaultMaxBg, int defaultMinBg,
            int expectedTotal, int expectedMaxBg, int expectedMinBg) {
        final KeyValueListParser parser = new KeyValueListParser(',');
        parser.setString(config);

        final MaxJobCounts counts = new JobSchedulerService.MaxJobCounts(
                defaultTotal, "total",
                defaultMaxBg, "maxbg",
                defaultMinBg, "minbg");

        counts.parse(parser);

        Assert.assertEquals(expectedTotal, counts.getTotalMax());
        Assert.assertEquals(expectedMaxBg, counts.getMaxBg());
        Assert.assertEquals(expectedMinBg, counts.getMinBg());
    }

    @Test
    public void test() {
        check("", /*default*/ 5, 1, 0, /*expected*/ 5, 1, 0);
        check("", /*default*/ 5, 0, 0, /*expected*/ 5, 1, 0);
        check("", /*default*/ 0, 0, 0, /*expected*/ 1, 1, 0);
        check("", /*default*/ -1, -1, -1, /*expected*/ 1, 1, 0);
        check("", /*default*/ 5, 5, 5, /*expected*/ 5, 5, 4);
        check("", /*default*/ 6, 5, 6, /*expected*/ 6, 5, 5);
        check("", /*default*/ 4, 5, 6, /*expected*/ 4, 4, 3);
        check("", /*default*/ 5, 1, 1, /*expected*/ 5, 1, 1);

        check("total=5,maxbg=4,minbg=3", /*default*/ 9, 9, 9, /*expected*/ 5, 4, 3);
        check("total=5", /*default*/ 9, 9, 9, /*expected*/ 5, 5, 4);
        check("maxbg=4", /*default*/ 9, 9, 9, /*expected*/ 9, 4, 4);
        check("minbg=3", /*default*/ 9, 9, 9, /*expected*/ 9, 9, 3);
    }
}
