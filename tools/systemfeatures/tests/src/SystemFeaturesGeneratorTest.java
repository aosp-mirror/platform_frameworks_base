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

package com.android.systemfeatures;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class SystemFeaturesGeneratorTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;

    @Before
    public void setUp() {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
    }

    @Test
    public void testReadonlyDisabledNoDefinedFeatures() {
        // Always report null for conditional queries if readonly codegen is disabled.
        assertThat(RwNoFeatures.maybeHasFeature(PackageManager.FEATURE_WATCH, 0)).isNull();
        assertThat(RwNoFeatures.maybeHasFeature(PackageManager.FEATURE_WIFI, 0)).isNull();
        assertThat(RwNoFeatures.maybeHasFeature(PackageManager.FEATURE_VULKAN, 0)).isNull();
        assertThat(RwNoFeatures.maybeHasFeature(PackageManager.FEATURE_AUTO, 0)).isNull();
        assertThat(RwNoFeatures.maybeHasFeature("com.arbitrary.feature", 0)).isNull();
    }

    @Test
    public void testReadonlyNoDefinedFeatures() {
        // If no features are explicitly declared as readonly available, always report
        // null for conditional queries.
        assertThat(RoNoFeatures.maybeHasFeature(PackageManager.FEATURE_WATCH, 0)).isNull();
        assertThat(RoNoFeatures.maybeHasFeature(PackageManager.FEATURE_WIFI, 0)).isNull();
        assertThat(RoNoFeatures.maybeHasFeature(PackageManager.FEATURE_VULKAN, 0)).isNull();
        assertThat(RoNoFeatures.maybeHasFeature(PackageManager.FEATURE_AUTO, 0)).isNull();
        assertThat(RoNoFeatures.maybeHasFeature("com.arbitrary.feature", 0)).isNull();

        // Also ensure we fall back to the PackageManager for feature APIs without an accompanying
        // versioned feature definition.
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH, 0)).thenReturn(true);
        assertThat(RwFeatures.hasFeatureWatch(mContext)).isTrue();
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH, 0)).thenReturn(false);
        assertThat(RwFeatures.hasFeatureWatch(mContext)).isFalse();
    }

    @Test
    public void testReadonlyDisabledWithDefinedFeatures() {
        // Always fall back to the PackageManager for defined, explicit features queries.
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH, 0)).thenReturn(true);
        assertThat(RwFeatures.hasFeatureWatch(mContext)).isTrue();

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH, 0)).thenReturn(false);
        assertThat(RwFeatures.hasFeatureWatch(mContext)).isFalse();

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI, 0)).thenReturn(true);
        assertThat(RwFeatures.hasFeatureWifi(mContext)).isTrue();

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN, 0)).thenReturn(false);
        assertThat(RwFeatures.hasFeatureVulkan(mContext)).isFalse();

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTO, 0)).thenReturn(false);
        assertThat(RwFeatures.hasFeatureAuto(mContext)).isFalse();

        // For defined and undefined features, conditional queries should report null (unknown).
        assertThat(RwFeatures.maybeHasFeature(PackageManager.FEATURE_WATCH, 0)).isNull();
        assertThat(RwFeatures.maybeHasFeature(PackageManager.FEATURE_WIFI, 0)).isNull();
        assertThat(RwFeatures.maybeHasFeature(PackageManager.FEATURE_VULKAN, 0)).isNull();
        assertThat(RwFeatures.maybeHasFeature(PackageManager.FEATURE_AUTO, 0)).isNull();
        assertThat(RwFeatures.maybeHasFeature("com.arbitrary.feature", 0)).isNull();
    }

    @Test
    public void testReadonlyWithDefinedFeatures() {
        // Always use the build-time feature version for defined, explicit feature queries, never
        // falling back to the runtime query.
        assertThat(RoFeatures.hasFeatureWatch(mContext)).isTrue();
        assertThat(RoFeatures.hasFeatureWifi(mContext)).isTrue();
        assertThat(RoFeatures.hasFeatureVulkan(mContext)).isFalse();
        verify(mPackageManager, never()).hasSystemFeature(anyString(), anyInt());

        // For defined feature types, conditional queries should reflect either:
        //  * Enabled if the feature version is specified
        //  * Disabled if UNAVAILABLE is specified
        //  * Unknown if no version value is provided

        // VERSION=1
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_WATCH, -1)).isTrue();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_WATCH, 0)).isTrue();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_WATCH, 100)).isFalse();

        // VERSION=0
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_WIFI, -1)).isTrue();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_WIFI, 0)).isTrue();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_WIFI, 100)).isFalse();

        // VERSION=UNAVAILABLE
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_VULKAN, -1)).isFalse();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_VULKAN, 0)).isFalse();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_VULKAN, 100)).isFalse();

        // VERSION=
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTO, 0)).thenReturn(false);
        assertThat(RoFeatures.hasFeatureAuto(mContext)).isFalse();
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTO, 0)).thenReturn(true);
        assertThat(RoFeatures.hasFeatureAuto(mContext)).isTrue();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_AUTO, -1)).isNull();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_AUTO, 0)).isNull();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_AUTO, 100)).isNull();

        // For feature APIs without an associated feature definition, conditional queries should
        // report null, and explicit queries should report runtime-defined versions.
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_PC, 0)).thenReturn(true);
        assertThat(RoFeatures.hasFeaturePc(mContext)).isTrue();
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_PC, 0)).thenReturn(false);
        assertThat(RoFeatures.hasFeaturePc(mContext)).isFalse();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_PC, -1)).isNull();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_PC, 0)).isNull();
        assertThat(RoFeatures.maybeHasFeature(PackageManager.FEATURE_PC, 100)).isNull();

        // For undefined types, conditional queries should report null (unknown).
        assertThat(RoFeatures.maybeHasFeature("com.arbitrary.feature", -1)).isNull();
        assertThat(RoFeatures.maybeHasFeature("com.arbitrary.feature", 0)).isNull();
        assertThat(RoFeatures.maybeHasFeature("com.arbitrary.feature", 100)).isNull();
        assertThat(RoFeatures.maybeHasFeature("", 0)).isNull();
    }
}
