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

import android.annotation.Nullable;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.util.Pair;

import com.android.internal.util.HexDump;

import java.io.File;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper class to process watchlist read / save watchlist reports.
 */
class WatchlistReportDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "WatchlistReportDbHelper";

    private static final String NAME = "watchlist_report.db";
    private static final int VERSION = 2;

    private static final int IDLE_CONNECTION_TIMEOUT_MS = 30000;

    private static class WhiteListReportContract {
        private static final String TABLE = "records";
        private static final String APP_DIGEST = "app_digest";
        private static final String CNC_DOMAIN = "cnc_domain";
        private static final String TIMESTAMP = "timestamp";
    }

    private static final String CREATE_TABLE_MODEL = "CREATE TABLE "
            + WhiteListReportContract.TABLE + "("
            + WhiteListReportContract.APP_DIGEST + " BLOB,"
            + WhiteListReportContract.CNC_DOMAIN + " TEXT,"
            + WhiteListReportContract.TIMESTAMP + " INTEGER DEFAULT 0" + " )";

    private static final int INDEX_DIGEST = 0;
    private static final int INDEX_CNC_DOMAIN = 1;
    private static final int INDEX_TIMESTAMP = 2;

    private static final String[] DIGEST_DOMAIN_PROJECTION =
            new String[] {
                    WhiteListReportContract.APP_DIGEST,
                    WhiteListReportContract.CNC_DOMAIN
            };

    private static WatchlistReportDbHelper sInstance;

    /**
     * Aggregated watchlist records.
     */
    public static class AggregatedResult {
        // A list of digests that visited c&c domain or ip before.
        final Set<String> appDigestList;

        // The c&c domain or ip visited before.
        @Nullable final String cncDomainVisited;

        // A list of app digests and c&c domain visited.
        final HashMap<String, String> appDigestCNCList;

        public AggregatedResult(Set<String> appDigestList, String cncDomainVisited,
                HashMap<String, String> appDigestCNCList) {
            this.appDigestList = appDigestList;
            this.cncDomainVisited = cncDomainVisited;
            this.appDigestCNCList = appDigestCNCList;
        }
    }

    static File getSystemWatchlistDbFile() {
        return new File(Environment.getDataSystemDirectory(), NAME);
    }

    private WatchlistReportDbHelper(Context context) {
        super(context, getSystemWatchlistDbFile().getAbsolutePath(), null, VERSION);
        // Memory optimization - close idle connections after 30s of inactivity
        setIdleConnectionTimeout(IDLE_CONNECTION_TIMEOUT_MS);
    }

    public static synchronized WatchlistReportDbHelper getInstance(Context context) {
        if (sInstance != null) {
            return sInstance;
        }
        sInstance = new WatchlistReportDbHelper(context);
        return sInstance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_MODEL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO: For now, drop older tables and recreate new ones.
        db.execSQL("DROP TABLE IF EXISTS " + WhiteListReportContract.TABLE);
        onCreate(db);
    }

    /**
     * Insert new watchlist record.
     *
     * @param appDigest The digest of an app.
     * @param cncDomain C&C domain that app visited.
     * @return True if success.
     */
    public boolean insertNewRecord(byte[] appDigest, String cncDomain,
            long timestamp) {
        final SQLiteDatabase db = getWritableDatabase();
        final ContentValues values = new ContentValues();
        values.put(WhiteListReportContract.APP_DIGEST, appDigest);
        values.put(WhiteListReportContract.CNC_DOMAIN, cncDomain);
        values.put(WhiteListReportContract.TIMESTAMP, timestamp);
        return db.insert(WhiteListReportContract.TABLE, null, values) != -1;
    }

    /**
     * Aggregate the records in database, and return a rappor encoded result.
     */
    public AggregatedResult getAggregatedRecords() {
        final long twoDaysBefore = getTwoDaysBeforeTimestamp();
        final long yesterday = getYesterdayTimestamp();
        final String selectStatement = WhiteListReportContract.TIMESTAMP + " >= ? AND " +
                WhiteListReportContract.TIMESTAMP + " <= ?";

        final SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(true /* distinct */,
                    WhiteListReportContract.TABLE, DIGEST_DOMAIN_PROJECTION, selectStatement,
                    new String[]{"" + twoDaysBefore, "" + yesterday}, null, null,
                    null, null);
            if (c == null) {
                return null;
            }
            final HashSet<String> appDigestList = new HashSet<>();
            final HashMap<String, String> appDigestCNCList = new HashMap<>();
            String cncDomainVisited = null;
            while (c.moveToNext()) {
                // We use hex string here as byte[] cannot be a key in HashMap.
                String digestHexStr = HexDump.toHexString(c.getBlob(INDEX_DIGEST));
                String cncDomain = c.getString(INDEX_CNC_DOMAIN);

                appDigestList.add(digestHexStr);
                if (cncDomainVisited != null) {
                    cncDomainVisited = cncDomain;
                }
                appDigestCNCList.put(digestHexStr, cncDomain);
            }
            return new AggregatedResult(appDigestList, cncDomainVisited, appDigestCNCList);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Remove all the records before yesterday.
     *
     * @return True if success.
     */
    public boolean cleanup() {
        final SQLiteDatabase db = getWritableDatabase();
        final long twoDaysBefore = getTwoDaysBeforeTimestamp();
        final String clause = WhiteListReportContract.TIMESTAMP + "< " + twoDaysBefore;
        return db.delete(WhiteListReportContract.TABLE, clause, null) != 0;
    }

    static long getTwoDaysBeforeTimestamp() {
        return getMidnightTimestamp(2);
    }

    static long getYesterdayTimestamp() {
        return getMidnightTimestamp(1);
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