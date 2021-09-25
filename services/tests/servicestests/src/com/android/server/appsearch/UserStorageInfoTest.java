/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.appsearch;

import static com.android.server.appsearch.UserStorageInfo.STORAGE_INFO_FILE;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.appsearch.icing.proto.DocumentStorageInfoProto;
import com.android.server.appsearch.icing.proto.NamespaceStorageInfoProto;
import com.android.server.appsearch.icing.proto.StorageInfoProto;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class UserStorageInfoTest {
    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private File mFileParentPath;
    private UserStorageInfo mUserStorageInfo;

    @Before
    public void setUp() throws Exception {
        mFileParentPath = mTemporaryFolder.newFolder();
        mUserStorageInfo = new UserStorageInfo(mFileParentPath);
    }

    @Test
    public void testReadStorageInfoFromFile() throws IOException {
        NamespaceStorageInfoProto namespaceStorageInfo1 =
                NamespaceStorageInfoProto.newBuilder()
                        .setNamespace("pkg1$db/namespace")
                        .setNumAliveDocuments(2)
                        .setNumExpiredDocuments(1)
                        .build();
        NamespaceStorageInfoProto namespaceStorageInfo2 =
                NamespaceStorageInfoProto.newBuilder()
                        .setNamespace("pkg2$db/namespace")
                        .setNumAliveDocuments(3)
                        .setNumExpiredDocuments(3)
                        .build();
        DocumentStorageInfoProto documentStorageInfo =
                DocumentStorageInfoProto.newBuilder()
                        .setNumAliveDocuments(5)
                        .setNumExpiredDocuments(4)
                        .addNamespaceStorageInfo(namespaceStorageInfo1)
                        .addNamespaceStorageInfo(namespaceStorageInfo2)
                        .build();
        StorageInfoProto storageInfo =
                StorageInfoProto.newBuilder()
                        .setDocumentStorageInfo(documentStorageInfo)
                        .setTotalStorageSize(9)
                        .build();
        File storageInfoFile = new File(mFileParentPath, STORAGE_INFO_FILE);
        try (FileOutputStream out = new FileOutputStream(storageInfoFile)) {
            storageInfo.writeTo(out);
        }

        mUserStorageInfo.readStorageInfoFromFile();

        assertThat(mUserStorageInfo.getTotalSizeBytes()).isEqualTo(
                storageInfo.getTotalStorageSize());
        // We calculate the package storage size based on number of documents a package has.
        // Here, total storage size is 9. pkg1 has 3 docs and pkg2 has 6 docs. So storage size of
        // pkg1 is 3. pkg2's storage size is 6.
        assertThat(mUserStorageInfo.getSizeBytesForPackage("pkg1")).isEqualTo(3);
        assertThat(mUserStorageInfo.getSizeBytesForPackage("pkg2")).isEqualTo(6);
        assertThat(mUserStorageInfo.getSizeBytesForPackage("invalid_pkg")).isEqualTo(0);
    }

    @Test
    public void testCalculatePackageStorageInfoMap() {
        NamespaceStorageInfoProto namespaceStorageInfo1 =
                NamespaceStorageInfoProto.newBuilder()
                        .setNamespace("pkg1$db/namespace")
                        .setNumAliveDocuments(2)
                        .setNumExpiredDocuments(1)
                        .build();
        NamespaceStorageInfoProto namespaceStorageInfo2 =
                NamespaceStorageInfoProto.newBuilder()
                        .setNamespace("pkg2$db/namespace")
                        .setNumAliveDocuments(3)
                        .setNumExpiredDocuments(3)
                        .build();
        DocumentStorageInfoProto documentStorageInfo =
                DocumentStorageInfoProto.newBuilder()
                        .setNumAliveDocuments(5)
                        .setNumExpiredDocuments(4)
                        .addNamespaceStorageInfo(namespaceStorageInfo1)
                        .addNamespaceStorageInfo(namespaceStorageInfo2)
                        .build();
        StorageInfoProto storageInfo =
                StorageInfoProto.newBuilder()
                        .setDocumentStorageInfo(documentStorageInfo)
                        .setTotalStorageSize(9)
                        .build();

        // We calculate the package storage size based on number of documents a package has.
        // Here, total storage size is 9. pkg1 has 3 docs and pkg2 has 6 docs. So storage size of
        // pkg1 is 3. pkg2's storage size is 6.
        assertThat(mUserStorageInfo.calculatePackageStorageInfoMap(storageInfo))
                .containsExactly("pkg1", 3L, "pkg2", 6L);
    }
}
