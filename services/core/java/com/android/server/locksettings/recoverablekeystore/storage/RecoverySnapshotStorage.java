/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore.storage;

import android.annotation.Nullable;
import android.os.Environment;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.locksettings.recoverablekeystore.serialization
        .KeyChainSnapshotDeserializer;
import com.android.server.locksettings.recoverablekeystore.serialization
        .KeyChainSnapshotParserException;
import com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.cert.CertificateEncodingException;
import java.util.Locale;

/**
 * Storage for recovery snapshots. Stores snapshots in memory, backed by disk storage.
 *
 * <p>Recovery snapshots are generated after a successful screen unlock. They are only generated if
 * the recoverable keystore has been mutated since the previous snapshot. This class stores only the
 * latest snapshot for each recovery agent.
 *
 * <p>This class is thread-safe. It is used both on the service thread and the
 * {@link com.android.server.locksettings.recoverablekeystore.KeySyncTask} thread.
 */
public class RecoverySnapshotStorage {

    private static final String TAG = "RecoverySnapshotStorage";

    private static final String ROOT_PATH = "system";
    private static final String STORAGE_PATH = "recoverablekeystore/snapshots/";

    @GuardedBy("this")
    private final SparseArray<KeyChainSnapshot> mSnapshotByUid = new SparseArray<>();

    private final File rootDirectory;

    /**
     * A new instance, storing snapshots in /data/system/recoverablekeystore/snapshots.
     *
     * <p>NOTE: calling this multiple times DOES NOT return the same instance, so will NOT be backed
     * by the same in-memory store.
     */
    public static RecoverySnapshotStorage newInstance() {
        return new RecoverySnapshotStorage(
                new File(Environment.getDataDirectory(), ROOT_PATH));
    }

    @VisibleForTesting
    public RecoverySnapshotStorage(File rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    /**
     * Sets the latest {@code snapshot} for the recovery agent {@code uid}.
     */
    public synchronized void put(int uid, KeyChainSnapshot snapshot) {
        mSnapshotByUid.put(uid, snapshot);

        try {
            writeToDisk(uid, snapshot);
        } catch (IOException | CertificateEncodingException e) {
            Log.e(TAG,
                    String.format(Locale.US, "Error persisting snapshot for %d to disk", uid),
                    e);
        }
    }

    /**
     * Returns the latest snapshot for the recovery agent {@code uid}, or null if none exists.
     */
    @Nullable
    public synchronized KeyChainSnapshot get(int uid) {
        KeyChainSnapshot snapshot = mSnapshotByUid.get(uid);
        if (snapshot != null) {
            return snapshot;
        }

        try {
            return readFromDisk(uid);
        } catch (IOException | KeyChainSnapshotParserException e) {
            Log.e(TAG, String.format(Locale.US, "Error reading snapshot for %d from disk", uid), e);
            return null;
        }
    }

    /**
     * Removes any (if any) snapshot associated with recovery agent {@code uid}.
     */
    public synchronized void remove(int uid) {
        mSnapshotByUid.remove(uid);
        getSnapshotFile(uid).delete();
    }

    /**
     * Writes the snapshot for recovery agent {@code uid} to disk.
     *
     * @throws IOException if an IO error occurs writing to disk.
     */
    private void writeToDisk(int uid, KeyChainSnapshot snapshot)
            throws IOException, CertificateEncodingException {
        File snapshotFile = getSnapshotFile(uid);

        try (
            FileOutputStream fileOutputStream = new FileOutputStream(snapshotFile)
        ) {
            KeyChainSnapshotSerializer.serialize(snapshot, fileOutputStream);
        } catch (IOException | CertificateEncodingException e) {
            // If we fail to write the latest snapshot, we should delete any older snapshot that
            // happens to be around. Otherwise snapshot syncs might end up going 'back in time'.
            snapshotFile.delete();
            throw e;
        }
    }

    /**
     * Reads the last snapshot for recovery agent {@code uid} from disk.
     *
     * @return The snapshot, or null if none existed.
     * @throws IOException if an IO error occurs reading from disk.
     */
    @Nullable
    private KeyChainSnapshot readFromDisk(int uid)
            throws IOException, KeyChainSnapshotParserException {
        File snapshotFile = getSnapshotFile(uid);

        try (
            FileInputStream fileInputStream = new FileInputStream(snapshotFile)
        ) {
            return KeyChainSnapshotDeserializer.deserialize(fileInputStream);
        } catch (IOException | KeyChainSnapshotParserException e) {
            // If we fail to read the latest snapshot, we should delete it in case it is in some way
            // corrupted. We can regenerate snapshots anyway.
            snapshotFile.delete();
            throw e;
        }
    }

    private File getSnapshotFile(int uid) {
        File folder = getStorageFolder();
        String fileName = getSnapshotFileName(uid);
        return new File(folder, fileName);
    }

    private String getSnapshotFileName(int uid) {
        return String.format(Locale.US, "%d.xml", uid);
    }

    private File getStorageFolder() {
        File folder = new File(rootDirectory, STORAGE_PATH);
        folder.mkdirs();
        return folder;
    }
}
