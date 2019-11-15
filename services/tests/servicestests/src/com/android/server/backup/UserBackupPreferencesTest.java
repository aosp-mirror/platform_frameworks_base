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

package com.android.server.backup;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class UserBackupPreferencesTest {
    private static final String EXCLUDED_PACKAGE_1 = "package1";
    private static final String EXCLUDED_PACKAGE_2 = "package2";
    private static final List<String> EXCLUDED_KEYS_1 = Arrays.asList("key1", "key2");
    private static final List<String> EXCLUDED_KEYS_2 = Arrays.asList("key1");

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private UserBackupPreferences mExcludedRestoreKeysStorage;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mExcludedRestoreKeysStorage =
                new UserBackupPreferences(
                        InstrumentationRegistry.getContext(), mTemporaryFolder.newFolder());
    }

    @Test
    public void testGetExcludedKeysForPackages_returnsExcludedKeys() {
        mExcludedRestoreKeysStorage.addExcludedKeys(EXCLUDED_PACKAGE_1, EXCLUDED_KEYS_1);
        mExcludedRestoreKeysStorage.addExcludedKeys(EXCLUDED_PACKAGE_2, EXCLUDED_KEYS_2);

        Map<String, Set<String>> excludedKeys =
                mExcludedRestoreKeysStorage.getExcludedRestoreKeysForPackages(EXCLUDED_PACKAGE_1);
        assertTrue(excludedKeys.containsKey(EXCLUDED_PACKAGE_1));
        assertFalse(excludedKeys.containsKey(EXCLUDED_PACKAGE_2));
        assertEquals(new HashSet<>(EXCLUDED_KEYS_1), excludedKeys.get(EXCLUDED_PACKAGE_1));
    }

    @Test
    public void testGetExcludedKeysForPackages_withEmpty_list_returnsAllExcludedKeys() {
        mExcludedRestoreKeysStorage.addExcludedKeys(EXCLUDED_PACKAGE_1, EXCLUDED_KEYS_1);
        mExcludedRestoreKeysStorage.addExcludedKeys(EXCLUDED_PACKAGE_2, EXCLUDED_KEYS_2);

        Map<String, Set<String>> excludedKeys =
                mExcludedRestoreKeysStorage.getAllExcludedRestoreKeys();
        assertTrue(excludedKeys.containsKey(EXCLUDED_PACKAGE_1));
        assertTrue(excludedKeys.containsKey(EXCLUDED_PACKAGE_2));
        assertEquals(new HashSet<>(EXCLUDED_KEYS_1), excludedKeys.get(EXCLUDED_PACKAGE_1));
        assertEquals(new HashSet<>(EXCLUDED_KEYS_2), excludedKeys.get(EXCLUDED_PACKAGE_2));
    }
}
