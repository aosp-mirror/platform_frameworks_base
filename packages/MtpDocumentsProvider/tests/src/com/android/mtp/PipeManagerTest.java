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

import android.database.Cursor;
import android.mtp.MtpObjectInfo;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@MediumTest
public class PipeManagerTest extends AndroidTestCase {
    private static final byte[] HELLO_BYTES = new byte[] { 'h', 'e', 'l', 'l', 'o' };

    private TestMtpManager mtpManager;
    private ExecutorService mExecutor;
    private PipeManager mPipeManager;
    private MtpDatabase mDatabase;

    @Override
    public void setUp() {
        mtpManager = new TestMtpManager(getContext());
        mExecutor = Executors.newSingleThreadExecutor();
        mDatabase = new MtpDatabase(getContext(), MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        mPipeManager = new PipeManager(mDatabase, mExecutor);
    }

    @Override
    protected void tearDown() throws Exception {
        assertTrue(mPipeManager.close());
        mDatabase.close();
    }

    public void testReadDocument_basic() throws Exception {
        mtpManager.setImportFileBytes(0, 1, HELLO_BYTES);
        final ParcelFileDescriptor descriptor = mPipeManager.readDocument(
                mtpManager,
                new Identifier(0, 0, 1, null, MtpDatabaseConstants.DOCUMENT_TYPE_OBJECT));
        assertDescriptor(descriptor, HELLO_BYTES);
    }

    public void testReadDocument_error() throws Exception {
        final ParcelFileDescriptor descriptor = mPipeManager.readDocument(
                mtpManager,
                new Identifier(0, 0, 1, null, MtpDatabaseConstants.DOCUMENT_TYPE_OBJECT));
        assertDescriptorError(descriptor);
    }

    public void testWriteDocument_basic() throws Exception {
        TestUtil.addTestDevice(mDatabase);
        TestUtil.addTestStorage(mDatabase, "1");

        final MtpObjectInfo info =
                new MtpObjectInfo.Builder().setObjectHandle(1).setName("note.txt").build();
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(
                0, "2", TestUtil.OPERATIONS_SUPPORTED,
                new MtpObjectInfo[] { info });
        mDatabase.getMapper().stopAddingDocuments("2");
        // Create a placeholder file which should be replaced by a real file later.
        mtpManager.setObjectInfo(0, info);

        // Upload testing bytes.
        final ParcelFileDescriptor descriptor = mPipeManager.writeDocument(
                getContext(),
                mtpManager,
                new Identifier(0, 0, 1, "2", MtpDatabaseConstants.DOCUMENT_TYPE_OBJECT),
                TestUtil.OPERATIONS_SUPPORTED);
        final ParcelFileDescriptor.AutoCloseOutputStream outputStream =
                new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
        outputStream.write(HELLO_BYTES, 0, HELLO_BYTES.length);
        outputStream.close();
        mExecutor.shutdown();
        assertTrue(mExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS));

        // Check if the placeholder file is removed.
        try {
            mtpManager.getObjectInfo(0, 1);
            fail();  // The placeholder file has not been deleted.
        } catch (IOException e) {
            // Expected error, as the file is gone.
        }

        // Confirm that the target file is created.
        final MtpObjectInfo targetDocument = mtpManager.getObjectInfo(
                0, TestMtpManager.CREATED_DOCUMENT_HANDLE);
        assertTrue(targetDocument != null);

        // Confirm the object handle is updated.
        try (final Cursor cursor = mDatabase.queryDocument(
                "2", new String[] { MtpDatabaseConstants.COLUMN_OBJECT_HANDLE })) {
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(TestMtpManager.CREATED_DOCUMENT_HANDLE, cursor.getInt(0));
        }

        // Verify uploaded bytes.
        final byte[] uploadedBytes = mtpManager.getImportFileBytes(
                0, TestMtpManager.CREATED_DOCUMENT_HANDLE);
        assertEquals(HELLO_BYTES.length, uploadedBytes.length);
        for (int i = 0; i < HELLO_BYTES.length; i++) {
            assertEquals(HELLO_BYTES[i], uploadedBytes[i]);
        }
    }

    public void testReadThumbnail_basic() throws Exception {
        mtpManager.setThumbnail(0, 1, HELLO_BYTES);
        final ParcelFileDescriptor descriptor = mPipeManager.readThumbnail(
                mtpManager,
                new Identifier(0, 0, 1, null, MtpDatabaseConstants.DOCUMENT_TYPE_OBJECT));
        assertDescriptor(descriptor, HELLO_BYTES);
    }

    public void testReadThumbnail_error() throws Exception {
        final ParcelFileDescriptor descriptor = mPipeManager.readThumbnail(
                mtpManager,
                new Identifier(0, 0, 1, null, MtpDatabaseConstants.DOCUMENT_TYPE_OBJECT));
        assertDescriptorError(descriptor);
    }

    private void assertDescriptor(ParcelFileDescriptor descriptor, byte[] expectedBytes)
            throws IOException, InterruptedException {
        mExecutor.shutdown();
        assertTrue(mExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS));
        try (final ParcelFileDescriptor.AutoCloseInputStream stream =
                new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] results = new byte[100];
            assertEquals(expectedBytes.length, stream.read(results));
            for (int i = 0; i < expectedBytes.length; i++) {
                assertEquals(expectedBytes[i], results[i]);
            }
        }
    }

    private void assertDescriptorError(ParcelFileDescriptor descriptor)
            throws InterruptedException {
        mExecutor.shutdown();
        assertTrue(mExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS));
        try {
            descriptor.checkError();
            fail();
        } catch (Throwable error) {
            assertTrue(error instanceof IOException);
        }
    }
}
