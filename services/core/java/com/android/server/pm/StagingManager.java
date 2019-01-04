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
import android.content.pm.PackageInstaller;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.util.SparseArray;

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

    // STOPSHIP: This is a temporary mock implementation of staged sessions. This variable
    //           shouldn't be needed at all.
    // TODO(b/118865310): Implement staged sessions logic.
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

    void commitSession(@NonNull PackageInstallerSession sessionInfo) {
        updateStoredSession(sessionInfo);

        mBgHandler.post(() -> {
            // TODO(b/118865310): Dispatch the session to apexd/PackageManager for verification. For
            //                    now we directly mark it as ready.
            sessionInfo.setStagedSessionReady();
            mPm.sendSessionUpdatedBroadcast(sessionInfo.generateInfo(), sessionInfo.userId);
        });
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
}
