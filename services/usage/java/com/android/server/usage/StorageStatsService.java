/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.usage;

import android.app.AppOpsManager;
import android.app.usage.IStorageStatsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageSummary;
import android.content.Context;
import android.os.Binder;
import android.os.UserHandle;
import android.os.storage.StorageManager;

import com.android.server.SystemService;
import com.android.server.pm.Installer;

public class StorageStatsService extends IStorageStatsManager.Stub {
    private static final String TAG = "StorageStatsService";

    public static class Lifecycle extends SystemService {
        private StorageStatsService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new StorageStatsService(getContext());
            publishBinderService(Context.STORAGE_STATS_SERVICE, mService);
        }
    }

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final StorageManager mStorage;
    private final Installer mInstaller;

    public StorageStatsService(Context context) {
        mContext = context;
        mAppOps = context.getSystemService(AppOpsManager.class);
        mStorage = context.getSystemService(StorageManager.class);
        mInstaller = new Installer(context);
    }

    private void enforcePermission(int callingUid, String callingPackage) {
        final int mode = mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS,
                callingUid, callingPackage);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return;
            case AppOpsManager.MODE_DEFAULT:
                mContext.enforceCallingPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS, TAG);
            default:
                throw new SecurityException("Blocked by mode " + mode);
        }
    }

    @Override
    public StorageStats queryStats(String volumeUuid, int uid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);
        if (UserHandle.getUserId(uid) != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        // TODO: call installd to collect quota stats
        return null;
    }

    @Override
    public StorageSummary querySummary(String volumeUuid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);

        // TODO: call installd to collect quota stats
        return null;
    }
}
