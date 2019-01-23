/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.IApexService;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.PackageParser.SigningDetails;
import android.content.pm.PackageParser.SigningDetails.SignatureSchemeVersion;
import android.content.pm.ParceledListSlice;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class handles staged install sessions, i.e. install sessions that require packages to
 * be installed only after a reboot.
 */
public class StagingManager {

    private static final String TAG = "StagingManager";

    private final PackageManagerService mPm;
    private final Handler mBgHandler;

    @GuardedBy("mStagedSessions")
    private final SparseArray<PackageInstallerSession> mStagedSessions = new SparseArray<>();

    StagingManager(PackageManagerService pm) {
        mPm = pm;
        mBgHandler = BackgroundThread.getHandler();
    }

    private void updateStoredSession(@NonNull PackageInstallerSession sessionInfo) {
        synchronized (mStagedSessions) {
            PackageInstallerSession storedSession = mStagedSessions.get(sessionInfo.sessionId);
            // storedSession might be null if a call to abortSession was made before the session
            // is updated.
            if (storedSession != null) {
                mStagedSessions.put(sessionInfo.sessionId, sessionInfo);
            }
        }
    }

    ParceledListSlice<PackageInstaller.SessionInfo> getSessions() {
        final List<PackageInstaller.SessionInfo> result = new ArrayList<>();
        synchronized (mStagedSessions) {
            for (int i = 0; i < mStagedSessions.size(); i++) {
                result.add(mStagedSessions.valueAt(i).generateInfo(false));
            }
        }
        return new ParceledListSlice<>(result);
    }

    private static boolean validateApexSignatureLocked(String apexPath, String packageName) {
        final SigningDetails signingDetails;
        try {
            signingDetails = ApkSignatureVerifier.verify(apexPath, SignatureSchemeVersion.JAR);
        } catch (PackageParserException e) {
            Slog.e(TAG, "Unable to parse APEX package: " + apexPath, e);
            return false;
        }

        final IApexService apex = IApexService.Stub.asInterface(
                ServiceManager.getService("apexservice"));
        final ApexInfo apexInfo;
        try {
            apexInfo = apex.getActivePackage(packageName);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact APEXD", re);
            return false;
        }

        if (apexInfo == null || TextUtils.isEmpty(apexInfo.packageName)) {
            // TODO: What is the right thing to do here ? This implies there's no active package
            // with the given name. This should never be the case in production (where we only
            // accept updates to existing APEXes) but may be required for testing.
            return true;
        }

        final SigningDetails existingSigningDetails;
        try {
            existingSigningDetails = ApkSignatureVerifier.verify(
                apexInfo.packagePath, SignatureSchemeVersion.JAR);
        } catch (PackageParserException e) {
            Slog.e(TAG, "Unable to parse APEX package: " + apexInfo.packagePath, e);
            return false;
        }

        // Now that we have both sets of signatures, demand that they're an exact match.
        if (Signature.areExactMatch(existingSigningDetails.signatures, signingDetails.signatures)) {
            return true;
        }

        return false;
    }

    private static boolean submitSessionToApexService(@NonNull PackageInstallerSession session,
                                                      List<PackageInstallerSession> childSessions,
                                                      ApexInfoList apexInfoList) {
        return sendSubmitStagedSessionRequest(
                session.sessionId,
                childSessions != null
                        ? childSessions.stream().mapToInt(s -> s.sessionId).toArray() :
                        new int[]{},
                apexInfoList);
    }

    private static boolean sendSubmitStagedSessionRequest(
            int sessionId, int[] childSessionIds, ApexInfoList apexInfoList) {
        final IApexService apex = IApexService.Stub.asInterface(
                ServiceManager.getService("apexservice"));
        boolean success;
        try {
            success = apex.submitStagedSession(sessionId, childSessionIds, apexInfoList);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            return false;
        }
        return success;
    }

    private static boolean isApexSession(@NonNull PackageInstallerSession session) {
        return (session.params.installFlags & PackageManager.INSTALL_APEX) != 0;
    }

    private void preRebootVerification(@NonNull PackageInstallerSession session) {
        boolean success = true;

        final ApexInfoList apexInfoList = new ApexInfoList();
        // APEX checks. For single-package sessions, check if they contain an APEX. For
        // multi-package sessions, find all the child sessions that contain an APEX.
        if (!session.isMultiPackage()
                && isApexSession(session)) {
            success = submitSessionToApexService(session, null, apexInfoList);
        } else if (session.isMultiPackage()) {
            List<PackageInstallerSession> childSessions =
                    Arrays.stream(session.getChildSessionIds())
                            // Retrieve cached sessions matching ids.
                            .mapToObj(i -> mStagedSessions.get(i))
                            // Filter only the ones containing APEX.
                            .filter(childSession -> isApexSession(childSession))
                            .collect(Collectors.toList());
            if (!childSessions.isEmpty()) {
                success = submitSessionToApexService(session, childSessions, apexInfoList);
            } // else this is a staged multi-package session with no APEX files.
        }

        if (success && (apexInfoList.apexInfos.length > 0)) {
            // For APEXes, we validate the signature here before we mark the session as ready,
            // so we fail the session early if there is a signature mismatch. For APKs, the
            // signature verification will be done by the package manager at the point at which
            // it applies the staged install.
            //
            // TODO: Decide whether we want to fail fast by detecting signature mismatches for APKs,
            // right away.
            for (ApexInfo apexPackage : apexInfoList.apexInfos) {
                if (!validateApexSignatureLocked(apexPackage.packagePath,
                        apexPackage.packageName)) {
                    success = false;
                    break;
                }
            }
        }

        if (success) {
            session.setStagedSessionReady();
        } else {
            session.setStagedSessionFailed(SessionInfo.VERIFICATION_FAILED);
        }
    }

    private void resumeSession(@NonNull PackageInstallerSession session) {
        // Check with apexservice whether the apex
        // packages have been activated.
        final IApexService apex = IApexService.Stub.asInterface(
                ServiceManager.getService("apexservice"));
        ApexSessionInfo apexSessionInfo;
        try {
            apexSessionInfo = apex.getStagedSessionInfo(session.sessionId);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            // TODO should we retry here? Mark the session as failed?
            return;
        }
        if (apexSessionInfo.isActivationFailed || apexSessionInfo.isUnknown) {
            session.setStagedSessionFailed(SessionInfo.ACTIVATION_FAILED);
        }
        if (apexSessionInfo.isActivated) {
            session.setStagedSessionApplied();
            // TODO(b/118865310) if multi-package proceed with the installation of APKs.
        }
        // TODO(b/118865310) if (apexSessionInfo.isVerified) { /* mark this as staged in apexd */ }
        // In every other case apexd will retry to apply the session at next boot.
    }

    void commitSession(@NonNull PackageInstallerSession session) {
        updateStoredSession(session);
        mBgHandler.post(() -> preRebootVerification(session));
    }

    void createSession(@NonNull PackageInstallerSession sessionInfo) {
        synchronized (mStagedSessions) {
            mStagedSessions.append(sessionInfo.sessionId, sessionInfo);
        }
    }

    void abortSession(@NonNull PackageInstallerSession session) {
        synchronized (mStagedSessions) {
            updateStoredSession(session);
            mStagedSessions.remove(session.sessionId);
        }
    }

    @GuardedBy("mStagedSessions")
    private boolean isMultiPackageSessionComplete(@NonNull PackageInstallerSession session) {
        // This method assumes that the argument is either a parent session of a multi-package
        // i.e. isMultiPackage() returns true, or that it is a child session, i.e.
        // hasParentSessionId() returns true.
        if (session.isMultiPackage()) {
            // Parent session of a multi-package group. Check that we restored all the children.
            for (int childSession : session.getChildSessionIds()) {
                if (mStagedSessions.get(childSession) == null) {
                    return false;
                }
            }
            return true;
        }
        if (session.hasParentSessionId()) {
            PackageInstallerSession parent = mStagedSessions.get(session.getParentSessionId());
            if (parent == null) {
                return false;
            }
            return isMultiPackageSessionComplete(parent);
        }
        Slog.wtf(TAG, "Attempting to restore an invalid multi-package session.");
        return false;
    }

    void restoreSession(@NonNull PackageInstallerSession session) {
        PackageInstallerSession sessionToResume = session;
        synchronized (mStagedSessions) {
            mStagedSessions.append(session.sessionId, session);
            // For multi-package sessions, we don't know in which order they will be restored. We
            // need to wait until we have restored all the session in a group before restoring them.
            if (session.isMultiPackage() || session.hasParentSessionId()) {
                if (!isMultiPackageSessionComplete(session)) {
                    // Still haven't recovered all sessions of the group, return.
                    return;
                }
                // Group recovered, find the parent if necessary and resume the installation.
                if (session.hasParentSessionId()) {
                    sessionToResume = mStagedSessions.get(session.getParentSessionId());
                }
            }
        }
        checkStateAndResume(sessionToResume);
    }

    private void checkStateAndResume(@NonNull PackageInstallerSession session) {
        // Check the state of the session and decide what to do next.
        if (session.isStagedSessionFailed() || session.isStagedSessionApplied()) {
            // Final states, nothing to do.
            return;
        }
        if (!session.isStagedSessionReady()) {
            // The framework got restarted before the pre-reboot verification could complete,
            // restart the verification.
            mBgHandler.post(() -> preRebootVerification(session));
        } else {
            // Session had already being marked ready. Start the checks to verify if there is any
            // follow-up work.
            // TODO(b/118865310): should this be synchronous to ensure it completes before
            //                    systemReady() finishes?
            mBgHandler.post(() -> resumeSession(session));
        }
    }
}
