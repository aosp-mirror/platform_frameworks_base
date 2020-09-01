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

package com.android.server.backup.encryption.tasks;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.chunking.ProtoStore;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunkListing;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto.KeyValueListing;
import com.android.server.backup.encryption.storage.BackupEncryptionDb;
import com.android.server.backup.encryption.storage.TertiaryKey;
import com.android.server.backup.encryption.storage.TertiaryKeysTable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ClearCryptoStateTaskTest {
    private static final String TEST_PACKAGE_NAME = "com.android.example";

    private ClearCryptoStateTask mClearCryptoStateTask;
    private CryptoSettings mCryptoSettings;
    private Context mApplication;

    @Before
    public void setUp() {
        mApplication = ApplicationProvider.getApplicationContext();
        mCryptoSettings = spy(CryptoSettings.getInstanceForTesting(mApplication));
        mClearCryptoStateTask = new ClearCryptoStateTask(mApplication, mCryptoSettings);
    }

    @Test
    public void run_clearsChunkListingProtoState() throws Exception {
        String packageName = TEST_PACKAGE_NAME;
        ChunkListing chunkListing = new ChunkListing();
        ProtoStore.createChunkListingStore(mApplication).saveProto(packageName, chunkListing);

        mClearCryptoStateTask.run();

        assertThat(
                        ProtoStore.createChunkListingStore(mApplication)
                                .loadProto(packageName)
                                .isPresent())
                .isFalse();
    }

    @Test
    public void run_clearsKeyValueProtoState() throws Exception {
        String packageName = TEST_PACKAGE_NAME;
        KeyValueListing keyValueListing = new KeyValueListing();
        ProtoStore.createKeyValueListingStore(mApplication).saveProto(packageName, keyValueListing);

        mClearCryptoStateTask.run();

        assertThat(
                        ProtoStore.createKeyValueListingStore(mApplication)
                                .loadProto(packageName)
                                .isPresent())
                .isFalse();
    }

    @Test
    public void run_clearsTertiaryKeysTable() throws Exception {
        String secondaryKeyAlias = "bob";
        TertiaryKeysTable tertiaryKeysTable =
                BackupEncryptionDb.newInstance(mApplication).getTertiaryKeysTable();
        tertiaryKeysTable.addKey(
                new TertiaryKey(
                        secondaryKeyAlias, "packageName", /*wrappedKeyBytes=*/ new byte[0]));

        mClearCryptoStateTask.run();

        assertThat(tertiaryKeysTable.getAllKeys(secondaryKeyAlias)).isEmpty();
    }

    @Test
    public void run_clearsSettings() {
        mCryptoSettings.setSecondaryLastRotated(100001);

        mClearCryptoStateTask.run();

        assertThat(mCryptoSettings.getSecondaryLastRotated().isPresent()).isFalse();
    }
}
