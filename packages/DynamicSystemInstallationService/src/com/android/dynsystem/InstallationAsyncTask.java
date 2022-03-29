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

package com.android.dynsystem;

import android.content.Context;
import android.gsi.AvbPublicKey;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.MemoryFile;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.os.image.DynamicSystemManager;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Log;
import android.util.Range;
import android.webkit.URLUtil;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

class InstallationAsyncTask extends AsyncTask<String, Long, Throwable> {

    private static final String TAG = "InstallationAsyncTask";

    private static final int MIN_SHARED_MEMORY_SIZE = 8 << 10; // 8KiB
    private static final int MAX_SHARED_MEMORY_SIZE = 1024 << 10; // 1MiB
    private static final int DEFAULT_SHARED_MEMORY_SIZE = 64 << 10; // 64KiB
    private static final String SHARED_MEMORY_SIZE_PROP =
            "dynamic_system.data_transfer.shared_memory.size";

    private static final long MIN_PROGRESS_TO_PUBLISH = 1 << 27;

    private static final List<String> UNSUPPORTED_PARTITIONS =
            Arrays.asList(
                    "vbmeta", "boot", "userdata", "dtbo", "super_empty", "system_other", "scratch");

    private class UnsupportedUrlException extends Exception {
        private UnsupportedUrlException(String message) {
            super(message);
        }
    }

    private class UnsupportedFormatException extends Exception {
        private UnsupportedFormatException(String message) {
            super(message);
        }
    }

    static class ImageValidationException extends Exception {
        ImageValidationException(String message) {
            super(message);
        }

        ImageValidationException(Throwable cause) {
            super(cause);
        }
    }

    static class RevocationListFetchException extends ImageValidationException {
        RevocationListFetchException(Throwable cause) {
            super(cause);
        }
    }

    static class KeyRevokedException extends ImageValidationException {
        KeyRevokedException(String message) {
            super(message);
        }
    }

    static class PublicKeyException extends ImageValidationException {
        PublicKeyException(String message) {
            super(message);
        }
    }

    /** UNSET means the installation is not completed */
    static final int RESULT_UNSET = 0;
    static final int RESULT_OK = 1;
    static final int RESULT_CANCELLED = 2;
    static final int RESULT_ERROR_IO = 3;
    static final int RESULT_ERROR_UNSUPPORTED_URL = 4;
    static final int RESULT_ERROR_UNSUPPORTED_FORMAT = 5;
    static final int RESULT_ERROR_EXCEPTION = 6;

    static class Progress {
        public final String partitionName;
        public final long installedBytes;
        public final long totalBytes;
        public final int partitionNumber;
        public final int totalPartitionNumber;
        public final int totalProgressPercentage;

        Progress(
                String partitionName,
                long installedBytes,
                long totalBytes,
                int partitionNumber,
                int totalPartitionNumber,
                int totalProgressPercentage) {
            this.partitionName = partitionName;
            this.installedBytes = installedBytes;
            this.totalBytes = totalBytes;
            this.partitionNumber = partitionNumber;
            this.totalPartitionNumber = totalPartitionNumber;
            this.totalProgressPercentage = totalProgressPercentage;
        }
    }

    interface ProgressListener {
        void onProgressUpdate(Progress progress);

        void onResult(int resultCode, Throwable detail);
    }

    private final int mSharedMemorySize;
    private final String mUrl;
    private final String mDsuSlot;
    private final String mPublicKey;
    private final long mSystemSize;
    private final long mUserdataSize;
    private final Context mContext;
    private final DynamicSystemManager mDynSystem;
    private final ProgressListener mListener;
    private final boolean mIsNetworkUrl;
    private final boolean mIsDeviceBootloaderUnlocked;
    private final boolean mWantScratchPartition;
    private DynamicSystemManager.Session mInstallationSession;
    private KeyRevocationList mKeyRevocationList;

    private boolean mIsZip;
    private boolean mIsCompleted;
    private InputStream mStream;
    private ZipFile mZipFile;

    private static final double PROGRESS_READONLY_PARTITION_WEIGHT = 0.8;
    private static final double PROGRESS_WRITABLE_PARTITION_WEIGHT = 0.2;

    private String mProgressPartitionName;
    private long mProgressTotalBytes;
    private int mProgressPartitionNumber;
    private boolean mProgressPartitionIsReadonly;
    private int mProgressCompletedReadonlyPartitions;
    private int mProgressCompletedWritablePartitions;
    private int mTotalReadonlyPartitions;
    private int mTotalWritablePartitions;
    private int mTotalPartitionNumber;

    InstallationAsyncTask(
            String url,
            String dsuSlot,
            String publicKey,
            long systemSize,
            long userdataSize,
            Context context,
            DynamicSystemManager dynSystem,
            ProgressListener listener) {
        mSharedMemorySize =
                Range.create(MIN_SHARED_MEMORY_SIZE, MAX_SHARED_MEMORY_SIZE)
                        .clamp(
                                SystemProperties.getInt(
                                        SHARED_MEMORY_SIZE_PROP, DEFAULT_SHARED_MEMORY_SIZE));
        mUrl = url;
        mDsuSlot = dsuSlot;
        mPublicKey = publicKey;
        mSystemSize = systemSize;
        mUserdataSize = userdataSize;
        mContext = context;
        mDynSystem = dynSystem;
        mListener = listener;
        mIsNetworkUrl = URLUtil.isNetworkUrl(mUrl);
        PersistentDataBlockManager pdbManager =
                (PersistentDataBlockManager)
                        mContext.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);
        mIsDeviceBootloaderUnlocked =
                (pdbManager != null)
                        && (pdbManager.getFlashLockState()
                                == PersistentDataBlockManager.FLASH_LOCK_UNLOCKED);
        mWantScratchPartition = Build.IS_DEBUGGABLE;
    }

    @Override
    protected Throwable doInBackground(String... voids) {
        Log.d(TAG, "Start doInBackground(), URL: " + mUrl);

        try {
            // call DynamicSystemManager to cleanup stuff
            mDynSystem.remove();

            verifyAndPrepare();

            mDynSystem.startInstallation(mDsuSlot);

            installUserdata();
            if (isCancelled()) {
                mDynSystem.remove();
                return null;
            }
            if (mUrl == null) {
                mDynSystem.finishInstallation();
                return null;
            }
            installImages();
            if (isCancelled()) {
                mDynSystem.remove();
                return null;
            }

            if (mWantScratchPartition) {
                // If host is debuggable, then install a scratch partition so that we can do
                // adb remount in the guest system.
                try {
                    installScratch();
                } catch (IOException e) {
                    // Failing to install overlayFS scratch shouldn't be fatal.
                    // Just ignore the error and skip installing the scratch partition.
                    Log.w(TAG, e.toString(), e);
                }
                if (isCancelled()) {
                    mDynSystem.remove();
                    return null;
                }
            }

            mDynSystem.finishInstallation();
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
            mDynSystem.remove();
            return e;
        } finally {
            close();
        }

        return null;
    }

    @Override
    protected void onPostExecute(Throwable detail) {
        int result = RESULT_UNSET;

        if (detail == null) {
            result = RESULT_OK;
            mIsCompleted = true;
        } else if (detail instanceof IOException) {
            result = RESULT_ERROR_IO;
        } else if (detail instanceof UnsupportedUrlException) {
            result = RESULT_ERROR_UNSUPPORTED_URL;
        } else if (detail instanceof UnsupportedFormatException) {
            result = RESULT_ERROR_UNSUPPORTED_FORMAT;
        } else {
            result = RESULT_ERROR_EXCEPTION;
        }

        Log.d(TAG, "onPostExecute(), URL: " + mUrl + ", result: " + result);

        mListener.onResult(result, detail);
    }

    @Override
    protected void onCancelled() {
        Log.d(TAG, "onCancelled(), URL: " + mUrl);

        if (mDynSystem.abort()) {
            Log.d(TAG, "Installation aborted");
        } else {
            Log.w(TAG, "DynamicSystemManager.abort() returned false");
        }

        mListener.onResult(RESULT_CANCELLED, null);
    }

    @Override
    protected void onProgressUpdate(Long... progress) {
        final long installedBytes = progress[0];
        int totalProgressPercentage = 0;
        if (mTotalPartitionNumber > 0) {
            final double readonlyPartitionWeight =
                    mTotalReadonlyPartitions > 0
                            ? PROGRESS_READONLY_PARTITION_WEIGHT / mTotalReadonlyPartitions
                            : 0;
            final double writablePartitionWeight =
                    mTotalWritablePartitions > 0
                            ? PROGRESS_WRITABLE_PARTITION_WEIGHT / mTotalWritablePartitions
                            : 0;
            double totalProgress = 0.0;
            if (mProgressTotalBytes > 0) {
                totalProgress +=
                        (mProgressPartitionIsReadonly
                                        ? readonlyPartitionWeight
                                        : writablePartitionWeight)
                                * installedBytes
                                / mProgressTotalBytes;
            }
            totalProgress += readonlyPartitionWeight * mProgressCompletedReadonlyPartitions;
            totalProgress += writablePartitionWeight * mProgressCompletedWritablePartitions;
            totalProgressPercentage = (int) (totalProgress * 100);
        }
        mListener.onProgressUpdate(
                new Progress(
                        mProgressPartitionName,
                        installedBytes,
                        mProgressTotalBytes,
                        mProgressPartitionNumber,
                        mTotalPartitionNumber,
                        totalProgressPercentage));
    }

    private void initPartitionProgress(String partitionName, long totalBytes, boolean readonly) {
        if (mProgressPartitionNumber > 0) {
            // Assume previous partition completed successfully.
            if (mProgressPartitionIsReadonly) {
                ++mProgressCompletedReadonlyPartitions;
            } else {
                ++mProgressCompletedWritablePartitions;
            }
        }
        mProgressPartitionName = partitionName;
        mProgressTotalBytes = totalBytes;
        mProgressPartitionIsReadonly = readonly;
        ++mProgressPartitionNumber;
    }

    private void verifyAndPrepare() throws Exception {
        if (mUrl == null) {
            return;
        }
        String extension = mUrl.substring(mUrl.lastIndexOf('.') + 1);

        if ("gz".equals(extension) || "gzip".equals(extension)) {
            mIsZip = false;
        } else if ("zip".equals(extension)) {
            mIsZip = true;
        } else {
            throw new UnsupportedFormatException(
                String.format(Locale.US, "Unsupported file format: %s", mUrl));
        }

        if (mIsNetworkUrl) {
            mStream = new URL(mUrl).openStream();
        } else if (URLUtil.isFileUrl(mUrl)) {
            if (mIsZip) {
                mZipFile = new ZipFile(new File(new URL(mUrl).toURI()));
            } else {
                mStream = new URL(mUrl).openStream();
            }
        } else if (URLUtil.isContentUrl(mUrl)) {
            mStream = mContext.getContentResolver().openInputStream(Uri.parse(mUrl));
        } else {
            throw new UnsupportedUrlException(
                    String.format(Locale.US, "Unsupported URL: %s", mUrl));
        }

        boolean hasTotalPartitionNumber = false;
        if (mIsZip) {
            if (mZipFile != null) {
                // {*.img in zip} + {userdata}
                hasTotalPartitionNumber = true;
                mTotalReadonlyPartitions = calculateNumberOfImagesInLocalZip(mZipFile);
                mTotalWritablePartitions = 1;
            } else {
                // TODO: Come up with a way to retrieve the number of total partitions from
                // network URL.
            }
        } else {
            // gzip has exactly two partitions, {system, userdata}
            hasTotalPartitionNumber = true;
            mTotalReadonlyPartitions = 1;
            mTotalWritablePartitions = 1;
        }

        if (hasTotalPartitionNumber) {
            if (mWantScratchPartition) {
                // {scratch}
                ++mTotalWritablePartitions;
            }
            mTotalPartitionNumber = mTotalReadonlyPartitions + mTotalWritablePartitions;
        }

        try {
            String listUrl = mContext.getString(R.string.key_revocation_list_url);
            mKeyRevocationList = KeyRevocationList.fromUrl(new URL(listUrl));
        } catch (IOException | JSONException e) {
            mKeyRevocationList = new KeyRevocationList();
            imageValidationThrowOrWarning(new RevocationListFetchException(e));
        }
        if (mKeyRevocationList.isRevoked(mPublicKey)) {
            imageValidationThrowOrWarning(new KeyRevokedException(mPublicKey));
        }
    }

    private void imageValidationThrowOrWarning(ImageValidationException e)
            throws ImageValidationException {
        if (mIsDeviceBootloaderUnlocked || !mIsNetworkUrl) {
            // If device is OEM unlocked or DSU is being installed from a local file URI,
            // then be permissive.
            Log.w(TAG, e.toString());
        } else {
            throw e;
        }
    }

    private void installWritablePartition(final String partitionName, final long partitionSize)
            throws IOException {
        Log.d(TAG, "Creating writable partition: " + partitionName + ", size: " + partitionSize);

        Thread thread = new Thread() {
            @Override
            public void run() {
                mInstallationSession =
                        mDynSystem.createPartition(
                                partitionName, partitionSize, /* readOnly= */ false);
            }
        };

        initPartitionProgress(partitionName, partitionSize, /* readonly = */ false);
        publishProgress(/* installedSize = */ 0L);

        long prevInstalledSize = 0;
        thread.start();
        while (thread.isAlive()) {
            if (isCancelled()) {
                return;
            }

            final long installedSize = mDynSystem.getInstallationProgress().bytes_processed;

            if (installedSize > prevInstalledSize + MIN_PROGRESS_TO_PUBLISH) {
                publishProgress(installedSize);
                prevInstalledSize = installedSize;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore the error.
            }
        }

        if (prevInstalledSize != partitionSize) {
            publishProgress(partitionSize);
        }

        if (mInstallationSession == null) {
            throw new IOException(
                    "Failed to start installation with requested size: " + partitionSize);
        }

        // Reset installation session and verify that installation completes successfully.
        mInstallationSession = null;
        if (!mDynSystem.closePartition()) {
            throw new IOException("Failed to complete partition installation: " + partitionName);
        }
    }

    private void installScratch() throws IOException {
        installWritablePartition("scratch", mDynSystem.suggestScratchSize());
    }

    private void installUserdata() throws IOException {
        installWritablePartition("userdata", mUserdataSize);
    }

    private void installImages() throws IOException, ImageValidationException {
        if (mStream != null) {
            if (mIsZip) {
                installStreamingZipUpdate();
            } else {
                installStreamingGzUpdate();
            }
        } else {
            installLocalZipUpdate();
        }
    }

    private void installStreamingGzUpdate() throws IOException, ImageValidationException {
        Log.d(TAG, "To install a streaming GZ update");
        installImage("system", mSystemSize, new GZIPInputStream(mStream));
    }

    private boolean shouldInstallEntry(String name) {
        if (!name.endsWith(".img")) {
            return false;
        }
        String partitionName = name.substring(0, name.length() - 4);
        if (UNSUPPORTED_PARTITIONS.contains(partitionName)) {
            return false;
        }
        return true;
    }

    private int calculateNumberOfImagesInLocalZip(ZipFile zipFile) {
        int total = 0;
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (shouldInstallEntry(entry.getName())) {
                ++total;
            }
        }
        return total;
    }

    private void installStreamingZipUpdate() throws IOException, ImageValidationException {
        Log.d(TAG, "To install a streaming ZIP update");

        ZipInputStream zis = new ZipInputStream(mStream);
        ZipEntry entry = null;

        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if (shouldInstallEntry(name)) {
                installImageFromAnEntry(entry, zis);
            } else {
                Log.d(TAG, name + " installation is not supported, skip it.");
            }

            if (isCancelled()) {
                break;
            }
        }
    }

    private void installLocalZipUpdate() throws IOException, ImageValidationException {
        Log.d(TAG, "To install a local ZIP update");

        Enumeration<? extends ZipEntry> entries = mZipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (shouldInstallEntry(name)) {
                installImageFromAnEntry(entry, mZipFile.getInputStream(entry));
            } else {
                Log.d(TAG, name + " installation is not supported, skip it.");
            }

            if (isCancelled()) {
                break;
            }
        }
    }

    private void installImageFromAnEntry(ZipEntry entry, InputStream is)
            throws IOException, ImageValidationException {
        String name = entry.getName();

        Log.d(TAG, "ZipEntry: " + name);

        String partitionName = name.substring(0, name.length() - 4);
        long uncompressedSize = entry.getSize();

        installImage(partitionName, uncompressedSize, is);
    }

    private void installImage(String partitionName, long uncompressedSize, InputStream is)
            throws IOException, ImageValidationException {

        SparseInputStream sis = new SparseInputStream(new BufferedInputStream(is));

        long unsparseSize = sis.getUnsparseSize();

        final long partitionSize;

        if (unsparseSize != -1) {
            partitionSize = unsparseSize;
            Log.d(TAG, partitionName + " is sparse, raw size = " + unsparseSize);
        } else if (uncompressedSize != -1) {
            partitionSize = uncompressedSize;
            Log.d(TAG, partitionName + " is already unsparse, raw size = " + uncompressedSize);
        } else {
            throw new IOException("Cannot get raw size for " + partitionName);
        }

        Thread thread = new Thread(() -> {
            mInstallationSession =
                    mDynSystem.createPartition(partitionName, partitionSize, true);
        });

        Log.d(TAG, "Start creating partition: " + partitionName);
        thread.start();

        while (thread.isAlive()) {
            if (isCancelled()) {
                return;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore the error.
            }
        }

        if (mInstallationSession == null) {
            throw new IOException(
                    "Failed to start installation with requested size: " + partitionSize);
        }

        Log.d(TAG, "Start installing: " + partitionName);

        MemoryFile memoryFile = new MemoryFile("dsu_" + partitionName, mSharedMemorySize);
        ParcelFileDescriptor pfd = new ParcelFileDescriptor(memoryFile.getFileDescriptor());

        mInstallationSession.setAshmem(pfd, memoryFile.length());

        initPartitionProgress(partitionName, partitionSize, /* readonly = */ true);
        publishProgress(/* installedSize = */ 0L);

        long prevInstalledSize = 0;
        long installedSize = 0;
        byte[] bytes = new byte[memoryFile.length()];
        int numBytesRead;

        while ((numBytesRead = sis.read(bytes, 0, bytes.length)) != -1) {
            if (isCancelled()) {
                return;
            }

            memoryFile.writeBytes(bytes, 0, 0, numBytesRead);

            if (!mInstallationSession.submitFromAshmem(numBytesRead)) {
                throw new IOException("Failed write() to DynamicSystem");
            }

            installedSize += numBytesRead;

            if (installedSize > prevInstalledSize + MIN_PROGRESS_TO_PUBLISH) {
                publishProgress(installedSize);
                prevInstalledSize = installedSize;
            }
        }

        if (prevInstalledSize != partitionSize) {
            publishProgress(partitionSize);
        }

        AvbPublicKey avbPublicKey = new AvbPublicKey();
        if (!mInstallationSession.getAvbPublicKey(avbPublicKey)) {
            imageValidationThrowOrWarning(new PublicKeyException("getAvbPublicKey() failed"));
        } else {
            String publicKey = toHexString(avbPublicKey.sha1);
            if (mKeyRevocationList.isRevoked(publicKey)) {
                imageValidationThrowOrWarning(new KeyRevokedException(publicKey));
            }
        }

        // Reset installation session and verify that installation completes successfully.
        mInstallationSession = null;
        if (!mDynSystem.closePartition()) {
            throw new IOException("Failed to complete partition installation: " + partitionName);
        }
    }

    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void close() {
        try {
            if (mStream != null) {
                mStream.close();
                mStream = null;
            }
            if (mZipFile != null) {
                mZipFile.close();
                mZipFile = null;
            }
        } catch (IOException e) {
            // ignore
        }
    }

    boolean isCompleted() {
        return mIsCompleted;
    }

    boolean commit() {
        return mDynSystem.setEnable(true, true);
    }
}
