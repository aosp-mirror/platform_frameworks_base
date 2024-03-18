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
import android.os.Binder;
import android.os.Build;
import android.os.CreateAppDataArgs;
import android.os.CreateAppDataResult;
import android.os.IBinder;
import android.os.IInstalld;
import android.os.ParcelFileDescriptor;
import android.os.ReconcileSdkDataArgs;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.CrateMetadata;
import android.text.format.DateUtils;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.EventLogTags;
import com.android.server.SystemService;

import dalvik.system.BlockGuard;
import dalvik.system.VMRuntime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    /** Indicates that dexopt may be run with different performance / priority tuned for restore */
    public static final int DEXOPT_FOR_RESTORE = 1 << 13; // TODO(b/135202722): remove

    /** The result of the profile analysis indicating that the app should be optimized. */
    public static final int PROFILE_ANALYSIS_OPTIMIZE = 1;
    /** The result of the profile analysis indicating that the app should not be optimized. */
    public static final int PROFILE_ANALYSIS_DONT_OPTIMIZE_SMALL_DELTA = 2;
    /**
     * The result of the profile analysis indicating that the app should not be optimized because
     * the profiles are empty.
     */
    public static final int PROFILE_ANALYSIS_DONT_OPTIMIZE_EMPTY_PROFILES = 3;

    public static final int FLAG_STORAGE_DE = IInstalld.FLAG_STORAGE_DE;
    public static final int FLAG_STORAGE_CE = IInstalld.FLAG_STORAGE_CE;
    public static final int FLAG_STORAGE_EXTERNAL = IInstalld.FLAG_STORAGE_EXTERNAL;
    public static final int FLAG_STORAGE_SDK = IInstalld.FLAG_STORAGE_SDK;

    public static final int FLAG_CLEAR_CACHE_ONLY = IInstalld.FLAG_CLEAR_CACHE_ONLY;
    public static final int FLAG_CLEAR_CODE_CACHE_ONLY = IInstalld.FLAG_CLEAR_CODE_CACHE_ONLY;

    public static final int FLAG_FREE_CACHE_V2 = IInstalld.FLAG_FREE_CACHE_V2;
    public static final int FLAG_FREE_CACHE_V2_DEFY_QUOTA = IInstalld.FLAG_FREE_CACHE_V2_DEFY_QUOTA;
    public static final int FLAG_FREE_CACHE_NOOP = IInstalld.FLAG_FREE_CACHE_NOOP;
    public static final int FLAG_FREE_CACHE_DEFY_TARGET_FREE_BYTES =
            IInstalld.FLAG_FREE_CACHE_DEFY_TARGET_FREE_BYTES;

    public static final int FLAG_USE_QUOTA = IInstalld.FLAG_USE_QUOTA;
    public static final int FLAG_FORCE = IInstalld.FLAG_FORCE;

    public static final int FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES =
            IInstalld.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES;

    private static final long CONNECT_RETRY_DELAY_MS = DateUtils.SECOND_IN_MILLIS;
    private static final long CONNECT_WAIT_MS = 10 * DateUtils.SECOND_IN_MILLIS;

    private final boolean mIsolated;
    private volatile boolean mDeferSetFirstBoot;
    private volatile IInstalld mInstalld = null;
    private volatile CountDownLatch mInstalldLatch = new CountDownLatch(1);
    private volatile Object mWarnIfHeld;

    public Installer(Context context) {
        this(context, false);
    }

    /**
     * @param isolated Make the installer isolated. See {@link isIsolated}.
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

    /**
     * Returns true if the installer is isolated, i.e. if this object should <em>not</em> connect to
     * the real {@code installd}. All remote calls will be ignored unless you extend this class and
     * intercept them.
     */
    public boolean isIsolated() {
        return mIsolated;
    }

    @Override
    public void onStart() {
        if (mIsolated) {
            mInstalld = null;
            mInstalldLatch.countDown();
        } else {
            connect();
        }
    }

    private void connect() {
        IBinder binder = ServiceManager.getService("installd");
        if (binder != null) {
            try {
                binder.linkToDeath(() -> {
                    Slog.w(TAG, "installd died; reconnecting");
                    mInstalldLatch = new CountDownLatch(1);
                    connect();
                }, 0);
            } catch (RemoteException e) {
                binder = null;
            }
        }

        if (binder != null) {
            IInstalld installd = IInstalld.Stub.asInterface(binder);
            mInstalld = installd;
            mInstalldLatch.countDown();
            try {
                invalidateMounts();
                executeDeferredActions();
            } catch (InstallerException ignored) {
            }
        } else {
            Slog.w(TAG, "installd not found; trying again");
            BackgroundThread.getHandler().postDelayed(this::connect, CONNECT_RETRY_DELAY_MS);
        }
    }

    /**
     * Perform any deferred actions on mInstalld while the connection could not be made.
     */
    private void executeDeferredActions() throws InstallerException {
        if (mDeferSetFirstBoot) {
            setFirstBoot();
        }
    }

    /**
     * Do several pre-flight checks before making a remote call.
     *
     * @return if the remote call should continue.
     */
    private boolean checkBeforeRemote() throws InstallerException {
        if (mWarnIfHeld != null && Thread.holdsLock(mWarnIfHeld)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding 0x"
                    + Integer.toHexString(System.identityHashCode(mWarnIfHeld)), new Throwable());
        }
        if (mIsolated) {
            Slog.i(TAG, "Ignoring request because this installer is isolated");
            return false;
        }

        try {
            if (!mInstalldLatch.await(CONNECT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new InstallerException("time out waiting for the installer to be ready");
            }
        } catch (InterruptedException e) {
            // Do nothing.
        }

        return true;
    }

    // We explicitly do NOT set previousAppId because the default value should always be 0.
    // Manually override previousAppId after building CreateAppDataArgs for specific behaviors.
    static CreateAppDataArgs buildCreateAppDataArgs(String uuid, String packageName,
            int userId, int flags, int appId, String seInfo, int targetSdkVersion,
            boolean usesSdk) {
        final CreateAppDataArgs args = new CreateAppDataArgs();
        args.uuid = uuid;
        args.packageName = packageName;
        args.userId = userId;
        args.flags = flags;
        if (usesSdk) {
            args.flags |= FLAG_STORAGE_SDK;
        }
        args.appId = appId;
        args.seInfo = seInfo;
        args.targetSdkVersion = targetSdkVersion;
        return args;
    }

    private static CreateAppDataResult buildPlaceholderCreateAppDataResult() {
        final CreateAppDataResult result = new CreateAppDataResult();
        result.ceDataInode = -1;
        result.deDataInode = -1;
        result.exceptionCode = 0;
        result.exceptionMessage = null;
        return result;
    }

    static ReconcileSdkDataArgs buildReconcileSdkDataArgs(String uuid, String packageName,
            List<String> subDirNames, int userId, int appId,
            String seInfo, int flags) {
        final ReconcileSdkDataArgs args = new ReconcileSdkDataArgs();
        args.uuid = uuid;
        args.packageName = packageName;
        args.subDirNames = subDirNames;
        args.userId = userId;
        args.appId = appId;
        args.previousAppId = 0;
        args.seInfo = seInfo;
        args.flags = flags;
        return args;
    }

    public @NonNull CreateAppDataResult createAppData(@NonNull CreateAppDataArgs args)
            throws InstallerException {
        if (!checkBeforeRemote()) {
            return buildPlaceholderCreateAppDataResult();
        }
        // Hardcode previousAppId to 0 to disable any data migration (http://b/221088088)
        args.previousAppId = 0;
        try {
            return mInstalld.createAppData(args);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public @NonNull CreateAppDataResult[] createAppDataBatched(@NonNull CreateAppDataArgs[] args)
            throws InstallerException {
        if (!checkBeforeRemote()) {
            final CreateAppDataResult[] results = new CreateAppDataResult[args.length];
            Arrays.fill(results, buildPlaceholderCreateAppDataResult());
            return results;
        }
        // Hardcode previousAppId to 0 to disable any data migration (http://b/221088088)
        for (final CreateAppDataArgs arg : args) {
            arg.previousAppId = 0;
        }
        try {
            return mInstalld.createAppDataBatched(args);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    void reconcileSdkData(@NonNull ReconcileSdkDataArgs args)
            throws InstallerException {
        if (!checkBeforeRemote()) {
            return;
        }
        try {
            mInstalld.reconcileSdkData(args);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Sets in Installd that it is first boot after data wipe
     */
    public void setFirstBoot() throws InstallerException {
        if (!checkBeforeRemote()) {
            return;
        }
        try {
            // mInstalld might be null if the connection could not be established.
            if (mInstalld != null) {
                mInstalld.setFirstBoot();
            } else {
                // if it is null while trying to set the first boot, set a flag to try and set the
                // first boot when the connection is eventually established
                mDeferSetFirstBoot = true;
            }
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Class that collects multiple {@code installd} operations together in an
     * attempt to more efficiently execute them in bulk.
     * <p>
     * Instead of returning results immediately, {@link CompletableFuture}
     * instances are returned which can be used to chain follow-up work for each
     * request.
     * <p>
     * The creator of this object <em>must</em> invoke {@link #execute()}
     * exactly once to begin execution of all pending operations. Once execution
     * has been kicked off, no additional events can be enqueued into this
     * instance, but multiple instances can safely exist in parallel.
     */
    public static class Batch {
        private static final int CREATE_APP_DATA_BATCH_SIZE = 256;

        private boolean mExecuted;

        private final List<CreateAppDataArgs> mArgs = new ArrayList<>();
        private final List<CompletableFuture<CreateAppDataResult>> mFutures = new ArrayList<>();

        /**
         * Enqueue the given {@code installd} operation to be executed in the
         * future when {@link #execute(Installer)} is invoked.
         * <p>
         * Callers of this method are not required to hold a monitor lock on an
         * {@link Installer} object.
         */
        @NonNull
        public synchronized CompletableFuture<CreateAppDataResult> createAppData(
                CreateAppDataArgs args) {
            if (mExecuted) {
                throw new IllegalStateException();
            }
            final CompletableFuture<CreateAppDataResult> future = new CompletableFuture<>();
            mArgs.add(args);
            mFutures.add(future);
            return future;
        }

        /**
         * Execute all pending {@code installd} operations that have been
         * collected by this batch in a blocking fashion.
         * <p>
         * Callers of this method <em>must</em> hold a monitor lock on the given
         * {@link Installer} object.
         */
        public synchronized void execute(@NonNull Installer installer) throws InstallerException {
            if (mExecuted) throw new IllegalStateException();
            mExecuted = true;

            final int size = mArgs.size();
            for (int i = 0; i < size; i += CREATE_APP_DATA_BATCH_SIZE) {
                final CreateAppDataArgs[] args = new CreateAppDataArgs[Math.min(size - i,
                        CREATE_APP_DATA_BATCH_SIZE)];
                for (int j = 0; j < args.length; j++) {
                    args[j] = mArgs.get(i + j);
                }
                final CreateAppDataResult[] results = installer.createAppDataBatched(args);
                for (int j = 0; j < results.length; j++) {
                    final CreateAppDataResult result = results[j];
                    final CompletableFuture<CreateAppDataResult> future = mFutures.get(i + j);
                    if (result.exceptionCode == 0) {
                        future.complete(result);
                    } else {
                        future.completeExceptionally(
                                new InstallerException(result.exceptionMessage));
                    }
                }
            }
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

            final StackTraceElement[] elements = Thread.currentThread().getStackTrace();
            String className;
            String methodName;
            String fileName;
            int lineNumber;
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            EventLog.writeEvent(EventLogTags.INSTALLER_CLEAR_APP_DATA_CALLER, pid, uid, packageName,
                    flags);
            // Skip the first two elements since they are always the same, ie
            // Thread#getStackTrace() and VMStack#getThreadStackTrace()
            for (int i = 2; i < elements.length; i++) {
                className = elements[i].getClassName();
                methodName = elements[i].getMethodName();
                fileName = elements[i].getFileName();
                lineNumber = elements[i].getLineNumber();
                EventLog.writeEvent(EventLogTags.INSTALLER_CLEAR_APP_DATA_CALL_STACK, methodName,
                        className, fileName, lineNumber);
            }
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

    /**
     * Remove all invalid dirs under app data folder.
     * All dirs are supposed to be valid file and package names.
     */
    public void cleanupInvalidPackageDirs(String uuid, int userId, int flags)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.cleanupInvalidPackageDirs(uuid, userId, flags);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    public void moveCompleteApp(String fromUuid, String toUuid, String packageName,
            int appId, String seInfo, int targetSdkVersion,
            String fromCodePath) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.moveCompleteApp(fromUuid, toUuid, packageName, appId, seInfo,
                    targetSdkVersion, fromCodePath);
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

    /**
     * To get all of the CrateMetadata of the crates for the specified user app by the installd.
     *
     * @param uuid the UUID
     * @param packageNames the application package names
     * @param userId the user id
     * @return the array of CrateMetadata
     */
    @Nullable
    public CrateMetadata[] getAppCrates(@NonNull String uuid, @NonNull String[] packageNames,
            @UserIdInt int userId) throws InstallerException {
        if (!checkBeforeRemote()) return null;
        try {
            return mInstalld.getAppCrates(uuid, packageNames, userId);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * To retrieve all of the CrateMetadata of the crate for the specified user app by the installd.
     *
     * @param uuid the UUID
     * @param userId the user id
     * @return the array of CrateMetadata
     */
    @Nullable
    public CrateMetadata[] getUserCrates(String uuid, @UserIdInt int userId)
            throws InstallerException {
        if (!checkBeforeRemote()) return null;
        try {
            return mInstalld.getUserCrates(uuid, userId);
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

    /**
     * This function only remains to allow overriding in OtaDexoptService.
     */
    public boolean dexopt(String apkPath, int uid, String pkgName, String instructionSet,
            int dexoptNeeded, @Nullable String outputPath, int dexFlags, String compilerFilter,
            @Nullable String volumeUuid, @Nullable String classLoaderContext,
            @Nullable String seInfo, boolean downgrade, int targetSdkVersion,
            @Nullable String profileName, @Nullable String dexMetadataPath,
            @Nullable String compilationReason)
            throws InstallerException, LegacyDexoptDisabledException {
        throw new LegacyDexoptDisabledException();
    }

    /**
     * Remove a directory belonging to a package.
     */
    public void rmPackageDir(String packageName, String packageDir) throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(packageDir);
        try {
            mInstalld.rmPackageDir(packageName, packageDir);
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

    /**
     * Deletes cache from specified uuid until targetFreeBytes amount of space is free.
     * flag denotes aggressive or non-aggresive mode where cache under quota is eligible or not
     * respectively for clearing.
     */
    public void freeCache(String uuid, long targetFreeBytes, int flags) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.freeCache(uuid, targetFreeBytes, flags);
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

    /**
     * Creates an oat dir for given package and instruction set.
     */
    public void createOatDir(String packageName, String oatDir, String dexInstructionSet)
            throws InstallerException {
        // This method should be allowed even if ART Service is enabled, because it's used for
        // creating oat dirs before creating hard links for partial installation.
        // TODO(b/274658735): Add an ART Service API to support hard linking.
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.createOatDir(packageName, oatDir, dexInstructionSet);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Creates a hardlink for a path.
     */
    public void linkFile(String packageName, String relativePath, String fromBase, String toBase)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(fromBase);
        BlockGuard.getVmPolicy().onPathAccess(toBase);
        try {
            mInstalld.linkFile(packageName, relativePath, fromBase, toBase);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Moves oat/vdex/art from "B" set defined by ro.boot.slot_suffix to the default set.
     */
    public void moveAb(String packageName, String apkPath, String instructionSet, String outputPath)
            throws InstallerException {
        if (!checkBeforeRemote()) return;
        BlockGuard.getVmPolicy().onPathAccess(apkPath);
        BlockGuard.getVmPolicy().onPathAccess(outputPath);
        try {
            mInstalld.moveAb(packageName, apkPath, instructionSet, outputPath);
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

    /**
     * Bind mount private volume CE and DE mirror storage.
     */
    public void tryMountDataMirror(String volumeUuid) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.tryMountDataMirror(volumeUuid);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Unmount private volume CE and DE mirror storage.
     */
    public void onPrivateVolumeRemoved(String volumeUuid) throws InstallerException {
        if (!checkBeforeRemote()) return;
        try {
            mInstalld.onPrivateVolumeRemoved(volumeUuid);
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
     * @return {@code true} if the snapshot was taken successfully, or {@code false} if a remote
     * call shouldn't be continued. See {@link #checkBeforeRemote}.
     *
     * @throws InstallerException if failed to snapshot user data.
     */
    public boolean snapshotAppData(String pkg, @UserIdInt int userId, int snapshotId,
            int storageFlags) throws InstallerException {
        if (!checkBeforeRemote()) return false;

        try {
            mInstalld.snapshotAppData(null, pkg, userId, snapshotId, storageFlags);
            return true;
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
     * @param snapshotId id of the snapshot to delete.
     * @param storageFlags flags controlling which user data snapshot (CE or DE) to delete.
     *
     * @return {@code true} if user data snapshot was successfully deleted, or {@code false} if a
     *  remote call shouldn't be continued. See {@link #checkBeforeRemote}.
     *
     * @throws InstallerException if failed to delete user data snapshot.
     */
    public boolean destroyAppDataSnapshot(String pkg, @UserIdInt int userId,
            int snapshotId, int storageFlags) throws InstallerException {
        if (!checkBeforeRemote()) return false;

        try {
            mInstalld.destroyAppDataSnapshot(null, pkg, userId, 0, snapshotId, storageFlags);
            return true;
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Deletes all snapshots of credential encrypted user data, where the snapshot id is not
     * included in {@code retainSnapshotIds}.
     *
     * @param userId id of the user whose user data snapshots to delete.
     * @param retainSnapshotIds ids of the snapshots that should not be deleted.
     *
     * @return {@code true} if the operation was successful, or {@code false} if a remote call
     * shouldn't be continued. See {@link #checkBeforeRemote}.
     *
     * @throws InstallerException if failed to delete user data snapshot.
     */
    public boolean destroyCeSnapshotsNotSpecified(@UserIdInt int userId,
            int[] retainSnapshotIds) throws InstallerException {
        if (!checkBeforeRemote()) return false;

        try {
            mInstalld.destroyCeSnapshotsNotSpecified(null, userId, retainSnapshotIds);
            return true;
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Migrates obb data from its legacy location {@code /data/media/obb} to
     * {@code /data/media/0/Android/obb}. This call is idempotent and a fast no-op if data has
     * already been migrated.
     *
     * @throws InstallerException if an error occurs.
     */
    public boolean migrateLegacyObbData() throws InstallerException {
        if (!checkBeforeRemote()) return false;

        try {
            mInstalld.migrateLegacyObbData();
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

    /**
     * Returns an auth token for the provided writable FD.
     *
     * @param authFd a file descriptor to proof that the caller can write to the file.
     * @param uid uid of the calling app.
     *
     * @return authToken, or null if a remote call shouldn't be continued. See {@link
     * #checkBeforeRemote}.
     *
     * @throws InstallerException if the remote call failed.
     */
    public IInstalld.IFsveritySetupAuthToken createFsveritySetupAuthToken(
            ParcelFileDescriptor authFd, int uid) throws InstallerException {
        if (!checkBeforeRemote()) {
            return null;
        }
        try {
            return mInstalld.createFsveritySetupAuthToken(authFd, uid);
        } catch (Exception e) {
            throw InstallerException.from(e);
        }
    }

    /**
     * Enables fs-verity to the given app file.
     *
     * @param authToken a token previously returned from {@link #createFsveritySetupAuthToken}.
     * @param filePath file path of the package to enable fs-verity.
     * @param packageName name of the package.
     *
     * @return 0 if the operation was successful, otherwise {@code errno}.
     *
     * @throws InstallerException if the remote call failed (e.g. see {@link #checkBeforeRemote}).
     */
    public int enableFsverity(IInstalld.IFsveritySetupAuthToken authToken, String filePath,
            String packageName) throws InstallerException {
        if (!checkBeforeRemote()) {
            throw new InstallerException("fs-verity wasn't enabled with an isolated installer");
        }
        BlockGuard.getVmPolicy().onPathAccess(filePath);
        try {
            return mInstalld.enableFsverity(authToken, filePath, packageName);
        } catch (Exception e) {
            throw InstallerException.from(e);
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

    /**
     * A checked exception that is thrown in legacy dexopt code paths when ART Service should be
     * used instead.
     */
    public static class LegacyDexoptDisabledException extends Exception {
        // TODO(b/260124949): Remove the legacy dexopt code paths, i.e. this exception and all code
        // that may throw it.
        public LegacyDexoptDisabledException() {
            super("Invalid call to legacy dexopt method while ART Service is in use.");
        }
    }
}
