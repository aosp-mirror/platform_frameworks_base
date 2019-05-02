/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License.s
 */

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.IApexService;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.SystemClock;
import android.sysprop.ApexProperties;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ApexManager class handles communications with the apex service to perform operation and queries,
 * as well as providing caching to avoid unnecessary calls to the service.
 *
 * @hide
 */
public final class ApexManager extends SystemService {
    private static final String TAG = "ApexManager";
    private IApexService mApexService;

    private final CountDownLatch mActivePackagesCacheLatch = new CountDownLatch(1);
    private Map<String, PackageInfo> mActivePackagesCache;

    private final CountDownLatch mApexFilesCacheLatch = new CountDownLatch(1);
    private ApexInfo[] mApexFiles;

    public ApexManager(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        try {
            mApexService = IApexService.Stub.asInterface(
                    ServiceManager.getServiceOrThrow("apexservice"));
        } catch (ServiceNotFoundException e) {
            throw new IllegalStateException("Required service apexservice not available");
        }
        publishLocalService(ApexManager.class, this);
        HandlerThread oneShotThread = new HandlerThread("ApexManagerOneShotHandler");
        oneShotThread.start();
        oneShotThread.getThreadHandler().post(this::initSequence);
        oneShotThread.quitSafely();
    }

    private void initSequence() {
        populateApexFilesCache();
        parseApexFiles();
    }

    private void populateApexFilesCache() {
        if (mApexFiles != null) {
            return;
        }
        long startTimeMicros = SystemClock.currentTimeMicro();
        Slog.i(TAG, "Starting to populate apex files cache");
        try {
            mApexFiles = mApexService.getActivePackages();
            Slog.i(TAG, "IPC to apexd finished in " + (SystemClock.currentTimeMicro()
                    - startTimeMicros) + " μs");
        } catch (RemoteException re) {
            // TODO: make sure this error is propagated to system server.
            Slog.e(TAG, "Unable to retrieve packages from apexservice: " + re.toString());
            re.rethrowAsRuntimeException();
        }
        mApexFilesCacheLatch.countDown();
        Slog.i(TAG, "Finished populating apex files cache in " + (SystemClock.currentTimeMicro()
                - startTimeMicros) + " μs");
    }

    private void parseApexFiles() {
        waitForLatch(mApexFilesCacheLatch);
        if (mApexFiles == null) {
            throw new IllegalStateException("mApexFiles must be populated");
        }
        long startTimeMicros = SystemClock.currentTimeMicro();
        Slog.i(TAG, "Starting to parse apex files");
        List<PackageInfo> list = new ArrayList<>();
        // TODO: this can be parallelized.
        for (ApexInfo ai : mApexFiles) {
            try {
                // If the device is using flattened APEX, don't report any APEX
                // packages since they won't be managed or updated by PackageManager.
                if ((new File(ai.packagePath)).isDirectory()) {
                    break;
                }
                list.add(PackageParser.generatePackageInfoFromApex(
                        new File(ai.packagePath), PackageManager.GET_META_DATA
                                | PackageManager.GET_SIGNING_CERTIFICATES));
            } catch (PackageParserException pe) {
                // TODO: make sure this error is propagated to system server.
                throw new IllegalStateException("Unable to parse: " + ai, pe);
            }
        }
        mActivePackagesCache = list.stream().collect(
                Collectors.toMap(p -> p.packageName, Function.identity()));
        mActivePackagesCacheLatch.countDown();
        Slog.i(TAG, "Finished parsing apex files in " + (SystemClock.currentTimeMicro()
                - startTimeMicros) + " μs");
    }

    /**
     * Retrieves information about an active APEX package.
     *
     * <p>This method blocks caller thread until {@link #parseApexFiles()} succeeds. Note that in
     * case {@link #parseApexFiles()}} throws an exception this method will never finish
     * essentially putting device into a boot loop.
     *
     * @param packageName the package name to look for. Note that this is the package name reported
     *                    in the APK container manifest (i.e. AndroidManifest.xml), which might
     *                    differ from the one reported in the APEX manifest (i.e.
     *                    apex_manifest.json).
     * @return a PackageInfo object with the information about the package, or null if the package
     *         is not found.
     */
    @Nullable PackageInfo getActivePackage(String packageName) {
        waitForLatch(mActivePackagesCacheLatch);
        return mActivePackagesCache.get(packageName);
    }

    /**
     * Retrieves information about all active APEX packages.
     *
     * <p>This method blocks caller thread until {@link #parseApexFiles()} succeeds. Note that in
     * case {@link #parseApexFiles()}} throws an exception this method will never finish
     * essentially putting device into a boot loop.
     *
     * @return a Collection of PackageInfo object, each one containing information about a different
     *         active package.
     */
    Collection<PackageInfo> getActivePackages() {
        waitForLatch(mActivePackagesCacheLatch);
        return mActivePackagesCache.values();
    }

    /**
     * Checks if {@code packageName} is an apex package.
     *
     * <p>This method blocks caller thread until {@link #populateApexFilesCache()} succeeds. Note
     * that in case {@link #populateApexFilesCache()} throws an exception this method will never
     * finish essentially putting device into a boot loop.
     *
     * @param packageName package to check.
     * @return {@code true} if {@code packageName} is an apex package.
     */
    boolean isApexPackage(String packageName) {
        waitForLatch(mApexFilesCacheLatch);
        for (ApexInfo ai : mApexFiles) {
            if (ai.packageName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retrieves information about an apexd staged session i.e. the internal state used by apexd to
     * track the different states of a session.
     *
     * @param sessionId the identifier of the session.
     * @return an ApexSessionInfo object, or null if the session is not known.
     */
    @Nullable ApexSessionInfo getStagedSessionInfo(int sessionId) {
        try {
            ApexSessionInfo apexSessionInfo = mApexService.getStagedSessionInfo(sessionId);
            if (apexSessionInfo.isUnknown) {
                return null;
            }
            return apexSessionInfo;
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        }
    }

    /**
     * Submit a staged session to apex service. This causes the apex service to perform some initial
     * verification and accept or reject the session. Submitting a session successfully is not
     * enough for it to be activated at the next boot, the caller needs to call
     * {@link #markStagedSessionReady(int)}.
     *
     * @param sessionId the identifier of the {@link PackageInstallerSession} being submitted.
     * @param childSessionIds if {@code sessionId} is a multi-package session, this should contain
     *                        an array of identifiers of all the child sessions. Otherwise it should
     *                        be an empty array.
     * @param apexInfoList this is an output parameter, which needs to be initialized by tha caller
     *                     and will be filled with a list of {@link ApexInfo} objects, each of which
     *                     contains metadata about one of the packages being submitted as part of
     *                     the session.
     * @return whether the submission of the session was successful.
     */
    boolean submitStagedSession(
            int sessionId, @NonNull int[] childSessionIds, @NonNull ApexInfoList apexInfoList) {
        try {
            return mApexService.submitStagedSession(sessionId, childSessionIds, apexInfoList);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        }
    }

    /**
     * Mark a staged session previously submitted using {@code submitStagedSession} as ready to be
     * applied at next reboot.
     *
     * @param sessionId the identifier of the {@link PackageInstallerSession} being marked as ready.
     * @return true upon success, false if the session is unknown.
     */
    boolean markStagedSessionReady(int sessionId) {
        try {
            return mApexService.markStagedSessionReady(sessionId);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        }
    }

    /**
     * Marks a staged session as successful.
     *
     * <p>Only activated session can be marked as successful.
     *
     * @param sessionId the identifier of the {@link PackageInstallerSession} being marked as
     *                  successful.
     */
    void markStagedSessionSuccessful(int sessionId) {
        try {
            mApexService.markStagedSessionSuccessful(sessionId);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        } catch (Exception e) {
            // It is fine to just log an exception in this case. APEXd will be able to recover in
            // case markStagedSessionSuccessful fails.
            Slog.e(TAG, "Failed to mark session " + sessionId + " as successful", e);
        }
    }

    /**
     * Whether the current device supports the management of APEX packages.
     *
     * @return true if APEX packages can be managed on this device, false otherwise.
     */
    boolean isApexSupported() {
        return ApexProperties.updatable().orElse(false);
    }

    /**
     * Abandons the (only) active session previously submitted.
     *
     * @return {@code true} upon success, {@code false} if any remote exception occurs
     */
    boolean abortActiveSession() {
        try {
            mApexService.abortActiveSession();
            return true;
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            return false;
        }
    }

    /**
     * Uninstalls given {@code apexPackage}.
     *
     * <p>NOTE. Device must be rebooted in order for uninstall to take effect.
     *
     * @param apexPackagePath package to uninstall.
     * @return {@code true} upon successful uninstall, {@code false} otherwise.
     */
    boolean uninstallApex(String apexPackagePath) {
        try {
            mApexService.unstagePackages(Collections.singletonList(apexPackagePath));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Blocks current thread until {@code latch} has counted down to zero.
     *
     * @throws RuntimeException if thread was interrupted while waiting.
     */
    private void waitForLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted waiting for cache to be populated", e);
        }
    }

    /**
     * Dumps various state information to the provided {@link PrintWriter} object.
     *
     * @param pw the {@link PrintWriter} object to send information to.
     * @param packageName a {@link String} containing a package name, or {@code null}. If set, only
     *                    information about that specific package will be dumped.
     */
    void dump(PrintWriter pw, @Nullable String packageName) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
        ipw.println();
        ipw.println("Active APEX packages:");
        ipw.increaseIndent();
        try {
            waitForLatch(mActivePackagesCacheLatch);
            for (PackageInfo pi : mActivePackagesCache.values()) {
                if (packageName != null && !packageName.equals(pi.packageName)) {
                    continue;
                }
                ipw.println(pi.packageName);
                ipw.increaseIndent();
                ipw.println("Version: " + pi.versionCode);
                ipw.println("Path: " + pi.applicationInfo.sourceDir);
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();
            ipw.println();
            ipw.println("APEX session state:");
            ipw.increaseIndent();
            final ApexSessionInfo[] sessions = mApexService.getSessions();
            for (ApexSessionInfo si : sessions) {
                ipw.println("Session ID: " + si.sessionId);
                ipw.increaseIndent();
                if (si.isUnknown) {
                    ipw.println("State: UNKNOWN");
                } else if (si.isVerified) {
                    ipw.println("State: VERIFIED");
                } else if (si.isStaged) {
                    ipw.println("State: STAGED");
                } else if (si.isActivated) {
                    ipw.println("State: ACTIVATED");
                } else if (si.isActivationFailed) {
                    ipw.println("State: ACTIVATION FAILED");
                } else if (si.isSuccess) {
                    ipw.println("State: SUCCESS");
                } else if (si.isRollbackInProgress) {
                    ipw.println("State: ROLLBACK IN PROGRESS");
                } else if (si.isRolledBack) {
                    ipw.println("State: ROLLED BACK");
                } else if (si.isRollbackFailed) {
                    ipw.println("State: ROLLBACK FAILED");
                }
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();
        } catch (RemoteException e) {
            ipw.println("Couldn't communicate with apexd.");
        }
    }
}
