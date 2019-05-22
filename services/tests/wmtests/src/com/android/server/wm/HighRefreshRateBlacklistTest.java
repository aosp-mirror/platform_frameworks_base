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

package com.android.server.wm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.server.wm.HighRefreshRateBlacklist.SystemPropertyGetter;

import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest WmTests:HighRefreshRateBlacklistTest
 */
@SmallTest
@Presubmit
@FlakyTest
public class HighRefreshRateBlacklistTest {

    @Test
    public void testBlacklist() {
        HighRefreshRateBlacklist blacklist = new HighRefreshRateBlacklist(
                new SystemPropertyGetter() {

                    @Override
                    public int getInt(String key, int def) {
                        if ("ro.window_manager.high_refresh_rate_blacklist_length".equals(key)) {
                            return 2;
                        }
                        return def;
                    }

                    @Override
                    public String get(String key) {
                        if ("ro.window_manager.high_refresh_rate_blacklist_entry1".equals(key)) {
                            return "com.android.sample1";
                        }
                        if ("ro.window_manager.high_refresh_rate_blacklist_entry2".equals(key)) {
                            return "com.android.sample2";
                        }
                        return "";
                    }
                });
        assertTrue(blacklist.isBlacklisted("com.android.sample1"));
        assertTrue(blacklist.isBlacklisted("com.android.sample2"));
        assertFalse(blacklist.isBlacklisted("com.android.sample3"));
    }

    @Test
    public void testNoBlacklist() {
        HighRefreshRateBlacklist blacklist = new HighRefreshRateBlacklist(
                new SystemPropertyGetter() {

                    @Override
                    public int getInt(String key, int def) {
                        return def;
                    }

                    @Override
                    public String get(String key) {
                        return "";
                    }
                });
        assertFalse(blacklist.isBlacklisted("com.android.sample1"));
    }
}
