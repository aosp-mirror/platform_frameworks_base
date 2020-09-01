/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.storage;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Tests for {@link BackupEncryptionDb}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class BackupEncryptionDbTest {
    private BackupEncryptionDb mBackupEncryptionDb;

    /** Creates an empty {@link BackupEncryptionDb} */
    @Before
    public void setUp() {
        mBackupEncryptionDb = BackupEncryptionDb.newInstance(RuntimeEnvironment.application);
    }

    /**
     * Tests that the tertiary keys table gets cleared when calling {@link
     * BackupEncryptionDb#clear()}.
     */
    @Test
    public void clear_withNonEmptyTertiaryKeysTable_clearsTertiaryKeysTable() throws Exception {
        String secondaryKeyAlias = "secondaryKeyAlias";
        TertiaryKeysTable tertiaryKeysTable = mBackupEncryptionDb.getTertiaryKeysTable();
        tertiaryKeysTable.addKey(new TertiaryKey(secondaryKeyAlias, "packageName", new byte[0]));

        mBackupEncryptionDb.clear();

        assertThat(tertiaryKeysTable.getAllKeys(secondaryKeyAlias)).isEmpty();
    }
}
