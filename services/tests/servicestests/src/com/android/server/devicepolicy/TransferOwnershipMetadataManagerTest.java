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

package com.android.server.devicepolicy;

import static com.android.server.devicepolicy.TransferOwnershipMetadataManager
        .ADMIN_TYPE_DEVICE_OWNER;
import static com.android.server.devicepolicy.TransferOwnershipMetadataManager
        .OWNER_TRANSFER_METADATA_XML;
import static com.android.server.devicepolicy.TransferOwnershipMetadataManager.TAG_ADMIN_TYPE;
import static com.android.server.devicepolicy.TransferOwnershipMetadataManager.TAG_SOURCE_COMPONENT;
import static com.android.server.devicepolicy.TransferOwnershipMetadataManager.TAG_TARGET_COMPONENT;
import static com.android.server.devicepolicy.TransferOwnershipMetadataManager.TAG_USER_ID;

import static com.google.common.truth.Truth.assertThat;

import android.os.Environment;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.devicepolicy.TransferOwnershipMetadataManager.Injector;
import com.android.server.devicepolicy.TransferOwnershipMetadataManager.Metadata;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
* Unit tests for {@link TransferOwnershipMetadataManager}.
 *
 * <p>Run this test with:
 *
 * <pre><code>
 atest FrameworksServicesTests:com.android.server.devicepolicy.TransferOwnershipMetadataManagerTest
 * </code></pre>
 */
@RunWith(AndroidJUnit4.class)
public final class TransferOwnershipMetadataManagerTest {
    private final static String TAG = TransferOwnershipMetadataManagerTest.class.getName();
    private final static String SOURCE_COMPONENT =
            "com.dummy.admin.package/com.dummy.admin.package.SourceClassName";
    private final static String TARGET_COMPONENT =
            "com.dummy.target.package/com.dummy.target.package.TargetClassName";
    private final static int USER_ID = 123;
    private final static Metadata TEST_PARAMS = new Metadata(SOURCE_COMPONENT,
            TARGET_COMPONENT, USER_ID, ADMIN_TYPE_DEVICE_OWNER);

    private MockInjector mMockInjector;

    @Before
    public void setUp() {
        mMockInjector = new MockInjector();
        getOwnerTransferParams().deleteMetadataFile();
    }

    @Test
    public void testSave() {
        TransferOwnershipMetadataManager paramsManager = getOwnerTransferParams();
        assertThat(paramsManager.saveMetadataFile(TEST_PARAMS)).isTrue();
        assertThat(paramsManager.metadataFileExists()).isTrue();
    }

    @Test
    @Ignore
    public void testFileContentValid() {
        TransferOwnershipMetadataManager paramsManager = getOwnerTransferParams();
        assertThat(paramsManager.saveMetadataFile(TEST_PARAMS)).isTrue();
        Path path = Paths.get(new File(mMockInjector.getOwnerTransferMetadataDir(),
                OWNER_TRANSFER_METADATA_XML).getAbsolutePath());
        try {
            String contents = new String(Files.readAllBytes(path), Charset.forName("UTF-8"));
            assertThat(contents).isEqualTo(
                    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                            + "<" + TAG_USER_ID + ">" + USER_ID + "</" + TAG_USER_ID + ">\n"
                            + "<" + TAG_SOURCE_COMPONENT + ">" + SOURCE_COMPONENT + "</"
                            + TAG_SOURCE_COMPONENT + ">\n"
                            + "<" + TAG_TARGET_COMPONENT + ">" + TARGET_COMPONENT + "</"
                            + TAG_TARGET_COMPONENT + ">\n"
                            + "<" + TAG_ADMIN_TYPE + ">" + ADMIN_TYPE_DEVICE_OWNER + "</"
                            + TAG_ADMIN_TYPE + ">\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testLoad() {
        TransferOwnershipMetadataManager paramsManager = getOwnerTransferParams();
        final File transferOwnershipMetadataFile =
                new File(mMockInjector.getOwnerTransferMetadataDir(), OWNER_TRANSFER_METADATA_XML);
        Log.d(TAG, "testLoad: file path is " + transferOwnershipMetadataFile.getAbsolutePath());
        Log.d(TAG, "testLoad: file exists? " + transferOwnershipMetadataFile.exists());
        Log.d(TAG, "testLoad: file mkdir?" + transferOwnershipMetadataFile.mkdir());
        try {
            File canonicalFile = transferOwnershipMetadataFile.getCanonicalFile();
            File parentFile = canonicalFile.getParentFile();
            Log.d(TAG, "testLoad: file getCanonicalFile?" + canonicalFile);
            Log.d(TAG, "testLoad: getCanonicalFile.getParentFile " + parentFile);
            Log.d(TAG, "testLoad: parent mkdirs? " + parentFile.mkdirs());
            Log.d(TAG, "testLoad: parent exists? " + parentFile.exists());
            Log.d(TAG, "testLoad: canonical file.mkdir()? " + canonicalFile.mkdir());
        } catch (IOException e) {
            Log.d(TAG, "testLoad: failed to get canonical file");
        }
        paramsManager.saveMetadataFile(TEST_PARAMS);
        assertThat(paramsManager.loadMetadataFile()).isEqualTo(TEST_PARAMS);
    }

    @Test
    public void testDelete() {
        TransferOwnershipMetadataManager paramsManager = getOwnerTransferParams();
        paramsManager.saveMetadataFile(TEST_PARAMS);
        paramsManager.deleteMetadataFile();
        assertThat(paramsManager.metadataFileExists()).isFalse();
    }

    @After
    public void tearDown() {
        getOwnerTransferParams().deleteMetadataFile();
    }

    private TransferOwnershipMetadataManager getOwnerTransferParams() {
        return new TransferOwnershipMetadataManager(mMockInjector);
    }

    static class MockInjector extends Injector {
        @Override
        public File getOwnerTransferMetadataDir() {
            return Environment.getExternalStorageDirectory();
        }
    }

}
