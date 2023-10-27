/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.settings;

import static android.provider.Settings.CALL_METHOD_GENERATION_INDEX_KEY;
import static android.provider.Settings.CALL_METHOD_GENERATION_KEY;
import static android.provider.Settings.CALL_METHOD_TRACK_GENERATION_KEY;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.util.MemoryIntArray;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class GenerationRegistryTest {
    @Test
    public void testGenerationsWithRegularSetting() throws IOException {
        final GenerationRegistry generationRegistry = new GenerationRegistry(2);
        final int secureKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_SECURE, 0);
        final String testSecureSetting = "test_secure_setting";
        Bundle b = new Bundle();
        // IncrementGeneration should have no effect on a non-cached setting.
        generationRegistry.incrementGeneration(secureKey, testSecureSetting);
        generationRegistry.incrementGeneration(secureKey, testSecureSetting);
        generationRegistry.incrementGeneration(secureKey, testSecureSetting);
        generationRegistry.addGenerationData(b, secureKey, testSecureSetting);
        // Default index is 0 and generation is only 1 despite early calls of incrementGeneration
        checkBundle(b, 0, 1, false);

        generationRegistry.incrementGeneration(secureKey, testSecureSetting);
        generationRegistry.addGenerationData(b, secureKey, testSecureSetting);
        // Index is still 0 and generation is now 2; also check direct array access
        assertThat(getArray(b).get(0)).isEqualTo(2);

        final int systemKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_SYSTEM, 0);
        final String testSystemSetting = "test_system_setting";
        generationRegistry.addGenerationData(b, systemKey, testSystemSetting);
        // Default index is 0 and generation is 1 for another backingStore (system)
        checkBundle(b, 0, 1, false);

        final String testSystemSetting2 = "test_system_setting2";
        generationRegistry.addGenerationData(b, systemKey, testSystemSetting2);
        // Second system setting index is 1 and default generation is 1
        checkBundle(b, 1, 1, false);

        generationRegistry.incrementGeneration(systemKey, testSystemSetting);
        generationRegistry.incrementGeneration(systemKey, testSystemSetting);
        generationRegistry.addGenerationData(b, systemKey, testSystemSetting);
        // First system setting generation now incremented to 3
        checkBundle(b, 0, 3, false);

        final int systemKey2 = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_SYSTEM, 10);
        generationRegistry.addGenerationData(b, systemKey2, testSystemSetting);
        // User 10 has a new set of backingStores
        checkBundle(b, 0, 1, false);

        // Check user removal
        generationRegistry.onUserRemoved(10);
        generationRegistry.incrementGeneration(systemKey2, testSystemSetting);

        // Removed user should not affect existing caches
        generationRegistry.addGenerationData(b, secureKey, testSecureSetting);
        assertThat(getArray(b).get(0)).isEqualTo(2);

        // IncrementGeneration should have no effect for a non-cached user
        b.clear();
        checkBundle(b, -1, -1, true);
        // AddGeneration should create new backing store for the non-cached user
        generationRegistry.addGenerationData(b, systemKey2, testSystemSetting);
        checkBundle(b, 0, 1, false);
    }

    @Test
    public void testGenerationsWithConfigSetting() throws IOException {
        final GenerationRegistry generationRegistry = new GenerationRegistry(1);
        final String prefix = "test_namespace/";
        final int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);

        Bundle b = new Bundle();
        generationRegistry.addGenerationData(b, configKey, prefix);
        checkBundle(b, 0, 1, false);

        final String setting = "test_namespace/test_setting";
        // Check that the generation of the prefix is incremented correctly
        generationRegistry.incrementGeneration(configKey, setting);
        generationRegistry.addGenerationData(b, configKey, prefix);
        checkBundle(b, 0, 2, false);
    }

    @Test
    public void testMaxNumBackingStores() throws IOException {
        final GenerationRegistry generationRegistry = new GenerationRegistry(2);
        final String testSecureSetting = "test_secure_setting";
        Bundle b = new Bundle();
        for (int i = 0; i < generationRegistry.getMaxNumBackingStores(); i++) {
            b.clear();
            final int key = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_SECURE, i);
            generationRegistry.addGenerationData(b, key, testSecureSetting);
            checkBundle(b, 0, 1, false);
        }
        b.clear();
        final int key = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_SECURE,
                generationRegistry.getMaxNumBackingStores() + 1);
        generationRegistry.addGenerationData(b, key, testSecureSetting);
        // Should fail to add generation because the number of backing stores has reached limit
        checkBundle(b, -1, -1, true);
        // Remove one user should free up a backing store
        generationRegistry.onUserRemoved(0);
        generationRegistry.addGenerationData(b, key, testSecureSetting);
        checkBundle(b, 0, 1, false);
    }

    @Test
    public void testMaxSizeBackingStore() throws IOException {
        final GenerationRegistry generationRegistry = new GenerationRegistry(1);
        final int secureKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_SECURE, 0);
        final String testSecureSetting = "test_secure_setting";
        Bundle b = new Bundle();
        for (int i = 0; i < GenerationRegistry.MAX_BACKING_STORE_SIZE; i++) {
            generationRegistry.addGenerationData(b, secureKey, testSecureSetting + i);
            checkBundle(b, i, 1, false);
        }
        b.clear();
        generationRegistry.addGenerationData(b, secureKey, testSecureSetting);
        // Should fail to increase index because the number of entries in the backing store has
        // reached the limit
        checkBundle(b, -1, -1, true);
        // Shouldn't affect other cached entries
        generationRegistry.addGenerationData(b, secureKey, testSecureSetting + "0");
        checkBundle(b, 0, 1, false);
    }

    @Test
    public void testUnsetSettings() throws IOException {
        final GenerationRegistry generationRegistry = new GenerationRegistry(1);
        final int secureKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_SECURE, 0);
        final String testSecureSetting = "test_secure_setting";
        Bundle b = new Bundle();
        generationRegistry.addGenerationData(b, secureKey, testSecureSetting);
        checkBundle(b, 0, 1, false);
        generationRegistry.addGenerationDataForUnsetSettings(b, secureKey);
        checkBundle(b, 1, 1, false);
        generationRegistry.addGenerationDataForUnsetSettings(b, secureKey);
        // Test that unset settings always have the same index
        checkBundle(b, 1, 1, false);
        generationRegistry.incrementGenerationForUnsetSettings(secureKey);
        // Test that the generation number of the unset settings have increased
        generationRegistry.addGenerationDataForUnsetSettings(b, secureKey);
        checkBundle(b, 1, 2, false);
    }

    @Test
    public void testGlobalSettings() throws IOException {
        final GenerationRegistry generationRegistry = new GenerationRegistry(2);
        final int globalKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_GLOBAL, 0);
        final String testGlobalSetting = "test_global_setting";
        final Bundle b = new Bundle();
        generationRegistry.addGenerationData(b, globalKey, testGlobalSetting);
        checkBundle(b, 0, 1, false);
        final MemoryIntArray array = getArray(b);
        final int globalKey2 = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_GLOBAL, 10);
        b.clear();
        generationRegistry.addGenerationData(b, globalKey2, testGlobalSetting);
        checkBundle(b, 0, 1, false);
        final MemoryIntArray array2 = getArray(b);
        // Check that user10 and user0 use the same array to store global settings' generations
        assertThat(array).isEqualTo(array2);
    }

    @Test
    public void testNumberOfBackingStores() {
        GenerationRegistry generationRegistry = new GenerationRegistry(0);
        // Test that the capacity of the backing stores is always valid
        assertThat(generationRegistry.getMaxNumBackingStores()).isEqualTo(
                GenerationRegistry.MIN_NUM_BACKING_STORE);
        generationRegistry = new GenerationRegistry(100);
        // Test that the capacity of the backing stores is always valid
        assertThat(generationRegistry.getMaxNumBackingStores()).isEqualTo(
                GenerationRegistry.MAX_NUM_BACKING_STORE);
    }

    private void checkBundle(Bundle b, int expectedIndex, int expectedGeneration, boolean isNull)
            throws IOException {
        final MemoryIntArray array = getArray(b);
        if (isNull) {
            assertThat(array).isNull();
        } else {
            assertThat(array).isNotNull();
        }
        final int index = b.getInt(
                CALL_METHOD_GENERATION_INDEX_KEY, -1);
        assertThat(index).isEqualTo(expectedIndex);
        final int generation = b.getInt(CALL_METHOD_GENERATION_KEY, -1);
        assertThat(generation).isEqualTo(expectedGeneration);
        if (!isNull) {
            // Read into the result array with the result index should match the result generation
            assertThat(array.get(index)).isEqualTo(generation);
        }
    }

    private MemoryIntArray getArray(Bundle b) {
        return b.getParcelable(
                CALL_METHOD_TRACK_GENERATION_KEY, android.util.MemoryIntArray.class);
    }
}
