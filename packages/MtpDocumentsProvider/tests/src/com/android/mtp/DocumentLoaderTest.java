/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.content.Context;
import android.database.Cursor;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

@MediumTest
public class DocumentLoaderTest extends AndroidTestCase {
    private MtpDatabase mDatabase;
    private BlockableTestMtpManager mManager;
    private TestContentResolver mResolver;
    private DocumentLoader mLoader;
    final private Identifier mParentIdentifier = new Identifier(
            0, 0, 0, "2", MtpDatabaseConstants.DOCUMENT_TYPE_STORAGE);

    @Override
    public void setUp() throws Exception {
        mDatabase = new MtpDatabase(getContext(), MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);

        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(
                new MtpDeviceRecord(0, "Device", null, true, new MtpRoot[0], null, null));
        mDatabase.getMapper().stopAddingDocuments(null);

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", new int[0], new MtpRoot[] {
                new MtpRoot(0, 0, "Storage", 1000, 1000, "")
        });
        mDatabase.getMapper().stopAddingDocuments("1");

        mManager = new BlockableTestMtpManager(getContext());
        mResolver = new TestContentResolver();
    }

    @Override
    public void tearDown() throws Exception {
        mLoader.close();
        mDatabase.close();
    }

    public void testBasic() throws Exception {
        setUpLoader();

        final Uri uri = DocumentsContract.buildChildDocumentsUri(
                MtpDocumentsProvider.AUTHORITY, mParentIdentifier.mDocumentId);
        setUpDocument(mManager, 40);
        mManager.blockDocument(0, 15);
        mManager.blockDocument(0, 35);

        {
            final Cursor cursor = mLoader.queryChildDocuments(
                    MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier);
            assertEquals(DocumentLoader.NUM_INITIAL_ENTRIES, cursor.getCount());
        }

        Thread.sleep(DocumentLoader.NOTIFY_PERIOD_MS);
        mManager.unblockDocument(0, 15);
        mResolver.waitForNotification(uri, 1);

        {
            final Cursor cursor = mLoader.queryChildDocuments(
                    MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier);
            assertEquals(
                    DocumentLoader.NUM_INITIAL_ENTRIES + DocumentLoader.NUM_LOADING_ENTRIES,
                    cursor.getCount());
        }

        mManager.unblockDocument(0, 35);
        mResolver.waitForNotification(uri, 2);

        {
            final Cursor cursor = mLoader.queryChildDocuments(
                    MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier);
            assertEquals(40, cursor.getCount());
        }

        assertEquals(2, mResolver.getChangeCount(uri));
    }

    public void testError_GetObjectHandles() throws Exception {
        mManager = new BlockableTestMtpManager(getContext()) {
            @Override
            int[] getObjectHandles(int deviceId, int storageId, int parentObjectHandle)
                    throws IOException {
                throw new IOException();
            }
        };
        setUpLoader();
        mManager.setObjectHandles(0, 0, MtpManager.OBJECT_HANDLE_ROOT_CHILDREN, null);
        try {
            try (final Cursor cursor = mLoader.queryChildDocuments(
                    MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier)) {}
            fail();
        } catch (IOException exception) {
            // Expect exception.
        }
    }

    public void testError_GetObjectInfo() throws Exception {
        mManager = new BlockableTestMtpManager(getContext()) {
            @Override
            MtpObjectInfo getObjectInfo(int deviceId, int objectHandle) throws IOException {
                if (objectHandle == DocumentLoader.NUM_INITIAL_ENTRIES) {
                    throw new IOException();
                } else {
                    return super.getObjectInfo(deviceId, objectHandle);
                }
            }
        };
        setUpLoader();
        setUpDocument(mManager, DocumentLoader.NUM_INITIAL_ENTRIES);
        try (final Cursor cursor = mLoader.queryChildDocuments(
                MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier)) {
            // Even if MtpManager returns an error for a document, loading must complete.
            assertFalse(cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING));
        }
    }

    public void testCancelTask() throws IOException, InterruptedException, TimeoutException {
        setUpDocument(mManager,
                DocumentLoader.NUM_INITIAL_ENTRIES + 1);

        // Block the first iteration in the background thread.
        mManager.blockDocument(
                0, DocumentLoader.NUM_INITIAL_ENTRIES + 1);
        setUpLoader();
        try (final Cursor cursor = mLoader.queryChildDocuments(
                MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier)) {
            assertTrue(cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING));
        }

        final Uri uri = DocumentsContract.buildChildDocumentsUri(
                MtpDocumentsProvider.AUTHORITY, mParentIdentifier.mDocumentId);
        assertEquals(0, mResolver.getChangeCount(uri));

        // Clear task while the first iteration is being blocked.
        mLoader.cancelTask(mParentIdentifier);
        mManager.unblockDocument(
                0, DocumentLoader.NUM_INITIAL_ENTRIES + 1);
        Thread.sleep(DocumentLoader.NOTIFY_PERIOD_MS);
        assertEquals(0, mResolver.getChangeCount(uri));

        // Check if it's OK to query invalidated task.
        try (final Cursor cursor = mLoader.queryChildDocuments(
                MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier)) {
            assertTrue(cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING));
        }
        mResolver.waitForNotification(uri, 1);
    }

    private void setUpLoader() {
        mLoader = new DocumentLoader(
                new MtpDeviceRecord(
                        0, "Device", "Key", true, new MtpRoot[0],
                        TestUtil.OPERATIONS_SUPPORTED, new int[0]),
                mManager,
                mResolver,
                mDatabase);
    }

    private void setUpDocument(TestMtpManager manager, int count) {
        int[] childDocuments = new int[count];
        for (int i = 0; i < childDocuments.length; i++) {
            final int objectHandle = i + 1;
            childDocuments[i] = objectHandle;
            manager.setObjectInfo(0, new MtpObjectInfo.Builder()
                    .setObjectHandle(objectHandle)
                    .setName(Integer.toString(i))
                    .build());
        }
        manager.setObjectHandles(0, 0, MtpManager.OBJECT_HANDLE_ROOT_CHILDREN, childDocuments);
    }

    private static class BlockableTestMtpManager extends TestMtpManager {
        final private Map<String, CountDownLatch> blockedDocuments = new HashMap<>();

        BlockableTestMtpManager(Context context) {
            super(context);
        }

        void blockDocument(int deviceId, int objectHandle) {
            blockedDocuments.put(pack(deviceId, objectHandle), new CountDownLatch(1));
        }

        void unblockDocument(int deviceId, int objectHandle) {
            blockedDocuments.get(pack(deviceId, objectHandle)).countDown();
        }

        @Override
        MtpObjectInfo getObjectInfo(int deviceId, int objectHandle) throws IOException {
            final CountDownLatch latch = blockedDocuments.get(pack(deviceId, objectHandle));
            if (latch != null) {
                try {
                    latch.await();
                } catch(InterruptedException e) {
                    fail();
                }
            }
            return super.getObjectInfo(deviceId, objectHandle);
        }
    }
}
