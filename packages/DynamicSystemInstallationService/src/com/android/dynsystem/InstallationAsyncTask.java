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
import android.os.image.DynamicSystemManager;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Log;
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

class InstallationAsyncTask extends AsyncTask<String, InstallationAsyncTask.Progress, Throwable> {

    private static final String TAG = "InstallationAsyncTask";

    private static final int READ_BUFFER_SIZE = 1 << 13;
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
        public final long partitionSize;
        public final int numInstalledPartitions;
        public long installedSize;

        Progress(String partitionName, long partitionSize, int numInstalledPartitions) {
            this.partitionName = partitionName;
            this.partitionSize = partitionSize;
            this.numInstalledPartitions = numInstalledPartitions;
        }
    }

    interface ProgressListener {
        void onProgressUpdate(Progress progress);

        void onResult(int resultCode, Throwable detail);
    }

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
    private DynamicSystemManager.Session mInstallationSession;
    private KeyRevocationList mKeyRevocationList;

    private boolean mIsZip;
    private boolean mIsCompleted;

    private int mNumInstalledPartitions;

    private InputStream mStream;
    private ZipFile mZipFile;

    InstallationAsyncTask(
            String url,
            String dsuSlot,
            String publicKey,
            long systemSize,
            long userdataSize,
            Context context,
            DynamicSystemManager dynSystem,
            ProgressListener listener) {
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

            if (Build.IS_DEBUGGABLE) {
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
    protected void onProgressUpdate(Progress... values) {
        Progress progress = values[0];
        mListener.onProgressUpdate(progress);
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

        thread.start();
        Progress progress = new Progress(partitionName, partitionSize, mNumInstalledPartitions++);

        while (thread.isAlive()) {
            if (isCancelled()) {
                return;
            }

            final long installedSize = mDynSystem.getInstallationProgress().bytes_processed;

            if (installedSize > progress.installedSize + MIN_PROGRESS_TO_PUBLISH) {
                progress.installedSize = installedSize;
                publishProgress(progress);
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

    private void installStreamingZipUpdate() throws IOException, ImageValidationException {
        Log.d(TAG, "To install a streaming ZIP update");

        ZipInputStream zis = new ZipInputStream(mStream);
        ZipEntry zipEntry = null;

        while ((zipEntry = zis.getNextEntry()) != null) {
            installImageFromAnEntry(zipEntry, zis);

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
            installImageFromAnEntry(entry, mZipFile.getInputStream(entry));

            if (isCancelled()) {
                break;
            }
        }
    }

    private boolean installImageFromAnEntry(ZipEntry entry, InputStream is)
            throws IOException, ImageValidationException {
        String name = entry.getName();

        Log.d(TAG, "ZipEntry: " + name);

        if (!name.endsWith(".img")) {
            return false;
        }

        String partitionName = name.substring(0, name.length() - 4);

        if (UNSUPPORTED_PARTITIONS.contains(partitionName)) {
            Log.d(TAG, name + " installation is not supported, skip it.");
            return false;
        }

        long uncompressedSize = entry.getSize();

        installImage(partitionName, uncompressedSize, is);

        return true;
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

        MemoryFile memoryFile = new MemoryFile("dsu_" + partitionName, READ_BUFFER_SIZE);
        ParcelFileDescriptor pfd = new ParcelFileDescriptor(memoryFile.getFileDescriptor());

        mInstallationSession.setAshmem(pfd, READ_BUFFER_SIZE);

        Progress progress = new Progress(partitionName, partitionSize, mNumInstalledPartitions++);

        long installedSize = 0;
        byte[] bytes = new byte[READ_BUFFER_SIZE];
        int numBytesRead;

        while ((numBytesRead = sis.read(bytes, 0, READ_BUFFER_SIZE)) != -1) {
            if (isCancelled()) {
                return;
            }

            memoryFile.writeBytes(bytes, 0, 0, numBytesRead);

            if (!mInstallationSession.submitFromAshmem(numBytesRead)) {
                throw new IOException("Failed write() to DynamicSystem");
            }

            installedSize += numBytesRead;

            if (installedSize > progress.installedSize + MIN_PROGRESS_TO_PUBLISH) {
                progress.installedSize = installedSize;
                publishProgress(progress);
            }
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
