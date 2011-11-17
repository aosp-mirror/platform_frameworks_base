/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;
import android.webkit.CookieManager.Cookie;
import android.webkit.CacheManager.CacheResult;
import android.webkit.JniUtil;

public class WebViewDatabase {
    private static final String DATABASE_FILE = "webview.db";
    private static final String CACHE_DATABASE_FILE = "webviewCache.db";

    // log tag
    protected static final String LOGTAG = "webviewdatabase";

    private static final int DATABASE_VERSION = 11;
    // 2 -> 3 Modified Cache table to allow cache of redirects
    // 3 -> 4 Added Oma-Downloads table
    // 4 -> 5 Modified Cache table to support persistent contentLength
    // 5 -> 4 Removed Oma-Downoads table
    // 5 -> 6 Add INDEX for cache table
    // 6 -> 7 Change cache localPath from int to String
    // 7 -> 8 Move cache to its own db
    // 8 -> 9 Store both scheme and host when storing passwords
    // 9 -> 10 Update httpauth table UNIQUE
    // 10 -> 11 Drop cookies and cache now managed by the chromium stack,
    //          and update the form data table to use the new format
    //          implemented for b/5265606.
    private static final int CACHE_DATABASE_VERSION = 4;
    // 1 -> 2 Add expires String
    // 2 -> 3 Add content-disposition
    // 3 -> 4 Add crossdomain (For x-permitted-cross-domain-policies header)

    private static WebViewDatabase mInstance = null;

    private static SQLiteDatabase mDatabase = null;
    private static SQLiteDatabase mCacheDatabase = null;

    // synchronize locks
    private final Object mCookieLock = new Object();
    private final Object mPasswordLock = new Object();
    private final Object mFormLock = new Object();
    private final Object mHttpAuthLock = new Object();

    // TODO: The Chromium HTTP stack handles cookies independently.
    // We should consider removing the cookies table if and when we switch to
    // the Chromium HTTP stack for good.
    private static final String mTableNames[] = {
        "cookies", "password", "formurl", "formdata", "httpauth"
    };

    // Table ids (they are index to mTableNames)
    private static final int TABLE_COOKIES_ID = 0;

    private static final int TABLE_PASSWORD_ID = 1;

    private static final int TABLE_FORMURL_ID = 2;

    private static final int TABLE_FORMDATA_ID = 3;

    private static final int TABLE_HTTPAUTH_ID = 4;

    // column id strings for "_id" which can be used by any table
    private static final String ID_COL = "_id";

    private static final String[] ID_PROJECTION = new String[] {
        "_id"
    };

    // column id strings for "cookies" table
    private static final String COOKIES_NAME_COL = "name";

    private static final String COOKIES_VALUE_COL = "value";

    private static final String COOKIES_DOMAIN_COL = "domain";

    private static final String COOKIES_PATH_COL = "path";

    private static final String COOKIES_EXPIRES_COL = "expires";

    private static final String COOKIES_SECURE_COL = "secure";

    // column id strings for "cache" table
    private static final String CACHE_URL_COL = "url";

    private static final String CACHE_FILE_PATH_COL = "filepath";

    private static final String CACHE_LAST_MODIFY_COL = "lastmodify";

    private static final String CACHE_ETAG_COL = "etag";

    private static final String CACHE_EXPIRES_COL = "expires";

    private static final String CACHE_EXPIRES_STRING_COL = "expiresstring";

    private static final String CACHE_MIMETYPE_COL = "mimetype";

    private static final String CACHE_ENCODING_COL = "encoding";

    private static final String CACHE_HTTP_STATUS_COL = "httpstatus";

    private static final String CACHE_LOCATION_COL = "location";

    private static final String CACHE_CONTENTLENGTH_COL = "contentlength";

    private static final String CACHE_CONTENTDISPOSITION_COL = "contentdisposition";

    private static final String CACHE_CROSSDOMAIN_COL = "crossdomain";

    // column id strings for "password" table
    private static final String PASSWORD_HOST_COL = "host";

    private static final String PASSWORD_USERNAME_COL = "username";

    private static final String PASSWORD_PASSWORD_COL = "password";

    // column id strings for "formurl" table
    private static final String FORMURL_URL_COL = "url";

    // column id strings for "formdata" table
    private static final String FORMDATA_URLID_COL = "urlid";

    private static final String FORMDATA_NAME_COL = "name";

    private static final String FORMDATA_VALUE_COL = "value";

    // column id strings for "httpauth" table
    private static final String HTTPAUTH_HOST_COL = "host";

    private static final String HTTPAUTH_REALM_COL = "realm";

    private static final String HTTPAUTH_USERNAME_COL = "username";

    private static final String HTTPAUTH_PASSWORD_COL = "password";

    // use InsertHelper to improve insert performance by 40%
    private static DatabaseUtils.InsertHelper mCacheInserter;
    private static int mCacheUrlColIndex;
    private static int mCacheFilePathColIndex;
    private static int mCacheLastModifyColIndex;
    private static int mCacheETagColIndex;
    private static int mCacheExpiresColIndex;
    private static int mCacheExpiresStringColIndex;
    private static int mCacheMimeTypeColIndex;
    private static int mCacheEncodingColIndex;
    private static int mCacheHttpStatusColIndex;
    private static int mCacheLocationColIndex;
    private static int mCacheContentLengthColIndex;
    private static int mCacheContentDispositionColIndex;
    private static int mCacheCrossDomainColIndex;

    private static int mCacheTransactionRefcount;

    // Initially true until the background thread completes.
    private boolean mInitialized = false;

    private WebViewDatabase(final Context context) {
        new Thread() {
            @Override
            public void run() {
                init(context);
            }
        }.start();

        // Singleton only, use getInstance()
    }

    public static synchronized WebViewDatabase getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new WebViewDatabase(context);
        }
        return mInstance;
    }

    private synchronized void init(Context context) {
        if (mInitialized) {
            return;
        }

        initDatabase(context);
        if (JniUtil.useChromiumHttpStack()) {
            context.deleteDatabase(CACHE_DATABASE_FILE);
        } else {
            initCacheDatabase(context);
        }

        // Thread done, notify.
        mInitialized = true;
        notify();
    }

    private void initDatabase(Context context) {
        try {
            mDatabase = context.openOrCreateDatabase(DATABASE_FILE, 0, null);
        } catch (SQLiteException e) {
            // try again by deleting the old db and create a new one
            if (context.deleteDatabase(DATABASE_FILE)) {
                mDatabase = context.openOrCreateDatabase(DATABASE_FILE, 0,
                        null);
            }
        }
        mDatabase.enableWriteAheadLogging();

        // mDatabase should not be null,
        // the only case is RequestAPI test has problem to create db
        if (mDatabase == null) {
            mInitialized = true;
            notify();
            return;
        }

        if (mDatabase.getVersion() != DATABASE_VERSION) {
            mDatabase.beginTransactionNonExclusive();
            try {
                upgradeDatabase();
                mDatabase.setTransactionSuccessful();
            } finally {
                mDatabase.endTransaction();
            }
        }

        // use per table Mutex lock, turn off database lock, this
        // improves performance as database's ReentrantLock is
        // expansive
        mDatabase.setLockingEnabled(false);
    }

    private void initCacheDatabase(Context context) {
        assert !JniUtil.useChromiumHttpStack();

        try {
            mCacheDatabase = context.openOrCreateDatabase(
                    CACHE_DATABASE_FILE, 0, null);
        } catch (SQLiteException e) {
            // try again by deleting the old db and create a new one
            if (context.deleteDatabase(CACHE_DATABASE_FILE)) {
                mCacheDatabase = context.openOrCreateDatabase(
                        CACHE_DATABASE_FILE, 0, null);
            }
        }
        mCacheDatabase.enableWriteAheadLogging();

        // mCacheDatabase should not be null,
        // the only case is RequestAPI test has problem to create db
        if (mCacheDatabase == null) {
            mInitialized = true;
            notify();
            return;
        }

        if (mCacheDatabase.getVersion() != CACHE_DATABASE_VERSION) {
            mCacheDatabase.beginTransactionNonExclusive();
            try {
                upgradeCacheDatabase();
                bootstrapCacheDatabase();
                mCacheDatabase.setTransactionSuccessful();
            } finally {
                mCacheDatabase.endTransaction();
            }
            // Erase the files from the file system in the
            // case that the database was updated and the
            // there were existing cache content
            CacheManager.removeAllCacheFiles();
        }

        // use read_uncommitted to speed up READ
        mCacheDatabase.execSQL("PRAGMA read_uncommitted = true;");
        // as only READ can be called in the
        // non-WebViewWorkerThread, and read_uncommitted is used,
        // we can turn off database lock to use transaction.
        mCacheDatabase.setLockingEnabled(false);

        // use InsertHelper for faster insertion
        mCacheInserter =
                new DatabaseUtils.InsertHelper(mCacheDatabase,
                        "cache");
        mCacheUrlColIndex = mCacheInserter
                            .getColumnIndex(CACHE_URL_COL);
        mCacheFilePathColIndex = mCacheInserter
                .getColumnIndex(CACHE_FILE_PATH_COL);
        mCacheLastModifyColIndex = mCacheInserter
                .getColumnIndex(CACHE_LAST_MODIFY_COL);
        mCacheETagColIndex = mCacheInserter
                .getColumnIndex(CACHE_ETAG_COL);
        mCacheExpiresColIndex = mCacheInserter
                .getColumnIndex(CACHE_EXPIRES_COL);
        mCacheExpiresStringColIndex = mCacheInserter
                .getColumnIndex(CACHE_EXPIRES_STRING_COL);
        mCacheMimeTypeColIndex = mCacheInserter
                .getColumnIndex(CACHE_MIMETYPE_COL);
        mCacheEncodingColIndex = mCacheInserter
                .getColumnIndex(CACHE_ENCODING_COL);
        mCacheHttpStatusColIndex = mCacheInserter
                .getColumnIndex(CACHE_HTTP_STATUS_COL);
        mCacheLocationColIndex = mCacheInserter
                .getColumnIndex(CACHE_LOCATION_COL);
        mCacheContentLengthColIndex = mCacheInserter
                .getColumnIndex(CACHE_CONTENTLENGTH_COL);
        mCacheContentDispositionColIndex = mCacheInserter
                .getColumnIndex(CACHE_CONTENTDISPOSITION_COL);
        mCacheCrossDomainColIndex = mCacheInserter
                .getColumnIndex(CACHE_CROSSDOMAIN_COL);
    }

    private static void upgradeDatabase() {
        upgradeDatabaseToV10();
        upgradeDatabaseFromV10ToV11();
        // Add future database upgrade functions here, one version at a
        // time.
        mDatabase.setVersion(DATABASE_VERSION);
    }

    private static void upgradeDatabaseFromV10ToV11() {
        int oldVersion = mDatabase.getVersion();

        if (oldVersion >= 11) {
            // Nothing to do.
            return;
        }

        if (JniUtil.useChromiumHttpStack()) {
            // Clear out old java stack cookies - this data is now stored in
            // a separate database managed by the Chrome stack.
            mDatabase.execSQL("DROP TABLE IF EXISTS " + mTableNames[TABLE_COOKIES_ID]);

            // Likewise for the old cache table.
            mDatabase.execSQL("DROP TABLE IF EXISTS cache");
        }

        // Update form autocomplete  URLs to match new ICS formatting.
        Cursor c = mDatabase.query(mTableNames[TABLE_FORMURL_ID], null, null,
                null, null, null, null);
        while (c.moveToNext()) {
            String urlId = Long.toString(c.getLong(c.getColumnIndex(ID_COL)));
            String url = c.getString(c.getColumnIndex(FORMURL_URL_COL));
            ContentValues cv = new ContentValues(1);
            cv.put(FORMURL_URL_COL, WebTextView.urlForAutoCompleteData(url));
            mDatabase.update(mTableNames[TABLE_FORMURL_ID], cv, ID_COL + "=?",
                    new String[] { urlId });
        }
        c.close();
    }

    private static void upgradeDatabaseToV10() {
        int oldVersion = mDatabase.getVersion();

        if (oldVersion >= 10) {
            // Nothing to do.
            return;
        }

        if (oldVersion != 0) {
            Log.i(LOGTAG, "Upgrading database from version "
                    + oldVersion + " to "
                    + DATABASE_VERSION + ", which will destroy old data");
        }

        if (9 == oldVersion) {
            mDatabase.execSQL("DROP TABLE IF EXISTS "
                    + mTableNames[TABLE_HTTPAUTH_ID]);
            mDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_HTTPAUTH_ID]
                    + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                    + HTTPAUTH_HOST_COL + " TEXT, " + HTTPAUTH_REALM_COL
                    + " TEXT, " + HTTPAUTH_USERNAME_COL + " TEXT, "
                    + HTTPAUTH_PASSWORD_COL + " TEXT," + " UNIQUE ("
                    + HTTPAUTH_HOST_COL + ", " + HTTPAUTH_REALM_COL
                    + ") ON CONFLICT REPLACE);");
            return;
        }

        mDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_COOKIES_ID]);
        mDatabase.execSQL("DROP TABLE IF EXISTS cache");
        mDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_FORMURL_ID]);
        mDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_FORMDATA_ID]);
        mDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_HTTPAUTH_ID]);
        mDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_PASSWORD_ID]);

        // cookies
        mDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_COOKIES_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                + COOKIES_NAME_COL + " TEXT, " + COOKIES_VALUE_COL
                + " TEXT, " + COOKIES_DOMAIN_COL + " TEXT, "
                + COOKIES_PATH_COL + " TEXT, " + COOKIES_EXPIRES_COL
                + " INTEGER, " + COOKIES_SECURE_COL + " INTEGER" + ");");
        mDatabase.execSQL("CREATE INDEX cookiesIndex ON "
                + mTableNames[TABLE_COOKIES_ID] + " (path)");

        // formurl
        mDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_FORMURL_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, " + FORMURL_URL_COL
                + " TEXT" + ");");

        // formdata
        mDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_FORMDATA_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                + FORMDATA_URLID_COL + " INTEGER, " + FORMDATA_NAME_COL
                + " TEXT, " + FORMDATA_VALUE_COL + " TEXT," + " UNIQUE ("
                + FORMDATA_URLID_COL + ", " + FORMDATA_NAME_COL + ", "
                + FORMDATA_VALUE_COL + ") ON CONFLICT IGNORE);");

        // httpauth
        mDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_HTTPAUTH_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                + HTTPAUTH_HOST_COL + " TEXT, " + HTTPAUTH_REALM_COL
                + " TEXT, " + HTTPAUTH_USERNAME_COL + " TEXT, "
                + HTTPAUTH_PASSWORD_COL + " TEXT," + " UNIQUE ("
                + HTTPAUTH_HOST_COL + ", " + HTTPAUTH_REALM_COL
                + ") ON CONFLICT REPLACE);");
        // passwords
        mDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_PASSWORD_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                + PASSWORD_HOST_COL + " TEXT, " + PASSWORD_USERNAME_COL
                + " TEXT, " + PASSWORD_PASSWORD_COL + " TEXT," + " UNIQUE ("
                + PASSWORD_HOST_COL + ", " + PASSWORD_USERNAME_COL
                + ") ON CONFLICT REPLACE);");
    }

    private static void upgradeCacheDatabase() {
        int oldVersion = mCacheDatabase.getVersion();
        if (oldVersion != 0) {
            Log.i(LOGTAG, "Upgrading cache database from version "
                    + oldVersion + " to "
                    + CACHE_DATABASE_VERSION + ", which will destroy all old data");
        }
        mCacheDatabase.execSQL("DROP TABLE IF EXISTS cache");
        mCacheDatabase.setVersion(CACHE_DATABASE_VERSION);
    }

    private static void bootstrapCacheDatabase() {
        if (mCacheDatabase != null) {
            mCacheDatabase.execSQL("CREATE TABLE cache"
                    + " (" + ID_COL + " INTEGER PRIMARY KEY, " + CACHE_URL_COL
                    + " TEXT, " + CACHE_FILE_PATH_COL + " TEXT, "
                    + CACHE_LAST_MODIFY_COL + " TEXT, " + CACHE_ETAG_COL
                    + " TEXT, " + CACHE_EXPIRES_COL + " INTEGER, "
                    + CACHE_EXPIRES_STRING_COL + " TEXT, "
                    + CACHE_MIMETYPE_COL + " TEXT, " + CACHE_ENCODING_COL
                    + " TEXT," + CACHE_HTTP_STATUS_COL + " INTEGER, "
                    + CACHE_LOCATION_COL + " TEXT, " + CACHE_CONTENTLENGTH_COL
                    + " INTEGER, " + CACHE_CONTENTDISPOSITION_COL + " TEXT, "
                    + CACHE_CROSSDOMAIN_COL + " TEXT,"
                    + " UNIQUE (" + CACHE_URL_COL + ") ON CONFLICT REPLACE);");
            mCacheDatabase.execSQL("CREATE INDEX cacheUrlIndex ON cache ("
                    + CACHE_URL_COL + ")");
        }
    }

    // Wait for the background initialization thread to complete and check the
    // database creation status.
    private boolean checkInitialized() {
        synchronized (this) {
            while (!mInitialized) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Log.e(LOGTAG, "Caught exception while checking " +
                                  "initialization");
                    Log.e(LOGTAG, Log.getStackTraceString(e));
                }
            }
        }
        return mDatabase != null;
    }

    private boolean hasEntries(int tableId) {
        if (!checkInitialized()) {
            return false;
        }

        Cursor cursor = null;
        boolean ret = false;
        try {
            cursor = mDatabase.query(mTableNames[tableId], ID_PROJECTION,
                    null, null, null, null, null);
            ret = cursor.moveToFirst() == true;
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "hasEntries", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return ret;
    }

    //
    // cookies functions
    //

    /**
     * Get cookies in the format of CookieManager.Cookie inside an ArrayList for
     * a given domain
     *
     * @return ArrayList<Cookie> If nothing is found, return an empty list.
     */
    ArrayList<Cookie> getCookiesForDomain(String domain) {
        ArrayList<Cookie> list = new ArrayList<Cookie>();
        if (domain == null || !checkInitialized()) {
            return list;
        }

        synchronized (mCookieLock) {
            final String[] columns = new String[] {
                    ID_COL, COOKIES_DOMAIN_COL, COOKIES_PATH_COL,
                    COOKIES_NAME_COL, COOKIES_VALUE_COL, COOKIES_EXPIRES_COL,
                    COOKIES_SECURE_COL
            };
            final String selection = "(" + COOKIES_DOMAIN_COL
                    + " GLOB '*' || ?)";
            Cursor cursor = null;
            try {
                cursor = mDatabase.query(mTableNames[TABLE_COOKIES_ID],
                        columns, selection, new String[] { domain }, null, null,
                        null);
                if (cursor.moveToFirst()) {
                    int domainCol = cursor.getColumnIndex(COOKIES_DOMAIN_COL);
                    int pathCol = cursor.getColumnIndex(COOKIES_PATH_COL);
                    int nameCol = cursor.getColumnIndex(COOKIES_NAME_COL);
                    int valueCol = cursor.getColumnIndex(COOKIES_VALUE_COL);
                    int expiresCol = cursor.getColumnIndex(COOKIES_EXPIRES_COL);
                    int secureCol = cursor.getColumnIndex(COOKIES_SECURE_COL);
                    do {
                        Cookie cookie = new Cookie();
                        cookie.domain = cursor.getString(domainCol);
                        cookie.path = cursor.getString(pathCol);
                        cookie.name = cursor.getString(nameCol);
                        cookie.value = cursor.getString(valueCol);
                        if (cursor.isNull(expiresCol)) {
                            cookie.expires = -1;
                        } else {
                            cookie.expires = cursor.getLong(expiresCol);
                        }
                        cookie.secure = cursor.getShort(secureCol) != 0;
                        cookie.mode = Cookie.MODE_NORMAL;
                        list.add(cookie);
                    } while (cursor.moveToNext());
                }
            } catch (IllegalStateException e) {
                Log.e(LOGTAG, "getCookiesForDomain", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return list;
        }
    }

    /**
     * Delete cookies which matches (domain, path, name).
     *
     * @param domain If it is null, nothing happens.
     * @param path If it is null, all the cookies match (domain) will be
     *            deleted.
     * @param name If it is null, all the cookies match (domain, path) will be
     *            deleted.
     */
    void deleteCookies(String domain, String path, String name) {
        if (domain == null || !checkInitialized()) {
            return;
        }

        synchronized (mCookieLock) {
            final String where = "(" + COOKIES_DOMAIN_COL + " == ?) AND ("
                    + COOKIES_PATH_COL + " == ?) AND (" + COOKIES_NAME_COL
                    + " == ?)";
            mDatabase.delete(mTableNames[TABLE_COOKIES_ID], where,
                    new String[] { domain, path, name });
        }
    }

    /**
     * Add a cookie to the database
     *
     * @param cookie
     */
    void addCookie(Cookie cookie) {
        if (cookie.domain == null || cookie.path == null || cookie.name == null
                || !checkInitialized()) {
            return;
        }

        synchronized (mCookieLock) {
            ContentValues cookieVal = new ContentValues();
            cookieVal.put(COOKIES_DOMAIN_COL, cookie.domain);
            cookieVal.put(COOKIES_PATH_COL, cookie.path);
            cookieVal.put(COOKIES_NAME_COL, cookie.name);
            cookieVal.put(COOKIES_VALUE_COL, cookie.value);
            if (cookie.expires != -1) {
                cookieVal.put(COOKIES_EXPIRES_COL, cookie.expires);
            }
            cookieVal.put(COOKIES_SECURE_COL, cookie.secure);
            mDatabase.insert(mTableNames[TABLE_COOKIES_ID], null, cookieVal);
        }
    }

    /**
     * Whether there is any cookies in the database
     *
     * @return TRUE if there is cookie.
     */
    boolean hasCookies() {
        synchronized (mCookieLock) {
            return hasEntries(TABLE_COOKIES_ID);
        }
    }

    /**
     * Clear cookie database
     */
    void clearCookies() {
        if (!checkInitialized()) {
            return;
        }

        synchronized (mCookieLock) {
            mDatabase.delete(mTableNames[TABLE_COOKIES_ID], null, null);
        }
    }

    /**
     * Clear session cookies, which means cookie doesn't have EXPIRES.
     */
    void clearSessionCookies() {
        if (!checkInitialized()) {
            return;
        }

        final String sessionExpired = COOKIES_EXPIRES_COL + " ISNULL";
        synchronized (mCookieLock) {
            mDatabase.delete(mTableNames[TABLE_COOKIES_ID], sessionExpired,
                    null);
        }
    }

    /**
     * Clear expired cookies
     *
     * @param now Time for now
     */
    void clearExpiredCookies(long now) {
        if (!checkInitialized()) {
            return;
        }

        final String expires = COOKIES_EXPIRES_COL + " <= ?";
        synchronized (mCookieLock) {
            mDatabase.delete(mTableNames[TABLE_COOKIES_ID], expires,
                    new String[] { Long.toString(now) });
        }
    }

    //
    // cache functions
    //

    // only called from WebViewWorkerThread
    boolean startCacheTransaction() {
        if (++mCacheTransactionRefcount == 1) {
            if (!Thread.currentThread().equals(
                    WebViewWorker.getHandler().getLooper().getThread())) {
                Log.w(LOGTAG, "startCacheTransaction should be called from "
                        + "WebViewWorkerThread instead of from "
                        + Thread.currentThread().getName());
            }
            mCacheDatabase.beginTransactionNonExclusive();
            return true;
        }
        return false;
    }

    // only called from WebViewWorkerThread
    boolean endCacheTransaction() {
        if (--mCacheTransactionRefcount == 0) {
            if (!Thread.currentThread().equals(
                    WebViewWorker.getHandler().getLooper().getThread())) {
                Log.w(LOGTAG, "endCacheTransaction should be called from "
                        + "WebViewWorkerThread instead of from "
                        + Thread.currentThread().getName());
            }
            try {
                mCacheDatabase.setTransactionSuccessful();
            } finally {
                mCacheDatabase.endTransaction();
            }
            return true;
        }
        return false;
    }

    /**
     * Get a cache item.
     * 
     * @param url The url
     * @return CacheResult The CacheManager.CacheResult
     */
    CacheResult getCache(String url) {
        assert !JniUtil.useChromiumHttpStack();

        if (url == null || !checkInitialized()) {
            return null;
        }

        Cursor cursor = null;
        final String query = "SELECT filepath, lastmodify, etag, expires, "
                + "expiresstring, mimetype, encoding, httpstatus, location, contentlength, "
                + "contentdisposition, crossdomain FROM cache WHERE url = ?";
        try {
            cursor = mCacheDatabase.rawQuery(query, new String[] { url });
            if (cursor.moveToFirst()) {
                CacheResult ret = new CacheResult();
                ret.localPath = cursor.getString(0);
                ret.lastModified = cursor.getString(1);
                ret.etag = cursor.getString(2);
                ret.expires = cursor.getLong(3);
                ret.expiresString = cursor.getString(4);
                ret.mimeType = cursor.getString(5);
                ret.encoding = cursor.getString(6);
                ret.httpStatusCode = cursor.getInt(7);
                ret.location = cursor.getString(8);
                ret.contentLength = cursor.getLong(9);
                ret.contentdisposition = cursor.getString(10);
                ret.crossDomain = cursor.getString(11);
                return ret;
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "getCache", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /**
     * Remove a cache item.
     * 
     * @param url The url
     */
    void removeCache(String url) {
        assert !JniUtil.useChromiumHttpStack();

        if (url == null || !checkInitialized()) {
            return;
        }

        mCacheDatabase.execSQL("DELETE FROM cache WHERE url = ?", new String[] { url });
    }

    /**
     * Add or update a cache. CACHE_URL_COL is unique in the table.
     *
     * @param url The url
     * @param c The CacheManager.CacheResult
     */
    void addCache(String url, CacheResult c) {
        assert !JniUtil.useChromiumHttpStack();

        if (url == null || !checkInitialized()) {
            return;
        }

        mCacheInserter.prepareForInsert();
        mCacheInserter.bind(mCacheUrlColIndex, url);
        mCacheInserter.bind(mCacheFilePathColIndex, c.localPath);
        mCacheInserter.bind(mCacheLastModifyColIndex, c.lastModified);
        mCacheInserter.bind(mCacheETagColIndex, c.etag);
        mCacheInserter.bind(mCacheExpiresColIndex, c.expires);
        mCacheInserter.bind(mCacheExpiresStringColIndex, c.expiresString);
        mCacheInserter.bind(mCacheMimeTypeColIndex, c.mimeType);
        mCacheInserter.bind(mCacheEncodingColIndex, c.encoding);
        mCacheInserter.bind(mCacheHttpStatusColIndex, c.httpStatusCode);
        mCacheInserter.bind(mCacheLocationColIndex, c.location);
        mCacheInserter.bind(mCacheContentLengthColIndex, c.contentLength);
        mCacheInserter.bind(mCacheContentDispositionColIndex,
                c.contentdisposition);
        mCacheInserter.bind(mCacheCrossDomainColIndex, c.crossDomain);
        mCacheInserter.execute();
    }

    /**
     * Clear cache database
     */
    void clearCache() {
        if (!checkInitialized()) {
            return;
        }

        mCacheDatabase.delete("cache", null, null);
    }

    boolean hasCache() {
        if (!checkInitialized()) {
            return false;
        }

        Cursor cursor = null;
        boolean ret = false;
        try {
            cursor = mCacheDatabase.query("cache", ID_PROJECTION,
                    null, null, null, null, null);
            ret = cursor.moveToFirst() == true;
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "hasCache", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return ret;
    }

    long getCacheTotalSize() {
        if (mCacheDatabase == null) {
            return 0;
        }
        long size = 0;
        Cursor cursor = null;
        final String query = "SELECT SUM(contentlength) as sum FROM cache";
        try {
            cursor = mCacheDatabase.rawQuery(query, null);
            if (cursor.moveToFirst()) {
                size = cursor.getLong(0);
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "getCacheTotalSize", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return size;
    }

    List<String> trimCache(long amount) {
        ArrayList<String> pathList = new ArrayList<String>(100);
        Cursor cursor = null;
        final String query = "SELECT contentlength, filepath FROM cache ORDER BY expires ASC";
        try {
            cursor = mCacheDatabase.rawQuery(query, null);
            if (cursor.moveToFirst()) {
                int batchSize = 100;
                StringBuilder pathStr = new StringBuilder(20 + 16 * batchSize);
                pathStr.append("DELETE FROM cache WHERE filepath IN (?");
                for (int i = 1; i < batchSize; i++) {
                    pathStr.append(", ?");
                }
                pathStr.append(")");
                SQLiteStatement statement = null;
                try {
                    statement = mCacheDatabase.compileStatement(
                            pathStr.toString());
                    // as bindString() uses 1-based index, initialize index to 1
                    int index = 1;
                    do {
                        long length = cursor.getLong(0);
                        if (length == 0) {
                            continue;
                        }
                        amount -= length;
                        String filePath = cursor.getString(1);
                        statement.bindString(index, filePath);
                        pathList.add(filePath);
                        if (index++ == batchSize) {
                            statement.execute();
                            statement.clearBindings();
                            index = 1;
                        }
                    } while (cursor.moveToNext() && amount > 0);
                    if (index > 1) {
                        // there may be old bindings from the previous statement
                        // if index is less than batchSize, which is Ok.
                        statement.execute();
                    }
                } catch (IllegalStateException e) {
                    Log.e(LOGTAG, "trimCache SQLiteStatement", e);
                } finally {
                    if (statement != null) statement.close();
                }
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "trimCache Cursor", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return pathList;
    }

    List<String> getAllCacheFileNames() {
        ArrayList<String> pathList = null;
        Cursor cursor = null;
        try {
            cursor = mCacheDatabase.rawQuery("SELECT filepath FROM cache",
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                pathList = new ArrayList<String>(cursor.getCount());
                do {
                    pathList.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "getAllCacheFileNames", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return pathList;
    }

    //
    // password functions
    //

    /**
     * Set password. Tuple (PASSWORD_HOST_COL, PASSWORD_USERNAME_COL) is unique.
     *
     * @param schemePlusHost The scheme and host for the password
     * @param username The username for the password. If it is null, it means
     *            password can't be saved.
     * @param password The password
     */
    void setUsernamePassword(String schemePlusHost, String username,
                String password) {
        if (schemePlusHost == null || !checkInitialized()) {
            return;
        }

        synchronized (mPasswordLock) {
            final ContentValues c = new ContentValues();
            c.put(PASSWORD_HOST_COL, schemePlusHost);
            c.put(PASSWORD_USERNAME_COL, username);
            c.put(PASSWORD_PASSWORD_COL, password);
            mDatabase.insert(mTableNames[TABLE_PASSWORD_ID], PASSWORD_HOST_COL,
                    c);
        }
    }

    /**
     * Retrieve the username and password for a given host
     *
     * @param schemePlusHost The scheme and host which passwords applies to
     * @return String[] if found, String[0] is username, which can be null and
     *         String[1] is password. Return null if it can't find anything.
     */
    String[] getUsernamePassword(String schemePlusHost) {
        if (schemePlusHost == null || !checkInitialized()) {
            return null;
        }

        final String[] columns = new String[] {
                PASSWORD_USERNAME_COL, PASSWORD_PASSWORD_COL
        };
        final String selection = "(" + PASSWORD_HOST_COL + " == ?)";
        synchronized (mPasswordLock) {
            String[] ret = null;
            Cursor cursor = null;
            try {
                cursor = mDatabase.query(mTableNames[TABLE_PASSWORD_ID],
                        columns, selection, new String[] { schemePlusHost }, null,
                        null, null);
                if (cursor.moveToFirst()) {
                    ret = new String[2];
                    ret[0] = cursor.getString(
                            cursor.getColumnIndex(PASSWORD_USERNAME_COL));
                    ret[1] = cursor.getString(
                            cursor.getColumnIndex(PASSWORD_PASSWORD_COL));
                }
            } catch (IllegalStateException e) {
                Log.e(LOGTAG, "getUsernamePassword", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return ret;
        }
    }

    /**
     * Find out if there are any passwords saved. 
     *
     * @return TRUE if there is passwords saved
     */
    public boolean hasUsernamePassword() {
        synchronized (mPasswordLock) {
            return hasEntries(TABLE_PASSWORD_ID);
        }
    }

    /**
     * Clear password database
     */
    public void clearUsernamePassword() {
        if (!checkInitialized()) {
            return;
        }

        synchronized (mPasswordLock) {
            mDatabase.delete(mTableNames[TABLE_PASSWORD_ID], null, null);
        }
    }

    //
    // http authentication password functions
    //

    /**
     * Set HTTP authentication password. Tuple (HTTPAUTH_HOST_COL,
     * HTTPAUTH_REALM_COL, HTTPAUTH_USERNAME_COL) is unique.
     *
     * @param host The host for the password
     * @param realm The realm for the password
     * @param username The username for the password. If it is null, it means
     *            password can't be saved.
     * @param password The password
     */
    void setHttpAuthUsernamePassword(String host, String realm, String username,
            String password) {
        if (host == null || realm == null || !checkInitialized()) {
            return;
        }

        synchronized (mHttpAuthLock) {
            final ContentValues c = new ContentValues();
            c.put(HTTPAUTH_HOST_COL, host);
            c.put(HTTPAUTH_REALM_COL, realm);
            c.put(HTTPAUTH_USERNAME_COL, username);
            c.put(HTTPAUTH_PASSWORD_COL, password);
            mDatabase.insert(mTableNames[TABLE_HTTPAUTH_ID], HTTPAUTH_HOST_COL,
                    c);
        }
    }

    /**
     * Retrieve the HTTP authentication username and password for a given
     * host+realm pair
     *
     * @param host The host the password applies to
     * @param realm The realm the password applies to
     * @return String[] if found, String[0] is username, which can be null and
     *         String[1] is password. Return null if it can't find anything.
     */
    String[] getHttpAuthUsernamePassword(String host, String realm) {
        if (host == null || realm == null || !checkInitialized()){
            return null;
        }

        final String[] columns = new String[] {
                HTTPAUTH_USERNAME_COL, HTTPAUTH_PASSWORD_COL
        };
        final String selection = "(" + HTTPAUTH_HOST_COL + " == ?) AND ("
                + HTTPAUTH_REALM_COL + " == ?)";
        synchronized (mHttpAuthLock) {
            String[] ret = null;
            Cursor cursor = null;
            try {
                cursor = mDatabase.query(mTableNames[TABLE_HTTPAUTH_ID],
                        columns, selection, new String[] { host, realm }, null,
                        null, null);
                if (cursor.moveToFirst()) {
                    ret = new String[2];
                    ret[0] = cursor.getString(
                            cursor.getColumnIndex(HTTPAUTH_USERNAME_COL));
                    ret[1] = cursor.getString(
                            cursor.getColumnIndex(HTTPAUTH_PASSWORD_COL));
                }
            } catch (IllegalStateException e) {
                Log.e(LOGTAG, "getHttpAuthUsernamePassword", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return ret;
        }
    }

    /**
     *  Find out if there are any HTTP authentication passwords saved.   
     *
     * @return TRUE if there are passwords saved
     */
    public boolean hasHttpAuthUsernamePassword() {
        synchronized (mHttpAuthLock) {
            return hasEntries(TABLE_HTTPAUTH_ID);
        }
    }

    /**
     * Clear HTTP authentication password database
     */
    public void clearHttpAuthUsernamePassword() {
        if (!checkInitialized()) {
            return;
        }

        synchronized (mHttpAuthLock) {
            mDatabase.delete(mTableNames[TABLE_HTTPAUTH_ID], null, null);
        }
    }

    //
    // form data functions
    //

    /**
     * Set form data for a site. Tuple (FORMDATA_URLID_COL, FORMDATA_NAME_COL,
     * FORMDATA_VALUE_COL) is unique
     *
     * @param url The url of the site
     * @param formdata The form data in HashMap
     */
    void setFormData(String url, HashMap<String, String> formdata) {
        if (url == null || formdata == null || !checkInitialized()) {
            return;
        }

        final String selection = "(" + FORMURL_URL_COL + " == ?)";
        synchronized (mFormLock) {
            long urlid = -1;
            Cursor cursor = null;
            try {
                cursor = mDatabase.query(mTableNames[TABLE_FORMURL_ID],
                        ID_PROJECTION, selection, new String[] { url }, null, null,
                        null);
                if (cursor.moveToFirst()) {
                    urlid = cursor.getLong(cursor.getColumnIndex(ID_COL));
                } else {
                    ContentValues c = new ContentValues();
                    c.put(FORMURL_URL_COL, url);
                    urlid = mDatabase.insert(
                            mTableNames[TABLE_FORMURL_ID], null, c);
                }
            } catch (IllegalStateException e) {
                Log.e(LOGTAG, "setFormData", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            if (urlid >= 0) {
                Set<Entry<String, String>> set = formdata.entrySet();
                Iterator<Entry<String, String>> iter = set.iterator();
                ContentValues map = new ContentValues();
                map.put(FORMDATA_URLID_COL, urlid);
                while (iter.hasNext()) {
                    Entry<String, String> entry = iter.next();
                    map.put(FORMDATA_NAME_COL, entry.getKey());
                    map.put(FORMDATA_VALUE_COL, entry.getValue());
                    mDatabase.insert(mTableNames[TABLE_FORMDATA_ID], null, map);
                }
            }
        }
    }

    /**
     * Get all the values for a form entry with "name" in a given site
     *
     * @param url The url of the site
     * @param name The name of the form entry
     * @return A list of values. Return empty list if nothing is found.
     */
    ArrayList<String> getFormData(String url, String name) {
        ArrayList<String> values = new ArrayList<String>();
        if (url == null || name == null || !checkInitialized()) {
            return values;
        }

        final String urlSelection = "(" + FORMURL_URL_COL + " == ?)";
        final String dataSelection = "(" + FORMDATA_URLID_COL + " == ?) AND ("
                + FORMDATA_NAME_COL + " == ?)";
        synchronized (mFormLock) {
            Cursor cursor = null;
            try {
                cursor = mDatabase.query(mTableNames[TABLE_FORMURL_ID],
                        ID_PROJECTION, urlSelection, new String[] { url }, null,
                        null, null);
                while (cursor.moveToNext()) {
                    long urlid = cursor.getLong(cursor.getColumnIndex(ID_COL));
                    Cursor dataCursor = null;
                    try {
                        dataCursor = mDatabase.query(
                                mTableNames[TABLE_FORMDATA_ID],
                                new String[] { ID_COL, FORMDATA_VALUE_COL },
                                dataSelection,
                                new String[] { Long.toString(urlid), name },
                                null, null, null);
                        if (dataCursor.moveToFirst()) {
                            int valueCol = dataCursor.getColumnIndex(
                                    FORMDATA_VALUE_COL);
                            do {
                                values.add(dataCursor.getString(valueCol));
                            } while (dataCursor.moveToNext());
                        }
                    } catch (IllegalStateException e) {
                        Log.e(LOGTAG, "getFormData dataCursor", e);
                    } finally {
                        if (dataCursor != null) dataCursor.close();
                    }
                }
            } catch (IllegalStateException e) {
                Log.e(LOGTAG, "getFormData cursor", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return values;
        }
    }

    /**
     * Find out if there is form data saved.
     *
     * @return TRUE if there is form data in the database
     */
    public boolean hasFormData() {
        synchronized (mFormLock) {
            return hasEntries(TABLE_FORMURL_ID);
        }
    }

    /**
     * Clear form database
     */
    public void clearFormData() {
        if (!checkInitialized()) {
            return;
        }

        synchronized (mFormLock) {
            mDatabase.delete(mTableNames[TABLE_FORMURL_ID], null, null);
            mDatabase.delete(mTableNames[TABLE_FORMDATA_ID], null, null);
        }
    }
}
