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

package com.android.server.appop;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

/**
 * A testing class, which supports both xml and sqlite persistence for discrete ops, the class
 * logs warning if there is a mismatch in the behavior.
 */
class DiscreteOpsTestingShim extends DiscreteOpsRegistry {
    private static final String LOG_TAG = "DiscreteOpsTestingShim";
    private final DiscreteOpsRegistry mXmlRegistry;
    private final DiscreteOpsRegistry mSqlRegistry;

    DiscreteOpsTestingShim(DiscreteOpsRegistry xmlRegistry,
            DiscreteOpsRegistry sqlRegistry) {
        mXmlRegistry = xmlRegistry;
        mSqlRegistry = sqlRegistry;
    }

    @Override
    void recordDiscreteAccess(int uid, String packageName, @NonNull String deviceId, int op,
            @Nullable String attributionTag, int flags, int uidState, long accessTime,
            long accessDuration, int attributionFlags, int attributionChainId, int accessType) {
        long start = SystemClock.uptimeMillis();
        mXmlRegistry.recordDiscreteAccess(uid, packageName, deviceId, op, attributionTag, flags,
                uidState, accessTime, accessDuration, attributionFlags, attributionChainId,
                accessType);
        long start2 = SystemClock.uptimeMillis();
        mSqlRegistry.recordDiscreteAccess(uid, packageName, deviceId, op, attributionTag, flags,
                uidState, accessTime, accessDuration, attributionFlags, attributionChainId,
                accessType);
        long end = SystemClock.uptimeMillis();
        long xmlTimeTaken = start2 - start;
        long sqlTimeTaken = end - start2;
        Log.i(LOG_TAG,
                "recordDiscreteAccess: XML time taken : " + xmlTimeTaken + ", SQL time taken : "
                        + sqlTimeTaken + ", diff (sql - xml): " + (sqlTimeTaken - xmlTimeTaken));
    }


    @Override
    void writeAndClearOldAccessHistory() {
        mXmlRegistry.writeAndClearOldAccessHistory();
        mSqlRegistry.writeAndClearOldAccessHistory();
    }

    @Override
    void clearHistory() {
        mXmlRegistry.clearHistory();
        mSqlRegistry.clearHistory();
    }

    @Override
    void clearHistory(int uid, String packageName) {
        mXmlRegistry.clearHistory(uid, packageName);
        mSqlRegistry.clearHistory(uid, packageName);
    }

    @Override
    void offsetHistory(long offset) {
        mXmlRegistry.offsetHistory(offset);
        mSqlRegistry.offsetHistory(offset);
    }

    @Override
    void addFilteredDiscreteOpsToHistoricalOps(AppOpsManager.HistoricalOps result,
            long beginTimeMillis, long endTimeMillis, int filter, int uidFilter,
            @Nullable String packageNameFilter, @Nullable String[] opNamesFilter,
            @Nullable String attributionTagFilter, int flagsFilter,
            Set<String> attributionExemptPkgs) {
        AppOpsManager.HistoricalOps result2 =
                new AppOpsManager.HistoricalOps(beginTimeMillis, endTimeMillis);

        long start = System.currentTimeMillis();
        mXmlRegistry.addFilteredDiscreteOpsToHistoricalOps(result2, beginTimeMillis, endTimeMillis,
                filter, uidFilter, packageNameFilter, opNamesFilter, attributionTagFilter,
                flagsFilter, attributionExemptPkgs);
        long start2 = System.currentTimeMillis();
        mSqlRegistry.addFilteredDiscreteOpsToHistoricalOps(result, beginTimeMillis, endTimeMillis,
                filter, uidFilter, packageNameFilter, opNamesFilter, attributionTagFilter,
                flagsFilter, attributionExemptPkgs);
        long end = System.currentTimeMillis();
        long xmlTimeTaken = start2 - start;
        long sqlTimeTaken = end - start2;
        try {
            assertHistoricalOpsAreEquals(result, result2);
        } catch (Exception ex) {
            Slog.e(LOG_TAG, "different output when reading discrete ops", ex);
        }
        Log.i(LOG_TAG, "Read: XML time taken : " + xmlTimeTaken + ", SQL time taken : "
                + sqlTimeTaken + ", diff (sql - xml): " + (sqlTimeTaken - xmlTimeTaken));
    }

    void assertHistoricalOpsAreEquals(AppOpsManager.HistoricalOps sqlResult,
            AppOpsManager.HistoricalOps xmlResult) {
        assertEquals(sqlResult.getUidCount(), xmlResult.getUidCount());
        int uidCount = sqlResult.getUidCount();

        for (int i = 0; i < uidCount; i++) {
            AppOpsManager.HistoricalUidOps sqlUidOps = sqlResult.getUidOpsAt(i);
            AppOpsManager.HistoricalUidOps xmlUidOps = xmlResult.getUidOpsAt(i);
            Slog.i(LOG_TAG, "sql uid: " + sqlUidOps.getUid() + ", xml uid: " + xmlUidOps.getUid());
            assertEquals(sqlUidOps.getUid(), xmlUidOps.getUid());
            assertEquals(sqlUidOps.getPackageCount(), xmlUidOps.getPackageCount());

            int packageCount = sqlUidOps.getPackageCount();
            for (int p = 0; p < packageCount; p++) {
                AppOpsManager.HistoricalPackageOps sqlPackageOps = sqlUidOps.getPackageOpsAt(p);
                AppOpsManager.HistoricalPackageOps xmlPackageOps = xmlUidOps.getPackageOpsAt(p);
                Slog.i(LOG_TAG, "sql package: " + sqlPackageOps.getPackageName() + ", xml package: "
                        + xmlPackageOps.getPackageName());
                assertEquals(sqlPackageOps.getPackageName(), xmlPackageOps.getPackageName());
                assertEquals(sqlPackageOps.getAttributedOpsCount(),
                        xmlPackageOps.getAttributedOpsCount());

                int attrCount = sqlPackageOps.getAttributedOpsCount();
                for (int a = 0; a < attrCount; a++) {
                    AppOpsManager.AttributedHistoricalOps sqlAttrOps =
                            sqlPackageOps.getAttributedOpsAt(a);
                    AppOpsManager.AttributedHistoricalOps xmlAttrOps =
                            xmlPackageOps.getAttributedOpsAt(a);
                    Slog.i(LOG_TAG, "sql tag: " + sqlAttrOps.getTag() + ", xml tag: "
                            + xmlAttrOps.getTag());
                    assertEquals(sqlAttrOps.getTag(), xmlAttrOps.getTag());
                    assertEquals(sqlAttrOps.getOpCount(), xmlAttrOps.getOpCount());

                    int opCount = sqlAttrOps.getOpCount();
                    for (int o = 0; o < opCount; o++) {
                        AppOpsManager.HistoricalOp sqlHistoricalOp = sqlAttrOps.getOpAt(o);
                        AppOpsManager.HistoricalOp xmlHistoricalOp = xmlAttrOps.getOpAt(o);
                        Slog.i(LOG_TAG, "sql op: " + sqlHistoricalOp.getOpName() + ", xml op: "
                                + xmlHistoricalOp.getOpName());
                        assertEquals(sqlHistoricalOp.getOpName(), xmlHistoricalOp.getOpName());
                        assertEquals(sqlHistoricalOp.getDiscreteAccessCount(),
                                xmlHistoricalOp.getDiscreteAccessCount());

                        int accessCount = sqlHistoricalOp.getDiscreteAccessCount();
                        for (int x = 0; x < accessCount; x++) {
                            AppOpsManager.AttributedOpEntry sqlOpEntry =
                                    sqlHistoricalOp.getDiscreteAccessAt(x);
                            AppOpsManager.AttributedOpEntry xmlOpEntry =
                                    xmlHistoricalOp.getDiscreteAccessAt(x);
                            Slog.i(LOG_TAG, "sql keys: " + sqlOpEntry.collectKeys() + ", xml keys: "
                                    + xmlOpEntry.collectKeys());
                            assertEquals(sqlOpEntry.collectKeys(), xmlOpEntry.collectKeys());
                            assertEquals(sqlOpEntry.isRunning(), xmlOpEntry.isRunning());
                            ArraySet<Long> keys = sqlOpEntry.collectKeys();
                            final int keyCount = keys.size();
                            for (int k = 0; k < keyCount; k++) {
                                final long key = keys.valueAt(k);
                                final int flags = extractFlagsFromKey(key);
                                assertEquals(sqlOpEntry.getLastDuration(flags),
                                        xmlOpEntry.getLastDuration(flags));
                                assertEquals(sqlOpEntry.getLastProxyInfo(flags),
                                        xmlOpEntry.getLastProxyInfo(flags));
                                assertEquals(sqlOpEntry.getLastAccessTime(flags),
                                        xmlOpEntry.getLastAccessTime(flags));
                            }
                        }
                    }
                }
            }
        }
    }

    // code duplicated for assertions
    private static final int FLAGS_MASK = 0xFFFFFFFF;

    public static int extractFlagsFromKey(@AppOpsManager.DataBucketKey long key) {
        return (int) (key & FLAGS_MASK);
    }

    private void assertEquals(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalStateException("Actual (" + actual + ") is not equal to expected ("
                    + expected + ")");
        }
    }

    @Override
    void dump(@NonNull PrintWriter pw, int uidFilter, @Nullable String packageNameFilter,
            @Nullable String attributionTagFilter, int filter, int dumpOp,
            @NonNull SimpleDateFormat sdf, @NonNull Date date, @NonNull String prefix,
            int nDiscreteOps) {
        mXmlRegistry.dump(pw, uidFilter, packageNameFilter, attributionTagFilter, filter, dumpOp,
                sdf, date, prefix, nDiscreteOps);
        pw.println("--------------------------------------------------------");
        pw.println("--------------------------------------------------------");
        mSqlRegistry.dump(pw, uidFilter, packageNameFilter, attributionTagFilter, filter, dumpOp,
                sdf, date, prefix, nDiscreteOps);
    }
}
