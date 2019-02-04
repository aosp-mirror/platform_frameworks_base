/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageStats;
import android.os.Build;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInstalld;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

import dalvik.system.BlockGuard;
import dalvik.system.VMRuntime;

import java.io.FileDescriptor;

public class Installer extends SystemService {
    private static final String TAG = "Installer";

    /* ***************************************************************************
     * IMPORTANT: These values are passed to native code. Keep them in sync with
     * frameworks/native/cmds/installd/installd_constants.h
     * **************************************************************************/
    /** Application should be visible to everyone */
    public static final int DEXOPT_PUBLIC         = 1 << 1;
    /** Application wants to allow debugging of its code */
    public static final int DEXOPT_DEBUGGABLE     = 1 << 2;
    /** The system boot has finished */
    public static final int DEXOPT_BOOTCOMPLETE   = 1 << 3;
    /** Hint that the dexopt type is profile-guided. */
    public static final int DEXOPT_PROFILE_GUIDED = 1 << 4;
    /** The compilation is for a secondary dex file. */
    public static final int DEXOPT_SECONDARY_DEX  = 1 << 5;
    /** Ignore the result of dexoptNeeded and force compilation. */
    public static final int DEXOPT_FORCE          = 1 << 6;
    /** Indicates that the dex file passed to dexopt in on CE storage. */
    public static final int DEXOPT_STORAGE_CE     = 1 << 7;
    /** Indicates that the dex file passed to dexopt in on DE storage. */
    public static final int DEXOPT_STORAGE_DE     = 1 << 8;
    /** Indicates that dexopt is invoked from the background service. */
    public static final int DEXOPT_IDLE_BACKGROUND_JOB = 1 << 9;
    /** Indicates that dexopt should restrict access to private APIs. */
    public static final int DEXOPT_ENABLE_HIDDEN_API_CHECKS = 1 << 10;
    /** Indicates that dexopt should convert to CompactDex. */
    public static final int DEXOPT_GENERATE_COMPACT_DEX = 1 << 11;
    /** Indicates that dexopt should generate an app image */
    public static final int DEXOPT_GENERATE_APP_IMAGE = 1 << 12;

    public static final int FLAG_STORAGE_DE = IInstalld.FLAG_STORAGE_DE;
    public static final int FLAG_STORAGE_CE = IInstalld.FLAG_STORAGE_CE;

    public static final int FLAG_CLEAR_CACHE_ONLY = IInstalld.FLAG_CLEAR_CACHE_ONLY;
    public static final int FLAG_CLEAR_CODE_CACHE_ONLY = IInstalld.FLAG_CLEAR_CODE_CACHE_ONLY;

    public static final int FLAG_FREE_CACHE_V2 = IInstalld.FLAG_FREE_CACHE_V2;
    public static final int FLAG_FREE_CACHE_V2_DEFY_QUOTA = IInstalld.FLAG_FREE_CACHE_V2_DEFY_QUOTA;
    public static final int FLAG_FREE_CACHE_NOOP = IInstalld.FLAG_FREE_CACHE_NOOP;

    public static final int FLAG_USE_QUOTA = IInstalld.FLAG_USE_QUOTA;
    public static final int FLAG_FORCE = IInstalld.FLAG_FORCE;

    private final boolean mIsolated;

    private volatile IInstalld mInstalld;
    private volatile Object mWarnIfHeld;

    public Installer(Context context) {
        this(context, false);
    }

    /**
     * @param isolated indicates if this object should <em>not</em> connect to
     *            the real {@code installd}. All remote calls will be ignored
     *            unless you extend this class and intercept them.
     */
    public Installer(Context context, boolean isolated) {
        super(context);
        mIsolated = isolated;
    }

    /**
     * Yell loudly if someone tries making future calls while holding a lock on
     * the given object.
     */
    public void setWarnIfHeld(Object warnIfHeld) {
        mWarnIfHeld = warnIfHeld;
    }

    @Override
    public void onStart() {
        if (mIsolated) {
            mInstalld = null;
        } else {
            connect();
        }
    }

    private void connect() {
        IBinder binder = ServiceManager.getService("installd");
        if (binder != null) {
            try {
                binder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Slog.w(TAG, "installd died; reconnecting");
                        connect();
                    }
                }, 0);
            } catch (RemoteException e) {
                binder = null;
            }
        }

        if (binder != null) {
            mInstalld = IInstalld.Stub.asInterface(binder);
            try {
                invalidateMounts();
            } catch (InstallerException ignored) {
            }
        } else {
            Slog.w(TAG, "installd not found; trying again");
            BackgroundThread.getHandler().postDelayed(() -> {
                connect();
            }, DateUtils.SECOND_IN_MILLIS);
        }
    }

    /**
     * Do several pre-flight checks before making a remote call.
     *
     * @return if the remote call should continue.
     */
    private boolean checkBeforeRemote() {
        if (mWarnIfHeld != null && Thread.holdsLock(mWarnIfHeld)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding 0x"
                    + Integer.toHexString(System.identityHashCode(mWarnIfHeld)), new Throwable());
        }
        if (mIsolated) {
            Slog.i(TAG, "Ignoring request because this installer is isolated");
            return false;
        } else {
            return true;
        }
    }

    public long createAppData(String uuid, String packageName, int userId, int flags, int appId,
            String seInfo, int targetSdkVersion) throws InstallerException {
        if (!checkBeforeRemote()) return -1;
        try {
            return mInstalld.createAppData(uuid, packageName, userId, flags, appId, seInfo,
                    targetSdkVersion);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void restoreconAppData(String uuid, String packageName, int userId, int flags, int appId,
            String seInfo) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.restoreconAppData(uuid, packageName, userId, flags, appId, seInfo);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void migrateAppData(String uuid, String packageName, int userId, int flags)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.migrateAppData(uuid, packageName, userId, flags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void clearAppData(String uuid, String packageName, int userId, int flags,
            long ceDataInode) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.clearAppData(uuid, packageName, userId, flags, ceDataInode);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void destroyAppData(String uuid, String packageName, int userId, int flags,
            long ceDataInode) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.destroyAppData(uuid, packageName, userId, flags, ceDataInode);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void fixupAppData(String uuid, int flags) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.fixupAppData(uuid, flags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void moveCompleteApp(String fromUuid, String toUuid, String packageName,
            String dataAppName, int appId, String seInfo, int targetSdkVersion)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.moveCompleteApp(fromUuid, toUuid, packageName, dataAppName, appId, seInfo,
                    targetSdkVersion);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void getAppSize(String uuid, String[] packageNames, int userId, int flags, int appId,
            long[] ceDataInodes, String[] codePaths, PackageStats stats)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        if (codePaths != null) {
            for (String codePath : codePaths) {
                BlockGuard.getVmPolicy().onPathAccess(codePath);
            }
        }
        try {
            final long[] res = mInstalld.getAppSize(uuid, packageNames, userId, flags,
                    appId, ceDataInodes, codePaths);
            stats.codeSize += res[0];
            stats.dataSize += res[1];
            stats.cacheSize += res[2];
            stats.externalCodeSize += res[3];
            stats.externalDataSize += res[4];
            stats.externalCacheSize += res[5];
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void getUserSize(String uuid, int userId, int flags, int[] appIds, PackageStats stats)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            final long[] res = mInstalld.getUserSize(uuid, userId, flags, appIds);
            stats.codeSize += res[0];
            stats.dataSize += res[1];
            stats.cacheSize += res[2];
            stats.externalCodeSize += res[3];
            stats.externalDataSize += res[4];
            stats.externalCacheSize += res[5];
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public long[] getExternalSize(String uuid, int userId, int flags, int[] appIds)
            throws InstallerException {
        if (!checkBeforeRemote()) return new long[6];
        try {
            return mInstalld.getExternalSize(uuid, userId, flags, appIds);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void setAppQuota(String uuid, int userId, int appId, long cacheQuota)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.setAppQuota(uuid, userId, appId, cacheQuota);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void dexopt(String apkPath, int uid, @Nullable String pkgName, String instructionSet,
            int dexoptNeeded, @Nullable String outputPath, int dexFlags,
            String compilerFilter, @Nullable String volumeUuid, @Nullable String sharedLibraries,
            @Nullable String seInfo, boolean downgrade, int targetSdkVersion,
            @Nullable String profileName, @Nullable String dexMetadataPath,
            @Nullable String compilationReason) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        BlockGuard.getVmPolicy().onPathAccess(apkPath);
        BlockGuard.getVmPolicy().onPathAccess(outputPath);
        BlockGuard.getVmPolicy().onPathAccess(dexMetadataPath);
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.dexopt(apkPath, uid, pkgName, instructionSet, dexoptNeeded, outputPath,
                    dexFlags, compilerFilter, volumeUuid, sharedLibraries, seInfo, downgrade,
                    targetSdkVersion, profileName, dexMetadataPath, compilationReason);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean mergeProfiles(int uid, String packageName, String profileName)
            throws InstallerException {
        if (!checkBeforeRemote()) return false;
        try {
            return mInstalld.mergeProfiles(uid, packageName, profileName);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean dumpProfiles(int uid, String packageName, String profileName, String codePath)
            throws InstallerException {
        if (!checkBeforeRemote()) return false;
        BlockGuard.getVmPolicy().onPathAccess(codePath);
        try {
            return mInstalld.dumpProfiles(uid, packageName, profileName, codePath);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean copySystemProfile(String systemProfile, int uid, String packageName,
                String profileName) throws InstallerException {
        if (!checkBeforeRemote()) return false;
        try {
            return mInstalld.copySystemProfile(systemProfile, uid, packageName, profileName);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void idmap(String targetApkPath, String overlayApkPath, int uid)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(targetApkPath);
        BlockGuard.getVmPolicy().onPathAccess(overlayApkPath);
        try {
            mInstalld.idmap(targetApkPath, overlayApkPath, uid);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void removeIdmap(String overlayApkPath) throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(overlayApkPath);
        try {
            mInstalld.removeIdmap(overlayApkPath);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void rmdex(String codePath, String instructionSet) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(codePath);
        try {
            mInstalld.rmdex(codePath, instructionSet);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void rmPackageDir(String packageDir) throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(packageDir);
        try {
            mInstalld.rmPackageDir(packageDir);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void clearAppProfiles(String packageName, String profileName) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.clearAppProfiles(packageName, profileName);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void destroyAppProfiles(String packageName) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.destroyAppProfiles(packageName);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void createUserData(String uuid, int userId, int userSerial, int flags)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.createUserData(uuid, userId, userSerial, flags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void destroyUserData(String uuid, int userId, int flags) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.destroyUserData(uuid, userId, flags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void markBootComplete(String instructionSet) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.markBootComplete(instructionSet);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void freeCache(String uuid, long targetFreeBytes, long cacheReservedBytes, int flags)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.freeCache(uuid, targetFreeBytes, cacheReservedBytes, flags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Links the 32 bit native library directory in an application's data
     * directory to the real location for backward compatibility. Note that no
     * such symlink is created for 64 bit shared libraries.
     */
    public void linkNativeLibraryDirectory(String uuid, String packageName, String nativeLibPath32,
            int userId) throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(nativeLibPath32);
        try {
            mInstalld.linkNativeLibraryDirectory(uuid, packageName, nativeLibPath32, userId);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void createOatDir(String oatDir, String dexInstructionSet)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.createOatDir(oatDir, dexInstructionSet);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void linkFile(String relativePath, String fromBase, String toBase)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(fromBase);
        BlockGuard.getVmPolicy().onPathAccess(toBase);
        try {
            mInstalld.linkFile(relativePath, fromBase, toBase);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void moveAb(String apkPath, String instructionSet, String outputPath)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(apkPath);
        BlockGuard.getVmPolicy().onPathAccess(outputPath);
        try {
            mInstalld.moveAb(apkPath, instructionSet, outputPath);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void deleteOdex(String apkPath, String instructionSet, String outputPath)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(apkPath);
        BlockGuard.getVmPolicy().onPathAccess(outputPath);
        try {
            mInstalld.deleteOdex(apkPath, instructionSet, outputPath);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void installApkVerity(String filePath, FileDescriptor verityInput, int contentSize)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(filePath);
        try {
            mInstalld.installApkVerity(filePath, verityInput, contentSize);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void assertFsverityRootHashMatches(String filePath, @NonNull byte[] expectedHash)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(filePath);
        try {
            mInstalld.assertFsverityRootHashMatches(filePath, expectedHash);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean reconcileSecondaryDexFile(String apkPath, String packageName, int uid,
            String[] isas, @Nullable String volumeUuid, int flags) throws InstallerException {
        for (int i = 0; i < isas.length; i++) {
            assertValidInstructionSet(isas[i]);
        }
        if (!checkBeforeRemote()) return false;
        BlockGuard.getVmPolicy().onPathAccess(apkPath);
        try {
            return mInstalld.reconcileSecondaryDexFile(apkPath, packageName, uid, isas,
                    volumeUuid, flags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public byte[] hashSecondaryDexFile(String dexPath, String packageName, int uid,
            @Nullable String volumeUuid, int flags) throws InstallerException {
        if (!checkBeforeRemote()) return new byte[0];
        BlockGuard.getVmPolicy().onPathAccess(dexPath);
        try {
            return mInstalld.hashSecondaryDexFile(dexPath, packageName, uid, volumeUuid, flags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean createProfileSnapshot(int appId, String packageName, String profileName,
            String classpath) throws InstallerException {
        if (!checkBeforeRemote()) return false;
        try {
            return mInstalld.createProfileSnapshot(appId, packageName, profileName, classpath);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void destroyProfileSnapshot(String packageName, String profileName)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.destroyProfileSnapshot(packageName, profileName);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void invalidateMounts() throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.invalidateMounts();
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean isQuotaSupported(String volumeUuid) throws InstallerException {
        if (!checkBeforeRemote()) return false;
        try {
            return mInstalld.isQuotaSupported(volumeUuid);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean prepareAppProfile(String pkg, @UserIdInt int userId, @AppIdInt int appId,
            String profileName, String codePath, String dexMetadataPath) throws InstallerException {
        if (!checkBeforeRemote()) return false;
        BlockGuard.getVmPolicy().onPathAccess(codePath);
        BlockGuard.getVmPolicy().onPathAccess(dexMetadataPath);
        try {
            return mInstalld.prepareAppProfile(pkg, userId, appId, profileName, codePath,
                    dexMetadataPath);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Snapshots user data of the given package.
     *
     * @param pkg name of the package to snapshot user data for.
     * @param userId id of the user whose data to snapshot.
     * @param snapshotId id of this snapshot.
     * @param storageFlags flags controlling which data (CE or DE) to snapshot.
     *
     * @return inode of the snapshot of users CE package data, or {@code 0} if a remote calls
     *  shouldn't be continued. See {@link #checkBeforeRemote}.
     *
     * @throws InstallerException if failed to snapshot user data.
     */
    public long snapshotAppData(String pkg, @UserIdInt int userId, int snapshotId, int storageFlags)
            throws InstallerException {
        if (!checkBeforeRemote()) return 0;

        try {
            return mInstalld.snapshotAppData(null, pkg, userId, snapshotId, storageFlags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Restores user data snapshot of the given package.
     *
     * @param pkg name of the package to restore user data for.
     * @param appId id of the package to restore user data for.
     * @param userId id of the user whose data to restore.
     * @param snapshotId id of the snapshot to restore.
     * @param storageFlags flags controlling which data (CE or DE) to restore.
     *
     * @return {@code true} if user data restore was successful, or {@code false} if a remote call
     *  shouldn't be continued. See {@link #checkBeforeRemote}.
     *
     * @throws InstallerException if failed to restore user data.
     */
    public boolean restoreAppDataSnapshot(String pkg, @AppIdInt  int appId, String seInfo,
            @UserIdInt int userId, int snapshotId, int storageFlags) throws InstallerException {
        if (!checkBeforeRemote()) return false;

        try {
            mInstalld.restoreAppDataSnapshot(null, pkg, appId, seInfo, userId, snapshotId,
                    storageFlags);
            return true;
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Deletes user data snapshot of the given package.
     *
     * @param pkg name of the package to delete user data snapshot for.
     * @param userId id of the user whose user data snapshot to delete.
     * @param ceSnapshotInode inode of CE user data snapshot.
     * @param snapshotId id of the snapshot to delete.
     * @param storageFlags flags controlling which user data snapshot (CE or DE) to delete.
     *
     * @return {@code true} if user data snapshot was successfully deleted, or {@code false} if a
     *  remote call shouldn't be continued. See {@link #checkBeforeRemote}.
     *
     * @throws InstallerException if failed to delete user data snapshot.
     */
    public boolean destroyAppDataSnapshot(String pkg, @UserIdInt int userId, long ceSnapshotInode,
            int snapshotId, int storageFlags) throws InstallerException {
        if (!checkBeforeRemote()) return false;

        try {
            mInstalld.destroyAppDataSnapshot(null, pkg, userId, ceSnapshotInode, snapshotId,
                    storageFlags);
            return true;
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    private static void assertValidInstructionSet(String instructionSet)
            throws InstallerException {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (VMRuntime.getInstructionSet(abi).equals(instructionSet)) {
                return;
            }
        }
        throw new InstallerException("Invalid instruction set: " + instructionSet);
    }

    public boolean compileLayouts(String apkPath, String packageName, String outDexFile, int uid) {
        try {
            return mInstalld.compileLayouts(apkPath, packageName, outDexFile, uid);
        } catch (RemoteException e) {
            return false;
        }
    }

    public static class InstallerException extends Exception {
        public InstallerException(String detailMessage) {
            super(detailMessage);
        }

        public static InstallerException from(Exception e) throws InstallerException {
            throw new InstallerException(e.toString());
        }
    }
}
