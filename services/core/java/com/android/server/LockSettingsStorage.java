/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import static android.content.Context.USER_SERVICE;

/**
 * Storage for the lock settings service.
 */
class LockSettingsStorage {

    private static final String TAG = "LockSettingsStorage";
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

    private final DatabaseHelper mOpenHelper;
    private final Context mContext;
    private final Object mFileWriteLock = new Object();

    LockSettingsStorage(Context context, Callback callback) {
        mContext = context;
        mOpenHelper = new DatabaseHelper(context, callback);
    }

    void writeKeyValue(String key, String value, int userId) {
        writeKeyValue(mOpenHelper.getWritableDatabase(), key, value, userId);
    }

    void writeKeyValue(SQLiteDatabase db, String key, String value, int userId) {
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

    String readKeyValue(String key, String defaultValue, int userId) {
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

    byte[] readPasswordHash(int userId) {
        final byte[] stored = readFile(getLockPasswordFilename(userId), userId);
        if (stored != null && stored.length > 0) {
            return stored;
        }
        return null;
    }

    byte[] readPatternHash(int userId) {
        final byte[] stored = readFile(getLockPatternFilename(userId), userId);
        if (stored != null && stored.length > 0) {
            return stored;
        }
        return null;
    }

    boolean hasPassword(int userId) {
        return hasFile(getLockPasswordFilename(userId), userId);
    }

    boolean hasPattern(int userId) {
        return hasFile(getLockPatternFilename(userId), userId);
    }

    private boolean hasFile(String name, int userId) {
        byte[] contents = readFile(name, userId);
        return contents != null && contents.length > 0;
    }

    private byte[] readFile(String name, int userId) {
        RandomAccessFile raf = null;
        byte[] stored = null;
        try {
            raf = new RandomAccessFile(name, "r");
            stored = new byte[(int) raf.length()];
            raf.readFully(stored, 0, stored.length);
            raf.close();
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read file " + e);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    Slog.e(TAG, "Error closing file " + e);
                }
            }
        }
        return stored;
    }

    private void writeFile(String name, byte[] hash, int userId) {
        synchronized (mFileWriteLock) {
            RandomAccessFile raf = null;
            try {
                // Write the hash to file
                raf = new RandomAccessFile(name, "rw");
                // Truncate the file if pattern is null, to clear the lock
                if (hash == null || hash.length == 0) {
                    raf.setLength(0);
                } else {
                    raf.write(hash, 0, hash.length);
                }
                raf.close();
            } catch (IOException e) {
                Slog.e(TAG, "Error writing to file " + e);
            } finally {
                if (raf != null) {
                    try {
                        raf.close();
                    } catch (IOException e) {
                        Slog.e(TAG, "Error closing file " + e);
                    }
                }
            }
        }
    }

    public void writePatternHash(byte[] hash, int userId) {
        writeFile(getLockPatternFilename(userId), hash, userId);
    }

    public void writePasswordHash(byte[] hash, int userId) {
        writeFile(getLockPasswordFilename(userId), hash, userId);
    }


    private String getLockPatternFilename(int userId) {
        String dataSystemDirectory =
                android.os.Environment.getDataDirectory().getAbsolutePath() +
                        SYSTEM_DIRECTORY;
        userId = getUserParentOrSelfId(userId);
        if (userId == 0) {
            // Leave it in the same place for user 0
            return dataSystemDirectory + LOCK_PATTERN_FILE;
        } else {
            return new File(Environment.getUserSystemDirectory(userId), LOCK_PATTERN_FILE)
                    .getAbsolutePath();
        }
    }

    private String getLockPasswordFilename(int userId) {
        userId = getUserParentOrSelfId(userId);
        String dataSystemDirectory =
                android.os.Environment.getDataDirectory().getAbsolutePath() +
                        SYSTEM_DIRECTORY;
        if (userId == 0) {
            // Leave it in the same place for user 0
            return dataSystemDirectory + LOCK_PASSWORD_FILE;
        } else {
            return new File(Environment.getUserSystemDirectory(userId), LOCK_PASSWORD_FILE)
                    .getAbsolutePath();
        }
    }

    private int getUserParentOrSelfId(int userId) {
        if (userId != 0) {
            final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
            final UserInfo pi = um.getProfileParent(userId);
            if (pi != null) {
                return pi.id;
            }
        }
        return userId;
    }


    public void removeUser(int userId) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
        final UserInfo parentInfo = um.getProfileParent(userId);

        synchronized (mFileWriteLock) {
            if (parentInfo == null) {
                // This user owns its lock settings files - safe to delete them
                File file = new File(getLockPasswordFilename(userId));
                if (file.exists()) {
                    file.delete();
                }
                file = new File(getLockPatternFilename(userId));
                if (file.exists()) {
                    file.delete();
                }
            }
        }

        try {
            db.beginTransaction();
            db.delete(TABLE, COLUMN_USERID + "='" + userId + "'", null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }


    interface Callback {
        void initialize(SQLiteDatabase db);
    }

    class DatabaseHelper extends SQLiteOpenHelper {
        private static final String TAG = "LockSettingsDB";
        private static final String DATABASE_NAME = "locksettings.db";

        private static final int DATABASE_VERSION = 2;

        private final Callback mCallback;

        public DatabaseHelper(Context context, Callback callback) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            setWriteAheadLoggingEnabled(true);
            mCallback = callback;
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
            mCallback.initialize(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            int upgradeVersion = oldVersion;
            if (upgradeVersion == 1) {
                // Previously migrated lock screen widget settings. Now defunct.
                upgradeVersion = 2;
            }

            if (upgradeVersion != DATABASE_VERSION) {
                Log.w(TAG, "Failed to upgrade database!");
            }
        }
    }
}
