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

package com.android.server.pdb;

import static com.android.internal.util.Preconditions.checkArgument;

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
import android.service.persistentdata.IPersistentDataBlockService;
import android.service.persistentdata.PersistentDataBlockManager;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;

import libcore.io.IoUtils;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 *
 * The persistent data block is currently laid out as follows:
 * | ---------BEGINNING OF PARTITION-------------|
 * | Partition digest (32 bytes)                 |
 * | --------------------------------------------|
 * | PARTITION_TYPE_MARKER (4 bytes)             |
 * | --------------------------------------------|
 * | FRP data block length (4 bytes)             |
 * | --------------------------------------------|
 * | FRP data (variable length)                  |
 * | --------------------------------------------|
 * | ...                                         |
 * | --------------------------------------------|
 * | Test mode data block (10000 bytes)          |
 * | --------------------------------------------|
 * |     | Test mode data length (4 bytes)       |
 * | --------------------------------------------|
 * |     | Test mode data (variable length)      |
 * |     | ...                                   |
 * | --------------------------------------------|
 * | FRP credential handle block (1000 bytes)    |
 * | --------------------------------------------|
 * |     | FRP credential handle length (4 bytes)|
 * | --------------------------------------------|
 * |     | FRP credential handle (variable len)  |
 * |     | ...                                   |
 * | --------------------------------------------|
 * | OEM Unlock bit (1 byte)                     |
 * | ---------END OF PARTITION-------------------|
 *
 * TODO: now that the persistent partition contains several blocks, next time someone wants a new
 * block, we should look at adding more generic block definitions and get rid of the various raw
 * XXX_RESERVED_SIZE and XXX_DATA_SIZE constants. That will ensure the code is easier to maintain
 * and less likely to introduce out-of-bounds read/write.
 */
public class PersistentDataBlockService extends SystemService {
    private static final String TAG = PersistentDataBlockService.class.getSimpleName();

    private static final String GSI_SANDBOX = "/data/gsi_persistent_data";
    private static final String GSI_RUNNING_PROP = "ro.gsid.image_running";

    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";
    @VisibleForTesting
    static final int HEADER_SIZE = 8;
    // Magic number to mark block device as adhering to the format consumed by this service
    private static final int PARTITION_TYPE_MARKER = 0x19901873;
    /** Size of the block reserved for FRP credential, including 4 bytes for the size header. */
    private static final int FRP_CREDENTIAL_RESERVED_SIZE = 1000;
    /** Maximum size of the FRP credential handle that can be stored. */
    @VisibleForTesting
    static final int MAX_FRP_CREDENTIAL_HANDLE_SIZE = FRP_CREDENTIAL_RESERVED_SIZE - 4;
    /**
     * Size of the block reserved for Test Harness Mode data, including 4 bytes for the size header.
     */
    private static final int TEST_MODE_RESERVED_SIZE = 10000;
    /** Maximum size of the Test Harness Mode data that can be stored. */
    @VisibleForTesting
    static final int MAX_TEST_MODE_DATA_SIZE = TEST_MODE_RESERVED_SIZE - 4;

    // Limit to 100k as blocks larger than this might cause strain on Binder.
    @VisibleForTesting
    static final int MAX_DATA_BLOCK_SIZE = 1024 * 100;

    public static final int DIGEST_SIZE_BYTES = 32;
    private static final String OEM_UNLOCK_PROP = "sys.oem_unlock_allowed";
    private static final String FLASH_LOCK_PROP = "ro.boot.flash.locked";
    private static final String FLASH_LOCK_LOCKED = "1";
    private static final String FLASH_LOCK_UNLOCKED = "0";

    private final Context mContext;
    private final String mDataBlockFile;
    private final boolean mIsFileBacked;
    private final Object mLock = new Object();
    private final CountDownLatch mInitDoneSignal = new CountDownLatch(1);

    private int mAllowedUid = -1;
    private long mBlockDeviceSize = -1; // Load lazily

    @GuardedBy("mLock")
    private boolean mIsWritable = true;

    public PersistentDataBlockService(Context context) {
        super(context);
        mContext = context;
        if (SystemProperties.getBoolean(GSI_RUNNING_PROP, false)) {
            mIsFileBacked = true;
            mDataBlockFile = GSI_SANDBOX;
        } else {
            mIsFileBacked = false;
            mDataBlockFile = SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP);
        }
    }

    @VisibleForTesting
    PersistentDataBlockService(Context context, boolean isFileBacked, String dataBlockFile,
            long blockDeviceSize) {
        super(context);
        mContext = context;
        mIsFileBacked = isFileBacked;
        mDataBlockFile = dataBlockFile;
        mBlockDeviceSize = blockDeviceSize;
    }

    private int getAllowedUid() {
        final UserManagerInternal umInternal = LocalServices.getService(UserManagerInternal.class);
        int mainUserId = umInternal.getMainUserId();
        if (mainUserId < 0) {
            // If main user is not defined. Use the SYSTEM user instead.
            mainUserId = UserHandle.USER_SYSTEM;
        }
        String allowedPackage = mContext.getResources()
                .getString(R.string.config_persistentDataPackageName);
        int allowedUid = -1;
        if (!TextUtils.isEmpty(allowedPackage)) {
            try {
                allowedUid = mContext.getPackageManager().getPackageUidAsUser(
                        allowedPackage, PackageManager.MATCH_SYSTEM_ONLY, mainUserId);
            } catch (PackageManager.NameNotFoundException e) {
                // not expected
                Slog.e(TAG, "not able to find package " + allowedPackage, e);
            }
        }
        return allowedUid;
    }

    @Override
    public void onStart() {
        // Do init on a separate thread, will join in PHASE_ACTIVITY_MANAGER_READY
        SystemServerInitThreadPool.submit(() -> {
            enforceChecksumValidity();
            formatIfOemUnlockEnabled();
            publishBinderService(Context.PERSISTENT_DATA_BLOCK_SERVICE, mService);
            mInitDoneSignal.countDown();
        }, TAG + ".onStart");
    }

    @Override
    public void onBootPhase(int phase) {
        // Wait for initialization in onStart to finish
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            try {
                if (!mInitDoneSignal.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Service " + TAG + " init timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Service " + TAG + " init interrupted", e);
            }
            // The user responsible for FRP should exist by now.
            mAllowedUid = getAllowedUid();
            LocalServices.addService(PersistentDataBlockManagerInternal.class, mInternalService);
        }
        super.onBootPhase(phase);
    }

    @VisibleForTesting
    void setAllowedUid(int uid) {
        mAllowedUid = uid;
    }

    private void formatIfOemUnlockEnabled() {
        boolean enabled = doGetOemUnlockEnabled();
        if (enabled) {
            synchronized (mLock) {
                formatPartitionLocked(true);
            }
        }

        setProperty(OEM_UNLOCK_PROP, enabled ? "1" : "0");
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
                if (mIsFileBacked) {
                    mBlockDeviceSize = MAX_DATA_BLOCK_SIZE;
                } else {
                    mBlockDeviceSize = nativeGetBlockDeviceSize(mDataBlockFile);
                }
            }
        }

        return mBlockDeviceSize;
    }

    @VisibleForTesting
    int getMaximumFrpDataSize() {
        return (int) (getTestHarnessModeDataOffset() - DIGEST_SIZE_BYTES - HEADER_SIZE);
    }

    @VisibleForTesting
    long getFrpCredentialDataOffset() {
        return getOemUnlockDataOffset() - FRP_CREDENTIAL_RESERVED_SIZE;
    }

    @VisibleForTesting
    long getTestHarnessModeDataOffset() {
        return getFrpCredentialDataOffset() - TEST_MODE_RESERVED_SIZE;
    }

    @VisibleForTesting
    long getOemUnlockDataOffset() {
        return getBlockDeviceSize() - 1;
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

    private FileChannel getBlockOutputChannel() throws IOException {
        return new RandomAccessFile(mDataBlockFile, "rw").getChannel();
    }

    private boolean computeAndWriteDigestLocked() {
        byte[] digest = computeDigestLocked(null);
        if (digest != null) {
            try (FileChannel channel = getBlockOutputChannel()) {
                ByteBuffer buf = ByteBuffer.allocate(DIGEST_SIZE_BYTES);
                buf.put(digest);
                buf.flip();
                channel.write(buf);
                channel.force(true);
            } catch (IOException e) {
                Slog.e(TAG, "failed to write block checksum", e);
                return false;
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

    @VisibleForTesting
    void formatPartitionLocked(boolean setOemUnlockEnabled) {

        try (FileChannel channel = getBlockOutputChannel()) {
            // Format the data selectively.
            //
            // 1. write header, set length = 0
            int header_size = DIGEST_SIZE_BYTES + HEADER_SIZE;
            ByteBuffer buf = ByteBuffer.allocate(header_size);
            buf.put(new byte[DIGEST_SIZE_BYTES]);
            buf.putInt(PARTITION_TYPE_MARKER);
            buf.putInt(0);
            buf.flip();
            channel.write(buf);
            channel.force(true);

            // 2. corrupt the legacy FRP data explicitly
            int payload_size = (int) getBlockDeviceSize() - header_size;
            buf = ByteBuffer.allocate(payload_size
                          - TEST_MODE_RESERVED_SIZE - FRP_CREDENTIAL_RESERVED_SIZE - 1);
            channel.write(buf);
            channel.force(true);

            // 3. skip the test mode data and leave it unformat
            //    This is for a feature that enables testing.
            channel.position(channel.position() + TEST_MODE_RESERVED_SIZE);

            // 4. wipe the FRP_CREDENTIAL explicitly
            buf = ByteBuffer.allocate(FRP_CREDENTIAL_RESERVED_SIZE);
            channel.write(buf);
            channel.force(true);

            // 5. set unlock = 0 because it's a formatPartitionLocked
            buf = ByteBuffer.allocate(FRP_CREDENTIAL_RESERVED_SIZE);
            buf.put((byte)0);
            buf.flip();
            channel.write(buf);
            channel.force(true);
        } catch (IOException e) {
            Slog.e(TAG, "failed to format block", e);
            return;
        }

        doSetOemUnlockEnabledLocked(setOemUnlockEnabled);
        computeAndWriteDigestLocked();
    }

    private void doSetOemUnlockEnabledLocked(boolean enabled) {

        try (FileChannel channel = getBlockOutputChannel()) {

            channel.position(getBlockDeviceSize() - 1);

            ByteBuffer data = ByteBuffer.allocate(1);
            data.put(enabled ? (byte) 1 : (byte) 0);
            data.flip();
            channel.write(data);
            channel.force(true);
        } catch (IOException e) {
            Slog.e(TAG, "unable to access persistent partition", e);
            return;
        } finally {
            setProperty(OEM_UNLOCK_PROP, enabled ? "1" : "0");
        }
    }

    @VisibleForTesting
    void setProperty(String name, String value) {
        SystemProperties.set(name, value);
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

    private long doGetMaximumDataBlockSize() {
        long actualSize = getBlockDeviceSize() - HEADER_SIZE - DIGEST_SIZE_BYTES
                - TEST_MODE_RESERVED_SIZE - FRP_CREDENTIAL_RESERVED_SIZE - 1;
        return actualSize <= MAX_DATA_BLOCK_SIZE ? actualSize : MAX_DATA_BLOCK_SIZE;
    }

    private native long nativeGetBlockDeviceSize(String path);
    private native int nativeWipe(String path);

    @VisibleForTesting
    IPersistentDataBlockService getInterfaceForTesting() {
        return IPersistentDataBlockService.Stub.asInterface(mService);
    }

    @VisibleForTesting
    PersistentDataBlockManagerInternal getInternalInterfaceForTesting() {
        return mInternalService;
    }

    private final IBinder mService = new IPersistentDataBlockService.Stub() {

        /**
         * Write the data to the persistent data block.
         *
         * @return a positive integer of the number of bytes that were written if successful,
         * otherwise a negative integer indicating there was a problem
         */
        @Override
        public int write(byte[] data) throws RemoteException {
            enforceUid(Binder.getCallingUid());

            // Need to ensure we don't write over the last byte
            long maxBlockSize = doGetMaximumDataBlockSize();
            if (data.length > maxBlockSize) {
                // partition is ~500k so shouldn't be a problem to downcast
                return (int) -maxBlockSize;
            }

            ByteBuffer headerAndData = ByteBuffer.allocate(
                                           data.length + HEADER_SIZE + DIGEST_SIZE_BYTES);
            headerAndData.put(new byte[DIGEST_SIZE_BYTES]);
            headerAndData.putInt(PARTITION_TYPE_MARKER);
            headerAndData.putInt(data.length);
            headerAndData.put(data);
            headerAndData.flip();
            synchronized (mLock) {
                if (!mIsWritable) {
                    return -1;
                }

                try (FileChannel channel = getBlockOutputChannel()) {
                    channel.write(headerAndData);
                    channel.force(true);
                } catch (IOException e) {
                    Slog.e(TAG, "failed writing to the persistent data block", e);
                    return -1;
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
                int ret;
                if (mIsFileBacked) {
                    try {
                        Files.write(Paths.get(mDataBlockFile), new byte[MAX_DATA_BLOCK_SIZE],
                                StandardOpenOption.TRUNCATE_EXISTING);
                        ret = 0;
                    } catch (IOException e) {
                        ret = -1;
                    }
                } else {
                    ret = nativeWipe(mDataBlockFile);
                }

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
            enforceUid(Binder.getCallingUid());
            return doGetMaximumDataBlockSize();
        }

        @Override
        public boolean hasFrpCredentialHandle() {
            enforcePersistentDataBlockAccess();
            try {
                return mInternalService.getFrpCredentialHandle() != null;
            } catch (IllegalStateException e) {
                Slog.e(TAG, "error reading frp handle", e);
                throw new UnsupportedOperationException("cannot read frp credential");
            }
        }

        @Override
        public String getPersistentDataPackageName() {
            enforcePersistentDataBlockAccess();
            return mContext.getString(R.string.config_persistentDataPackageName);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            pw.println("mDataBlockFile: " + mDataBlockFile);
            pw.println("mIsFileBacked: " + mIsFileBacked);
            pw.println("mInitDoneSignal: " + mInitDoneSignal);
            pw.println("mAllowedUid: " + mAllowedUid);
            pw.println("mBlockDeviceSize: " + mBlockDeviceSize);
            synchronized (mLock) {
                pw.println("mIsWritable: " + mIsWritable);
            }
        }
    };

    private PersistentDataBlockManagerInternal mInternalService =
            new PersistentDataBlockManagerInternal() {

        @Override
        public void setFrpCredentialHandle(byte[] handle) {
            writeInternal(handle, getFrpCredentialDataOffset(), MAX_FRP_CREDENTIAL_HANDLE_SIZE);
        }

        @Override
        public byte[] getFrpCredentialHandle() {
            return readInternal(getFrpCredentialDataOffset(), MAX_FRP_CREDENTIAL_HANDLE_SIZE);
        }

        @Override
        public void setTestHarnessModeData(byte[] data) {
            writeInternal(data, getTestHarnessModeDataOffset(), MAX_TEST_MODE_DATA_SIZE);
        }

        @Override
        public byte[] getTestHarnessModeData() {
            byte[] data = readInternal(getTestHarnessModeDataOffset(), MAX_TEST_MODE_DATA_SIZE);
            if (data == null) {
                return new byte[0];
            }
            return data;
        }

        @Override
        public void clearTestHarnessModeData() {
            int size = Math.min(MAX_TEST_MODE_DATA_SIZE, getTestHarnessModeData().length) + 4;
            writeDataBuffer(getTestHarnessModeDataOffset(), ByteBuffer.allocate(size));
        }

        @Override
        public int getAllowedUid() {
            return mAllowedUid;
        }

        private void writeInternal(byte[] data, long offset, int dataLength) {
            checkArgument(data == null || data.length > 0, "data must be null or non-empty");
            checkArgument(
                    data == null || data.length <= dataLength,
                    "data must not be longer than " + dataLength);

            ByteBuffer dataBuffer = ByteBuffer.allocate(dataLength + 4);
            dataBuffer.putInt(data == null ? 0 : data.length);
            if (data != null) {
                dataBuffer.put(data);
            }
            dataBuffer.flip();

            writeDataBuffer(offset, dataBuffer);
        }

        private void writeDataBuffer(long offset, ByteBuffer dataBuffer) {
            synchronized (mLock) {
                if (!mIsWritable) {
                    return;
                }
                try (FileChannel channel = getBlockOutputChannel()) {
                    channel.position(offset);
                    channel.write(dataBuffer);
                    channel.force(true);
                } catch (IOException e) {
                    Slog.e(TAG, "unable to access persistent partition", e);
                    return;
                }

                computeAndWriteDigestLocked();
            }
        }

        private byte[] readInternal(long offset, int maxLength) {
            if (!enforceChecksumValidity()) {
                throw new IllegalStateException("invalid checksum");
            }

            DataInputStream inputStream;
            try {
                inputStream = new DataInputStream(
                        new FileInputStream(new File(mDataBlockFile)));
            } catch (FileNotFoundException e) {
                throw new IllegalStateException("persistent partition not available");
            }

            try {
                synchronized (mLock) {
                    inputStream.skip(offset);
                    int length = inputStream.readInt();
                    if (length <= 0 || length > maxLength) {
                        return null;
                    }
                    byte[] bytes = new byte[length];
                    inputStream.readFully(bytes);
                    return bytes;
                }
            } catch (IOException e) {
                throw new IllegalStateException("persistent partition not readable", e);
            } finally {
                IoUtils.closeQuietly(inputStream);
            }
        }

        @Override
        public void forceOemUnlockEnabled(boolean enabled) {
            synchronized (mLock) {
                doSetOemUnlockEnabledLocked(enabled);
                computeAndWriteDigestLocked();
            }
        }
    };
}
