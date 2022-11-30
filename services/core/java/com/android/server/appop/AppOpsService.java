/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.appop;

import static android.app.AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;
import static android.app.AppOpsManager.ATTRIBUTION_FLAG_TRUSTED;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.OP_FLAGS_ALL;
import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXIED;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_BOOT_TIME_SAMPLING;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_RARELY_USED;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_UNIFORM;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_UNIFORM_OPS;
import static android.app.AppOpsManager._NUM_OP;
import static android.app.AppOpsManager.opRestrictsRead;
import static android.app.AppOpsManager.opToPublicName;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.content.pm.PermissionInfo.PROTECTION_FLAG_APPOP;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManager.AttributionFlags;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.OpFlags;
import android.app.AppOpsManagerInternal;
import android.app.AppOpsManagerInternal.CheckOpsDelegate;
import android.app.AsyncNotedAppOp;
import android.app.RuntimeAppOpAccessMessage;
import android.app.SyncNotedAppOp;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.hardware.camera2.CameraDevice.CAMERA_AUDIO_RESTRICTION;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PackageTagsList;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.Immutable;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsAsyncNotedCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IAppOpsStartedCallback;
import com.android.internal.app.MessageSamplingConfig;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;
import com.android.server.pm.PackageList;
import com.android.server.policy.AppOpsPolicy;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * The system service component to {@link AppOpsManager}.
 */
public class AppOpsService extends IAppOpsService.Stub {

    private final AppOpsServiceInterface mAppOpsService;

    static final String TAG = "AppOps";
    static final boolean DEBUG = false;

    /**
     * Used for data access validation collection, we wish to only log a specific access once
     */
    private final ArraySet<NoteOpTrace> mNoteOpCallerStacktraces = new ArraySet<>();

    // Constant meaning that any UID should be matched when dispatching callbacks
    private static final int UID_ANY = -2;

    private static final int MAX_UNFORWARDED_OPS = 10;

    private static final int RARELY_USED_PACKAGES_INITIALIZATION_DELAY_MILLIS = 300000;

    final Context mContext;
    private final @Nullable File mNoteOpCallerStacktracesFile;
    final Handler mHandler;

    private final AppOpsManagerInternalImpl mAppOpsManagerInternal
            = new AppOpsManagerInternalImpl();

    /**
     * Registered callbacks, called from {@link #collectAsyncNotedOp}.
     *
     * <p>(package name, uid) -> callbacks
     *
     * @see #getAsyncNotedOpsKey(String, int)
     */
    @GuardedBy("this")
    private final ArrayMap<Pair<String, Integer>, RemoteCallbackList<IAppOpsAsyncNotedCallback>>
            mAsyncOpWatchers = new ArrayMap<>();

    /**
     * Async note-ops collected from {@link #collectAsyncNotedOp} that have not been delivered to a
     * callback yet.
     *
     * <p>(package name, uid) -> list&lt;ops&gt;
     *
     * @see #getAsyncNotedOpsKey(String, int)
     */
    @GuardedBy("this")
    private final ArrayMap<Pair<String, Integer>, ArrayList<AsyncNotedAppOp>>
            mUnforwardedAsyncNotedOps = new ArrayMap<>();

    boolean mWriteNoteOpsScheduled;

    private volatile CheckOpsDelegateDispatcher mCheckOpsDelegateDispatcher =
            new CheckOpsDelegateDispatcher(/*policy*/ null, /*delegate*/ null);


    /** Package sampled for message collection in the current session */
    @GuardedBy("this")
    private String mSampledPackage = null;

    /** Appop sampled for message collection in the current session */
    @GuardedBy("this")
    private int mSampledAppOpCode = OP_NONE;

    /** Maximum distance for appop to be considered for message collection in the current session */
    @GuardedBy("this")
    private int mAcceptableLeftDistance = 0;

    /** Number of messages collected for sampled package and appop in the current session */
    @GuardedBy("this")
    private float mMessagesCollectedCount;

    /** List of rarely used packages priorities for message collection */
    @GuardedBy("this")
    private ArraySet<String> mRarelyUsedPackages = new ArraySet<>();

    /** Sampling strategy used for current session */
    @GuardedBy("this")
    @AppOpsManager.SamplingStrategy
    private int mSamplingStrategy;

    /** Last runtime permission access message collected and ready for reporting */
    @GuardedBy("this")
    private RuntimeAppOpAccessMessage mCollectedRuntimePermissionMessage;

    /** Package Manager internal. Access via {@link #getPackageManagerInternal()} */
    private @Nullable PackageManagerInternal mPackageManagerInternal;

    final AudioRestrictionManager mAudioRestrictionManager = new AudioRestrictionManager();

    /**
     * Loads the OpsValidation file results into a hashmap {@link #mNoteOpCallerStacktraces}
     * so that we do not log the same operation twice between instances
     */
    private void readNoteOpCallerStackTraces() {
        try {
            if (!mNoteOpCallerStacktracesFile.exists()) {
                mNoteOpCallerStacktracesFile.createNewFile();
                return;
            }

            try (Scanner read = new Scanner(mNoteOpCallerStacktracesFile)) {
                read.useDelimiter("\\},");
                while (read.hasNext()) {
                    String jsonOps = read.next();
                    mNoteOpCallerStacktraces.add(NoteOpTrace.fromJson(jsonOps));
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Cannot parse traces noteOps", e);
        }
    }

    public AppOpsService(File storagePath, Handler handler, Context context) {
        this(handler, context, new AppOpsServiceImpl(storagePath, handler, context));
    }

    @VisibleForTesting
    public AppOpsService(Handler handler, Context context,
            AppOpsServiceInterface appOpsServiceInterface) {
        if (AppOpsManager.NOTE_OP_COLLECTION_ENABLED) {
            mNoteOpCallerStacktracesFile = new File(SystemServiceManager.ensureSystemDir(),
                    "noteOpStackTraces.json");
            readNoteOpCallerStackTraces();
        } else {
            mNoteOpCallerStacktracesFile = null;
        }

        mAppOpsService = appOpsServiceInterface;
        mContext = context;
        mHandler = handler;
    }

    /**
     * Publishes binder and local service.
     */
    public void publish() {
        ServiceManager.addService(Context.APP_OPS_SERVICE, asBinder());
        LocalServices.addService(AppOpsManagerInternal.class, mAppOpsManagerInternal);
    }

    /**
     * Finishes boot sequence.
     */
    public void systemReady() {
        mAppOpsService.systemReady();

        final IntentFilter packageAddedFilter = new IntentFilter();
        packageAddedFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageAddedFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                final String packageName = intent.getData().getSchemeSpecificPart();
                PackageInfo pi = getPackageManagerInternal().getPackageInfo(packageName,
                        PackageManager.GET_PERMISSIONS, Process.myUid(), mContext.getUserId());
                if (isSamplingTarget(pi)) {
                    synchronized (AppOpsService.this) {
                        mRarelyUsedPackages.add(packageName);
                    }
                }
            }
        }, packageAddedFilter);

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<String> packageNames = getPackageListAndResample();
                initializeRarelyUsedPackagesList(new ArraySet<>(packageNames));
            }
        }, RARELY_USED_PACKAGES_INITIALIZATION_DELAY_MILLIS);

        getPackageManagerInternal().setExternalSourcesPolicy(
                new PackageManagerInternal.ExternalSourcesPolicy() {
                    @Override
                    public int getPackageTrustedToInstallApps(String packageName, int uid) {
                        int appOpMode = checkOperation(AppOpsManager.OP_REQUEST_INSTALL_PACKAGES,
                                uid, packageName);
                        switch (appOpMode) {
                            case AppOpsManager.MODE_ALLOWED:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_TRUSTED;
                            case AppOpsManager.MODE_ERRORED:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_BLOCKED;
                            default:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_DEFAULT;
                        }
                    }
                });
    }

    /**
     * Sets a policy for handling app ops.
     *
     * @param policy The policy.
     */
    public void setAppOpsPolicy(@Nullable CheckOpsDelegate policy) {
        final CheckOpsDelegateDispatcher oldDispatcher = mCheckOpsDelegateDispatcher;
        final CheckOpsDelegate delegate = (oldDispatcher != null)
                ? oldDispatcher.mCheckOpsDelegate : null;
        mCheckOpsDelegateDispatcher = new CheckOpsDelegateDispatcher(policy, delegate);
    }

    /**
     * Notify when a package is removed
     */
    public void packageRemoved(int uid, String packageName) {
        mAppOpsService.packageRemoved(uid, packageName);
    }

    /**
     * Notify when a uid is removed.
     */
    public void uidRemoved(int uid) {
        mAppOpsService.uidRemoved(uid);
    }

    /**
     * Notify the proc state or capability has changed for a certain UID.
     */
    public void updateUidProcState(int uid, int procState,
            @ActivityManager.ProcessCapability int capability) {
        mAppOpsService.updateUidProcState(uid, procState, capability);
    }

    /**
     * Initiates shutdown.
     */
    public void shutdown() {
        mAppOpsService.shutdown();

        if (AppOpsManager.NOTE_OP_COLLECTION_ENABLED && mWriteNoteOpsScheduled) {
            writeNoteOps();
        }
    }

    @Override
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        return mAppOpsService.getPackagesForOps(ops);
    }

    @Override
    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName,
            int[] ops) {
        return mAppOpsService.getOpsForPackage(uid, packageName, ops);
    }

    @Override
    public void getHistoricalOps(int uid, String packageName, String attributionTag,
            List<String> opNames, int dataType, int filter, long beginTimeMillis,
            long endTimeMillis, int flags, RemoteCallback callback) {
        mAppOpsService.getHistoricalOps(uid, packageName, attributionTag, opNames,
                dataType, filter, beginTimeMillis, endTimeMillis, flags, callback);
    }

    @Override
    public void getHistoricalOpsFromDiskRaw(int uid, String packageName, String attributionTag,
            List<String> opNames, int dataType, int filter, long beginTimeMillis,
            long endTimeMillis, int flags, RemoteCallback callback) {
        mAppOpsService.getHistoricalOpsFromDiskRaw(uid, packageName, attributionTag,
                opNames, dataType, filter, beginTimeMillis, endTimeMillis, flags, callback);
    }

    @Override
    public void reloadNonHistoricalState() {
        mAppOpsService.reloadNonHistoricalState();
    }

    @Override
    public List<AppOpsManager.PackageOps> getUidOps(int uid, int[] ops) {
        return mAppOpsService.getUidOps(uid, ops);
    }

    @Override
    public void setUidMode(int code, int uid, int mode) {
        mAppOpsService.setUidMode(code, uid, mode, null);
    }

    /**
     * Sets the mode for a certain op and uid.
     *
     * @param code The op code to set
     * @param uid The UID for which to set
     * @param packageName The package for which to set
     * @param mode The new mode to set
     */
    @Override
    public void setMode(int code, int uid, @NonNull String packageName, int mode) {
        mAppOpsService.setMode(code, uid, packageName, mode, null);
    }

    @Override
    public void resetAllModes(int reqUserId, String reqPackageName) {
        mAppOpsService.resetAllModes(reqUserId, reqPackageName);
    }

    @Override
    public void startWatchingMode(int op, String packageName, IAppOpsCallback callback) {
        startWatchingModeWithFlags(op, packageName, 0, callback);
    }

    @Override
    public void startWatchingModeWithFlags(int op, String packageName, int flags,
            IAppOpsCallback callback) {
        mAppOpsService.startWatchingModeWithFlags(op, packageName, flags, callback);
    }

    @Override
    public void stopWatchingMode(IAppOpsCallback callback) {
        mAppOpsService.stopWatchingMode(callback);
    }

    /**
     * @return the current {@link CheckOpsDelegate}.
     */
    public CheckOpsDelegate getAppOpsServiceDelegate() {
        synchronized (AppOpsService.this) {
            final CheckOpsDelegateDispatcher dispatcher = mCheckOpsDelegateDispatcher;
            return (dispatcher != null) ? dispatcher.getCheckOpsDelegate() : null;
        }
    }

    /**
     * Sets the appops {@link CheckOpsDelegate}
     */
    public void setAppOpsServiceDelegate(CheckOpsDelegate delegate) {
        synchronized (AppOpsService.this) {
            final CheckOpsDelegateDispatcher oldDispatcher = mCheckOpsDelegateDispatcher;
            final CheckOpsDelegate policy = (oldDispatcher != null) ? oldDispatcher.mPolicy : null;
            mCheckOpsDelegateDispatcher = new CheckOpsDelegateDispatcher(policy, delegate);
        }
    }

    @Override
    public int checkOperationRaw(int code, int uid, String packageName,
            @Nullable String attributionTag) {
        return mCheckOpsDelegateDispatcher.checkOperation(code, uid, packageName, attributionTag,
                true /*raw*/);
    }

    @Override
    public int checkOperation(int code, int uid, String packageName) {
        return mCheckOpsDelegateDispatcher.checkOperation(code, uid, packageName, null,
                false /*raw*/);
    }

    private int checkOperationImpl(int code, int uid, String packageName,
            @Nullable String attributionTag, boolean raw) {
        return mAppOpsService.checkOperation(code, uid, packageName, attributionTag, raw);
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
        return mCheckOpsDelegateDispatcher.checkAudioOperation(code, usage, uid, packageName);
    }

    private int checkAudioOperationImpl(int code, int usage, int uid, String packageName) {
        final int mode = mAudioRestrictionManager.checkAudioOperation(
                code, usage, uid, packageName);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            return mode;
        }
        return checkOperation(code, uid, packageName);
    }

    @Override
    public void setAudioRestriction(int code, int usage, int uid, int mode,
            String[] exceptionPackages) {
        mAppOpsService.enforceManageAppOpsModes(Binder.getCallingPid(),
                Binder.getCallingUid(), uid);
        verifyIncomingUid(uid);
        verifyIncomingOp(code);

        mAudioRestrictionManager.setZenModeAudioRestriction(
                code, usage, uid, mode, exceptionPackages);

        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsServiceInterface::notifyWatchersOfChange, mAppOpsService, code,
                UID_ANY));
    }


    @Override
    public void setCameraAudioRestriction(@CAMERA_AUDIO_RESTRICTION int mode) {
        mAppOpsService.enforceManageAppOpsModes(Binder.getCallingPid(),
                Binder.getCallingUid(), -1);

        mAudioRestrictionManager.setCameraAudioRestriction(mode);

        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsServiceInterface::notifyWatchersOfChange, mAppOpsService,
                AppOpsManager.OP_PLAY_AUDIO, UID_ANY));
        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsServiceInterface::notifyWatchersOfChange, mAppOpsService,
                AppOpsManager.OP_VIBRATE, UID_ANY));
    }

    @Override
    public int checkPackage(int uid, String packageName) {
        return mAppOpsService.checkPackage(uid, packageName);
    }

    private boolean isPackageExisted(String packageName) {
        return getPackageManagerInternal().getPackageStateInternal(packageName) != null;
    }

    @Override
    public SyncNotedAppOp noteProxyOperation(int code, AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation) {
        return mCheckOpsDelegateDispatcher.noteProxyOperation(code, attributionSource,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage, skipProxyOperation);
    }

    private SyncNotedAppOp noteProxyOperationImpl(int code, AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation) {
        final int proxyUid = attributionSource.getUid();
        final String proxyPackageName = attributionSource.getPackageName();
        final String proxyAttributionTag = attributionSource.getAttributionTag();
        final int proxiedUid = attributionSource.getNextUid();
        final String proxiedPackageName = attributionSource.getNextPackageName();
        final String proxiedAttributionTag = attributionSource.getNextAttributionTag();

        verifyIncomingProxyUid(attributionSource);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(proxiedPackageName, UserHandle.getUserId(proxiedUid))
                || !isIncomingPackageValid(proxyPackageName, UserHandle.getUserId(proxyUid))) {
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        skipProxyOperation = skipProxyOperation
                && isCallerAndAttributionTrusted(attributionSource);

        String resolveProxyPackageName = AppOpsManager.resolvePackageName(proxyUid,
                proxyPackageName);
        if (resolveProxyPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code,
                    proxiedAttributionTag, proxiedPackageName);
        }

        final boolean isSelfBlame = Binder.getCallingUid() == proxiedUid;
        final boolean isProxyTrusted = mContext.checkPermission(
                Manifest.permission.UPDATE_APP_OPS_STATS, -1, proxyUid)
                == PackageManager.PERMISSION_GRANTED || isSelfBlame;

        if (!skipProxyOperation) {
            final int proxyFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXY
                    : AppOpsManager.OP_FLAG_UNTRUSTED_PROXY;

            final int proxyReturn = mAppOpsService.noteOperationUnchecked(code, proxyUid,
                    resolveProxyPackageName, proxyAttributionTag, Process.INVALID_UID, null, null,
                    proxyFlags);
            if (proxyReturn != AppOpsManager.MODE_ALLOWED) {
                return new SyncNotedAppOp(proxyReturn, code, proxiedAttributionTag,
                        proxiedPackageName);
            }
            if (shouldCollectAsyncNotedOp) {
                boolean isProxyAttributionTagValid = mAppOpsService.isAttributionTagValid(proxyUid,
                        resolveProxyPackageName, proxyAttributionTag, null);
                collectAsyncNotedOp(proxyUid, resolveProxyPackageName, code,
                        isProxyAttributionTagValid ? proxyAttributionTag : null, proxyFlags,
                        message, shouldCollectMessage);
            }
        }

        String resolveProxiedPackageName = AppOpsManager.resolvePackageName(proxiedUid,
                proxiedPackageName);
        if (resolveProxiedPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        final int proxiedFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXIED
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED;
        final int result = mAppOpsService.noteOperationUnchecked(code, proxiedUid,
                resolveProxiedPackageName, proxiedAttributionTag, proxyUid, resolveProxyPackageName,
                proxyAttributionTag, proxiedFlags);

        boolean isProxiedAttributionTagValid = mAppOpsService.isAttributionTagValid(proxiedUid,
                resolveProxiedPackageName, proxiedAttributionTag, resolveProxyPackageName);
        if (shouldCollectAsyncNotedOp && result == AppOpsManager.MODE_ALLOWED) {
            collectAsyncNotedOp(proxiedUid, resolveProxiedPackageName, code,
                    isProxiedAttributionTagValid ? proxiedAttributionTag : null, proxiedFlags,
                    message, shouldCollectMessage);
        }


        return new SyncNotedAppOp(result, code,
                isProxiedAttributionTagValid ? proxiedAttributionTag : null,
                resolveProxiedPackageName);
    }

    private boolean isCallerAndAttributionTrusted(@NonNull AttributionSource attributionSource) {
        if (attributionSource.getUid() != Binder.getCallingUid()
                && attributionSource.isTrusted(mContext)) {
            return true;
        }
        return mContext.checkPermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public SyncNotedAppOp noteOperation(int code, int uid, String packageName,
            String attributionTag, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage) {
        return mCheckOpsDelegateDispatcher.noteOperation(code, uid, packageName,
                attributionTag, shouldCollectAsyncNotedOp, message, shouldCollectMessage);
    }

    private SyncNotedAppOp noteOperationImpl(int code, int uid, @Nullable String packageName,
            @Nullable String attributionTag, boolean shouldCollectAsyncNotedOp,
            @Nullable String message, boolean shouldCollectMessage) {
        if (!isIncomingPackageValid(packageName, UserHandle.getUserId(uid))) {
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, attributionTag,
                    packageName);
        }

        int result = mAppOpsService.noteOperation(code, uid, packageName,
                attributionTag, message);

        String resolvedPackageName = AppOpsManager.resolvePackageName(uid, packageName);

        boolean isAttributionTagValid = mAppOpsService.isAttributionTagValid(uid,
                    resolvedPackageName, attributionTag, null);

        if (shouldCollectAsyncNotedOp && result == MODE_ALLOWED) {
            collectAsyncNotedOp(uid, resolvedPackageName, code,
                    isAttributionTagValid ? attributionTag : null, AppOpsManager.OP_FLAG_SELF,
                    message, shouldCollectMessage);
        }

        return new SyncNotedAppOp(result, code, isAttributionTagValid ? attributionTag : null,
                resolvedPackageName);
    }

    // TODO moltmann: Allow watching for attribution ops
    @Override
    public void startWatchingActive(int[] ops, IAppOpsActiveCallback callback) {
        mAppOpsService.startWatchingActive(ops, callback);
    }

    @Override
    public void stopWatchingActive(IAppOpsActiveCallback callback) {
        mAppOpsService.stopWatchingActive(callback);
    }

    @Override
    public void startWatchingStarted(int[] ops, @NonNull IAppOpsStartedCallback callback) {
        mAppOpsService.startWatchingStarted(ops, callback);
    }

    @Override
    public void stopWatchingStarted(IAppOpsStartedCallback callback) {
        mAppOpsService.stopWatchingStarted(callback);
    }

    @Override
    public void startWatchingNoted(@NonNull int[] ops, @NonNull IAppOpsNotedCallback callback) {
        mAppOpsService.startWatchingNoted(ops, callback);
    }

    @Override
    public void stopWatchingNoted(IAppOpsNotedCallback callback) {
        mAppOpsService.stopWatchingNoted(callback);
    }

    /**
     * Collect an {@link AsyncNotedAppOp}.
     *
     * @param uid The uid the op was noted for
     * @param packageName The package the op was noted for
     * @param opCode The code of the op noted
     * @param attributionTag attribution tag the op was noted for
     * @param message The message for the op noting
     */
    private void collectAsyncNotedOp(int uid, @NonNull String packageName, int opCode,
            @Nullable String attributionTag, @OpFlags int flags, @NonNull String message,
            boolean shouldCollectMessage) {
        Objects.requireNonNull(message);

        int callingUid = Binder.getCallingUid();

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                Pair<String, Integer> key = getAsyncNotedOpsKey(packageName, uid);

                RemoteCallbackList<IAppOpsAsyncNotedCallback> callbacks = mAsyncOpWatchers.get(key);
                AsyncNotedAppOp asyncNotedOp = new AsyncNotedAppOp(opCode, callingUid,
                        attributionTag, message, System.currentTimeMillis());
                final boolean[] wasNoteForwarded = {false};

                if ((flags & (OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED)) != 0
                        && shouldCollectMessage) {
                    reportRuntimeAppOpAccessMessageAsyncLocked(uid, packageName, opCode,
                            attributionTag, message);
                }

                if (callbacks != null) {
                    callbacks.broadcast((cb) -> {
                        try {
                            cb.opNoted(asyncNotedOp);
                            wasNoteForwarded[0] = true;
                        } catch (RemoteException e) {
                            Slog.e(TAG,
                                    "Could not forward noteOp of " + opCode + " to " + packageName
                                            + "/" + uid + "(" + attributionTag + ")", e);
                        }
                    });
                }

                if (!wasNoteForwarded[0]) {
                    ArrayList<AsyncNotedAppOp> unforwardedOps = mUnforwardedAsyncNotedOps.get(key);
                    if (unforwardedOps == null) {
                        unforwardedOps = new ArrayList<>(1);
                        mUnforwardedAsyncNotedOps.put(key, unforwardedOps);
                    }

                    unforwardedOps.add(asyncNotedOp);
                    if (unforwardedOps.size() > MAX_UNFORWARDED_OPS) {
                        unforwardedOps.remove(0);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Compute a key to be used in {@link #mAsyncOpWatchers} and {@link #mUnforwardedAsyncNotedOps}
     *
     * @param packageName The package name of the app
     * @param uid The uid of the app
     *
     * @return They key uniquely identifying the app
     */
    private @NonNull Pair<String, Integer> getAsyncNotedOpsKey(@NonNull String packageName,
            int uid) {
        return new Pair<>(packageName, uid);
    }

    @Override
    public void startWatchingAsyncNoted(String packageName, IAppOpsAsyncNotedCallback callback) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callback);

        int uid = Binder.getCallingUid();
        Pair<String, Integer> key = getAsyncNotedOpsKey(packageName, uid);

        mAppOpsService.verifyPackage(uid, packageName);

        synchronized (this) {
            RemoteCallbackList<IAppOpsAsyncNotedCallback> callbacks = mAsyncOpWatchers.get(key);
            if (callbacks == null) {
                callbacks = new RemoteCallbackList<IAppOpsAsyncNotedCallback>() {
                    @Override
                    public void onCallbackDied(IAppOpsAsyncNotedCallback callback) {
                        synchronized (AppOpsService.this) {
                            if (getRegisteredCallbackCount() == 0) {
                                mAsyncOpWatchers.remove(key);
                            }
                        }
                    }
                };
                mAsyncOpWatchers.put(key, callbacks);
            }

            callbacks.register(callback);
        }
    }

    @Override
    public void stopWatchingAsyncNoted(String packageName, IAppOpsAsyncNotedCallback callback) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callback);

        int uid = Binder.getCallingUid();
        Pair<String, Integer> key = getAsyncNotedOpsKey(packageName, uid);

        mAppOpsService.verifyPackage(uid, packageName);

        synchronized (this) {
            RemoteCallbackList<IAppOpsAsyncNotedCallback> callbacks = mAsyncOpWatchers.get(key);
            if (callbacks != null) {
                callbacks.unregister(callback);
                if (callbacks.getRegisteredCallbackCount() == 0) {
                    mAsyncOpWatchers.remove(key);
                }
            }
        }
    }

    @Override
    public List<AsyncNotedAppOp> extractAsyncOps(String packageName) {
        Objects.requireNonNull(packageName);

        int uid = Binder.getCallingUid();

        mAppOpsService.verifyPackage(uid, packageName);

        synchronized (this) {
            return mUnforwardedAsyncNotedOps.remove(getAsyncNotedOpsKey(packageName, uid));
        }
    }

    @Override
    public SyncNotedAppOp startOperation(IBinder token, int code, int uid,
            @Nullable String packageName, @Nullable String attributionTag,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp,
            String message, boolean shouldCollectMessage, @AttributionFlags int attributionFlags,
            int attributionChainId) {
        return mCheckOpsDelegateDispatcher.startOperation(token, code, uid, packageName,
                attributionTag, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage, attributionFlags, attributionChainId);
    }

    private SyncNotedAppOp startOperationImpl(@NonNull IBinder clientId, int code, int uid,
            @Nullable String packageName, @Nullable String attributionTag,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, @NonNull String message,
            boolean shouldCollectMessage, @AttributionFlags int attributionFlags,
            int attributionChainId) {
        if (!isIncomingPackageValid(packageName, UserHandle.getUserId(uid))) {
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, attributionTag,
                    packageName);
        }

        int result = mAppOpsService.startOperation(clientId, code, uid, packageName,
                attributionTag, startIfModeDefault, message,
                attributionFlags, attributionChainId);

        String resolvedPackageName = AppOpsManager.resolvePackageName(uid, packageName);

        boolean isAttributionTagValid = mAppOpsService.isAttributionTagValid(uid,
                resolvedPackageName, attributionTag, null);

        if (shouldCollectAsyncNotedOp && result == MODE_ALLOWED) {
            collectAsyncNotedOp(uid, resolvedPackageName, code,
                    isAttributionTagValid ? attributionTag : null, AppOpsManager.OP_FLAG_SELF,
                    message, shouldCollectMessage);
        }

        return new SyncNotedAppOp(result, code, isAttributionTagValid ? attributionTag : null,
                resolvedPackageName);
    }

    @Override
    public SyncNotedAppOp startProxyOperation(IBinder clientId, int code,
            @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation, @AttributionFlags int proxyAttributionFlags,
            @AttributionFlags int proxiedAttributionFlags, int attributionChainId) {
        return mCheckOpsDelegateDispatcher.startProxyOperation(clientId, code,
                attributionSource, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                proxiedAttributionFlags, attributionChainId);
    }

    private SyncNotedAppOp startProxyOperationImpl(IBinder clientId, int code,
            @NonNull AttributionSource attributionSource,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage, boolean skipProxyOperation, @AttributionFlags
            int proxyAttributionFlags, @AttributionFlags int proxiedAttributionFlags,
            int attributionChainId) {

        final int proxyUid = attributionSource.getUid();
        final String proxyPackageName = attributionSource.getPackageName();
        final String proxyAttributionTag = attributionSource.getAttributionTag();
        final int proxiedUid = attributionSource.getNextUid();
        final String proxiedPackageName = attributionSource.getNextPackageName();
        final String proxiedAttributionTag = attributionSource.getNextAttributionTag();

        verifyIncomingProxyUid(attributionSource);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(proxyPackageName, UserHandle.getUserId(proxyUid))
                || !isIncomingPackageValid(proxiedPackageName, UserHandle.getUserId(proxiedUid))) {
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        boolean isCallerTrusted = isCallerAndAttributionTrusted(attributionSource);
        skipProxyOperation = isCallerTrusted && skipProxyOperation;

        String resolvedProxyPackageName = AppOpsManager.resolvePackageName(proxyUid,
                proxyPackageName);
        if (resolvedProxyPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        final boolean isChainTrusted = isCallerTrusted
                && attributionChainId != ATTRIBUTION_CHAIN_ID_NONE
                && ((proxyAttributionFlags & ATTRIBUTION_FLAG_TRUSTED) != 0
                || (proxiedAttributionFlags & ATTRIBUTION_FLAG_TRUSTED) != 0);
        final boolean isSelfBlame = Binder.getCallingUid() == proxiedUid;
        final boolean isProxyTrusted = mContext.checkPermission(
                Manifest.permission.UPDATE_APP_OPS_STATS, -1, proxyUid)
                == PackageManager.PERMISSION_GRANTED || isSelfBlame
                || isChainTrusted;

        String resolvedProxiedPackageName = AppOpsManager.resolvePackageName(proxiedUid,
                proxiedPackageName);
        if (resolvedProxiedPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        final int proxiedFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXIED
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED;

        if (!skipProxyOperation) {
            // Test if the proxied operation will succeed before starting the proxy operation
            final int testProxiedOp = mAppOpsService.startOperationUnchecked(clientId, code,
                    proxiedUid, resolvedProxiedPackageName, proxiedAttributionTag, proxyUid,
                    resolvedProxyPackageName, proxyAttributionTag, proxiedFlags, startIfModeDefault,
                    proxiedAttributionFlags, attributionChainId, /*dryRun*/ true);

            boolean isTestProxiedAttributionTagValid =
                    mAppOpsService.isAttributionTagValid(proxiedUid, resolvedProxiedPackageName,
                            proxiedAttributionTag, resolvedProxyPackageName);

            if (!shouldStartForMode(testProxiedOp, startIfModeDefault)) {
                return new SyncNotedAppOp(testProxiedOp, code,
                        isTestProxiedAttributionTagValid ? proxiedAttributionTag : null,
                        resolvedProxiedPackageName);
            }

            final int proxyFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXY
                    : AppOpsManager.OP_FLAG_UNTRUSTED_PROXY;

            final int proxyAppOp = mAppOpsService.startOperationUnchecked(clientId, code, proxyUid,
                    resolvedProxyPackageName, proxyAttributionTag, Process.INVALID_UID, null, null,
                    proxyFlags, startIfModeDefault, proxyAttributionFlags, attributionChainId,
                    /*dryRun*/ false);

            boolean isProxyAttributionTagValid = mAppOpsService.isAttributionTagValid(proxyUid,
                    resolvedProxyPackageName, proxyAttributionTag, null);

            if (!shouldStartForMode(proxyAppOp, startIfModeDefault)) {
                return new SyncNotedAppOp(proxyAppOp, code,
                        isProxyAttributionTagValid ? proxyAttributionTag : null,
                        resolvedProxyPackageName);
            }

            if (shouldCollectAsyncNotedOp) {
                collectAsyncNotedOp(proxyUid, resolvedProxyPackageName, code,
                        isProxyAttributionTagValid ? proxyAttributionTag : null, proxyFlags,
                        message, shouldCollectMessage);
            }
        }

        final int proxiedAppOp = mAppOpsService.startOperationUnchecked(clientId, code, proxiedUid,
                resolvedProxiedPackageName, proxiedAttributionTag, proxyUid,
                resolvedProxyPackageName, proxyAttributionTag, proxiedFlags, startIfModeDefault,
                proxiedAttributionFlags, attributionChainId,/*dryRun*/ false);

        boolean isProxiedAttributionTagValid = mAppOpsService.isAttributionTagValid(proxiedUid,
                resolvedProxiedPackageName, proxiedAttributionTag, resolvedProxyPackageName);

        if (shouldCollectAsyncNotedOp && proxiedAppOp == MODE_ALLOWED) {
            collectAsyncNotedOp(proxyUid, resolvedProxiedPackageName, code,
                    isProxiedAttributionTagValid ? proxiedAttributionTag : null,
                    proxiedAttributionFlags, message, shouldCollectMessage);
        }

        return new SyncNotedAppOp(proxiedAppOp, code,
                isProxiedAttributionTagValid ? proxiedAttributionTag : null,
                resolvedProxiedPackageName);
    }

    private boolean shouldStartForMode(int mode, boolean startIfModeDefault) {
        return (mode == MODE_ALLOWED || (mode == MODE_DEFAULT && startIfModeDefault));
    }

    @Override
    public void finishOperation(IBinder clientId, int code, int uid, String packageName,
            String attributionTag) {
        mCheckOpsDelegateDispatcher.finishOperation(clientId, code, uid, packageName,
                attributionTag);
    }

    private void finishOperationImpl(IBinder clientId, int code, int uid, String packageName,
            String attributionTag) {
        mAppOpsService.finishOperation(clientId, code, uid, packageName, attributionTag);
    }

    @Override
    public void finishProxyOperation(IBinder clientId, int code,
            @NonNull AttributionSource attributionSource, boolean skipProxyOperation) {
        mCheckOpsDelegateDispatcher.finishProxyOperation(clientId, code, attributionSource,
                skipProxyOperation);
    }

    private Void finishProxyOperationImpl(IBinder clientId, int code,
            @NonNull AttributionSource attributionSource, boolean skipProxyOperation) {
        final int proxyUid = attributionSource.getUid();
        final String proxyPackageName = attributionSource.getPackageName();
        final String proxyAttributionTag = attributionSource.getAttributionTag();
        final int proxiedUid = attributionSource.getNextUid();
        final String proxiedPackageName = attributionSource.getNextPackageName();
        final String proxiedAttributionTag = attributionSource.getNextAttributionTag();

        skipProxyOperation = skipProxyOperation
                && isCallerAndAttributionTrusted(attributionSource);

        verifyIncomingProxyUid(attributionSource);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(proxyPackageName, UserHandle.getUserId(proxyUid))
                || !isIncomingPackageValid(proxiedPackageName, UserHandle.getUserId(proxiedUid))) {
            return null;
        }

        String resolvedProxyPackageName = AppOpsManager.resolvePackageName(proxyUid,
                proxyPackageName);
        if (resolvedProxyPackageName == null) {
            return null;
        }

        if (!skipProxyOperation) {
            mAppOpsService.finishOperationUnchecked(clientId, code, proxyUid,
                    resolvedProxyPackageName, proxyAttributionTag);
        }

        String resolvedProxiedPackageName = AppOpsManager.resolvePackageName(proxiedUid,
                proxiedPackageName);
        if (resolvedProxiedPackageName == null) {
            return null;
        }

        mAppOpsService.finishOperationUnchecked(clientId, code, proxiedUid,
                resolvedProxiedPackageName, proxiedAttributionTag);

        return null;
    }

    @Override
    public int permissionToOpCode(String permission) {
        if (permission == null) {
            return AppOpsManager.OP_NONE;
        }
        return AppOpsManager.permissionToOpCode(permission);
    }

    @Override
    public boolean shouldCollectNotes(int opCode) {
        Preconditions.checkArgumentInRange(opCode, 0, _NUM_OP - 1, "opCode");

        if (AppOpsManager.shouldForceCollectNoteForOp(opCode)) {
            return true;
        }

        String perm = AppOpsManager.opToPermission(opCode);
        if (perm == null) {
            return false;
        }

        PermissionInfo permInfo;
        try {
            permInfo = mContext.getPackageManager().getPermissionInfo(perm, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        return permInfo.getProtection() == PROTECTION_DANGEROUS
                || (permInfo.getProtectionFlags() & PROTECTION_FLAG_APPOP) != 0;
    }

    private void verifyIncomingProxyUid(@NonNull AttributionSource attributionSource) {
        if (attributionSource.getUid() == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        if (attributionSource.isTrusted(mContext)) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private void verifyIncomingOp(int op) {
        if (op >= 0 && op < AppOpsManager._NUM_OP) {
            // Enforce manage appops permission if it's a restricted read op.
            if (opRestrictsRead(op)) {
                mContext.enforcePermission(Manifest.permission.MANAGE_APPOPS,
                        Binder.getCallingPid(), Binder.getCallingUid(), "verifyIncomingOp");
            }
            return;
        }
        throw new IllegalArgumentException("Bad operation #" + op);
    }

    private boolean isIncomingPackageValid(@Nullable String packageName, @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        // Handle the special UIDs that don't have actual packages (audioserver, cameraserver, etc).
        if (packageName == null || isSpecialPackage(callingUid, packageName)) {
            return true;
        }

        // If the package doesn't exist, #verifyAndGetBypass would throw a SecurityException in
        // the end. Although that exception would be caught and return, we could make it return
        // early.
        if (!isPackageExisted(packageName)) {
            return false;
        }

        if (getPackageManagerInternal().filterAppAccess(packageName, callingUid, userId)) {
            Slog.w(TAG, packageName + " not found from " + callingUid);
            return false;
        }

        return true;
    }

    private boolean isSpecialPackage(int callingUid, @Nullable String packageName) {
        final String resolvedPackage = AppOpsManager.resolvePackageName(callingUid, packageName);
        return callingUid == Process.SYSTEM_UID
                || resolveUid(resolvedPackage) != Process.INVALID_UID;
    }

    /**
     * @return {@link PackageManagerInternal}
     */
    private @NonNull PackageManagerInternal getPackageManagerInternal() {
        if (mPackageManagerInternal == null) {
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        }

        return mPackageManagerInternal;
    }

    static class Shell extends ShellCommand {
        final IAppOpsService mInterface;
        final AppOpsService mInternal;

        int userId = UserHandle.USER_SYSTEM;
        String packageName;
        String attributionTag;
        String opStr;
        String modeStr;
        int op;
        int mode;
        int packageUid;
        int nonpackageUid;
        IBinder mToken;
        boolean targetsUid;

        Shell(IAppOpsService iface, AppOpsService internal) {
            mInterface = iface;
            mInternal = internal;
            mToken = AppOpsManager.getClientId();
        }

        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            dumpCommandHelp(pw);
        }

        static int strOpToOp(String op, PrintWriter err) {
            try {
                return AppOpsManager.strOpToOp(op);
            } catch (IllegalArgumentException e) {
            }
            try {
                return Integer.parseInt(op);
            } catch (NumberFormatException e) {
            }
            try {
                return AppOpsManager.strDebugOpToOp(op);
            } catch (IllegalArgumentException e) {
                err.println("Error: " + e.getMessage());
                return -1;
            }
        }

        static int strModeToMode(String modeStr, PrintWriter err) {
            for (int i = AppOpsManager.MODE_NAMES.length - 1; i >= 0; i--) {
                if (AppOpsManager.MODE_NAMES[i].equals(modeStr)) {
                    return i;
                }
            }
            try {
                return Integer.parseInt(modeStr);
            } catch (NumberFormatException e) {
            }
            err.println("Error: Mode " + modeStr + " is not valid");
            return -1;
        }

        int parseUserOpMode(int defMode, PrintWriter err) throws RemoteException {
            userId = UserHandle.USER_CURRENT;
            opStr = null;
            modeStr = null;
            for (String argument; (argument = getNextArg()) != null;) {
                if ("--user".equals(argument)) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    if (opStr == null) {
                        opStr = argument;
                    } else if (modeStr == null) {
                        modeStr = argument;
                        break;
                    }
                }
            }
            if (opStr == null) {
                err.println("Error: Operation not specified.");
                return -1;
            }
            op = strOpToOp(opStr, err);
            if (op < 0) {
                return -1;
            }
            if (modeStr != null) {
                if ((mode=strModeToMode(modeStr, err)) < 0) {
                    return -1;
                }
            } else {
                mode = defMode;
            }
            return 0;
        }

        int parseUserPackageOp(boolean reqOp, PrintWriter err) throws RemoteException {
            userId = UserHandle.USER_CURRENT;
            packageName = null;
            opStr = null;
            for (String argument; (argument = getNextArg()) != null;) {
                if ("--user".equals(argument)) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else if ("--uid".equals(argument)) {
                    targetsUid = true;
                } else if ("--attribution".equals(argument)) {
                    attributionTag = getNextArgRequired();
                } else {
                    if (packageName == null) {
                        packageName = argument;
                    } else if (opStr == null) {
                        opStr = argument;
                        break;
                    }
                }
            }
            if (packageName == null) {
                err.println("Error: Package name not specified.");
                return -1;
            } else if (opStr == null && reqOp) {
                err.println("Error: Operation not specified.");
                return -1;
            }
            if (opStr != null) {
                op = strOpToOp(opStr, err);
                if (op < 0) {
                    return -1;
                }
            } else {
                op = AppOpsManager.OP_NONE;
            }
            if (userId == UserHandle.USER_CURRENT) {
                userId = ActivityManager.getCurrentUser();
            }
            nonpackageUid = -1;
            try {
                nonpackageUid = Integer.parseInt(packageName);
            } catch (NumberFormatException e) {
            }
            if (nonpackageUid == -1 && packageName.length() > 1 && packageName.charAt(0) == 'u'
                    && packageName.indexOf('.') < 0) {
                int i = 1;
                while (i < packageName.length() && packageName.charAt(i) >= '0'
                        && packageName.charAt(i) <= '9') {
                    i++;
                }
                if (i > 1 && i < packageName.length()) {
                    String userStr = packageName.substring(1, i);
                    try {
                        int user = Integer.parseInt(userStr);
                        char type = packageName.charAt(i);
                        i++;
                        int startTypeVal = i;
                        while (i < packageName.length() && packageName.charAt(i) >= '0'
                                && packageName.charAt(i) <= '9') {
                            i++;
                        }
                        if (i > startTypeVal) {
                            String typeValStr = packageName.substring(startTypeVal, i);
                            try {
                                int typeVal = Integer.parseInt(typeValStr);
                                if (type == 'a') {
                                    nonpackageUid = UserHandle.getUid(user,
                                            typeVal + Process.FIRST_APPLICATION_UID);
                                } else if (type == 's') {
                                    nonpackageUid = UserHandle.getUid(user, typeVal);
                                }
                            } catch (NumberFormatException e) {
                            }
                        }
                    } catch (NumberFormatException e) {
                    }
                }
            }
            if (nonpackageUid != -1) {
                packageName = null;
            } else {
                packageUid = resolveUid(packageName);
                if (packageUid < 0) {
                    packageUid = AppGlobals.getPackageManager().getPackageUid(packageName,
                            PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                }
                if (packageUid < 0) {
                    err.println("Error: No UID for " + packageName + " in user " + userId);
                    return -1;
                }
            }
            return 0;
        }
    }

    @Override public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new Shell(this, this)).exec(this, in, out, err, args, callback, resultReceiver);
    }

    static void dumpCommandHelp(PrintWriter pw) {
        pw.println("AppOps service (appops) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  start [--user <USER_ID>] [--attribution <ATTRIBUTION_TAG>] <PACKAGE | UID> "
                + "<OP> ");
        pw.println("    Starts a given operation for a particular application.");
        pw.println("  stop [--user <USER_ID>] [--attribution <ATTRIBUTION_TAG>] <PACKAGE | UID> "
                + "<OP> ");
        pw.println("    Stops a given operation for a particular application.");
        pw.println("  set [--user <USER_ID>] <[--uid] PACKAGE | UID> <OP> <MODE>");
        pw.println("    Set the mode for a particular application and operation.");
        pw.println("  get [--user <USER_ID>] [--attribution <ATTRIBUTION_TAG>] <PACKAGE | UID> "
                + "[<OP>]");
        pw.println("    Return the mode for a particular application and optional operation.");
        pw.println("  query-op [--user <USER_ID>] <OP> [<MODE>]");
        pw.println("    Print all packages that currently have the given op in the given mode.");
        pw.println("  reset [--user <USER_ID>] [<PACKAGE>]");
        pw.println("    Reset the given application or all applications to default modes.");
        pw.println("  write-settings");
        pw.println("    Immediately write pending changes to storage.");
        pw.println("  read-settings");
        pw.println("    Read the last written settings, replacing current state in RAM.");
        pw.println("  options:");
        pw.println("    <PACKAGE> an Android package name or its UID if prefixed by --uid");
        pw.println("    <OP>      an AppOps operation.");
        pw.println("    <MODE>    one of allow, ignore, deny, or default");
        pw.println("    <USER_ID> the user id under which the package is installed. If --user is");
        pw.println("              not specified, the current user is assumed.");
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mAppOpsService.dump(fd, pw, args);

        pw.println();
        if (mCheckOpsDelegateDispatcher.mPolicy != null
                && mCheckOpsDelegateDispatcher.mPolicy instanceof AppOpsPolicy) {
            AppOpsPolicy policy = (AppOpsPolicy) mCheckOpsDelegateDispatcher.mPolicy;
            policy.dumpTags(pw);
        } else {
            pw.println("  AppOps policy not set.");
        }

        if (mAudioRestrictionManager.hasActiveRestrictions()) {
            pw.println();
            mAudioRestrictionManager.dump(pw);
        }
    }
    static int onShellCommand(Shell shell, String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        PrintWriter err = shell.getErrPrintWriter();
        try {
            switch (cmd) {
                case "set": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }
                    String modeStr = shell.getNextArg();
                    if (modeStr == null) {
                        err.println("Error: Mode not specified.");
                        return -1;
                    }

                    final int mode = shell.strModeToMode(modeStr, err);
                    if (mode < 0) {
                        return -1;
                    }

                    if (!shell.targetsUid && shell.packageName != null) {
                        shell.mInterface.setMode(shell.op, shell.packageUid, shell.packageName,
                                mode);
                    } else if (shell.targetsUid && shell.packageName != null) {
                        try {
                            final int uid = shell.mInternal.mContext.getPackageManager()
                                    .getPackageUidAsUser(shell.packageName, shell.userId);
                            shell.mInterface.setUidMode(shell.op, uid, mode);
                        } catch (PackageManager.NameNotFoundException e) {
                            return -1;
                        }
                    } else {
                        shell.mInterface.setUidMode(shell.op, shell.nonpackageUid, mode);
                    }
                    return 0;
                }
                case "get": {
                    int res = shell.parseUserPackageOp(false, err);
                    if (res < 0) {
                        return res;
                    }

                    List<AppOpsManager.PackageOps> ops = new ArrayList<>();
                    if (shell.packageName != null) {
                        // Uid mode overrides package mode, so make sure it's also reported
                        List<AppOpsManager.PackageOps> r = shell.mInterface.getUidOps(
                                shell.packageUid,
                                shell.op != AppOpsManager.OP_NONE ? new int[]{shell.op} : null);
                        if (r != null) {
                            ops.addAll(r);
                        }
                        r = shell.mInterface.getOpsForPackage(
                                shell.packageUid, shell.packageName,
                                shell.op != AppOpsManager.OP_NONE ? new int[]{shell.op} : null);
                        if (r != null) {
                            ops.addAll(r);
                        }
                    } else {
                        ops = shell.mInterface.getUidOps(
                                shell.nonpackageUid,
                                shell.op != AppOpsManager.OP_NONE ? new int[]{shell.op} : null);
                    }
                    if (ops == null || ops.size() <= 0) {
                        pw.println("No operations.");
                        if (shell.op > AppOpsManager.OP_NONE && shell.op < AppOpsManager._NUM_OP) {
                            pw.println("Default mode: " + AppOpsManager.modeToName(
                                    AppOpsManager.opToDefaultMode(shell.op)));
                        }
                        return 0;
                    }
                    final long now = System.currentTimeMillis();
                    for (int i=0; i<ops.size(); i++) {
                        AppOpsManager.PackageOps packageOps = ops.get(i);
                        if (packageOps.getPackageName() == null) {
                            pw.print("Uid mode: ");
                        }
                        List<AppOpsManager.OpEntry> entries = packageOps.getOps();
                        for (int j=0; j<entries.size(); j++) {
                            AppOpsManager.OpEntry ent = entries.get(j);
                            pw.print(AppOpsManager.opToName(ent.getOp()));
                            pw.print(": ");
                            pw.print(AppOpsManager.modeToName(ent.getMode()));
                            if (shell.attributionTag == null) {
                                if (ent.getLastAccessTime(OP_FLAGS_ALL) != -1) {
                                    pw.print("; time=");
                                    TimeUtils.formatDuration(
                                            now - ent.getLastAccessTime(OP_FLAGS_ALL), pw);
                                    pw.print(" ago");
                                }
                                if (ent.getLastRejectTime(OP_FLAGS_ALL) != -1) {
                                    pw.print("; rejectTime=");
                                    TimeUtils.formatDuration(
                                            now - ent.getLastRejectTime(OP_FLAGS_ALL), pw);
                                    pw.print(" ago");
                                }
                                if (ent.isRunning()) {
                                    pw.print(" (running)");
                                } else if (ent.getLastDuration(OP_FLAGS_ALL) != -1) {
                                    pw.print("; duration=");
                                    TimeUtils.formatDuration(ent.getLastDuration(OP_FLAGS_ALL), pw);
                                }
                            } else {
                                final AppOpsManager.AttributedOpEntry attributionEnt =
                                        ent.getAttributedOpEntries().get(shell.attributionTag);
                                if (attributionEnt != null) {
                                    if (attributionEnt.getLastAccessTime(OP_FLAGS_ALL) != -1) {
                                        pw.print("; time=");
                                        TimeUtils.formatDuration(
                                                now - attributionEnt.getLastAccessTime(
                                                        OP_FLAGS_ALL), pw);
                                        pw.print(" ago");
                                    }
                                    if (attributionEnt.getLastRejectTime(OP_FLAGS_ALL) != -1) {
                                        pw.print("; rejectTime=");
                                        TimeUtils.formatDuration(
                                                now - attributionEnt.getLastRejectTime(
                                                        OP_FLAGS_ALL), pw);
                                        pw.print(" ago");
                                    }
                                    if (attributionEnt.isRunning()) {
                                        pw.print(" (running)");
                                    } else if (attributionEnt.getLastDuration(OP_FLAGS_ALL)
                                            != -1) {
                                        pw.print("; duration=");
                                        TimeUtils.formatDuration(
                                                attributionEnt.getLastDuration(OP_FLAGS_ALL), pw);
                                    }
                                }
                            }
                            pw.println();
                        }
                    }
                    return 0;
                }
                case "query-op": {
                    int res = shell.parseUserOpMode(AppOpsManager.MODE_IGNORED, err);
                    if (res < 0) {
                        return res;
                    }
                    List<AppOpsManager.PackageOps> ops = shell.mInterface.getPackagesForOps(
                            new int[] {shell.op});
                    if (ops == null || ops.size() <= 0) {
                        pw.println("No operations.");
                        return 0;
                    }
                    for (int i=0; i<ops.size(); i++) {
                        final AppOpsManager.PackageOps pkg = ops.get(i);
                        boolean hasMatch = false;
                        final List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();
                        for (int j=0; j<entries.size(); j++) {
                            AppOpsManager.OpEntry ent = entries.get(j);
                            if (ent.getOp() == shell.op && ent.getMode() == shell.mode) {
                                hasMatch = true;
                                break;
                            }
                        }
                        if (hasMatch) {
                            pw.println(pkg.getPackageName());
                        }
                    }
                    return 0;
                }
                case "reset": {
                    String packageName = null;
                    int userId = UserHandle.USER_CURRENT;
                    for (String argument; (argument = shell.getNextArg()) != null;) {
                        if ("--user".equals(argument)) {
                            String userStr = shell.getNextArgRequired();
                            userId = UserHandle.parseUserArg(userStr);
                        } else {
                            if (packageName == null) {
                                packageName = argument;
                            } else {
                                err.println("Error: Unsupported argument: " + argument);
                                return -1;
                            }
                        }
                    }

                    if (userId == UserHandle.USER_CURRENT) {
                        userId = ActivityManager.getCurrentUser();
                    }

                    shell.mInterface.resetAllModes(userId, packageName);
                    pw.print("Reset all modes for: ");
                    if (userId == UserHandle.USER_ALL) {
                        pw.print("all users");
                    } else {
                        pw.print("user "); pw.print(userId);
                    }
                    pw.print(", ");
                    if (packageName == null) {
                        pw.println("all packages");
                    } else {
                        pw.print("package "); pw.println(packageName);
                    }
                    return 0;
                }
                case "write-settings": {
                    shell.mInternal.mAppOpsService
                            .enforceManageAppOpsModes(Binder.getCallingPid(),
                            Binder.getCallingUid(), -1);
                    final long token = Binder.clearCallingIdentity();
                    try {
                        shell.mInternal.mAppOpsService.writeState();
                        pw.println("Current settings written.");
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return 0;
                }
                case "read-settings": {
                    shell.mInternal.mAppOpsService
                            .enforceManageAppOpsModes(Binder.getCallingPid(),
                                    Binder.getCallingUid(), -1);
                    final long token = Binder.clearCallingIdentity();
                    try {
                        shell.mInternal.mAppOpsService.readState();
                        pw.println("Last settings read.");
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return 0;
                }
                case "start": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }

                    if (shell.packageName != null) {
                        shell.mInterface.startOperation(shell.mToken, shell.op, shell.packageUid,
                                shell.packageName, shell.attributionTag, true, true,
                                "appops start shell command", true,
                                AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR, ATTRIBUTION_CHAIN_ID_NONE);
                    } else {
                        return -1;
                    }
                    return 0;
                }
                case "stop": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }

                    if (shell.packageName != null) {
                        shell.mInterface.finishOperation(shell.mToken, shell.op, shell.packageUid,
                                shell.packageName, shell.attributionTag);
                    } else {
                        return -1;
                    }
                    return 0;
                }
                default:
                    return shell.handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    @Override
    public void setUserRestrictions(Bundle restrictions, IBinder token, int userHandle) {
        mAppOpsService.setUserRestrictions(restrictions, token, userHandle);
    }

    @Override
    public void setUserRestriction(int code, boolean restricted, IBinder token, int userHandle,
            PackageTagsList excludedPackageTags) {
        mAppOpsService.setUserRestriction(code, restricted, token, userHandle,
                excludedPackageTags);
    }

    @Override
    public void removeUser(int userHandle) throws RemoteException {
        mAppOpsService.removeUser(userHandle);
    }

    @Override
    public boolean isOperationActive(int code, int uid, String packageName) {
        return mAppOpsService.isOperationActive(code, uid, packageName);
    }

    @Override
    public boolean isProxying(int op, @NonNull String proxyPackageName,
            @NonNull String proxyAttributionTag, int proxiedUid,
            @NonNull String proxiedPackageName) {
        return mAppOpsService.isProxying(op, proxyPackageName, proxyAttributionTag,
                proxiedUid, proxiedPackageName);
    }

    @Override
    public void resetPackageOpsNoHistory(@NonNull String packageName) {
        mAppOpsService.resetPackageOpsNoHistory(packageName);
    }

    @Override
    public void setHistoryParameters(@AppOpsManager.HistoricalMode int mode,
            long baseSnapshotInterval, int compressionStep) {
        mAppOpsService.setHistoryParameters(mode, baseSnapshotInterval, compressionStep);
    }

    @Override
    public void offsetHistory(long offsetMillis) {
        mAppOpsService.offsetHistory(offsetMillis);
    }

    @Override
    public void addHistoricalOps(HistoricalOps ops) {
        mAppOpsService.addHistoricalOps(ops);
    }

    @Override
    public void resetHistoryParameters() {
        mAppOpsService.resetHistoryParameters();
    }

    @Override
    public void clearHistory() {
        mAppOpsService.clearHistory();
    }

    @Override
    public void rebootHistory(long offlineDurationMillis) {
        mAppOpsService.rebootHistory(offlineDurationMillis);
    }

    /**
     * Report runtime access to AppOp together with message (including stack trace)
     *
     * @param packageName The package which reported the op
     * @param notedAppOp contains code of op and attributionTag provided by developer
     * @param message Message describing AppOp access (can be stack trace)
     *
     * @return Config for future sampling to reduce amount of reporting
     */
    @Override
    public MessageSamplingConfig reportRuntimeAppOpAccessMessageAndGetConfig(
            String packageName, SyncNotedAppOp notedAppOp, String message) {
        int uid = Binder.getCallingUid();
        Objects.requireNonNull(packageName);
        synchronized (this) {
            switchPackageIfBootTimeOrRarelyUsedLocked(packageName);
            if (!packageName.equals(mSampledPackage)) {
                return new MessageSamplingConfig(OP_NONE, 0,
                        Instant.now().plus(1, ChronoUnit.HOURS).toEpochMilli());
            }

            Objects.requireNonNull(notedAppOp);
            Objects.requireNonNull(message);

            reportRuntimeAppOpAccessMessageInternalLocked(uid, packageName,
                    AppOpsManager.strOpToOp(notedAppOp.getOp()),
                    notedAppOp.getAttributionTag(), message);

            return new MessageSamplingConfig(mSampledAppOpCode, mAcceptableLeftDistance,
                    Instant.now().plus(1, ChronoUnit.HOURS).toEpochMilli());
        }
    }

    /**
     * Report runtime access to AppOp together with message (entry point for reporting
     * asynchronous access)
     * @param uid Uid of the package which reported the op
     * @param packageName The package which reported the op
     * @param opCode Code of AppOp
     * @param attributionTag FeautreId of AppOp reported
     * @param message Message describing AppOp access (can be stack trace)
     */
    private void reportRuntimeAppOpAccessMessageAsyncLocked(int uid,
            @NonNull String packageName, int opCode, @Nullable String attributionTag,
            @NonNull String message) {
        switchPackageIfBootTimeOrRarelyUsedLocked(packageName);
        if (!Objects.equals(mSampledPackage, packageName)) {
            return;
        }
        reportRuntimeAppOpAccessMessageInternalLocked(uid, packageName, opCode, attributionTag,
                message);
    }

    /**
     * Decides whether reported message is within the range of watched AppOps and picks it for
     * reporting uniformly at random across all received messages.
     */
    private void reportRuntimeAppOpAccessMessageInternalLocked(int uid,
            @NonNull String packageName, int opCode, @Nullable String attributionTag,
            @NonNull String message) {
        int newLeftDistance = AppOpsManager.leftCircularDistance(opCode,
                mSampledAppOpCode, _NUM_OP);

        if (mAcceptableLeftDistance < newLeftDistance
                && mSamplingStrategy != SAMPLING_STRATEGY_UNIFORM_OPS) {
            return;
        }

        if (mAcceptableLeftDistance > newLeftDistance
                && mSamplingStrategy != SAMPLING_STRATEGY_UNIFORM_OPS) {
            mAcceptableLeftDistance = newLeftDistance;
            mMessagesCollectedCount = 0.0f;
        }

        mMessagesCollectedCount += 1.0f;
        if (ThreadLocalRandom.current().nextFloat() <= 1.0f / mMessagesCollectedCount) {
            mCollectedRuntimePermissionMessage = new RuntimeAppOpAccessMessage(uid, opCode,
                    packageName, attributionTag, message, mSamplingStrategy);
        }
        return;
    }

    /** Pulls current AppOps access report and resamples package and app op to watch */
    @Override
    public @Nullable RuntimeAppOpAccessMessage collectRuntimeAppOpAccessMessage() {
        ActivityManagerInternal ami = LocalServices.getService(ActivityManagerInternal.class);
        boolean isCallerInstrumented =
                ami.getInstrumentationSourceUid(Binder.getCallingUid()) != Process.INVALID_UID;
        boolean isCallerSystem = Binder.getCallingPid() == Process.myPid();
        if (!isCallerSystem && !isCallerInstrumented) {
            return null;
        }
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        RuntimeAppOpAccessMessage result;
        synchronized (this) {
            result = mCollectedRuntimePermissionMessage;
            mCollectedRuntimePermissionMessage = null;
        }
        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::getPackageListAndResample,
                this));
        return result;
    }

    /**
     * Checks if package is in the list of rarely used package and starts watching the new package
     * to collect incoming message or if collection is happening in first minutes since boot.
     * @param packageName
     */
    private void switchPackageIfBootTimeOrRarelyUsedLocked(@NonNull String packageName) {
        if (mSampledPackage == null) {
            if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                mSamplingStrategy = SAMPLING_STRATEGY_BOOT_TIME_SAMPLING;
                resampleAppOpForPackageLocked(packageName, true);
            }
        } else if (mRarelyUsedPackages.contains(packageName)) {
            mRarelyUsedPackages.remove(packageName);
            if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                mSamplingStrategy = SAMPLING_STRATEGY_RARELY_USED;
                resampleAppOpForPackageLocked(packageName, true);
            }
        }
    }

    /** Obtains package list and resamples package and appop to watch. */
    private List<String> getPackageListAndResample() {
        List<String> packageNames = getPackageNamesForSampling();
        synchronized (this) {
            resamplePackageAndAppOpLocked(packageNames);
        }
        return packageNames;
    }

    /** Resamples package and appop to watch from the list provided. */
    private void resamplePackageAndAppOpLocked(@NonNull List<String> packageNames) {
        if (!packageNames.isEmpty()) {
            if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                mSamplingStrategy = SAMPLING_STRATEGY_UNIFORM;
                resampleAppOpForPackageLocked(packageNames.get(
                        ThreadLocalRandom.current().nextInt(packageNames.size())), true);
            } else {
                mSamplingStrategy = SAMPLING_STRATEGY_UNIFORM_OPS;
                resampleAppOpForPackageLocked(packageNames.get(
                        ThreadLocalRandom.current().nextInt(packageNames.size())), false);
            }
        }
    }

    /** Resamples appop for the chosen package and initializes sampling state */
    private void resampleAppOpForPackageLocked(@NonNull String packageName, boolean pickOp) {
        mMessagesCollectedCount = 0.0f;
        mSampledAppOpCode = pickOp ? ThreadLocalRandom.current().nextInt(_NUM_OP) : OP_NONE;
        mAcceptableLeftDistance = _NUM_OP - 1;
        mSampledPackage = packageName;
    }

    /**
     * Creates list of rarely used packages - packages which were not used over last week or
     * which declared but did not use permissions over last week.
     *  */
    private void initializeRarelyUsedPackagesList(@NonNull ArraySet<String> candidates) {
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        List<String> runtimeAppOpsList = getRuntimeAppOpsList();
        AppOpsManager.HistoricalOpsRequest histOpsRequest =
                new AppOpsManager.HistoricalOpsRequest.Builder(
                        Math.max(Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli(), 0),
                        Long.MAX_VALUE).setOpNames(runtimeAppOpsList).setFlags(
                        OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED).build();
        appOps.getHistoricalOps(histOpsRequest, AsyncTask.THREAD_POOL_EXECUTOR,
                new Consumer<HistoricalOps>() {
                    @Override
                    public void accept(HistoricalOps histOps) {
                        int uidCount = histOps.getUidCount();
                        for (int uidIdx = 0; uidIdx < uidCount; uidIdx++) {
                            final AppOpsManager.HistoricalUidOps uidOps = histOps.getUidOpsAt(
                                    uidIdx);
                            int pkgCount = uidOps.getPackageCount();
                            for (int pkgIdx = 0; pkgIdx < pkgCount; pkgIdx++) {
                                String packageName = uidOps.getPackageOpsAt(
                                        pkgIdx).getPackageName();
                                if (!candidates.contains(packageName)) {
                                    continue;
                                }
                                AppOpsManager.HistoricalPackageOps packageOps =
                                        uidOps.getPackageOpsAt(pkgIdx);
                                if (packageOps.getOpCount() != 0) {
                                    candidates.remove(packageName);
                                }
                            }
                        }
                        synchronized (this) {
                            int numPkgs = mRarelyUsedPackages.size();
                            for (int i = 0; i < numPkgs; i++) {
                                candidates.add(mRarelyUsedPackages.valueAt(i));
                            }
                            mRarelyUsedPackages = candidates;
                        }
                    }
                });
    }

    /** List of app ops related to runtime permissions */
    private List<String> getRuntimeAppOpsList() {
        ArrayList<String> result = new ArrayList();
        for (int i = 0; i < _NUM_OP; i++) {
            if (shouldCollectNotes(i)) {
                result.add(opToPublicName(i));
            }
        }
        return result;
    }

    /** Returns list of packages to be used for package sampling */
    private @NonNull List<String> getPackageNamesForSampling() {
        List<String> packageNames = new ArrayList<>();
        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        PackageList packages = packageManagerInternal.getPackageList();
        for (String packageName : packages.getPackageNames()) {
            PackageInfo pkg = packageManagerInternal.getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS, Process.myUid(), mContext.getUserId());
            if (isSamplingTarget(pkg)) {
                packageNames.add(pkg.packageName);
            }
        }
        return packageNames;
    }

    /** Checks whether package should be included in sampling pool */
    private boolean isSamplingTarget(@Nullable PackageInfo pkg) {
        if (pkg == null) {
            return false;
        }
        String[] requestedPermissions = pkg.requestedPermissions;
        if (requestedPermissions == null) {
            return false;
        }
        for (String permission : requestedPermissions) {
            PermissionInfo permissionInfo;
            try {
                permissionInfo = mContext.getPackageManager().getPermissionInfo(permission, 0);
            } catch (PackageManager.NameNotFoundException ignored) {
                continue;
            }
            if (permissionInfo.getProtection() == PROTECTION_DANGEROUS) {
                return true;
            }
        }
        return false;
    }

    private static int resolveUid(String packageName)  {
        if (packageName == null) {
            return Process.INVALID_UID;
        }
        switch (packageName) {
            case "root":
                return Process.ROOT_UID;
            case "shell":
            case "dumpstate":
                return Process.SHELL_UID;
            case "media":
                return Process.MEDIA_UID;
            case "audioserver":
                return Process.AUDIOSERVER_UID;
            case "cameraserver":
                return Process.CAMERASERVER_UID;
        }
        return Process.INVALID_UID;
    }

    private final class AppOpsManagerInternalImpl extends AppOpsManagerInternal {
        @Override public void setDeviceAndProfileOwners(SparseIntArray owners) {
            AppOpsService.this.mAppOpsService.setDeviceAndProfileOwners(owners);
        }

        @Override
        public void updateAppWidgetVisibility(SparseArray<String> uidPackageNames,
                boolean visible) {
            AppOpsService.this.mAppOpsService
                    .updateAppWidgetVisibility(uidPackageNames, visible);
        }

        @Override
        public void setUidModeFromPermissionPolicy(int code, int uid, int mode,
                @Nullable IAppOpsCallback callback) {
            AppOpsService.this.mAppOpsService.setUidMode(code, uid, mode, callback);
        }

        @Override
        public void setModeFromPermissionPolicy(int code, int uid, @NonNull String packageName,
                int mode, @Nullable IAppOpsCallback callback) {
            AppOpsService.this.mAppOpsService
                    .setMode(code, uid, packageName, mode, callback);
        }


        @Override
        public void setGlobalRestriction(int code, boolean restricted, IBinder token) {
            AppOpsService.this.mAppOpsService
                    .setGlobalRestriction(code, restricted, token);
        }

        @Override
        public int getOpRestrictionCount(int code, UserHandle user, String pkg,
                String attributionTag) {
            return AppOpsService.this.mAppOpsService
                    .getOpRestrictionCount(code, user, pkg, attributionTag);
        }
    }

    /**
     * Async task for writing note op stack trace, op code, package name and version to file
     * More specifically, writes all the collected ops from {@link #mNoteOpCallerStacktraces}
     */
    private void writeNoteOps() {
        synchronized (this) {
            mWriteNoteOpsScheduled = false;
        }
        synchronized (mNoteOpCallerStacktracesFile) {
            try (FileWriter writer = new FileWriter(mNoteOpCallerStacktracesFile)) {
                int numTraces = mNoteOpCallerStacktraces.size();
                for (int i = 0; i < numTraces; i++) {
                    // Writing json formatted string into file
                    writer.write(mNoteOpCallerStacktraces.valueAt(i).asJson());
                    // Comma separation, so we can wrap the entire log as a JSON object
                    // when all results are collected
                    writer.write(",");
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed to load opsValidation file for FileWriter", e);
            }
        }
    }

    /**
     * This class represents a NoteOp Trace object amd contains the necessary fields that will
     * be written to file to use for permissions data validation in JSON format
     */
    @Immutable
    static class NoteOpTrace {
        static final String STACKTRACE = "stackTrace";
        static final String OP = "op";
        static final String PACKAGENAME = "packageName";
        static final String VERSION = "version";

        private final @NonNull String mStackTrace;
        private final int mOp;
        private final @Nullable String mPackageName;
        private final long mVersion;

        /**
         * Initialize a NoteOp object using a JSON object containing the necessary fields
         *
         * @param jsonTrace JSON object represented as a string
         *
         * @return NoteOpTrace object initialized with JSON fields
         */
        static NoteOpTrace fromJson(String jsonTrace) {
            try {
                // Re-add closing bracket which acted as a delimiter by the reader
                JSONObject obj = new JSONObject(jsonTrace.concat("}"));
                return new NoteOpTrace(obj.getString(STACKTRACE), obj.getInt(OP),
                        obj.getString(PACKAGENAME), obj.getLong(VERSION));
            } catch (JSONException e) {
                // Swallow error, only meant for logging ops, should not affect flow of the code
                Slog.e(TAG, "Error constructing NoteOpTrace object "
                        + "JSON trace format incorrect", e);
                return null;
            }
        }

        NoteOpTrace(String stackTrace, int op, String packageName, long version) {
            mStackTrace = stackTrace;
            mOp = op;
            mPackageName = packageName;
            mVersion = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NoteOpTrace that = (NoteOpTrace) o;
            return mOp == that.mOp
                    && mVersion == that.mVersion
                    && mStackTrace.equals(that.mStackTrace)
                    && Objects.equals(mPackageName, that.mPackageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mStackTrace, mOp, mPackageName, mVersion);
        }

        /**
         * The object is formatted as a JSON object and returned as a String
         *
         * @return JSON formatted string
         */
        public String asJson() {
            return  "{"
                    + "\"" + STACKTRACE + "\":\"" + mStackTrace.replace("\n", "\\n")
                    + '\"' + ",\"" + OP + "\":" + mOp
                    + ",\"" + PACKAGENAME + "\":\"" + mPackageName + '\"'
                    + ",\"" + VERSION + "\":" + mVersion
                    + '}';
        }
    }

    /**
     * Collects noteOps, noteProxyOps and startOps from AppOpsManager and writes it into a file
     * which will be used for permissions data validation, the given parameters to this method
     * will be logged in json format
     *
     * @param stackTrace stacktrace from the most recent call in AppOpsManager
     * @param op op code
     * @param packageName package making call
     * @param version android version for this call
     */
    @Override
    public void collectNoteOpCallsForValidation(String stackTrace, int op, String packageName,
            long version) {
        if (!AppOpsManager.NOTE_OP_COLLECTION_ENABLED) {
            return;
        }

        Objects.requireNonNull(stackTrace);
        Preconditions.checkArgument(op >= 0);
        Preconditions.checkArgument(op < AppOpsManager._NUM_OP);

        NoteOpTrace noteOpTrace = new NoteOpTrace(stackTrace, op, packageName, version);

        boolean noteOpSetWasChanged;
        synchronized (this) {
            noteOpSetWasChanged = mNoteOpCallerStacktraces.add(noteOpTrace);
            if (noteOpSetWasChanged && !mWriteNoteOpsScheduled) {
                mWriteNoteOpsScheduled = true;
                mHandler.postDelayed(PooledLambda.obtainRunnable((that) -> {
                    AsyncTask.execute(() -> {
                        that.writeNoteOps();
                    });
                }, this), 2500);
            }
        }
    }

    @Immutable
    private final class CheckOpsDelegateDispatcher {
        private final @Nullable CheckOpsDelegate mPolicy;
        private final @Nullable CheckOpsDelegate mCheckOpsDelegate;

        CheckOpsDelegateDispatcher(@Nullable CheckOpsDelegate policy,
                @Nullable CheckOpsDelegate checkOpsDelegate) {
            mPolicy = policy;
            mCheckOpsDelegate = checkOpsDelegate;
        }

        public @NonNull CheckOpsDelegate getCheckOpsDelegate() {
            return mCheckOpsDelegate;
        }

        public int checkOperation(int code, int uid, String packageName,
                @Nullable String attributionTag, boolean raw) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.checkOperation(code, uid, packageName, attributionTag, raw,
                            this::checkDelegateOperationImpl);
                } else {
                    return mPolicy.checkOperation(code, uid, packageName, attributionTag, raw,
                            AppOpsService.this::checkOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return checkDelegateOperationImpl(code, uid, packageName, attributionTag, raw);
            }
            return checkOperationImpl(code, uid, packageName, attributionTag, raw);
        }

        private int checkDelegateOperationImpl(int code, int uid, String packageName,
                @Nullable String attributionTag, boolean raw) {
            return mCheckOpsDelegate.checkOperation(code, uid, packageName, attributionTag, raw,
                    AppOpsService.this::checkOperationImpl);
        }

        public int checkAudioOperation(int code, int usage, int uid, String packageName) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.checkAudioOperation(code, usage, uid, packageName,
                            this::checkDelegateAudioOperationImpl);
                } else {
                    return mPolicy.checkAudioOperation(code, usage, uid, packageName,
                            AppOpsService.this::checkAudioOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return checkDelegateAudioOperationImpl(code, usage, uid, packageName);
            }
            return checkAudioOperationImpl(code, usage, uid, packageName);
        }

        private int checkDelegateAudioOperationImpl(int code, int usage, int uid,
                String packageName) {
            return mCheckOpsDelegate.checkAudioOperation(code, usage, uid, packageName,
                    AppOpsService.this::checkAudioOperationImpl);
        }

        public SyncNotedAppOp noteOperation(int code, int uid, String packageName,
                String attributionTag, boolean shouldCollectAsyncNotedOp, String message,
                boolean shouldCollectMessage) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.noteOperation(code, uid, packageName, attributionTag,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            this::noteDelegateOperationImpl);
                } else {
                    return mPolicy.noteOperation(code, uid, packageName, attributionTag,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            AppOpsService.this::noteOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return noteDelegateOperationImpl(code, uid, packageName,
                        attributionTag, shouldCollectAsyncNotedOp, message, shouldCollectMessage);
            }
            return noteOperationImpl(code, uid, packageName, attributionTag,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage);
        }

        private SyncNotedAppOp noteDelegateOperationImpl(int code, int uid,
                @Nullable String packageName, @Nullable String featureId,
                boolean shouldCollectAsyncNotedOp, @Nullable String message,
                boolean shouldCollectMessage) {
            return mCheckOpsDelegate.noteOperation(code, uid, packageName, featureId,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    AppOpsService.this::noteOperationImpl);
        }

        public SyncNotedAppOp noteProxyOperation(int code, AttributionSource attributionSource,
                boolean shouldCollectAsyncNotedOp, @Nullable String message,
                boolean shouldCollectMessage, boolean skipProxyOperation) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.noteProxyOperation(code, attributionSource,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            skipProxyOperation, this::noteDelegateProxyOperationImpl);
                } else {
                    return mPolicy.noteProxyOperation(code, attributionSource,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            skipProxyOperation, AppOpsService.this::noteProxyOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return noteDelegateProxyOperationImpl(code,
                        attributionSource, shouldCollectAsyncNotedOp, message,
                        shouldCollectMessage, skipProxyOperation);
            }
            return noteProxyOperationImpl(code, attributionSource, shouldCollectAsyncNotedOp,
                    message, shouldCollectMessage,skipProxyOperation);
        }

        private SyncNotedAppOp noteDelegateProxyOperationImpl(int code,
                @NonNull AttributionSource attributionSource, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                boolean skipProxyOperation) {
            return mCheckOpsDelegate.noteProxyOperation(code, attributionSource,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage, skipProxyOperation,
                    AppOpsService.this::noteProxyOperationImpl);
        }

        public SyncNotedAppOp startOperation(IBinder token, int code, int uid,
                @Nullable String packageName, @NonNull String attributionTag,
                boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                @AttributionFlags int attributionFlags, int attributionChainId) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.startOperation(token, code, uid, packageName,
                            attributionTag, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, attributionFlags, attributionChainId,
                            this::startDelegateOperationImpl);
                } else {
                    return mPolicy.startOperation(token, code, uid, packageName, attributionTag,
                            startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, attributionFlags, attributionChainId,
                            AppOpsService.this::startOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return startDelegateOperationImpl(token, code, uid, packageName, attributionTag,
                        startIfModeDefault, shouldCollectAsyncNotedOp, message,
                        shouldCollectMessage, attributionFlags, attributionChainId);
            }
            return startOperationImpl(token, code, uid, packageName, attributionTag,
                    startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    attributionFlags, attributionChainId);
        }

        private SyncNotedAppOp startDelegateOperationImpl(IBinder token, int code, int uid,
                @Nullable String packageName, @Nullable String attributionTag,
                boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
                boolean shouldCollectMessage, @AttributionFlags int attributionFlags,
                int attributionChainId) {
            return mCheckOpsDelegate.startOperation(token, code, uid, packageName, attributionTag,
                    startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    attributionFlags, attributionChainId, AppOpsService.this::startOperationImpl);
        }

        public SyncNotedAppOp startProxyOperation(IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
                boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
                boolean skipProxyOperation, @AttributionFlags int proxyAttributionFlags,
                @AttributionFlags int proxiedAttributionFlags, int attributionChainId) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.startProxyOperation(clientId, code, attributionSource,
                            startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                            proxiedAttributionFlags, attributionChainId,
                            this::startDelegateProxyOperationImpl);
                } else {
                    return mPolicy.startProxyOperation(clientId, code, attributionSource,
                            startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                            proxiedAttributionFlags, attributionChainId,
                            AppOpsService.this::startProxyOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return startDelegateProxyOperationImpl(clientId, code, attributionSource,
                        startIfModeDefault, shouldCollectAsyncNotedOp, message,
                        shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                        proxiedAttributionFlags, attributionChainId);
            }
            return startProxyOperationImpl(clientId, code, attributionSource, startIfModeDefault,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage, skipProxyOperation,
                    proxyAttributionFlags, proxiedAttributionFlags, attributionChainId);
        }

        private SyncNotedAppOp startDelegateProxyOperationImpl(IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
                boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
                boolean skipProxyOperation, @AttributionFlags int proxyAttributionFlags,
                @AttributionFlags int proxiedAttributionFlsgs, int attributionChainId) {
            return mCheckOpsDelegate.startProxyOperation(clientId, code, attributionSource,
                    startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    skipProxyOperation, proxyAttributionFlags, proxiedAttributionFlsgs,
                    attributionChainId, AppOpsService.this::startProxyOperationImpl);
        }

        public void finishOperation(IBinder clientId, int code, int uid, String packageName,
                String attributionTag) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    mPolicy.finishOperation(clientId, code, uid, packageName, attributionTag,
                            this::finishDelegateOperationImpl);
                } else {
                    mPolicy.finishOperation(clientId, code, uid, packageName, attributionTag,
                            AppOpsService.this::finishOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                finishDelegateOperationImpl(clientId, code, uid, packageName, attributionTag);
            } else {
                finishOperationImpl(clientId, code, uid, packageName, attributionTag);
            }
        }

        private void finishDelegateOperationImpl(IBinder clientId, int code, int uid,
                String packageName, String attributionTag) {
            mCheckOpsDelegate.finishOperation(clientId, code, uid, packageName, attributionTag,
                    AppOpsService.this::finishOperationImpl);
        }

        public void finishProxyOperation(IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean skipProxyOperation) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    mPolicy.finishProxyOperation(clientId, code, attributionSource,
                            skipProxyOperation, this::finishDelegateProxyOperationImpl);
                } else {
                    mPolicy.finishProxyOperation(clientId, code, attributionSource,
                            skipProxyOperation, AppOpsService.this::finishProxyOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                finishDelegateProxyOperationImpl(clientId, code, attributionSource,
                        skipProxyOperation);
            } else {
                finishProxyOperationImpl(clientId, code, attributionSource, skipProxyOperation);
            }
        }

        private Void finishDelegateProxyOperationImpl(IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean skipProxyOperation) {
            mCheckOpsDelegate.finishProxyOperation(clientId, code, attributionSource,
                    skipProxyOperation, AppOpsService.this::finishProxyOperationImpl);
            return null;
        }
    }
}
