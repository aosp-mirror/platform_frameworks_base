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

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.provider.DrmStore;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileNotFoundException;

public class SettingsProvider extends ContentProvider {
    private static final String TAG = "SettingsProvider";
    private static final boolean LOCAL_LOGV = false;

    protected DatabaseHelper mOpenHelper;

    /**
     * Decode a content URL into the table, projection, and arguments
     * used to access the corresponding database rows.
     */
    private static class SqlArguments {
        public final String table;
        public final String where;
        public final String[] args;

        /** Operate on existing rows. */
        SqlArguments(Uri url, String where, String[] args) {
            if (url.getPathSegments().size() == 1) {
                this.table = url.getPathSegments().get(0);
                this.where = where;
                this.args = args;
            } else if (url.getPathSegments().size() != 2) {
                throw new IllegalArgumentException("Invalid URI: " + url);
            } else if (!TextUtils.isEmpty(where)) {
                throw new UnsupportedOperationException("WHERE clause not supported: " + url);
            } else {
                this.table = url.getPathSegments().get(0);
                if ("gservices".equals(this.table) || "system".equals(this.table)
                        || "secure".equals(this.table)) {
                    this.where = Settings.NameValueTable.NAME + "=?";
                    this.args = new String[] { url.getPathSegments().get(1) };
                } else {
                    this.where = "_id=" + ContentUris.parseId(url);
                    this.args = null;
                }
            }
        }

        /** Insert new rows (no where clause allowed). */
        SqlArguments(Uri url) {
            if (url.getPathSegments().size() == 1) {
                this.table = url.getPathSegments().get(0);
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
        if ("gservices".equals(table) || "system".equals(table)
                || "secure".equals(table)) {
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
    private void sendNotify(Uri uri) {
        // Update the system property *first*, so if someone is listening for
        // a notification and then using the contract class to get their data,
        // the system property will be updated and they'll get the new data.

        String property = null, table = uri.getPathSegments().get(0);
        if (table.equals("system")) {
            property = Settings.System.SYS_PROP_SETTING_VERSION;
        } else if (table.equals("secure")) {
            property = Settings.Secure.SYS_PROP_SETTING_VERSION;
        } else if (table.equals("gservices")) {
            property = Settings.Gservices.SYS_PROP_SETTING_VERSION;
        }

        if (property != null) {
            long version = SystemProperties.getLong(property, 0) + 1;
            if (LOCAL_LOGV) Log.v(TAG, "property: " + property + "=" + version);
            SystemProperties.set(property, Long.toString(version));
        }

        // Now send the notification through the content framework.

        String notify = uri.getQueryParameter("notify");
        if (notify == null || "true".equals(notify)) {
            getContext().getContentResolver().notifyChange(uri, null);
            if (LOCAL_LOGV) Log.v(TAG, "notifying: " + uri);
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
        if ("secure".equals(args.table) &&
                getContext().checkCallingOrSelfPermission(
                        android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                    PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Cannot write secure settings table");
        
        // TODO: Move gservices into its own provider so we don't need this nonsense.
        } else if ("gservices".equals(args.table) &&
            getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_GSERVICES) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Cannot write gservices table");
        }
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] select, String where, String[] whereArgs, String sort) {
        SqlArguments args = new SqlArguments(url, where, whereArgs);
        // The favorites table was moved from this provider to a provider inside Home
        // Home still need to query this table to upgrade from pre-cupcake builds
        // However, a cupcake+ build with no data does not contain this table which will
        // cause an exception in the SQL stack. The following line is a special case to
        // let the caller of the query have a chance to recover and avoid the exception
        if ("favorites".equals(args.table)) return null;

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(args.table);

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = qb.query(db, select, args.where, args.args, null, null, sort);
        ret.setNotificationUri(getContext().getContentResolver(), url);
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
        SqlArguments args = new SqlArguments(uri);
        checkWritePermissions(args);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            int numValues = values.length;
            for (int i = 0; i < numValues; i++) {
                if (db.insert(args.table, null, values[i]) < 0) return 0;
                if (LOCAL_LOGV) Log.v(TAG, args.table + " <- " + values[i]);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        sendNotify(uri);
        return values.length;
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        SqlArguments args = new SqlArguments(url);
        checkWritePermissions(args);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        final long rowId = db.insert(args.table, null, initialValues);
        if (rowId <= 0) return null;

        if (LOCAL_LOGV) Log.v(TAG, args.table + " <- " + initialValues);
        url = getUriFor(url, initialValues, rowId);
        sendNotify(url);
        return url;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        SqlArguments args = new SqlArguments(url, where, whereArgs);
        checkWritePermissions(args);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.delete(args.table, args.where, args.args);
        if (count > 0) sendNotify(url);
        if (LOCAL_LOGV) Log.v(TAG, args.table + ": " + count + " row(s) deleted");
        return count;
    }

    @Override
    public int update(Uri url, ContentValues initialValues, String where, String[] whereArgs) {
        SqlArguments args = new SqlArguments(url, where, whereArgs);
        checkWritePermissions(args);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.update(args.table, initialValues, args.where, args.args);
        if (count > 0) sendNotify(url);
        if (LOCAL_LOGV) Log.v(TAG, args.table + ": " + count + " row(s) <- " + initialValues);
        return count;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {

        /*
         * When a client attempts to openFile the default ringtone or
         * notification setting Uri, we will proxy the call to the current
         * default ringtone's Uri (if it is in the DRM or media provider).
         */ 
        int ringtoneType = RingtoneManager.getDefaultType(uri);
        // Above call returns -1 if the Uri doesn't match a default type
        if (ringtoneType != -1) {
            Context context = getContext();
            
            // Get the current value for the default sound
            Uri soundUri = RingtoneManager.getActualDefaultRingtoneUri(context, ringtoneType);
            if (soundUri == null) {
                // Fallback on any valid ringtone Uri
                soundUri = RingtoneManager.getValidRingtoneUri(context);
            }

            if (soundUri != null) { 
                // Only proxy the openFile call to drm or media providers
                String authority = soundUri.getAuthority();
                boolean isDrmAuthority = authority.equals(DrmStore.AUTHORITY);
                if (isDrmAuthority || authority.equals(MediaStore.AUTHORITY)) {

                    if (isDrmAuthority) {
                        try {
                            // Check DRM access permission here, since once we
                            // do the below call the DRM will be checking our
                            // permission, not our caller's permission
                            DrmStore.enforceAccessDrmPermission(context);
                        } catch (SecurityException e) {
                            throw new FileNotFoundException(e.getMessage());
                        }
                    }
                    
                    return context.getContentResolver().openFileDescriptor(soundUri, mode);
                }
            }
        }

        return super.openFile(uri, mode);
    }
}
