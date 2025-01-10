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

import static com.android.internal.pm.SystemFeaturesMetadata.maybeGetSdkFeatureIndex;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.PackageManager;

import com.android.internal.pm.SystemFeaturesMetadata;

import com.google.common.collect.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SystemFeaturesMetadataProcessorTest {

    @Test
    public void testSdkFeatureCount() {
        // See the fake PackageManager definition in this directory.
        // It defines 6 annotated features, and any/all other constants should be ignored.
        assertThat(SystemFeaturesMetadata.SDK_FEATURE_COUNT).isEqualTo(6);
    }

    @Test
    public void testSdkFeatureIndex() {
        // Only SDK-defined features return valid indices.
        final Range validIndexRange = Range.closedOpen(0, SystemFeaturesMetadata.SDK_FEATURE_COUNT);
        assertThat(maybeGetSdkFeatureIndex(PackageManager.FEATURE_PC)).isIn(validIndexRange);
        assertThat(maybeGetSdkFeatureIndex(PackageManager.FEATURE_VULKAN)).isIn(validIndexRange);
        assertThat(maybeGetSdkFeatureIndex(PackageManager.FEATURE_NOT_ANNOTATED)).isEqualTo(-1);
        assertThat(maybeGetSdkFeatureIndex(PackageManager.NOT_FEATURE)).isEqualTo(-1);
        assertThat(maybeGetSdkFeatureIndex("foo")).isEqualTo(-1);
        assertThat(maybeGetSdkFeatureIndex("0")).isEqualTo(-1);
        assertThat(maybeGetSdkFeatureIndex("")).isEqualTo(-1);
    }
}
