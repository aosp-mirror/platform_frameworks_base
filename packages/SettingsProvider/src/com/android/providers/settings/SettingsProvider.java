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

package com.android.providers.settings;

import java.io.FileNotFoundException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.backup.BackupManager;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.AssetFileDescriptor;
import android.database.AbstractCursor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteQueryBuilder;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.Slog;
import android.util.SparseArray;

public class SettingsProvider extends ContentProvider {
    private static final String TAG = "SettingsProvider";
    private static final boolean LOCAL_LOGV = false;

    private static final boolean USER_CHECK_THROWS = true;

    private static final String TABLE_SYSTEM = "system";
    private static final String TABLE_SECURE = "secure";
    private static final String TABLE_GLOBAL = "global";
    private static final String TABLE_FAVORITES = "favorites";
    private static final String TABLE_OLD_FAVORITES = "old_favorites";

    private static final String[] COLUMN_VALUE = new String[] { "value" };

    // Caches for each user's settings, access-ordered for acting as LRU.
    // Guarded by themselves.
    private static final int MAX_CACHE_ENTRIES = 200;
    private static final SparseArray<SettingsCache> sSystemCaches
            = new SparseArray<SettingsCache>();
    private static final SparseArray<SettingsCache> sSecureCaches
            = new SparseArray<SettingsCache>();
    private static final SettingsCache sGlobalCache = new SettingsCache(TABLE_GLOBAL);

    // The count of how many known (handled by SettingsProvider)
    // database mutations are currently being handled for this user.
    // Used by file observers to not reload the database when it's ourselves
    // modifying it.
    private static final SparseArray<AtomicInteger> sKnownMutationsInFlight
            = new SparseArray<AtomicInteger>();

    // Each defined user has their own settings
    protected final SparseArray<DatabaseHelper> mOpenHelpers = new SparseArray<DatabaseHelper>();

    // Keep the list of managed profiles synced here
    private List<UserInfo> mManagedProfiles = null;

    // Over this size we don't reject loading or saving settings but
    // we do consider them broken/malicious and don't keep them in
    // memory at least:
    private static final int MAX_CACHE_ENTRY_SIZE = 500;

    private static final Bundle NULL_SETTING = Bundle.forPair("value", null);

    // Used as a sentinel value in an instance equality test when we
    // want to cache the existence of a key, but not store its value.
    private static final Bundle TOO_LARGE_TO_CACHE_MARKER = Bundle.forPair("_dummy", null);

    private UserManager mUserManager;
    private BackupManager mBackupManager;

    /**
     * Settings which need to be treated as global/shared in multi-user environments.
     */
    static final HashSet<String> sSecureGlobalKeys;
    static final HashSet<String> sSystemGlobalKeys;

    // Settings that cannot be modified if associated user restrictions are enabled.
    static final Map<String, String> sRestrictedKeys;

    private static final String DROPBOX_TAG_USERLOG = "restricted_profile_ssaid";

    static final HashSet<String> sSecureCloneToManagedKeys;
    static final HashSet<String> sSystemCloneToManagedKeys;

    static {
        // Keys (name column) from the 'secure' table that are now in the owner user's 'global'
        // table, shared across all users
        // These must match Settings.Secure.MOVED_TO_GLOBAL
        sSecureGlobalKeys = new HashSet<String>();
        Settings.Secure.getMovedKeys(sSecureGlobalKeys);

        // Keys from the 'system' table now moved to 'global'
        // These must match Settings.System.MOVED_TO_GLOBAL
        sSystemGlobalKeys = new HashSet<String>();
        Settings.System.getNonLegacyMovedKeys(sSystemGlobalKeys);

        sRestrictedKeys = new HashMap<String, String>();
        sRestrictedKeys.put(Settings.Secure.LOCATION_MODE, UserManager.DISALLOW_SHARE_LOCATION);
        sRestrictedKeys.put(Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                UserManager.DISALLOW_SHARE_LOCATION);
        sRestrictedKeys.put(Settings.Secure.INSTALL_NON_MARKET_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        sRestrictedKeys.put(Settings.Global.ADB_ENABLED, UserManager.DISALLOW_DEBUGGING_FEATURES);
        sRestrictedKeys.put(Settings.Global.PACKAGE_VERIFIER_ENABLE,
                UserManager.ENSURE_VERIFY_APPS);
        sRestrictedKeys.put(Settings.Global.PREFERRED_NETWORK_MODE,
                UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);

        sSecureCloneToManagedKeys = new HashSet<String>();
        for (int i = 0; i < Settings.Secure.CLONE_TO_MANAGED_PROFILE.length; i++) {
            sSecureCloneToManagedKeys.add(Settings.Secure.CLONE_TO_MANAGED_PROFILE[i]);
        }
        sSystemCloneToManagedKeys = new HashSet<String>();
        for (int i = 0; i < Settings.System.CLONE_TO_MANAGED_PROFILE.length; i++) {
            sSystemCloneToManagedKeys.add(Settings.System.CLONE_TO_MANAGED_PROFILE[i]);
        }
    }

    private boolean settingMovedToGlobal(final String name) {
        return sSecureGlobalKeys.contains(name) || sSystemGlobalKeys.contains(name);
    }

    /**
     * Decode a content URL into the table, projection, and arguments
     * used to access the corresponding database rows.
     */
    private static class SqlArguments {
        public String table;
        public final String where;
        public final String[] args;

        /** Operate on existing rows. */
        SqlArguments(Uri url, String where, String[] args) {
            if (url.getPathSegments().size() == 1) {
                // of the form content://settings/secure, arbitrary where clause
                this.table = url.getPathSegments().get(0);
                if (!DatabaseHelper.isValidTable(this.table)) {
                    throw new IllegalArgumentException("Bad root path: " + this.table);
                }
                this.where = where;
                this.args = args;
            } else if (url.getPathSegments().size() != 2) {
                throw new IllegalArgumentException("Invalid URI: " + url);
            } else if (!TextUtils.isEmpty(where)) {
                throw new UnsupportedOperationException("WHERE clause not supported: " + url);
            } else {
                // of the form content://settings/secure/element_name, no where clause
                this.table = url.getPathSegments().get(0);
                if (!DatabaseHelper.isValidTable(this.table)) {
                    throw new IllegalArgumentException("Bad root path: " + this.table);
                }
                if (TABLE_SYSTEM.equals(this.table) || TABLE_SECURE.equals(this.table) ||
                    TABLE_GLOBAL.equals(this.table)) {
                    this.where = Settings.NameValueTable.NAME + "=?";
                    final String name = url.getPathSegments().get(1);
                    this.args = new String[] { name };
                    // Rewrite the table for known-migrated names
                    if (TABLE_SYSTEM.equals(this.table) || TABLE_SECURE.equals(this.table)) {
                        if (sSecureGlobalKeys.contains(name) || sSystemGlobalKeys.contains(name)) {
                            this.table = TABLE_GLOBAL;
                        }
                    }
                } else {
                    // of the form content://bookmarks/19
                    this.where = "_id=" + ContentUris.parseId(url);
                    this.args = null;
                }
            }
        }

        /** Insert new rows (no where clause allowed). */
        SqlArguments(Uri url) {
            if (url.getPathSegments().size() == 1) {
                this.table = url.getPathSegments().get(0);
                if (!DatabaseHelper.isValidTable(this.table)) {
                    throw new IllegalArgumentException("Bad root path: " + this.table);
                }
                this.where = null;
                this.args = null;
            } else {
                throw new IllegalArgumentException("Invalid URI: " + url);
            }
        }
    }

    /**
     * Get the content URI of a row added to a table.
     * @param tableUri of the entire table
     * @param values found in the row
     * @param rowId of the row
     * @return the content URI for this particular row
     */
    private Uri getUriFor(Uri tableUri, ContentValues values, long rowId) {
        if (tableUri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Invalid URI: " + tableUri);
        }
        String table = tableUri.getPathSegments().get(0);
        if (TABLE_SYSTEM.equals(table) ||
                TABLE_SECURE.equals(table) ||
                TABLE_GLOBAL.equals(table)) {
            String name = values.getAsString(Settings.NameValueTable.NAME);
            return Uri.withAppendedPath(tableUri, name);
        } else {
            return ContentUris.withAppendedId(tableUri, rowId);
        }
    }

    /**
     * Send a notification when a particular content URI changes.
     * Modify the system property used to communicate the version of
     * this table, for tables which have such a property.  (The Settings
     * contract class uses these to provide client-side caches.)
     * @param uri to send notifications for
     */
    private void sendNotify(Uri uri, int userHandle) {
        // Update the system property *first*, so if someone is listening for
        // a notification and then using the contract class to get their data,
        // the system property will be updated and they'll get the new data.

        boolean backedUpDataChanged = false;
        String property = null, table = uri.getPathSegments().get(0);
        final boolean isGlobal = table.equals(TABLE_GLOBAL);
        if (table.equals(TABLE_SYSTEM)) {
            property = Settings.System.SYS_PROP_SETTING_VERSION;
            backedUpDataChanged = true;
        } else if (table.equals(TABLE_SECURE)) {
            property = Settings.Secure.SYS_PROP_SETTING_VERSION;
            backedUpDataChanged = true;
        } else if (isGlobal) {
            property = Settings.Global.SYS_PROP_SETTING_VERSION;    // this one is global
            backedUpDataChanged = true;
        }

        if (property != null) {
            long version = SystemProperties.getLong(property, 0) + 1;
            if (LOCAL_LOGV) Log.v(TAG, "property: " + property + "=" + version);
            SystemProperties.set(property, Long.toString(version));
        }

        // Inform the backup manager about a data change
        if (backedUpDataChanged) {
            mBackupManager.dataChanged();
        }
        // Now send the notification through the content framework.

        String notify = uri.getQueryParameter("notify");
        if (notify == null || "true".equals(notify)) {
            final int notifyTarget = isGlobal ? UserHandle.USER_ALL : userHandle;
            final long oldId = Binder.clearCallingIdentity();
            try {
                getContext().getContentResolver().notifyChange(uri, null, true, notifyTarget);
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }
            if (LOCAL_LOGV) Log.v(TAG, "notifying for " + notifyTarget + ": " + uri);
        } else {
            if (LOCAL_LOGV) Log.v(TAG, "notification suppressed: " + uri);
        }
    }

    /**
     * Make sure the caller has permission to write this data.
     * @param args supplied by the caller
     * @throws SecurityException if the caller is forbidden to write.
     */
    private void checkWritePermissions(SqlArguments args) {
        if ((TABLE_SECURE.equals(args.table) || TABLE_GLOBAL.equals(args.table)) &&
            getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    String.format("Permission denial: writing to secure settings requires %1$s",
                                  android.Manifest.permission.WRITE_SECURE_SETTINGS));
        }
    }

    private void checkUserRestrictions(String setting) {
        String userRestriction = sRestrictedKeys.get(setting);
        if (!TextUtils.isEmpty(userRestriction)
            && mUserManager.hasUserRestriction(userRestriction)) {
            throw new SecurityException(
                    "Permission denial: user is restricted from changing this setting.");
        }
    }

    // FileObserver for external modifications to the database file.
    // Note that this is for platform developers only with
    // userdebug/eng builds who should be able to tinker with the
    // sqlite database out from under the SettingsProvider, which is
    // normally the exclusive owner of the database.  But we keep this
    // enabled all the time to minimize development-vs-user
    // differences in testing.
    private static SparseArray<SettingsFileObserver> sObserverInstances
            = new SparseArray<SettingsFileObserver>();
    private class SettingsFileObserver extends FileObserver {
        private final AtomicBoolean mIsDirty = new AtomicBoolean(false);
        private final int mUserHandle;
        private final String mPath;

        public SettingsFileObserver(int userHandle, String path) {
            super(path, FileObserver.CLOSE_WRITE |
                  FileObserver.CREATE | FileObserver.DELETE |
                  FileObserver.MOVED_TO | FileObserver.MODIFY);
            mUserHandle = userHandle;
            mPath = path;
        }

        public void onEvent(int event, String path) {
            int modsInFlight = sKnownMutationsInFlight.get(mUserHandle).get();
            if (modsInFlight > 0) {
                // our own modification.
                return;
            }
            Log.d(TAG, "User " + mUserHandle + " external modification to " + mPath
                    + "; event=" + event);
            if (!mIsDirty.compareAndSet(false, true)) {
                // already handled. (we get a few update events
                // during an sqlite write)
                return;
            }
            Log.d(TAG, "User " + mUserHandle + " updating our caches for " + mPath);
            fullyPopulateCaches(mUserHandle);
            mIsDirty.set(false);
        }
    }

    @Override
    public boolean onCreate() {
        mBackupManager = new BackupManager(getContext());
        mUserManager = UserManager.get(getContext());

        setAppOps(AppOpsManager.OP_NONE, AppOpsManager.OP_WRITE_SETTINGS);
        establishDbTracking(UserHandle.USER_OWNER);

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_OWNER);
                if (intent.getAction().equals(Intent.ACTION_USER_REMOVED)) {
                    onUserRemoved(userHandle);
                } else if (intent.getAction().equals(Intent.ACTION_USER_ADDED)) {
                    onProfilesChanged();
                }
            }
        }, userFilter);

        onProfilesChanged();

        return true;
    }

    void onUserRemoved(int userHandle) {
        synchronized (this) {
            // the db file itself will be deleted automatically, but we need to tear down
            // our caches and other internal bookkeeping.
            FileObserver observer = sObserverInstances.get(userHandle);
            if (observer != null) {
                observer.stopWatching();
                sObserverInstances.delete(userHandle);
            }

            mOpenHelpers.delete(userHandle);
            sSystemCaches.delete(userHandle);
            sSecureCaches.delete(userHandle);
            sKnownMutationsInFlight.delete(userHandle);
            onProfilesChanged();
        }
    }

    /**
     * Updates the list of managed profiles. It assumes that only the primary user
     * can have managed profiles. Modify this code if that changes in the future.
     */
    void onProfilesChanged() {
        synchronized (this) {
            mManagedProfiles = mUserManager.getProfiles(UserHandle.USER_OWNER);
            if (mManagedProfiles != null) {
                // Remove the primary user from the list
                for (int i = mManagedProfiles.size() - 1; i >= 0; i--) {
                    if (mManagedProfiles.get(i).id == UserHandle.USER_OWNER) {
                        mManagedProfiles.remove(i);
                    }
                }
                // If there are no managed profiles, reset the variable
                if (mManagedProfiles.size() == 0) {
                    mManagedProfiles = null;
                }
            }
            if (LOCAL_LOGV) {
                Slog.d(TAG, "Managed Profiles = " + mManagedProfiles);
            }
        }
    }

    private void establishDbTracking(int userHandle) {
        if (LOCAL_LOGV) {
            Slog.i(TAG, "Installing settings db helper and caches for user " + userHandle);
        }

        DatabaseHelper dbhelper;

        synchronized (this) {
            dbhelper = mOpenHelpers.get(userHandle);
            if (dbhelper == null) {
                dbhelper = new DatabaseHelper(getContext(), userHandle);
                mOpenHelpers.append(userHandle, dbhelper);

                sSystemCaches.append(userHandle, new SettingsCache(TABLE_SYSTEM));
                sSecureCaches.append(userHandle, new SettingsCache(TABLE_SECURE));
                sKnownMutationsInFlight.append(userHandle, new AtomicInteger(0));
            }
        }

        // Initialization of the db *outside* the locks.  It's possible that racing
        // threads might wind up here, the second having read the cache entries
        // written by the first, but that's benign: the SQLite helper implementation
        // manages concurrency itself, and it's important that we not run the db
        // initialization with any of our own locks held, so we're fine.
        SQLiteDatabase db = dbhelper.getWritableDatabase();

        // Watch for external modifications to the database files,
        // keeping our caches in sync.  We synchronize the observer set
        // separately, and of course it has to run after the db file
        // itself was set up by the DatabaseHelper.
        synchronized (sObserverInstances) {
            if (sObserverInstances.get(userHandle) == null) {
                SettingsFileObserver observer = new SettingsFileObserver(userHandle, db.getPath());
                sObserverInstances.append(userHandle, observer);
                observer.startWatching();
            }
        }

        ensureAndroidIdIsSet(userHandle);

        startAsyncCachePopulation(userHandle);
    }

    class CachePrefetchThread extends Thread {
        private int mUserHandle;

        CachePrefetchThread(int userHandle) {
            super("populate-settings-caches");
            mUserHandle = userHandle;
        }

        @Override
        public void run() {
            fullyPopulateCaches(mUserHandle);
        }
    }

    private void startAsyncCachePopulation(int userHandle) {
        new CachePrefetchThread(userHandle).start();
    }

    private void fullyPopulateCaches(final int userHandle) {
        DatabaseHelper dbHelper = mOpenHelpers.get(userHandle);
        // Only populate the globals cache once, for the owning user
        if (userHandle == UserHandle.USER_OWNER) {
            fullyPopulateCache(dbHelper, TABLE_GLOBAL, sGlobalCache);
        }
        fullyPopulateCache(dbHelper, TABLE_SECURE, sSecureCaches.get(userHandle));
        fullyPopulateCache(dbHelper, TABLE_SYSTEM, sSystemCaches.get(userHandle));
    }

    // Slurp all values (if sane in number & size) into cache.
    private void fullyPopulateCache(DatabaseHelper dbHelper, String table, SettingsCache cache) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(
            table,
            new String[] { Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE },
            null, null, null, null, null,
            "" + (MAX_CACHE_ENTRIES + 1) /* limit */);
        try {
            synchronized (cache) {
                cache.evictAll();
                cache.setFullyMatchesDisk(true);  // optimistic
                int rows = 0;
                while (c.moveToNext()) {
                    rows++;
                    String name = c.getString(0);
                    String value = c.getString(1);
                    cache.populate(name, value);
                }
                if (rows > MAX_CACHE_ENTRIES) {
                    // Somewhat redundant, as removeEldestEntry() will
                    // have already done this, but to be explicit:
                    cache.setFullyMatchesDisk(false);
                    Log.d(TAG, "row count exceeds max cache entries for table " + table);
                }
                if (LOCAL_LOGV) Log.d(TAG, "cache for settings table '" + table
                        + "' rows=" + rows + "; fullycached=" + cache.fullyMatchesDisk());
            }
        } finally {
            c.close();
        }
    }

    private boolean ensureAndroidIdIsSet(int userHandle) {
        final Cursor c = queryForUser(Settings.Secure.CONTENT_URI,
                new String[] { Settings.NameValueTable.VALUE },
                Settings.NameValueTable.NAME + "=?",
                new String[] { Settings.Secure.ANDROID_ID }, null,
                userHandle);
        try {
            final String value = c.moveToNext() ? c.getString(0) : null;
            if (value == null) {
                // sanity-check the user before touching the db
                final UserInfo user = mUserManager.getUserInfo(userHandle);
                if (user == null) {
                    // can happen due to races when deleting users; treat as benign
                    return false;
                }

                final SecureRandom random = new SecureRandom();
                final String newAndroidIdValue = Long.toHexString(random.nextLong());
                final ContentValues values = new ContentValues();
                values.put(Settings.NameValueTable.NAME, Settings.Secure.ANDROID_ID);
                values.put(Settings.NameValueTable.VALUE, newAndroidIdValue);
                final Uri uri = insertForUser(Settings.Secure.CONTENT_URI, values, userHandle);
                if (uri == null) {
                    Slog.e(TAG, "Unable to generate new ANDROID_ID for user " + userHandle);
                    return false;
                }
                Slog.d(TAG, "Generated and saved new ANDROID_ID [" + newAndroidIdValue
                        + "] for user " + userHandle);
                // Write a dropbox entry if it's a restricted profile
                if (user.isRestricted()) {
                    DropBoxManager dbm = (DropBoxManager)
                            getContext().getSystemService(Context.DROPBOX_SERVICE);
                    if (dbm != null && dbm.isTagEnabled(DROPBOX_TAG_USERLOG)) {
                        dbm.addText(DROPBOX_TAG_USERLOG, System.currentTimeMillis()
                                + ",restricted_profile_ssaid,"
                                + newAndroidIdValue + "\n");
                    }
                }
            }
            return true;
        } finally {
            c.close();
        }
    }

    // Lazy-initialize the settings caches for non-primary users
    private SettingsCache getOrConstructCache(int callingUser, SparseArray<SettingsCache> which) {
        getOrEstablishDatabase(callingUser); // ignore return value; we don't need it
        return which.get(callingUser);
    }

    // Lazy initialize the database helper and caches for this user, if necessary
    private DatabaseHelper getOrEstablishDatabase(int callingUser) {
        if (callingUser >= Process.SYSTEM_UID) {
            if (USER_CHECK_THROWS) {
                throw new IllegalArgumentException("Uid rather than user handle: " + callingUser);
            } else {
                Slog.wtf(TAG, "establish db for uid rather than user: " + callingUser);
            }
        }

        long oldId = Binder.clearCallingIdentity();
        try {
            DatabaseHelper dbHelper = mOpenHelpers.get(callingUser);
            if (null == dbHelper) {
                establishDbTracking(callingUser);
                dbHelper = mOpenHelpers.get(callingUser);
            }
            return dbHelper;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    public SettingsCache cacheForTable(final int callingUser, String tableName) {
        if (TABLE_SYSTEM.equals(tableName)) {
            return getOrConstructCache(callingUser, sSystemCaches);
        }
        if (TABLE_SECURE.equals(tableName)) {
            return getOrConstructCache(callingUser, sSecureCaches);
        }
        if (TABLE_GLOBAL.equals(tableName)) {
            return sGlobalCache;
        }
        return null;
    }

    /**
     * Used for wiping a whole cache on deletes when we're not
     * sure what exactly was deleted or changed.
     */
    public void invalidateCache(final int callingUser, String tableName) {
        SettingsCache cache = cacheForTable(callingUser, tableName);
        if (cache == null) {
            return;
        }
        synchronized (cache) {
            cache.evictAll();
            cache.mCacheFullyMatchesDisk = false;
        }
    }

    /**
     * Checks if the calling user is a managed profile of the primary user.
     * Currently only the primary user (USER_OWNER) can have managed profiles.
     * @param callingUser the user trying to read/write settings
     * @return true if it is a managed profile of the primary user
     */
    private boolean isManagedProfile(int callingUser) {
        synchronized (this) {
            if (mManagedProfiles == null) return false;
            for (int i = mManagedProfiles.size() - 1; i >= 0; i--) {
                if (mManagedProfiles.get(i).id == callingUser) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Fast path that avoids the use of chatty remoted Cursors.
     */
    @Override
    public Bundle call(String method, String request, Bundle args) {
        int callingUser = UserHandle.getCallingUserId();
        if (args != null) {
            int reqUser = args.getInt(Settings.CALL_METHOD_USER_KEY, callingUser);
            if (reqUser != callingUser) {
                callingUser = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                        Binder.getCallingUid(), reqUser, false, true,
                        "get/set setting for user", null);
                if (LOCAL_LOGV) Slog.v(TAG, "   access setting for user " + callingUser);
            }
        }

        // Note: we assume that get/put operations for moved-to-global names have already
        // been directed to the new location on the caller side (otherwise we'd fix them
        // up here).
        DatabaseHelper dbHelper;
        SettingsCache cache;

        // Get methods
        if (Settings.CALL_METHOD_GET_SYSTEM.equals(method)) {
            if (LOCAL_LOGV) Slog.v(TAG, "call(system:" + request + ") for " + callingUser);
            if (isManagedProfile(callingUser) && sSystemCloneToManagedKeys.contains(request)) {
                callingUser = UserHandle.USER_OWNER;
            }
            dbHelper = getOrEstablishDatabase(callingUser);
            cache = sSystemCaches.get(callingUser);
            return lookupValue(dbHelper, TABLE_SYSTEM, cache, request);
        }
        if (Settings.CALL_METHOD_GET_SECURE.equals(method)) {
            if (LOCAL_LOGV) Slog.v(TAG, "call(secure:" + request + ") for " + callingUser);
            if (isManagedProfile(callingUser) && sSecureCloneToManagedKeys.contains(request)) {
                callingUser = UserHandle.USER_OWNER;
            }
            dbHelper = getOrEstablishDatabase(callingUser);
            cache = sSecureCaches.get(callingUser);
            return lookupValue(dbHelper, TABLE_SECURE, cache, request);
        }
        if (Settings.CALL_METHOD_GET_GLOBAL.equals(method)) {
            if (LOCAL_LOGV) Slog.v(TAG, "call(global:" + request + ") for " + callingUser);
            // fast path: owner db & cache are immutable after onCreate() so we need not
            // guard on the attempt to look them up
            return lookupValue(getOrEstablishDatabase(UserHandle.USER_OWNER), TABLE_GLOBAL,
                    sGlobalCache, request);
        }

        // Put methods - new value is in the args bundle under the key named by
        // the Settings.NameValueTable.VALUE static.
        final String newValue = (args == null)
                ? null : args.getString(Settings.NameValueTable.VALUE);

        // Framework can't do automatic permission checking for calls, so we need
        // to do it here.
        if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    String.format("Permission denial: writing to settings requires %1$s",
                                  android.Manifest.permission.WRITE_SETTINGS));
        }

        // Also need to take care of app op.
        if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SETTINGS, Binder.getCallingUid(),
                getCallingPackage()) != AppOpsManager.MODE_ALLOWED) {
            return null;
        }

        final ContentValues values = new ContentValues();
        values.put(Settings.NameValueTable.NAME, request);
        values.put(Settings.NameValueTable.VALUE, newValue);
        if (Settings.CALL_METHOD_PUT_SYSTEM.equals(method)) {
            if (LOCAL_LOGV) {
                Slog.v(TAG, "call_put(system:" + request + "=" + newValue + ") for "
                        + callingUser);
            }
            // Extra check for USER_OWNER to optimize for the 99%
            if (callingUser != UserHandle.USER_OWNER && isManagedProfile(callingUser)) {
                if (sSystemCloneToManagedKeys.contains(request)) {
                    // Don't write these settings
                    return null;
                }
            }
            insertForUser(Settings.System.CONTENT_URI, values, callingUser);
            // Clone the settings to the managed profiles so that notifications can be sent out
            if (callingUser == UserHandle.USER_OWNER && mManagedProfiles != null
                    && sSystemCloneToManagedKeys.contains(request)) {
                final long token = Binder.clearCallingIdentity();
                try {
                    for (int i = mManagedProfiles.size() - 1; i >= 0; i--) {
                        if (LOCAL_LOGV) {
                            Slog.v(TAG, "putting to additional user "
                                    + mManagedProfiles.get(i).id);
                        }
                        insertForUser(Settings.System.CONTENT_URI, values,
                                mManagedProfiles.get(i).id);
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if (Settings.CALL_METHOD_PUT_SECURE.equals(method)) {
            if (LOCAL_LOGV) {
                Slog.v(TAG, "call_put(secure:" + request + "=" + newValue + ") for "
                        + callingUser);
            }
            // Extra check for USER_OWNER to optimize for the 99%
            if (callingUser != UserHandle.USER_OWNER && isManagedProfile(callingUser)) {
                if (sSecureCloneToManagedKeys.contains(request)) {
                    // Don't write these settings
                    return null;
                }
            }
            insertForUser(Settings.Secure.CONTENT_URI, values, callingUser);
            // Clone the settings to the managed profiles so that notifications can be sent out
            if (callingUser == UserHandle.USER_OWNER && mManagedProfiles != null
                    && sSecureCloneToManagedKeys.contains(request)) {
                final long token = Binder.clearCallingIdentity();
                try {
                    for (int i = mManagedProfiles.size() - 1; i >= 0; i--) {
                        if (LOCAL_LOGV) {
                            Slog.v(TAG, "putting to additional user "
                                    + mManagedProfiles.get(i).id);
                        }
                        insertForUser(Settings.Secure.CONTENT_URI, values,
                                mManagedProfiles.get(i).id);
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if (Settings.CALL_METHOD_PUT_GLOBAL.equals(method)) {
            if (LOCAL_LOGV) {
                Slog.v(TAG, "call_put(global:" + request + "=" + newValue + ") for "
                        + callingUser);
            }
            insertForUser(Settings.Global.CONTENT_URI, values, callingUser);
        } else {
            Slog.w(TAG, "call() with invalid method: " + method);
        }

        return null;
    }

    // Looks up value 'key' in 'table' and returns either a single-pair Bundle,
    // possibly with a null value, or null on failure.
    private Bundle lookupValue(DatabaseHelper dbHelper, String table,
            final SettingsCache cache, String key) {
        if (cache == null) {
           Slog.e(TAG, "cache is null for user " + UserHandle.getCallingUserId() + " : key=" + key);
           return null;
        }
        synchronized (cache) {
            Bundle value = cache.get(key);
            if (value != null) {
                if (value != TOO_LARGE_TO_CACHE_MARKER) {
                    return value;
                }
                // else we fall through and read the value from disk
            } else if (cache.fullyMatchesDisk()) {
                // Fast path (very common).  Don't even try touch disk
                // if we know we've slurped it all in.  Trying to
                // touch the disk would mean waiting for yaffs2 to
                // give us access, which could takes hundreds of
                // milliseconds.  And we're very likely being called
                // from somebody's UI thread...
                return NULL_SETTING;
            }
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(table, COLUMN_VALUE, "name=?", new String[]{key},
                              null, null, null, null);
            if (cursor != null && cursor.getCount() == 1) {
                cursor.moveToFirst();
                return cache.putIfAbsent(key, cursor.getString(0));
            }
        } catch (SQLiteException e) {
            Log.w(TAG, "settings lookup error", e);
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
        cache.putIfAbsent(key, null);
        return NULL_SETTING;
    }

    @Override
    public Cursor query(Uri url, String[] select, String where, String[] whereArgs, String sort) {
        return queryForUser(url, select, where, whereArgs, sort, UserHandle.getCallingUserId());
    }

    private Cursor queryForUser(Uri url, String[] select, String where, String[] whereArgs,
            String sort, int forUser) {
        if (LOCAL_LOGV) Slog.v(TAG, "query(" + url + ") for user " + forUser);
        SqlArguments args = new SqlArguments(url, where, whereArgs);
        DatabaseHelper dbH;
        dbH = getOrEstablishDatabase(
                TABLE_GLOBAL.equals(args.table) ? UserHandle.USER_OWNER : forUser);
        SQLiteDatabase db = dbH.getReadableDatabase();

        // The favorites table was moved from this provider to a provider inside Home
        // Home still need to query this table to upgrade from pre-cupcake builds
        // However, a cupcake+ build with no data does not contain this table which will
        // cause an exception in the SQL stack. The following line is a special case to
        // let the caller of the query have a chance to recover and avoid the exception
        if (TABLE_FAVORITES.equals(args.table)) {
            return null;
        } else if (TABLE_OLD_FAVORITES.equals(args.table)) {
            args.table = TABLE_FAVORITES;
            Cursor cursor = db.rawQuery("PRAGMA table_info(favorites);", null);
            if (cursor != null) {
                boolean exists = cursor.getCount() > 0;
                cursor.close();
                if (!exists) return null;
            } else {
                return null;
            }
        }

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(args.table);

        Cursor ret = qb.query(db, select, args.where, args.args, null, null, sort);
        // the default Cursor interface does not support per-user observation
        try {
            AbstractCursor c = (AbstractCursor) ret;
            c.setNotificationUri(getContext().getContentResolver(), url, forUser);
        } catch (ClassCastException e) {
            // details of the concrete Cursor implementation have changed and this code has
            // not been updated to match -- complain and fail hard.
            Log.wtf(TAG, "Incompatible cursor derivation!");
            throw e;
        }
        return ret;
    }

    @Override
    public String getType(Uri url) {
        // If SqlArguments supplies a where clause, then it must be an item
        // (because we aren't supplying our own where clause).
        SqlArguments args = new SqlArguments(url, null, null);
        if (TextUtils.isEmpty(args.where)) {
            return "vnd.android.cursor.dir/" + args.table;
        } else {
            return "vnd.android.cursor.item/" + args.table;
        }
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        final int callingUser = UserHandle.getCallingUserId();
        if (LOCAL_LOGV) Slog.v(TAG, "bulkInsert() for user " + callingUser);
        SqlArguments args = new SqlArguments(uri);
        if (TABLE_FAVORITES.equals(args.table)) {
            return 0;
        }
        checkWritePermissions(args);
        SettingsCache cache = cacheForTable(callingUser, args.table);

        final AtomicInteger mutationCount = sKnownMutationsInFlight.get(callingUser);
        mutationCount.incrementAndGet();
        DatabaseHelper dbH = getOrEstablishDatabase(
                TABLE_GLOBAL.equals(args.table) ? UserHandle.USER_OWNER : callingUser);
        SQLiteDatabase db = dbH.getWritableDatabase();
        db.beginTransaction();
        try {
            int numValues = values.length;
            for (int i = 0; i < numValues; i++) {
                checkUserRestrictions(values[i].getAsString(Settings.Secure.NAME));
                if (db.insert(args.table, null, values[i]) < 0) return 0;
                SettingsCache.populate(cache, values[i]);
                if (LOCAL_LOGV) Log.v(TAG, args.table + " <- " + values[i]);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            mutationCount.decrementAndGet();
        }

        sendNotify(uri, callingUser);
        return values.length;
    }

    /*
     * Used to parse changes to the value of Settings.Secure.LOCATION_PROVIDERS_ALLOWED.
     * This setting contains a list of the currently enabled location providers.
     * But helper functions in android.providers.Settings can enable or disable
     * a single provider by using a "+" or "-" prefix before the provider name.
     *
     * @returns whether the database needs to be updated or not, also modifying
     *     'initialValues' if needed.
     */
    private boolean parseProviderList(Uri url, ContentValues initialValues, int desiredUser) {
        String value = initialValues.getAsString(Settings.Secure.VALUE);
        String newProviders = null;
        if (value != null && value.length() > 1) {
            char prefix = value.charAt(0);
            if (prefix == '+' || prefix == '-') {
                // skip prefix
                value = value.substring(1);

                // read list of enabled providers into "providers"
                String providers = "";
                String[] columns = {Settings.Secure.VALUE};
                String where = Settings.Secure.NAME + "=\'" + Settings.Secure.LOCATION_PROVIDERS_ALLOWED + "\'";
                Cursor cursor = queryForUser(url, columns, where, null, null, desiredUser);
                if (cursor != null && cursor.getCount() == 1) {
                    try {
                        cursor.moveToFirst();
                        providers = cursor.getString(0);
                    } finally {
                        cursor.close();
                    }
                }

                int index = providers.indexOf(value);
                int end = index + value.length();
                // check for commas to avoid matching on partial string
                if (index > 0 && providers.charAt(index - 1) != ',') index = -1;
                if (end < providers.length() && providers.charAt(end) != ',') index = -1;

                if (prefix == '+' && index < 0) {
                    // append the provider to the list if not present
                    if (providers.length() == 0) {
                        newProviders = value;
                    } else {
                        newProviders = providers + ',' + value;
                    }
                } else if (prefix == '-' && index >= 0) {
                    // remove the provider from the list if present
                    // remove leading or trailing comma
                    if (index > 0) {
                        index--;
                    } else if (end < providers.length()) {
                        end++;
                    }

                    newProviders = providers.substring(0, index);
                    if (end < providers.length()) {
                        newProviders += providers.substring(end);
                    }
                } else {
                    // nothing changed, so no need to update the database
                    return false;
                }

                if (newProviders != null) {
                    initialValues.put(Settings.Secure.VALUE, newProviders);
                }
            }
        }

        return true;
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        return insertForUser(url, initialValues, UserHandle.getCallingUserId());
    }

    // Settings.put*ForUser() always winds up here, so this is where we apply
    // policy around permission to write settings for other users.
    private Uri insertForUser(Uri url, ContentValues initialValues, int desiredUserHandle) {
        final int callingUser = UserHandle.getCallingUserId();
        if (callingUser != desiredUserHandle) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "Not permitted to access settings for other users");
        }

        if (LOCAL_LOGV) Slog.v(TAG, "insert(" + url + ") for user " + desiredUserHandle
                + " by " + callingUser);

        SqlArguments args = new SqlArguments(url);
        if (TABLE_FAVORITES.equals(args.table)) {
            return null;
        }

        // Special case LOCATION_PROVIDERS_ALLOWED.
        // Support enabling/disabling a single provider (using "+" or "-" prefix)
        String name = initialValues.getAsString(Settings.Secure.NAME);
        if (Settings.Secure.LOCATION_PROVIDERS_ALLOWED.equals(name)) {
            if (!parseProviderList(url, initialValues, desiredUserHandle)) return null;
        }

        // If this is an insert() of a key that has been migrated to the global store,
        // redirect the operation to that store
        if (name != null) {
            if (sSecureGlobalKeys.contains(name) || sSystemGlobalKeys.contains(name)) {
                if (!TABLE_GLOBAL.equals(args.table)) {
                    if (LOCAL_LOGV) Slog.i(TAG, "Rewrite of insert() of now-global key " + name);
                }
                args.table = TABLE_GLOBAL;  // next condition will rewrite the user handle
            }
        }

        // Check write permissions only after determining which table the insert will touch
        checkWritePermissions(args);

        checkUserRestrictions(name);

        // The global table is stored under the owner, always
        if (TABLE_GLOBAL.equals(args.table)) {
            desiredUserHandle = UserHandle.USER_OWNER;
        }

        SettingsCache cache = cacheForTable(desiredUserHandle, args.table);
        String value = initialValues.getAsString(Settings.NameValueTable.VALUE);
        if (SettingsCache.isRedundantSetValue(cache, name, value)) {
            return Uri.withAppendedPath(url, name);
        }

        final AtomicInteger mutationCount = sKnownMutationsInFlight.get(desiredUserHandle);
        mutationCount.incrementAndGet();
        DatabaseHelper dbH = getOrEstablishDatabase(desiredUserHandle);
        SQLiteDatabase db = dbH.getWritableDatabase();
        final long rowId = db.insert(args.table, null, initialValues);
        mutationCount.decrementAndGet();
        if (rowId <= 0) return null;

        SettingsCache.populate(cache, initialValues);  // before we notify

        if (LOCAL_LOGV) Log.v(TAG, args.table + " <- " + initialValues
                + " for user " + desiredUserHandle);
        // Note that we use the original url here, not the potentially-rewritten table name
        url = getUriFor(url, initialValues, rowId);
        sendNotify(url, desiredUserHandle);
        return url;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        int callingUser = UserHandle.getCallingUserId();
        if (LOCAL_LOGV) Slog.v(TAG, "delete() for user " + callingUser);
        SqlArguments args = new SqlArguments(url, where, whereArgs);
        if (TABLE_FAVORITES.equals(args.table)) {
            return 0;
        } else if (TABLE_OLD_FAVORITES.equals(args.table)) {
            args.table = TABLE_FAVORITES;
        } else if (TABLE_GLOBAL.equals(args.table)) {
            callingUser = UserHandle.USER_OWNER;
        }
        checkWritePermissions(args);

        final AtomicInteger mutationCount = sKnownMutationsInFlight.get(callingUser);
        mutationCount.incrementAndGet();
        DatabaseHelper dbH = getOrEstablishDatabase(callingUser);
        SQLiteDatabase db = dbH.getWritableDatabase();
        int count = db.delete(args.table, args.where, args.args);
        mutationCount.decrementAndGet();
        if (count > 0) {
            invalidateCache(callingUser, args.table);  // before we notify
            sendNotify(url, callingUser);
        }
        startAsyncCachePopulation(callingUser);
        if (LOCAL_LOGV) Log.v(TAG, args.table + ": " + count + " row(s) deleted");
        return count;
    }

    @Override
    public int update(Uri url, ContentValues initialValues, String where, String[] whereArgs) {
        // NOTE: update() is never called by the front-end Settings API, and updates that
        // wind up affecting rows in Secure that are globally shared will not have the
        // intended effect (the update will be invisible to the rest of the system).
        // This should have no practical effect, since writes to the Secure db can only
        // be done by system code, and that code should be using the correct API up front.
        int callingUser = UserHandle.getCallingUserId();
        if (LOCAL_LOGV) Slog.v(TAG, "update() for user " + callingUser);
        SqlArguments args = new SqlArguments(url, where, whereArgs);
        if (TABLE_FAVORITES.equals(args.table)) {
            return 0;
        } else if (TABLE_GLOBAL.equals(args.table)) {
            callingUser = UserHandle.USER_OWNER;
        }
        checkWritePermissions(args);
        checkUserRestrictions(initialValues.getAsString(Settings.Secure.NAME));

        final AtomicInteger mutationCount = sKnownMutationsInFlight.get(callingUser);
        mutationCount.incrementAndGet();
        DatabaseHelper dbH = getOrEstablishDatabase(callingUser);
        SQLiteDatabase db = dbH.getWritableDatabase();
        int count = db.update(args.table, initialValues, args.where, args.args);
        mutationCount.decrementAndGet();
        if (count > 0) {
            invalidateCache(callingUser, args.table);  // before we notify
            sendNotify(url, callingUser);
        }
        startAsyncCachePopulation(callingUser);
        if (LOCAL_LOGV) Log.v(TAG, args.table + ": " + count + " row(s) <- " + initialValues);
        return count;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {

        /*
         * When a client attempts to openFile the default ringtone or
         * notification setting Uri, we will proxy the call to the current
         * default ringtone's Uri (if it is in the media provider).
         */
        int ringtoneType = RingtoneManager.getDefaultType(uri);
        // Above call returns -1 if the Uri doesn't match a default type
        if (ringtoneType != -1) {
            Context context = getContext();

            // Get the current value for the default sound
            Uri soundUri = RingtoneManager.getActualDefaultRingtoneUri(context, ringtoneType);

            if (soundUri != null) {
                // Proxy the openFile call to media provider
                String authority = soundUri.getAuthority();
                if (authority.equals(MediaStore.AUTHORITY)) {
                    return context.getContentResolver().openFileDescriptor(soundUri, mode);
                }
            }
        }

        return super.openFile(uri, mode);
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {

        /*
         * When a client attempts to openFile the default ringtone or
         * notification setting Uri, we will proxy the call to the current
         * default ringtone's Uri (if it is in the media provider).
         */
        int ringtoneType = RingtoneManager.getDefaultType(uri);
        // Above call returns -1 if the Uri doesn't match a default type
        if (ringtoneType != -1) {
            Context context = getContext();

            // Get the current value for the default sound
            Uri soundUri = RingtoneManager.getActualDefaultRingtoneUri(context, ringtoneType);

            if (soundUri != null) {
                // Proxy the openFile call to media provider
                String authority = soundUri.getAuthority();
                if (authority.equals(MediaStore.AUTHORITY)) {
                    ParcelFileDescriptor pfd = null;
                    try {
                        pfd = context.getContentResolver().openFileDescriptor(soundUri, mode);
                        return new AssetFileDescriptor(pfd, 0, -1);
                    } catch (FileNotFoundException ex) {
                        // fall through and open the fallback ringtone below
                    }
                }

                try {
                    return super.openAssetFile(soundUri, mode);
                } catch (FileNotFoundException ex) {
                    // Since a non-null Uri was specified, but couldn't be opened,
                    // fall back to the built-in ringtone.
                    return context.getResources().openRawResourceFd(
                            com.android.internal.R.raw.fallbackring);
                }
            }
            // no need to fall through and have openFile() try again, since we
            // already know that will fail.
            throw new FileNotFoundException(); // or return null ?
        }

        // Note that this will end up calling openFile() above.
        return super.openAssetFile(uri, mode);
    }

    /**
     * In-memory LRU Cache of system and secure settings, along with
     * associated helper functions to keep cache coherent with the
     * database.
     */
    private static final class SettingsCache extends LruCache<String, Bundle> {

        private final String mCacheName;
        private boolean mCacheFullyMatchesDisk = false;  // has the whole database slurped.

        public SettingsCache(String name) {
            super(MAX_CACHE_ENTRIES);
            mCacheName = name;
        }

        /**
         * Is the whole database table slurped into this cache?
         */
        public boolean fullyMatchesDisk() {
            synchronized (this) {
                return mCacheFullyMatchesDisk;
            }
        }

        public void setFullyMatchesDisk(boolean value) {
            synchronized (this) {
                mCacheFullyMatchesDisk = value;
            }
        }

        @Override
        protected void entryRemoved(boolean evicted, String key, Bundle oldValue, Bundle newValue) {
            if (evicted) {
                mCacheFullyMatchesDisk = false;
            }
        }

        /**
         * Atomic cache population, conditional on size of value and if
         * we lost a race.
         *
         * @returns a Bundle to send back to the client from call(), even
         *     if we lost the race.
         */
        public Bundle putIfAbsent(String key, String value) {
            Bundle bundle = (value == null) ? NULL_SETTING : Bundle.forPair("value", value);
            if (value == null || value.length() <= MAX_CACHE_ENTRY_SIZE) {
                synchronized (this) {
                    if (get(key) == null) {
                        put(key, bundle);
                    }
                }
            }
            return bundle;
        }

        /**
         * Populates a key in a given (possibly-null) cache.
         */
        public static void populate(SettingsCache cache, ContentValues contentValues) {
            if (cache == null) {
                return;
            }
            String name = contentValues.getAsString(Settings.NameValueTable.NAME);
            if (name == null) {
                Log.w(TAG, "null name populating settings cache.");
                return;
            }
            String value = contentValues.getAsString(Settings.NameValueTable.VALUE);
            cache.populate(name, value);
        }

        public void populate(String name, String value) {
            synchronized (this) {
                if (value == null || value.length() <= MAX_CACHE_ENTRY_SIZE) {
                    put(name, Bundle.forPair(Settings.NameValueTable.VALUE, value));
                } else {
                    put(name, TOO_LARGE_TO_CACHE_MARKER);
                }
            }
        }

        /**
         * For suppressing duplicate/redundant settings inserts early,
         * checking our cache first (but without faulting it in),
         * before going to sqlite with the mutation.
         */
        public static boolean isRedundantSetValue(SettingsCache cache, String name, String value) {
            if (cache == null) return false;
            synchronized (cache) {
                Bundle bundle = cache.get(name);
                if (bundle == null) return false;
                String oldValue = bundle.getPairValue();
                if (oldValue == null && value == null) return true;
                if ((oldValue == null) != (value == null)) return false;
                return oldValue.equals(value);
            }
        }
    }
}
