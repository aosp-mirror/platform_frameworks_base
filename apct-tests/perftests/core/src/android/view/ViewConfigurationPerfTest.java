/*
 * Copyright 2023 The Android Open Source Project
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

package android.view;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.content.Context;

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;

@SmallTest
public class ViewConfigurationPerfTest {
    @Rule
    public final BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    private final Context mContext = getInstrumentation().getTargetContext();

    @Test
    public void testGet_newViewConfiguration() {
        final BenchmarkState state = mBenchmarkRule.getState();

        while (state.keepRunning()) {
            state.pauseTiming();
            // Reset cache so that `ViewConfiguration#get` creates a new instance.
            ViewConfiguration.resetCacheForTesting();
            state.resumeTiming();

            ViewConfiguration.get(mContext);
        }
    }

    @Test
    public void testGet_cachedViewConfiguration() {
        final BenchmarkState state = mBenchmarkRule.getState();
        // Do `get` once to make sure there's something cached.
        ViewConfiguration.get(mContext);

        while (state.keepRunning()) {
            ViewConfiguration.get(mContext);
        }
    }
}
