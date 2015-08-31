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

import android.os.ParcelFileDescriptor;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SmallTest
public class PipeManagerTest extends AndroidTestCase {
    private static final byte[] HELLO_BYTES = new byte[] { 'h', 'e', 'l', 'l', 'o' };

    private TestMtpManager mtpManager;
    private ExecutorService mExecutor;
    private PipeManager mPipeManager;

    @Override
    public void setUp() {
        mtpManager = new TestMtpManager(getContext());
        mExecutor = Executors.newSingleThreadExecutor();
        mPipeManager = new PipeManager(mExecutor);
    }

    public void testReadDocument_basic() throws Exception {
        mtpManager.setImportFileBytes(0, 1, HELLO_BYTES);
        final ParcelFileDescriptor descriptor = mPipeManager.readDocument(
                mtpManager, new Identifier(0, 0, 1));
        assertDescriptor(descriptor, HELLO_BYTES);
    }

    public void testReadDocument_error() throws Exception {
        final ParcelFileDescriptor descriptor =
                mPipeManager.readDocument(mtpManager, new Identifier(0, 0, 1));
        assertDescriptorError(descriptor);
    }

    public void testWriteDocument_basic() throws Exception {
        // Create a placeholder file which should be replaced by a real file later.
        mtpManager.setDocument(0, 1, new MtpDocument(1, 0, "", new Date(), 0, 0, false));

        // Upload testing bytes.
        final ParcelFileDescriptor descriptor = mPipeManager.writeDocument(
                getContext(), mtpManager, new Identifier(0, 0, 1));
        final ParcelFileDescriptor.AutoCloseOutputStream outputStream =
                new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
        outputStream.write(HELLO_BYTES, 0, HELLO_BYTES.length);
        outputStream.close();
        mExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS);

        // Check if the placeholder file is removed.
        try {
            final MtpDocument placeholderDocument = mtpManager.getDocument(0, 1);
            fail();  // The placeholder file has not been deleted.
        } catch (IOException e) {
            // Expected error, as the file is gone.
        }

        // Confirm that the target file is created.
        final MtpDocument targetDocument = mtpManager.getDocument(
                0, TestMtpManager.CREATED_DOCUMENT_HANDLE);
        assertTrue(targetDocument != null);

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
                mtpManager, new Identifier(0, 0, 1));
        assertDescriptor(descriptor, HELLO_BYTES);
    }

    public void testReadThumbnail_error() throws Exception {
        final ParcelFileDescriptor descriptor =
                mPipeManager.readThumbnail(mtpManager, new Identifier(0, 0, 1));
        assertDescriptorError(descriptor);
    }

    private void assertDescriptor(ParcelFileDescriptor descriptor, byte[] expectedBytes)
            throws IOException, InterruptedException {
        mExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS);
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
        mExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS);
        try {
            descriptor.checkError();
            fail();
        } catch (Throwable error) {
            assertTrue(error instanceof IOException);
        }
    }
}
