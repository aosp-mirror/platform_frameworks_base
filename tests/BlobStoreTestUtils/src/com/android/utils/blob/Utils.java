/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.utils.blob;

import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.app.blob.BlobHandle;
import android.app.blob.BlobStoreManager;
import android.app.blob.LeaseInfo;
import android.content.Context;
import android.content.res.Resources;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.test.uiautomator.UiDevice;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Utils {
    public static final String TAG = "BlobStoreTest";

    public static final int BUFFER_SIZE_BYTES = 16 * 1024;

    public static final long KB_IN_BYTES = 1000;
    public static final long MB_IN_BYTES = KB_IN_BYTES * 1000;

    public static void copy(InputStream in, OutputStream out, long lengthBytes)
            throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        long bytesWrittern = 0;
        while (bytesWrittern < lengthBytes) {
            final int toWrite = (bytesWrittern + buffer.length <= lengthBytes)
                    ? buffer.length : (int) (lengthBytes - bytesWrittern);
            in.read(buffer, 0, toWrite);
            out.write(buffer, 0, toWrite);
            bytesWrittern += toWrite;
        }
    }

    public static void writeToSession(BlobStoreManager.Session session, ParcelFileDescriptor input,
            long lengthBytes) throws IOException {
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
            writeToSession(session, in, 0, lengthBytes);
        }
    }

    public static void writeToSession(BlobStoreManager.Session session, FileInputStream in,
            long offsetBytes, long lengthBytes) throws IOException {
        in.getChannel().position(offsetBytes);
        try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(
                session.openWrite(offsetBytes, lengthBytes))) {
            copy(in, out, lengthBytes);
        }
    }

    public static void assertLeasedBlobs(BlobStoreManager blobStoreManager,
            BlobHandle... expectedBlobHandles) throws IOException {
        assertThat(blobStoreManager.getLeasedBlobs()).containsExactly(
                (Object[]) expectedBlobHandles);
    }

    public static void assertNoLeasedBlobs(BlobStoreManager blobStoreManager)
            throws IOException {
        assertThat(blobStoreManager.getLeasedBlobs()).isEmpty();
    }

    public static void acquireLease(Context context,
            BlobHandle blobHandle, CharSequence description) throws IOException {
        final BlobStoreManager blobStoreManager = (BlobStoreManager) context.getSystemService(
                Context.BLOB_STORE_SERVICE);
        blobStoreManager.acquireLease(blobHandle, description);

        final LeaseInfo leaseInfo = blobStoreManager.getLeaseInfo(blobHandle);
        assertLeaseInfo(leaseInfo, context.getPackageName(), 0,
                Resources.ID_NULL, description);
    }

    public static void acquireLease(Context context,
            BlobHandle blobHandle, int descriptionResId) throws IOException {
        final BlobStoreManager blobStoreManager = (BlobStoreManager) context.getSystemService(
                Context.BLOB_STORE_SERVICE);
        blobStoreManager.acquireLease(blobHandle, descriptionResId);

        final LeaseInfo leaseInfo = blobStoreManager.getLeaseInfo(blobHandle);
        assertLeaseInfo(leaseInfo, context.getPackageName(), 0,
                descriptionResId, context.getString(descriptionResId));
    }

    public static void acquireLease(Context context,
            BlobHandle blobHandle, CharSequence description,
            long expiryTimeMs) throws IOException {
        final BlobStoreManager blobStoreManager = (BlobStoreManager) context.getSystemService(
                Context.BLOB_STORE_SERVICE);
        blobStoreManager.acquireLease(blobHandle, description, expiryTimeMs);

        final LeaseInfo leaseInfo = blobStoreManager.getLeaseInfo(blobHandle);
        assertLeaseInfo(leaseInfo, context.getPackageName(), expiryTimeMs,
                Resources.ID_NULL, description);
    }

    public static void acquireLease(Context context,
            BlobHandle blobHandle, int descriptionResId,
            long expiryTimeMs) throws IOException {
        final BlobStoreManager blobStoreManager = (BlobStoreManager) context.getSystemService(
                Context.BLOB_STORE_SERVICE);
        blobStoreManager.acquireLease(blobHandle, descriptionResId, expiryTimeMs);

        final LeaseInfo leaseInfo = blobStoreManager.getLeaseInfo(blobHandle);
        assertLeaseInfo(leaseInfo, context.getPackageName(), expiryTimeMs,
                descriptionResId, context.getString(descriptionResId));
    }

    public static void releaseLease(Context context,
            BlobHandle blobHandle) throws IOException {
        final BlobStoreManager blobStoreManager = (BlobStoreManager) context.getSystemService(
                Context.BLOB_STORE_SERVICE);
        blobStoreManager.releaseLease(blobHandle);
        try {
            assertThat(blobStoreManager.getLeaseInfo(blobHandle)).isNull();
        } catch (SecurityException e) {
            // Expected, ignore
        }
    }

    private static void assertLeaseInfo(LeaseInfo leaseInfo, String packageName,
            long expiryTimeMs, int descriptionResId, CharSequence description) {
        assertThat(leaseInfo.getPackageName()).isEqualTo(packageName);
        assertThat(leaseInfo.getExpiryTimeMillis()).isEqualTo(expiryTimeMs);
        assertThat(leaseInfo.getDescriptionResId()).isEqualTo(descriptionResId);
        assertThat(leaseInfo.getDescription()).isEqualTo(description);
    }

    public static void triggerIdleMaintenance(Instrumentation instrumentation) throws IOException {
        runShellCmd(instrumentation, "cmd blob_store idle-maintenance");
    }

    private static String runShellCmd(Instrumentation instrumentation,
            String cmd) throws IOException {
        final UiDevice uiDevice = UiDevice.getInstance(instrumentation);
        final String result = uiDevice.executeShellCommand(cmd);
        Log.i(TAG, "Output of '" + cmd + "': '" + result + "'");
        return result;
    }
}
