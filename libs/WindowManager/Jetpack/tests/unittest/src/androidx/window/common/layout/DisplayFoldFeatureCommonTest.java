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

package androidx.window.common.layout;

import static androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_UNKNOWN;
import static androidx.window.common.layout.CommonFoldingFeature.COMMON_TYPE_FOLD;
import static androidx.window.common.layout.CommonFoldingFeature.COMMON_TYPE_HINGE;
import static androidx.window.common.layout.DisplayFoldFeatureCommon.DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED;
import static androidx.window.common.layout.DisplayFoldFeatureCommon.DISPLAY_FOLD_FEATURE_TYPE_HINGE;
import static androidx.window.common.layout.DisplayFoldFeatureCommon.DISPLAY_FOLD_FEATURE_TYPE_SCREEN_FOLD_IN;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Rect;
import android.util.ArraySet;

import org.junit.Test;

import java.util.Set;

/**
 * Test class for {@link DisplayFoldFeatureCommon}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:DisplayFoldFeatureCommonTest
 */
public class DisplayFoldFeatureCommonTest {

    @Test
    public void test_different_type_not_equals() {
        final Set<Integer> properties = new ArraySet<>();
        final DisplayFoldFeatureCommon first =
                new DisplayFoldFeatureCommon(DISPLAY_FOLD_FEATURE_TYPE_HINGE, properties);
        final DisplayFoldFeatureCommon second =
                new DisplayFoldFeatureCommon(DISPLAY_FOLD_FEATURE_TYPE_SCREEN_FOLD_IN, properties);

        assertThat(first).isEqualTo(second);
    }

    @Test
    public void test_different_property_set_not_equals() {
        final Set<Integer> firstProperties = new ArraySet<>();
        final Set<Integer> secondProperties = new ArraySet<>();
        secondProperties.add(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED);
        final DisplayFoldFeatureCommon first =
                new DisplayFoldFeatureCommon(DISPLAY_FOLD_FEATURE_TYPE_HINGE, firstProperties);
        final DisplayFoldFeatureCommon second =
                new DisplayFoldFeatureCommon(DISPLAY_FOLD_FEATURE_TYPE_HINGE, secondProperties);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    public void test_check_single_property_exists() {
        final Set<Integer> properties = new ArraySet<>();
        properties.add(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED);
        final DisplayFoldFeatureCommon foldFeatureCommon =
                new DisplayFoldFeatureCommon(DISPLAY_FOLD_FEATURE_TYPE_HINGE, properties);

        assertThat(
                foldFeatureCommon.hasProperty(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED))
                .isTrue();
    }

    @Test
    public void test_check_multiple_properties_exists() {
        final Set<Integer> properties = new ArraySet<>();
        properties.add(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED);
        final DisplayFoldFeatureCommon foldFeatureCommon =
                new DisplayFoldFeatureCommon(DISPLAY_FOLD_FEATURE_TYPE_HINGE, properties);

        assertThat(foldFeatureCommon.hasProperties(
                DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED))
                .isTrue();
    }

    @Test
    public void test_properties_matches_getter() {
        final Set<Integer> properties = new ArraySet<>();
        properties.add(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED);
        final DisplayFoldFeatureCommon foldFeatureCommon =
                new DisplayFoldFeatureCommon(DISPLAY_FOLD_FEATURE_TYPE_HINGE, properties);

        assertThat(foldFeatureCommon.getProperties()).isEqualTo(properties);
    }

    @Test
    public void test_type_matches_getter() {
        final Set<Integer> properties = new ArraySet<>();
        final DisplayFoldFeatureCommon foldFeatureCommon =
                new DisplayFoldFeatureCommon(DISPLAY_FOLD_FEATURE_TYPE_HINGE, properties);

        assertThat(foldFeatureCommon.getType()).isEqualTo(DISPLAY_FOLD_FEATURE_TYPE_HINGE);
    }

    @Test
    public void test_create_half_opened_feature() {
        final CommonFoldingFeature foldingFeature =
                new CommonFoldingFeature(COMMON_TYPE_HINGE, COMMON_STATE_UNKNOWN, new Rect());
        final DisplayFoldFeatureCommon foldFeatureCommon = DisplayFoldFeatureCommon.create(
                foldingFeature, true);

        assertThat(foldFeatureCommon.getType()).isEqualTo(DISPLAY_FOLD_FEATURE_TYPE_HINGE);
        assertThat(
                foldFeatureCommon.hasProperty(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED))
                .isTrue();
    }

    @Test
    public void test_create_fold_feature_no_half_opened() {
        final CommonFoldingFeature foldingFeature =
                new CommonFoldingFeature(COMMON_TYPE_FOLD, COMMON_STATE_UNKNOWN, new Rect());
        final DisplayFoldFeatureCommon foldFeatureCommon = DisplayFoldFeatureCommon.create(
                foldingFeature, true);

        assertThat(foldFeatureCommon.getType()).isEqualTo(DISPLAY_FOLD_FEATURE_TYPE_SCREEN_FOLD_IN);
        assertThat(
                foldFeatureCommon.hasProperty(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED))
                .isTrue();
    }
}
