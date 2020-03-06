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
package com.android.server.blob;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Environment;
import android.util.Log;
import android.util.Slog;

import java.io.File;
import java.util.concurrent.TimeUnit;

class BlobStoreConfig {
    public static final String TAG = "BlobStore";
    public static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    // Initial version.
    public static final int XML_VERSION_INIT = 1;
    // Added a string variant of lease description.
    public static final int XML_VERSION_ADD_STRING_DESC = 2;
    public static final int XML_VERSION_ADD_DESC_RES_NAME = 3;

    public static final int XML_VERSION_CURRENT = XML_VERSION_ADD_DESC_RES_NAME;

    private static final String ROOT_DIR_NAME = "blobstore";
    private static final String BLOBS_DIR_NAME = "blobs";
    private static final String SESSIONS_INDEX_FILE_NAME = "sessions_index.xml";
    private static final String BLOBS_INDEX_FILE_NAME = "blobs_index.xml";

    /**
     * Job Id for idle maintenance job ({@link BlobStoreIdleJobService}).
     */
    public static final int IDLE_JOB_ID = 0xB70B1D7; // 191934935L
    /**
     * Max time period (in millis) between each idle maintenance job run.
     */
    public static final long IDLE_JOB_PERIOD_MILLIS = TimeUnit.DAYS.toMillis(1);

    /**
     * Timeout in millis after which sessions with no updates will be deleted.
     */
    public static final long SESSION_EXPIRY_TIMEOUT_MILLIS = TimeUnit.DAYS.toMillis(7);

    @Nullable
    public static File prepareBlobFile(long sessionId) {
        final File blobsDir = prepareBlobsDir();
        return blobsDir == null ? null : getBlobFile(blobsDir, sessionId);
    }

    @NonNull
    public static File getBlobFile(long sessionId) {
        return getBlobFile(getBlobsDir(), sessionId);
    }

    @NonNull
    private static File getBlobFile(File blobsDir, long sessionId) {
        return new File(blobsDir, String.valueOf(sessionId));
    }

    @Nullable
    public static File prepareBlobsDir() {
        final File blobsDir = getBlobsDir(prepareBlobStoreRootDir());
        if (!blobsDir.exists() && !blobsDir.mkdir()) {
            Slog.e(TAG, "Failed to mkdir(): " + blobsDir);
            return null;
        }
        return blobsDir;
    }

    @NonNull
    public static File getBlobsDir() {
        return getBlobsDir(getBlobStoreRootDir());
    }

    @NonNull
    private static File getBlobsDir(File blobsRootDir) {
        return new File(blobsRootDir, BLOBS_DIR_NAME);
    }

    @Nullable
    public static File prepareSessionIndexFile() {
        final File blobStoreRootDir = prepareBlobStoreRootDir();
        if (blobStoreRootDir == null) {
            return null;
        }
        return new File(blobStoreRootDir, SESSIONS_INDEX_FILE_NAME);
    }

    @Nullable
    public static File prepareBlobsIndexFile() {
        final File blobsStoreRootDir = prepareBlobStoreRootDir();
        if (blobsStoreRootDir == null) {
            return null;
        }
        return new File(blobsStoreRootDir, BLOBS_INDEX_FILE_NAME);
    }

    @Nullable
    public static File prepareBlobStoreRootDir() {
        final File blobStoreRootDir = getBlobStoreRootDir();
        if (!blobStoreRootDir.exists() && !blobStoreRootDir.mkdir()) {
            Slog.e(TAG, "Failed to mkdir(): " + blobStoreRootDir);
            return null;
        }
        return blobStoreRootDir;
    }

    @NonNull
    public static File getBlobStoreRootDir() {
        return new File(Environment.getDataSystemDirectory(), ROOT_DIR_NAME);
    }
}
