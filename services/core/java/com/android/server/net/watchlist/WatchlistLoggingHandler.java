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

package com.android.server.net.watchlist;

import static android.os.incremental.IncrementalManager.isIncrementalPath;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.HexDump;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A Handler class for network watchlist logging on a background thread.
 */
class WatchlistLoggingHandler extends Handler {

    private static final String TAG = WatchlistLoggingHandler.class.getSimpleName();
    private static final boolean DEBUG = NetworkWatchlistService.DEBUG;

    @VisibleForTesting
    static final int LOG_WATCHLIST_EVENT_MSG = 1;
    @VisibleForTesting
    static final int REPORT_RECORDS_IF_NECESSARY_MSG = 2;
    @VisibleForTesting
    static final int FORCE_REPORT_RECORDS_NOW_FOR_TEST_MSG = 3;

    private static final long ONE_DAY_MS = TimeUnit.DAYS.toMillis(1);
    private static final String DROPBOX_TAG = "network_watchlist_report";

    private final Context mContext;
    private final @Nullable DropBoxManager mDropBoxManager;
    private final ContentResolver mResolver;
    private final PackageManager mPm;
    private final WatchlistReportDbHelper mDbHelper;
    private final WatchlistConfig mConfig;
    private final WatchlistSettings mSettings;
    private int mPrimaryUserId = -1;
    // A cache for uid and apk digest mapping.
    // As uid won't be reused until reboot, it's safe to assume uid is unique per signature and app.
    // TODO: Use more efficient data structure.
    private final ConcurrentHashMap<Integer, byte[]> mCachedUidDigestMap =
            new ConcurrentHashMap<>();

    private final FileHashCache mApkHashCache;

    private interface WatchlistEventKeys {
        String HOST = "host";
        String IP_ADDRESSES = "ipAddresses";
        String UID = "uid";
        String TIMESTAMP = "timestamp";
    }

    WatchlistLoggingHandler(Context context, Looper looper) {
        super(looper);
        mContext = context;
        mPm = mContext.getPackageManager();
        mResolver = mContext.getContentResolver();
        mDbHelper = WatchlistReportDbHelper.getInstance(context);
        mConfig = WatchlistConfig.getInstance();
        mSettings = WatchlistSettings.getInstance();
        mDropBoxManager = mContext.getSystemService(DropBoxManager.class);
        mPrimaryUserId = getPrimaryUserId();
        if (context.getResources().getBoolean(
                com.android.internal.R.bool.config_watchlistUseFileHashesCache)) {
            mApkHashCache = new FileHashCache(this);
            Slog.i(TAG, "Using file hashes cache.");
        } else {
            mApkHashCache = null;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case LOG_WATCHLIST_EVENT_MSG: {
                final Bundle data = msg.getData();
                handleNetworkEvent(
                        data.getString(WatchlistEventKeys.HOST),
                        data.getStringArray(WatchlistEventKeys.IP_ADDRESSES),
                        data.getInt(WatchlistEventKeys.UID),
                        data.getLong(WatchlistEventKeys.TIMESTAMP)
                );
                break;
            }
            case REPORT_RECORDS_IF_NECESSARY_MSG:
                tryAggregateRecords(getLastMidnightTime());
                break;
            case FORCE_REPORT_RECORDS_NOW_FOR_TEST_MSG:
                if (msg.obj instanceof Long) {
                    long lastRecordTime = (Long) msg.obj;
                    tryAggregateRecords(lastRecordTime);
                } else {
                    Slog.e(TAG, "Msg.obj needs to be a Long object.");
                }
                break;
            default: {
                Slog.d(TAG, "WatchlistLoggingHandler received an unknown of message.");
                break;
            }
        }
    }

    /**
     * Get primary user id.
     * @return Primary user id. -1 if primary user not found.
     */
    private int getPrimaryUserId() {
        final UserInfo primaryUserInfo = ((UserManager) mContext.getSystemService(
                Context.USER_SERVICE)).getPrimaryUser();
        if (primaryUserInfo != null) {
            return primaryUserInfo.id;
        }
        return -1;
    }

    /**
     * Return if a given package has testOnly is true.
     */
    private boolean isPackageTestOnly(int uid) {
        final ApplicationInfo ai;
        try {
            final String[] packageNames = mPm.getPackagesForUid(uid);
            if (packageNames == null || packageNames.length == 0) {
                Slog.e(TAG, "Couldn't find package: " + Arrays.toString(packageNames));
                return false;
            }
            ai = mPm.getApplicationInfo(packageNames[0], 0);
        } catch (NameNotFoundException e) {
            // Should not happen.
            return false;
        }
        return (ai.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0;
    }

    /**
     * Report network watchlist records if we collected enough data.
     */
    public void reportWatchlistIfNecessary() {
        final Message msg = obtainMessage(REPORT_RECORDS_IF_NECESSARY_MSG);
        sendMessage(msg);
    }

    public void forceReportWatchlistForTest(long lastReportTime) {
        final Message msg = obtainMessage(FORCE_REPORT_RECORDS_NOW_FOR_TEST_MSG);
        msg.obj = lastReportTime;
        sendMessage(msg);
    }

    /**
     * Insert network traffic event to watchlist async queue processor.
     */
    public void asyncNetworkEvent(String host, String[] ipAddresses, int uid) {
        final Message msg = obtainMessage(LOG_WATCHLIST_EVENT_MSG);
        final Bundle bundle = new Bundle();
        bundle.putString(WatchlistEventKeys.HOST, host);
        bundle.putStringArray(WatchlistEventKeys.IP_ADDRESSES, ipAddresses);
        bundle.putInt(WatchlistEventKeys.UID, uid);
        bundle.putLong(WatchlistEventKeys.TIMESTAMP, System.currentTimeMillis());
        msg.setData(bundle);
        sendMessage(msg);
    }

    private void handleNetworkEvent(String hostname, String[] ipAddresses,
            int uid, long timestamp) {
        if (DEBUG) {
            Slog.i(TAG, "handleNetworkEvent with host: " + hostname + ", uid: " + uid);
        }
        // Update primary user id if necessary
        if (mPrimaryUserId == -1) {
            mPrimaryUserId = getPrimaryUserId();
        }

        // Only process primary user data
        if (UserHandle.getUserId(uid) != mPrimaryUserId) {
            if (DEBUG) {
                Slog.i(TAG, "Do not log non-system user records");
            }
            return;
        }
        final String cncDomain = searchAllSubDomainsInWatchlist(hostname);
        if (cncDomain != null) {
            insertRecord(uid, cncDomain, timestamp);
        } else {
            final String cncIp = searchIpInWatchlist(ipAddresses);
            if (cncIp != null) {
                insertRecord(uid, cncIp, timestamp);
            }
        }
    }

    private void insertRecord(int uid, String cncHost, long timestamp) {
        if (DEBUG) {
            Slog.i(TAG, "trying to insert record with host: " + cncHost + ", uid: " + uid);
        }
        if (!mConfig.isConfigSecure() && !isPackageTestOnly(uid)) {
            // Skip package if config is not secure and package is not TestOnly app.
            if (DEBUG) {
                Slog.i(TAG, "uid: " + uid + " is not test only package");
            }
            return;
        }
        final byte[] digest = getDigestFromUid(uid);
        if (digest == null) {
            return;
        }
        if (mDbHelper.insertNewRecord(digest, cncHost, timestamp)) {
            Slog.w(TAG, "Unable to insert record for uid: " + uid);
        }
    }

    private boolean shouldReportNetworkWatchlist(long lastRecordTime) {
        final long lastReportTime = Settings.Global.getLong(mResolver,
                Settings.Global.NETWORK_WATCHLIST_LAST_REPORT_TIME, 0L);
        if (lastRecordTime < lastReportTime) {
            Slog.i(TAG, "Last report time is larger than current time, reset report");
            mDbHelper.cleanup(lastReportTime);
            return false;
        }
        return lastRecordTime >= lastReportTime + ONE_DAY_MS;
    }

    private void tryAggregateRecords(long lastRecordTime) {
        long startTime = System.currentTimeMillis();
        try {
            // Check if it's necessary to generate watchlist report now.
            if (!shouldReportNetworkWatchlist(lastRecordTime)) {
                Slog.i(TAG, "No need to aggregate record yet.");
                return;
            }
            Slog.i(TAG, "Start aggregating watchlist records.");
            if (mDropBoxManager != null && mDropBoxManager.isTagEnabled(DROPBOX_TAG)) {
                Settings.Global.putLong(mResolver,
                        Settings.Global.NETWORK_WATCHLIST_LAST_REPORT_TIME,
                        lastRecordTime);
                final WatchlistReportDbHelper.AggregatedResult aggregatedResult =
                        mDbHelper.getAggregatedRecords(lastRecordTime);
                if (aggregatedResult == null) {
                    Slog.i(TAG, "Cannot get result from database");
                    return;
                }
                // Get all digests for watchlist report, it should include all installed
                // application digests and previously recorded app digests.
                final List<String> digestsForReport = getAllDigestsForReport(aggregatedResult);
                final byte[] secretKey = mSettings.getPrivacySecretKey();
                final byte[] encodedResult = ReportEncoder.encodeWatchlistReport(mConfig,
                        secretKey, digestsForReport, aggregatedResult);
                if (encodedResult != null) {
                    addEncodedReportToDropBox(encodedResult);
                }
            } else {
                Slog.w(TAG, "Network Watchlist dropbox tag is not enabled");
            }
            mDbHelper.cleanup(lastRecordTime);
        } finally {
            long endTime = System.currentTimeMillis();
            Slog.i(TAG, "Milliseconds spent on tryAggregateRecords(): " + (endTime - startTime));
        }
    }

    /**
     * Get all digests for watchlist report.
     * It should include:
     * (1) All installed app digests. We need this because we need to ensure after DP we don't know
     * if an app is really visited C&C site.
     * (2) App digests that previously recorded in database.
     */
    @VisibleForTesting
    List<String> getAllDigestsForReport(WatchlistReportDbHelper.AggregatedResult record) {
        // Step 1: Get all installed application digests.
        final List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                PackageManager.MATCH_ALL);
        final HashSet<String> result = new HashSet<>(apps.size() + record.appDigestCNCList.size());
        final int size = apps.size();
        for (int i = 0; i < size; i++) {
            byte[] digest = getDigestFromUid(apps.get(i).uid);
            if (digest != null) {
                result.add(HexDump.toHexString(digest));
            }
        }
        // Step 2: Add all digests from records
        result.addAll(record.appDigestCNCList.keySet());
        return new ArrayList<>(result);
    }

    private void addEncodedReportToDropBox(byte[] encodedReport) {
        mDropBoxManager.addData(DROPBOX_TAG, encodedReport, 0);
    }

    /**
     * Get app digest from app uid.
     * Return null if system cannot get digest from uid.
     */
    @Nullable
    private byte[] getDigestFromUid(int uid) {
        return mCachedUidDigestMap.computeIfAbsent(uid, key -> {
            final String[] packageNames = mPm.getPackagesForUid(key);
            final int userId = UserHandle.getUserId(uid);
            if (!ArrayUtils.isEmpty(packageNames)) {
                for (String packageName : packageNames) {
                    try {
                        final String apkPath = mPm.getPackageInfoAsUser(packageName,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId)
                                .applicationInfo.publicSourceDir;
                        if (TextUtils.isEmpty(apkPath)) {
                            Slog.w(TAG, "Cannot find apkPath for " + packageName);
                            continue;
                        }
                        if (isIncrementalPath(apkPath)) {
                            // Do not scan incremental fs apk, as the whole APK may not yet
                            // be available, so we can't compute the hash of it.
                            Slog.i(TAG, "Skipping incremental path: " + packageName);
                            continue;
                        }
                        return mApkHashCache != null
                                ? mApkHashCache.getSha256Hash(new File(apkPath))
                                : DigestUtils.getSha256Hash(new File(apkPath));
                    } catch (NameNotFoundException | NoSuchAlgorithmException | IOException e) {
                        Slog.e(TAG, "Cannot get digest from uid: " + key
                                + ",pkg: " + packageName, e);
                        return null;
                    }
                }
            }
            // Not able to find a package name for this uid, possibly the package is installed on
            // another user.
            return null;
        });
    }

    /**
     * Search if any ip addresses are in watchlist.
     *
     * @param ipAddresses Ip address that you want to search in watchlist.
     * @return Ip address that exists in watchlist, null if it does not match anything.
     */
    @Nullable
    private String searchIpInWatchlist(String[] ipAddresses) {
        for (String ipAddress : ipAddresses) {
            if (isIpInWatchlist(ipAddress)) {
                return ipAddress;
            }
        }
        return null;
    }

    /** Search if the ip is in watchlist */
    private boolean isIpInWatchlist(String ipAddr) {
        if (ipAddr == null) {
            return false;
        }
        return mConfig.containsIp(ipAddr);
    }

    /** Search if the host is in watchlist */
    private boolean isHostInWatchlist(String host) {
        if (host == null) {
            return false;
        }
        return mConfig.containsDomain(host);
    }

    /**
     * Search if any sub-domain in host is in watchlist.
     *
     * @param host Host that we want to search.
     * @return Domain that exists in watchlist, null if it does not match anything.
     */
    @Nullable
    private String searchAllSubDomainsInWatchlist(String host) {
        if (host == null) {
            return null;
        }
        final String[] subDomains = getAllSubDomains(host);
        for (String subDomain : subDomains) {
            if (isHostInWatchlist(subDomain)) {
                return subDomain;
            }
        }
        return null;
    }

    /** Get all sub-domains in a host */
    @VisibleForTesting
    @Nullable
    static String[] getAllSubDomains(String host) {
        if (host == null) {
            return null;
        }
        final ArrayList<String> subDomainList = new ArrayList<>();
        subDomainList.add(host);
        int index = host.indexOf(".");
        while (index != -1) {
            host = host.substring(index + 1);
            if (!TextUtils.isEmpty(host)) {
                subDomainList.add(host);
            }
            index = host.indexOf(".");
        }
        return subDomainList.toArray(new String[0]);
    }

    static long getLastMidnightTime() {
        return getMidnightTimestamp(0);
    }

    static long getMidnightTimestamp(int daysBefore) {
        java.util.Calendar date = new GregorianCalendar();
        // reset hour, minutes, seconds and millis
        date.set(java.util.Calendar.HOUR_OF_DAY, 0);
        date.set(java.util.Calendar.MINUTE, 0);
        date.set(java.util.Calendar.SECOND, 0);
        date.set(java.util.Calendar.MILLISECOND, 0);
        date.add(java.util.Calendar.DAY_OF_MONTH, -daysBefore);
        return date.getTimeInMillis();
    }
}
