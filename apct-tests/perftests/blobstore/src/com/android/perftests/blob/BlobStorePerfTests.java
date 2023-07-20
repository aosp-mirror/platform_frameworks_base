/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.perftests.blob;

import static com.android.utils.blob.Utils.acquireLease;

import android.app.blob.BlobHandle;
import android.app.blob.BlobStoreManager;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.PerfManualStatusReporter;
import android.perftests.utils.TraceMarkParser;
import android.perftests.utils.TraceMarkParser.TraceMarkSlice;
import android.support.test.uiautomator.UiDevice;
import android.util.DataUnit;

import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.utils.blob.FakeBlobData;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@LargeTest
@RunWith(Parameterized.class)
public class BlobStorePerfTests {
    // From frameworks/native/cmds/atrace/atrace.cpp
    private static final String ATRACE_CATEGORY_SYSTEM_SERVER = "ss";
    // From f/b/apex/blobstore/service/java/com/android/server/blob/BlobStoreSession.java
    private static final String ATRACE_COMPUTE_DIGEST_PREFIX = "computeBlobDigest-";

    public static final int BUFFER_SIZE_BYTES = 16 * 1024;

    private Context mContext;
    private BlobStoreManager mBlobStoreManager;
    private AtraceUtils mAtraceUtils;
    private ManualBenchmarkState mState;

    @Rule
    public PerfManualStatusReporter mPerfManualStatusReporter = new PerfManualStatusReporter();

    @Parameterized.Parameter(0)
    public int fileSizeInMb;

    @Parameterized.Parameters(name = "{0}MB")
    public static Collection<Object[]> getParameters() {
        return Arrays.asList(new Object[][] {
                { 25 },
                { 50 },
                { 100 },
                { 200 },
        });
    }

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mBlobStoreManager = (BlobStoreManager) mContext.getSystemService(
                Context.BLOB_STORE_SERVICE);
        mAtraceUtils = AtraceUtils.getInstance(InstrumentationRegistry.getInstrumentation());
        mState = mPerfManualStatusReporter.getBenchmarkState();
    }

    @After
    public void tearDown() throws Exception {
        mContext.getFilesDir().delete();
        mBlobStoreManager.releaseAllLeases();
        runShellCommand("cmd blob_store idle-maintenance");
    }

    @Test
    public void testComputeDigest() throws Exception {
        mAtraceUtils.startTrace(ATRACE_CATEGORY_SYSTEM_SERVER);
        try {
            final List<Long> durations = new ArrayList<>();
            final FakeBlobData blobData = prepareDataBlob(fileSizeInMb);
            final TraceMarkParser parser = new TraceMarkParser(
                    line -> line.name.startsWith(ATRACE_COMPUTE_DIGEST_PREFIX));
            while (mState.keepRunning(durations)) {
                commitBlob(blobData);

                durations.clear();
                collectDigestDurationsFromTrace(parser, durations);

                deleteBlob(blobData.getBlobHandle());
            }
        } finally {
            mAtraceUtils.stopTrace();
        }
    }

    @Test
    public void testDirectReads() throws Exception {
        final File file = new File(mContext.getDataDir(), "test_read_file");
        final long sizeBytes = DataUnit.MEBIBYTES.toBytes(fileSizeInMb);
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            writeData(outputStream, sizeBytes);
        }

        long durationNs = 0;
        while (mState.keepRunning(durationNs)) {
            dropCache();
            try (FileInputStream inputStream = new FileInputStream(file)) {
                final long startTimeNs = System.nanoTime();
                readData(inputStream, sizeBytes);
                durationNs = System.nanoTime() - startTimeNs;
            }
        }
    }

    @Test
    public void testBlobStoreReads() throws Exception {
        final FakeBlobData blobData = prepareDataBlob(fileSizeInMb);
        commitBlob(blobData);
        acquireLease(mContext, blobData.getBlobHandle(), "Test Desc");
        final long sizeBytes = DataUnit.MEBIBYTES.toBytes(fileSizeInMb);

        long durationNs = 0;
        while (mState.keepRunning(durationNs)) {
            dropCache();
            try (FileInputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(
                    mBlobStoreManager.openBlob(blobData.getBlobHandle()))) {
                final long startTimeNs = System.nanoTime();
                readData(inputStream, sizeBytes);
                durationNs = System.nanoTime() - startTimeNs;
            }
        }

        deleteBlob(blobData.getBlobHandle());
    }

    @Test
    public void testDirectWrites() throws Exception {
        final File file = new File(mContext.getDataDir(), "test_write_file");
        final long sizeBytes = DataUnit.MEBIBYTES.toBytes(fileSizeInMb);

        long durationNs = 0;
        while (mState.keepRunning(durationNs)) {
            file.delete();
            dropCache();
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                final long startTimeNs = System.nanoTime();
                writeData(outputStream, sizeBytes);
                durationNs = System.nanoTime() - startTimeNs;
            }
        }
    }

    @Test
    public void testBlobStoreWrites() throws Exception {
        final FakeBlobData blobData = prepareDataBlob(fileSizeInMb);
        final long sizeBytes = DataUnit.MEBIBYTES.toBytes(fileSizeInMb);

        long durationNs = 0;
        while (mState.keepRunning(durationNs)) {
            final long sessionId = mBlobStoreManager.createSession(blobData.getBlobHandle());
            try (BlobStoreManager.Session session = mBlobStoreManager.openSession(sessionId)) {
                dropCache();
                try (FileOutputStream outputStream = new ParcelFileDescriptor
                        .AutoCloseOutputStream(session.openWrite(0, sizeBytes))) {
                    final long startTimeNs = System.nanoTime();
                    writeData(outputStream, sizeBytes);
                    durationNs = System.nanoTime() - startTimeNs;
                }
            }
            mBlobStoreManager.abandonSession(sessionId);
        }
    }

    private void readData(FileInputStream inputStream, long sizeBytes) throws Exception {
        final byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        long bytesRead = 0;
        while (bytesRead < sizeBytes) {
            bytesRead += inputStream.read(buffer);
        }
    }

    private void writeData(FileOutputStream outputStream, long sizeBytes) throws Exception {
        final byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        long bytesWritten = 0;
        final Random random = new Random(0);
        while (bytesWritten < sizeBytes) {
            random.nextBytes(buffer);
            final int toWrite = (bytesWritten + buffer.length <= sizeBytes)
                    ? buffer.length : (int) (sizeBytes - bytesWritten);
            outputStream.write(buffer, 0, toWrite);
            bytesWritten += toWrite;
        }
    }

    private void dropCache() {
        final MacrobenchmarkScope scope = new MacrobenchmarkScope(mContext.getPackageName(), false);
        scope.dropKernelPageCache();
    }

    private void collectDigestDurationsFromTrace(TraceMarkParser parser, List<Long> durations) {
        mAtraceUtils.performDump(parser, (key, slices) -> {
            for (TraceMarkSlice slice : slices) {
                durations.add(TimeUnit.MICROSECONDS.toNanos(slice.getDurationInMicroseconds()));
            }
        });
    }

    private FakeBlobData prepareDataBlob(int fileSizeInMb) throws Exception {
        final FakeBlobData blobData = new FakeBlobData.Builder(mContext)
                .setFileSize(DataUnit.MEBIBYTES.toBytes(fileSizeInMb))
                .build();
        blobData.prepare();
        return blobData;
    }

    private void commitBlob(FakeBlobData blobData) throws Exception {
        final long sessionId = mBlobStoreManager.createSession(blobData.getBlobHandle());
        try (BlobStoreManager.Session session = mBlobStoreManager.openSession(sessionId)) {
            blobData.writeToSession(session);
            final CompletableFuture<Integer> callback = new CompletableFuture<>();
            session.commit(mContext.getMainExecutor(), callback::complete);
            // Ignore commit callback result.
            callback.get();
        }
    }

    private void deleteBlob(BlobHandle blobHandle) throws Exception {
        runShellCommand(String.format(
                "cmd blob_store delete-blob --algo %s --digest %s --label %s --expiry %d --tag %s",
                blobHandle.algorithm,
                Base64.getEncoder().encodeToString(blobHandle.digest),
                blobHandle.label,
                blobHandle.expiryTimeMillis,
                blobHandle.tag));
    }

    private String runShellCommand(String cmd) {
        try {
            return UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
