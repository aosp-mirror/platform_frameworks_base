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

package com.android.server.locksettings;

import static android.content.Context.USER_SERVICE;
import static android.text.TextUtils.formatSimple;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.internal.widget.LockPatternUtils.USER_FRP;

import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.app.backup.BackupManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.PersistentDataBlockManagerInternal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    private static final String[] COLUMNS_FOR_PREFETCH = {
            COLUMN_KEY, COLUMN_VALUE
    };

    private static final String CHILD_PROFILE_LOCK_FILE = "gatekeeper.profile.key";

    private static final String REBOOT_ESCROW_FILE = "reboot.escrow.key";
    private static final String REBOOT_ESCROW_SERVER_BLOB_FILE = "reboot.escrow.server.blob.key";

    private static final String SYNTHETIC_PASSWORD_DIRECTORY = "spblob/";

    private static final Object DEFAULT = new Object();

    private static final String[] SETTINGS_TO_BACKUP = new String[] {
            Settings.Secure.LOCK_SCREEN_OWNER_INFO_ENABLED,
            Settings.Secure.LOCK_SCREEN_OWNER_INFO,
            Settings.Secure.LOCK_PATTERN_VISIBLE,
            LockPatternUtils.LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS
    };

    private final DatabaseHelper mOpenHelper;
    private final Context mContext;
    private final Cache mCache = new Cache();
    private final Object mFileWriteLock = new Object();

    private PersistentDataBlockManagerInternal mPersistentDataBlockManagerInternal;

    public LockSettingsStorage(Context context) {
        mContext = context;
        mOpenHelper = new DatabaseHelper(context);
    }

    public void setDatabaseOnCreateCallback(Callback callback) {
        mOpenHelper.setCallback(callback);
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void writeKeyValue(String key, String value, int userId) {
        writeKeyValue(mOpenHelper.getWritableDatabase(), key, value, userId);
    }

    @VisibleForTesting
    public void writeKeyValue(SQLiteDatabase db, String key, String value, int userId) {
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
            mCache.putKeyValue(key, value, userId);
        } finally {
            db.endTransaction();
        }
    }

    @VisibleForTesting
    public String readKeyValue(String key, String defaultValue, int userId) {
        int version;
        synchronized (mCache) {
            if (mCache.hasKeyValue(key, userId)) {
                return mCache.peekKeyValue(key, defaultValue, userId);
            }
            version = mCache.getVersion();
        }

        Cursor cursor;
        Object result = DEFAULT;
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
        mCache.putKeyValueIfUnchanged(key, result, userId, version);
        return result == DEFAULT ? defaultValue : (String) result;
    }

    @VisibleForTesting
    boolean isKeyValueCached(String key, int userId) {
        return mCache.hasKeyValue(key, userId);
    }

    @VisibleForTesting
    boolean isUserPrefetched(int userId) {
        return mCache.isFetched(userId);
    }

    @VisibleForTesting
    public void removeKey(String key, int userId) {
        removeKey(mOpenHelper.getWritableDatabase(), key, userId);
    }

    private void removeKey(SQLiteDatabase db, String key, int userId) {
        ContentValues cv = new ContentValues();
        cv.put(COLUMN_KEY, key);
        cv.put(COLUMN_USERID, userId);

        db.beginTransaction();
        try {
            db.delete(TABLE, COLUMN_KEY + "=? AND " + COLUMN_USERID + "=?",
                    new String[] {key, Integer.toString(userId)});
            db.setTransactionSuccessful();
            mCache.removeKey(key, userId);
        } finally {
            db.endTransaction();
        }
    }

    public void prefetchUser(int userId) {
        int version;
        synchronized (mCache) {
            if (mCache.isFetched(userId)) {
                return;
            }
            mCache.setFetched(userId);
            version = mCache.getVersion();
        }

        Cursor cursor;
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        if ((cursor = db.query(TABLE, COLUMNS_FOR_PREFETCH,
                COLUMN_USERID + "=?",
                new String[] { Integer.toString(userId) },
                null, null, null)) != null) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                String value = cursor.getString(1);
                mCache.putKeyValueIfUnchanged(key, value, userId, version);
            }
            cursor.close();
        }
    }

    public void removeChildProfileLock(int userId) {
        deleteFile(getChildProfileLockFile(userId));
    }

    public void writeChildProfileLock(int userId, byte[] lock) {
        writeFile(getChildProfileLockFile(userId), lock);
    }

    public byte[] readChildProfileLock(int userId) {
        return readFile(getChildProfileLockFile(userId));
    }

    public boolean hasChildProfileLock(int userId) {
        return hasFile(getChildProfileLockFile(userId));
    }

    public void writeRebootEscrow(int userId, byte[] rebootEscrow) {
        writeFile(getRebootEscrowFile(userId), rebootEscrow);
    }

    public byte[] readRebootEscrow(int userId) {
        return readFile(getRebootEscrowFile(userId));
    }

    public boolean hasRebootEscrow(int userId) {
        return hasFile(getRebootEscrowFile(userId));
    }

    public void removeRebootEscrow(int userId) {
        deleteFile(getRebootEscrowFile(userId));
    }

    public void writeRebootEscrowServerBlob(byte[] serverBlob) {
        writeFile(getRebootEscrowServerBlobFile(), serverBlob);
    }

    public byte[] readRebootEscrowServerBlob() {
        return readFile(getRebootEscrowServerBlobFile());
    }

    public boolean hasRebootEscrowServerBlob() {
        return hasFile(getRebootEscrowServerBlobFile());
    }

    public void removeRebootEscrowServerBlob() {
        deleteFile(getRebootEscrowServerBlobFile());
    }

    private boolean hasFile(File path) {
        byte[] contents = readFile(path);
        return contents != null && contents.length > 0;
    }

    private byte[] readFile(File path) {
        int version;
        synchronized (mCache) {
            if (mCache.hasFile(path)) {
                return mCache.peekFile(path);
            }
            version = mCache.getVersion();
        }

        byte[] data = null;
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            data = new byte[(int) raf.length()];
            raf.readFully(data, 0, data.length);
            raf.close();
        } catch (FileNotFoundException suppressed) {
            // readFile() is also called by hasFile() to check the existence of files, in this
            // case FileNotFoundException is expected.
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read file " + e);
        }
        mCache.putFileIfUnchanged(path, data, version);
        return data;
    }

    private void fsyncDirectory(File directory) {
        try {
            try (FileChannel file = FileChannel.open(directory.toPath(),
                    StandardOpenOption.READ)) {
                file.force(true);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Error syncing directory: " + directory, e);
        }
    }

    private void writeFile(File path, byte[] data) {
        synchronized (mFileWriteLock) {
            RandomAccessFile raf = null;
            try {
                // Write the data to the file, requiring each write to be synchronized to the
                // underlying storage device immediately to avoid data loss in case of power loss.
                raf = new RandomAccessFile(path, "rws");
                // Truncate the file if the data is empty.
                if (data == null || data.length == 0) {
                    raf.setLength(0);
                } else {
                    raf.write(data, 0, data.length);
                }
                raf.close();
                fsyncDirectory(path.getParentFile());
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
            mCache.putFile(path, data);
        }
    }

    private void deleteFile(File path) {
        synchronized (mFileWriteLock) {
            if (path.exists()) {
                // Zeroize the file to try to make its contents unrecoverable.  This is *not*
                // guaranteed to be effective, and in fact it usually isn't, but it doesn't hurt.
                try (RandomAccessFile raf = new RandomAccessFile(path, "rws")) {
                    final int fileSize = (int) raf.length();
                    raf.write(new byte[fileSize]);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to zeroize " + path, e);
                }
                path.delete();
                mCache.putFile(path, null);
            }
        }
    }

    @VisibleForTesting
    File getChildProfileLockFile(int userId) {
        return getLockCredentialFileForUser(userId, CHILD_PROFILE_LOCK_FILE);
    }

    @VisibleForTesting
    File getRebootEscrowFile(int userId) {
        return getLockCredentialFileForUser(userId, REBOOT_ESCROW_FILE);
    }

    @VisibleForTesting
    File getRebootEscrowServerBlobFile() {
        // There is a single copy of server blob for all users.
        return getLockCredentialFileForUser(UserHandle.USER_SYSTEM, REBOOT_ESCROW_SERVER_BLOB_FILE);
    }

    private File getLockCredentialFileForUser(int userId, String fileName) {
        if (userId == 0) {
            // The files for user 0 are stored directly in /data/system, since this is where they
            // originally were, and they haven't been moved yet.
            return new File(Environment.getDataSystemDirectory(), fileName);
        } else {
            return new File(Environment.getUserSystemDirectory(userId), fileName);
        }
    }

    public void writeSyntheticPasswordState(int userId, long handle, String name, byte[] data) {
        ensureSyntheticPasswordDirectoryForUser(userId);
        writeFile(getSyntheticPasswordStateFileForUser(userId, handle, name), data);
    }

    public byte[] readSyntheticPasswordState(int userId, long handle, String name) {
        return readFile(getSyntheticPasswordStateFileForUser(userId, handle, name));
    }

    public void deleteSyntheticPasswordState(int userId, long handle, String name) {
        deleteFile(getSyntheticPasswordStateFileForUser(userId, handle, name));
    }

    public Map<Integer, List<Long>> listSyntheticPasswordHandlesForAllUsers(String stateName) {
        Map<Integer, List<Long>> result = new ArrayMap<>();
        final UserManager um = UserManager.get(mContext);
        for (UserInfo user : um.getUsers()) {
            result.put(user.id, listSyntheticPasswordHandlesForUser(stateName, user.id));
        }
        return result;
    }

    public List<Long> listSyntheticPasswordHandlesForUser(String stateName, int userId) {
        File baseDir = getSyntheticPasswordDirectoryForUser(userId);
        List<Long> result = new ArrayList<>();
        File[] files = baseDir.listFiles();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            String[] parts = file.getName().split("\\.");
            if (parts.length == 2 && parts[1].equals(stateName)) {
                try {
                    result.add(Long.parseUnsignedLong(parts[0], 16));
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Failed to parse handle " + parts[0]);
                }
            }
        }
        return result;
    }

    @VisibleForTesting
    protected File getSyntheticPasswordDirectoryForUser(int userId) {
        return new File(Environment.getDataSystemDeDirectory(userId), SYNTHETIC_PASSWORD_DIRECTORY);
    }

    /** Ensure per-user directory for synthetic password state exists */
    private void ensureSyntheticPasswordDirectoryForUser(int userId) {
        File baseDir = getSyntheticPasswordDirectoryForUser(userId);
        if (!baseDir.exists()) {
            baseDir.mkdir();
        }
    }

    private File getSyntheticPasswordStateFileForUser(int userId, long handle, String name) {
        String fileName = formatSimple("%016x.%s", handle, name);
        return new File(getSyntheticPasswordDirectoryForUser(userId), fileName);
    }

    public void removeUser(int userId) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
        final UserInfo parentInfo = um.getProfileParent(userId);

        if (parentInfo == null) {
            // This user owns its lock settings files - safe to delete them
            deleteFile(getRebootEscrowFile(userId));
        } else {
            // Managed profile
            removeChildProfileLock(userId);
        }

        File spStateDir = getSyntheticPasswordDirectoryForUser(userId);
        try {
            db.beginTransaction();
            db.delete(TABLE, COLUMN_USERID + "='" + userId + "'", null);
            db.setTransactionSuccessful();
            mCache.removeUser(userId);
            // The directory itself will be deleted as part of user deletion operation by the
            // framework, so only need to purge cache here.
            //TODO: (b/34600579) invoke secdiscardable
            mCache.purgePath(spStateDir);
        } finally {
            db.endTransaction();
        }
    }

    public void setBoolean(String key, boolean value, int userId) {
        setString(key, value ? "1" : "0", userId);
    }

    public void setLong(String key, long value, int userId) {
        setString(key, Long.toString(value), userId);
    }

    public void setInt(String key, int value, int userId) {
        setString(key, Integer.toString(value), userId);
    }

    public void setString(String key, String value, int userId) {
        Preconditions.checkArgument(userId != USER_FRP, "cannot store lock settings for FRP user");

        writeKeyValue(key, value, userId);
        if (ArrayUtils.contains(SETTINGS_TO_BACKUP, key)) {
            BackupManager.dataChanged("com.android.providers.settings");
        }
    }

    public boolean getBoolean(String key, boolean defaultValue, int userId) {
        String value = getString(key, null, userId);
        return TextUtils.isEmpty(value)
                ? defaultValue : (value.equals("1") || value.equals("true"));
    }

    public long getLong(String key, long defaultValue, int userId) {
        String value = getString(key, null, userId);
        return TextUtils.isEmpty(value) ? defaultValue : Long.parseLong(value);
    }

    public int getInt(String key, int defaultValue, int userId) {
        String value = getString(key, null, userId);
        return TextUtils.isEmpty(value) ? defaultValue : Integer.parseInt(value);
    }

    public String getString(String key, String defaultValue, int userId) {
        if (userId == USER_FRP) {
            return null;
        }

        if (LockPatternUtils.LEGACY_LOCK_PATTERN_ENABLED.equals(key)) {
            key = Settings.Secure.LOCK_PATTERN_ENABLED;
        }

        return readKeyValue(key, defaultValue, userId);
    }

    @VisibleForTesting
    void closeDatabase() {
        mOpenHelper.close();
    }

    @VisibleForTesting
    void clearCache() {
        mCache.clear();
    }

    @Nullable @VisibleForTesting
    PersistentDataBlockManagerInternal getPersistentDataBlockManager() {
        if (mPersistentDataBlockManagerInternal == null) {
            mPersistentDataBlockManagerInternal =
                    LocalServices.getService(PersistentDataBlockManagerInternal.class);
        }
        return mPersistentDataBlockManagerInternal;
    }

    public void writePersistentDataBlock(int persistentType, int userId, int qualityForUi,
            byte[] payload) {
        PersistentDataBlockManagerInternal persistentDataBlock = getPersistentDataBlockManager();
        if (persistentDataBlock == null) {
            return;
        }
        persistentDataBlock.setFrpCredentialHandle(PersistentData.toBytes(
                persistentType, userId, qualityForUi, payload));
    }

    public PersistentData readPersistentDataBlock() {
        PersistentDataBlockManagerInternal persistentDataBlock = getPersistentDataBlockManager();
        if (persistentDataBlock == null) {
            return PersistentData.NONE;
        }
        try {
            return PersistentData.fromBytes(persistentDataBlock.getFrpCredentialHandle());
        } catch (IllegalStateException e) {
            Slog.e(TAG, "Error reading persistent data block", e);
            return PersistentData.NONE;
        }
    }

    public static class PersistentData {
        static final byte VERSION_1 = 1;
        static final int VERSION_1_HEADER_SIZE = 1 + 1 + 4 + 4;

        public static final int TYPE_NONE = 0;
        public static final int TYPE_SP = 1;
        public static final int TYPE_SP_WEAVER = 2;

        public static final PersistentData NONE = new PersistentData(TYPE_NONE,
                UserHandle.USER_NULL, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, null);

        final int type;
        final int userId;
        final int qualityForUi;
        final byte[] payload;

        private PersistentData(int type, int userId, int qualityForUi, byte[] payload) {
            this.type = type;
            this.userId = userId;
            this.qualityForUi = qualityForUi;
            this.payload = payload;
        }

        public static PersistentData fromBytes(byte[] frpData) {
            if (frpData == null || frpData.length == 0) {
                return NONE;
            }

            DataInputStream is = new DataInputStream(new ByteArrayInputStream(frpData));
            try {
                byte version = is.readByte();
                if (version == PersistentData.VERSION_1) {
                    int type = is.readByte() & 0xFF;
                    int userId = is.readInt();
                    int qualityForUi = is.readInt();
                    byte[] payload = new byte[frpData.length - VERSION_1_HEADER_SIZE];
                    System.arraycopy(frpData, VERSION_1_HEADER_SIZE, payload, 0, payload.length);
                    return new PersistentData(type, userId, qualityForUi, payload);
                } else {
                    Slog.wtf(TAG, "Unknown PersistentData version code: " + version);
                    return NONE;
                }
            } catch (IOException e) {
                Slog.wtf(TAG, "Could not parse PersistentData", e);
                return NONE;
            }
        }

        public static byte[] toBytes(int persistentType, int userId, int qualityForUi,
                byte[] payload) {
            if (persistentType == PersistentData.TYPE_NONE) {
                Preconditions.checkArgument(payload == null,
                        "TYPE_NONE must have empty payload");
                return null;
            }
            Preconditions.checkArgument(payload != null && payload.length > 0,
                    "empty payload must only be used with TYPE_NONE");

            ByteArrayOutputStream os = new ByteArrayOutputStream(
                    VERSION_1_HEADER_SIZE + payload.length);
            DataOutputStream dos = new DataOutputStream(os);
            try {
                dos.writeByte(PersistentData.VERSION_1);
                dos.writeByte(persistentType);
                dos.writeInt(userId);
                dos.writeInt(qualityForUi);
                dos.write(payload);
            } catch (IOException e) {
                throw new IllegalStateException("ByteArrayOutputStream cannot throw IOException");
            }
            return os.toByteArray();
        }
    }

    public interface Callback {
        void initialize(SQLiteDatabase db);
    }

    public void dump(IndentingPrintWriter pw) {
        final UserManager um = UserManager.get(mContext);
        for (UserInfo user : um.getUsers()) {
            File userPath = getSyntheticPasswordDirectoryForUser(user.id);
            pw.println(String.format("User %d [%s]:", user.id, userPath));
            pw.increaseIndent();
            File[] files = userPath.listFiles();
            if (files != null) {
                Arrays.sort(files);
                for (File file : files) {
                    pw.println(String.format("%6d %s %s", file.length(),
                            LockSettingsService.timestampToString(file.lastModified()),
                            file.getName()));
                }
            } else {
                pw.println("[Not found]");
            }
            pw.decreaseIndent();
        }
    }

    static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String TAG = "LockSettingsDB";
        private static final String DATABASE_NAME = "locksettings.db";

        private static final int DATABASE_VERSION = 2;
        private static final int IDLE_CONNECTION_TIMEOUT_MS = 30000;

        private Callback mCallback;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            setWriteAheadLoggingEnabled(false);
            // Memory optimization - close idle connections after 30s of inactivity
            setIdleConnectionTimeout(IDLE_CONNECTION_TIMEOUT_MS);
        }

        public void setCallback(Callback callback) {
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
            if (mCallback != null) {
                mCallback.initialize(db);
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            int upgradeVersion = oldVersion;
            if (upgradeVersion == 1) {
                // Previously migrated lock screen widget settings. Now defunct.
                upgradeVersion = 2;
            }

            if (upgradeVersion != DATABASE_VERSION) {
                Slog.w(TAG, "Failed to upgrade database!");
            }
        }
    }

    /*
     * A cache for the following types of data:
     *
     *  - Key-value entries from the locksettings database, where the key is the combination of a
     *    userId and a string key, and the value is a string.
     *  - File paths to file contents.
     *  - The per-user "prefetched" flag.
     *
     * Cache consistency model:
     *  - Writes to storage write directly to the cache, but this MUST happen within an atomic
     *    section either provided by the database transaction or mFileWriteLock, such that writes to
     *    the cache and writes to the backing storage are guaranteed to occur in the same order.
     *  - Reads can populate the cache, but because there are no strong ordering guarantees with
     *    respect to writes the following precaution is taken: The cache is assigned a version
     *    number that increases every time the backing storage is modified. Reads from backing
     *    storage can only populate the cache if the backing storage has not changed since the load
     *    operation has begun. This guarantees that a read operation can't clobber a different value
     *    that was written to the cache by a concurrent write operation.
     */
    private static class Cache {
        private final ArrayMap<CacheKey, Object> mCache = new ArrayMap<>();
        private final CacheKey mCacheKey = new CacheKey();
        private int mVersion = 0;

        String peekKeyValue(String key, String defaultValue, int userId) {
            Object cached = peek(CacheKey.TYPE_KEY_VALUE, key, userId);
            return cached == DEFAULT ? defaultValue : (String) cached;
        }

        boolean hasKeyValue(String key, int userId) {
            return contains(CacheKey.TYPE_KEY_VALUE, key, userId);
        }

        void putKeyValue(String key, String value, int userId) {
            put(CacheKey.TYPE_KEY_VALUE, key, value, userId);
        }

        void putKeyValueIfUnchanged(String key, Object value, int userId, int version) {
            putIfUnchanged(CacheKey.TYPE_KEY_VALUE, key, value, userId, version);
        }

        void removeKey(String key, int userId) {
            remove(CacheKey.TYPE_KEY_VALUE, key, userId);
        }

        byte[] peekFile(File path) {
            return copyOf((byte[]) peek(CacheKey.TYPE_FILE, path.toString(), -1 /* userId */));
        }

        boolean hasFile(File path) {
            return contains(CacheKey.TYPE_FILE, path.toString(), -1 /* userId */);
        }

        void putFile(File path, byte[] data) {
            put(CacheKey.TYPE_FILE, path.toString(), copyOf(data), -1 /* userId */);
        }

        void putFileIfUnchanged(File path, byte[] data, int version) {
            putIfUnchanged(CacheKey.TYPE_FILE, path.toString(), copyOf(data), -1 /* userId */,
                    version);
        }

        void setFetched(int userId) {
            put(CacheKey.TYPE_FETCHED, "", "true", userId);
        }

        boolean isFetched(int userId) {
            return contains(CacheKey.TYPE_FETCHED, "", userId);
        }

        private synchronized void remove(int type, String key, int userId) {
            mCache.remove(mCacheKey.set(type, key, userId));
            mVersion++;
        }

        private synchronized void put(int type, String key, Object value, int userId) {
            // Create a new CacheKey here because it may be saved in the map if the key is absent.
            mCache.put(new CacheKey().set(type, key, userId), value);
            mVersion++;
        }

        private synchronized void putIfUnchanged(int type, String key, Object value, int userId,
                int version) {
            if (!contains(type, key, userId) && mVersion == version) {
                mCache.put(new CacheKey().set(type, key, userId), value);
                // Don't increment mVersion, as this method should only be called in cases where the
                // backing storage isn't being modified.
            }
        }

        private synchronized boolean contains(int type, String key, int userId) {
            return mCache.containsKey(mCacheKey.set(type, key, userId));
        }

        private synchronized Object peek(int type, String key, int userId) {
            return mCache.get(mCacheKey.set(type, key, userId));
        }

        private synchronized int getVersion() {
            return mVersion;
        }

        synchronized void removeUser(int userId) {
            for (int i = mCache.size() - 1; i >= 0; i--) {
                if (mCache.keyAt(i).userId == userId) {
                    mCache.removeAt(i);
                }
            }

            // Make sure in-flight loads can't write to cache.
            mVersion++;
        }

        private byte[] copyOf(byte[] data) {
            return data != null ? Arrays.copyOf(data, data.length) : null;
        }

        synchronized void purgePath(File path) {
            final String pathStr = path.toString();
            for (int i = mCache.size() - 1; i >= 0; i--) {
                CacheKey entry = mCache.keyAt(i);
                if (entry.type == CacheKey.TYPE_FILE && entry.key.startsWith(pathStr)) {
                    mCache.removeAt(i);
                }
            }
            mVersion++;
        }

        synchronized void clear() {
            mCache.clear();
            mVersion++;
        }

        private static final class CacheKey {
            static final int TYPE_KEY_VALUE = 0;
            static final int TYPE_FILE = 1;
            static final int TYPE_FETCHED = 2;

            String key;
            int userId;
            int type;

            public CacheKey set(int type, String key, int userId) {
                this.type = type;
                this.key = key;
                this.userId = userId;
                return this;
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof CacheKey))
                    return false;
                CacheKey o = (CacheKey) obj;
                return userId == o.userId && type == o.type && key.equals(o.key);
            }

            @Override
            public int hashCode() {
                return key.hashCode() ^ userId ^ type;
            }
        }
    }

}
