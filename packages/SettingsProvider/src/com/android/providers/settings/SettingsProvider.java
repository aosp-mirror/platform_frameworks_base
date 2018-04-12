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

import static android.os.Process.ROOT_UID;
import static android.os.Process.SHELL_UID;
import static android.os.Process.SYSTEM_UID;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.backup.BackupManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.hardware.camera2.utils.ArrayUtils;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.provider.SettingsValidators;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.ByteStringUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.providers.settings.SettingsState.Setting;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;


/**
 * <p>
 * This class is a content provider that publishes the system settings.
 * It can be accessed via the content provider APIs or via custom call
 * commands. The latter is a bit faster and is the preferred way to access
 * the platform settings.
 * </p>
 * <p>
 * There are three settings types, global (with signature level protection
 * and shared across users), secure (with signature permission level
 * protection and per user), and system (with dangerous permission level
 * protection and per user). Global settings are stored under the device owner.
 * Each of these settings is represented by a {@link
 * com.android.providers.settings.SettingsState} object mapped to an integer
 * key derived from the setting type in the most significant bits and user
 * id in the least significant bits. Settings are synchronously loaded on
 * instantiation of a SettingsState and asynchronously persisted on mutation.
 * Settings are stored in the user specific system directory.
 * </p>
 * <p>
 * Apps targeting APIs Lollipop MR1 and lower can add custom settings entries
 * and get a warning. Targeting higher API version prohibits this as the
 * system settings are not a place for apps to save their state. When a package
 * is removed the settings it added are deleted. Apps cannot delete system
 * settings added by the platform. System settings values are validated to
 * ensure the clients do not put bad values. Global and secure settings are
 * changed only by trusted parties, therefore no validation is performed. Also
 * there is a limit on the amount of app specific settings that can be added
 * to prevent unlimited growth of the system process memory footprint.
 * </p>
 */
@SuppressWarnings("deprecation")
public class SettingsProvider extends ContentProvider {
    static final boolean DEBUG = false;

    private static final boolean DROP_DATABASE_ON_MIGRATION = true;

    private static final String LOG_TAG = "SettingsProvider";

    private static final String TABLE_SYSTEM = "system";
    private static final String TABLE_SECURE = "secure";
    private static final String TABLE_GLOBAL = "global";

    // Old tables no longer exist.
    private static final String TABLE_FAVORITES = "favorites";
    private static final String TABLE_OLD_FAVORITES = "old_favorites";
    private static final String TABLE_BLUETOOTH_DEVICES = "bluetooth_devices";
    private static final String TABLE_BOOKMARKS = "bookmarks";
    private static final String TABLE_ANDROID_METADATA = "android_metadata";

    // The set of removed legacy tables.
    private static final Set<String> REMOVED_LEGACY_TABLES = new ArraySet<>();
    static {
        REMOVED_LEGACY_TABLES.add(TABLE_FAVORITES);
        REMOVED_LEGACY_TABLES.add(TABLE_OLD_FAVORITES);
        REMOVED_LEGACY_TABLES.add(TABLE_BLUETOOTH_DEVICES);
        REMOVED_LEGACY_TABLES.add(TABLE_BOOKMARKS);
        REMOVED_LEGACY_TABLES.add(TABLE_ANDROID_METADATA);
    }

    private static final int MUTATION_OPERATION_INSERT = 1;
    private static final int MUTATION_OPERATION_DELETE = 2;
    private static final int MUTATION_OPERATION_UPDATE = 3;
    private static final int MUTATION_OPERATION_RESET = 4;

    private static final String[] ALL_COLUMNS = new String[] {
            Settings.NameValueTable._ID,
            Settings.NameValueTable.NAME,
            Settings.NameValueTable.VALUE
    };

    public static final int SETTINGS_TYPE_GLOBAL = SettingsState.SETTINGS_TYPE_GLOBAL;
    public static final int SETTINGS_TYPE_SYSTEM = SettingsState.SETTINGS_TYPE_SYSTEM;
    public static final int SETTINGS_TYPE_SECURE = SettingsState.SETTINGS_TYPE_SECURE;
    public static final int SETTINGS_TYPE_SSAID = SettingsState.SETTINGS_TYPE_SSAID;

    private static final Bundle NULL_SETTING_BUNDLE = Bundle.forPair(
            Settings.NameValueTable.VALUE, null);

    // Overlay specified settings whitelisted for Instant Apps
    private static final Set<String> OVERLAY_ALLOWED_GLOBAL_INSTANT_APP_SETTINGS = new ArraySet<>();
    private static final Set<String> OVERLAY_ALLOWED_SYSTEM_INSTANT_APP_SETTINGS = new ArraySet<>();
    private static final Set<String> OVERLAY_ALLOWED_SECURE_INSTANT_APP_SETTINGS = new ArraySet<>();

    static {
        for (String name : Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_allowedGlobalInstantAppSettings)) {
            OVERLAY_ALLOWED_GLOBAL_INSTANT_APP_SETTINGS.add(name);
        }
        for (String name : Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_allowedSystemInstantAppSettings)) {
            OVERLAY_ALLOWED_SYSTEM_INSTANT_APP_SETTINGS.add(name);
        }
        for (String name : Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_allowedSecureInstantAppSettings)) {
            OVERLAY_ALLOWED_SECURE_INSTANT_APP_SETTINGS.add(name);
        }
    }

    // Changes to these global settings are synchronously persisted
    private static final Set<String> CRITICAL_GLOBAL_SETTINGS = new ArraySet<>();
    static {
        CRITICAL_GLOBAL_SETTINGS.add(Settings.Global.DEVICE_PROVISIONED);
    }

    // Changes to these secure settings are synchronously persisted
    private static final Set<String> CRITICAL_SECURE_SETTINGS = new ArraySet<>();
    static {
        CRITICAL_SECURE_SETTINGS.add(Settings.Secure.USER_SETUP_COMPLETE);
    }

    // Per user secure settings that moved to the for all users global settings.
    static final Set<String> sSecureMovedToGlobalSettings = new ArraySet<>();
    static {
        Settings.Secure.getMovedToGlobalSettings(sSecureMovedToGlobalSettings);
    }

    // Per user system settings that moved to the for all users global settings.
    static final Set<String> sSystemMovedToGlobalSettings = new ArraySet<>();
    static {
        Settings.System.getMovedToGlobalSettings(sSystemMovedToGlobalSettings);
    }

    // Per user system settings that moved to the per user secure settings.
    static final Set<String> sSystemMovedToSecureSettings = new ArraySet<>();
    static {
        Settings.System.getMovedToSecureSettings(sSystemMovedToSecureSettings);
    }

    // Per all users global settings that moved to the per user secure settings.
    static final Set<String> sGlobalMovedToSecureSettings = new ArraySet<>();
    static {
        Settings.Global.getMovedToSecureSettings(sGlobalMovedToSecureSettings);
    }

    // Per user secure settings that are cloned for the managed profiles of the user.
    private static final Set<String> sSecureCloneToManagedSettings = new ArraySet<>();
    static {
        Settings.Secure.getCloneToManagedProfileSettings(sSecureCloneToManagedSettings);
    }

    // Per user system settings that are cloned for the managed profiles of the user.
    private static final Set<String> sSystemCloneToManagedSettings = new ArraySet<>();
    static {
        Settings.System.getCloneToManagedProfileSettings(sSystemCloneToManagedSettings);
    }

    // Per user system settings that are cloned from the profile's parent when a dependency
    // in {@link Settings.Secure} is set to "1".
    public static final Map<String, String> sSystemCloneFromParentOnDependency = new ArrayMap<>();
    static {
        Settings.System.getCloneFromParentOnValueSettings(sSystemCloneFromParentOnDependency);
    }

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private SettingsRegistry mSettingsRegistry;

    @GuardedBy("mLock")
    private HandlerThread mHandlerThread;

    @GuardedBy("mLock")
    private Handler mHandler;

    // We have to call in the user manager with no lock held,
    private volatile UserManager mUserManager;

    private UserManagerInternal mUserManagerInternal;

    // We have to call in the package manager with no lock held,
    private volatile IPackageManager mPackageManager;

    public static int makeKey(int type, int userId) {
        return SettingsState.makeKey(type, userId);
    }

    public static int getTypeFromKey(int key) {
        return SettingsState.getTypeFromKey(key);
    }

    public static int getUserIdFromKey(int key) {
        return SettingsState.getUserIdFromKey(key);
    }

    public static String settingTypeToString(int type) {
        return SettingsState.settingTypeToString(type);
    }

    public static String keyToString(int key) {
        return SettingsState.keyToString(key);
    }

    @Override
    public boolean onCreate() {
        Settings.setInSystemServer();

        // fail to boot if there're any backed up settings that don't have a non-null validator
        ensureAllBackedUpSystemSettingsHaveValidators();
        ensureAllBackedUpGlobalSettingsHaveValidators();
        ensureAllBackedUpSecureSettingsHaveValidators();

        synchronized (mLock) {
            mUserManager = UserManager.get(getContext());
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
            mPackageManager = AppGlobals.getPackageManager();
            mHandlerThread = new HandlerThread(LOG_TAG,
                    Process.THREAD_PRIORITY_BACKGROUND);
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
            mSettingsRegistry = new SettingsRegistry();
        }
        mHandler.post(() -> {
            registerBroadcastReceivers();
            startWatchingUserRestrictionChanges();
        });
        ServiceManager.addService("settings", new SettingsService(this));
        return true;
    }

    private void ensureAllBackedUpSystemSettingsHaveValidators() {
        String offenders = getOffenders(concat(Settings.System.SETTINGS_TO_BACKUP,
                Settings.System.LEGACY_RESTORE_SETTINGS), Settings.System.VALIDATORS);

        failToBootIfOffendersPresent(offenders, "Settings.System");
    }

    private void ensureAllBackedUpGlobalSettingsHaveValidators() {
        String offenders = getOffenders(concat(Settings.Global.SETTINGS_TO_BACKUP,
                Settings.Global.LEGACY_RESTORE_SETTINGS), Settings.Global.VALIDATORS);

        failToBootIfOffendersPresent(offenders, "Settings.Global");
    }

    private void ensureAllBackedUpSecureSettingsHaveValidators() {
        String offenders = getOffenders(concat(Settings.Secure.SETTINGS_TO_BACKUP,
                Settings.Secure.LEGACY_RESTORE_SETTINGS), Settings.Secure.VALIDATORS);

        failToBootIfOffendersPresent(offenders, "Settings.Secure");
    }

    private void failToBootIfOffendersPresent(String offenders, String settingsType) {
        if (offenders.length() > 0) {
            throw new RuntimeException("All " + settingsType + " settings that are backed up"
                    + " have to have a non-null validator, but those don't: " + offenders);
        }
    }

    private String getOffenders(String[] settingsToBackup, Map<String,
            SettingsValidators.Validator> validators) {
        StringBuilder offenders = new StringBuilder();
        for (String setting : settingsToBackup) {
            if (validators.get(setting) == null) {
                offenders.append(setting).append(" ");
            }
        }
        return offenders.toString();
    }

    private final String[] concat(String[] first, String[] second) {
        if (second == null || second.length == 0) {
            return first;
        }
        final int firstLen = first.length;
        final int secondLen = second.length;
        String[] both = new String[firstLen + secondLen];
        System.arraycopy(first, 0, both, 0, firstLen);
        System.arraycopy(second, 0, both, firstLen, secondLen);
        return both;
    }

    @Override
    public Bundle call(String method, String name, Bundle args) {
        final int requestingUserId = getRequestingUserId(args);
        switch (method) {
            case Settings.CALL_METHOD_GET_GLOBAL: {
                Setting setting = getGlobalSetting(name);
                return packageValueForCallResult(setting, isTrackingGeneration(args));
            }

            case Settings.CALL_METHOD_GET_SECURE: {
                Setting setting = getSecureSetting(name, requestingUserId,
                        /*enableOverride=*/ true);
                return packageValueForCallResult(setting, isTrackingGeneration(args));
            }

            case Settings.CALL_METHOD_GET_SYSTEM: {
                Setting setting = getSystemSetting(name, requestingUserId);
                return packageValueForCallResult(setting, isTrackingGeneration(args));
            }

            case Settings.CALL_METHOD_PUT_GLOBAL: {
                String value = getSettingValue(args);
                String tag = getSettingTag(args);
                final boolean makeDefault = getSettingMakeDefault(args);
                insertGlobalSetting(name, value, tag, makeDefault, requestingUserId, false);
                break;
            }

            case Settings.CALL_METHOD_PUT_SECURE: {
                String value = getSettingValue(args);
                String tag = getSettingTag(args);
                final boolean makeDefault = getSettingMakeDefault(args);
                insertSecureSetting(name, value, tag, makeDefault, requestingUserId, false);
                break;
            }

            case Settings.CALL_METHOD_PUT_SYSTEM: {
                String value = getSettingValue(args);
                insertSystemSetting(name, value, requestingUserId);
                break;
            }

            case Settings.CALL_METHOD_RESET_GLOBAL: {
                final int mode = getResetModeEnforcingPermission(args);
                String tag = getSettingTag(args);
                resetGlobalSetting(requestingUserId, mode, tag);
                break;
            }

            case Settings.CALL_METHOD_RESET_SECURE: {
                final int mode = getResetModeEnforcingPermission(args);
                String tag = getSettingTag(args);
                resetSecureSetting(requestingUserId, mode, tag);
                break;
            }

            default: {
                Slog.w(LOG_TAG, "call() with invalid method: " + method);
            } break;
        }

        return null;
    }

    @Override
    public String getType(Uri uri) {
        Arguments args = new Arguments(uri, null, null, true);
        if (TextUtils.isEmpty(args.name)) {
            return "vnd.android.cursor.dir/" + args.table;
        } else {
            return "vnd.android.cursor.item/" + args.table;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String where, String[] whereArgs,
            String order) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "query() for user: " + UserHandle.getCallingUserId());
        }

        Arguments args = new Arguments(uri, where, whereArgs, true);
        String[] normalizedProjection = normalizeProjection(projection);

        // If a legacy table that is gone, done.
        if (REMOVED_LEGACY_TABLES.contains(args.table)) {
            return new MatrixCursor(normalizedProjection, 0);
        }

        switch (args.table) {
            case TABLE_GLOBAL: {
                if (args.name != null) {
                    Setting setting = getGlobalSetting(args.name);
                    return packageSettingForQuery(setting, normalizedProjection);
                } else {
                    return getAllGlobalSettings(projection);
                }
            }

            case TABLE_SECURE: {
                final int userId = UserHandle.getCallingUserId();
                if (args.name != null) {
                    Setting setting = getSecureSetting(args.name, userId);
                    return packageSettingForQuery(setting, normalizedProjection);
                } else {
                    return getAllSecureSettings(userId, projection);
                }
            }

            case TABLE_SYSTEM: {
                final int userId = UserHandle.getCallingUserId();
                if (args.name != null) {
                    Setting setting = getSystemSetting(args.name, userId);
                    return packageSettingForQuery(setting, normalizedProjection);
                } else {
                    return getAllSystemSettings(userId, projection);
                }
            }

            default: {
                throw new IllegalArgumentException("Invalid Uri path:" + uri);
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "insert() for user: " + UserHandle.getCallingUserId());
        }

        String table = getValidTableOrThrow(uri);

        // If a legacy table that is gone, done.
        if (REMOVED_LEGACY_TABLES.contains(table)) {
            return null;
        }

        String name = values.getAsString(Settings.Secure.NAME);
        if (!isKeyValid(name)) {
            return null;
        }

        String value = values.getAsString(Settings.Secure.VALUE);

        switch (table) {
            case TABLE_GLOBAL: {
                if (insertGlobalSetting(name, value, null, false,
                        UserHandle.getCallingUserId(), false)) {
                    return Uri.withAppendedPath(Settings.Global.CONTENT_URI, name);
                }
            } break;

            case TABLE_SECURE: {
                if (insertSecureSetting(name, value, null, false,
                        UserHandle.getCallingUserId(), false)) {
                    return Uri.withAppendedPath(Settings.Secure.CONTENT_URI, name);
                }
            } break;

            case TABLE_SYSTEM: {
                if (insertSystemSetting(name, value, UserHandle.getCallingUserId())) {
                    return Uri.withAppendedPath(Settings.System.CONTENT_URI, name);
                }
            } break;

            default: {
                throw new IllegalArgumentException("Bad Uri path:" + uri);
            }
        }

        return null;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] allValues) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "bulkInsert() for user: " + UserHandle.getCallingUserId());
        }

        int insertionCount = 0;
        final int valuesCount = allValues.length;
        for (int i = 0; i < valuesCount; i++) {
            ContentValues values = allValues[i];
            if (insert(uri, values) != null) {
                insertionCount++;
            }
        }

        return insertionCount;
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "delete() for user: " + UserHandle.getCallingUserId());
        }

        Arguments args = new Arguments(uri, where, whereArgs, false);

        // If a legacy table that is gone, done.
        if (REMOVED_LEGACY_TABLES.contains(args.table)) {
            return 0;
        }

        if (!isKeyValid(args.name)) {
            return 0;
        }

        switch (args.table) {
            case TABLE_GLOBAL: {
                final int userId = UserHandle.getCallingUserId();
                return deleteGlobalSetting(args.name, userId, false) ? 1 : 0;
            }

            case TABLE_SECURE: {
                final int userId = UserHandle.getCallingUserId();
                return deleteSecureSetting(args.name, userId, false) ? 1 : 0;
            }

            case TABLE_SYSTEM: {
                final int userId = UserHandle.getCallingUserId();
                return deleteSystemSetting(args.name, userId) ? 1 : 0;
            }

            default: {
                throw new IllegalArgumentException("Bad Uri path:" + uri);
            }
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "update() for user: " + UserHandle.getCallingUserId());
        }

        Arguments args = new Arguments(uri, where, whereArgs, false);

        // If a legacy table that is gone, done.
        if (REMOVED_LEGACY_TABLES.contains(args.table)) {
            return 0;
        }

        String name = values.getAsString(Settings.Secure.NAME);
        if (!isKeyValid(name)) {
            return 0;
        }
        String value = values.getAsString(Settings.Secure.VALUE);

        switch (args.table) {
            case TABLE_GLOBAL: {
                final int userId = UserHandle.getCallingUserId();
                return updateGlobalSetting(args.name, value, null, false,
                        userId, false) ? 1 : 0;
            }

            case TABLE_SECURE: {
                final int userId = UserHandle.getCallingUserId();
                return updateSecureSetting(args.name, value, null, false,
                        userId, false) ? 1 : 0;
            }

            case TABLE_SYSTEM: {
                final int userId = UserHandle.getCallingUserId();
                return updateSystemSetting(args.name, value, userId) ? 1 : 0;
            }

            default: {
                throw new IllegalArgumentException("Invalid Uri path:" + uri);
            }
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        final int userId = getUserIdFromUri(uri, UserHandle.getCallingUserId());
        if (userId != UserHandle.getCallingUserId()) {
            getContext().enforceCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS,
                    "Access files from the settings of another user");
        }
        uri = ContentProvider.getUriWithoutUserId(uri);

        final String cacheRingtoneSetting;
        final String cacheName;
        if (Settings.System.RINGTONE_CACHE_URI.equals(uri)) {
            cacheRingtoneSetting = Settings.System.RINGTONE;
            cacheName = Settings.System.RINGTONE_CACHE;
        } else if (Settings.System.NOTIFICATION_SOUND_CACHE_URI.equals(uri)) {
            cacheRingtoneSetting = Settings.System.NOTIFICATION_SOUND;
            cacheName = Settings.System.NOTIFICATION_SOUND_CACHE;
        } else if (Settings.System.ALARM_ALERT_CACHE_URI.equals(uri)) {
            cacheRingtoneSetting = Settings.System.ALARM_ALERT;
            cacheName = Settings.System.ALARM_ALERT_CACHE;
        } else {
            throw new FileNotFoundException("Direct file access no longer supported; "
                    + "ringtone playback is available through android.media.Ringtone");
        }

        int actualCacheOwner;
        // Redirect cache to parent if ringtone setting is owned by profile parent
        synchronized (mLock) {
            actualCacheOwner = resolveOwningUserIdForSystemSettingLocked(userId,
                    cacheRingtoneSetting);
        }
        final File cacheFile = new File(getRingtoneCacheDir(actualCacheOwner), cacheName);
        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.parseMode(mode));
    }

    private File getRingtoneCacheDir(int userId) {
        final File cacheDir = new File(Environment.getDataSystemDeDirectory(userId), "ringtones");
        cacheDir.mkdir();
        SELinux.restorecon(cacheDir);
        return cacheDir;
    }

    /**
     * Dump all settings as a proto buf.
     *
     * @param fd The file to dump to
     */
    void dumpProto(@NonNull FileDescriptor fd) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            SettingsProtoDumpUtil.dumpProtoLocked(mSettingsRegistry, proto);
        }

        proto.flush();
    }

    public void dumpInternal(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            final long identity = Binder.clearCallingIdentity();
            try {
                SparseBooleanArray users = mSettingsRegistry.getKnownUsersLocked();
                final int userCount = users.size();
                for (int i = 0; i < userCount; i++) {
                    dumpForUserLocked(users.keyAt(i), pw);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private void dumpForUserLocked(int userId, PrintWriter pw) {
        if (userId == UserHandle.USER_SYSTEM) {
            pw.println("GLOBAL SETTINGS (user " + userId + ")");
            SettingsState globalSettings = mSettingsRegistry.getSettingsLocked(
                    SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
            if (globalSettings != null) {
                dumpSettingsLocked(globalSettings, pw);
                pw.println();
                globalSettings.dumpHistoricalOperations(pw);
            }
        }

        pw.println("SECURE SETTINGS (user " + userId + ")");
        SettingsState secureSettings = mSettingsRegistry.getSettingsLocked(
                SETTINGS_TYPE_SECURE, userId);
        if (secureSettings != null) {
            dumpSettingsLocked(secureSettings, pw);
            pw.println();
            secureSettings.dumpHistoricalOperations(pw);
        }

        pw.println("SYSTEM SETTINGS (user " + userId + ")");
        SettingsState systemSettings = mSettingsRegistry.getSettingsLocked(
                SETTINGS_TYPE_SYSTEM, userId);
        if (systemSettings != null) {
            dumpSettingsLocked(systemSettings, pw);
            pw.println();
            systemSettings.dumpHistoricalOperations(pw);
        }
    }

    private void dumpSettingsLocked(SettingsState settingsState, PrintWriter pw) {
        List<String> names = settingsState.getSettingNamesLocked();

        final int nameCount = names.size();

        for (int i = 0; i < nameCount; i++) {
            String name = names.get(i);
            Setting setting = settingsState.getSettingLocked(name);
            pw.print("_id:"); pw.print(toDumpString(setting.getId()));
            pw.print(" name:"); pw.print(toDumpString(name));
            if (setting.getPackageName() != null) {
                pw.print(" pkg:"); pw.print(setting.getPackageName());
            }
            pw.print(" value:"); pw.print(toDumpString(setting.getValue()));
            if (setting.getDefaultValue() != null) {
                pw.print(" default:"); pw.print(setting.getDefaultValue());
                pw.print(" defaultSystemSet:"); pw.print(setting.isDefaultFromSystem());
            }
            if (setting.getTag() != null) {
                pw.print(" tag:"); pw.print(setting.getTag());
            }
            pw.println();
        }
    }

    private static String toDumpString(String s) {
        if (s != null) {
            return s;
        }
        return "{null}";
    }

    private void registerBroadcastReceivers() {
        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        userFilter.addAction(Intent.ACTION_USER_STOPPED);

        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_SYSTEM);

                switch (intent.getAction()) {
                    case Intent.ACTION_USER_REMOVED: {
                        synchronized (mLock) {
                            mSettingsRegistry.removeUserStateLocked(userId, true);
                        }
                    } break;

                    case Intent.ACTION_USER_STOPPED: {
                        synchronized (mLock) {
                            mSettingsRegistry.removeUserStateLocked(userId, false);
                        }
                    } break;
                }
            }
        }, userFilter);

        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    mSettingsRegistry.onPackageRemovedLocked(packageName,
                            UserHandle.getUserId(uid));
                }
            }

            @Override
            public void onUidRemoved(int uid) {
                synchronized (mLock) {
                    mSettingsRegistry.onUidRemovedLocked(uid);
                }
            }
        };

        // package changes
        monitor.register(getContext(), BackgroundThread.getHandler().getLooper(),
                UserHandle.ALL, true);
    }

    private void startWatchingUserRestrictionChanges() {
        // TODO: The current design of settings looking different based on user restrictions
        // should be reworked to keep them separate and system code should check the setting
        // first followed by checking the user restriction before performing an operation.
        UserManagerInternal userManager = LocalServices.getService(UserManagerInternal.class);
        userManager.addUserRestrictionsListener((int userId, Bundle newRestrictions,
                Bundle prevRestrictions) -> {
            // We are changing the settings affected by restrictions to their current
            // value with a forced update to ensure that all cross profile dependencies
            // are taken into account. Also make sure the settings update to.. the same
            // value passes the security checks, so clear binder calling id.
            if (newRestrictions.getBoolean(UserManager.DISALLOW_SHARE_LOCATION)
                    != prevRestrictions.getBoolean(UserManager.DISALLOW_SHARE_LOCATION)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        Setting setting = getSecureSetting(
                                Settings.Secure.LOCATION_PROVIDERS_ALLOWED, userId);
                        updateSecureSetting(Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                                setting != null ? setting.getValue() : null, null,
                                true, userId, true);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            if (newRestrictions.getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                    != prevRestrictions.getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        Setting setting = getGlobalSetting(Settings.Global.INSTALL_NON_MARKET_APPS);
                        String value = setting != null ? setting.getValue() : null;
                        updateGlobalSetting(Settings.Global.INSTALL_NON_MARKET_APPS,
                                value, null, true, userId, true);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            if (newRestrictions.getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES)
                    != prevRestrictions.getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        Setting setting = getGlobalSetting(Settings.Global.ADB_ENABLED);
                        String value = setting != null ? setting.getValue() : null;
                        updateGlobalSetting(Settings.Global.ADB_ENABLED,
                                value, null, true, userId, true);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            if (newRestrictions.getBoolean(UserManager.ENSURE_VERIFY_APPS)
                    != prevRestrictions.getBoolean(UserManager.ENSURE_VERIFY_APPS)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        Setting enable = getGlobalSetting(
                                Settings.Global.PACKAGE_VERIFIER_ENABLE);
                        String enableValue = enable != null ? enable.getValue() : null;
                        updateGlobalSetting(Settings.Global.PACKAGE_VERIFIER_ENABLE,
                                enableValue, null, true, userId, true);
                        Setting include = getGlobalSetting(
                                Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB);
                        String includeValue = include != null ? include.getValue() : null;
                        updateGlobalSetting(Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB,
                                includeValue, null, true, userId, true);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            if (newRestrictions.getBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
                    != prevRestrictions.getBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (mLock) {
                        Setting setting = getGlobalSetting(
                                Settings.Global.PREFERRED_NETWORK_MODE);
                        String value = setting != null ? setting.getValue() : null;
                        updateGlobalSetting(Settings.Global.PREFERRED_NETWORK_MODE,
                                value, null, true, userId, true);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        });
    }

    private Cursor getAllGlobalSettings(String[] projection) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getAllGlobalSettings()");
        }

        synchronized (mLock) {
            // Get the settings.
            SettingsState settingsState = mSettingsRegistry.getSettingsLocked(
                    SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);

            List<String> names = getSettingsNamesLocked(SETTINGS_TYPE_GLOBAL,
                    UserHandle.USER_SYSTEM);

            final int nameCount = names.size();

            String[] normalizedProjection = normalizeProjection(projection);
            MatrixCursor result = new MatrixCursor(normalizedProjection, nameCount);

            // Anyone can get the global settings, so no security checks.
            for (int i = 0; i < nameCount; i++) {
                String name = names.get(i);
                Setting setting = settingsState.getSettingLocked(name);
                appendSettingToCursor(result, setting);
            }

            return result;
        }
    }

    private Setting getGlobalSetting(String name) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getGlobalSetting(" + name + ")");
        }

        // Ensure the caller can access the setting.
        enforceSettingReadable(name, SETTINGS_TYPE_GLOBAL, UserHandle.getCallingUserId());

        // Get the value.
        synchronized (mLock) {
            return mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_GLOBAL,
                    UserHandle.USER_SYSTEM, name);
        }
    }

    private boolean updateGlobalSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, boolean forceNotify) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "updateGlobalSetting(" + name + ", " + value + ", "
                    + ", " + tag + ", " + makeDefault + ", " + requestingUserId
                    + ", " + forceNotify + ")");
        }
        return mutateGlobalSetting(name, value, tag, makeDefault, requestingUserId,
                MUTATION_OPERATION_UPDATE, forceNotify, 0);
    }

    private boolean insertGlobalSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, boolean forceNotify) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "insertGlobalSetting(" + name + ", " + value  + ", "
                    + ", " + tag + ", " + makeDefault + ", " + requestingUserId
                    + ", " + forceNotify + ")");
        }
        return mutateGlobalSetting(name, value, tag, makeDefault, requestingUserId,
                MUTATION_OPERATION_INSERT, forceNotify, 0);
    }

    private boolean deleteGlobalSetting(String name, int requestingUserId, boolean forceNotify) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "deleteGlobalSetting(" + name + ", " + requestingUserId
                    + ", " + forceNotify + ")");
        }
        return mutateGlobalSetting(name, null, null, false, requestingUserId,
                MUTATION_OPERATION_DELETE, forceNotify, 0);
    }

    private void resetGlobalSetting(int requestingUserId, int mode, String tag) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "resetGlobalSetting(" + requestingUserId + ", "
                    + mode + ", " + tag + ")");
        }
        mutateGlobalSetting(null, null, tag, false, requestingUserId,
                MUTATION_OPERATION_RESET, false, mode);
    }

    private boolean mutateGlobalSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, int operation, boolean forceNotify,
            int mode) {
        // Make sure the caller can change the settings - treated as secure.
        enforceWritePermission(Manifest.permission.WRITE_SECURE_SETTINGS);

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissionsLocked(requestingUserId);

        // If this is a setting that is currently restricted for this user, do not allow
        // unrestricting changes.
        if (name != null && mUserManagerInternal.isSettingRestrictedForUser(
                name, callingUserId, value, Binder.getCallingUid())) {
            return false;
        }

        // Perform the mutation.
        synchronized (mLock) {
            switch (operation) {
                case MUTATION_OPERATION_INSERT: {
                    return mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_GLOBAL,
                            UserHandle.USER_SYSTEM, name, value, tag, makeDefault,
                            getCallingPackage(), forceNotify, CRITICAL_GLOBAL_SETTINGS);
                }

                case MUTATION_OPERATION_DELETE: {
                    return mSettingsRegistry.deleteSettingLocked(SETTINGS_TYPE_GLOBAL,
                            UserHandle.USER_SYSTEM, name, forceNotify, CRITICAL_GLOBAL_SETTINGS);
                }

                case MUTATION_OPERATION_UPDATE: {
                    return mSettingsRegistry.updateSettingLocked(SETTINGS_TYPE_GLOBAL,
                            UserHandle.USER_SYSTEM, name, value, tag, makeDefault,
                            getCallingPackage(), forceNotify, CRITICAL_GLOBAL_SETTINGS);
                }

                case MUTATION_OPERATION_RESET: {
                    mSettingsRegistry.resetSettingsLocked(SETTINGS_TYPE_GLOBAL,
                            UserHandle.USER_SYSTEM, getCallingPackage(), mode, tag);
                } return true;
            }
        }

        return false;
    }

    private PackageInfo getCallingPackageInfo(int userId) {
        try {
            return mPackageManager.getPackageInfo(getCallingPackage(),
                    PackageManager.GET_SIGNATURES, userId);
        } catch (RemoteException e) {
            throw new IllegalStateException("Package " + getCallingPackage() + " doesn't exist");
        }
    }

    private Cursor getAllSecureSettings(int userId, String[] projection) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getAllSecureSettings(" + userId + ")");
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissionsLocked(userId);

        // The relevant "calling package" userId will be the owning userId for some
        // profiles, and we can't do the lookup inside our [lock held] loop, so work out
        // up front who the effective "new SSAID" user ID for that settings name will be.
        final int ssaidUserId = resolveOwningUserIdForSecureSettingLocked(callingUserId,
                Settings.Secure.ANDROID_ID);
        final PackageInfo ssaidCallingPkg = getCallingPackageInfo(ssaidUserId);

        synchronized (mLock) {
            List<String> names = getSettingsNamesLocked(SETTINGS_TYPE_SECURE, callingUserId);

            final int nameCount = names.size();

            String[] normalizedProjection = normalizeProjection(projection);
            MatrixCursor result = new MatrixCursor(normalizedProjection, nameCount);

            for (int i = 0; i < nameCount; i++) {
                String name = names.get(i);
                // Determine the owning user as some profile settings are cloned from the parent.
                final int owningUserId = resolveOwningUserIdForSecureSettingLocked(callingUserId,
                        name);

                if (!isSecureSettingAccessible(name, callingUserId, owningUserId)) {
                    // This caller is not permitted to access this setting. Pretend the setting
                    // doesn't exist.
                    continue;
                }

                // As of Android O, the SSAID is read from an app-specific entry in table
                // SETTINGS_FILE_SSAID, unless accessed by a system process.
                final Setting setting;
                if (isNewSsaidSetting(name)) {
                    setting = getSsaidSettingLocked(ssaidCallingPkg, owningUserId);
                } else {
                    setting = mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_SECURE, owningUserId,
                            name);
                }
                appendSettingToCursor(result, setting);
            }

            return result;
        }
    }

    private Setting getSecureSetting(String name, int requestingUserId) {
        return getSecureSetting(name, requestingUserId, /*enableOverride=*/ false);
    }

    private Setting getSecureSetting(String name, int requestingUserId, boolean enableOverride) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getSecureSetting(" + name + ", " + requestingUserId + ")");
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissionsLocked(requestingUserId);

        // Ensure the caller can access the setting.
        enforceSettingReadable(name, SETTINGS_TYPE_SECURE, UserHandle.getCallingUserId());

        // Determine the owning user as some profile settings are cloned from the parent.
        final int owningUserId = resolveOwningUserIdForSecureSettingLocked(callingUserId, name);

        if (!isSecureSettingAccessible(name, callingUserId, owningUserId)) {
            // This caller is not permitted to access this setting. Pretend the setting doesn't
            // exist.
            SettingsState settings = mSettingsRegistry.getSettingsLocked(SETTINGS_TYPE_SECURE,
                    owningUserId);
            return settings != null ? settings.getNullSetting() : null;
        }

        // As of Android O, the SSAID is read from an app-specific entry in table
        // SETTINGS_FILE_SSAID, unless accessed by a system process.
        if (isNewSsaidSetting(name)) {
            PackageInfo callingPkg = getCallingPackageInfo(owningUserId);
            synchronized (mLock) {
                return getSsaidSettingLocked(callingPkg, owningUserId);
            }
        }
        if (enableOverride) {
            if (Secure.LOCATION_PROVIDERS_ALLOWED.equals(name)) {
                final Setting overridden = getLocationProvidersAllowedSetting(owningUserId);
                if (overridden != null) {
                    return overridden;
                }
            }
        }

        // Not the SSAID; do a straight lookup
        synchronized (mLock) {
            return mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_SECURE,
                    owningUserId, name);
        }
    }

    private boolean isNewSsaidSetting(String name) {
        return Settings.Secure.ANDROID_ID.equals(name)
                && UserHandle.getAppId(Binder.getCallingUid()) >= Process.FIRST_APPLICATION_UID;
    }

    private Setting getSsaidSettingLocked(PackageInfo callingPkg, int owningUserId) {
        // Get uid of caller (key) used to store ssaid value
        String name = Integer.toString(
                UserHandle.getUid(owningUserId, UserHandle.getAppId(Binder.getCallingUid())));

        if (DEBUG) {
            Slog.v(LOG_TAG, "getSsaidSettingLocked(" + name + "," + owningUserId + ")");
        }

        // Retrieve the ssaid from the table if present.
        final Setting ssaid = mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_SSAID, owningUserId,
                name);
        // If the app is an Instant App use its stored SSAID instead of our own.
        final String instantSsaid;
        final long token = Binder.clearCallingIdentity();
        try {
            instantSsaid = mPackageManager.getInstantAppAndroidId(callingPkg.packageName,
                    owningUserId);
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Failed to get Instant App Android ID", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        final SettingsState ssaidSettings = mSettingsRegistry.getSettingsLocked(
                SETTINGS_TYPE_SSAID, owningUserId);

        if (instantSsaid != null) {
            // Use the stored value if it is still valid.
            if (ssaid != null && instantSsaid.equals(ssaid.getValue())) {
                return mascaradeSsaidSetting(ssaidSettings, ssaid);
            }
            // The value has changed, update the stored value.
            final boolean success = ssaidSettings.insertSettingLocked(name, instantSsaid, null,
                    true, callingPkg.packageName);
            if (!success) {
                throw new IllegalStateException("Failed to update instant app android id");
            }
            Setting setting = mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_SSAID,
                    owningUserId, name);
            return mascaradeSsaidSetting(ssaidSettings, setting);
        }

        // Lazy initialize ssaid if not yet present in ssaid table.
        if (ssaid == null || ssaid.isNull() || ssaid.getValue() == null) {
            Setting setting = mSettingsRegistry.generateSsaidLocked(callingPkg, owningUserId);
            return mascaradeSsaidSetting(ssaidSettings, setting);
        }

        return mascaradeSsaidSetting(ssaidSettings, ssaid);
    }

    private Setting mascaradeSsaidSetting(SettingsState settingsState, Setting ssaidSetting) {
        // SSAID settings are located in a dedicated table for internal bookkeeping
        // but for the world they reside in the secure table, so adjust the key here.
        // We have a special name when looking it up but want the world to see it as
        // "android_id".
        if (ssaidSetting != null) {
            return settingsState.new Setting(ssaidSetting) {
                @Override
                public int getKey() {
                    final int userId = getUserIdFromKey(super.getKey());
                    return makeKey(SETTINGS_TYPE_SECURE, userId);
                }

                @Override
                public String getName() {
                    return Settings.Secure.ANDROID_ID;
                }
            };
        }
        return null;
    }

    private Setting getLocationProvidersAllowedSetting(int owningUserId) {
        synchronized (mLock) {
            final Setting setting = getGlobalSetting(
                    Global.LOCATION_GLOBAL_KILL_SWITCH);
            if (!"1".equals(setting.getValue())) {
                return null;
            }
            // Global kill-switch is enabled. Return an empty value.
            final SettingsState settingsState = mSettingsRegistry.getSettingsLocked(
                    SETTINGS_TYPE_SECURE, owningUserId);
            return settingsState.new Setting(
                    Secure.LOCATION_PROVIDERS_ALLOWED,
                    "", // value
                    "", // tag
                    "", // default value
                    "", // package name
                    false, // from system
                    "0" // id
            ) {
                @Override
                public boolean update(String value, boolean setDefault, String packageName,
                        String tag, boolean forceNonSystemPackage) {
                    Slog.wtf(LOG_TAG, "update shoudln't be called on this instance.");
                    return false;
                }
            };
        }
    }

    private boolean insertSecureSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, boolean forceNotify) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "insertSecureSetting(" + name + ", " + value + ", "
                    + ", " + tag  + ", " + makeDefault + ", "  + requestingUserId
                    + ", " + forceNotify + ")");
        }
        return mutateSecureSetting(name, value, tag, makeDefault, requestingUserId,
                MUTATION_OPERATION_INSERT, forceNotify, 0);
    }

    private boolean deleteSecureSetting(String name, int requestingUserId, boolean forceNotify) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "deleteSecureSetting(" + name + ", " + requestingUserId
                    + ", " + forceNotify + ")");
        }

        return mutateSecureSetting(name, null, null, false, requestingUserId,
                MUTATION_OPERATION_DELETE, forceNotify, 0);
    }

    private boolean updateSecureSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, boolean forceNotify) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "updateSecureSetting(" + name + ", " + value + ", "
                    + ", " + tag  + ", " + makeDefault + ", "  + requestingUserId
                    + ", "  + forceNotify +")");
        }

        return mutateSecureSetting(name, value, tag, makeDefault, requestingUserId,
                MUTATION_OPERATION_UPDATE, forceNotify, 0);
    }

    private void resetSecureSetting(int requestingUserId, int mode, String tag) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "resetSecureSetting(" + requestingUserId + ", "
                    + mode + ", " + tag + ")");
        }

        mutateSecureSetting(null, null, tag, false, requestingUserId,
                MUTATION_OPERATION_RESET, false, mode);
    }

    private boolean mutateSecureSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, int operation, boolean forceNotify,
            int mode) {
        // Make sure the caller can change the settings.
        enforceWritePermission(Manifest.permission.WRITE_SECURE_SETTINGS);

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissionsLocked(requestingUserId);

        // If this is a setting that is currently restricted for this user, do not allow
        // unrestricting changes.
        if (name != null && mUserManagerInternal.isSettingRestrictedForUser(
                name, callingUserId, value, Binder.getCallingUid())) {
            return false;
        }

        // Determine the owning user as some profile settings are cloned from the parent.
        final int owningUserId = resolveOwningUserIdForSecureSettingLocked(callingUserId, name);

        // Only the owning user can change the setting.
        if (owningUserId != callingUserId) {
            return false;
        }

        // Special cases for location providers (sigh).
        if (Settings.Secure.LOCATION_PROVIDERS_ALLOWED.equals(name)) {
            return updateLocationProvidersAllowedLocked(value, tag, owningUserId, makeDefault,
                    forceNotify);
        }

        // Mutate the value.
        synchronized (mLock) {
            switch (operation) {
                case MUTATION_OPERATION_INSERT: {
                    return mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_SECURE,
                            owningUserId, name, value, tag, makeDefault,
                            getCallingPackage(), forceNotify, CRITICAL_SECURE_SETTINGS);
                }

                case MUTATION_OPERATION_DELETE: {
                    return mSettingsRegistry.deleteSettingLocked(SETTINGS_TYPE_SECURE,
                            owningUserId, name, forceNotify, CRITICAL_SECURE_SETTINGS);
                }

                case MUTATION_OPERATION_UPDATE: {
                    return mSettingsRegistry.updateSettingLocked(SETTINGS_TYPE_SECURE,
                            owningUserId, name, value, tag, makeDefault,
                            getCallingPackage(), forceNotify, CRITICAL_SECURE_SETTINGS);
                }

                case MUTATION_OPERATION_RESET: {
                    mSettingsRegistry.resetSettingsLocked(SETTINGS_TYPE_SECURE,
                            UserHandle.USER_SYSTEM, getCallingPackage(), mode, tag);
                } return true;
            }
        }

        return false;
    }

    private Cursor getAllSystemSettings(int userId, String[] projection) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getAllSecureSystem(" + userId + ")");
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissionsLocked(userId);

        synchronized (mLock) {
            List<String> names = getSettingsNamesLocked(SETTINGS_TYPE_SYSTEM, callingUserId);

            final int nameCount = names.size();

            String[] normalizedProjection = normalizeProjection(projection);
            MatrixCursor result = new MatrixCursor(normalizedProjection, nameCount);

            for (int i = 0; i < nameCount; i++) {
                String name = names.get(i);

                // Determine the owning user as some profile settings are cloned from the parent.
                final int owningUserId = resolveOwningUserIdForSystemSettingLocked(callingUserId,
                        name);

                Setting setting = mSettingsRegistry.getSettingLocked(
                        SETTINGS_TYPE_SYSTEM, owningUserId, name);
                appendSettingToCursor(result, setting);
            }

            return result;
        }
    }

    private Setting getSystemSetting(String name, int requestingUserId) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getSystemSetting(" + name + ", " + requestingUserId + ")");
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissionsLocked(requestingUserId);

        // Ensure the caller can access the setting.
        enforceSettingReadable(name, SETTINGS_TYPE_SYSTEM, UserHandle.getCallingUserId());

        // Determine the owning user as some profile settings are cloned from the parent.
        final int owningUserId = resolveOwningUserIdForSystemSettingLocked(callingUserId, name);

        // Get the value.
        synchronized (mLock) {
            return mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_SYSTEM, owningUserId, name);
        }
    }

    private boolean insertSystemSetting(String name, String value, int requestingUserId) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "insertSystemSetting(" + name + ", " + value + ", "
                    + requestingUserId + ")");
        }

        return mutateSystemSetting(name, value, requestingUserId, MUTATION_OPERATION_INSERT);
    }

    private boolean deleteSystemSetting(String name, int requestingUserId) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "deleteSystemSetting(" + name + ", " + requestingUserId + ")");
        }

        return mutateSystemSetting(name, null, requestingUserId, MUTATION_OPERATION_DELETE);
    }

    private boolean updateSystemSetting(String name, String value, int requestingUserId) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "updateSystemSetting(" + name + ", " + value + ", "
                    + requestingUserId + ")");
        }

        return mutateSystemSetting(name, value, requestingUserId, MUTATION_OPERATION_UPDATE);
    }

    private boolean mutateSystemSetting(String name, String value, int runAsUserId,
            int operation) {
        if (!hasWriteSecureSettingsPermission()) {
            // If the caller doesn't hold WRITE_SECURE_SETTINGS, we verify whether this
            // operation is allowed for the calling package through appops.
            if (!Settings.checkAndNoteWriteSettingsOperation(getContext(),
                    Binder.getCallingUid(), getCallingPackage(), true)) {
                return false;
            }
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissionsLocked(runAsUserId);

        if (name != null && mUserManagerInternal.isSettingRestrictedForUser(
                name, callingUserId, value, Binder.getCallingUid())) {
            return false;
        }

        // Enforce what the calling package can mutate the system settings.
        enforceRestrictedSystemSettingsMutationForCallingPackage(operation, name, callingUserId);

        // Determine the owning user as some profile settings are cloned from the parent.
        final int owningUserId = resolveOwningUserIdForSystemSettingLocked(callingUserId, name);

        // Only the owning user id can change the setting.
        if (owningUserId != callingUserId) {
            return false;
        }

        // Invalidate any relevant cache files
        String cacheName = null;
        if (Settings.System.RINGTONE.equals(name)) {
            cacheName = Settings.System.RINGTONE_CACHE;
        } else if (Settings.System.NOTIFICATION_SOUND.equals(name)) {
            cacheName = Settings.System.NOTIFICATION_SOUND_CACHE;
        } else if (Settings.System.ALARM_ALERT.equals(name)) {
            cacheName = Settings.System.ALARM_ALERT_CACHE;
        }
        if (cacheName != null) {
            final File cacheFile = new File(
                    getRingtoneCacheDir(owningUserId), cacheName);
            cacheFile.delete();
        }

        // Mutate the value.
        synchronized (mLock) {
            switch (operation) {
                case MUTATION_OPERATION_INSERT: {
                    validateSystemSettingValue(name, value);
                    return mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_SYSTEM,
                            owningUserId, name, value, null, false, getCallingPackage(),
                            false, null);
                }

                case MUTATION_OPERATION_DELETE: {
                    return mSettingsRegistry.deleteSettingLocked(SETTINGS_TYPE_SYSTEM,
                            owningUserId, name, false, null);
                }

                case MUTATION_OPERATION_UPDATE: {
                    validateSystemSettingValue(name, value);
                    return mSettingsRegistry.updateSettingLocked(SETTINGS_TYPE_SYSTEM,
                            owningUserId, name, value, null, false, getCallingPackage(),
                            false, null);
                }
            }

            return false;
        }
    }

    private boolean hasWriteSecureSettingsPermission() {
        // Write secure settings is a more protected permission. If caller has it we are good.
        if (getContext().checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        return false;
    }

    private void validateSystemSettingValue(String name, String value) {
        SettingsValidators.Validator validator = Settings.System.VALIDATORS.get(name);
        if (validator != null && !validator.validate(value)) {
            throw new IllegalArgumentException("Invalid value: " + value
                    + " for setting: " + name);
        }
    }

    /**
     * Returns {@code true} if the specified secure setting should be accessible to the caller.
     */
    private boolean isSecureSettingAccessible(String name, int callingUserId,
            int owningUserId) {
        // Special case for location (sigh).
        // This check is not inside the name-based checks below because this method performs checks
        // only if the calling user ID is not the same as the owning user ID.
        if (isLocationProvidersAllowedRestricted(name, callingUserId, owningUserId)) {
            return false;
        }

        switch (name) {
            case "bluetooth_address":
                // BluetoothManagerService for some reason stores the Android's Bluetooth MAC
                // address in this secure setting. Secure settings can normally be read by any app,
                // which thus enables them to bypass the recently introduced restrictions on access
                // to device identifiers.
                // To mitigate this we make this setting available only to callers privileged to see
                // this device's MAC addresses, same as through public API
                // BluetoothAdapter.getAddress() (see BluetoothManagerService for details).
                return getContext().checkCallingOrSelfPermission(
                        Manifest.permission.LOCAL_MAC_ADDRESS) == PackageManager.PERMISSION_GRANTED;
            default:
                return true;
        }
    }

    private boolean isLocationProvidersAllowedRestricted(String name, int callingUserId,
            int owningUserId) {
        // Optimization - location providers are restricted only for managed profiles.
        if (callingUserId == owningUserId) {
            return false;
        }
        if (Settings.Secure.LOCATION_PROVIDERS_ALLOWED.equals(name)
                && mUserManager.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION,
                new UserHandle(callingUserId))) {
            return true;
        }
        return false;
    }

    private int resolveOwningUserIdForSecureSettingLocked(int userId, String setting) {
        return resolveOwningUserIdLocked(userId, sSecureCloneToManagedSettings, setting);
    }

    private int resolveOwningUserIdForSystemSettingLocked(int userId, String setting) {
        final int parentId;
        // Resolves dependency if setting has a dependency and the calling user has a parent
        if (sSystemCloneFromParentOnDependency.containsKey(setting)
                && (parentId = getGroupParentLocked(userId)) != userId) {
            // The setting has a dependency and the profile has a parent
            String dependency = sSystemCloneFromParentOnDependency.get(setting);
            // Lookup the dependency setting as ourselves, some callers may not have access to it.
            final long token = Binder.clearCallingIdentity();
            try {
                Setting settingObj = getSecureSetting(dependency, userId);
                if (settingObj != null && settingObj.getValue().equals("1")) {
                    return parentId;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        return resolveOwningUserIdLocked(userId, sSystemCloneToManagedSettings, setting);
    }

    private int resolveOwningUserIdLocked(int userId, Set<String> keys, String name) {
        final int parentId = getGroupParentLocked(userId);
        if (parentId != userId && keys.contains(name)) {
            return parentId;
        }
        return userId;
    }

    private void enforceRestrictedSystemSettingsMutationForCallingPackage(int operation,
            String name, int userId) {
        // System/root/shell can mutate whatever secure settings they want.
        final int callingUid = Binder.getCallingUid();
        final int appId = UserHandle.getAppId(callingUid);
        if (appId == android.os.Process.SYSTEM_UID
                || appId == Process.SHELL_UID
                || appId == Process.ROOT_UID) {
            return;
        }

        switch (operation) {
            case MUTATION_OPERATION_INSERT:
                // Insert updates.
            case MUTATION_OPERATION_UPDATE: {
                if (Settings.System.PUBLIC_SETTINGS.contains(name)) {
                    return;
                }

                // The calling package is already verified.
                PackageInfo packageInfo = getCallingPackageInfoOrThrow(userId);

                // Privileged apps can do whatever they want.
                if ((packageInfo.applicationInfo.privateFlags
                        & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                    return;
                }

                warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk(
                        packageInfo.applicationInfo.targetSdkVersion, name);
            } break;

            case MUTATION_OPERATION_DELETE: {
                if (Settings.System.PUBLIC_SETTINGS.contains(name)
                        || Settings.System.PRIVATE_SETTINGS.contains(name)) {
                    throw new IllegalArgumentException("You cannot delete system defined"
                            + " secure settings.");
                }

                // The calling package is already verified.
                PackageInfo packageInfo = getCallingPackageInfoOrThrow(userId);

                // Privileged apps can do whatever they want.
                if ((packageInfo.applicationInfo.privateFlags &
                        ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                    return;
                }

                warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk(
                        packageInfo.applicationInfo.targetSdkVersion, name);
            } break;
        }
    }

    private Set<String> getInstantAppAccessibleSettings(int settingsType) {
        switch (settingsType) {
            case SETTINGS_TYPE_GLOBAL:
                return Settings.Global.INSTANT_APP_SETTINGS;
            case SETTINGS_TYPE_SECURE:
                return Settings.Secure.INSTANT_APP_SETTINGS;
            case SETTINGS_TYPE_SYSTEM:
                return Settings.System.INSTANT_APP_SETTINGS;
            default:
                throw new IllegalArgumentException("Invalid settings type: " + settingsType);
        }
    }

    private Set<String> getOverlayInstantAppAccessibleSettings(int settingsType) {
        switch (settingsType) {
            case SETTINGS_TYPE_GLOBAL:
                return OVERLAY_ALLOWED_GLOBAL_INSTANT_APP_SETTINGS;
            case SETTINGS_TYPE_SYSTEM:
                return OVERLAY_ALLOWED_SYSTEM_INSTANT_APP_SETTINGS;
            case SETTINGS_TYPE_SECURE:
                return OVERLAY_ALLOWED_SECURE_INSTANT_APP_SETTINGS;
            default:
                throw new IllegalArgumentException("Invalid settings type: " + settingsType);
        }
    }

    private List<String> getSettingsNamesLocked(int settingsType, int userId) {
        // Don't enforce the instant app whitelist for now -- its too prone to unintended breakage
        // in the current form.
        return mSettingsRegistry.getSettingsNamesLocked(settingsType, userId);
    }

    private void enforceSettingReadable(String settingName, int settingsType, int userId) {
        if (UserHandle.getAppId(Binder.getCallingUid()) < Process.FIRST_APPLICATION_UID) {
            return;
        }
        ApplicationInfo ai = getCallingApplicationInfoOrThrow();
        if (!ai.isInstantApp()) {
            return;
        }
        if (!getInstantAppAccessibleSettings(settingsType).contains(settingName)
                && !getOverlayInstantAppAccessibleSettings(settingsType).contains(settingName)) {
            // Don't enforce the instant app whitelist for now -- its too prone to unintended
            // breakage in the current form.
            Slog.w(LOG_TAG, "Instant App " + ai.packageName
                    + " trying to access unexposed setting, this will be an error in the future.");
        }
    }

    private ApplicationInfo getCallingApplicationInfoOrThrow() {
        // We always use the callingUid for this lookup. This means that if hypothetically an
        // app was installed in user A with cross user and in user B as an Instant App
        // the app in A would be able to see all the settings in user B. However since cross
        // user is a system permission and the app must be uninstalled in B and then installed as
        // an Instant App that situation is not realistic or supported.
        ApplicationInfo ai = null;
        try {
            ai = mPackageManager.getApplicationInfo(getCallingPackage(), 0
                    , UserHandle.getCallingUserId());
        } catch (RemoteException ignored) {
        }
        if (ai == null) {
            throw new IllegalStateException("Failed to lookup info for package "
                    + getCallingPackage());
        }
        return ai;
    }

    private PackageInfo getCallingPackageInfoOrThrow(int userId) {
        try {
            PackageInfo packageInfo = mPackageManager.getPackageInfo(
                    getCallingPackage(), 0, userId);
            if (packageInfo != null) {
                return packageInfo;
            }
        } catch (RemoteException e) {
            /* ignore */
        }
        throw new IllegalStateException("Calling package doesn't exist");
    }

    private int getGroupParentLocked(int userId) {
        // Most frequent use case.
        if (userId == UserHandle.USER_SYSTEM) {
            return userId;
        }
        // We are in the same process with the user manager and the returned
        // user info is a cached instance, so just look up instead of cache.
        final long identity = Binder.clearCallingIdentity();
        try {
            // Just a lookup and not reentrant, so holding a lock is fine.
            UserInfo userInfo = mUserManager.getProfileParent(userId);
            return (userInfo != null) ? userInfo.id : userId;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void enforceWritePermission(String permission) {
        if (getContext().checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Permission denial: writing to settings requires:"
                    + permission);
        }
    }

    /*
     * Used to parse changes to the value of Settings.Secure.LOCATION_PROVIDERS_ALLOWED.
     * This setting contains a list of the currently enabled location providers.
     * But helper functions in android.providers.Settings can enable or disable
     * a single provider by using a "+" or "-" prefix before the provider name.
     *
     * <p>See also {@link com.android.server.pm.UserRestrictionsUtils#isSettingRestrictedForUser()}.
     * If DISALLOW_SHARE_LOCATION is set, the said method will only allow values with
     * the "-" prefix.
     *
     * @returns whether the enabled location providers changed.
     */
    private boolean updateLocationProvidersAllowedLocked(String value, String tag,
            int owningUserId, boolean makeDefault, boolean forceNotify) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        Setting oldSetting = getSecureSetting(
                Settings.Secure.LOCATION_PROVIDERS_ALLOWED, owningUserId);
        if (oldSetting == null) {
            return false;
        }
        String oldProviders = oldSetting.getValue();
        List<String> oldProvidersList = TextUtils.isEmpty(oldProviders)
                ? new ArrayList<>() : new ArrayList<>(Arrays.asList(oldProviders.split(",")));
        Set<String> newProvidersSet = new ArraySet<>();
        newProvidersSet.addAll(oldProvidersList);

        String[] providerUpdates = value.split(",");
        boolean inputError = false;
        for (String provider : providerUpdates) {
            // do not update location_providers_allowed when input is invalid
            if (TextUtils.isEmpty(provider)) {
                inputError = true;
                break;
            }
            final char prefix = provider.charAt(0);
            // do not update location_providers_allowed when input is invalid
            if (prefix != '+' && prefix != '-') {
                inputError = true;
                break;
            }
            // skip prefix
            provider = provider.substring(1);
            if (prefix == '+') {
                newProvidersSet.add(provider);
            } else if (prefix == '-') {
                newProvidersSet.remove(provider);
            }
        }
        String newProviders = TextUtils.join(",", newProvidersSet.toArray());
        if (inputError == true || newProviders.equals(oldProviders)) {
            // nothing changed, so no need to update the database
            if (forceNotify) {
                mSettingsRegistry.notifyForSettingsChange(
                        makeKey(SETTINGS_TYPE_SECURE, owningUserId),
                        Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
            }
            return false;
        }
        return mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_SECURE,
                owningUserId, Settings.Secure.LOCATION_PROVIDERS_ALLOWED, newProviders, tag,
                makeDefault, getCallingPackage(), forceNotify, CRITICAL_SECURE_SETTINGS);
    }

    private static void warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk(
            int targetSdkVersion, String name) {
        // If the app targets Lollipop MR1 or older SDK we warn, otherwise crash.
        if (targetSdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (Settings.System.PRIVATE_SETTINGS.contains(name)) {
                Slog.w(LOG_TAG, "You shouldn't not change private system settings."
                        + " This will soon become an error.");
            } else {
                Slog.w(LOG_TAG, "You shouldn't keep your settings in the secure settings."
                        + " This will soon become an error.");
            }
        } else {
            if (Settings.System.PRIVATE_SETTINGS.contains(name)) {
                throw new IllegalArgumentException("You cannot change private secure settings.");
            } else {
                throw new IllegalArgumentException("You cannot keep your settings in"
                        + " the secure settings.");
            }
        }
    }

    private static int resolveCallingUserIdEnforcingPermissionsLocked(int requestingUserId) {
        if (requestingUserId == UserHandle.getCallingUserId()) {
            return requestingUserId;
        }
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), requestingUserId, false, true,
                "get/set setting for user", null);
    }

    private Bundle packageValueForCallResult(Setting setting,
            boolean trackingGeneration) {
        if (!trackingGeneration) {
            if (setting == null || setting.isNull()) {
                return NULL_SETTING_BUNDLE;
            }
            return Bundle.forPair(Settings.NameValueTable.VALUE, setting.getValue());
        }
        Bundle result = new Bundle();
        result.putString(Settings.NameValueTable.VALUE,
                !setting.isNull() ? setting.getValue() : null);

        mSettingsRegistry.mGenerationRegistry.addGenerationData(result, setting.getKey());
        return result;
    }

    private static int getRequestingUserId(Bundle args) {
        final int callingUserId = UserHandle.getCallingUserId();
        return (args != null) ? args.getInt(Settings.CALL_METHOD_USER_KEY, callingUserId)
                : callingUserId;
    }

    private boolean isTrackingGeneration(Bundle args) {
        return args != null && args.containsKey(Settings.CALL_METHOD_TRACK_GENERATION_KEY);
    }

    private static String getSettingValue(Bundle args) {
        return (args != null) ? args.getString(Settings.NameValueTable.VALUE) : null;
    }

    private static String getSettingTag(Bundle args) {
        return (args != null) ? args.getString(Settings.CALL_METHOD_TAG_KEY) : null;
    }

    private static boolean getSettingMakeDefault(Bundle args) {
        return (args != null) && args.getBoolean(Settings.CALL_METHOD_MAKE_DEFAULT_KEY);
    }

    private static int getResetModeEnforcingPermission(Bundle args) {
        final int mode = (args != null) ? args.getInt(Settings.CALL_METHOD_RESET_MODE_KEY) : 0;
        switch (mode) {
            case Settings.RESET_MODE_UNTRUSTED_DEFAULTS: {
                if (!isCallerSystemOrShellOrRootOnDebuggableBuild()) {
                    throw new SecurityException("Only system, shell/root on a "
                            + "debuggable build can reset to untrusted defaults");
                }
                return mode;
            }
            case Settings.RESET_MODE_UNTRUSTED_CHANGES: {
                if (!isCallerSystemOrShellOrRootOnDebuggableBuild()) {
                    throw new SecurityException("Only system, shell/root on a "
                            + "debuggable build can reset untrusted changes");
                }
                return mode;
            }
            case Settings.RESET_MODE_TRUSTED_DEFAULTS: {
                if (!isCallerSystemOrShellOrRootOnDebuggableBuild()) {
                    throw new SecurityException("Only system, shell/root on a "
                            + "debuggable build can reset to trusted defaults");
                }
                return mode;
            }
            case Settings.RESET_MODE_PACKAGE_DEFAULTS: {
                return mode;
            }
        }
        throw new IllegalArgumentException("Invalid reset mode: " + mode);
    }

    private static boolean isCallerSystemOrShellOrRootOnDebuggableBuild() {
        final int appId = UserHandle.getAppId(Binder.getCallingUid());
        return appId == SYSTEM_UID || (Build.IS_DEBUGGABLE
                && (appId == SHELL_UID || appId == ROOT_UID));
    }

    private static String getValidTableOrThrow(Uri uri) {
        if (uri.getPathSegments().size() > 0) {
            String table = uri.getPathSegments().get(0);
            if (DatabaseHelper.isValidTable(table)) {
                return table;
            }
            throw new IllegalArgumentException("Bad root path: " + table);
        }
        throw new IllegalArgumentException("Invalid URI:" + uri);
    }

    private static MatrixCursor packageSettingForQuery(Setting setting, String[] projection) {
        if (setting.isNull()) {
            return new MatrixCursor(projection, 0);
        }
        MatrixCursor cursor = new MatrixCursor(projection, 1);
        appendSettingToCursor(cursor, setting);
        return cursor;
    }

    private static String[] normalizeProjection(String[] projection) {
        if (projection == null) {
            return ALL_COLUMNS;
        }

        final int columnCount = projection.length;
        for (int i = 0; i < columnCount; i++) {
            String column = projection[i];
            if (!ArrayUtils.contains(ALL_COLUMNS, column)) {
                throw new IllegalArgumentException("Invalid column: " + column);
            }
        }

        return projection;
    }

    private static void appendSettingToCursor(MatrixCursor cursor, Setting setting) {
        if (setting == null || setting.isNull()) {
            return;
        }
        final int columnCount = cursor.getColumnCount();

        String[] values =  new String[columnCount];

        for (int i = 0; i < columnCount; i++) {
            String column = cursor.getColumnName(i);

            switch (column) {
                case Settings.NameValueTable._ID: {
                    values[i] = setting.getId();
                } break;

                case Settings.NameValueTable.NAME: {
                    values[i] = setting.getName();
                } break;

                case Settings.NameValueTable.VALUE: {
                    values[i] = setting.getValue();
                } break;
            }
        }

        cursor.addRow(values);
    }

    private static boolean isKeyValid(String key) {
        return !(TextUtils.isEmpty(key) || SettingsState.isBinary(key));
    }

    private static final class Arguments {
        private static final Pattern WHERE_PATTERN_WITH_PARAM_NO_BRACKETS =
                Pattern.compile("[\\s]*name[\\s]*=[\\s]*\\?[\\s]*");

        private static final Pattern WHERE_PATTERN_WITH_PARAM_IN_BRACKETS =
                Pattern.compile("[\\s]*\\([\\s]*name[\\s]*=[\\s]*\\?[\\s]*\\)[\\s]*");

        private static final Pattern WHERE_PATTERN_NO_PARAM_IN_BRACKETS =
                Pattern.compile("[\\s]*\\([\\s]*name[\\s]*=[\\s]*['\"].*['\"][\\s]*\\)[\\s]*");

        private static final Pattern WHERE_PATTERN_NO_PARAM_NO_BRACKETS =
                Pattern.compile("[\\s]*name[\\s]*=[\\s]*['\"].*['\"][\\s]*");

        public final String table;
        public final String name;

        public Arguments(Uri uri, String where, String[] whereArgs, boolean supportAll) {
            final int segmentSize = uri.getPathSegments().size();
            switch (segmentSize) {
                case 1: {
                    if (where != null
                            && (WHERE_PATTERN_WITH_PARAM_NO_BRACKETS.matcher(where).matches()
                                || WHERE_PATTERN_WITH_PARAM_IN_BRACKETS.matcher(where).matches())
                            && whereArgs.length == 1) {
                        name = whereArgs[0];
                        table = computeTableForSetting(uri, name);
                        return;
                    } else if (where != null
                            && (WHERE_PATTERN_NO_PARAM_NO_BRACKETS.matcher(where).matches()
                                || WHERE_PATTERN_NO_PARAM_IN_BRACKETS.matcher(where).matches())) {
                        final int startIndex = Math.max(where.indexOf("'"),
                                where.indexOf("\"")) + 1;
                        final int endIndex = Math.max(where.lastIndexOf("'"),
                                where.lastIndexOf("\""));
                        name = where.substring(startIndex, endIndex);
                        table = computeTableForSetting(uri, name);
                        return;
                    } else if (supportAll && where == null && whereArgs == null) {
                        name = null;
                        table = computeTableForSetting(uri, null);
                        return;
                    }
                } break;

                case 2: {
                    if (where == null && whereArgs == null) {
                        name = uri.getPathSegments().get(1);
                        table = computeTableForSetting(uri, name);
                        return;
                    }
                } break;
            }

            EventLogTags.writeUnsupportedSettingsQuery(
                    uri.toSafeString(), where, Arrays.toString(whereArgs));
            String message = String.format( "Supported SQL:\n"
                    + "  uri content://some_table/some_property with null where and where args\n"
                    + "  uri content://some_table with query name=? and single name as arg\n"
                    + "  uri content://some_table with query name=some_name and null args\n"
                    + "  but got - uri:%1s, where:%2s whereArgs:%3s", uri, where,
                    Arrays.toString(whereArgs));
            throw new IllegalArgumentException(message);
        }

        private static String computeTableForSetting(Uri uri, String name) {
            String table = getValidTableOrThrow(uri);

            if (name != null) {
                if (sSystemMovedToSecureSettings.contains(name)) {
                    table = TABLE_SECURE;
                }

                if (sSystemMovedToGlobalSettings.contains(name)) {
                    table = TABLE_GLOBAL;
                }

                if (sSecureMovedToGlobalSettings.contains(name)) {
                    table = TABLE_GLOBAL;
                }

                if (sGlobalMovedToSecureSettings.contains(name)) {
                    table = TABLE_SECURE;
                }
            }

            return table;
        }
    }

    final class SettingsRegistry {
        private static final String DROPBOX_TAG_USERLOG = "restricted_profile_ssaid";

        private static final String SETTINGS_FILE_GLOBAL = "settings_global.xml";
        private static final String SETTINGS_FILE_SYSTEM = "settings_system.xml";
        private static final String SETTINGS_FILE_SECURE = "settings_secure.xml";
        private static final String SETTINGS_FILE_SSAID = "settings_ssaid.xml";

        private static final String SSAID_USER_KEY = "userkey";

        private final SparseArray<SettingsState> mSettingsStates = new SparseArray<>();

        private GenerationRegistry mGenerationRegistry;

        private final Handler mHandler;

        private final BackupManager mBackupManager;

        private String mSettingsCreationBuildId;

        public SettingsRegistry() {
            mHandler = new MyHandler(getContext().getMainLooper());
            mGenerationRegistry = new GenerationRegistry(mLock);
            mBackupManager = new BackupManager(getContext());
            migrateAllLegacySettingsIfNeeded();
            syncSsaidTableOnStart();
        }

        private void generateUserKeyLocked(int userId) {
            // Generate a random key for each user used for creating a new ssaid.
            final byte[] keyBytes = new byte[32];
            final SecureRandom rand = new SecureRandom();
            rand.nextBytes(keyBytes);

            // Convert to string for storage in settings table.
            final String userKey = ByteStringUtils.toHexString(keyBytes);

            // Store the key in the ssaid table.
            final SettingsState ssaidSettings = getSettingsLocked(SETTINGS_TYPE_SSAID, userId);
            final boolean success = ssaidSettings.insertSettingLocked(SSAID_USER_KEY, userKey, null,
                    true, SettingsState.SYSTEM_PACKAGE_NAME);

            if (!success) {
                throw new IllegalStateException("Ssaid settings not accessible");
            }
        }

        private byte[] getLengthPrefix(byte[] data) {
            return ByteBuffer.allocate(4).putInt(data.length).array();
        }

        public Setting generateSsaidLocked(PackageInfo callingPkg, int userId) {
            // Read the user's key from the ssaid table.
            Setting userKeySetting = getSettingLocked(SETTINGS_TYPE_SSAID, userId, SSAID_USER_KEY);
            if (userKeySetting == null || userKeySetting.isNull()
                    || userKeySetting.getValue() == null) {
                // Lazy initialize and store the user key.
                generateUserKeyLocked(userId);
                userKeySetting = getSettingLocked(SETTINGS_TYPE_SSAID, userId, SSAID_USER_KEY);
                if (userKeySetting == null || userKeySetting.isNull()
                        || userKeySetting.getValue() == null) {
                    throw new IllegalStateException("User key not accessible");
                }
            }
            final String userKey = userKeySetting.getValue();

            // Convert the user's key back to a byte array.
            final byte[] keyBytes = ByteStringUtils.fromHexToByteArray(userKey);

            // Validate that the key is of expected length.
            // Keys are currently 32 bytes, but were once 16 bytes during Android O development.
            if (keyBytes == null || (keyBytes.length != 16 && keyBytes.length != 32)) {
                throw new IllegalStateException("User key invalid");
            }

            final Mac m;
            try {
                m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(keyBytes, m.getAlgorithm()));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("HmacSHA256 is not available", e);
            } catch (InvalidKeyException e) {
                throw new IllegalStateException("Key is corrupted", e);
            }

            // Mac each of the developer signatures.
            for (int i = 0; i < callingPkg.signatures.length; i++) {
                byte[] sig = callingPkg.signatures[i].toByteArray();
                m.update(getLengthPrefix(sig), 0, 4);
                m.update(sig);
            }

            // Convert result to a string for storage in settings table. Only want first 64 bits.
            final String ssaid = ByteStringUtils.toHexString(m.doFinal()).substring(0, 16)
                    .toLowerCase(Locale.US);

            // Save the ssaid in the ssaid table.
            final String uid = Integer.toString(callingPkg.applicationInfo.uid);
            final SettingsState ssaidSettings = getSettingsLocked(SETTINGS_TYPE_SSAID, userId);
            final boolean success = ssaidSettings.insertSettingLocked(uid, ssaid, null, true,
                callingPkg.packageName);

            if (!success) {
                throw new IllegalStateException("Ssaid settings not accessible");
            }

            return getSettingLocked(SETTINGS_TYPE_SSAID, userId, uid);
        }

        public void syncSsaidTableOnStart() {
            synchronized (mLock) {
                // Verify that each user's packages and ssaid's are in sync.
                for (UserInfo user : mUserManager.getUsers(true)) {
                    // Get all uids for the user's packages.
                    final List<PackageInfo> packages;
                    try {
                        packages = mPackageManager.getInstalledPackages(
                            PackageManager.MATCH_UNINSTALLED_PACKAGES,
                            user.id).getList();
                    } catch (RemoteException e) {
                        throw new IllegalStateException("Package manager not available");
                    }
                    final Set<String> appUids = new HashSet<>();
                    for (PackageInfo info : packages) {
                        appUids.add(Integer.toString(info.applicationInfo.uid));
                    }

                    // Get all uids currently stored in the user's ssaid table.
                    final Set<String> ssaidUids = new HashSet<>(
                            getSettingsNamesLocked(SETTINGS_TYPE_SSAID, user.id));
                    ssaidUids.remove(SSAID_USER_KEY);

                    // Perform a set difference for the appUids and ssaidUids.
                    ssaidUids.removeAll(appUids);

                    // If there are ssaidUids left over they need to be removed from the table.
                    final SettingsState ssaidSettings = getSettingsLocked(SETTINGS_TYPE_SSAID,
                            user.id);
                    for (String uid : ssaidUids) {
                        ssaidSettings.deleteSettingLocked(uid);
                    }
                }
            }
        }

        public List<String> getSettingsNamesLocked(int type, int userId) {
            final int key = makeKey(type, userId);
            SettingsState settingsState = peekSettingsStateLocked(key);
            if (settingsState == null) {
                return new ArrayList<String>();
            }
            return settingsState.getSettingNamesLocked();
        }

        public SparseBooleanArray getKnownUsersLocked() {
            SparseBooleanArray users = new SparseBooleanArray();
            for (int i = mSettingsStates.size()-1; i >= 0; i--) {
                users.put(getUserIdFromKey(mSettingsStates.keyAt(i)), true);
            }
            return users;
        }

        @Nullable
        public SettingsState getSettingsLocked(int type, int userId) {
            final int key = makeKey(type, userId);
            return peekSettingsStateLocked(key);
        }

        public boolean ensureSettingsForUserLocked(int userId) {
            // First make sure this user actually exists.
            if (mUserManager.getUserInfo(userId) == null) {
                Slog.wtf(LOG_TAG, "Requested user " + userId + " does not exist");
                return false;
            }

            // Migrate the setting for this user if needed.
            migrateLegacySettingsForUserIfNeededLocked(userId);

            // Ensure global settings loaded if owner.
            if (userId == UserHandle.USER_SYSTEM) {
                final int globalKey = makeKey(SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
                ensureSettingsStateLocked(globalKey);
            }

            // Ensure secure settings loaded.
            final int secureKey = makeKey(SETTINGS_TYPE_SECURE, userId);
            ensureSettingsStateLocked(secureKey);

            // Make sure the secure settings have an Android id set.
            SettingsState secureSettings = getSettingsLocked(SETTINGS_TYPE_SECURE, userId);
            ensureSecureSettingAndroidIdSetLocked(secureSettings);

            // Ensure system settings loaded.
            final int systemKey = makeKey(SETTINGS_TYPE_SYSTEM, userId);
            ensureSettingsStateLocked(systemKey);

            // Ensure secure settings loaded.
            final int ssaidKey = makeKey(SETTINGS_TYPE_SSAID, userId);
            ensureSettingsStateLocked(ssaidKey);

            // Upgrade the settings to the latest version.
            UpgradeController upgrader = new UpgradeController(userId);
            upgrader.upgradeIfNeededLocked();
            return true;
        }

        private void ensureSettingsStateLocked(int key) {
            if (mSettingsStates.get(key) == null) {
                final int maxBytesPerPackage = getMaxBytesPerPackageForType(getTypeFromKey(key));
                SettingsState settingsState = new SettingsState(getContext(), mLock,
                        getSettingsFile(key), key, maxBytesPerPackage, mHandlerThread.getLooper());
                mSettingsStates.put(key, settingsState);
            }
        }

        public void removeUserStateLocked(int userId, boolean permanently) {
            // We always keep the global settings in memory.

            // Nuke system settings.
            final int systemKey = makeKey(SETTINGS_TYPE_SYSTEM, userId);
            final SettingsState systemSettingsState = mSettingsStates.get(systemKey);
            if (systemSettingsState != null) {
                if (permanently) {
                    mSettingsStates.remove(systemKey);
                    systemSettingsState.destroyLocked(null);
                } else {
                    systemSettingsState.destroyLocked(new Runnable() {
                        @Override
                        public void run() {
                            mSettingsStates.remove(systemKey);
                        }
                    });
                }
            }

            // Nuke secure settings.
            final int secureKey = makeKey(SETTINGS_TYPE_SECURE, userId);
            final SettingsState secureSettingsState = mSettingsStates.get(secureKey);
            if (secureSettingsState != null) {
                if (permanently) {
                    mSettingsStates.remove(secureKey);
                    secureSettingsState.destroyLocked(null);
                } else {
                    secureSettingsState.destroyLocked(new Runnable() {
                        @Override
                        public void run() {
                            mSettingsStates.remove(secureKey);
                        }
                    });
                }
            }

            // Nuke ssaid settings.
            final int ssaidKey = makeKey(SETTINGS_TYPE_SSAID, userId);
            final SettingsState ssaidSettingsState = mSettingsStates.get(ssaidKey);
            if (ssaidSettingsState != null) {
                if (permanently) {
                    mSettingsStates.remove(ssaidKey);
                    ssaidSettingsState.destroyLocked(null);
                } else {
                    ssaidSettingsState.destroyLocked(new Runnable() {
                        @Override
                        public void run() {
                            mSettingsStates.remove(ssaidKey);
                        }
                    });
                }
            }

            // Nuke generation tracking data
            mGenerationRegistry.onUserRemoved(userId);
        }

        public boolean insertSettingLocked(int type, int userId, String name, String value,
                String tag, boolean makeDefault, String packageName, boolean forceNotify,
                Set<String> criticalSettings) {
            final int key = makeKey(type, userId);

            boolean success = false;
            SettingsState settingsState = peekSettingsStateLocked(key);
            if (settingsState != null) {
                success = settingsState.insertSettingLocked(name, value,
                        tag, makeDefault, packageName);
            }

            if (success && criticalSettings != null && criticalSettings.contains(name)) {
                settingsState.persistSyncLocked();
            }

            if (forceNotify || success) {
                notifyForSettingsChange(key, name);
            }
            return success;
        }

        public boolean deleteSettingLocked(int type, int userId, String name, boolean forceNotify,
                Set<String> criticalSettings) {
            final int key = makeKey(type, userId);

            boolean success = false;
            SettingsState settingsState = peekSettingsStateLocked(key);
            if (settingsState != null) {
                success = settingsState.deleteSettingLocked(name);
            }

            if (success && criticalSettings != null && criticalSettings.contains(name)) {
                settingsState.persistSyncLocked();
            }

            if (forceNotify || success) {
                notifyForSettingsChange(key, name);
            }
            return success;
        }

        public boolean updateSettingLocked(int type, int userId, String name, String value,
                String tag, boolean makeDefault, String packageName, boolean forceNotify,
                Set<String> criticalSettings) {
            final int key = makeKey(type, userId);

            boolean success = false;
            SettingsState settingsState = peekSettingsStateLocked(key);
            if (settingsState != null) {
                success = settingsState.updateSettingLocked(name, value, tag,
                        makeDefault, packageName);
            }

            if (success && criticalSettings != null && criticalSettings.contains(name)) {
                settingsState.persistSyncLocked();
            }

            if (forceNotify || success) {
                notifyForSettingsChange(key, name);
            }

            return success;
        }

        public Setting getSettingLocked(int type, int userId, String name) {
            final int key = makeKey(type, userId);

            SettingsState settingsState = peekSettingsStateLocked(key);
            if (settingsState == null) {
                return null;
            }

            // getSettingLocked will return non-null result
            return settingsState.getSettingLocked(name);
        }

        public void resetSettingsLocked(int type, int userId, String packageName, int mode,
                String tag) {
            final int key = makeKey(type, userId);
            SettingsState settingsState = peekSettingsStateLocked(key);
            if (settingsState == null) {
                return;
            }

            switch (mode) {
                case Settings.RESET_MODE_PACKAGE_DEFAULTS: {
                    for (String name : settingsState.getSettingNamesLocked()) {
                        boolean someSettingChanged = false;
                        Setting setting = settingsState.getSettingLocked(name);
                        if (packageName.equals(setting.getPackageName())) {
                            if (tag != null && !tag.equals(setting.getTag())) {
                                continue;
                            }
                            if (settingsState.resetSettingLocked(name)) {
                                someSettingChanged = true;
                                notifyForSettingsChange(key, name);
                            }
                        }
                        if (someSettingChanged) {
                            settingsState.persistSyncLocked();
                        }
                    }
                } break;

                case Settings.RESET_MODE_UNTRUSTED_DEFAULTS: {
                    for (String name : settingsState.getSettingNamesLocked()) {
                        boolean someSettingChanged = false;
                        Setting setting = settingsState.getSettingLocked(name);
                        if (!SettingsState.isSystemPackage(getContext(),
                                setting.getPackageName())) {
                            if (settingsState.resetSettingLocked(name)) {
                                someSettingChanged = true;
                                notifyForSettingsChange(key, name);
                            }
                        }
                        if (someSettingChanged) {
                            settingsState.persistSyncLocked();
                        }
                    }
                } break;

                case Settings.RESET_MODE_UNTRUSTED_CHANGES: {
                    for (String name : settingsState.getSettingNamesLocked()) {
                        boolean someSettingChanged = false;
                        Setting setting = settingsState.getSettingLocked(name);
                        if (!SettingsState.isSystemPackage(getContext(),
                                setting.getPackageName())) {
                            if (setting.isDefaultFromSystem()) {
                                if (settingsState.resetSettingLocked(name)) {
                                    someSettingChanged = true;
                                    notifyForSettingsChange(key, name);
                                }
                            } else if (settingsState.deleteSettingLocked(name)) {
                                someSettingChanged = true;
                                notifyForSettingsChange(key, name);
                            }
                        }
                        if (someSettingChanged) {
                            settingsState.persistSyncLocked();
                        }
                    }
                } break;

                case Settings.RESET_MODE_TRUSTED_DEFAULTS: {
                    for (String name : settingsState.getSettingNamesLocked()) {
                        Setting setting = settingsState.getSettingLocked(name);
                        boolean someSettingChanged = false;
                        if (setting.isDefaultFromSystem()) {
                            if (settingsState.resetSettingLocked(name)) {
                                someSettingChanged = true;
                                notifyForSettingsChange(key, name);
                            }
                        } else if (settingsState.deleteSettingLocked(name)) {
                            someSettingChanged = true;
                            notifyForSettingsChange(key, name);
                        }
                        if (someSettingChanged) {
                            settingsState.persistSyncLocked();
                        }
                    }
                } break;
            }
        }

        public void onPackageRemovedLocked(String packageName, int userId) {
            // Global and secure settings are signature protected. Apps signed
            // by the platform certificate are generally not uninstalled  and
            // the main exception is tests. We trust components signed
            // by the platform certificate and do not do a clean up after them.

            final int systemKey = makeKey(SETTINGS_TYPE_SYSTEM, userId);
            SettingsState systemSettings = mSettingsStates.get(systemKey);
            if (systemSettings != null) {
                systemSettings.onPackageRemovedLocked(packageName);
            }
        }

        public void onUidRemovedLocked(int uid) {
            final SettingsState ssaidSettings = getSettingsLocked(SETTINGS_TYPE_SSAID,
                    UserHandle.getUserId(uid));
            if (ssaidSettings != null) {
                ssaidSettings.deleteSettingLocked(Integer.toString(uid));
            }
        }

        @Nullable
        private SettingsState peekSettingsStateLocked(int key) {
            SettingsState settingsState = mSettingsStates.get(key);
            if (settingsState != null) {
                return settingsState;
            }

            if (!ensureSettingsForUserLocked(getUserIdFromKey(key))) {
                return null;
            }
            return mSettingsStates.get(key);
        }

        private void migrateAllLegacySettingsIfNeeded() {
            synchronized (mLock) {
                final int key = makeKey(SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
                File globalFile = getSettingsFile(key);
                if (SettingsState.stateFileExists(globalFile)) {
                    return;
                }

                mSettingsCreationBuildId = Build.ID;

                final long identity = Binder.clearCallingIdentity();
                try {
                    List<UserInfo> users = mUserManager.getUsers(true);

                    final int userCount = users.size();
                    for (int i = 0; i < userCount; i++) {
                        final int userId = users.get(i).id;

                        DatabaseHelper dbHelper = new DatabaseHelper(getContext(), userId);
                        SQLiteDatabase database = dbHelper.getWritableDatabase();
                        migrateLegacySettingsForUserLocked(dbHelper, database, userId);

                        // Upgrade to the latest version.
                        UpgradeController upgrader = new UpgradeController(userId);
                        upgrader.upgradeIfNeededLocked();

                        // Drop from memory if not a running user.
                        if (!mUserManager.isUserRunning(new UserHandle(userId))) {
                            removeUserStateLocked(userId, false);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        private void migrateLegacySettingsForUserIfNeededLocked(int userId) {
            // Every user has secure settings and if no file we need to migrate.
            final int secureKey = makeKey(SETTINGS_TYPE_SECURE, userId);
            File secureFile = getSettingsFile(secureKey);
            if (SettingsState.stateFileExists(secureFile)) {
                return;
            }

            DatabaseHelper dbHelper = new DatabaseHelper(getContext(), userId);
            SQLiteDatabase database = dbHelper.getWritableDatabase();

            migrateLegacySettingsForUserLocked(dbHelper, database, userId);
        }

        private void migrateLegacySettingsForUserLocked(DatabaseHelper dbHelper,
                SQLiteDatabase database, int userId) {
            // Move over the system settings.
            final int systemKey = makeKey(SETTINGS_TYPE_SYSTEM, userId);
            ensureSettingsStateLocked(systemKey);
            SettingsState systemSettings = mSettingsStates.get(systemKey);
            migrateLegacySettingsLocked(systemSettings, database, TABLE_SYSTEM);
            systemSettings.persistSyncLocked();

            // Move over the secure settings.
            // Do this after System settings, since this is the first thing we check when deciding
            // to skip over migration from db to xml for a secondary user.
            final int secureKey = makeKey(SETTINGS_TYPE_SECURE, userId);
            ensureSettingsStateLocked(secureKey);
            SettingsState secureSettings = mSettingsStates.get(secureKey);
            migrateLegacySettingsLocked(secureSettings, database, TABLE_SECURE);
            ensureSecureSettingAndroidIdSetLocked(secureSettings);
            secureSettings.persistSyncLocked();

            // Move over the global settings if owner.
            // Do this last, since this is the first thing we check when deciding
            // to skip over migration from db to xml for owner user.
            if (userId == UserHandle.USER_SYSTEM) {
                final int globalKey = makeKey(SETTINGS_TYPE_GLOBAL, userId);
                ensureSettingsStateLocked(globalKey);
                SettingsState globalSettings = mSettingsStates.get(globalKey);
                migrateLegacySettingsLocked(globalSettings, database, TABLE_GLOBAL);
                // If this was just created
                if (mSettingsCreationBuildId != null) {
                    globalSettings.insertSettingLocked(Settings.Global.DATABASE_CREATION_BUILDID,
                            mSettingsCreationBuildId, null, true,
                            SettingsState.SYSTEM_PACKAGE_NAME);
                }
                globalSettings.persistSyncLocked();
            }

            // Drop the database as now all is moved and persisted.
            if (DROP_DATABASE_ON_MIGRATION) {
                dbHelper.dropDatabase();
            } else {
                dbHelper.backupDatabase();
            }
        }

        private void migrateLegacySettingsLocked(SettingsState settingsState,
                SQLiteDatabase database, String table) {
            SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
            queryBuilder.setTables(table);

            Cursor cursor = queryBuilder.query(database, ALL_COLUMNS,
                    null, null, null, null, null);

            if (cursor == null) {
                return;
            }

            try {
                if (!cursor.moveToFirst()) {
                    return;
                }

                final int nameColumnIdx = cursor.getColumnIndex(Settings.NameValueTable.NAME);
                final int valueColumnIdx = cursor.getColumnIndex(Settings.NameValueTable.VALUE);

                settingsState.setVersionLocked(database.getVersion());

                while (!cursor.isAfterLast()) {
                    String name = cursor.getString(nameColumnIdx);
                    String value = cursor.getString(valueColumnIdx);
                    settingsState.insertSettingLocked(name, value, null, true,
                            SettingsState.SYSTEM_PACKAGE_NAME);
                    cursor.moveToNext();
                }
            } finally {
                cursor.close();
            }
        }

        private void ensureSecureSettingAndroidIdSetLocked(SettingsState secureSettings) {
            Setting value = secureSettings.getSettingLocked(Settings.Secure.ANDROID_ID);

            if (!value.isNull()) {
                return;
            }

            final int userId = getUserIdFromKey(secureSettings.mKey);

            final UserInfo user;
            final long identity = Binder.clearCallingIdentity();
            try {
                user = mUserManager.getUserInfo(userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            if (user == null) {
                // Can happen due to races when deleting users - treat as benign.
                return;
            }

            String androidId = Long.toHexString(new SecureRandom().nextLong());
            secureSettings.insertSettingLocked(Settings.Secure.ANDROID_ID, androidId,
                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);

            Slog.d(LOG_TAG, "Generated and saved new ANDROID_ID [" + androidId
                    + "] for user " + userId);

            // Write a drop box entry if it's a restricted profile
            if (user.isRestricted()) {
                DropBoxManager dbm = (DropBoxManager) getContext().getSystemService(
                        Context.DROPBOX_SERVICE);
                if (dbm != null && dbm.isTagEnabled(DROPBOX_TAG_USERLOG)) {
                    dbm.addText(DROPBOX_TAG_USERLOG, System.currentTimeMillis()
                            + "," + DROPBOX_TAG_USERLOG + "," + androidId + "\n");
                }
            }
        }

        private void notifyForSettingsChange(int key, String name) {
            // Increment the generation first, so observers always see the new value
            mGenerationRegistry.incrementGeneration(key);

            if (isGlobalSettingsKey(key)) {
                final long token = Binder.clearCallingIdentity();
                try {
                    if (Global.LOCATION_GLOBAL_KILL_SWITCH.equals(name)) {
                        // When the global kill switch is updated, send the
                        // change notification for the location setting.
                        notifyLocationChangeForRunningUsers();
                    }
                    notifyGlobalSettingChangeForRunningUsers(key, name);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } else {
                final int userId = getUserIdFromKey(key);
                final Uri uri = getNotificationUriFor(key, name);
                mHandler.obtainMessage(MyHandler.MSG_NOTIFY_URI_CHANGED,
                        userId, 0, uri).sendToTarget();
                if (isSecureSettingsKey(key)) {
                    maybeNotifyProfiles(getTypeFromKey(key), userId, uri, name,
                            sSecureCloneToManagedSettings);
                    maybeNotifyProfiles(SETTINGS_TYPE_SYSTEM, userId, uri, name,
                            sSystemCloneFromParentOnDependency.values());
                } else if (isSystemSettingsKey(key)) {
                    maybeNotifyProfiles(getTypeFromKey(key), userId, uri, name,
                            sSystemCloneToManagedSettings);
                }
            }

            // Always notify that our data changed
            mHandler.obtainMessage(MyHandler.MSG_NOTIFY_DATA_CHANGED).sendToTarget();
        }

        private void maybeNotifyProfiles(int type, int userId, Uri uri, String name,
                Collection<String> keysCloned) {
            if (keysCloned.contains(name)) {
                for (int profileId : mUserManager.getProfileIdsWithDisabled(userId)) {
                    // the notification for userId has already been sent.
                    if (profileId != userId) {
                        final int key = makeKey(type, profileId);
                        // Increment the generation first, so observers always see the new value
                        mGenerationRegistry.incrementGeneration(key);
                        mHandler.obtainMessage(MyHandler.MSG_NOTIFY_URI_CHANGED,
                                profileId, 0, uri).sendToTarget();
                    }
                }
            }
        }

        private void notifyGlobalSettingChangeForRunningUsers(int key, String name) {
            // Important: No need to update generation for each user as there
            // is a singleton generation entry for the global settings which
            // is already incremented be the caller.
            final Uri uri = getNotificationUriFor(key, name);
            final List<UserInfo> users = mUserManager.getUsers(/*excludeDying*/ true);
            for (int i = 0; i < users.size(); i++) {
                final int userId = users.get(i).id;
                if (mUserManager.isUserRunning(UserHandle.of(userId))) {
                    mHandler.obtainMessage(MyHandler.MSG_NOTIFY_URI_CHANGED,
                            userId, 0, uri).sendToTarget();
                }
            }
        }

        private void notifyLocationChangeForRunningUsers() {
            final List<UserInfo> users = mUserManager.getUsers(/*excludeDying=*/ true);

            for (int i = 0; i < users.size(); i++) {
                final int userId = users.get(i).id;

                if (!mUserManager.isUserRunning(UserHandle.of(userId))) {
                    continue;
                }

                // Increment the generation first, so observers always see the new value
                final int key = makeKey(SETTINGS_TYPE_SECURE, userId);
                mGenerationRegistry.incrementGeneration(key);

                final Uri uri = getNotificationUriFor(key, Secure.LOCATION_PROVIDERS_ALLOWED);
                mHandler.obtainMessage(MyHandler.MSG_NOTIFY_URI_CHANGED,
                        userId, 0, uri).sendToTarget();
            }
        }

        private boolean isGlobalSettingsKey(int key) {
            return getTypeFromKey(key) == SETTINGS_TYPE_GLOBAL;
        }

        private boolean isSystemSettingsKey(int key) {
            return getTypeFromKey(key) == SETTINGS_TYPE_SYSTEM;
        }

        private boolean isSecureSettingsKey(int key) {
            return getTypeFromKey(key) == SETTINGS_TYPE_SECURE;
        }

        private boolean isSsaidSettingsKey(int key) {
            return getTypeFromKey(key) == SETTINGS_TYPE_SSAID;
        }

        private File getSettingsFile(int key) {
            if (isGlobalSettingsKey(key)) {
                final int userId = getUserIdFromKey(key);
                return new File(Environment.getUserSystemDirectory(userId),
                        SETTINGS_FILE_GLOBAL);
            } else if (isSystemSettingsKey(key)) {
                final int userId = getUserIdFromKey(key);
                return new File(Environment.getUserSystemDirectory(userId),
                        SETTINGS_FILE_SYSTEM);
            } else if (isSecureSettingsKey(key)) {
                final int userId = getUserIdFromKey(key);
                return new File(Environment.getUserSystemDirectory(userId),
                        SETTINGS_FILE_SECURE);
            } else if (isSsaidSettingsKey(key)) {
                final int userId = getUserIdFromKey(key);
                return new File(Environment.getUserSystemDirectory(userId),
                        SETTINGS_FILE_SSAID);
            } else {
                throw new IllegalArgumentException("Invalid settings key:" + key);
            }
        }

        private Uri getNotificationUriFor(int key, String name) {
            if (isGlobalSettingsKey(key)) {
                return (name != null) ? Uri.withAppendedPath(Settings.Global.CONTENT_URI, name)
                        : Settings.Global.CONTENT_URI;
            } else if (isSecureSettingsKey(key)) {
                return (name != null) ? Uri.withAppendedPath(Settings.Secure.CONTENT_URI, name)
                        : Settings.Secure.CONTENT_URI;
            } else if (isSystemSettingsKey(key)) {
                return (name != null) ? Uri.withAppendedPath(Settings.System.CONTENT_URI, name)
                        : Settings.System.CONTENT_URI;
            } else {
                throw new IllegalArgumentException("Invalid settings key:" + key);
            }
        }

        private int getMaxBytesPerPackageForType(int type) {
            switch (type) {
                case SETTINGS_TYPE_GLOBAL:
                case SETTINGS_TYPE_SECURE:
                case SETTINGS_TYPE_SSAID: {
                    return SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED;
                }

                default: {
                    return SettingsState.MAX_BYTES_PER_APP_PACKAGE_LIMITED;
                }
            }
        }

        private final class MyHandler extends Handler {
            private static final int MSG_NOTIFY_URI_CHANGED = 1;
            private static final int MSG_NOTIFY_DATA_CHANGED = 2;

            public MyHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_NOTIFY_URI_CHANGED: {
                        final int userId = msg.arg1;
                        Uri uri = (Uri) msg.obj;
                        try {
                            getContext().getContentResolver().notifyChange(uri, null, true, userId);
                        } catch (SecurityException e) {
                            Slog.w(LOG_TAG, "Failed to notify for " + userId + ": " + uri, e);
                        }
                        if (DEBUG || true) {
                            Slog.v(LOG_TAG, "Notifying for " + userId + ": " + uri);
                        }
                    } break;

                    case MSG_NOTIFY_DATA_CHANGED: {
                        mBackupManager.dataChanged();
                    } break;
                }
            }
        }

        private final class UpgradeController {
            private static final int SETTINGS_VERSION = 163;

            private final int mUserId;

            public UpgradeController(int userId) {
                mUserId = userId;
            }

            public void upgradeIfNeededLocked() {
                // The version of all settings for a user is the same (all users have secure).
                SettingsState secureSettings = getSettingsLocked(
                        SETTINGS_TYPE_SECURE, mUserId);

                // Try an update from the current state.
                final int oldVersion = secureSettings.getVersionLocked();
                final int newVersion = SETTINGS_VERSION;

                // If up do date - done.
                if (oldVersion == newVersion) {
                    return;
                }

                // Try to upgrade.
                final int curVersion = onUpgradeLocked(mUserId, oldVersion, newVersion);

                // If upgrade failed start from scratch and upgrade.
                if (curVersion != newVersion) {
                    // Drop state we have for this user.
                    removeUserStateLocked(mUserId, true);

                    // Recreate the database.
                    DatabaseHelper dbHelper = new DatabaseHelper(getContext(), mUserId);
                    SQLiteDatabase database = dbHelper.getWritableDatabase();
                    dbHelper.recreateDatabase(database, newVersion, curVersion, oldVersion);

                    // Migrate the settings for this user.
                    migrateLegacySettingsForUserLocked(dbHelper, database, mUserId);

                    // Now upgrade should work fine.
                    onUpgradeLocked(mUserId, oldVersion, newVersion);

                    // Make a note what happened, so we don't wonder why data was lost
                    String reason = "Settings rebuilt! Current version: "
                            + curVersion + " while expected: " + newVersion;
                    getGlobalSettingsLocked().insertSettingLocked(
                            Settings.Global.DATABASE_DOWNGRADE_REASON,
                            reason, null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                }

                // Set the global settings version if owner.
                if (mUserId == UserHandle.USER_SYSTEM) {
                    SettingsState globalSettings = getSettingsLocked(
                            SETTINGS_TYPE_GLOBAL, mUserId);
                    globalSettings.setVersionLocked(newVersion);
                }

                // Set the secure settings version.
                secureSettings.setVersionLocked(newVersion);

                // Set the system settings version.
                SettingsState systemSettings = getSettingsLocked(
                        SETTINGS_TYPE_SYSTEM, mUserId);
                systemSettings.setVersionLocked(newVersion);
            }

            private SettingsState getGlobalSettingsLocked() {
                return getSettingsLocked(SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
            }

            private SettingsState getSecureSettingsLocked(int userId) {
                return getSettingsLocked(SETTINGS_TYPE_SECURE, userId);
            }

            private SettingsState getSsaidSettingsLocked(int userId) {
                return getSettingsLocked(SETTINGS_TYPE_SSAID, userId);
            }

            private SettingsState getSystemSettingsLocked(int userId) {
                return getSettingsLocked(SETTINGS_TYPE_SYSTEM, userId);
            }

            /**
             * You must perform all necessary mutations to bring the settings
             * for this user from the old to the new version. When you add a new
             * upgrade step you *must* update SETTINGS_VERSION.
             *
             * This is an example of moving a setting from secure to global.
             *
             * // v119: Example settings changes.
             * if (currentVersion == 118) {
             *     if (userId == UserHandle.USER_OWNER) {
             *         // Remove from the secure settings.
             *         SettingsState secureSettings = getSecureSettingsLocked(userId);
             *         String name = "example_setting_to_move";
             *         String value = secureSettings.getSetting(name);
             *         secureSettings.deleteSetting(name);
             *
             *         // Add to the global settings.
             *         SettingsState globalSettings = getGlobalSettingsLocked();
             *         globalSettings.insertSetting(name, value, SettingsState.SYSTEM_PACKAGE_NAME);
             *     }
             *
             *     // Update the current version.
             *     currentVersion = 119;
             * }
             */
            private int onUpgradeLocked(int userId, int oldVersion, int newVersion) {
                if (DEBUG) {
                    Slog.w(LOG_TAG, "Upgrading settings for user: " + userId + " from version: "
                            + oldVersion + " to version: " + newVersion);
                }

                int currentVersion = oldVersion;

                // v119: Reset zen + ringer mode.
                if (currentVersion == 118) {
                    if (userId == UserHandle.USER_SYSTEM) {
                        final SettingsState globalSettings = getGlobalSettingsLocked();
                        globalSettings.updateSettingLocked(Settings.Global.ZEN_MODE,
                                Integer.toString(Settings.Global.ZEN_MODE_OFF), null,
                                true, SettingsState.SYSTEM_PACKAGE_NAME);
                        globalSettings.updateSettingLocked(Settings.Global.MODE_RINGER,
                                Integer.toString(AudioManager.RINGER_MODE_NORMAL), null,
                                true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 119;
                }

                // v120: Add double tap to wake setting.
                if (currentVersion == 119) {
                    SettingsState secureSettings = getSecureSettingsLocked(userId);
                    secureSettings.insertSettingLocked(Settings.Secure.DOUBLE_TAP_TO_WAKE,
                            getContext().getResources().getBoolean(
                                    R.bool.def_double_tap_to_wake) ? "1" : "0", null, true,
                            SettingsState.SYSTEM_PACKAGE_NAME);

                    currentVersion = 120;
                }

                if (currentVersion == 120) {
                    // Before 121, we used a different string encoding logic.  We just bump the
                    // version here; SettingsState knows how to handle pre-version 120 files.
                    currentVersion = 121;
                }

                if (currentVersion == 121) {
                    // Version 122: allow OEMs to set a default payment component in resources.
                    // Note that we only write the default if no default has been set;
                    // if there is, we just leave the default at whatever it currently is.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    String defaultComponent = (getContext().getResources().getString(
                            R.string.def_nfc_payment_component));
                    Setting currentSetting = secureSettings.getSettingLocked(
                            Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT);
                    if (defaultComponent != null && !defaultComponent.isEmpty() &&
                        currentSetting.isNull()) {
                        secureSettings.insertSettingLocked(
                                Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                                defaultComponent, null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 122;
                }

                if (currentVersion == 122) {
                    // Version 123: Adding a default value for the ability to add a user from
                    // the lock screen.
                    if (userId == UserHandle.USER_SYSTEM) {
                        final SettingsState globalSettings = getGlobalSettingsLocked();
                        Setting currentSetting = globalSettings.getSettingLocked(
                                Settings.Global.ADD_USERS_WHEN_LOCKED);
                        if (currentSetting.isNull()) {
                            globalSettings.insertSettingLocked(
                                    Settings.Global.ADD_USERS_WHEN_LOCKED,
                                    getContext().getResources().getBoolean(
                                            R.bool.def_add_users_from_lockscreen) ? "1" : "0",
                                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }
                    currentVersion = 123;
                }

                if (currentVersion == 123) {
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    String defaultDisabledProfiles = (getContext().getResources().getString(
                            R.string.def_bluetooth_disabled_profiles));
                    globalSettings.insertSettingLocked(Settings.Global.BLUETOOTH_DISABLED_PROFILES,
                            defaultDisabledProfiles, null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    currentVersion = 124;
                }

                if (currentVersion == 124) {
                    // Version 124: allow OEMs to set a default value for whether IME should be
                    // shown when a physical keyboard is connected.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    Setting currentSetting = secureSettings.getSettingLocked(
                            Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD);
                    if (currentSetting.isNull()) {
                        secureSettings.insertSettingLocked(
                                Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                                getContext().getResources().getBoolean(
                                        R.bool.def_show_ime_with_hard_keyboard) ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 125;
                }

                if (currentVersion == 125) {
                    // Version 125: Allow OEMs to set the default VR service.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);

                    Setting currentSetting = secureSettings.getSettingLocked(
                            Settings.Secure.ENABLED_VR_LISTENERS);
                    if (currentSetting.isNull()) {
                        ArraySet<ComponentName> l =
                                SystemConfig.getInstance().getDefaultVrComponents();

                        if (l != null && !l.isEmpty()) {
                            StringBuilder b = new StringBuilder();
                            boolean start = true;
                            for (ComponentName c : l) {
                                if (!start) {
                                    b.append(':');
                                }
                                b.append(c.flattenToString());
                                start = false;
                            }
                            secureSettings.insertSettingLocked(
                                    Settings.Secure.ENABLED_VR_LISTENERS, b.toString(),
                                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }

                    }
                    currentVersion = 126;
                }

                if (currentVersion == 126) {
                    // Version 126: copy the primary values of LOCK_SCREEN_SHOW_NOTIFICATIONS and
                    // LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS into managed profile.
                    if (mUserManager.isManagedProfile(userId)) {
                        final SettingsState systemSecureSettings =
                                getSecureSettingsLocked(UserHandle.USER_SYSTEM);

                        final Setting showNotifications = systemSecureSettings.getSettingLocked(
                                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS);
                        if (!showNotifications.isNull()) {
                            final SettingsState secureSettings = getSecureSettingsLocked(userId);
                            secureSettings.insertSettingLocked(
                                    Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                                    showNotifications.getValue(), null, true,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }

                        final Setting allowPrivate = systemSecureSettings.getSettingLocked(
                                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
                        if (!allowPrivate.isNull()) {
                            final SettingsState secureSettings = getSecureSettingsLocked(userId);
                            secureSettings.insertSettingLocked(
                                    Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                                    allowPrivate.getValue(), null, true,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }
                    currentVersion = 127;
                }

                if (currentVersion == 127) {
                    // version 127 is no longer used.
                    currentVersion = 128;
                }

                if (currentVersion == 128) {
                    // Version 128: Removed
                    currentVersion = 129;
                }

                if (currentVersion == 129) {
                    // default longpress timeout changed from 500 to 400. If unchanged from the old
                    // default, update to the new default.
                    final SettingsState systemSecureSettings =
                            getSecureSettingsLocked(userId);
                    final String oldValue = systemSecureSettings.getSettingLocked(
                            Settings.Secure.LONG_PRESS_TIMEOUT).getValue();
                    if (TextUtils.equals("500", oldValue)) {
                        systemSecureSettings.insertSettingLocked(
                                Settings.Secure.LONG_PRESS_TIMEOUT,
                                String.valueOf(getContext().getResources().getInteger(
                                        R.integer.def_long_press_timeout_millis)),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 130;
                }

                if (currentVersion == 130) {
                    // Split Ambient settings
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    boolean dozeExplicitlyDisabled = "0".equals(secureSettings.
                            getSettingLocked(Settings.Secure.DOZE_ENABLED).getValue());

                    if (dozeExplicitlyDisabled) {
                        secureSettings.insertSettingLocked(Settings.Secure.DOZE_PULSE_ON_PICK_UP,
                                "0", null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        secureSettings.insertSettingLocked(Settings.Secure.DOZE_PULSE_ON_DOUBLE_TAP,
                                "0", null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 131;
                }

                if (currentVersion == 131) {
                    // Initialize new multi-press timeout to default value
                    final SettingsState systemSecureSettings = getSecureSettingsLocked(userId);
                    final String oldValue = systemSecureSettings.getSettingLocked(
                            Settings.Secure.MULTI_PRESS_TIMEOUT).getValue();
                    if (TextUtils.equals(null, oldValue)) {
                        systemSecureSettings.insertSettingLocked(
                                Settings.Secure.MULTI_PRESS_TIMEOUT,
                                String.valueOf(getContext().getResources().getInteger(
                                        R.integer.def_multi_press_timeout_millis)),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 132;
                }

                if (currentVersion == 132) {
                    // Version 132: Allow managed profile to optionally use the parent's ringtones
                    final SettingsState systemSecureSettings = getSecureSettingsLocked(userId);
                    String defaultSyncParentSounds = (getContext().getResources()
                            .getBoolean(R.bool.def_sync_parent_sounds) ? "1" : "0");
                    systemSecureSettings.insertSettingLocked(
                            Settings.Secure.SYNC_PARENT_SOUNDS, defaultSyncParentSounds,
                            null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    currentVersion = 133;
                }

                if (currentVersion == 133) {
                    // Version 133: Add default end button behavior
                    final SettingsState systemSettings = getSystemSettingsLocked(userId);
                    if (systemSettings.getSettingLocked(Settings.System.END_BUTTON_BEHAVIOR) ==
                            null) {
                        String defaultEndButtonBehavior = Integer.toString(getContext()
                                .getResources().getInteger(R.integer.def_end_button_behavior));
                        systemSettings.insertSettingLocked(Settings.System.END_BUTTON_BEHAVIOR,
                                defaultEndButtonBehavior, null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 134;
                }

                if (currentVersion == 134) {
                    // Remove setting that specifies if magnification values should be preserved.
                    // This setting defaulted to true and never has a UI.
                    getSecureSettingsLocked(userId).deleteSettingLocked(
                            Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_AUTO_UPDATE);
                    currentVersion = 135;
                }

                if (currentVersion == 135) {
                    // Version 135 no longer used.
                    currentVersion = 136;
                }

                if (currentVersion == 136) {
                    // Version 136: Store legacy SSAID for all apps currently installed on the
                    // device as first step in migrating SSAID to be unique per application.

                    final boolean isUpgrade;
                    try {
                        isUpgrade = mPackageManager.isUpgrade();
                    } catch (RemoteException e) {
                        throw new IllegalStateException("Package manager not available");
                    }
                    // Only retain legacy ssaid if the device is performing an OTA. After wiping
                    // user data or first boot on a new device should use new ssaid generation.
                    if (isUpgrade) {
                        // Retrieve the legacy ssaid from the secure settings table.
                        final Setting legacySsaidSetting = getSettingLocked(SETTINGS_TYPE_SECURE,
                                userId, Settings.Secure.ANDROID_ID);
                        if (legacySsaidSetting == null || legacySsaidSetting.isNull()
                                || legacySsaidSetting.getValue() == null) {
                            throw new IllegalStateException("Legacy ssaid not accessible");
                        }
                        final String legacySsaid = legacySsaidSetting.getValue();

                        // Fill each uid with the legacy ssaid to be backwards compatible.
                        final List<PackageInfo> packages;
                        try {
                            packages = mPackageManager.getInstalledPackages(
                                PackageManager.MATCH_UNINSTALLED_PACKAGES,
                                userId).getList();
                        } catch (RemoteException e) {
                            throw new IllegalStateException("Package manager not available");
                        }

                        final SettingsState ssaidSettings = getSsaidSettingsLocked(userId);
                        for (PackageInfo info : packages) {
                            // Check if the UID already has an entry in the table.
                            final String uid = Integer.toString(info.applicationInfo.uid);
                            final Setting ssaid = ssaidSettings.getSettingLocked(uid);

                            if (ssaid.isNull() || ssaid.getValue() == null) {
                                // Android Id doesn't exist for this package so create it.
                                ssaidSettings.insertSettingLocked(uid, legacySsaid, null, true,
                                        info.packageName);
                                if (DEBUG) {
                                    Slog.d(LOG_TAG, "Keep the legacy ssaid for uid=" + uid);
                                }
                            }
                        }
                    }

                    currentVersion = 137;
                }
                if (currentVersion == 137) {
                    // Version 138: Settings.Secure#INSTALL_NON_MARKET_APPS is deprecated and its
                    // default value set to 1. The user can no longer change the value of this
                    // setting through the UI.
                    final SettingsState secureSetting = getSecureSettingsLocked(userId);
                    if (!mUserManager.hasUserRestriction(
                            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, UserHandle.of(userId))
                            && secureSetting.getSettingLocked(
                            Settings.Secure.INSTALL_NON_MARKET_APPS).getValue().equals("0")) {

                        secureSetting.insertSettingLocked(Settings.Secure.INSTALL_NON_MARKET_APPS,
                                "1", null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        // For managed profiles with profile owners, DevicePolicyManagerService
                        // may want to set the user restriction in this case
                        secureSetting.insertSettingLocked(
                                Settings.Secure.UNKNOWN_SOURCES_DEFAULT_REVERSED, "1", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 138;
                }

                if (currentVersion == 138) {
                    // Version 139: Removed.
                    currentVersion = 139;
                }

                if (currentVersion == 139) {
                    // Version 140: Settings.Secure#ACCESSIBILITY_SPEAK_PASSWORD is deprecated and
                    // the user can no longer change the value of this setting through the UI.
                    // Force to true.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    secureSettings.updateSettingLocked(Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD,
                            "1", null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    currentVersion = 140;
                }

                if (currentVersion == 140) {
                    // Version 141: Removed
                    currentVersion = 141;
                }

                if (currentVersion == 141) {
                    // This implementation was incorrectly setting the current value of
                    // settings changed by non-system packages as the default which default
                    // is set by the system. We add a new upgrade step at the end to properly
                    // handle this case which would also fix incorrect changes made by the
                    // old implementation of this step.
                    currentVersion = 142;
                }

                if (currentVersion == 142) {
                    // Version 143: Set a default value for Wi-Fi wakeup feature.
                    if (userId == UserHandle.USER_SYSTEM) {
                        final SettingsState globalSettings = getGlobalSettingsLocked();
                        Setting currentSetting = globalSettings.getSettingLocked(
                                Settings.Global.WIFI_WAKEUP_ENABLED);
                        if (currentSetting.isNull()) {
                            globalSettings.insertSettingLocked(
                                    Settings.Global.WIFI_WAKEUP_ENABLED,
                                    getContext().getResources().getBoolean(
                                            R.bool.def_wifi_wakeup_enabled) ? "1" : "0",
                                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }

                    currentVersion = 143;
                }

                if (currentVersion == 143) {
                    // Version 144: Set a default value for Autofill service.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting currentSetting = secureSettings
                            .getSettingLocked(Settings.Secure.AUTOFILL_SERVICE);
                    if (currentSetting.isNull()) {
                        final String defaultValue = getContext().getResources().getString(
                                com.android.internal.R.string.config_defaultAutofillService);
                        if (defaultValue != null) {
                            Slog.d(LOG_TAG, "Setting [" + defaultValue + "] as Autofill Service "
                                    + "for user " + userId);
                            secureSettings.insertSettingLocked(Settings.Secure.AUTOFILL_SERVICE,
                                    defaultValue, null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }

                    currentVersion = 144;
                }

                if (currentVersion == 144) {
                    // Version 145: Removed
                    currentVersion = 145;
                }

                if (currentVersion == 145) {
                    // Version 146: In step 142 we had a bug where incorrectly
                    // some settings were considered system set and as a result
                    // made the default and marked as the default being set by
                    // the system. Here reevaluate the default and default system
                    // set flags. This would both fix corruption by the old impl
                    // of step 142 and also properly handle devices which never
                    // run 142.
                    if (userId == UserHandle.USER_SYSTEM) {
                        SettingsState globalSettings = getGlobalSettingsLocked();
                        ensureLegacyDefaultValueAndSystemSetUpdatedLocked(globalSettings, userId);
                        globalSettings.persistSyncLocked();
                    }

                    SettingsState secureSettings = getSecureSettingsLocked(mUserId);
                    ensureLegacyDefaultValueAndSystemSetUpdatedLocked(secureSettings, userId);
                    secureSettings.persistSyncLocked();

                    SettingsState systemSettings = getSystemSettingsLocked(mUserId);
                    ensureLegacyDefaultValueAndSystemSetUpdatedLocked(systemSettings, userId);
                    systemSettings.persistSyncLocked();

                    currentVersion = 146;
                }

                if (currentVersion == 146) {
                    // Version 147: Removed. (This version previously allowed showing the
                    // "wifi_wakeup_available" setting).
                    // The setting that was added here is deleted in 153.
                    currentVersion = 147;
                }

                if (currentVersion == 147) {
                    // Version 148: Set the default value for DEFAULT_RESTRICT_BACKGROUND_DATA.
                    if (userId == UserHandle.USER_SYSTEM) {
                        final SettingsState globalSettings = getGlobalSettingsLocked();
                        final Setting currentSetting = globalSettings.getSettingLocked(
                                Global.DEFAULT_RESTRICT_BACKGROUND_DATA);
                        if (currentSetting.isNull()) {
                            globalSettings.insertSettingLocked(
                                    Global.DEFAULT_RESTRICT_BACKGROUND_DATA,
                                    getContext().getResources().getBoolean(
                                            R.bool.def_restrict_background_data) ? "1" : "0",
                                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }
                    currentVersion = 148;
                }

                if (currentVersion == 148) {
                    // Version 149: Set the default value for BACKUP_MANAGER_CONSTANTS.
                    final SettingsState systemSecureSettings = getSecureSettingsLocked(userId);
                    final String oldValue = systemSecureSettings.getSettingLocked(
                            Settings.Secure.BACKUP_MANAGER_CONSTANTS).getValue();
                    if (TextUtils.equals(null, oldValue)) {
                        final String defaultValue = getContext().getResources().getString(
                                R.string.def_backup_manager_constants);
                        if (!TextUtils.isEmpty(defaultValue)) {
                            systemSecureSettings.insertSettingLocked(
                                    Settings.Secure.BACKUP_MANAGER_CONSTANTS, defaultValue, null,
                                    true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }
                    currentVersion = 149;
                }

                if (currentVersion == 149) {
                    // Version 150: Set a default value for mobile data always on
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final Setting currentSetting = globalSettings.getSettingLocked(
                            Settings.Global.MOBILE_DATA_ALWAYS_ON);
                    if (currentSetting.isNull()) {
                        globalSettings.insertSettingLocked(
                                Settings.Global.MOBILE_DATA_ALWAYS_ON,
                                getContext().getResources().getBoolean(
                                        R.bool.def_mobile_data_always_on) ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 150;
                }

                if (currentVersion == 150) {
                    // Version 151: Reset rotate locked setting for upgrading users
                    final SettingsState systemSettings = getSystemSettingsLocked(userId);
                    systemSettings.insertSettingLocked(
                            Settings.System.ACCELEROMETER_ROTATION,
                            getContext().getResources().getBoolean(
                                    R.bool.def_accelerometer_rotation) ? "1" : "0",
                            null, true, SettingsState.SYSTEM_PACKAGE_NAME);

                    currentVersion = 151;
                }

                if (currentVersion == 151) {
                    // Version 152: Removed. (This version made the setting for wifi_wakeup enabled
                    // by default but it is now no longer configurable).
                    // The setting updated here is deleted in 153.
                    currentVersion = 152;
                }

                if (currentVersion == 152) {
                    getGlobalSettingsLocked().deleteSettingLocked("wifi_wakeup_available");
                    currentVersion = 153;
                }

                if (currentVersion == 153) {
                    // Version 154: Read notification badge configuration from config.
                    // If user has already set the value, don't do anything.
                    final SettingsState systemSecureSettings = getSecureSettingsLocked(userId);
                    final Setting showNotificationBadges = systemSecureSettings.getSettingLocked(
                            Settings.Secure.NOTIFICATION_BADGING);
                    if (showNotificationBadges.isNull()) {
                        final boolean defaultValue = getContext().getResources().getBoolean(
                                com.android.internal.R.bool.config_notificationBadging);
                        systemSecureSettings.insertSettingLocked(
                                Secure.NOTIFICATION_BADGING,
                                defaultValue ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 154;
                }

                if (currentVersion == 154) {
                    // Version 155: Set the default value for BACKUP_LOCAL_TRANSPORT_PARAMETERS.
                    final SettingsState systemSecureSettings = getSecureSettingsLocked(userId);
                    final String oldValue = systemSecureSettings.getSettingLocked(
                            Settings.Secure.BACKUP_LOCAL_TRANSPORT_PARAMETERS).getValue();
                    if (TextUtils.equals(null, oldValue)) {
                        final String defaultValue = getContext().getResources().getString(
                                R.string.def_backup_local_transport_parameters);
                        if (!TextUtils.isEmpty(defaultValue)) {
                            systemSecureSettings.insertSettingLocked(
                                    Settings.Secure.BACKUP_LOCAL_TRANSPORT_PARAMETERS, defaultValue,
                                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }

                    }
                    currentVersion = 155;
                }

                if (currentVersion == 155) {
                    // Version 156: Set the default value for CHARGING_STARTED_SOUND.
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final String oldValue = globalSettings.getSettingLocked(
                            Global.CHARGING_STARTED_SOUND).getValue();
                    final String oldDefault = getContext().getResources().getString(
                            R.string.def_wireless_charging_started_sound);
                    if (TextUtils.equals(null, oldValue)
                            || TextUtils.equals(oldValue, oldDefault)) {
                        final String defaultValue = getContext().getResources().getString(
                                R.string.def_charging_started_sound);
                        if (!TextUtils.isEmpty(defaultValue)) {
                            globalSettings.insertSettingLocked(
                                    Settings.Global.CHARGING_STARTED_SOUND, defaultValue,
                                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }

                    }
                    currentVersion = 156;
                }

                if (currentVersion == 156) {
                    // Version 157: Set a default value for zen duration
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final Setting currentSetting = globalSettings.getSettingLocked(
                            Global.ZEN_DURATION);
                    if (currentSetting.isNull()) {
                        String defaultZenDuration = Integer.toString(getContext()
                                .getResources().getInteger(R.integer.def_zen_duration));
                        globalSettings.insertSettingLocked(
                                Global.ZEN_DURATION, defaultZenDuration,
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 157;
                }

                if (currentVersion == 157) {
                    // Version 158: Set default value for BACKUP_AGENT_TIMEOUT_PARAMETERS.
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final String oldValue = globalSettings.getSettingLocked(
                            Settings.Global.BACKUP_AGENT_TIMEOUT_PARAMETERS).getValue();
                    if (TextUtils.equals(null, oldValue)) {
                        final String defaultValue = getContext().getResources().getString(
                                R.string.def_backup_agent_timeout_parameters);
                        if (!TextUtils.isEmpty(defaultValue)) {
                            globalSettings.insertSettingLocked(
                                    Settings.Global.BACKUP_AGENT_TIMEOUT_PARAMETERS, defaultValue,
                                    null, true,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }
                    currentVersion = 158;
                }

                if (currentVersion == 158) {
                    // Remove setting that specifies wifi bgscan throttling params
                    getGlobalSettingsLocked().deleteSettingLocked(
                        "wifi_scan_background_throttle_interval_ms");
                    getGlobalSettingsLocked().deleteSettingLocked(
                        "wifi_scan_background_throttle_package_whitelist");
                    currentVersion = 159;
                }

                if (currentVersion == 159) {
                    // Version 160: Hiding notifications from the lockscreen is only available as
                    // primary user option, profiles can only make them redacted. If a profile was
                    // configured to not show lockscreen notifications, ensure that at the very
                    // least these will be come hidden.
                    if (mUserManager.isManagedProfile(userId)) {
                        final SettingsState secureSettings = getSecureSettingsLocked(userId);
                        Setting showNotifications = secureSettings.getSettingLocked(
                            Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS);
                        // The default value is "1", check if user has turned it off.
                        if ("0".equals(showNotifications.getValue())) {
                            secureSettings.insertSettingLocked(
                                Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, "0",
                                null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                        // The setting is no longer valid for managed profiles, it should be
                        // treated as if it was set to "1".
                        secureSettings.deleteSettingLocked(Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS);
                    }
                    currentVersion = 160;
                }

                if (currentVersion == 160) {
                    // Version 161: Set the default value for
                    // MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY and
                    // SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT
                    final SettingsState globalSettings = getGlobalSettingsLocked();

                    String oldValue = globalSettings.getSettingLocked(
                            Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY).getValue();
                    if (TextUtils.equals(null, oldValue)) {
                        globalSettings.insertSettingLocked(
                                Settings.Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY,
                                Integer.toString(getContext().getResources().getInteger(
                                        R.integer.def_max_sound_trigger_detection_service_ops_per_day)),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    oldValue = globalSettings.getSettingLocked(
                            Global.SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT).getValue();
                    if (TextUtils.equals(null, oldValue)) {
                        globalSettings.insertSettingLocked(
                                Settings.Global.SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT,
                                Integer.toString(getContext().getResources().getInteger(
                                        R.integer.def_sound_trigger_detection_service_op_timeout)),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 161;
                }

                if (currentVersion == 161) {
                    // Version 161: Add a gesture for silencing phones
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting currentSetting = secureSettings.getSettingLocked(
                            Secure.VOLUME_HUSH_GESTURE);
                    if (currentSetting.isNull()) {
                        secureSettings.insertSettingLocked(
                                Secure.VOLUME_HUSH_GESTURE,
                                Integer.toString(Secure.VOLUME_HUSH_VIBRATE),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 162;
                }

                if (currentVersion == 162) {
                    // Version 162: Add a gesture for silencing phones
                    final SettingsState settings = getGlobalSettingsLocked();
                    final Setting currentSetting = settings.getSettingLocked(
                            Global.SHOW_ZEN_UPGRADE_NOTIFICATION);
                    if (!currentSetting.isNull()
                            && TextUtils.equals("0", currentSetting.getValue())) {
                        settings.insertSettingLocked(
                                Global.SHOW_ZEN_UPGRADE_NOTIFICATION, "1",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 163;
                }

                // vXXX: Add new settings above this point.

                if (currentVersion != newVersion) {
                    Slog.wtf("SettingsProvider", "warning: upgrading settings database to version "
                            + newVersion + " left it at "
                            + currentVersion +
                            " instead; this is probably a bug. Did you update SETTINGS_VERSION?",
                            new Throwable());
                    if (DEBUG) {
                        throw new RuntimeException("db upgrade error");
                    }
                }

                // Return the current version.
                return currentVersion;
            }
        }

        private void ensureLegacyDefaultValueAndSystemSetUpdatedLocked(SettingsState settings,
                int userId) {
            List<String> names = settings.getSettingNamesLocked();
            final int nameCount = names.size();
            for (int i = 0; i < nameCount; i++) {
                String name = names.get(i);
                Setting setting = settings.getSettingLocked(name);

                // In the upgrade case we pretend the call is made from the app
                // that made the last change to the setting to properly determine
                // whether the call has been made by a system component.
                int callingUid = -1;
                try {
                    callingUid = mPackageManager.getPackageUid(setting.getPackageName(), 0, userId);
                } catch (RemoteException e) {
                    /* ignore - handled below */
                }
                if (callingUid < 0) {
                    Slog.e(LOG_TAG, "Unknown package: " + setting.getPackageName());
                    continue;
                }
                try {
                    final boolean systemSet = SettingsState.isSystemPackage(getContext(),
                            setting.getPackageName(), callingUid);
                    if (systemSet) {
                        settings.insertSettingLocked(name, setting.getValue(),
                                setting.getTag(), true, setting.getPackageName());
                    } else if (setting.getDefaultValue() != null && setting.isDefaultFromSystem()) {
                        // We had a bug where changes by non-system packages were marked
                        // as system made and as a result set as the default. Therefore, if
                        // the package changed the setting last is not a system one but the
                        // setting is marked as its default coming from the system we clear
                        // the default and clear the system set flag.
                        settings.resetSettingDefaultValueLocked(name);
                    }
                } catch (IllegalStateException e) {
                    // If the package goes over its quota during the upgrade, don't
                    // crash but just log the error as the system does the upgrade.
                    Slog.e(LOG_TAG, "Error upgrading setting: " + setting.getName(), e);

                }
            }
        }
    }
}
