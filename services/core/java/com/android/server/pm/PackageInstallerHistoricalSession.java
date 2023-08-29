/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.pm.PackageInstaller.PreapprovalDetails;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;

import com.android.internal.util.IndentingPrintWriter;

import java.io.CharArrayWriter;
import java.io.File;

/**
 * A historical session object that stores minimal session info.
 */
public final class PackageInstallerHistoricalSession {
    public final int sessionId;
    public final int userId;
    private final String mParams;
    private final long mCreatedMillis;

    private final File mStageDir;
    private final String mStageCid;

    private final long mUpdatedMillis;

    private final long mCommittedMillis;

    private final int mOriginalInstallerUid;

    private final String mOriginalInstallerPackageName;

    private final int mInstallerUid;

    private final InstallSource mInstallSource;

    private final float mClientProgress;

    private final float mProgress;
    private final boolean mSealed;

    private final boolean mPreapprovalRequested;
    private final boolean mCommitted;

    private final boolean mStageDirInUse;

    private final boolean mPermissionsManuallyAccepted;

    private final int mFinalStatus;
    private final String mFinalMessage;

    private final int mFds;
    private final int mBridges;

    private final String mPreapprovalDetails;
    private final int mParentSessionId;
    private final boolean mDestroyed;
    private final int[] mChildSessionIds;
    private final boolean mSessionApplied;
    private final boolean mSessionReady;
    private final boolean mSessionFailed;
    private final int mSessionErrorCode;
    private final String mSessionErrorMessage;

    PackageInstallerHistoricalSession(int sessionId, int userId, int originalInstallerUid,
            String originalInstallerPackageName, InstallSource installSource, int installerUid,
            long createdMillis, long updatedMillis, long committedMillis, File stageDir,
            String stageCid, float clientProgress, float progress, boolean committed,
            boolean preapprovalRequested, boolean sealed, boolean permissionsManuallyAccepted,
            boolean stageDirInUse, boolean destroyed, int fds, int bridges, int finalStatus,
            String finalMessage, SessionParams params, int parentSessionId,
            int[] childSessionIds, boolean sessionApplied, boolean sessionFailed,
            boolean sessionReady, int sessionErrorCode, String sessionErrorMessage,
            PreapprovalDetails preapprovalDetails) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.mOriginalInstallerUid = originalInstallerUid;
        this.mOriginalInstallerPackageName = originalInstallerPackageName;
        this.mInstallSource = installSource;
        this.mInstallerUid = installerUid;
        this.mCreatedMillis = createdMillis;
        this.mUpdatedMillis = updatedMillis;
        this.mCommittedMillis = committedMillis;
        this.mStageDir = stageDir;
        this.mStageCid = stageCid;
        this.mClientProgress = clientProgress;
        this.mProgress = progress;
        this.mCommitted = committed;
        this.mPreapprovalRequested = preapprovalRequested;
        this.mSealed = sealed;
        this.mPermissionsManuallyAccepted = permissionsManuallyAccepted;
        this.mStageDirInUse = stageDirInUse;
        this.mDestroyed = destroyed;
        this.mFds = fds;
        this.mBridges = bridges;
        this.mFinalStatus = finalStatus;
        this.mFinalMessage = finalMessage;

        CharArrayWriter writer = new CharArrayWriter();
        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "    ");
        params.dump(pw);
        this.mParams = writer.toString();

        this.mParentSessionId = parentSessionId;
        this.mChildSessionIds = childSessionIds;
        this.mSessionApplied = sessionApplied;
        this.mSessionFailed = sessionFailed;
        this.mSessionReady = sessionReady;
        this.mSessionErrorCode = sessionErrorCode;
        this.mSessionErrorMessage = sessionErrorMessage;
        if (preapprovalDetails != null) {
            this.mPreapprovalDetails = preapprovalDetails.toString();
        } else {
            this.mPreapprovalDetails = null;
        }
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Session " + sessionId + ":");
        pw.increaseIndent();

        pw.printPair("userId", userId);
        pw.printPair("mOriginalInstallerUid", mOriginalInstallerUid);
        pw.printPair("mOriginalInstallerPackageName", mOriginalInstallerPackageName);
        pw.printPair("installerPackageName", mInstallSource.mInstallerPackageName);
        pw.printPair("installInitiatingPackageName", mInstallSource.mInitiatingPackageName);
        pw.printPair("installOriginatingPackageName", mInstallSource.mOriginatingPackageName);
        pw.printPair("mInstallerUid", mInstallerUid);
        pw.printPair("createdMillis", mCreatedMillis);
        pw.printPair("updatedMillis", mUpdatedMillis);
        pw.printPair("committedMillis", mCommittedMillis);
        pw.printPair("stageDir", mStageDir);
        pw.printPair("stageCid", mStageCid);
        pw.println();

        pw.print(mParams);

        pw.printPair("mClientProgress", mClientProgress);
        pw.printPair("mProgress", mProgress);
        pw.printPair("mCommitted", mCommitted);
        pw.printPair("mPreapprovalRequested", mPreapprovalRequested);
        pw.printPair("mSealed", mSealed);
        pw.printPair("mPermissionsManuallyAccepted", mPermissionsManuallyAccepted);
        pw.printPair("mStageDirInUse", mStageDirInUse);
        pw.printPair("mDestroyed", mDestroyed);
        pw.printPair("mFds", mFds);
        pw.printPair("mBridges", mBridges);
        pw.printPair("mFinalStatus", mFinalStatus);
        pw.printPair("mFinalMessage", mFinalMessage);
        pw.printPair("mParentSessionId", mParentSessionId);
        pw.printPair("mChildSessionIds", mChildSessionIds);
        pw.printPair("mSessionApplied", mSessionApplied);
        pw.printPair("mSessionFailed", mSessionFailed);
        pw.printPair("mSessionReady", mSessionReady);
        pw.printPair("mSessionErrorCode", mSessionErrorCode);
        pw.printPair("mSessionErrorMessage", mSessionErrorMessage);
        pw.printPair("mPreapprovalDetails", mPreapprovalDetails);
        pw.println();

        pw.decreaseIndent();
    }

    /**
     * Generates a {@link SessionInfo} object.
     */
    public SessionInfo generateInfo() {
        final SessionInfo info = new SessionInfo();
        info.sessionId = sessionId;
        info.userId = userId;
        info.installerPackageName = mInstallSource.mInstallerPackageName;
        info.installerAttributionTag = mInstallSource.mInstallerAttributionTag;
        info.progress = mProgress;
        info.sealed = mSealed;
        info.isCommitted = mCommitted;
        info.isPreapprovalRequested = mPreapprovalRequested;

        info.parentSessionId = mParentSessionId;
        info.childSessionIds = mChildSessionIds;
        info.isSessionApplied = mSessionApplied;
        info.isSessionReady = mSessionReady;
        info.isSessionFailed = mSessionFailed;
        info.setSessionErrorCode(mSessionErrorCode, mSessionErrorMessage);
        info.createdMillis = mCreatedMillis;
        info.updatedMillis = mUpdatedMillis;
        info.installerUid = mInstallerUid;
        return info;
    }
}
