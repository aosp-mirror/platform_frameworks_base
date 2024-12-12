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
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class SystemFeaturesMetadataPerfTest {
    // As each query is relatively cheap, add an inner iteration loop to reduce execution noise.
    private static final int NUM_ITERATIONS = 10;

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void maybeGetSdkFeatureIndex_featureDefined() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                PackageManager.maybeGetSdkFeatureIndex(PackageManager.FEATURE_WATCH);
                PackageManager.maybeGetSdkFeatureIndex(PackageManager.FEATURE_LEANBACK);
                PackageManager.maybeGetSdkFeatureIndex(PackageManager.FEATURE_IPSEC_TUNNELS);
                PackageManager.maybeGetSdkFeatureIndex(PackageManager.FEATURE_WEBVIEW);
                PackageManager.maybeGetSdkFeatureIndex(PackageManager.FEATURE_NFC_BEAM);
                PackageManager.maybeGetSdkFeatureIndex(PackageManager.FEATURE_AUTOFILL);
            }
        }
    }

    @Test
    public void maybeGetSdkFeatureIndex_featureUndefined() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < NUM_ITERATIONS; ++i) {
                PackageManager.maybeGetSdkFeatureIndex("com.android.custom.feature.1");
                PackageManager.maybeGetSdkFeatureIndex("com.android.custom.feature.2");
                PackageManager.maybeGetSdkFeatureIndex("foo");
                PackageManager.maybeGetSdkFeatureIndex("bar");
                PackageManager.maybeGetSdkFeatureIndex("0");
                PackageManager.maybeGetSdkFeatureIndex("");
            }
        }
    }

}
