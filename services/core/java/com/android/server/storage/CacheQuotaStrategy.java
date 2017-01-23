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

package com.android.server.storage;

import android.annotation.MainThread;
import android.app.usage.CacheQuotaHint;
import android.app.usage.CacheQuotaService;
import android.app.usage.ICacheQuotaService;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.util.Preconditions;
import com.android.server.pm.Installer;

import java.util.ArrayList;
import java.util.List;


/**
 * CacheQuotaStrategy is a strategy for determining cache quotas using usage stats and foreground
 * time using the calculation as defined in the refuel rocket.
 */
public class CacheQuotaStrategy implements RemoteCallback.OnResultListener {
    private static final String TAG = "CacheQuotaStrategy";

    private final Object mLock = new Object();

    private final Context mContext;
    private final UsageStatsManagerInternal mUsageStats;
    private final Installer mInstaller;
    private ServiceConnection mServiceConnection;
    private ICacheQuotaService mRemoteService;

    public CacheQuotaStrategy(
            Context context, UsageStatsManagerInternal usageStatsManager, Installer installer) {
        mContext = Preconditions.checkNotNull(context);
        mUsageStats = Preconditions.checkNotNull(usageStatsManager);
        mInstaller = Preconditions.checkNotNull(installer);
    }

    /**
     * Recalculates the quotas and stores them to installd.
     */
    public void recalculateQuotas() {
        createServiceConnection();

        ComponentName component = getServiceComponentName();
        if (component != null) {
            Intent intent = new Intent();
            intent.setComponent(component);
            mContext.bindServiceAsUser(
                    intent, mServiceConnection, Context.BIND_AUTO_CREATE, UserHandle.CURRENT);
        }
    }

    private void createServiceConnection() {
        // If we're already connected, don't create a new connection.
        if (mServiceConnection != null) {
            return;
        }

        mServiceConnection = new ServiceConnection() {
            @Override
            @MainThread
            public void onServiceConnected(ComponentName name, IBinder service) {
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        synchronized (mLock) {
                            mRemoteService = ICacheQuotaService.Stub.asInterface(service);
                            List<CacheQuotaHint> requests = getUnfulfilledRequests();
                            final RemoteCallback remoteCallback =
                                    new RemoteCallback(CacheQuotaStrategy.this);
                            try {
                                mRemoteService.computeCacheQuotaHints(remoteCallback, requests);
                            } catch (RemoteException ex) {
                                Slog.w(TAG,
                                        "Remote exception occurred while trying to get cache quota",
                                        ex);
                            }
                        }
                    }
                };
                AsyncTask.execute(runnable);
            }

            @Override
            @MainThread
            public void onServiceDisconnected(ComponentName name) {
                synchronized (mLock) {
                    mRemoteService = null;
                }
            }
        };
    }

    /**
     * Returns a list of CacheQuotaRequests which do not have their quotas filled out for apps
     * which have been used in the last year.
     */
    private List<CacheQuotaHint> getUnfulfilledRequests() {
        long timeNow = System.currentTimeMillis();
        long oneYearAgo = timeNow - DateUtils.YEAR_IN_MILLIS;

        List<CacheQuotaHint> requests = new ArrayList<>();
        UserManager um = mContext.getSystemService(UserManager.class);
        final List<UserInfo> users = um.getUsers();
        final int userCount = users.size();
        final PackageManager packageManager = mContext.getPackageManager();
        for (int i = 0; i < userCount; i++) {
            UserInfo info = users.get(i);
            List<UsageStats> stats =
                    mUsageStats.queryUsageStatsForUser(info.id, UsageStatsManager.INTERVAL_BEST,
                            oneYearAgo, timeNow);
            if (stats == null) {
                continue;
            }

            for (UsageStats stat : stats) {
                String packageName = stat.getPackageName();
                try {
                    // We need the app info to determine the uid and the uuid of the volume
                    // where the app is installed.
                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                    requests.add(
                            new CacheQuotaHint.Builder()
                                    .setVolumeUuid(appInfo.volumeUuid)
                                    .setUid(appInfo.uid)
                                    .setUsageStats(stat)
                                    .setQuota(CacheQuotaHint.QUOTA_NOT_SET)
                                    .build());
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.w(TAG, "Unable to find package for quota calculation", e);
                    continue;
                }
            }
        }
        return requests;
    }

    @Override
    public void onResult(Bundle data) {
        final List<CacheQuotaHint> processedRequests =
                data.getParcelableArrayList(
                        CacheQuotaService.REQUEST_LIST_KEY);
        final int requestSize = processedRequests.size();
        for (int i = 0; i < requestSize; i++) {
            CacheQuotaHint request = processedRequests.get(i);
            long proposedQuota = request.getQuota();
            if (proposedQuota == CacheQuotaHint.QUOTA_NOT_SET) {
                continue;
            }

            try {
                int uid = request.getUid();
                mInstaller.setAppQuota(request.getVolumeUuid(),
                        UserHandle.getUserId(uid),
                        UserHandle.getAppId(uid), proposedQuota);
            } catch (Installer.InstallerException ex) {
                Slog.w(TAG,
                        "Failed to set cache quota for " + request.getUid(),
                        ex);
            }
        }

        disconnectService();
    }

    private void disconnectService() {
        mContext.unbindService(mServiceConnection);
        mServiceConnection = null;
    }

    private ComponentName getServiceComponentName() {
        String packageName =
                mContext.getPackageManager().getServicesSystemSharedLibraryPackageName();
        if (packageName == null) {
            Slog.w(TAG, "could not access the cache quota service: no package!");
            return null;
        }

        Intent intent = new Intent(CacheQuotaService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.w(TAG, "No valid components found.");
            return null;
        }
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        return new ComponentName(serviceInfo.packageName, serviceInfo.name);
    }
}
