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

package android.app.backup;

import android.os.ParcelFileDescriptor;
import android.util.ArrayMap;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Utility class for writing BackupHelpers whose underlying data is a
 * fixed set of byte-array blobs.  The helper manages diff detection
 * and compression on the wire.
 *
 * @hide
 */
public abstract class BlobBackupHelper extends BackupHelperWithLogger {
    private static final String TAG = "BlobBackupHelper";
    private static final boolean DEBUG = false;

    private final int mCurrentBlobVersion;
    private final String[] mKeys;

    public BlobBackupHelper(int currentBlobVersion, String... keys) {
        mCurrentBlobVersion = currentBlobVersion;
        mKeys = keys;
    }

    // Client interface

    /**
     * Generate and return the byte array containing the backup payload describing
     * the current data state.  During a backup operation this method is called once
     * per key that was supplied to the helper's constructor.
     *
     * @return A byte array containing the data blob that the caller wishes to store,
     *     or {@code null} if the current state is empty or undefined.
     */
    abstract protected byte[] getBackupPayload(String key);

    /**
     * Given a byte array that was restored from backup, do whatever is appropriate
     * to apply that described state in the live system.  This method is called once
     * per key/value payload that was delivered for restore.  Typically data is delivered
     * for restore in lexical order by key, <i>not</i> in the order in which the keys
     * were supplied in the constructor.
     *
     * @param payload The byte array that was passed to {@link #getBackupPayload()}
     *     on the ancestral device.
     */
    abstract protected void applyRestoredPayload(String key, byte[] payload);


    // Internal implementation

    /*
     * State on-disk format:
     * [Int]    : overall blob version number
     * [Int=N] : number of keys represented in the state blob
     * N* :
     *     [String] key
     *     [Long]   blob checksum, calculated after compression
     */
    @SuppressWarnings("resource")
    private ArrayMap<String, Long> readOldState(ParcelFileDescriptor oldStateFd) {
        final ArrayMap<String, Long> state = new ArrayMap<String, Long>();

        FileInputStream fis = new FileInputStream(oldStateFd.getFileDescriptor());
        DataInputStream in = new DataInputStream(fis);

        try {
            int version = in.readInt();
            if (version <= mCurrentBlobVersion) {
                final int numKeys = in.readInt();
                if (DEBUG) {
                    Log.i(TAG, "  " + numKeys + " keys in state record");
                }
                for (int i = 0; i < numKeys; i++) {
                    String key = in.readUTF();
                    long checksum = in.readLong();
                    if (DEBUG) {
                        Log.i(TAG, "  key '" + key + "' checksum is " + checksum);
                    }
                    state.put(key, checksum);
                }
            } else {
                Log.w(TAG, "Prior state from unrecognized version " + version);
            }
        } catch (EOFException e) {
            // Empty file is expected on first backup,  so carry on. If the state
            // is truncated we just treat it the same way.
            if (DEBUG) {
                Log.i(TAG, "Hit EOF reading prior state");
            }
            state.clear();
        } catch (Exception e) {
            Log.e(TAG, "Error examining prior backup state " + e.getMessage());
            state.clear();
        }

        return state;
    }

    /**
     * New overall state record
     */
    private void writeBackupState(ArrayMap<String, Long> state, ParcelFileDescriptor stateFile) {
        try {
            FileOutputStream fos = new FileOutputStream(stateFile.getFileDescriptor());

            // We explicitly don't close 'out' because we must not close the backing fd.
            // The FileOutputStream will not close it implicitly.
            @SuppressWarnings("resource")
            DataOutputStream out = new DataOutputStream(fos);

            out.writeInt(mCurrentBlobVersion);

            final int N = (state != null) ? state.size() : 0;
            out.writeInt(N);
            for (int i = 0; i < N; i++) {
                final String key = state.keyAt(i);
                final long checksum = state.valueAt(i).longValue();
                if (DEBUG) {
                    Log.i(TAG, "  writing key " + key + " checksum = " + checksum);
                }
                out.writeUTF(key);
                out.writeLong(checksum);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to write updated state", e);
        }
    }

    // Also versions the deflated blob internally in case we need to revise it
    private byte[] deflate(byte[] data) {
        byte[] result = null;
        if (data != null) {
            try {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                DataOutputStream headerOut = new DataOutputStream(sink);

                // write the header directly to the sink ahead of the deflated payload
                headerOut.writeInt(mCurrentBlobVersion);

                DeflaterOutputStream out = new DeflaterOutputStream(sink);
                out.write(data);
                out.close();  // finishes and commits the compression run
                result = sink.toByteArray();
                if (DEBUG) {
                    Log.v(TAG, "Deflated " + data.length + " bytes to " + result.length);
                }
            } catch (IOException e) {
                Log.w(TAG, "Unable to process payload: " + e.getMessage());
            }
        }
        return result;
    }

    // Returns null if inflation failed
    private byte[] inflate(byte[] compressedData) {
        byte[] result = null;
        if (compressedData != null) {
            try {
                ByteArrayInputStream source = new ByteArrayInputStream(compressedData);
                DataInputStream headerIn = new DataInputStream(source);
                int version = headerIn.readInt();
                if (version > mCurrentBlobVersion) {
                    Log.w(TAG, "Saved payload from unrecognized version " + version);
                    return null;
                }

                InflaterInputStream in = new InflaterInputStream(source);
                ByteArrayOutputStream inflated = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int nRead;
                while ((nRead = in.read(buffer)) > 0) {
                    inflated.write(buffer, 0, nRead);
                }
                in.close();
                inflated.flush();
                result = inflated.toByteArray();
                if (DEBUG) {
                    Log.v(TAG, "Inflated " + compressedData.length + " bytes to " + result.length);
                }
            } catch (IOException e) {
                // result is still null here
                Log.w(TAG, "Unable to process restored payload: " + e.getMessage());
            }
        }
        return result;
    }

    private long checksum(byte[] buffer) {
        if (buffer != null) {
            try {
                CRC32 crc = new CRC32();
                ByteArrayInputStream bis = new ByteArrayInputStream(buffer);
                byte[] buf = new byte[4096];
                int nRead = 0;
                while ((nRead = bis.read(buf)) >= 0) {
                    crc.update(buf, 0, nRead);
                }
                return crc.getValue();
            } catch (Exception e) {
                // whoops; fall through with an explicitly bogus checksum
            }
        }
        return -1;
    }

    // BackupHelper interface

    @Override
    public void performBackup(ParcelFileDescriptor oldStateFd, BackupDataOutput data,
            ParcelFileDescriptor newStateFd) {
        if (DEBUG) {
            Log.i(TAG, "Performing backup for " + this.getClass().getName());
        }

        final ArrayMap<String, Long> oldState = readOldState(oldStateFd);
        final ArrayMap<String, Long> newState = new ArrayMap<String, Long>();

        try {
            for (String key : mKeys) {
                final byte[] payload = deflate(getBackupPayload(key));
                final long checksum = checksum(payload);
                if (DEBUG) {
                    Log.i(TAG, "Key " + key + " backup checksum is " + checksum);
                }
                newState.put(key, checksum);

                Long oldChecksum = oldState.get(key);
                if (oldChecksum == null || checksum != oldChecksum.longValue()) {
                    if (DEBUG) {
                        Log.i(TAG, "Checksum has changed from " + oldChecksum + " to " + checksum
                                + " for key " + key + ", writing");
                    }
                    if (payload != null) {
                        data.writeEntityHeader(key, payload.length);
                        data.writeEntityData(payload, payload.length);
                    } else {
                        // state's changed but there's no current payload => delete
                        data.writeEntityHeader(key, -1);
                    }
                } else {
                    if (DEBUG) {
                        Log.i(TAG, "No change under key " + key + " => not writing");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG,  "Unable to record notification state: " + e.getMessage());
            newState.clear();
        } finally {
            // Always rewrite the state even if nothing changed
            writeBackupState(newState, newStateFd);
        }
    }

    @Override
    public void restoreEntity(BackupDataInputStream data) {
        final String key = data.getKey();
        try {
            // known key?
            int which;
            for (which = 0; which < mKeys.length; which++) {
                if (key.equals(mKeys[which])) {
                    break;
                }
            }
            if (which >= mKeys.length) {
                Log.e(TAG, "Unrecognized key " + key + ", ignoring");
                return;
            }

            byte[] compressed = new byte[data.size()];
            data.read(compressed);
            byte[] payload = inflate(compressed);
            applyRestoredPayload(key, payload);
        } catch (Exception e) {
            Log.e(TAG, "Exception restoring entity " + key + " : " + e.getMessage());
        }
    }

    @Override
    public void writeNewStateDescription(ParcelFileDescriptor newState) {
        // Just ensure that we do a full backup the first time after a restore
        if (DEBUG) {
            Log.i(TAG, "Writing state description after restore");
        }
        writeBackupState(null, newState);
    }
}
