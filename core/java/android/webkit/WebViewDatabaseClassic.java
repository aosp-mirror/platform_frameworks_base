/*
 * Copyright (C) 2012 The Android Open Source Project
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

final class WebViewDatabaseClassic extends WebViewDatabase {
    private static final String LOGTAG = "WebViewDatabaseClassic";
    private static final String DATABASE_FILE = "webview.db";
    private static final String CACHE_DATABASE_FILE = "webviewCache.db";

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

    private static WebViewDatabaseClassic sInstance = null;

    private static SQLiteDatabase sDatabase = null;

    // synchronize locks
    private final Object mPasswordLock = new Object();
    private final Object mFormLock = new Object();
    private final Object mHttpAuthLock = new Object();

    private static final String mTableNames[] = {
        "password", "formurl", "formdata", "httpauth"
    };

    // Table ids (they are index to mTableNames)
    private static final int TABLE_PASSWORD_ID = 0;
    private static final int TABLE_FORMURL_ID = 1;
    private static final int TABLE_FORMDATA_ID = 2;
    private static final int TABLE_HTTPAUTH_ID = 3;

    // column id strings for "_id" which can be used by any table
    private static final String ID_COL = "_id";

    private static final String[] ID_PROJECTION = new String[] {
        "_id"
    };

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

    // Initially true until the background thread completes.
    private boolean mInitialized = false;

    WebViewDatabaseClassic(final Context context) {
        new Thread() {
            @Override
            public void run() {
                init(context);
            }
        }.start();

        // Singleton only, use getInstance()
    }

    public static synchronized WebViewDatabaseClassic getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new WebViewDatabaseClassic(context);
        }
        return sInstance;
    }

    private synchronized void init(Context context) {
        if (mInitialized) {
            return;
        }

        initDatabase(context);
        // Before using the Chromium HTTP stack, we stored the WebKit cache in
        // our own DB. Clean up the DB file if it's still around.
        context.deleteDatabase(CACHE_DATABASE_FILE);

        // Thread done, notify.
        mInitialized = true;
        notify();
    }

    private void initDatabase(Context context) {
        try {
            sDatabase = context.openOrCreateDatabase(DATABASE_FILE, 0, null);
        } catch (SQLiteException e) {
            // try again by deleting the old db and create a new one
            if (context.deleteDatabase(DATABASE_FILE)) {
                sDatabase = context.openOrCreateDatabase(DATABASE_FILE, 0,
                        null);
            }
        }

        // sDatabase should not be null,
        // the only case is RequestAPI test has problem to create db
        if (sDatabase == null) {
            mInitialized = true;
            notify();
            return;
        }

        if (sDatabase.getVersion() != DATABASE_VERSION) {
            sDatabase.beginTransactionNonExclusive();
            try {
                upgradeDatabase();
                sDatabase.setTransactionSuccessful();
            } finally {
                sDatabase.endTransaction();
            }
        }
    }

    private static void upgradeDatabase() {
        upgradeDatabaseToV10();
        upgradeDatabaseFromV10ToV11();
        // Add future database upgrade functions here, one version at a
        // time.
        sDatabase.setVersion(DATABASE_VERSION);
    }

    private static void upgradeDatabaseFromV10ToV11() {
        int oldVersion = sDatabase.getVersion();

        if (oldVersion >= 11) {
            // Nothing to do.
            return;
        }

        // Clear out old java stack cookies - this data is now stored in
        // a separate database managed by the Chrome stack.
        sDatabase.execSQL("DROP TABLE IF EXISTS cookies");

        // Likewise for the old cache table.
        sDatabase.execSQL("DROP TABLE IF EXISTS cache");

        // Update form autocomplete  URLs to match new ICS formatting.
        Cursor c = sDatabase.query(mTableNames[TABLE_FORMURL_ID], null, null,
                null, null, null, null);
        while (c.moveToNext()) {
            String urlId = Long.toString(c.getLong(c.getColumnIndex(ID_COL)));
            String url = c.getString(c.getColumnIndex(FORMURL_URL_COL));
            ContentValues cv = new ContentValues(1);
            cv.put(FORMURL_URL_COL, WebTextView.urlForAutoCompleteData(url));
            sDatabase.update(mTableNames[TABLE_FORMURL_ID], cv, ID_COL + "=?",
                    new String[] { urlId });
        }
        c.close();
    }

    private static void upgradeDatabaseToV10() {
        int oldVersion = sDatabase.getVersion();

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
            sDatabase.execSQL("DROP TABLE IF EXISTS "
                    + mTableNames[TABLE_HTTPAUTH_ID]);
            sDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_HTTPAUTH_ID]
                    + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                    + HTTPAUTH_HOST_COL + " TEXT, " + HTTPAUTH_REALM_COL
                    + " TEXT, " + HTTPAUTH_USERNAME_COL + " TEXT, "
                    + HTTPAUTH_PASSWORD_COL + " TEXT," + " UNIQUE ("
                    + HTTPAUTH_HOST_COL + ", " + HTTPAUTH_REALM_COL
                    + ") ON CONFLICT REPLACE);");
            return;
        }

        sDatabase.execSQL("DROP TABLE IF EXISTS cookies");
        sDatabase.execSQL("DROP TABLE IF EXISTS cache");
        sDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_FORMURL_ID]);
        sDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_FORMDATA_ID]);
        sDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_HTTPAUTH_ID]);
        sDatabase.execSQL("DROP TABLE IF EXISTS "
                + mTableNames[TABLE_PASSWORD_ID]);

        // formurl
        sDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_FORMURL_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, " + FORMURL_URL_COL
                + " TEXT" + ");");

        // formdata
        sDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_FORMDATA_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                + FORMDATA_URLID_COL + " INTEGER, " + FORMDATA_NAME_COL
                + " TEXT, " + FORMDATA_VALUE_COL + " TEXT," + " UNIQUE ("
                + FORMDATA_URLID_COL + ", " + FORMDATA_NAME_COL + ", "
                + FORMDATA_VALUE_COL + ") ON CONFLICT IGNORE);");

        // httpauth
        sDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_HTTPAUTH_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                + HTTPAUTH_HOST_COL + " TEXT, " + HTTPAUTH_REALM_COL
                + " TEXT, " + HTTPAUTH_USERNAME_COL + " TEXT, "
                + HTTPAUTH_PASSWORD_COL + " TEXT," + " UNIQUE ("
                + HTTPAUTH_HOST_COL + ", " + HTTPAUTH_REALM_COL
                + ") ON CONFLICT REPLACE);");
        // passwords
        sDatabase.execSQL("CREATE TABLE " + mTableNames[TABLE_PASSWORD_ID]
                + " (" + ID_COL + " INTEGER PRIMARY KEY, "
                + PASSWORD_HOST_COL + " TEXT, " + PASSWORD_USERNAME_COL
                + " TEXT, " + PASSWORD_PASSWORD_COL + " TEXT," + " UNIQUE ("
                + PASSWORD_HOST_COL + ", " + PASSWORD_USERNAME_COL
                + ") ON CONFLICT REPLACE);");
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
        return sDatabase != null;
    }

    private boolean hasEntries(int tableId) {
        if (!checkInitialized()) {
            return false;
        }

        Cursor cursor = null;
        boolean ret = false;
        try {
            cursor = sDatabase.query(mTableNames[tableId], ID_PROJECTION,
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
            sDatabase.insert(mTableNames[TABLE_PASSWORD_ID], PASSWORD_HOST_COL,
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
                cursor = sDatabase.query(mTableNames[TABLE_PASSWORD_ID],
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
     * @see WebViewDatabase#hasUsernamePassword
     */
    @Override
    public boolean hasUsernamePassword() {
        synchronized (mPasswordLock) {
            return hasEntries(TABLE_PASSWORD_ID);
        }
    }

    /**
     * @see WebViewDatabase#clearUsernamePassword
     */
    @Override
    public void clearUsernamePassword() {
        if (!checkInitialized()) {
            return;
        }

        synchronized (mPasswordLock) {
            sDatabase.delete(mTableNames[TABLE_PASSWORD_ID], null, null);
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
            sDatabase.insert(mTableNames[TABLE_HTTPAUTH_ID], HTTPAUTH_HOST_COL,
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
                cursor = sDatabase.query(mTableNames[TABLE_HTTPAUTH_ID],
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
     * @see WebViewDatabase#hasHttpAuthUsernamePassword
     */
    @Override
    public boolean hasHttpAuthUsernamePassword() {
        synchronized (mHttpAuthLock) {
            return hasEntries(TABLE_HTTPAUTH_ID);
        }
    }

    /**
     * @see WebViewDatabase#clearHttpAuthUsernamePassword
     */
    @Override
    public void clearHttpAuthUsernamePassword() {
        if (!checkInitialized()) {
            return;
        }

        synchronized (mHttpAuthLock) {
            sDatabase.delete(mTableNames[TABLE_HTTPAUTH_ID], null, null);
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
                cursor = sDatabase.query(mTableNames[TABLE_FORMURL_ID],
                        ID_PROJECTION, selection, new String[] { url }, null, null,
                        null);
                if (cursor.moveToFirst()) {
                    urlid = cursor.getLong(cursor.getColumnIndex(ID_COL));
                } else {
                    ContentValues c = new ContentValues();
                    c.put(FORMURL_URL_COL, url);
                    urlid = sDatabase.insert(
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
                    sDatabase.insert(mTableNames[TABLE_FORMDATA_ID], null, map);
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
                cursor = sDatabase.query(mTableNames[TABLE_FORMURL_ID],
                        ID_PROJECTION, urlSelection, new String[] { url }, null,
                        null, null);
                while (cursor.moveToNext()) {
                    long urlid = cursor.getLong(cursor.getColumnIndex(ID_COL));
                    Cursor dataCursor = null;
                    try {
                        dataCursor = sDatabase.query(
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
     * @see WebViewDatabase#hasFormData
     */
    @Override
    public boolean hasFormData() {
        synchronized (mFormLock) {
            return hasEntries(TABLE_FORMURL_ID);
        }
    }

    /**
     * @see WebViewDatabase#clearFormData
     */
    @Override
    public void clearFormData() {
        if (!checkInitialized()) {
            return;
        }

        synchronized (mFormLock) {
            sDatabase.delete(mTableNames[TABLE_FORMURL_ID], null, null);
            sDatabase.delete(mTableNames[TABLE_FORMDATA_ID], null, null);
        }
    }
}
