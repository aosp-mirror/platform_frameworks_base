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
import java.util.List;

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

    private static boolean submitSessionToApexService(int sessionId, ApexInfoList apexInfoList) {
        final IApexService apex = IApexService.Stub.asInterface(
                ServiceManager.getService("apexservice"));
        boolean success;
        try {
            success = apex.submitStagedSession(sessionId, new int[0], apexInfoList);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            return false;
        }
        return success;
    }

    private void preRebootVerification(@NonNull PackageInstallerSession session) {
        boolean success = true;
        if ((session.params.installFlags & PackageManager.INSTALL_APEX) != 0) {

            final ApexInfoList apexInfoList = new ApexInfoList();

            if (!submitSessionToApexService(session.sessionId, apexInfoList)) {
                success = false;
            } else {
                // For APEXes, we validate the signature here before we mark the session as ready,
                // so we fail the session early if there is a signature mismatch. For APKs, the
                // signature verification will be done by the package manager at the point at which
                // it applies the staged install.
                //
                // TODO: Decide whether we want to fail fast by detecting signature mismatches right
                // away.
                for (ApexInfo apexPackage : apexInfoList.apexInfos) {
                    if (!validateApexSignatureLocked(apexPackage.packagePath,
                            apexPackage.packageName)) {
                        success = false;
                        break;
                    }
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

    void abortSession(@NonNull PackageInstallerSession sessionInfo) {
        updateStoredSession(sessionInfo);
        synchronized (mStagedSessions) {
            mStagedSessions.remove(sessionInfo.sessionId);
        }
    }

    void restoreSession(@NonNull PackageInstallerSession session) {
        updateStoredSession(session);
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
            mBgHandler.post(() -> resumeSession(session));
        }
    }
}
