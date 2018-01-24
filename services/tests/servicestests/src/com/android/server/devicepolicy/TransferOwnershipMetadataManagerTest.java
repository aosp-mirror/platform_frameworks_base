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

import static com.android.server.devicepolicy.TransferOwnershipMetadataManager.ADMIN_TYPE_DEVICE_OWNER;


import static com.android.server.devicepolicy.TransferOwnershipMetadataManager
        .OWNER_TRANSFER_METADATA_XML;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Environment;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.devicepolicy.TransferOwnershipMetadataManager.Injector;
import com.android.server.devicepolicy.TransferOwnershipMetadataManager.Metadata;

import org.junit.After;
import org.junit.Before;
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
 * bit FrameworksServicesTests:com.android.server.devicepolicy.TransferOwnershipMetadataManagerTest
 * runtest -x frameworks/base/services/tests/servicestests/src/com/android/server/devicepolicy/TransferOwnershipMetadataManagerTest.java
* */

@RunWith(AndroidJUnit4.class)
public class TransferOwnershipMetadataManagerTest {
    private final static String ADMIN_PACKAGE = "com.dummy.admin.package";
    private final static String TARGET_PACKAGE = "com.dummy.target.package";
    private final static int USER_ID = 123;
    private final static Metadata TEST_PARAMS = new Metadata(ADMIN_PACKAGE,
            TARGET_PACKAGE, USER_ID, ADMIN_TYPE_DEVICE_OWNER);

    private MockInjector mMockInjector;

    @Before
    public void setUp() {
        mMockInjector = new MockInjector();
        getOwnerTransferParams().deleteMetadataFile();
    }

    @Test
    public void testSave() {
        TransferOwnershipMetadataManager paramsManager = getOwnerTransferParams();
        assertTrue(paramsManager.saveMetadataFile(TEST_PARAMS));
        assertTrue(paramsManager.metadataFileExists());
    }

    @Test
    public void testFileContentValid() {
        TransferOwnershipMetadataManager paramsManager = getOwnerTransferParams();
        assertTrue(paramsManager.saveMetadataFile(TEST_PARAMS));
        Path path = Paths.get(new File(mMockInjector.getOwnerTransferMetadataDir(),
                OWNER_TRANSFER_METADATA_XML).getAbsolutePath());
        try {
            String contents = new String(Files.readAllBytes(path), Charset.forName("UTF-8"));
            assertEquals(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    + "<user-id>" + USER_ID + "</user-id>\n"
                    + "<admin-component>" + ADMIN_PACKAGE + "</admin-component>\n"
                    + "<target-component>" + TARGET_PACKAGE + "</target-component>\n"
                    + "<admin-type>" + ADMIN_TYPE_DEVICE_OWNER + "</admin-type>\n",
                contents);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testLoad() {
        TransferOwnershipMetadataManager paramsManager = getOwnerTransferParams();
        paramsManager.saveMetadataFile(TEST_PARAMS);
        assertEquals(TEST_PARAMS, paramsManager.loadMetadataFile());
    }

    @Test
    public void testDelete() {
        TransferOwnershipMetadataManager paramsManager = getOwnerTransferParams();
        paramsManager.saveMetadataFile(TEST_PARAMS);
        paramsManager.deleteMetadataFile();
        assertFalse(paramsManager.metadataFileExists());
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
