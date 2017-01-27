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
import android.os.UserManager;
import android.provider.Settings;
import android.service.persistentdata.IPersistentDataBlockService;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Service for reading and writing blocks to a persistent partition.
 * This data will live across factory resets not initiated via the Settings UI.
 * When a device is factory reset through Settings this data is wiped.
 *
 * Allows writing one block at a time. Namely, each time {@link IPersistentDataBlockService#write}
 * is called, it will overwrite the data that was previously written on the block.
 *
 * Clients can query the size of the currently written block via
 * {@link IPersistentDataBlockService#getDataBlockSize}
 *
 * Clients can read any number of bytes from the currently written block up to its total size by
 * invoking {@link IPersistentDataBlockService#read}
 */
public class PersistentDataBlockService extends SystemService {
    private static final String TAG = PersistentDataBlockService.class.getSimpleName();

    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";
    private static final int HEADER_SIZE = 8;
    // Magic number to mark block device as adhering to the format consumed by this service
    private static final int PARTITION_TYPE_MARKER = 0x19901873;
    // Limit to 100k as blocks larger than this might cause strain on Binder.
    private static final int MAX_DATA_BLOCK_SIZE = 1024 * 100;
    public static final int DIGEST_SIZE_BYTES = 32;
    private static final String OEM_UNLOCK_PROP = "sys.oem_unlock_allowed";
    private static final String FLASH_LOCK_PROP = "ro.boot.flash.locked";
    private static final String FLASH_LOCK_LOCKED = "1";
    private static final String FLASH_LOCK_UNLOCKED = "0";

    private final Context mContext;
    private final String mDataBlockFile;
    private final Object mLock = new Object();

    private int mAllowedUid = -1;
    private long mBlockDeviceSize;

    @GuardedBy("mLock")
    private boolean mIsWritable = true;

    public PersistentDataBlockService(Context context) {
        super(context);
        mContext = context;
        mDataBlockFile = SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP);
        mBlockDeviceSize = -1; // Load lazily
        mAllowedUid = getAllowedUid(UserHandle.USER_SYSTEM);
    }

    private int getAllowedUid(int userHandle) {
        String allowedPackage = mContext.getResources()
                .getString(R.string.config_persistentDataPackageName);
        PackageManager pm = mContext.getPackageManager();
        int allowedUid = -1;
        try {
            allowedUid = pm.getPackageUidAsUser(allowedPackage,
                    PackageManager.MATCH_SYSTEM_ONLY, userHandle);
        } catch (PackageManager.NameNotFoundException e) {
            // not expected
            Slog.e(TAG, "not able to find package " + allowedPackage, e);
        }
        return allowedUid;
    }

    @Override
    public void onStart() {
        enforceChecksumValidity();
        formatIfOemUnlockEnabled();
        publishBinderService(Context.PERSISTENT_DATA_BLOCK_SERVICE, mService);
    }

    private void formatIfOemUnlockEnabled() {
        boolean enabled = doGetOemUnlockEnabled();
        if (enabled) {
            synchronized (mLock) {
                formatPartitionLocked(true);
            }
        }

        SystemProperties.set(OEM_UNLOCK_PROP, enabled ? "1" : "0");
    }

    private void enforceOemUnlockReadPermission() {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.READ_OEM_UNLOCK_STATE)
                == PackageManager.PERMISSION_DENIED
                && mContext.checkCallingOrSelfPermission(Manifest.permission.OEM_UNLOCK_STATE)
                == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException("Can't access OEM unlock state. Requires "
                    + "READ_OEM_UNLOCK_STATE or OEM_UNLOCK_STATE permission.");
        }
    }

    private void enforceOemUnlockWritePermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.OEM_UNLOCK_STATE,
                "Can't modify OEM unlock state");
    }

    private void enforceUid(int callingUid) {
        if (callingUid != mAllowedUid) {
            throw new SecurityException("uid " + callingUid + " not allowed to access PST");
        }
    }

    private void enforceIsAdmin() {
        final int userId = UserHandle.getCallingUserId();
        final boolean isAdmin = UserManager.get(mContext).isUserAdmin(userId);
        if (!isAdmin) {
            throw new SecurityException(
                    "Only the Admin user is allowed to change OEM unlock state");
        }
    }

    private void enforceUserRestriction(String userRestriction) {
        if (UserManager.get(mContext).hasUserRestriction(userRestriction)) {
            throw new SecurityException(
                    "OEM unlock is disallowed by user restriction: " + userRestriction);
        }
    }

    private int getTotalDataSizeLocked(DataInputStream inputStream) throws IOException {
        // skip over checksum
        inputStream.skipBytes(DIGEST_SIZE_BYTES);

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

    private boolean enforceChecksumValidity() {
        byte[] storedDigest = new byte[DIGEST_SIZE_BYTES];

        synchronized (mLock) {
            byte[] digest = computeDigestLocked(storedDigest);
            if (digest == null || !Arrays.equals(storedDigest, digest)) {
                Slog.i(TAG, "Formatting FRP partition...");
                formatPartitionLocked(false);
                return false;
            }
        }

        return true;
    }

    private boolean computeAndWriteDigestLocked() {
        byte[] digest = computeDigestLocked(null);
        if (digest != null) {
            DataOutputStream outputStream;
            try {
                outputStream = new DataOutputStream(
                        new FileOutputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "partition not available?", e);
                return false;
            }

            try {
                outputStream.write(digest, 0, DIGEST_SIZE_BYTES);
                outputStream.flush();
            } catch (IOException e) {
                Slog.e(TAG, "failed to write block checksum", e);
                return false;
            } finally {
                IoUtils.closeQuietly(outputStream);
            }
            return true;
        } else {
            return false;
        }
    }

    private byte[] computeDigestLocked(byte[] storedDigest) {
        DataInputStream inputStream;
        try {
            inputStream = new DataInputStream(new FileInputStream(new File(mDataBlockFile)));
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "partition not available?", e);
            return null;
        }

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // won't ever happen -- every implementation is required to support SHA-256
            Slog.e(TAG, "SHA-256 not supported?", e);
            IoUtils.closeQuietly(inputStream);
            return null;
        }

        try {
            if (storedDigest != null && storedDigest.length == DIGEST_SIZE_BYTES) {
                inputStream.read(storedDigest);
            } else {
                inputStream.skipBytes(DIGEST_SIZE_BYTES);
            }

            int read;
            byte[] data = new byte[1024];
            md.update(data, 0, DIGEST_SIZE_BYTES); // include 0 checksum in digest
            while ((read = inputStream.read(data)) != -1) {
                md.update(data, 0, read);
            }
        } catch (IOException e) {
            Slog.e(TAG, "failed to read partition", e);
            return null;
        } finally {
            IoUtils.closeQuietly(inputStream);
        }

        return md.digest();
    }

    private void formatPartitionLocked(boolean setOemUnlockEnabled) {
        DataOutputStream outputStream;
        try {
            outputStream = new DataOutputStream(new FileOutputStream(new File(mDataBlockFile)));
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "partition not available?", e);
            return;
        }

        byte[] data = new byte[DIGEST_SIZE_BYTES];
        try {
            outputStream.write(data, 0, DIGEST_SIZE_BYTES);
            outputStream.writeInt(PARTITION_TYPE_MARKER);
            outputStream.writeInt(0); // data size
            outputStream.flush();
        } catch (IOException e) {
            Slog.e(TAG, "failed to format block", e);
            return;
        } finally {
            IoUtils.closeQuietly(outputStream);
        }

        doSetOemUnlockEnabledLocked(setOemUnlockEnabled);
        computeAndWriteDigestLocked();
    }

    private void doSetOemUnlockEnabledLocked(boolean enabled) {
        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(new File(mDataBlockFile));
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "partition not available", e);
            return;
        }

        try {
            FileChannel channel = outputStream.getChannel();

            channel.position(getBlockDeviceSize() - 1);

            ByteBuffer data = ByteBuffer.allocate(1);
            data.put(enabled ? (byte) 1 : (byte) 0);
            data.flip();
            channel.write(data);
            outputStream.flush();
        } catch (IOException e) {
            Slog.e(TAG, "unable to access persistent partition", e);
            return;
        } finally {
            SystemProperties.set(OEM_UNLOCK_PROP, enabled ? "1" : "0");
            IoUtils.closeQuietly(outputStream);
        }
    }

    private boolean doGetOemUnlockEnabled() {
        DataInputStream inputStream;
        try {
            inputStream = new DataInputStream(new FileInputStream(new File(mDataBlockFile)));
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "partition not available");
            return false;
        }

        try {
            synchronized (mLock) {
                inputStream.skip(getBlockDeviceSize() - 1);
                return inputStream.readByte() != 0;
            }
        } catch (IOException e) {
            Slog.e(TAG, "unable to access persistent partition", e);
            return false;
        } finally {
            IoUtils.closeQuietly(inputStream);
        }
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

            synchronized (mLock) {
                if (!mIsWritable) {
                    IoUtils.closeQuietly(outputStream);
                    return -1;
                }

                try {
                    byte[] checksum = new byte[DIGEST_SIZE_BYTES];
                    outputStream.write(checksum, 0, DIGEST_SIZE_BYTES);
                    outputStream.write(headerAndData.array());
                    outputStream.flush();
                } catch (IOException e) {
                    Slog.e(TAG, "failed writing to the persistent data block", e);
                    return -1;
                } finally {
                    IoUtils.closeQuietly(outputStream);
                }

                if (computeAndWriteDigestLocked()) {
                    return data.length;
                } else {
                    return -1;
                }
            }
        }

        @Override
        public byte[] read() {
            enforceUid(Binder.getCallingUid());
            if (!enforceChecksumValidity()) {
                return new byte[0];
            }

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
            enforceOemUnlockWritePermission();

            synchronized (mLock) {
                int ret = nativeWipe(mDataBlockFile);

                if (ret < 0) {
                    Slog.e(TAG, "failed to wipe persistent partition");
                } else {
                    mIsWritable = false;
                    Slog.i(TAG, "persistent partition now wiped and unwritable");
                }
            }
        }

        @Override
        public void setOemUnlockEnabled(boolean enabled) throws SecurityException {
            // do not allow monkey to flip the flag
            if (ActivityManager.isUserAMonkey()) {
                return;
            }

            enforceOemUnlockWritePermission();
            enforceIsAdmin();

            if (enabled) {
                // Do not allow oem unlock to be enabled if it's disallowed by a user restriction.
                enforceUserRestriction(UserManager.DISALLOW_OEM_UNLOCK);
                enforceUserRestriction(UserManager.DISALLOW_FACTORY_RESET);
            }
            synchronized (mLock) {
                doSetOemUnlockEnabledLocked(enabled);
                computeAndWriteDigestLocked();
            }
        }

        @Override
        public boolean getOemUnlockEnabled() {
            enforceOemUnlockReadPermission();
            return doGetOemUnlockEnabled();
        }

        @Override
        public int getFlashLockState() {
            enforceOemUnlockReadPermission();
            String locked = SystemProperties.get(FLASH_LOCK_PROP);
            switch (locked) {
                case FLASH_LOCK_LOCKED:
                    return PersistentDataBlockManager.FLASH_LOCK_LOCKED;
                case FLASH_LOCK_UNLOCKED:
                    return PersistentDataBlockManager.FLASH_LOCK_UNLOCKED;
                default:
                    return PersistentDataBlockManager.FLASH_LOCK_UNKNOWN;
            }
        }

        @Override
        public int getDataBlockSize() {
            enforcePersistentDataBlockAccess();

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

        private void enforcePersistentDataBlockAccess() {
            if (mContext.checkCallingPermission(Manifest.permission.ACCESS_PDB_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                enforceUid(Binder.getCallingUid());
            }
        }

        @Override
        public long getMaximumDataBlockSize() {
            long actualSize = getBlockDeviceSize() - HEADER_SIZE - 1;
            return actualSize <= MAX_DATA_BLOCK_SIZE ? actualSize : MAX_DATA_BLOCK_SIZE;
        }
    };
}
