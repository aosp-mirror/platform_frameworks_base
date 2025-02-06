/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.pm.RoSystemFeatures;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;


@RunWith(AndroidJUnit4.class)
@LargeTest
public class SystemFeaturesPerfTest {
    // As each query is relatively cheap, add an inner iteration loop to reduce execution noise.
    private static final int NUM_ITERATIONS = 10;

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void hasSystemFeature_PackageManager() {
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                pm.hasSystemFeature(PackageManager.FEATURE_WATCH);
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
                pm.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS);
                pm.hasSystemFeature(PackageManager.FEATURE_AUTOFILL);
                pm.hasSystemFeature("com.android.custom.feature.1");
                pm.hasSystemFeature("foo");
                pm.hasSystemFeature("");
            }
        }
    }

    @Test
    public void hasSystemFeature_SystemFeaturesCache() {
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        final SystemFeaturesCache cache =
                new SystemFeaturesCache(Arrays.asList(pm.getSystemAvailableFeatures()));
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                cache.maybeHasFeature(PackageManager.FEATURE_WATCH, 0);
                cache.maybeHasFeature(PackageManager.FEATURE_LEANBACK, 0);
                cache.maybeHasFeature(PackageManager.FEATURE_IPSEC_TUNNELS, 0);
                cache.maybeHasFeature(PackageManager.FEATURE_AUTOFILL, 0);
                cache.maybeHasFeature("com.android.custom.feature.1", 0);
                cache.maybeHasFeature("foo", 0);
                cache.maybeHasFeature("", 0);
            }
        }
    }

    @Test
    public void hasSystemFeature_RoSystemFeatures() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                RoSystemFeatures.maybeHasFeature(PackageManager.FEATURE_WATCH, 0);
                RoSystemFeatures.maybeHasFeature(PackageManager.FEATURE_LEANBACK, 0);
                RoSystemFeatures.maybeHasFeature(PackageManager.FEATURE_IPSEC_TUNNELS, 0);
                RoSystemFeatures.maybeHasFeature(PackageManager.FEATURE_AUTOFILL, 0);
                RoSystemFeatures.maybeHasFeature("com.android.custom.feature.1", 0);
                RoSystemFeatures.maybeHasFeature("foo", 0);
                RoSystemFeatures.maybeHasFeature("", 0);
            }
        }
    }
}
