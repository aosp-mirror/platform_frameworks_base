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

package com.android.server;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.service.persistentdata.IPersistentDataBlockService;
import android.util.Slog;

import com.android.internal.R;

import libcore.io.IoUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Service for reading and writing blocks to a persistent partition.
 * This data will live across factory resets not initiated via the Settings UI.
 * When a device is factory reset through Settings this data is wiped.
 *
 * Allows writing one block at a time. Namely, each time
 * {@link android.service.persistentdata.IPersistentDataBlockService}.write(byte[] data)
 * is called, it will overwite the data that was previously written on the block.
 *
 * Clients can query the size of the currently written block via
 * {@link android.service.persistentdata.IPersistentDataBlockService}.getTotalDataSize().
 *
 * Clients can any number of bytes from the currently written block up to its total size by invoking
 * {@link android.service.persistentdata.IPersistentDataBlockService}.read(byte[] data)
 */
public class PersistentDataBlockService extends SystemService {
    private static final String TAG = PersistentDataBlockService.class.getSimpleName();

    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";
    private static final int HEADER_SIZE = 8;
    // Magic number to mark block device as adhering to the format consumed by this service
    private static final int PARTITION_TYPE_MARKER = 0x1990;
    // Limit to 100k as blocks larger than this might cause strain on Binder.
    // TODO(anmorales): Consider splitting up too-large blocks in PersistentDataBlockManager
    private static final int MAX_DATA_BLOCK_SIZE = 1024 * 100;

    private final Context mContext;
    private final String mDataBlockFile;
    private final Object mLock = new Object();

    private int mAllowedAppId = -1;
    /*
     * Separate lock for OEM unlock related operations as they can happen in parallel with regular
     * block operations.
     */
    private final Object mOemLock = new Object();

    private long mBlockDeviceSize;

    public PersistentDataBlockService(Context context) {
        super(context);
        mContext = context;
        mDataBlockFile = SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP);
        mBlockDeviceSize = -1; // Load lazily
        mAllowedAppId = getAllowedAppId(UserHandle.USER_OWNER);
    }


    private int getAllowedAppId(int userHandle) {
        String allowedPackage = mContext.getResources()
                .getString(R.string.config_persistentDataPackageName);
        PackageManager pm = mContext.getPackageManager();
        int allowedUid = -1;
        try {
            allowedUid = pm.getPackageUid(allowedPackage, userHandle);
        } catch (PackageManager.NameNotFoundException e) {
            // not expected
            Slog.e(TAG, "not able to find package " + allowedPackage, e);
        }
        return UserHandle.getAppId(allowedUid);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.PERSISTENT_DATA_BLOCK_SERVICE, mService);
    }

    private void enforceOemUnlockPermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.OEM_UNLOCK_STATE,
                "Can't access OEM unlock state");
    }

    private void enforceUid(int callingUid) {
        if (UserHandle.getAppId(callingUid) != mAllowedAppId) {
            throw new SecurityException("uid " + callingUid + " not allowed to access PST");
        }
    }

    private int getTotalDataSizeLocked(DataInputStream inputStream) throws IOException {
        int totalDataSize;
        int blockId = inputStream.readInt();
        if (blockId == PARTITION_TYPE_MARKER) {
            totalDataSize = inputStream.readInt();
        } else {
            totalDataSize = 0;
        }
        return totalDataSize;
    }

    private long getBlockDeviceSize() {
        synchronized (mLock) {
            if (mBlockDeviceSize == -1) {
                mBlockDeviceSize = nativeGetBlockDeviceSize(mDataBlockFile);
            }
        }

        return mBlockDeviceSize;
    }

    private native long nativeGetBlockDeviceSize(String path);
    private native int nativeWipe(String path);

    private final IBinder mService = new IPersistentDataBlockService.Stub() {
        @Override
        public int write(byte[] data) throws RemoteException {
            enforceUid(Binder.getCallingUid());

            // Need to ensure we don't write over the last byte
            long maxBlockSize = getBlockDeviceSize() - HEADER_SIZE - 1;
            if (data.length > maxBlockSize) {
                // partition is ~500k so shouldn't be a problem to downcast
                return (int) -maxBlockSize;
            }

            DataOutputStream outputStream;
            try {
                outputStream = new DataOutputStream(new FileOutputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "partition not available?", e);
                return -1;
            }

            ByteBuffer headerAndData = ByteBuffer.allocate(data.length + HEADER_SIZE);
            headerAndData.putInt(PARTITION_TYPE_MARKER);
            headerAndData.putInt(data.length);
            headerAndData.put(data);

            try {
                synchronized (mLock) {
                    outputStream.write(headerAndData.array());
                    return data.length;
                }
            } catch (IOException e) {
                Slog.e(TAG, "failed writing to the persistent data block", e);
                return -1;
            } finally {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    Slog.e(TAG, "failed closing output stream", e);
                }
            }
        }

        @Override
        public byte[] read() {
            enforceUid(Binder.getCallingUid());

            DataInputStream inputStream;
            try {
                inputStream = new DataInputStream(new FileInputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "partition not available?", e);
                return null;
            }

            try {
                synchronized (mLock) {
                    int totalDataSize = getTotalDataSizeLocked(inputStream);

                    if (totalDataSize == 0) {
                        return new byte[0];
                    }

                    byte[] data = new byte[totalDataSize];
                    int read = inputStream.read(data, 0, totalDataSize);
                    if (read < totalDataSize) {
                        // something went wrong, not returning potentially corrupt data
                        Slog.e(TAG, "failed to read entire data block. bytes read: " +
                                read + "/" + totalDataSize);
                        return null;
                    }
                    return data;
                }
            } catch (IOException e) {
                Slog.e(TAG, "failed to read data", e);
                return null;
            } finally {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Slog.e(TAG, "failed to close OutputStream");
                }
            }
        }

        @Override
        public void wipe() {
            enforceOemUnlockPermission();

            synchronized (mLock) {
                int ret = nativeWipe(mDataBlockFile);

                if (ret < 0) {
                    Slog.e(TAG, "failed to wipe persistent partition");
                }
            }
        }

        @Override
        public void setOemUnlockEnabled(boolean enabled) {
            // do not allow monkey to flip the flag
            if (ActivityManager.isUserAMonkey()) {
                return;
            }
            enforceOemUnlockPermission();
            FileOutputStream outputStream;
            try {
                outputStream = new FileOutputStream(new File(mDataBlockFile));
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "parition not available", e);
                return;
            }

            try {
                FileChannel channel = outputStream.getChannel();

                channel.position(getBlockDeviceSize() - 1);

                ByteBuffer data = ByteBuffer.allocate(1);
                data.put(enabled ? (byte) 1 : (byte) 0);
                data.flip();

                synchronized (mOemLock) {
                    channel.write(data);
                }
            } catch (IOException e) {
                Slog.e(TAG, "unable to access persistent partition", e);
            } finally {
                IoUtils.closeQuietly(outputStream);
            }
        }

        @Override
        public boolean getOemUnlockEnabled() {
            enforceOemUnlockPermission();
            DataInputStream inputStream;
            try {
                inputStream = new DataInputStream(new FileInputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "partition not available");
                return false;
            }

            try {
                inputStream.skip(getBlockDeviceSize() - 1);
                synchronized (mOemLock) {
                    return inputStream.readByte() != 0;
                }
            } catch (IOException e) {
                Slog.e(TAG, "unable to access persistent partition", e);
                return false;
            } finally {
                IoUtils.closeQuietly(inputStream);
            }
        }

        @Override
        public int getDataBlockSize() {
            enforceUid(Binder.getCallingUid());

            DataInputStream inputStream;
            try {
                inputStream = new DataInputStream(new FileInputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "partition not available");
                return 0;
            }

            try {
                synchronized (mLock) {
                    return getTotalDataSizeLocked(inputStream);
                }
            } catch (IOException e) {
                Slog.e(TAG, "error reading data block size");
                return 0;
            } finally {
                IoUtils.closeQuietly(inputStream);
            }
        }

        @Override
        public long getMaximumDataBlockSize() {
            long actualSize = getBlockDeviceSize() - HEADER_SIZE - 1;
            return actualSize <= MAX_DATA_BLOCK_SIZE ? actualSize : MAX_DATA_BLOCK_SIZE;
        }

    };
}
