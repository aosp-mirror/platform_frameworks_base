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
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseLongArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.pm.Installer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CacheQuotaStrategy is a strategy for determining cache quotas using usage stats and foreground
 * time using the calculation as defined in the refuel rocket.
 */
public class CacheQuotaStrategy implements RemoteCallback.OnResultListener {
    private static final String TAG = "CacheQuotaStrategy";

    private final Object mLock = new Object();

    // XML Constants
    private static final String CACHE_INFO_TAG = "cache-info";
    private static final String ATTR_PREVIOUS_BYTES = "previousBytes";
    private static final String TAG_QUOTA = "quota";
    private static final String ATTR_UUID = "uuid";
    private static final String ATTR_UID = "uid";
    private static final String ATTR_QUOTA_IN_BYTES = "bytes";

    private final Context mContext;
    private final UsageStatsManagerInternal mUsageStats;
    private final Installer mInstaller;
    private final ArrayMap<String, SparseLongArray> mQuotaMap;
    private ServiceConnection mServiceConnection;
    private ICacheQuotaService mRemoteService;
    private AtomicFile mPreviousValuesFile;

    public CacheQuotaStrategy(
            Context context, UsageStatsManagerInternal usageStatsManager, Installer installer,
            ArrayMap<String, SparseLongArray> quotaMap) {
        mContext = Preconditions.checkNotNull(context);
        mUsageStats = Preconditions.checkNotNull(usageStatsManager);
        mInstaller = Preconditions.checkNotNull(installer);
        mQuotaMap = Preconditions.checkNotNull(quotaMap);
        mPreviousValuesFile = new AtomicFile(new File(
                new File(Environment.getDataDirectory(), "system"), "cachequota.xml"));
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
     * Returns a list of CacheQuotaHints which do not have their quotas filled out for apps
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
                            oneYearAgo, timeNow, /*obfuscateInstantApps=*/ false);
            if (stats == null) {
                continue;
            }

            for (UsageStats stat : stats) {
                String packageName = stat.getPackageName();
                try {
                    // We need the app info to determine the uid and the uuid of the volume
                    // where the app is installed.
                    ApplicationInfo appInfo = packageManager.getApplicationInfoAsUser(
                            packageName, 0, info.id);
                    requests.add(
                            new CacheQuotaHint.Builder()
                                    .setVolumeUuid(appInfo.volumeUuid)
                                    .setUid(appInfo.uid)
                                    .setUsageStats(stat)
                                    .setQuota(CacheQuotaHint.QUOTA_NOT_SET)
                                    .build());
                } catch (PackageManager.NameNotFoundException e) {
                    // This may happen if an app has a recorded usage, but has been uninstalled.
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
        pushProcessedQuotas(processedRequests);
        writeXmlToFile(processedRequests);
    }

    private void pushProcessedQuotas(List<CacheQuotaHint> processedRequests) {
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
                insertIntoQuotaMap(request.getVolumeUuid(),
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

    private void insertIntoQuotaMap(String volumeUuid, int userId, int appId, long quota) {
        SparseLongArray volumeMap = mQuotaMap.get(volumeUuid);
        if (volumeMap == null) {
            volumeMap = new SparseLongArray();
            mQuotaMap.put(volumeUuid, volumeMap);
        }
        volumeMap.put(UserHandle.getUid(userId, appId), quota);
    }

    private void disconnectService() {
        if (mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
            mServiceConnection = null;
        }
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

    private void writeXmlToFile(List<CacheQuotaHint> processedRequests) {
        FileOutputStream fileStream = null;
        try {
            XmlSerializer out = new FastXmlSerializer();
            fileStream = mPreviousValuesFile.startWrite();
            out.setOutput(fileStream, StandardCharsets.UTF_8.name());
            saveToXml(out, processedRequests, 0);
            mPreviousValuesFile.finishWrite(fileStream);
        } catch (Exception e) {
            Slog.e(TAG, "An error occurred while writing the cache quota file.", e);
            mPreviousValuesFile.failWrite(fileStream);
        }
    }

    /**
     * Initializes the quotas from the file.
     * @return the number of bytes that were free on the device when the quotas were last calced.
     */
    public long setupQuotasFromFile() throws IOException {
        Pair<Long, List<CacheQuotaHint>> cachedValues = null;
        try (FileInputStream stream = mPreviousValuesFile.openRead()) {
            try {
                cachedValues = readFromXml(stream);
            } catch (XmlPullParserException e) {
                throw new IllegalStateException(e.getMessage());
            }
        } catch (FileNotFoundException e) {
            // The file may not exist yet -- this isn't truly exceptional.
            return -1;
        }

        if (cachedValues == null) {
            Slog.e(TAG, "An error occurred while parsing the cache quota file.");
            return -1;
        }
        pushProcessedQuotas(cachedValues.second);

        return cachedValues.first;
    }

    @VisibleForTesting
    static void saveToXml(XmlSerializer out,
            List<CacheQuotaHint> requests, long bytesWhenCalculated) throws IOException {
        out.startDocument(null, true);
        out.startTag(null, CACHE_INFO_TAG);
        int requestSize = requests.size();
        out.attribute(null, ATTR_PREVIOUS_BYTES, Long.toString(bytesWhenCalculated));

        for (int i = 0; i < requestSize; i++) {
            CacheQuotaHint request = requests.get(i);
            out.startTag(null, TAG_QUOTA);
            String uuid = request.getVolumeUuid();
            if (uuid != null) {
                out.attribute(null, ATTR_UUID, request.getVolumeUuid());
            }
            out.attribute(null, ATTR_UID, Integer.toString(request.getUid()));
            out.attribute(null, ATTR_QUOTA_IN_BYTES, Long.toString(request.getQuota()));
            out.endTag(null, TAG_QUOTA);
        }
        out.endTag(null, CACHE_INFO_TAG);
        out.endDocument();
    }

    protected static Pair<Long, List<CacheQuotaHint>> readFromXml(InputStream inputStream)
            throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(inputStream, StandardCharsets.UTF_8.name());

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.START_TAG &&
                eventType != XmlPullParser.END_DOCUMENT) {
            eventType = parser.next();
        }

        if (eventType == XmlPullParser.END_DOCUMENT) {
            Slog.d(TAG, "No quotas found in quota file.");
            return null;
        }

        String tagName = parser.getName();
        if (!CACHE_INFO_TAG.equals(tagName)) {
            throw new IllegalStateException("Invalid starting tag.");
        }

        final List<CacheQuotaHint> quotas = new ArrayList<>();
        long previousBytes;
        try {
            previousBytes = Long.parseLong(parser.getAttributeValue(
                    null, ATTR_PREVIOUS_BYTES));
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Previous bytes formatted incorrectly; aborting quota read.");
        }

        eventType = parser.next();
        do {
            if (eventType == XmlPullParser.START_TAG) {
                tagName = parser.getName();
                if (TAG_QUOTA.equals(tagName)) {
                    CacheQuotaHint request = getRequestFromXml(parser);
                    if (request == null) {
                        continue;
                    }
                    quotas.add(request);
                }
            }
            eventType = parser.next();
        } while (eventType != XmlPullParser.END_DOCUMENT);
        return new Pair<>(previousBytes, quotas);
    }

    @VisibleForTesting
    static CacheQuotaHint getRequestFromXml(XmlPullParser parser) {
        try {
            String uuid = parser.getAttributeValue(null, ATTR_UUID);
            int uid = Integer.parseInt(parser.getAttributeValue(null, ATTR_UID));
            long bytes = Long.parseLong(parser.getAttributeValue(null, ATTR_QUOTA_IN_BYTES));
            return new CacheQuotaHint.Builder()
                    .setVolumeUuid(uuid).setUid(uid).setQuota(bytes).build();
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Invalid cache quota request, skipping.");
            return null;
        }
    }
}
