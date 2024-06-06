/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.os.Process.INVALID_UID;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.UidObserver;
import android.os.Process;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Use to monitor UIDs are really killed by the {@link IUidObserver}
 */
final class KillAppBlocker {
    private static final int MAX_WAIT_TIMEOUT_MS = 1000;
    private CountDownLatch mUidsGoneCountDownLatch = new CountDownLatch(1);
    private List mActiveUids = new ArrayList();
    private boolean mRegistered = false;

    private final IUidObserver mUidObserver = new UidObserver() {
        @Override
        public void onUidGone(int uid, boolean disabled) {
            synchronized (this) {
                mActiveUids.remove((Integer) uid);

                if (mActiveUids.size() == 0) {
                    mUidsGoneCountDownLatch.countDown();
                }
            }
        }
    };

    void register() {
        if (!mRegistered) {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.registerUidObserver(mUidObserver, ActivityManager.UID_OBSERVER_GONE,
                            ActivityManager.PROCESS_STATE_UNKNOWN, "pm");
                    mRegistered = true;
                } catch (RemoteException e) {
                    // no-op
                }
            }
        }
    }

    void unregister() {
        if (mRegistered) {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    mRegistered = false;
                    am.unregisterUidObserver(mUidObserver);
                } catch (RemoteException e) {
                    // no-op
                }
            }
        }
    }

    void waitAppProcessGone(ActivityManagerInternal mAmi, Computer snapshot,
            UserManagerService userManager, String packageName) {
        if (!mRegistered) {
            return;
        }
        synchronized (this) {
            if (mAmi != null) {
                int[] users = userManager.getUserIds();

                for (int i = 0; i < users.length; i++) {
                    final int userId = users[i];
                    final int uid = snapshot.getPackageUidInternal(
                            packageName, MATCH_ALL, userId, Process.SYSTEM_UID);
                    if (uid != INVALID_UID) {
                        if (mAmi.getUidProcessState(uid) != PROCESS_STATE_NONEXISTENT) {
                            mActiveUids.add(uid);
                        }
                    }
                }
            }
        }

        try {
            mUidsGoneCountDownLatch.await(MAX_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // no-op
        }
    }
}
