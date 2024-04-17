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

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.SYNC;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Flags;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
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
 * | FRP data (variable length; 100KB max)    |
 * | --------------------------------------------|
 * | ...                                         |
 * | Empty space.                                |
 * | ...                                         |
 * | --------------------------------------------|
 * | FRP secret magic (8 bytes)                  |
 * | FRP secret (32 bytes)                       |
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
    @VisibleForTesting
    static final int FRP_CREDENTIAL_RESERVED_SIZE = 1000;
    /** Maximum size of the FRP credential handle that can be stored. */
    @VisibleForTesting
    static final int MAX_FRP_CREDENTIAL_HANDLE_SIZE = FRP_CREDENTIAL_RESERVED_SIZE - 4;
    /** Size of the FRP mode deactivation secret, in bytes */
    @VisibleForTesting
    static final int FRP_SECRET_SIZE = 32;
    /** Magic value to identify the FRP secret is present. */
    @VisibleForTesting
    static final byte[] FRP_SECRET_MAGIC = {(byte) 0xda, (byte) 0xc2, (byte) 0xfc,
            (byte) 0xcd, (byte) 0xb9, 0x1b, 0x09, (byte) 0x88};

    /**
     * Size of the block reserved for Test Harness Mode data, including 4 bytes for the size header.
     */
    @VisibleForTesting
    static final int TEST_MODE_RESERVED_SIZE = 10000;
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

    /**
     * Path to FRP secret stored on /data.  This file enables automatic deactivation of FRP mode if
     * it contains the current FRP secret.  When /data is wiped in an untrusted reset this file is
     * destroyed, blocking automatic deactivation.
     */
    private static final String FRP_SECRET_FILE = "/data/system/frp_secret";

    /**
     * Path to temp file used when changing the FRP secret.
     */
    private static final String FRP_SECRET_TMP_FILE = "/data/system/frp_secret_tmp";

    public static final String BOOTLOADER_LOCK_STATE = "ro.boot.vbmeta.device_state";
    public static final String VERIFIED_BOOT_STATE = "ro.boot.verifiedbootstate";
    public static final int INIT_WAIT_TIMEOUT = 10;

    private final Context mContext;
    private final String mDataBlockFile;
    private final boolean mIsFileBacked;
    private final Object mLock = new Object();
    private final CountDownLatch mInitDoneSignal = new CountDownLatch(1);
    private final String mFrpSecretFile;
    private final String mFrpSecretTmpFile;

    private int mAllowedUid = -1;
    private long mBlockDeviceSize = -1; // Load lazily

    private final boolean mFrpEnforced;

    /**
     * FRP active state.  When true (the default) we may have had an untrusted factory reset. In
     * that case we block any updates of the persistent data block.  To exit active state, it's
     * necessary for some caller to provide the FRP secret.
     */
    private boolean mFrpActive = false;

    @GuardedBy("mLock")
    private boolean mIsWritable = true;

    public PersistentDataBlockService(Context context) {
        super(context);
        mContext = context;
        mFrpEnforced = Flags.frpEnforcement();
        mFrpActive = mFrpEnforced;
        mFrpSecretFile = FRP_SECRET_FILE;
        mFrpSecretTmpFile = FRP_SECRET_TMP_FILE;
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
            long blockDeviceSize, boolean frpEnabled, String frpSecretFile,
            String frpSecretTmpFile) {
        super(context);
        mContext = context;
        mIsFileBacked = isFileBacked;
        mDataBlockFile = dataBlockFile;
        mBlockDeviceSize = blockDeviceSize;
        mFrpEnforced = frpEnabled;
        mFrpActive = mFrpEnforced;
        mFrpSecretFile = frpSecretFile;
        mFrpSecretTmpFile = frpSecretTmpFile;
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
            if (mFrpEnforced) {
                automaticallyDeactivateFrpIfPossible();
                setOemUnlockEnabledProperty(doGetOemUnlockEnabled());
                setOldSettingForBackworkCompatibility(mFrpActive);
            } else {
                formatIfOemUnlockEnabled();
            }
            publishBinderService(Context.PERSISTENT_DATA_BLOCK_SERVICE, mService);
            signalInitDone();
        }, TAG + ".onStart");
    }

    @VisibleForTesting
    void signalInitDone() {
        mInitDoneSignal.countDown();
    }

    private void setOldSettingForBackworkCompatibility(boolean isActive) {
        // Set the SECURE_FRP_MODE flag, for backward compatibility with clients who use it.
        // They should switch to calling #isFrpActive().
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.SECURE_FRP_MODE, isActive ? 1 : 0);
    }

    private void setOemUnlockEnabledProperty(boolean oemUnlockEnabled) {
        setProperty(OEM_UNLOCK_PROP, oemUnlockEnabled ? "1" : "0");
    }

    @Override
    public void onBootPhase(int phase) {
        // Wait for initialization in onStart to finish
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            waitForInitDoneSignal();
            // The user responsible for FRP should exist by now.
            mAllowedUid = getAllowedUid();
            LocalServices.addService(PersistentDataBlockManagerInternal.class, mInternalService);
        }
        super.onBootPhase(phase);
    }

    private void waitForInitDoneSignal() {
        try {
            if (!mInitDoneSignal.await(INIT_WAIT_TIMEOUT, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Service " + TAG + " init timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Service " + TAG + " init interrupted", e);
        }
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
        setOemUnlockEnabledProperty(enabled);
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

    private void enforceConfigureFrpPermission() {
        if (mFrpEnforced && mContext.checkCallingOrSelfPermission(
                Manifest.permission.CONFIGURE_FACTORY_RESET_PROTECTION)
                == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException(("Can't configure Factory Reset Protection. Requires "
                    + "CONFIGURE_FACTORY_RESET_PROTECTION"));
        }
    }

    private void enforceUid(int callingUid) {
        if (callingUid != mAllowedUid && callingUid != UserHandle.AID_ROOT) {
            throw new SecurityException("uid " + callingUid + " not allowed to access PDB");
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

    @VisibleForTesting
    long getBlockDeviceSize() {
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
        long frpSecretSize = mFrpEnforced ? FRP_SECRET_MAGIC.length + FRP_SECRET_SIZE : 0;
        return (int) (getTestHarnessModeDataOffset() - DIGEST_SIZE_BYTES - HEADER_SIZE
                - frpSecretSize);
    }

    @VisibleForTesting
    long getFrpCredentialDataOffset() {
        return getOemUnlockDataOffset() - FRP_CREDENTIAL_RESERVED_SIZE;
    }

    @VisibleForTesting
    long getFrpSecretMagicOffset() {
        return getFrpSecretDataOffset() - FRP_SECRET_MAGIC.length;
    }

    @VisibleForTesting
    long getFrpSecretDataOffset() {
        return getTestHarnessModeDataOffset() - FRP_SECRET_SIZE;
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
        enforceFactoryResetProtectionInactive();
        return getBlockOutputChannelIgnoringFrp();
    }

    private FileChannel getBlockOutputChannelIgnoringFrp() throws FileNotFoundException {
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

        try (FileChannel channel = getBlockOutputChannelIgnoringFrp()) {
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
            if (mFrpEnforced) {
                buf = ByteBuffer.allocate(payload_size - TEST_MODE_RESERVED_SIZE
                        - FRP_SECRET_MAGIC.length - FRP_SECRET_SIZE - FRP_CREDENTIAL_RESERVED_SIZE
                        - 1);
            } else {
                buf = ByteBuffer.allocate(payload_size - TEST_MODE_RESERVED_SIZE
                        - FRP_CREDENTIAL_RESERVED_SIZE - 1);
            }
            channel.write(buf);
            channel.force(true);

            // 3. Write the default FRP secret (all zeros).
            if (mFrpEnforced) {
                Slog.i(TAG, "Writing FRP secret magic");
                channel.write(ByteBuffer.wrap(FRP_SECRET_MAGIC));

                Slog.i(TAG, "Writing default FRP secret");
                channel.write(ByteBuffer.allocate(FRP_SECRET_SIZE));
                channel.force(true);

                mFrpActive = false;
            }

            // 4. skip the test mode data and leave it unformatted.
            //    This is for a feature that enables testing.
            channel.position(channel.position() + TEST_MODE_RESERVED_SIZE);

            // 5. wipe the FRP_CREDENTIAL explicitly
            buf = ByteBuffer.allocate(FRP_CREDENTIAL_RESERVED_SIZE);
            channel.write(buf);
            channel.force(true);

            // 6. set unlock = 0 because it's a formatPartitionLocked
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

    /**
     * Try to deactivate FRP by presenting an FRP secret from the data partition, or the default
     * secret if the secret(s) on the data partition are not present or don't work.
     */
    @VisibleForTesting
    boolean automaticallyDeactivateFrpIfPossible() {
        synchronized (mLock) {
            if (deactivateFrpWithFileSecret(mFrpSecretFile)) {
                return true;
            }

            Slog.w(TAG, "Failed to deactivate with primary secret file, trying backup.");
            if (deactivateFrpWithFileSecret(mFrpSecretTmpFile)) {
                // The backup file has the FRP secret, make it the primary file.
                moveFrpTempFileToPrimary();
                return true;
            }

            Slog.w(TAG, "Failed to deactivate with backup secret file, trying default secret.");
            if (deactivateFrp(new byte[FRP_SECRET_SIZE])) {
                return true;
            }

            // We could not deactivate FRP.  It's possible that we have hit an obscure corner case,
            // a device that once ran a version of Android that set the FRP magic and a secret,
            // then downgraded to a version that did not know about FRP, wiping the FRP secrets
            // files, then upgraded to a version (the current one) that does know about FRP,
            // potentially leaving the user unable to deactivate FRP because all copies of the
            // secret are gone.
            //
            // To handle this case, we check to see if we have recently upgraded from a pre-V
            // version.  If so, we deactivate FRP and set the secret to the default value.
            if (isUpgradingFromPreVRelease()) {
                Slog.w(TAG, "Upgrading from Android 14 or lower, defaulting FRP secret");
                writeFrpMagicAndDefaultSecret();
                mFrpActive = false;
                setOldSettingForBackworkCompatibility(mFrpActive);
                return true;
            }

            Slog.e(TAG, "Did not find valid FRP secret, FRP remains active.");
            return false;
        }
    }

    private boolean deactivateFrpWithFileSecret(String frpSecretFile) {
        try {
            return deactivateFrp(Files.readAllBytes(Paths.get(frpSecretFile)));
        } catch (IOException e) {
            Slog.i(TAG, "Failed to read FRP secret file: " + frpSecretFile + " "
                    + e.getClass().getSimpleName());
            return false;
        }
    }

    private void moveFrpTempFileToPrimary() {
        try {
            Files.move(Paths.get(mFrpSecretTmpFile), Paths.get(mFrpSecretFile), REPLACE_EXISTING);
        } catch (IOException e) {
            Slog.e(TAG, "Error moving FRP backup file to primary (ignored)", e);
        }
    }

    @VisibleForTesting
    boolean isFrpActive() {
        synchronized (mLock) {
            // mFrpActive is initialized and automatic deactivation done (if possible) before the
            // service is published, so there's no chance that callers could ask for the state
            // before it has settled.
            return mFrpActive;
        }
    }

    /**
     * Write the provided secret to the FRP secret file in /data and to the persistent data block
     * partition.
     *
     * Writing is a three-step process, to ensure that we can recover from a crash at any point.
     */
    private boolean updateFrpSecret(byte[] secret) {
        // 1.  Write the new secret to a temporary file, and sync the write.
        try {
            Files.write(
                    Paths.get(mFrpSecretTmpFile), secret, WRITE, CREATE, TRUNCATE_EXISTING, SYNC);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write FRP secret file", e);
            return false;
        }

        // 2.  Write the new secret to /persist, and sync the write.
        if (!mInternalService.writeDataBuffer(getFrpSecretDataOffset(), ByteBuffer.wrap(secret))) {
            return false;
        }

        // 3.  Move the temporary file to the primary file location.  Syncing doesn't matter
        //     here.  In the event this update doesn't complete it will get done by
        //     #automaticallyDeactivateFrpIfPossible() during the next boot.
        moveFrpTempFileToPrimary();
        return true;
    }

    /**
     * Only for testing, activate FRP.
     */
    @VisibleForTesting
    void activateFrp() {
        synchronized (mLock) {
            mFrpActive = true;
            setOldSettingForBackworkCompatibility(mFrpActive);
        }
    }

    private boolean hasFrpSecretMagic() {
        final byte[] frpMagic =
                readDataBlock(getFrpSecretMagicOffset(), FRP_SECRET_MAGIC.length);
        if (frpMagic == null) {
            // Transient read error on the partition?
            Slog.e(TAG, "Failed to read FRP magic region.");
            return false;
        }
        return Arrays.equals(frpMagic, FRP_SECRET_MAGIC);
    }

    private byte[] getFrpSecret() {
        return readDataBlock(getFrpSecretDataOffset(), FRP_SECRET_SIZE);
    }

    private boolean deactivateFrp(byte[] secret) {
        if (secret == null || secret.length != FRP_SECRET_SIZE) {
            Slog.w(TAG, "Attempted to deactivate FRP with a null or incorrectly-sized secret");
            return false;
        }

        synchronized (mLock) {
            if (!hasFrpSecretMagic()) {
                Slog.i(TAG, "No FRP secret magic, system must have been upgraded.");
                writeFrpMagicAndDefaultSecret();
            }
        }

        final byte[] partitionSecret = getFrpSecret();
        if (partitionSecret == null || partitionSecret.length != FRP_SECRET_SIZE) {
            Slog.e(TAG, "Failed to read FRP secret from persistent data partition");
            return false;
        }

        // MessageDigest.isEqual is constant-time, to protect secret deduction by timing attack.
        if (MessageDigest.isEqual(secret, partitionSecret)) {
            mFrpActive = false;
            Slog.i(TAG, "FRP secret matched, FRP deactivated.");
            setOldSettingForBackworkCompatibility(mFrpActive);
            return true;
        } else {
            Slog.e(TAG,
                    "FRP deactivation failed with secret " + HexFormat.of().formatHex(secret));
            return false;
        }
    }

    private void writeFrpMagicAndDefaultSecret() {
        try (FileChannel channel = getBlockOutputChannelIgnoringFrp()) {
            synchronized (mLock) {
                Slog.i(TAG, "Writing default FRP secret");
                channel.position(getFrpSecretDataOffset());
                channel.write(ByteBuffer.allocate(FRP_SECRET_SIZE));
                channel.force(true);

                Slog.i(TAG, "Writing FRP secret magic");
                channel.position(getFrpSecretMagicOffset());
                channel.write(ByteBuffer.wrap(FRP_SECRET_MAGIC));
                channel.force(true);

                mFrpActive = false;
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write FRP magic and default secret", e);
        }
        computeAndWriteDigestLocked();
    }

    @VisibleForTesting
    byte[] readDataBlock(long offset, int length) {
        try (DataInputStream inputStream =
                     new DataInputStream(new FileInputStream(new File(mDataBlockFile)))) {
            synchronized (mLock) {
                inputStream.skip(offset);
                byte[] bytes = new byte[length];
                inputStream.readFully(bytes);
                return bytes;
            }
        } catch (IOException e) {
            throw new IllegalStateException("persistent partition not readable", e);
        }
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
            setOemUnlockEnabledProperty(enabled);
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
        final long frpSecretSize =
                mFrpEnforced ? (FRP_SECRET_MAGIC.length + FRP_SECRET_SIZE) : 0;
        final long actualSize = getBlockDeviceSize() - HEADER_SIZE - DIGEST_SIZE_BYTES
                - TEST_MODE_RESERVED_SIZE - frpSecretSize - FRP_CREDENTIAL_RESERVED_SIZE - 1;
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
        private int printFrpStatus(PrintWriter pw, boolean printSecrets) {
            enforceUid(Binder.getCallingUid());

            pw.println("FRP state");
            pw.println("=========");
            pw.println("Enforcement enabled: " + mFrpEnforced);
            pw.println("FRP state: " + mFrpActive);
            printFrpDataFilesContents(pw, printSecrets);
            printFrpSecret(pw, printSecrets);
            pw.println("OEM unlock state: " + getOemUnlockEnabled());
            pw.println("Bootloader lock state: " + getFlashLockState());
            pw.println("Verified boot state: " + getVerifiedBootState());
            pw.println("Has FRP credential handle: " + hasFrpCredentialHandle());
            pw.println("FRP challenge block size: " + getDataBlockSize());
            return 1;
        }

        private void printFrpSecret(PrintWriter pw, boolean printSecret) {
            if (hasFrpSecretMagic()) {
                if (printSecret) {
                    pw.println("FRP secret in PDB: " + HexFormat.of().formatHex(
                            readDataBlock(getFrpSecretDataOffset(), FRP_SECRET_SIZE)));
                } else {
                    pw.println("FRP secret present but omitted.");
                }
            } else {
                pw.println("FRP magic not found");
            }
        }

        private void printFrpDataFilesContents(PrintWriter pw, boolean printSecrets) {
            printFrpDataFileContents(pw, mFrpSecretFile, printSecrets);
            printFrpDataFileContents(pw, mFrpSecretTmpFile, printSecrets);
        }

        private void printFrpDataFileContents(
                PrintWriter pw, String frpSecretFile, boolean printSecret) {
            if (Files.exists(Paths.get(frpSecretFile))) {
                if (printSecret) {
                    try {
                        pw.println("FRP secret in " + frpSecretFile + ": " + HexFormat.of()
                                .formatHex(Files.readAllBytes(Paths.get(frpSecretFile))));
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to read " + frpSecretFile, e);
                    }
                } else {
                    pw.println(
                            "FRP secret file " + frpSecretFile + " exists, contents omitted.");
                }
            }
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            if (!mFrpEnforced) {
                super.onShellCommand(in, out, err, args, callback, resultReceiver);
                return;
            }
            new ShellCommand(){
                @Override
                public int onCommand(final String cmd) {
                    if (cmd == null) {
                        return handleDefaultCommands(cmd);
                    }

                    final PrintWriter pw = getOutPrintWriter();
                    return switch (cmd) {
                        case "status" -> printFrpStatus(pw, /* printSecrets */ !mFrpActive);
                        case "activate" -> {
                            activateFrp();
                            yield printFrpStatus(pw, /* printSecrets */ !mFrpActive);
                        }

                        case "deactivate" -> {
                            byte[] secret = hashSecretString(getNextArg());
                            pw.println("Attempting to deactivate with: " + HexFormat.of().formatHex(
                                    secret));
                            pw.println("Deactivation "
                                    + (deactivateFrp(secret) ? "succeeded" : "failed"));
                            yield printFrpStatus(pw, /* printSecrets */ !mFrpActive);
                        }

                        case "auto_deactivate" -> {
                            boolean result = automaticallyDeactivateFrpIfPossible();
                            pw.println(
                                    "Automatic deactivation " + (result ? "succeeded" : "failed"));
                            yield printFrpStatus(pw, /* printSecrets */ !mFrpActive);
                        }

                        case "set_secret" -> {
                            byte[] secret = new byte[FRP_SECRET_SIZE];
                            String secretString = getNextArg();
                            if (!secretString.equals("default")) {
                                secret = hashSecretString(secretString);
                            }
                            pw.println("Setting FRP secret to: " + HexFormat.of()
                                    .formatHex(secret) + " length: " + secret.length);
                            setFactoryResetProtectionSecret(secret);
                            yield printFrpStatus(pw, /* printSecrets */ !mFrpActive);
                        }

                        default -> handleDefaultCommands(cmd);
                    };
                }

                @Override
                public void onHelp() {
                    final PrintWriter pw = getOutPrintWriter();
                    pw.println("Commands");
                    pw.println("status: Print the FRP state and associated information.");
                    pw.println("activate:  Put FRP into \"active\" mode.");
                    pw.println("deactivate <secret>:  Deactivate with a hash of 'secret'.");
                    pw.println("auto_deactivate: Deactivate with the stored secret or the default");
                    pw.println("set_secret <secret>:  Set the stored secret to a hash of `secret`");
                }

                private static byte[] hashSecretString(String secretInput) {
                    try {
                        // SHA-256 produces 32-byte outputs, same as the FRP secret size, so it's
                        // a convenient way to "normalize" the length of whatever the user provided.
                        // Also, hashing makes it difficult for an attacker to set the secret to a
                        // known value that was randomly generated.
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        return md.digest(secretInput.getBytes());
                    } catch (NoSuchAlgorithmException e) {
                        Slog.e(TAG, "Can't happen", e);
                        return new byte[FRP_SECRET_SIZE];
                    }
                }
            }.exec(this, in, out, err, args, callback, resultReceiver);
        }

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
            enforceFactoryResetProtectionInactive();
            enforceOemUnlockWritePermission();

            synchronized (mLock) {
                int ret;
                if (mIsFileBacked) {
                    try {
                        Files.write(Paths.get(mDataBlockFile), new byte[MAX_DATA_BLOCK_SIZE],
                                TRUNCATE_EXISTING);
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

        private static String getVerifiedBootState() {
            return SystemProperties.get(VERIFIED_BOOT_STATE);
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

        private void enforceConfigureFrpPermissionOrPersistentDataBlockAccess() {
            if (!mFrpEnforced) {
                enforcePersistentDataBlockAccess();
            } else {
                if (mContext.checkCallingOrSelfPermission(
                        Manifest.permission.CONFIGURE_FACTORY_RESET_PROTECTION)
                        == PackageManager.PERMISSION_DENIED) {
                    enforcePersistentDataBlockAccess();
                }
            }
        }

        @Override
        public long getMaximumDataBlockSize() {
            enforceUid(Binder.getCallingUid());
            return doGetMaximumDataBlockSize();
        }

        @Override
        public boolean hasFrpCredentialHandle() {
            enforceConfigureFrpPermissionOrPersistentDataBlockAccess();
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
            printFrpStatus(pw, /* printSecrets */ false);
        }

        @Override
        public boolean isFactoryResetProtectionActive() {
            return isFrpActive();
        }

        @Override
        public boolean deactivateFactoryResetProtection(byte[] secret) {
            enforceConfigureFrpPermission();
            return deactivateFrp(secret);
        }

        @Override
        public boolean setFactoryResetProtectionSecret(byte[] secret) {
            enforceConfigureFrpPermission();
            enforceUid(Binder.getCallingUid());
            if (secret == null || secret.length != FRP_SECRET_SIZE) {
                throw new IllegalArgumentException(
                        "Invalid FRP secret: " + HexFormat.of().formatHex(secret));
            }
            enforceFactoryResetProtectionInactive();
            return updateFrpSecret(secret);
        }
    };

    private void enforceFactoryResetProtectionInactive() {
        if (mFrpEnforced && isFrpActive()) {
            Slog.w(TAG, "Attempt to update PDB was blocked because FRP is active.");
            throw new SecurityException("FRP is active");
        }
    }

    @VisibleForTesting
    boolean isUpgradingFromPreVRelease() {
        PackageManagerInternal packageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        if (packageManagerInternal == null) {
            Slog.e(TAG, "Unable to retrieve PackageManagerInternal");
            return false;
        }

        return packageManagerInternal
                .isUpgradingFromLowerThan(Build.VERSION_CODES.VANILLA_ICE_CREAM);
    }

    private InternalService mInternalService = new InternalService();

    private class InternalService implements PersistentDataBlockManagerInternal {
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

        @Override
        public boolean deactivateFactoryResetProtectionWithoutSecret() {
            synchronized (mLock) {
                mFrpActive = false;
                setOldSettingForBackworkCompatibility(/* isActive */ mFrpActive);
            }
            return true;
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

        private boolean writeDataBuffer(long offset, ByteBuffer dataBuffer) {
            synchronized (mLock) {
                if (!mIsWritable) {
                    return false;
                }
                try (FileChannel channel = getBlockOutputChannel()) {
                    channel.position(offset);
                    channel.write(dataBuffer);
                    channel.force(true);
                } catch (IOException e) {
                    Slog.e(TAG, "unable to access persistent partition", e);
                    return false;
                }

                return computeAndWriteDigestLocked();
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
    }
}
