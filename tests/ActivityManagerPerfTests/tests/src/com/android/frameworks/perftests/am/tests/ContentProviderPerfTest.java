/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.frameworks.perftests.am.tests;

import android.content.ContentProviderClient;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.frameworks.perftests.am.util.TargetPackageUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ContentProviderPerfTest extends BasePerfTest {
    /**
     * Benchmark time to call ContentResolver.acquireContentProviderClient() when target package is
     * running.
     */
    @Test
    public void contentProviderRunning() {
        runPerfFunction(() -> {
            startTargetPackage();

            long startTimeNs = System.nanoTime();
            final ContentProviderClient contentProviderClient =
                    mContext.getContentResolver()
                            .acquireContentProviderClient(TargetPackageUtils.PACKAGE_NAME);
            final long endTimeNs = System.nanoTime();

            contentProviderClient.close();

            return endTimeNs - startTimeNs;
        });
    }

    /**
     * Benchmark time to call ContentResolver.acquireContentProviderClient() when target package is
     * not running.
     */
    @Test
    public void contentProviderNotRunning() {
        runPerfFunction(() -> {
            final long startTimeNs = System.nanoTime();
            final ContentProviderClient contentProviderClient =
                    mContext.getContentResolver().acquireContentProviderClient(
                            TargetPackageUtils.PACKAGE_NAME);
            final long endTimeNs = System.nanoTime();

            contentProviderClient.close();

            return endTimeNs - startTimeNs;
        });
    }
}
