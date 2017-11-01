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

import android.annotation.Nullable;
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

import dalvik.system.VMRuntime;

public class Installer extends SystemService {
    private static final String TAG = "Installer";

    /* ***************************************************************************
     * IMPORTANT: These values are passed to native code. Keep them in sync with
     * frameworks/native/cmds/installd/installd.h
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

    // NOTE: keep in sync with installd
    public static final int FLAG_CLEAR_CACHE_ONLY = 1 << 8;
    public static final int FLAG_CLEAR_CODE_CACHE_ONLY = 1 << 9;
    public static final int FLAG_USE_QUOTA = 1 << 12;
    public static final int FLAG_FREE_CACHE_V2 = 1 << 13;
    public static final int FLAG_FREE_CACHE_V2_DEFY_QUOTA = 1 << 14;
    public static final int FLAG_FREE_CACHE_NOOP = 1 << 15;
    public static final int FLAG_FORCE = 1 << 16;

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
            @Nullable String seInfo, boolean downgrade)
            throws InstallerException {
        assertValidInstructionSet(instructionSet);
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.dexopt(apkPath, uid, pkgName, instructionSet, dexoptNeeded, outputPath,
                    dexFlags, compilerFilter, volumeUuid, sharedLibraries, seInfo, downgrade);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean mergeProfiles(int uid, String packageName) throws InstallerException {
        if (!checkBeforeRemote()) return false;
        try {
            return mInstalld.mergeProfiles(uid, packageName);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean dumpProfiles(int uid, String packageName, String codePaths)
            throws InstallerException {
        if (!checkBeforeRemote()) return false;
        try {
            return mInstalld.dumpProfiles(uid, packageName, codePaths);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public boolean copySystemProfile(String systemProfile, int uid, String packageName)
            throws InstallerException {
        if (!checkBeforeRemote()) return false;
        try {
            return mInstalld.copySystemProfile(systemProfile, uid, packageName);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void idmap(String targetApkPath, String overlayApkPath, int uid)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.idmap(targetApkPath, overlayApkPath, uid);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void removeIdmap(String overlayApkPath) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.removeIdmap(overlayApkPath);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void rmdex(String codePath, String instructionSet) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.rmdex(codePath, instructionSet);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void rmPackageDir(String packageDir) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.rmPackageDir(packageDir);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void clearAppProfiles(String packageName) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.clearAppProfiles(packageName);
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
        try {
            mInstalld.linkFile(relativePath, fromBase, toBase);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void moveAb(String apkPath, String instructionSet, String outputPath)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.moveAb(apkPath, instructionSet, outputPath);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void deleteOdex(String apkPath, String instructionSet, String outputPath)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.deleteOdex(apkPath, instructionSet, outputPath);
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
        try {
            return mInstalld.reconcileSecondaryDexFile(apkPath, packageName, uid, isas,
                    volumeUuid, flags);
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

    private static void assertValidInstructionSet(String instructionSet)
            throws InstallerException {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (VMRuntime.getInstructionSet(abi).equals(instructionSet)) {
                return;
            }
        }
        throw new InstallerException("Invalid instruction set: " + instructionSet);
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
