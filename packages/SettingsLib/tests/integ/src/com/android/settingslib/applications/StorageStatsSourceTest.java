/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.applications;

import static com.google.common.truth.Truth.assertThat;

import android.app.usage.StorageStats;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class StorageStatsSourceTest {
    @Test
    public void AppStorageStatsImpl_totalCorrectly() {
        StorageStats storageStats = new StorageStats();
        storageStats.cacheBytes = 1;
        storageStats.codeBytes = 10;
        storageStats.dataBytes = 100;
        StorageStatsSource.AppStorageStatsImpl stats = new StorageStatsSource.AppStorageStatsImpl(
                storageStats);

        // Note that this does not double add the cache (111).
        assertThat(stats.getTotalBytes()).isEqualTo(110);
    }
}
