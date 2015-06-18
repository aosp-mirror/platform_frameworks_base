/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.persistentdata;

import android.annotation.SystemApi;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Interface for reading and writing data blocks to a persistent partition.
 *
 * Allows writing one block at a time. Namely, each time
 * {@link PersistentDataBlockManager#write(byte[])}
 * is called, it will overwite the data that was previously written on the block.
 *
 * Clients can query the size of the currently written block via
 * {@link PersistentDataBlockManager#getDataBlockSize()}.
 *
 * Clients can query the maximum size for a block via
 * {@link PersistentDataBlockManager#getMaximumDataBlockSize()}
 *
 * Clients can read the currently written block by invoking
 * {@link PersistentDataBlockManager#read()}.
 *
 * @hide
 */
@SystemApi
public class PersistentDataBlockManager {
    private static final String TAG = PersistentDataBlockManager.class.getSimpleName();
    private IPersistentDataBlockService sService;

    public PersistentDataBlockManager(IPersistentDataBlockService service) {
        sService = service;
    }

    /**
     * Writes {@code data} to the persistent partition. Previously written data
     * will be overwritten. This data will persist across factory resets.
     *
     * Returns the number of bytes written or -1 on error. If the block is too big
     * to fit on the partition, returns -MAX_BLOCK_SIZE.
     *
     * @param data the data to write
     */
    public int write(byte[] data) {
        try {
            return sService.write(data);
        } catch (RemoteException e) {
            onError("writing data");
            return -1;
        }
    }

    /**
     * Returns the data block stored on the persistent partition.
     */
    public byte[] read() {
        try {
            return sService.read();
        } catch (RemoteException e) {
            onError("reading data");
            return null;
        }
    }

    /**
     * Retrieves the size of the block currently written to the persistent partition.
     *
     * Return -1 on error.
     */
    public int getDataBlockSize() {
        try {
            return sService.getDataBlockSize();
        } catch (RemoteException e) {
            onError("getting data block size");
            return -1;
        }
    }

    /**
     * Retrieves the maximum size allowed for a data block.
     *
     * Returns -1 on error.
     */
    public long getMaximumDataBlockSize() {
        try {
            return sService.getMaximumDataBlockSize();
        } catch (RemoteException e) {
            onError("getting maximum data block size");
            return -1;
        }
    }

    /**
     * Zeroes the previously written block in its entirety. Calling this method
     * will erase all data written to the persistent data partition.
     */
    public void wipe() {
        try {
            sService.wipe();
        } catch (RemoteException e) {
            onError("wiping persistent partition");
        }
    }

    /**
     * Writes a byte enabling or disabling the ability to "OEM unlock" the device.
     */
    public void setOemUnlockEnabled(boolean enabled) {
        try {
            sService.setOemUnlockEnabled(enabled);
        } catch (RemoteException e) {
            onError("setting OEM unlock enabled to " + enabled);
        }
    }

    /**
     * Returns whether or not "OEM unlock" is enabled or disabled on this device.
     */
    public boolean getOemUnlockEnabled() {
        try {
            return sService.getOemUnlockEnabled();
        } catch (RemoteException e) {
            onError("getting OEM unlock enabled bit");
            return false;
        }
    }

    private void onError(String msg) {
        Slog.v(TAG, "Remote exception while " + msg);
    }
}
