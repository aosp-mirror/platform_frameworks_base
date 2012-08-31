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

package com.android.internal.widget;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Binder;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.Slog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Keeps the lock pattern/password data and related settings for each user.
 * Used by LockPatternUtils. Needs to be a service because Settings app also needs
 * to be able to save lockscreen information for secondary users.
 * @hide
 */
public class LockSettingsService extends ILockSettings.Stub {

    private final DatabaseHelper mOpenHelper;
    private static final String TAG = "LockSettingsService";

    private static final String TABLE = "locksettings";
    private static final String COLUMN_KEY = "name";
    private static final String COLUMN_USERID = "user";
    private static final String COLUMN_VALUE = "value";

    private static final String[] COLUMNS_FOR_QUERY = {
        COLUMN_VALUE
    };

    private static final String SYSTEM_DIRECTORY = "/system/";
    private static final String LOCK_PATTERN_FILE = "gesture.key";
    private static final String LOCK_PASSWORD_FILE = "password.key";

    private final Context mContext;

    public LockSettingsService(Context context) {
        mContext = context;
        // Open the database
        mOpenHelper = new DatabaseHelper(mContext);
    }

    public void systemReady() {
        migrateOldData();
    }

    private void migrateOldData() {
        try {
            if (getString("migrated", null, 0) != null) {
                // Already migrated
                return;
            }

            final ContentResolver cr = mContext.getContentResolver();
            for (String validSetting : VALID_SETTINGS) {
                String value = Settings.Secure.getString(cr, validSetting);
                if (value != null) {
                    setString(validSetting, value, 0);
                }
            }
            // No need to move the password / pattern files. They're already in the right place.
            setString("migrated", "true", 0);
            Slog.i(TAG, "Migrated lock settings to new location");
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to migrate old data");
        }
    }

    private static final void checkWritePermission(int userId) {
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getAppId(callingUid) != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("uid=" + callingUid
                    + " not authorized to write lock settings");
        }
    }

    private static final void checkPasswordReadPermission(int userId) {
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getAppId(callingUid) != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("uid=" + callingUid
                    + " not authorized to read lock password");
        }
    }

    private static final void checkReadPermission(int userId) {
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getAppId(callingUid) != android.os.Process.SYSTEM_UID
                && UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("uid=" + callingUid
                    + " not authorized to read settings of user " + userId);
        }
    }

    @Override
    public void setBoolean(String key, boolean value, int userId) throws RemoteException {
        checkWritePermission(userId);

        writeToDb(key, value ? "1" : "0", userId);
    }

    @Override
    public void setLong(String key, long value, int userId) throws RemoteException {
        checkWritePermission(userId);

        writeToDb(key, Long.toString(value), userId);
    }

    @Override
    public void setString(String key, String value, int userId) throws RemoteException {
        checkWritePermission(userId);

        writeToDb(key, value, userId);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue, int userId) throws RemoteException {
        //checkReadPermission(userId);

        String value = readFromDb(key, null, userId);
        return TextUtils.isEmpty(value) ?
                defaultValue : (value.equals("1") || value.equals("true"));
    }

    @Override
    public long getLong(String key, long defaultValue, int userId) throws RemoteException {
        //checkReadPermission(userId);

        String value = readFromDb(key, null, userId);
        return TextUtils.isEmpty(value) ? defaultValue : Long.parseLong(value);
    }

    @Override
    public String getString(String key, String defaultValue, int userId) throws RemoteException {
        //checkReadPermission(userId);

        return readFromDb(key, defaultValue, userId);
    }

    private String getLockPatternFilename(int userId) {
        String dataSystemDirectory =
                android.os.Environment.getDataDirectory().getAbsolutePath() +
                SYSTEM_DIRECTORY;
        if (userId == 0) {
            // Leave it in the same place for user 0
            return dataSystemDirectory + LOCK_PATTERN_FILE;
        } else {
            return  new File(Environment.getUserSystemDirectory(userId), LOCK_PATTERN_FILE)
                    .getAbsolutePath();
        }
    }

    private String getLockPasswordFilename(int userId) {
        String dataSystemDirectory =
                android.os.Environment.getDataDirectory().getAbsolutePath() +
                SYSTEM_DIRECTORY;
        if (userId == 0) {
            // Leave it in the same place for user 0
            return dataSystemDirectory + LOCK_PASSWORD_FILE;
        } else {
            return  new File(Environment.getUserSystemDirectory(userId), LOCK_PASSWORD_FILE)
                    .getAbsolutePath();
        }
    }

    @Override
    public boolean havePassword(int userId) throws RemoteException {
        // Do we need a permissions check here?

        return new File(getLockPasswordFilename(userId)).length() > 0;
    }

    @Override
    public boolean havePattern(int userId) throws RemoteException {
        // Do we need a permissions check here?

        return new File(getLockPatternFilename(userId)).length() > 0;
    }

    @Override
    public void setLockPattern(byte[] hash, int userId) throws RemoteException {
        checkWritePermission(userId);

        writeFile(getLockPatternFilename(userId), hash);
    }

    @Override
    public boolean checkPattern(byte[] hash, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        try {
            // Read all the bytes from the file
            RandomAccessFile raf = new RandomAccessFile(getLockPatternFilename(userId), "r");
            final byte[] stored = new byte[(int) raf.length()];
            int got = raf.read(stored, 0, stored.length);
            raf.close();
            if (got <= 0) {
                return true;
            }
            // Compare the hash from the file with the entered pattern's hash
            return Arrays.equals(stored, hash);
        } catch (FileNotFoundException fnfe) {
            Slog.e(TAG, "Cannot read file " + fnfe);
            return true;
        } catch (IOException ioe) {
            Slog.e(TAG, "Cannot read file " + ioe);
            return true;
        }
    }

    @Override
    public void setLockPassword(byte[] hash, int userId) throws RemoteException {
        checkWritePermission(userId);

        writeFile(getLockPasswordFilename(userId), hash);
    }

    @Override
    public boolean checkPassword(byte[] hash, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);

        try {
            // Read all the bytes from the file
            RandomAccessFile raf = new RandomAccessFile(getLockPasswordFilename(userId), "r");
            final byte[] stored = new byte[(int) raf.length()];
            int got = raf.read(stored, 0, stored.length);
            raf.close();
            if (got <= 0) {
                return true;
            }
            // Compare the hash from the file with the entered password's hash
            return Arrays.equals(stored, hash);
        } catch (FileNotFoundException fnfe) {
            Slog.e(TAG, "Cannot read file " + fnfe);
            return true;
        } catch (IOException ioe) {
            Slog.e(TAG, "Cannot read file " + ioe);
            return true;
        }
    }

    @Override
    public void removeUser(int userId) {
        checkWritePermission(userId);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try {
            File file = new File(getLockPasswordFilename(userId));
            if (file.exists()) {
                file.delete();
            }
            file = new File(getLockPatternFilename(userId));
            if (file.exists()) {
                file.delete();
            }

            db.beginTransaction();
            db.delete(TABLE, COLUMN_USERID + "='" + userId + "'", null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void writeFile(String name, byte[] hash) {
        try {
            // Write the hash to file
            RandomAccessFile raf = new RandomAccessFile(name, "rw");
            // Truncate the file if pattern is null, to clear the lock
            if (hash == null || hash.length == 0) {
                raf.setLength(0);
            } else {
                raf.write(hash, 0, hash.length);
            }
            raf.close();
        } catch (IOException ioe) {
            Slog.e(TAG, "Error writing to file " + ioe);
        }
    }

    private void writeToDb(String key, String value, int userId) {
        writeToDb(mOpenHelper.getWritableDatabase(), key, value, userId);
    }

    private void writeToDb(SQLiteDatabase db, String key, String value, int userId) {
        ContentValues cv = new ContentValues();
        cv.put(COLUMN_KEY, key);
        cv.put(COLUMN_USERID, userId);
        cv.put(COLUMN_VALUE, value);

        db.beginTransaction();
        try {
            db.delete(TABLE, COLUMN_KEY + "=? AND " + COLUMN_USERID + "=?",
                    new String[] {key, Integer.toString(userId)});
            db.insert(TABLE, null, cv);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private String readFromDb(String key, String defaultValue, int userId) {
        Cursor cursor;
        String result = defaultValue;
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        if ((cursor = db.query(TABLE, COLUMNS_FOR_QUERY,
                COLUMN_USERID + "=? AND " + COLUMN_KEY + "=?",
                new String[] { Integer.toString(userId), key },
                null, null, null)) != null) {
            if (cursor.moveToFirst()) {
                result = cursor.getString(0);
            }
            cursor.close();
        }
        return result;
    }

    class DatabaseHelper extends SQLiteOpenHelper {
        private static final String TAG = "LockSettingsDB";
        private static final String DATABASE_NAME = "locksettings.db";

        private static final int DATABASE_VERSION = 1;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            setWriteAheadLoggingEnabled(true);
        }

        private void createTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COLUMN_KEY + " TEXT," +
                    COLUMN_USERID + " INTEGER," +
                    COLUMN_VALUE + " TEXT" +
                    ");");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createTable(db);
            initializeDefaults(db);
        }

        private void initializeDefaults(SQLiteDatabase db) {
            // Get the lockscreen default from a system property, if available
            boolean lockScreenDisable = SystemProperties.getBoolean("ro.lockscreen.disable.default",
                    false);
            if (lockScreenDisable) {
                writeToDb(db, LockPatternUtils.DISABLE_LOCKSCREEN_KEY, "1", 0);
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            // Nothing yet
        }
    }

    private static final String[] VALID_SETTINGS = new String[] {
        LockPatternUtils.LOCKOUT_PERMANENT_KEY,
        LockPatternUtils.LOCKOUT_ATTEMPT_DEADLINE,
        LockPatternUtils.PATTERN_EVER_CHOSEN_KEY,
        LockPatternUtils.PASSWORD_TYPE_KEY,
        LockPatternUtils.PASSWORD_TYPE_ALTERNATE_KEY,
        LockPatternUtils.LOCK_PASSWORD_SALT_KEY,
        LockPatternUtils.DISABLE_LOCKSCREEN_KEY,
        LockPatternUtils.LOCKSCREEN_OPTIONS,
        LockPatternUtils.LOCKSCREEN_BIOMETRIC_WEAK_FALLBACK,
        LockPatternUtils.BIOMETRIC_WEAK_EVER_CHOSEN_KEY,
        LockPatternUtils.LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS,
        LockPatternUtils.PASSWORD_HISTORY_KEY,
        Secure.LOCK_PATTERN_ENABLED,
        Secure.LOCK_BIOMETRIC_WEAK_FLAGS,
        Secure.LOCK_PATTERN_VISIBLE,
        Secure.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED
        };
}
