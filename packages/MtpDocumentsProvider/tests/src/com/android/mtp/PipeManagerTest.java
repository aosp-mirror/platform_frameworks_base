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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SmallTest
public class PipeManagerTest extends AndroidTestCase {
    public void testReadDocument_basic() throws Exception {
        final TestMtpManager mtpManager = new TestMtpManager(getContext());
        final byte[] expectedBytes = new byte[] { 'h', 'e', 'l', 'l', 'o' };
        mtpManager.setObjectBytes(0, 1, 5, expectedBytes);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final PipeManager pipeManager = new PipeManager(executor);
        final ParcelFileDescriptor descriptor = pipeManager.readDocument(
                mtpManager, new Identifier(0, 0, 1), 5);
        try (final ParcelFileDescriptor.AutoCloseInputStream stream =
                new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            final byte[] results = new byte[100];
            assertEquals(5, stream.read(results));
            for (int i = 0; i < 5; i++) {
                assertEquals(expectedBytes[i], results[i]);
            }
        }
    }

    public void testReadDocument_error() throws Exception {
        final TestMtpManager mtpManager = new TestMtpManager(getContext());
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final PipeManager pipeManager = new PipeManager(executor);
        final ParcelFileDescriptor descriptor =
                pipeManager.readDocument(mtpManager, new Identifier(0, 0, 1), 5);
        executor.awaitTermination(1000, TimeUnit.MILLISECONDS);
        try {
            descriptor.checkError();
            fail();
        } catch (Throwable error) {
            assertTrue(error instanceof IOException);
        }
    }
}
