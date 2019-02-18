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
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ApexManager class handles communications with the apex service to perform operation and queries,
 * as well as providing caching to avoid unnecessary calls to the service.
 */
class ApexManager {
    static final String TAG = "ApexManager";
    private final IApexService mApexService;
    private final Map<String, PackageInfo> mActivePackagesCache;

    ApexManager() {
        mApexService = IApexService.Stub.asInterface(
            ServiceManager.getService("apexservice"));
        mActivePackagesCache = populateActivePackagesCache();
    }

    @NonNull
    private Map<String, PackageInfo> populateActivePackagesCache() {
        try {
            List<PackageInfo> list = new ArrayList<>();
            final ApexInfo[] activePkgs = mApexService.getActivePackages();
            for (ApexInfo ai : activePkgs) {
                // If the device is using flattened APEX, don't report any APEX
                // packages since they won't be managed or updated by PackageManager.
                if ((new File(ai.packagePath)).isDirectory()) {
                    break;
                }
                try {
                    list.add(PackageParser.generatePackageInfoFromApex(
                            new File(ai.packagePath), true /* collect certs */));
                } catch (PackageParserException pe) {
                    throw new IllegalStateException("Unable to parse: " + ai, pe);
                }
            }
            return list.stream().collect(Collectors.toMap(p -> p.packageName, Function.identity()));
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to retrieve packages from apexservice: " + re.toString());
            throw new RuntimeException(re);
        }
    }

    /**
     * Retrieves information about an active APEX package.
     *
     * @param packageName the package name to look for. Note that this is the package name reported
     *                    in the APK container manifest (i.e. AndroidManifest.xml), which might
     *                    differ from the one reported in the APEX manifest (i.e.
     *                    apex_manifest.json).
     * @return a PackageInfo object with the information about the package, or null if the package
     *         is not found.
     */
    @Nullable PackageInfo getActivePackage(String packageName) {
        return mActivePackagesCache.get(packageName);
    }

    /**
     * Retrieves information about all active APEX packages.
     *
     * @return a Collection of PackageInfo object, each one containing information about a different
     *         active package.
     */
    Collection<PackageInfo> getActivePackages() {
        return mActivePackagesCache.values();
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
     * Mark a staged session previously submitted using {@cde submitStagedSession} as ready to be
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
        // There is no system-wide property available to check if APEX are flattened and hence can't
        // be updated. In absence of such property, we assume that if we didn't index APEX packages
        // since they were flattened, no APEX management should be possible.
        return !mActivePackagesCache.isEmpty();
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
            populateActivePackagesCache();
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
                } else if (si.isActivationPendingRetry) {
                    ipw.println("State: ACTIVATION PENDING RETRY");
                } else if (si.isActivationFailed) {
                    ipw.println("State: ACTIVATION FAILED");
                }
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();
        } catch (RemoteException e) {
            ipw.println("Couldn't communicate with apexd.");
        }
    }
}
