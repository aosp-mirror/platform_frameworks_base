/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.smspush;

import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteDatabase;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.telephony.IWapPushManager;
import com.android.internal.telephony.WapPushManagerParams;

/**
 * The WapPushManager service is implemented to process incoming
 * WAP Push messages and to maintain the Receiver Application/Application
 * ID mapping. The WapPushManager runs as a system service, and only the
 * WapPushManager can update the WAP Push message and Receiver Application
 * mapping (Application ID Table). When the device receives an SMS WAP Push
 * message, the WapPushManager looks up the Receiver Application name in
 * Application ID Table. If an application is found, the application is
 * launched using its full component name instead of broadcasting an implicit
 * Intent. If a Receiver Application is not found in the Application ID
 * Table or the WapPushManager returns a process-further value, the
 * telephony stack will process the message using existing message processing
 * flow, and broadcast an implicit Intent.
 */
public class WapPushManager extends Service {

    private static final String LOG_TAG = "WAP PUSH";
    private static final String DATABASE_NAME = "wappush.db";
    private static final String APPID_TABLE_NAME = "appid_tbl";

    /**
     * Version number must be incremented when table structure is changed.
     */
    private static final int WAP_PUSH_MANAGER_VERSION = 1;
    private static final boolean DEBUG_SQL = false;
    private static final boolean LOCAL_LOGV = false;

    /**
     * Inner class that deals with application ID table
     */
    private class WapPushManDBHelper extends SQLiteOpenHelper {
        WapPushManDBHelper(Context context) {
            super(context, DATABASE_NAME, null, WAP_PUSH_MANAGER_VERSION);
            if (LOCAL_LOGV) Log.v(LOG_TAG, "helper instance created.");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            if (LOCAL_LOGV) Log.v(LOG_TAG, "db onCreate.");
            String sql = "CREATE TABLE " + APPID_TABLE_NAME + " ("
                    + "id INTEGER PRIMARY KEY, "
                    + "x_wap_application TEXT, "
                    + "content_type TEXT, "
                    + "package_name TEXT, "
                    + "class_name TEXT, "
                    + "app_type INTEGER, "
                    + "need_signature INTEGER, "
                    + "further_processing INTEGER, "
                    + "install_order INTEGER "
                    + ")";

            if (DEBUG_SQL) Log.v(LOG_TAG, "sql: " + sql);
            db.execSQL(sql);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db,
                    int oldVersion, int newVersion) {
            // TODO: when table structure is changed, need to dump and restore data.
            /*
              db.execSQL(
              "drop table if exists "+APPID_TABLE_NAME);
              onCreate(db);
            */
            Log.w(LOG_TAG, "onUpgrade is not implemented yet. do nothing.");
        }

        protected class queryData {
            public String packageName;
            public String className;
            int appType;
            int needSignature;
            int furtherProcessing;
            int installOrder;
        }

        /**
         * Query the latest receiver application info with supplied application ID and
         * content type.
         * @param app_id    application ID to look up
         * @param content_type    content type to look up
         */
        protected queryData queryLastApp(SQLiteDatabase db,
                String app_id, String content_type) {
            if (LOCAL_LOGV) Log.v(LOG_TAG, "queryLastApp app_id: " + app_id
                    + " content_type: " +  content_type);

            Cursor cur = db.query(APPID_TABLE_NAME,
                    new String[] {"install_order", "package_name", "class_name",
                    "app_type", "need_signature", "further_processing"},
                    "x_wap_application=? and content_type=?",
                    new String[] {app_id, content_type},
                    null /* groupBy */,
                    null /* having */,
                    "install_order desc" /* orderBy */);

            queryData ret = null;

            if (cur.moveToNext()) {
                ret = new queryData();
                ret.installOrder = cur.getInt(cur.getColumnIndex("install_order"));
                ret.packageName = cur.getString(cur.getColumnIndex("package_name"));
                ret.className = cur.getString(cur.getColumnIndex("class_name"));
                ret.appType = cur.getInt(cur.getColumnIndex("app_type"));
                ret.needSignature = cur.getInt(cur.getColumnIndex("need_signature"));
                ret.furtherProcessing = cur.getInt(cur.getColumnIndex("further_processing"));
            }
            cur.close();
            return ret;
        }

    }

    /**
     * The exported API implementations class
     */
    private class IWapPushManagerStub extends IWapPushManager.Stub {
        public Context mContext;

        public IWapPushManagerStub() {

        }

        /**
         * Compare the package signature with WapPushManager package
         */
        protected boolean signatureCheck(String package_name) {
            PackageManager pm = mContext.getPackageManager();
            int match = pm.checkSignatures(mContext.getPackageName(), package_name);

            if (LOCAL_LOGV) Log.v(LOG_TAG, "compare signature " + mContext.getPackageName()
                    + " and " +  package_name + ", match=" + match);

            return match == PackageManager.SIGNATURE_MATCH;
        }

        /**
         * Returns the status value of the message processing.
         * The message will be processed as follows:
         * 1.Look up Application ID Table with x-wap-application-id + content type
         * 2.Check the signature of package name that is found in the
         *   Application ID Table by using PackageManager.checkSignature
         * 3.Trigger the Application
         * 4.Returns the process status value.
         */
        public int processMessage(String app_id, String content_type, Intent intent)
            throws RemoteException {
            Log.d(LOG_TAG, "wpman processMsg " + app_id + ":" + content_type);

            WapPushManDBHelper dbh = getDatabase(mContext);
            SQLiteDatabase db = dbh.getReadableDatabase();
            WapPushManDBHelper.queryData lastapp = dbh.queryLastApp(db, app_id, content_type);
            db.close();

            if (lastapp == null) {
                Log.w(LOG_TAG, "no receiver app found for " + app_id + ":" + content_type);
                return WapPushManagerParams.APP_QUERY_FAILED;
            }
            if (LOCAL_LOGV) Log.v(LOG_TAG, "starting " + lastapp.packageName
                    + "/" + lastapp.className);

            if (lastapp.needSignature != 0) {
                if (!signatureCheck(lastapp.packageName)) {
                    return WapPushManagerParams.SIGNATURE_NO_MATCH;
                }
            }

            if (lastapp.appType == WapPushManagerParams.APP_TYPE_ACTIVITY) {
                //Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName(lastapp.packageName, lastapp.className);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                try {
                    mContext.startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Log.w(LOG_TAG, "invalid name " +
                            lastapp.packageName + "/" + lastapp.className);
                    return WapPushManagerParams.INVALID_RECEIVER_NAME;
                }
            } else {
                intent.setClassName(mContext, lastapp.className);
                intent.setComponent(new ComponentName(lastapp.packageName,
                        lastapp.className));
                if (mContext.startService(intent) == null) {
                    Log.w(LOG_TAG, "invalid name " +
                            lastapp.packageName + "/" + lastapp.className);
                    return WapPushManagerParams.INVALID_RECEIVER_NAME;
                }
            }

            return WapPushManagerParams.MESSAGE_HANDLED
                    | (lastapp.furtherProcessing == 1 ?
                            WapPushManagerParams.FURTHER_PROCESSING : 0);
        }

        protected boolean appTypeCheck(int app_type) {
            if (app_type == WapPushManagerParams.APP_TYPE_ACTIVITY ||
                    app_type == WapPushManagerParams.APP_TYPE_SERVICE) {
                return true;
            } else {
                return false;
            }
        }

        /**
         * Returns true if adding the package succeeded.
         */
        public boolean addPackage(String x_app_id, String content_type,
                String package_name, String class_name,
                int app_type, boolean need_signature, boolean further_processing) {
            WapPushManDBHelper dbh = getDatabase(mContext);
            SQLiteDatabase db = dbh.getWritableDatabase();
            WapPushManDBHelper.queryData lastapp = dbh.queryLastApp(db, x_app_id, content_type);
            boolean ret = false;
            boolean insert = false;
            int sq = 0;

            if (!appTypeCheck(app_type)) {
                Log.w(LOG_TAG, "invalid app_type " + app_type + ". app_type must be "
                        + WapPushManagerParams.APP_TYPE_ACTIVITY + " or "
                        + WapPushManagerParams.APP_TYPE_SERVICE);
                return false;
            }

            if (lastapp == null) {
                insert = true;
                sq = 0;
            } else if (!lastapp.packageName.equals(package_name) ||
                    !lastapp.className.equals(class_name)) {
                insert = true;
                sq = lastapp.installOrder + 1;
            }

            if (insert) {
                ContentValues values = new ContentValues();

                values.put("x_wap_application", x_app_id);
                values.put("content_type", content_type);
                values.put("package_name", package_name);
                values.put("class_name", class_name);
                values.put("app_type", app_type);
                values.put("need_signature", need_signature ? 1 : 0);
                values.put("further_processing", further_processing ? 1 : 0);
                values.put("install_order", sq);
                db.insert(APPID_TABLE_NAME, null, values);
                if (LOCAL_LOGV) Log.v(LOG_TAG, "add:" + x_app_id + ":" + content_type
                        + " " + package_name + "." + class_name
                        + ", newsq:" + sq);
                ret = true;
            }

            db.close();

            return ret;
        }

        /**
         * Returns true if updating the package succeeded.
         */
        public boolean updatePackage(String x_app_id, String content_type,
                String package_name, String class_name,
                int app_type, boolean need_signature, boolean further_processing) {

            if (!appTypeCheck(app_type)) {
                Log.w(LOG_TAG, "invalid app_type " + app_type + ". app_type must be "
                        + WapPushManagerParams.APP_TYPE_ACTIVITY + " or "
                        + WapPushManagerParams.APP_TYPE_SERVICE);
                return false;
            }

            WapPushManDBHelper dbh = getDatabase(mContext);
            SQLiteDatabase db = dbh.getWritableDatabase();
            WapPushManDBHelper.queryData lastapp = dbh.queryLastApp(db, x_app_id, content_type);

            if (lastapp == null) {
                db.close();
                return false;
            }

            ContentValues values = new ContentValues();
            String where = "x_wap_application=\'" + x_app_id + "\'"
                    + " and content_type=\'" + content_type + "\'"
                    + " and install_order=" + lastapp.installOrder;

            values.put("package_name", package_name);
            values.put("class_name", class_name);
            values.put("app_type", app_type);
            values.put("need_signature", need_signature ? 1 : 0);
            values.put("further_processing", further_processing ? 1 : 0);

            int num = db.update(APPID_TABLE_NAME, values, where, null);
            if (LOCAL_LOGV) Log.v(LOG_TAG, "update:" + x_app_id + ":" + content_type + " "
                    + package_name + "." + class_name
                    + ", sq:" + lastapp.installOrder);

            db.close();

            return num > 0;
        }

        /**
         * Returns true if deleting the package succeeded.
         */
        public boolean deletePackage(String x_app_id, String content_type,
                String package_name, String class_name) {
            WapPushManDBHelper dbh = getDatabase(mContext);
            SQLiteDatabase db = dbh.getWritableDatabase();
            String where = "x_wap_application=\'" + x_app_id + "\'"
                    + " and content_type=\'" + content_type + "\'"
                    + " and package_name=\'" + package_name + "\'"
                    + " and class_name=\'" + class_name + "\'";
            int num_removed = db.delete(APPID_TABLE_NAME, where, null);

            db.close();
            if (LOCAL_LOGV) Log.v(LOG_TAG, "deleted " + num_removed + " rows:"
                    + x_app_id + ":" + content_type + " "
                    + package_name + "." + class_name);
            return num_removed > 0;
        }
    };


    /**
     * Linux IPC Binder
     */
    private final IWapPushManagerStub mBinder = new IWapPushManagerStub();

    /**
     * Default constructor
     */
    public WapPushManager() {
        super();
        mBinder.mContext = this;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    /**
     * Application ID database instance
     */
    private WapPushManDBHelper mDbHelper = null;
    protected WapPushManDBHelper getDatabase(Context context) {
        if (mDbHelper == null) {
            if (LOCAL_LOGV) Log.v(LOG_TAG, "create new db inst.");
            mDbHelper = new WapPushManDBHelper(context);
        }
        return mDbHelper;
    }


    /**
     * This method is used for testing
     */
    public boolean verifyData(String x_app_id, String content_type,
            String package_name, String class_name,
            int app_type, boolean need_signature, boolean further_processing) {
        WapPushManDBHelper dbh = getDatabase(this);
        SQLiteDatabase db = dbh.getReadableDatabase();
        WapPushManDBHelper.queryData lastapp = dbh.queryLastApp(db, x_app_id, content_type);

        if (LOCAL_LOGV) Log.v(LOG_TAG, "verifyData app id: " + x_app_id + " content type: " +
                content_type + " lastapp: " + lastapp);

        db.close();

        if (lastapp == null) return false;

        if (LOCAL_LOGV) Log.v(LOG_TAG, "verifyData lastapp.packageName: " + lastapp.packageName +
                " lastapp.className: " + lastapp.className +
                " lastapp.appType: " + lastapp.appType +
                " lastapp.needSignature: " + lastapp.needSignature +
                " lastapp.furtherProcessing: " + lastapp.furtherProcessing);


        if (lastapp.packageName.equals(package_name)
                && lastapp.className.equals(class_name)
                && lastapp.appType == app_type
                &&  lastapp.needSignature == (need_signature ? 1 : 0)
                &&  lastapp.furtherProcessing == (further_processing ? 1 : 0)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * This method is used for testing
     */
    public boolean isDataExist(String x_app_id, String content_type,
            String package_name, String class_name) {
        WapPushManDBHelper dbh = getDatabase(this);
        SQLiteDatabase db = dbh.getReadableDatabase();
        boolean ret = dbh.queryLastApp(db, x_app_id, content_type) != null;

        db.close();
        return ret;
    }

}

