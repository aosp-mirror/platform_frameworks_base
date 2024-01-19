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
import static android.provider.DeviceConfig.SYNC_DISABLED_MODE_NONE;
import static android.provider.DeviceConfig.SYNC_DISABLED_MODE_PERSISTENT;
import static android.provider.DeviceConfig.SYNC_DISABLED_MODE_UNTIL_REBOOT;
import static android.provider.Settings.SET_ALL_RESULT_DISABLED;
import static android.provider.Settings.SET_ALL_RESULT_FAILURE;
import static android.provider.Settings.SET_ALL_RESULT_SUCCESS;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_MAGNIFICATION_CONTROLLER;
import static android.provider.Settings.Secure.NOTIFICATION_BUBBLES;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON_OVERLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.util.AccessibilityUtils.ACCESSIBILITY_MENU_IN_SYSTEM;
import static com.android.providers.settings.SettingsState.FALLBACK_FILE_SUFFIX;
import static com.android.providers.settings.SettingsState.getTypeFromKey;
import static com.android.providers.settings.SettingsState.getUserIdFromKey;
import static com.android.providers.settings.SettingsState.isConfigSettingsKey;
import static com.android.providers.settings.SettingsState.isGlobalSettingsKey;
import static com.android.providers.settings.SettingsState.isSecureSettingsKey;
import static com.android.providers.settings.SettingsState.isSystemSettingsKey;
import static com.android.providers.settings.SettingsState.makeKey;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.backup.BackupManager;
import android.app.compat.CompatChanges;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
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
import android.media.IRingtonePlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IUserRestrictionsListener;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemConfigManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Settings.Config.SyncDisabledMode;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.provider.Settings.SetAllResult;
import android.provider.settings.validators.SystemSettingsValidators;
import android.provider.settings.validators.Validator;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.accessibility.util.AccessibilityUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.display.RefreshRateSettingsUtils;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FrameworkStatsLog;
import com.android.providers.settings.SettingsState.Setting;

import com.google.android.collect.Sets;

import libcore.util.HexEncoding;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    public static final String TABLE_SYSTEM = "system";
    public static final String TABLE_SECURE = "secure";
    public static final String TABLE_GLOBAL = "global";
    public static final String TABLE_SSAID = "ssaid";
    public static final String TABLE_CONFIG = "config";

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

    private static final String[] LEGACY_SQL_COLUMNS = new String[] {
            Settings.NameValueTable._ID,
            Settings.NameValueTable.NAME,
            Settings.NameValueTable.VALUE,
    };

    private static final String[] ALL_COLUMNS = new String[] {
            Settings.NameValueTable._ID,
            Settings.NameValueTable.NAME,
            Settings.NameValueTable.VALUE,
            Settings.NameValueTable.IS_PRESERVED_IN_RESTORE,
    };

    public static final int SETTINGS_TYPE_GLOBAL = SettingsState.SETTINGS_TYPE_GLOBAL;
    public static final int SETTINGS_TYPE_SYSTEM = SettingsState.SETTINGS_TYPE_SYSTEM;
    public static final int SETTINGS_TYPE_SECURE = SettingsState.SETTINGS_TYPE_SECURE;
    public static final int SETTINGS_TYPE_SSAID = SettingsState.SETTINGS_TYPE_SSAID;
    public static final int SETTINGS_TYPE_CONFIG = SettingsState.SETTINGS_TYPE_CONFIG;

    private static final int CHANGE_TYPE_INSERT = 0;
    private static final int CHANGE_TYPE_DELETE = 1;
    private static final int CHANGE_TYPE_UPDATE = 2;
    private static final int CHANGE_TYPE_RESET = 3;

    private static final Bundle NULL_SETTING_BUNDLE = Bundle.forPair(
            Settings.NameValueTable.VALUE, null);

    public static final String RESULT_ROWS_DELETED = "result_rows_deleted";
    public static final String RESULT_SETTINGS_LIST = "result_settings_list";

    public static final String SETTINGS_PROVIDER_JOBS_NS = "SettingsProviderJobsNamespace";
    // Used for scheduling jobs to make a copy for the settings files
    public static final int WRITE_FALLBACK_SETTINGS_FILES_JOB_ID = 1;
    public static final long ONE_DAY_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L;

    // Overlay specified settings allowlisted for Instant Apps
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

    // Per all users global settings that moved to the per user system settings.
    static final Set<String> sGlobalMovedToSystemSettings = new ArraySet<>();
    static {
        Settings.Global.getMovedToSystemSettings(sGlobalMovedToSystemSettings);
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

    private static final Set<String> sAllSecureSettings = new ArraySet<>();
    private static final Set<String> sReadableSecureSettings = new ArraySet<>();
    private static final ArrayMap<String, Integer> sReadableSecureSettingsWithMaxTargetSdk =
            new ArrayMap<>();
    static {
        Settings.Secure.getPublicSettings(sAllSecureSettings, sReadableSecureSettings,
                sReadableSecureSettingsWithMaxTargetSdk);
    }

    private static final Set<String> sAllSystemSettings = new ArraySet<>();
    private static final Set<String> sReadableSystemSettings = new ArraySet<>();
    private static final ArrayMap<String, Integer> sReadableSystemSettingsWithMaxTargetSdk =
            new ArrayMap<>();
    static {
        Settings.System.getPublicSettings(sAllSystemSettings, sReadableSystemSettings,
                sReadableSystemSettingsWithMaxTargetSdk);
    }

    private static final Set<String> sAllGlobalSettings = new ArraySet<>();
    private static final Set<String> sReadableGlobalSettings = new ArraySet<>();
    private static final ArrayMap<String, Integer> sReadableGlobalSettingsWithMaxTargetSdk =
            new ArrayMap<>();
    static {
        Settings.Global.getPublicSettings(sAllGlobalSettings, sReadableGlobalSettings,
                sReadableGlobalSettingsWithMaxTargetSdk);
    }

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private RemoteCallback mConfigMonitorCallback;

    @GuardedBy("mLock")
    private SettingsRegistry mSettingsRegistry;

    @GuardedBy("mLock")
    private HandlerThread mHandlerThread;

    @GuardedBy("mLock")
    private Handler mHandler;

    // We have to call in the user manager with no lock held,
    private volatile UserManager mUserManager;

    // We have to call in the package manager with no lock held,
    private volatile IPackageManager mPackageManager;

    private volatile SystemConfigManager mSysConfigManager;

    @GuardedBy("mLock")
    private boolean mSyncConfigDisabledUntilReboot;

    @ChangeId
    @EnabledSince(targetSdkVersion=android.os.Build.VERSION_CODES.S)
    private static final long ENFORCE_READ_PERMISSION_FOR_MULTI_SIM_DATA_CALL = 172670679L;

    @Override
    public boolean onCreate() {
        Settings.setInSystemServer();

        synchronized (mLock) {
            mUserManager = UserManager.get(getContext());
            mPackageManager = AppGlobals.getPackageManager();
            mSysConfigManager = getContext().getSystemService(SystemConfigManager.class);
            mHandlerThread = new HandlerThread(LOG_TAG,
                    Process.THREAD_PRIORITY_BACKGROUND);
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
            mSettingsRegistry = new SettingsRegistry(mHandlerThread.getLooper());
        }
        SettingsState.cacheSystemPackageNamesAndSystemSignature(getContext());
        synchronized (mLock) {
            mSettingsRegistry.migrateAllLegacySettingsIfNeededLocked();
            for (UserInfo user : mUserManager.getAliveUsers()) {
                mSettingsRegistry.ensureSettingsForUserLocked(user.id);
            }
            mSettingsRegistry.syncSsaidTableOnStartLocked();
        }
        mHandler.post(() -> {
            registerBroadcastReceivers();
            startWatchingUserRestrictionChanges();
        });
        ServiceManager.addService("settings", new SettingsService(this));
        ServiceManager.addService("device_config", new DeviceConfigService(this));
        return true;
    }

    @Override
    public Bundle call(String method, String name, Bundle args) {
        final int requestingUserId = getRequestingUserId(args);
        switch (method) {
            case Settings.CALL_METHOD_GET_CONFIG -> {
                Setting setting = getConfigSetting(name);
                return packageValueForCallResult(SETTINGS_TYPE_CONFIG, name, requestingUserId,
                        setting, isTrackingGeneration(args));
            }
            case Settings.CALL_METHOD_GET_GLOBAL -> {
                Setting setting = getGlobalSetting(name);
                return packageValueForCallResult(SETTINGS_TYPE_GLOBAL, name, requestingUserId,
                        setting, isTrackingGeneration(args));
            }
            case Settings.CALL_METHOD_GET_SECURE -> {
                Setting setting = getSecureSetting(name, requestingUserId);
                return packageValueForCallResult(SETTINGS_TYPE_SECURE, name, requestingUserId,
                        setting, isTrackingGeneration(args));
            }
            case Settings.CALL_METHOD_GET_SYSTEM -> {
                Setting setting = getSystemSetting(name, requestingUserId);
                return packageValueForCallResult(SETTINGS_TYPE_SYSTEM, name, requestingUserId,
                        setting, isTrackingGeneration(args));
            }
            case Settings.CALL_METHOD_PUT_CONFIG -> {
                String value = getSettingValue(args);
                final boolean makeDefault = getSettingMakeDefault(args);
                insertConfigSetting(name, value, makeDefault);
            }
            case Settings.CALL_METHOD_PUT_GLOBAL -> {
                String value = getSettingValue(args);
                String tag = getSettingTag(args);
                final boolean makeDefault = getSettingMakeDefault(args);
                final boolean overrideableByRestore = getSettingOverrideableByRestore(args);
                insertGlobalSetting(name, value, tag, makeDefault, requestingUserId, false,
                        overrideableByRestore);
            }
            case Settings.CALL_METHOD_PUT_SECURE -> {
                String value = getSettingValue(args);
                String tag = getSettingTag(args);
                final boolean makeDefault = getSettingMakeDefault(args);
                final boolean overrideableByRestore = getSettingOverrideableByRestore(args);
                insertSecureSetting(name, value, tag, makeDefault, requestingUserId, false,
                        overrideableByRestore);
            }
            case Settings.CALL_METHOD_PUT_SYSTEM -> {
                String value = getSettingValue(args);
                boolean overrideableByRestore = getSettingOverrideableByRestore(args);
                insertSystemSetting(name, value, requestingUserId, overrideableByRestore);
            }
            case Settings.CALL_METHOD_SET_ALL_CONFIG -> {
                String prefix = getSettingPrefix(args);
                Map<String, String> flags = getSettingFlags(args);
                Bundle result = new Bundle();
                result.putInt(Settings.KEY_CONFIG_SET_ALL_RETURN,
                        setAllConfigSettings(prefix, flags));
                return result;
            }
            case Settings.CALL_METHOD_SET_SYNC_DISABLED_MODE_CONFIG -> {
                final int mode = getSyncDisabledMode(args);
                setSyncDisabledModeConfig(mode);
            }
            case Settings.CALL_METHOD_GET_SYNC_DISABLED_MODE_CONFIG -> {
                Bundle result = new Bundle();
                result.putInt(Settings.KEY_CONFIG_GET_SYNC_DISABLED_MODE_RETURN,
                        getSyncDisabledModeConfig());
                return result;
            }
            case Settings.CALL_METHOD_RESET_CONFIG -> {
                final int mode = getResetModeEnforcingPermission(args);
                String prefix = getSettingPrefix(args);
                resetConfigSetting(mode, prefix);
            }
            case Settings.CALL_METHOD_RESET_GLOBAL -> {
                final int mode = getResetModeEnforcingPermission(args);
                String tag = getSettingTag(args);
                resetGlobalSetting(requestingUserId, mode, tag);
            }
            case Settings.CALL_METHOD_RESET_SECURE -> {
                final int mode = getResetModeEnforcingPermission(args);
                String tag = getSettingTag(args);
                resetSecureSetting(requestingUserId, mode, tag);
            }
            case Settings.CALL_METHOD_RESET_SYSTEM -> {
                final int mode = getResetModeEnforcingPermission(args);
                String tag = getSettingTag(args);
                resetSystemSetting(requestingUserId, mode, tag);
            }
            case Settings.CALL_METHOD_DELETE_CONFIG -> {
                int rows = deleteConfigSetting(name) ? 1 : 0;
                Bundle result = new Bundle();
                result.putInt(RESULT_ROWS_DELETED, rows);
                return result;
            }
            case Settings.CALL_METHOD_DELETE_GLOBAL -> {
                int rows = deleteGlobalSetting(name, requestingUserId, false) ? 1 : 0;
                Bundle result = new Bundle();
                result.putInt(RESULT_ROWS_DELETED, rows);
                return result;
            }
            case Settings.CALL_METHOD_DELETE_SECURE -> {
                int rows = deleteSecureSetting(name, requestingUserId, false) ? 1 : 0;
                Bundle result = new Bundle();
                result.putInt(RESULT_ROWS_DELETED, rows);
                return result;
            }
            case Settings.CALL_METHOD_DELETE_SYSTEM -> {
                int rows = deleteSystemSetting(name, requestingUserId) ? 1 : 0;
                Bundle result = new Bundle();
                result.putInt(RESULT_ROWS_DELETED, rows);
                return result;
            }
            case Settings.CALL_METHOD_LIST_CONFIG -> {
                String prefix = getSettingPrefix(args);
                Bundle result = packageValuesForCallResult(prefix, getAllConfigFlags(prefix),
                        isTrackingGeneration(args));
                reportDeviceConfigAccess(prefix);
                return result;
            }
            case Settings.CALL_METHOD_REGISTER_MONITOR_CALLBACK_CONFIG -> {
                RemoteCallback callback = args.getParcelable(
                        Settings.CALL_METHOD_MONITOR_CALLBACK_KEY);
                setMonitorCallback(callback);
            }
            case Settings.CALL_METHOD_UNREGISTER_MONITOR_CALLBACK_CONFIG -> {
                clearMonitorCallback();
            }
            case Settings.CALL_METHOD_LIST_GLOBAL -> {
                Bundle result = new Bundle();
                result.putStringArrayList(RESULT_SETTINGS_LIST,
                        buildSettingsList(getAllGlobalSettings(null)));
                return result;
            }
            case Settings.CALL_METHOD_LIST_SECURE -> {
                Bundle result = new Bundle();
                result.putStringArrayList(RESULT_SETTINGS_LIST,
                        buildSettingsList(getAllSecureSettings(requestingUserId, null)));
                return result;
            }
            case Settings.CALL_METHOD_LIST_SYSTEM -> {
                Bundle result = new Bundle();
                result.putStringArrayList(RESULT_SETTINGS_LIST,
                        buildSettingsList(getAllSystemSettings(requestingUserId, null)));
                return result;
            }
            default -> {
                Slog.w(LOG_TAG, "call() with invalid method: " + method);
            }
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
            case TABLE_GLOBAL -> {
                if (args.name != null) {
                    Setting setting = getGlobalSetting(args.name);
                    return packageSettingForQuery(setting, normalizedProjection);
                } else {
                    return getAllGlobalSettings(projection);
                }
            }
            case TABLE_SECURE -> {
                final int userId = UserHandle.getCallingUserId();
                if (args.name != null) {
                    Setting setting = getSecureSetting(args.name, userId);
                    return packageSettingForQuery(setting, normalizedProjection);
                } else {
                    return getAllSecureSettings(userId, projection);
                }
            }
            case TABLE_SYSTEM -> {
                final int userId = UserHandle.getCallingUserId();
                if (args.name != null) {
                    Setting setting = getSystemSetting(args.name, userId);
                    return packageSettingForQuery(setting, normalizedProjection);
                } else {
                    return getAllSystemSettings(userId, projection);
                }
            }
            default -> {
                throw new IllegalArgumentException("Invalid Uri path:" + uri);
            }
        }
    }

    private ArrayList<String> buildSettingsList(Cursor cursor) {
        final ArrayList<String> lines = new ArrayList<>();
        try {
            while (cursor != null && cursor.moveToNext()) {
                lines.add(cursor.getString(1) + "=" + cursor.getString(2));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return lines;
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
            case TABLE_GLOBAL -> {
                if (insertGlobalSetting(name, value, null, false,
                        UserHandle.getCallingUserId(), false,
                        /* overrideableByRestore */ false)) {
                    return Uri.withAppendedPath(Global.CONTENT_URI, name);
                }
            }
            case TABLE_SECURE -> {
                if (insertSecureSetting(name, value, null, false,
                        UserHandle.getCallingUserId(), false,
                        /* overrideableByRestore */ false)) {
                    return Uri.withAppendedPath(Secure.CONTENT_URI, name);
                }
            }
            case TABLE_SYSTEM -> {
                if (insertSystemSetting(name, value, UserHandle.getCallingUserId(),
                        /* overridableByRestore */ false)) {
                    return Uri.withAppendedPath(Settings.System.CONTENT_URI, name);
                }
            }
            default -> {
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
            case TABLE_GLOBAL -> {
                final int userId = UserHandle.getCallingUserId();
                return deleteGlobalSetting(args.name, userId, false) ? 1 : 0;
            }
            case TABLE_SECURE -> {
                final int userId = UserHandle.getCallingUserId();
                return deleteSecureSetting(args.name, userId, false) ? 1 : 0;
            }
            case TABLE_SYSTEM -> {
                final int userId = UserHandle.getCallingUserId();
                return deleteSystemSetting(args.name, userId) ? 1 : 0;
            }
            default -> {
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
            case TABLE_GLOBAL -> {
                final int userId = UserHandle.getCallingUserId();
                return updateGlobalSetting(args.name, value, null, false,
                        userId, false) ? 1 : 0;
            }
            case TABLE_SECURE -> {
                final int userId = UserHandle.getCallingUserId();
                return updateSecureSetting(args.name, value, null, false,
                        userId, false) ? 1 : 0;
            }
            case TABLE_SYSTEM -> {
                final int userId = UserHandle.getCallingUserId();
                return updateSystemSetting(args.name, value, userId) ? 1 : 0;
            }
            default -> {
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
        final String callingPackage = getCallingPackage();
        if (mode.contains("w") && !Settings.checkAndNoteWriteSettingsOperation(getContext(),
                Binder.getCallingUid(), callingPackage, getCallingAttributionTag(),
                true /* throwException */)) {
            Slog.e(LOG_TAG, "Package: " + callingPackage + " is not allowed to modify "
                    + "system settings files.");
        }
        uri = ContentProvider.getUriWithoutUserId(uri);

        final String cacheRingtoneSetting;
        if (Settings.System.RINGTONE_CACHE_URI.equals(uri)) {
            cacheRingtoneSetting = Settings.System.RINGTONE;
        } else if (Settings.System.NOTIFICATION_SOUND_CACHE_URI.equals(uri)) {
            cacheRingtoneSetting = Settings.System.NOTIFICATION_SOUND;
        } else if (Settings.System.ALARM_ALERT_CACHE_URI.equals(uri)) {
            cacheRingtoneSetting = Settings.System.ALARM_ALERT;
        } else {
            throw new FileNotFoundException("Direct file access no longer supported; "
                    + "ringtone playback is available through android.media.Ringtone");
        }

        final File cacheFile = getCacheFile(cacheRingtoneSetting, userId);
        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.parseMode(mode));
    }

    @Nullable
    private String getCacheName(String setting) {
        if (Settings.System.RINGTONE.equals(setting)) {
            return Settings.System.RINGTONE_CACHE;
        } else if (Settings.System.NOTIFICATION_SOUND.equals(setting)) {
            return Settings.System.NOTIFICATION_SOUND_CACHE;
        } else if (Settings.System.ALARM_ALERT.equals(setting)) {
            return Settings.System.ALARM_ALERT_CACHE;
        }
        return null;
    }

    @Nullable
    private File getCacheFile(String setting, int userId) {
        int actualCacheOwner;
        // Redirect cache to parent if ringtone setting is owned by profile parent
        synchronized (mLock) {
            actualCacheOwner = resolveOwningUserIdForSystemSettingLocked(userId, setting);
        }
        final String cacheName = getCacheName(setting);
        if (cacheName == null) {
            return null;
        }
        final File cacheFile = new File(getRingtoneCacheDir(actualCacheOwner), cacheName);
        return cacheFile;
    }


    /**
     * Try opening the given ringtone locally first, but failover to
     * {@link IRingtonePlayer} if we can't access it directly. Typically, happens
     * when process doesn't hold {@link android.Manifest.permission#READ_EXTERNAL_STORAGE}.
     */
    private static InputStream openRingtone(Context context, Uri uri) throws IOException {
        final ContentResolver resolver = context.getContentResolver();
        try {
            return resolver.openInputStream(uri);
        } catch (SecurityException | IOException e) {
            Log.w(LOG_TAG, "Failed to open directly; attempting failover: " + e);
            final IRingtonePlayer player = context.getSystemService(AudioManager.class)
                    .getRingtonePlayer();
            try {
                return new ParcelFileDescriptor.AutoCloseInputStream(player.openRingtone(uri));
            } catch (Exception e2) {
                throw new IOException(e2);
            }
        }
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
            mSettingsRegistry.mGenerationRegistry.dump(pw);
        }
    }

    @GuardedBy("mLock")
    private void dumpForUserLocked(int userId, PrintWriter pw) {
        if (userId == UserHandle.USER_SYSTEM) {
            pw.println("CONFIG SETTINGS (user " + userId + ")");
            SettingsState configSettings = mSettingsRegistry.getSettingsLocked(
                    SETTINGS_TYPE_CONFIG, UserHandle.USER_SYSTEM);
            if (configSettings != null) {
                dumpSettingsLocked(configSettings, pw);
                pw.println();
                configSettings.dumpHistoricalOperations(pw);
            }

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

    @SuppressWarnings("GuardedBy")
    private void dumpSettingsLocked(SettingsState settingsState, PrintWriter pw) {
        List<String> names = settingsState.getSettingNamesLocked();
        pw.println("version: " + settingsState.getVersionLocked());
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
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);

        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() == null) {
                    return;
                }
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
                if (userId == UserHandle.USER_NULL) {
                    return;
                }

                switch (intent.getAction()) {
                    case Intent.ACTION_USER_ADDED -> {
                        synchronized (mLock) {
                            mSettingsRegistry.ensureSettingsForUserLocked(userId);
                        }
                    }
                    case Intent.ACTION_USER_REMOVED -> {
                        synchronized (mLock) {
                            mSettingsRegistry.removeUserStateLocked(userId, true);
                        }
                    }
                }
            }
        }, userFilter);

        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    mSettingsRegistry.removeSettingsForPackageLocked(packageName,
                            UserHandle.getUserId(uid));
                }
            }

            @Override
            public void onUidRemoved(int uid) {
                synchronized (mLock) {
                    mSettingsRegistry.onUidRemovedLocked(uid);
                }
            }

            @Override
            public void onPackageDataCleared(String packageName, int uid) {
                synchronized (mLock) {
                    mSettingsRegistry.removeSettingsForPackageLocked(packageName,
                            UserHandle.getUserId(uid));
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
        IUserRestrictionsListener listener = new IUserRestrictionsListener.Stub() {
            @Override
            public void onUserRestrictionsChanged(int userId,
                    Bundle newRestrictions, Bundle prevRestrictions) {
                Set<String> changedRestrictions =
                        getRestrictionDiff(prevRestrictions, newRestrictions);
                // We are changing the settings affected by restrictions to their current
                // value with a forced update to ensure that all cross profile dependencies
                // are taken into account. Also make sure the settings update to.. the same
                // value passes the security checks, so clear binder calling id.
                if (changedRestrictions.contains(UserManager.DISALLOW_SHARE_LOCATION)) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        synchronized (mLock) {
                            Setting setting = getSecureSetting(
                                    Settings.Secure.LOCATION_MODE, userId);
                            updateSecureSetting(Settings.Secure.LOCATION_MODE,
                                    setting != null ? setting.getValue() : null, null,
                                            true, userId, true);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
                if (changedRestrictions.contains(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                        || changedRestrictions.contains(
                                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        synchronized (mLock) {
                            Setting setting = getGlobalSetting(
                                    Settings.Global.INSTALL_NON_MARKET_APPS);
                            String value = setting != null ? setting.getValue() : null;
                            updateGlobalSetting(Settings.Global.INSTALL_NON_MARKET_APPS,
                                    value, null, true, userId, true);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
                if (changedRestrictions.contains(UserManager.DISALLOW_DEBUGGING_FEATURES)) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        synchronized (mLock) {
                            Setting setting = getGlobalSetting(Settings.Global.ADB_ENABLED);
                            String value = setting != null ? setting.getValue() : null;
                            updateGlobalSetting(Settings.Global.ADB_ENABLED,
                                    value, null, true, userId, true);

                            setting = getGlobalSetting(Settings.Global.ADB_WIFI_ENABLED);
                            value = setting != null ? setting.getValue() : null;
                            updateGlobalSetting(Settings.Global.ADB_WIFI_ENABLED,
                                    value, null, true, userId, true);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
                if (changedRestrictions.contains(UserManager.ENSURE_VERIFY_APPS)) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        synchronized (mLock) {
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
                if (changedRestrictions.contains(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
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
            }
        };
        mUserManager.addUserRestrictionsListener(listener);
    }

    private static Set<String> getRestrictionDiff(Bundle prevRestrictions, Bundle newRestrictions) {
        Set<String> restrictionNames = Sets.newArraySet();
        restrictionNames.addAll(prevRestrictions.keySet());
        restrictionNames.addAll(newRestrictions.keySet());
        Set<String> diff = Sets.newArraySet();
        for (String restrictionName : restrictionNames) {
            if (prevRestrictions.getBoolean(restrictionName) != newRestrictions.getBoolean(
                    restrictionName)) {
                diff.add(restrictionName);
            }
        }
        return diff;
    }

    private Setting getConfigSetting(String name) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getConfigSetting(" + name + ")");
        }

        // Get the value.
        synchronized (mLock) {
            return mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_CONFIG,
                    UserHandle.USER_SYSTEM, name);
        }
    }

    private boolean insertConfigSetting(String name, String value, boolean makeDefault) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "insertConfigSetting(" + name + ", " + value  + ", "
                    + makeDefault + ")");
        }
        return mutateConfigSetting(name, value, null, makeDefault,
                MUTATION_OPERATION_INSERT, 0);
    }


    private @SetAllResult int setAllConfigSettings(String prefix, Map<String, String> keyValues) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "setAllConfigSettings for prefix: " + prefix);
        }

        enforceDeviceConfigWritePermission(getContext(), keyValues.keySet());
        final String callingPackage = resolveCallingPackage();

        synchronized (mLock) {
            if (getSyncDisabledModeConfigLocked() != SYNC_DISABLED_MODE_NONE) {
                return SET_ALL_RESULT_DISABLED;
            }
            final int key = makeKey(SETTINGS_TYPE_CONFIG, UserHandle.USER_SYSTEM);
            boolean success = mSettingsRegistry.setConfigSettingsLocked(key, prefix, keyValues,
                    callingPackage);
            return success ? SET_ALL_RESULT_SUCCESS : SET_ALL_RESULT_FAILURE;
        }
    }

    private void setSyncDisabledModeConfig(@SyncDisabledMode int syncDisabledMode) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "setSyncDisabledModeConfig(" + syncDisabledMode + ")");
        }

        enforceHasAtLeastOnePermission(Manifest.permission.WRITE_DEVICE_CONFIG,
                Manifest.permission.READ_WRITE_SYNC_DISABLED_MODE_CONFIG);

        synchronized (mLock) {
            setSyncDisabledModeConfigLocked(syncDisabledMode);
        }
    }

    private int getSyncDisabledModeConfig() {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getSyncDisabledModeConfig");
        }

        enforceHasAtLeastOnePermission(Manifest.permission.WRITE_DEVICE_CONFIG,
                Manifest.permission.READ_WRITE_SYNC_DISABLED_MODE_CONFIG);

        synchronized (mLock) {
            return getSyncDisabledModeConfigLocked();
        }
    }

    @GuardedBy("mLock")
    private void setSyncDisabledModeConfigLocked(@SyncDisabledMode int syncDisabledMode) {
        boolean persistentValue;
        boolean inMemoryValue;
        if (syncDisabledMode == SYNC_DISABLED_MODE_NONE) {
            persistentValue = false;
            inMemoryValue = false;
        } else if (syncDisabledMode == SYNC_DISABLED_MODE_PERSISTENT) {
            persistentValue = true;
            inMemoryValue = false;
        } else if (syncDisabledMode == SYNC_DISABLED_MODE_UNTIL_REBOOT) {
            persistentValue = false;
            inMemoryValue = true;
        } else {
            throw new IllegalArgumentException(Integer.toString(syncDisabledMode));
        }

        mSyncConfigDisabledUntilReboot = inMemoryValue;

        CallingIdentity callingIdentity = clearCallingIdentity();
        try {
            String globalSettingValue = persistentValue ? "1" : "0";
            mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_GLOBAL,
                    UserHandle.USER_SYSTEM, Settings.Global.DEVICE_CONFIG_SYNC_DISABLED,
                    globalSettingValue, /*tag=*/null, /*makeDefault=*/false,
                    SettingsState.SYSTEM_PACKAGE_NAME, /*forceNotify=*/false,
                    /*criticalSettings=*/null, Settings.DEFAULT_OVERRIDEABLE_BY_RESTORE);
        } finally {
            restoreCallingIdentity(callingIdentity);
        }
    }

    @GuardedBy("mLock")
    private int getSyncDisabledModeConfigLocked() {
        // Check the values used for both SYNC_DISABLED_MODE_PERSISTENT and
        // SYNC_DISABLED_MODE_UNTIL_REBOOT.

        // The SYNC_DISABLED_MODE_UNTIL_REBOOT value is cheap to check first.
        if (mSyncConfigDisabledUntilReboot) {
            return SYNC_DISABLED_MODE_UNTIL_REBOOT;
        }

        // Now check the global setting used to implement SYNC_DISABLED_MODE_PERSISTENT.
        CallingIdentity callingIdentity = clearCallingIdentity();
        try {
            Setting settingLocked = mSettingsRegistry.getSettingLocked(
                    SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM,
                    Global.DEVICE_CONFIG_SYNC_DISABLED);
            String settingValue = settingLocked == null ? null : settingLocked.getValue();
            if (settingValue == null) {
                // Disable sync by default in test harness mode.
                return ActivityManager.isRunningInUserTestHarness()
                        ? SYNC_DISABLED_MODE_PERSISTENT : SYNC_DISABLED_MODE_NONE;
            }
            boolean isSyncDisabledPersistent = !"0".equals(settingValue);
            return isSyncDisabledPersistent
                    ? SYNC_DISABLED_MODE_PERSISTENT : SYNC_DISABLED_MODE_NONE;
        } finally {
            restoreCallingIdentity(callingIdentity);
        }
    }

    private boolean deleteConfigSetting(String name) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "deleteConfigSetting(" + name + ")");
        }
        return mutateConfigSetting(name, null, null, false,
                MUTATION_OPERATION_DELETE, 0);
    }

    private void resetConfigSetting(int mode, String prefix) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "resetConfigSetting(" + mode + ", " + prefix + ")");
        }
        mutateConfigSetting(null, null, prefix, false,
                MUTATION_OPERATION_RESET, mode);
    }

    private boolean mutateConfigSetting(String name, String value, String prefix,
            boolean makeDefault, int operation, int mode) {
        final String callingPackage = resolveCallingPackage();

        // Perform the mutation.
        synchronized (mLock) {
            switch (operation) {
                case MUTATION_OPERATION_INSERT -> {
                    enforceDeviceConfigWritePermission(getContext(), Collections.singleton(name));
                    return mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_CONFIG,
                            UserHandle.USER_SYSTEM, name, value, null, makeDefault, true,
                            callingPackage, false, null,
                            /* overrideableByRestore */ false);
                }
                case MUTATION_OPERATION_DELETE -> {
                    enforceDeviceConfigWritePermission(getContext(), Collections.singleton(name));
                    return mSettingsRegistry.deleteSettingLocked(SETTINGS_TYPE_CONFIG,
                            UserHandle.USER_SYSTEM, name, false, null);
                }
                case MUTATION_OPERATION_RESET -> {
                    enforceDeviceConfigWritePermission(getContext(),
                            getAllConfigFlags(prefix).keySet());
                    return mSettingsRegistry.resetSettingsLocked(SETTINGS_TYPE_CONFIG,
                            UserHandle.USER_SYSTEM, callingPackage, mode, null, prefix);
                }
            }
        }

        return false;
    }

    @NonNull
    private HashMap<String, String> getAllConfigFlags(@Nullable String prefix) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getAllConfigFlags() for " + prefix);
        }

        synchronized (mLock) {
            // Get the settings.
            SettingsState settingsState = mSettingsRegistry.getSettingsLocked(
                    SETTINGS_TYPE_CONFIG, UserHandle.USER_SYSTEM);
            List<String> names = getSettingsNamesLocked(SETTINGS_TYPE_CONFIG,
                    UserHandle.USER_SYSTEM);

            final int nameCount = names.size();
            HashMap<String, String> flagsToValues = new HashMap<>(names.size());

            for (int i = 0; i < nameCount; i++) {
                String name = names.get(i);
                Setting setting = settingsState.getSettingLocked(name);
                if (prefix == null || setting.getName().startsWith(prefix)) {
                    flagsToValues.put(setting.getName(), setting.getValue());
                }
            }

            return flagsToValues;
        }
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
                try {
                    enforceSettingReadable(name, SETTINGS_TYPE_GLOBAL,
                            UserHandle.getCallingUserId());
                } catch (SecurityException e) {
                    // Caller doesn't have permission to read this setting
                    continue;
                }
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
            boolean makeDefault, int requestingUserId, boolean forceNotify,
            boolean overrideableByRestore) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "insertGlobalSetting(" + name + ", " + value  + ", "
                    + ", " + tag + ", " + makeDefault + ", " + requestingUserId
                    + ", " + forceNotify + ")");
        }
        return mutateGlobalSetting(name, value, tag, makeDefault, requestingUserId,
                MUTATION_OPERATION_INSERT, forceNotify, 0, overrideableByRestore);
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

    private boolean isSettingRestrictedForUser(String name, int userId,
            String value, int callerUid) {
        final long oldId = Binder.clearCallingIdentity();
        try {
            return (name != null
                    && mUserManager.isSettingRestrictedForUser(name, userId, value, callerUid));
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    private boolean mutateGlobalSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, int operation, boolean forceNotify,
            int mode) {
        // overrideableByRestore = false as by default settings values shouldn't be overrideable by
        // restore.
        return mutateGlobalSetting(name, value, tag, makeDefault, requestingUserId, operation,
                forceNotify, mode, /* overrideableByRestore */ false);
    }

    private boolean mutateGlobalSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, int operation, boolean forceNotify,
            int mode, boolean overrideableByRestore) {
        // Make sure the caller can change the settings - treated as secure.
        enforceHasAtLeastOnePermission(Manifest.permission.WRITE_SECURE_SETTINGS);

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissions(requestingUserId);

        // If this is a setting that is currently restricted for this user, do not allow
        // unrestricting changes.
        if (isSettingRestrictedForUser(name, callingUserId, value, Binder.getCallingUid())) {
            return false;
        }

        final String callingPackage = getCallingPackage();

        // Perform the mutation.
        synchronized (mLock) {
            switch (operation) {
                case MUTATION_OPERATION_INSERT -> {
                    return mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_GLOBAL,
                            UserHandle.USER_SYSTEM, name, value, tag, makeDefault,
                            callingPackage, forceNotify,
                            CRITICAL_GLOBAL_SETTINGS, overrideableByRestore);
                }
                case MUTATION_OPERATION_DELETE -> {
                    return mSettingsRegistry.deleteSettingLocked(SETTINGS_TYPE_GLOBAL,
                            UserHandle.USER_SYSTEM, name, forceNotify, CRITICAL_GLOBAL_SETTINGS);
                }
                case MUTATION_OPERATION_UPDATE -> {
                    return mSettingsRegistry.updateSettingLocked(SETTINGS_TYPE_GLOBAL,
                            UserHandle.USER_SYSTEM, name, value, tag, makeDefault,
                            callingPackage, forceNotify, CRITICAL_GLOBAL_SETTINGS);
                }
                case MUTATION_OPERATION_RESET -> {
                    return mSettingsRegistry.resetSettingsLocked(SETTINGS_TYPE_GLOBAL,
                            UserHandle.USER_SYSTEM, callingPackage, mode, tag);
                }
            }
        }

        return false;
    }

    private PackageInfo getCallingPackageInfo(int userId) {
        final String callingPackage = getCallingPackage();
        try {
            return mPackageManager.getPackageInfo(callingPackage,
                    PackageManager.GET_SIGNATURES, userId);
        } catch (RemoteException e) {
            throw new IllegalStateException("Package " + callingPackage + " doesn't exist");
        }
    }

    private Cursor getAllSecureSettings(int userId, String[] projection) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getAllSecureSettings(" + userId + ")");
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissions(userId);

        // The relevant "calling package" userId will be the owning userId for some
        // profiles, and we can't do the lookup inside our [lock held] loop, so work out
        // up front who the effective "new SSAID" user ID for that settings name will be.
        final int ssaidUserId = resolveOwningUserIdForSecureSetting(callingUserId,
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
                final int owningUserId = resolveOwningUserIdForSecureSetting(callingUserId,
                        name);

                if (!isSecureSettingAccessible(name)) {
                    // This caller is not permitted to access this setting. Pretend the setting
                    // doesn't exist.
                    continue;
                }

                try {
                    enforceSettingReadable(name, SETTINGS_TYPE_SECURE, callingUserId);
                } catch (SecurityException e) {
                    // Caller doesn't have permission to read this setting
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
        if (DEBUG) {
            Slog.v(LOG_TAG, "getSecureSetting(" + name + ", " + requestingUserId + ")");
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissions(requestingUserId);

        // Ensure the caller can access the setting.
        enforceSettingReadable(name, SETTINGS_TYPE_SECURE, UserHandle.getCallingUserId());

        // Determine the owning user as some profile settings are cloned from the parent.
        final int owningUserId = resolveOwningUserIdForSecureSetting(callingUserId, name);

        if (!isSecureSettingAccessible(name)) {
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

    @GuardedBy("mLock")
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

    private boolean insertSecureSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, boolean forceNotify,
            boolean overrideableByRestore) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "insertSecureSetting(" + name + ", " + value + ", "
                    + ", " + tag  + ", " + makeDefault + ", "  + requestingUserId
                    + ", " + forceNotify + ")");
        }
        return mutateSecureSetting(name, value, tag, makeDefault, requestingUserId,
                MUTATION_OPERATION_INSERT, forceNotify, 0, overrideableByRestore);
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
        // overrideableByRestore = false as by default settings values shouldn't be overrideable by
        // restore.
        return mutateSecureSetting(name, value, tag, makeDefault, requestingUserId, operation,
                forceNotify, mode, /* overrideableByRestore */ false);
    }

    private boolean mutateSecureSetting(String name, String value, String tag,
            boolean makeDefault, int requestingUserId, int operation, boolean forceNotify,
            int mode, boolean overrideableByRestore) {
        // Make sure the caller can change the settings.
        enforceHasAtLeastOnePermission(Manifest.permission.WRITE_SECURE_SETTINGS);

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissions(requestingUserId);

        // If this is a setting that is currently restricted for this user, do not allow
        // unrestricting changes.
        if (isSettingRestrictedForUser(name, callingUserId, value, Binder.getCallingUid())) {
            return false;
        }

        // Determine the owning user as some profile settings are cloned from the parent.
        final int owningUserId = resolveOwningUserIdForSecureSetting(callingUserId, name);

        // Only the owning user can change the setting.
        if (owningUserId != callingUserId) {
            return false;
        }

        final String callingPackage = getCallingPackage();

        // Mutate the value.
        synchronized (mLock) {
            switch (operation) {
                case MUTATION_OPERATION_INSERT -> {
                    return mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_SECURE,
                            owningUserId, name, value, tag, makeDefault,
                            callingPackage, forceNotify, CRITICAL_SECURE_SETTINGS,
                            overrideableByRestore);
                }
                case MUTATION_OPERATION_DELETE -> {
                    return mSettingsRegistry.deleteSettingLocked(SETTINGS_TYPE_SECURE,
                            owningUserId, name, forceNotify, CRITICAL_SECURE_SETTINGS);
                }
                case MUTATION_OPERATION_UPDATE -> {
                    return mSettingsRegistry.updateSettingLocked(SETTINGS_TYPE_SECURE,
                            owningUserId, name, value, tag, makeDefault,
                            callingPackage, forceNotify, CRITICAL_SECURE_SETTINGS);
                }
                case MUTATION_OPERATION_RESET -> {
                    return mSettingsRegistry.resetSettingsLocked(SETTINGS_TYPE_SECURE,
                            UserHandle.USER_SYSTEM, callingPackage, mode, tag);
                }
            }
        }

        return false;
    }

    private Cursor getAllSystemSettings(int userId, String[] projection) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "getAllSecureSystem(" + userId + ")");
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissions(userId);

        synchronized (mLock) {
            List<String> names = getSettingsNamesLocked(SETTINGS_TYPE_SYSTEM, callingUserId);

            final int nameCount = names.size();

            String[] normalizedProjection = normalizeProjection(projection);
            MatrixCursor result = new MatrixCursor(normalizedProjection, nameCount);

            for (int i = 0; i < nameCount; i++) {
                String name = names.get(i);
                try {
                    enforceSettingReadable(name, SETTINGS_TYPE_SYSTEM, callingUserId);
                } catch (SecurityException e) {
                    // Caller doesn't have permission to read this setting
                    continue;
                }
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
        final int callingUserId = resolveCallingUserIdEnforcingPermissions(requestingUserId);

        // Ensure the caller can access the setting.
        enforceSettingReadable(name, SETTINGS_TYPE_SYSTEM, UserHandle.getCallingUserId());

        // Determine the owning user as some profile settings are cloned from the parent.
        final int owningUserId = resolveOwningUserIdForSystemSettingLocked(callingUserId, name);

        // Get the value.
        synchronized (mLock) {
            return mSettingsRegistry.getSettingLocked(SETTINGS_TYPE_SYSTEM, owningUserId, name);
        }
    }

    private boolean insertSystemSetting(String name, String value, int requestingUserId,
            boolean overrideableByRestore) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "insertSystemSetting(" + name + ", " + value + ", "
                    + requestingUserId + ")");
        }

        return mutateSystemSetting(name, value, /* tag= */ null, requestingUserId,
                MUTATION_OPERATION_INSERT, /* mode= */ 0, overrideableByRestore);
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

    private void resetSystemSetting(int requestingUserId, int mode, String tag) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "resetSystemSetting(" + requestingUserId + ", "
                    + mode + ", " + tag + ")");
        }

        mutateSystemSetting(null, null, tag, requestingUserId, MUTATION_OPERATION_RESET, mode,
                false);
    }

    private boolean mutateSystemSetting(String name, String value, int runAsUserId, int operation) {
        // overrideableByRestore = false as by default settings values shouldn't be overrideable by
        // restore.
        return mutateSystemSetting(name, value, /* tag= */ null, runAsUserId, operation,
                /* mode= */ 0, /* overrideableByRestore */ false);
    }

    private boolean mutateSystemSetting(String name, String value, String tag, int runAsUserId,
            int operation, int mode, boolean overrideableByRestore) {
        final String callingPackage = getCallingPackage();
        if (!hasWriteSecureSettingsPermission()) {
            // If the caller doesn't hold WRITE_SECURE_SETTINGS, we verify whether this
            // operation is allowed for the calling package through appops.
            if (!Settings.checkAndNoteWriteSettingsOperation(getContext(),
                    Binder.getCallingUid(), callingPackage, getCallingAttributionTag(),
                    true)) {
                Slog.e(LOG_TAG, "Calling package: " + callingPackage + " is not allowed to "
                        + "write system settings: " + name);
                return false;
            }
        }

        // Resolve the userId on whose behalf the call is made.
        final int callingUserId = resolveCallingUserIdEnforcingPermissions(runAsUserId);

        if (isSettingRestrictedForUser(name, callingUserId, value, Binder.getCallingUid())) {
            Slog.e(LOG_TAG, "UserId: " + callingUserId + " is disallowed to change system "
                    + "setting: " + name);
            return false;
        }

        // Enforce what the calling package can mutate the system settings.
        enforceRestrictedSystemSettingsMutationForCallingPackage(operation, name, callingUserId);

        // Determine the owning user as some profile settings are cloned from the parent.
        final int owningUserId = resolveOwningUserIdForSystemSettingLocked(callingUserId, name);

        // Only the owning user id can change the setting.
        if (owningUserId != callingUserId) {
            Slog.e(LOG_TAG, "UserId: " + callingUserId + " is not the owning userId: "
                    + owningUserId);
            return false;
        }

        File cacheFile = getCacheFile(name, callingUserId);
        if (cacheFile != null) {
            if (!isValidAudioUri(name, value)) {
                return false;
            }
            // Invalidate any relevant cache files
            cacheFile.delete();
        }

        final boolean success;
        // Mutate the value.
        synchronized (mLock) {
            switch (operation) {
                case MUTATION_OPERATION_INSERT -> {
                    validateSystemSettingValue(name, value);
                    success = mSettingsRegistry.insertSettingLocked(SETTINGS_TYPE_SYSTEM,
                            owningUserId, name, value, null, false, callingPackage,
                            false, null, overrideableByRestore);
                }
                case MUTATION_OPERATION_DELETE -> {
                    success = mSettingsRegistry.deleteSettingLocked(SETTINGS_TYPE_SYSTEM,
                            owningUserId, name, false, null);
                }
                case MUTATION_OPERATION_UPDATE -> {
                    validateSystemSettingValue(name, value);
                    success = mSettingsRegistry.updateSettingLocked(SETTINGS_TYPE_SYSTEM,
                            owningUserId, name, value, null, false, callingPackage,
                            false, null);
                }
                case MUTATION_OPERATION_RESET -> {
                    success = mSettingsRegistry.resetSettingsLocked(SETTINGS_TYPE_SYSTEM,
                            runAsUserId, callingPackage, mode, tag);
                }
                default -> {
                    success = false;
                    Slog.e(LOG_TAG, "Unknown operation code: " + operation);
                }
            }
        }

        if (!success) {
            return false;
        }

        if ((operation == MUTATION_OPERATION_INSERT || operation == MUTATION_OPERATION_UPDATE)
                && cacheFile != null && value != null) {
            final Uri ringtoneUri = Uri.parse(value);
            // Stream selected ringtone into cache, so it's available for playback
            // when CE storage is still locked
            Binder.withCleanCallingIdentity(() -> {
                try (InputStream in = openRingtone(getContext(), ringtoneUri);
                         OutputStream out = new FileOutputStream(cacheFile)) {
                    FileUtils.copy(in, out);
                } catch (IOException e) {
                    Slog.w(LOG_TAG, "Failed to cache ringtone: " + e);
                }
            });
        }
        return true;
    }

    private boolean isValidAudioUri(String name, String uri) {
        if (uri != null) {
            Uri audioUri = Uri.parse(uri);
            if (Settings.AUTHORITY.equals(
                    ContentProvider.getAuthorityWithoutUserId(audioUri.getAuthority()))) {
                // Don't accept setting the default uri to self-referential URIs like
                // Settings.System.DEFAULT_RINGTONE_URI, which is an alias to the value of this
                // setting.
                return false;
            }
            final String mimeType = getContext().getContentResolver().getType(audioUri);
            if (mimeType == null) {
                Slog.e(LOG_TAG,
                        "mutateSystemSetting for setting: " + name + " URI: " + audioUri
                        + " ignored: failure to find mimeType (no access from this context?)");
                return false;
            }
            if (!(mimeType.startsWith("audio/") || mimeType.equals("application/ogg")
                    || mimeType.equals("application/x-flac"))) {
                Slog.e(LOG_TAG,
                        "mutateSystemSetting for setting: " + name + " URI: " + audioUri
                        + " ignored: associated mimeType: " + mimeType + " is not an audio type");
                return false;
            }
        }
        return true;
    }

    private boolean hasWriteSecureSettingsPermission() {
        // Write secure settings is a more protected permission. If caller has it we are good.
        return getContext().checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void validateSystemSettingValue(String name, String value) {
        Validator validator = SystemSettingsValidators.VALIDATORS.get(name);
        if (validator != null && !validator.validate(value)) {
            throw new IllegalArgumentException("Invalid value: " + value
                    + " for setting: " + name);
        }
    }

    /**
     * Returns {@code true} if the specified secure setting should be accessible to the caller.
     */
    private boolean isSecureSettingAccessible(String name) {
        return switch (name) {
            case "bluetooth_address" ->
                // BluetoothManagerService for some reason stores the Android's Bluetooth MAC
                // address in this secure setting. Secure settings can normally be read by any app,
                // which thus enables them to bypass the recently introduced restrictions on access
                // to device identifiers.
                // To mitigate this we make this setting available only to callers privileged to see
                // this device's MAC addresses, same as through public API
                // BluetoothAdapter.getAddress() (see BluetoothManagerService for details).
                    getContext().checkCallingOrSelfPermission(Manifest.permission.LOCAL_MAC_ADDRESS)
                            == PackageManager.PERMISSION_GRANTED;
            default -> true;
        };
    }

    private int resolveOwningUserIdForSecureSetting(int userId, String setting) {
        // no need to lock because sSecureCloneToManagedSettings is never modified
        return resolveOwningUserId(userId, sSecureCloneToManagedSettings, setting);
    }

    @GuardedBy("mLock")
    private int resolveOwningUserIdForSystemSettingLocked(int userId, String setting) {
        final int parentId;
        // Resolves dependency if setting has a dependency and the calling user has a parent
        if (sSystemCloneFromParentOnDependency.containsKey(setting)
                && (parentId = getGroupParent(userId)) != userId) {
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
        return resolveOwningUserId(userId, sSystemCloneToManagedSettings, setting);
    }

    private int resolveOwningUserId(int userId, Set<String> keys, String name) {
        final int parentId = getGroupParent(userId);
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
            // Insert updates.
            case MUTATION_OPERATION_INSERT, MUTATION_OPERATION_UPDATE -> {
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
            }
            case MUTATION_OPERATION_DELETE -> {
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
            }
        }
    }

    private static Set<String> getInstantAppAccessibleSettings(int settingsType) {
        return switch (settingsType) {
            case SETTINGS_TYPE_GLOBAL -> Global.INSTANT_APP_SETTINGS;
            case SETTINGS_TYPE_SECURE -> Secure.INSTANT_APP_SETTINGS;
            case SETTINGS_TYPE_SYSTEM -> Settings.System.INSTANT_APP_SETTINGS;
            default -> throw new IllegalArgumentException("Invalid settings type: " + settingsType);
        };
    }

    private static Set<String> getOverlayInstantAppAccessibleSettings(int settingsType) {
        return switch (settingsType) {
            case SETTINGS_TYPE_GLOBAL -> OVERLAY_ALLOWED_GLOBAL_INSTANT_APP_SETTINGS;
            case SETTINGS_TYPE_SYSTEM -> OVERLAY_ALLOWED_SYSTEM_INSTANT_APP_SETTINGS;
            case SETTINGS_TYPE_SECURE -> OVERLAY_ALLOWED_SECURE_INSTANT_APP_SETTINGS;
            default -> throw new IllegalArgumentException("Invalid settings type: " + settingsType);
        };
    }

    @GuardedBy("mLock")
    private List<String> getSettingsNamesLocked(int settingsType, int userId) {
        // Don't enforce the instant app allowlist for now -- its too prone to unintended breakage
        // in the current form.
        return mSettingsRegistry.getSettingsNamesLocked(settingsType, userId);
    }

    private void enforceSettingReadable(String settingName, int settingsType, int userId) {
        if (UserHandle.getAppId(Binder.getCallingUid()) < Process.FIRST_APPLICATION_UID) {
            return;
        }
        ApplicationInfo ai = getCallingApplicationInfoOrThrow();
        if (ai.isSystemApp() || ai.isSignedWithPlatformKey()) {
            return;
        }
        if ((ai.flags & ApplicationInfo.FLAG_TEST_ONLY) == 0) {
            // Skip checking readable annotations for test_only apps
            checkReadableAnnotation(settingsType, settingName, ai.targetSdkVersion);
        }
        /**
         * some settings need additional permission check, this is to have a matching security
         * control from other API alternatives returning the same settings values.
         * note, the permission enforcement should be based on app's targetSDKlevel to better handle
         * app-compat.
         */
        switch (settingName) {
            // missing READ_PRIVILEGED_PHONE_STATE permission protection
            // see alternative API {@link SubscriptionManager#getPreferredDataSubscriptionId()
            case Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION -> {
                // app-compat handling, not break apps targeting on previous SDKs.
                if (CompatChanges.isChangeEnabled(
                        ENFORCE_READ_PERMISSION_FOR_MULTI_SIM_DATA_CALL)) {
                    getContext().enforceCallingOrSelfPermission(
                            Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                            "access global settings MULTI_SIM_DATA_CALL_SUBSCRIPTION");
                }
            }
        }
        if (!ai.isInstantApp()) {
            return;
        }
        if (!getInstantAppAccessibleSettings(settingsType).contains(settingName)
                && !getOverlayInstantAppAccessibleSettings(settingsType).contains(settingName)) {
            // Don't enforce the instant app allowlist for now -- its too prone to unintended
            // breakage in the current form.
            Slog.w(LOG_TAG, "Instant App " + ai.packageName
                    + " trying to access unexposed setting, this will be an error in the future.");
        }
    }

    /**
     * Check if the target settings key is readable. Reject if the caller app is trying to access a
     * settings key defined in the Settings.Secure, Settings.System or Settings.Global and is not
     * annotated as @Readable. Reject if the caller app is targeting an SDK level that is higher
     * than the maxTargetSdk specified in the @Readable annotation.
     * Notice that a key string that is not defined in any of the Settings.* classes will still be
     * regarded as readable.
     */
    private void checkReadableAnnotation(int settingsType, String settingName,
            int targetSdkVersion) {
        final Set<String> allFields;
        final Set<String> readableFields;
        final ArrayMap<String, Integer> readableFieldsWithMaxTargetSdk;
        switch (settingsType) {
            case SETTINGS_TYPE_GLOBAL -> {
                allFields = sAllGlobalSettings;
                readableFields = sReadableGlobalSettings;
                readableFieldsWithMaxTargetSdk = sReadableGlobalSettingsWithMaxTargetSdk;
            }
            case SETTINGS_TYPE_SYSTEM -> {
                allFields = sAllSystemSettings;
                readableFields = sReadableSystemSettings;
                readableFieldsWithMaxTargetSdk = sReadableSystemSettingsWithMaxTargetSdk;
            }
            case SETTINGS_TYPE_SECURE -> {
                allFields = sAllSecureSettings;
                readableFields = sReadableSecureSettings;
                readableFieldsWithMaxTargetSdk = sReadableSecureSettingsWithMaxTargetSdk;
            }
            default -> throw new IllegalArgumentException("Invalid settings type: " + settingsType);
        }

        if (allFields.contains(settingName)) {
            if (!readableFields.contains(settingName)) {
                throw new SecurityException(
                        "Settings key: <" + settingName + "> is not readable. From S+, settings "
                                + "keys annotated with @hide are restricted to system_server and "
                                + "system apps only, unless they are annotated with @Readable."
                );
            } else {
                if (readableFieldsWithMaxTargetSdk.containsKey(settingName)) {
                    final int maxTargetSdk = readableFieldsWithMaxTargetSdk.get(settingName);
                    if (targetSdkVersion > maxTargetSdk) {
                        throw new SecurityException(
                                "Settings key: <" + settingName + "> is only readable to apps with "
                                        + "targetSdkVersion lower than or equal to: "
                                        + maxTargetSdk
                        );
                    }
                }
            }
        }
    }

    private ApplicationInfo getCallingApplicationInfoOrThrow() {
        // We always use the callingUid for this lookup. This means that if hypothetically an
        // app was installed in user A with cross user and in user B as an Instant App
        // the app in A would be able to see all the settings in user B. However since cross
        // user is a system permission and the app must be uninstalled in B and then installed as
        // an Instant App that situation is not realistic or supported.
        ApplicationInfo ai = null;
        final String callingPackage = getCallingPackage();
        try {
            ai = mPackageManager.getApplicationInfo(callingPackage, 0
                    , UserHandle.getCallingUserId());
        } catch (RemoteException ignored) {
        }
        if (ai == null) {
            throw new IllegalStateException("Failed to lookup info for package "
                    + callingPackage);
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

    private int getGroupParent(int userId) {
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

    private void enforceHasAtLeastOnePermission(String ...permissions) {
        for (String permission : permissions) {
            if (getContext().checkCallingOrSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        throw new SecurityException("Permission denial, must have one of: "
            + Arrays.toString(permissions));
    }

    /**
     * Throws an exception if write permissions are not granted for {@code flags}.
     * <p>
     * Write permissions are granted if the calling UID is root, or the
     * WRITE_DEVICE_CONFIG permission is granted, or the WRITE_DEVICE_CONFIG_ALLOWLIST
     * permission is granted and each flag in {@code flags} is allowlisted in {@code
     * WRITABLE_FLAG_ALLOWLIST_FLAG}.
     *
     * @param context the {@link Context} this is called in
     * @param flags a list of flags to check, each one of the form 'namespace/flagName'
     *
     * @throws SecurityException if the above criteria are not met.
     * @hide
     */
    private void enforceDeviceConfigWritePermission(
            @NonNull Context context,
            @NonNull Set<String> flags) {
        boolean hasAllowlistPermission =
                context.checkCallingOrSelfPermission(
                Manifest.permission.WRITE_ALLOWLISTED_DEVICE_CONFIG)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasWritePermission =
                context.checkCallingOrSelfPermission(
                Manifest.permission.WRITE_DEVICE_CONFIG)
                == PackageManager.PERMISSION_GRANTED;
        boolean isRoot = Binder.getCallingUid() == Process.ROOT_UID;

        if (isRoot || hasWritePermission) {
            return;
        } else if (hasAllowlistPermission) {
            for (String flag : flags) {
                boolean namespaceAllowed = false;
                for (String allowlistedPrefix : WritableNamespacePrefixes.ALLOWLIST) {
                    if (flag.startsWith(allowlistedPrefix)) {
                        namespaceAllowed = true;
                        break;
                    }
                }

                if (!namespaceAllowed && !DeviceConfig.getAdbWritableFlags().contains(flag)) {
                    throw new SecurityException("Permission denial for flag '"
                        + flag
                        + "'; allowlist permission granted, but must add flag to the allowlist.");
                }
            }
        } else {
            throw new SecurityException("Permission denial to mutate flag, must have root, "
                + "WRITE_DEVICE_CONFIG, or WRITE_ALLOWLISTED_DEVICE_CONFIG");
        }
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

    private static int resolveCallingUserIdEnforcingPermissions(int requestingUserId) {
        if (requestingUserId == UserHandle.getCallingUserId()) {
            return requestingUserId;
        }
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), requestingUserId, false, true,
                "get/set setting for user", null);
    }

    private Bundle packageValueForCallResult(int type, @NonNull String name, int userId,
            @Nullable Setting setting, boolean trackingGeneration) {
        if (!trackingGeneration) {
            if (setting == null || setting.isNull()) {
                return NULL_SETTING_BUNDLE;
            }
            return Bundle.forPair(Settings.NameValueTable.VALUE, setting.getValue());
        }
        Bundle result = new Bundle();
        result.putString(Settings.NameValueTable.VALUE,
                (setting != null && !setting.isNull()) ? setting.getValue() : null);

        synchronized (mLock) {
            if ((setting != null && !setting.isNull()) || isSettingPreDefined(name, type)) {
                // Individual generation tracking for predefined settings even if they are unset
                mSettingsRegistry.mGenerationRegistry.addGenerationData(result,
                        SettingsState.makeKey(type, userId), name);
            } else {
                // All non-predefined, unset settings are tracked using the same generation number
                mSettingsRegistry.mGenerationRegistry.addGenerationDataForUnsetSettings(result,
                        SettingsState.makeKey(type, userId));
            }
        }
        return result;
    }

    private boolean isSettingPreDefined(String name, int type) {
        if (type == SETTINGS_TYPE_GLOBAL) {
            return sAllGlobalSettings.contains(name);
        } else if (type == SETTINGS_TYPE_SECURE) {
            return sAllSecureSettings.contains(name);
        } else if (type == SETTINGS_TYPE_SYSTEM) {
            return sAllSystemSettings.contains(name);
        } else {
            // Consider all config settings predefined because they are used by system apps only
            return type == SETTINGS_TYPE_CONFIG;
        }
    }

    private Bundle packageValuesForCallResult(String prefix,
            @NonNull HashMap<String, String> keyValues, boolean trackingGeneration) {
        Bundle result = new Bundle();
        result.putSerializable(Settings.NameValueTable.VALUE, keyValues);
        if (trackingGeneration) {
            synchronized (mLock) {
                // Track generation even if namespace is empty because this is for system apps only
                mSettingsRegistry.mGenerationRegistry.addGenerationData(result,
                        SettingsState.makeKey(SETTINGS_TYPE_CONFIG, UserHandle.USER_SYSTEM),
                        prefix);
            }
        }
        return result;
    }

    private void setMonitorCallback(RemoteCallback callback) {
        if (callback == null) {
            return;
        }
        getContext().enforceCallingOrSelfPermission(
                Manifest.permission.MONITOR_DEVICE_CONFIG_ACCESS,
                "Permission denial: registering for config access requires: "
                        + Manifest.permission.MONITOR_DEVICE_CONFIG_ACCESS);
        synchronized (mLock) {
            mConfigMonitorCallback = callback;
        }
    }

    private void clearMonitorCallback() {
        getContext().enforceCallingOrSelfPermission(
                Manifest.permission.MONITOR_DEVICE_CONFIG_ACCESS,
                "Permission denial: registering for config access requires: "
                        + Manifest.permission.MONITOR_DEVICE_CONFIG_ACCESS);
        synchronized (mLock) {
            mConfigMonitorCallback = null;
        }
    }

    private void reportDeviceConfigAccess(@Nullable String prefix) {
        if (prefix == null) {
            return;
        }
        String callingPackage = resolveCallingPackage();
        String namespace = prefix.replace("/", "");
        if (DeviceConfig.getPublicNamespaces().contains(namespace)) {
            return;
        }
        synchronized (mLock) {
            if (mConfigMonitorCallback != null) {
                Bundle callbackResult = new Bundle();
                callbackResult.putString(Settings.EXTRA_MONITOR_CALLBACK_TYPE,
                        Settings.EXTRA_ACCESS_CALLBACK);
                callbackResult.putString(Settings.EXTRA_CALLING_PACKAGE, callingPackage);
                callbackResult.putString(Settings.EXTRA_NAMESPACE, namespace);
                mConfigMonitorCallback.sendResult(callbackResult);
            }
        }
    }

    private void reportDeviceConfigUpdate(@Nullable String prefix) {
        if (prefix == null) {
            return;
        }
        String namespace = prefix.replace("/", "");
        if (DeviceConfig.getPublicNamespaces().contains(namespace)) {
            return;
        }
        synchronized (mLock) {
            if (mConfigMonitorCallback != null) {
                Bundle callbackResult = new Bundle();
                callbackResult.putString(Settings.EXTRA_MONITOR_CALLBACK_TYPE,
                        Settings.EXTRA_NAMESPACE_UPDATED_CALLBACK);
                callbackResult.putString(Settings.EXTRA_NAMESPACE, namespace);
                mConfigMonitorCallback.sendResult(callbackResult);
            }
        }
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

    private static String getSettingPrefix(Bundle args) {
        return (args != null) ? args.getString(Settings.CALL_METHOD_PREFIX_KEY) : null;
    }

    private static Map<String, String> getSettingFlags(Bundle args) {
        return (args != null) ? (HashMap) args.getSerializable(Settings.CALL_METHOD_FLAGS_KEY)
                : Collections.emptyMap();
    }

    private static boolean getSettingMakeDefault(Bundle args) {
        return (args != null) && args.getBoolean(Settings.CALL_METHOD_MAKE_DEFAULT_KEY);
    }

    private static boolean getSettingOverrideableByRestore(Bundle args) {
        return (args != null) && args.getBoolean(Settings.CALL_METHOD_OVERRIDEABLE_BY_RESTORE_KEY);
    }

    private static int getSyncDisabledMode(Bundle args) {
        final int mode = (args != null)
                ? args.getInt(Settings.CALL_METHOD_SYNC_DISABLED_MODE_KEY) : -1;
        if (mode == SYNC_DISABLED_MODE_NONE || mode == SYNC_DISABLED_MODE_UNTIL_REBOOT
                || mode == SYNC_DISABLED_MODE_PERSISTENT) {
            return mode;
        }
        throw new IllegalArgumentException("Invalid sync disabled mode: " + mode);
    }

    private static int getResetModeEnforcingPermission(Bundle args) {
        final int mode = (args != null) ? args.getInt(Settings.CALL_METHOD_RESET_MODE_KEY) : 0;
        switch (mode) {
            case Settings.RESET_MODE_UNTRUSTED_DEFAULTS -> {
                if (!isCallerSystemOrShellOrRootOnDebuggableBuild()) {
                    throw new SecurityException("Only system, shell/root on a "
                            + "debuggable build can reset to untrusted defaults");
                }
                return mode;
            }
            case Settings.RESET_MODE_UNTRUSTED_CHANGES -> {
                if (!isCallerSystemOrShellOrRootOnDebuggableBuild()) {
                    throw new SecurityException("Only system, shell/root on a "
                            + "debuggable build can reset untrusted changes");
                }
                return mode;
            }
            case Settings.RESET_MODE_TRUSTED_DEFAULTS -> {
                if (!isCallerSystemOrShellOrRootOnDebuggableBuild()) {
                    throw new SecurityException("Only system, shell/root on a "
                            + "debuggable build can reset to trusted defaults");
                }
                return mode;
            }
            case Settings.RESET_MODE_PACKAGE_DEFAULTS -> {
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
                case Settings.NameValueTable._ID -> {
                    values[i] = setting.getId();
                }
                case Settings.NameValueTable.NAME -> {
                    values[i] = setting.getName();
                }
                case Settings.NameValueTable.VALUE -> {
                    values[i] = setting.getValue();
                }
                case Settings.NameValueTable.IS_PRESERVED_IN_RESTORE -> {
                    values[i] = String.valueOf(setting.isValuePreservedInRestore());
                }
            }
        }

        cursor.addRow(values);
    }

    private static boolean isKeyValid(String key) {
        return !(TextUtils.isEmpty(key) || SettingsState.isBinary(key));
    }

    private String resolveCallingPackage() {
        return switch (Binder.getCallingUid()) {
            case Process.ROOT_UID -> "root";
            case Process.SHELL_UID -> "com.android.shell";
            default -> getCallingPackage();
        };
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
                case 1 -> {
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
                }
                case 2 -> {
                    if (where == null && whereArgs == null) {
                        name = uri.getPathSegments().get(1);
                        table = computeTableForSetting(uri, name);
                        return;
                    }
                }
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

                if (sGlobalMovedToSystemSettings.contains(name)) {
                    table = TABLE_SYSTEM;
                }
            }

            return table;
        }
    }

    /**
     * Schedule the job service to make a copy of all the settings files.
     */
    public void scheduleWriteFallbackFilesJob() {
        final Context context = getContext();
        JobScheduler jobScheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) {
            // Might happen: SettingsProvider is created before JobSchedulerService in system server
            return;
        }
        jobScheduler = jobScheduler.forNamespace(SETTINGS_PROVIDER_JOBS_NS);
        // Check if the job is already scheduled. If so, skip scheduling another one
        if (jobScheduler.getPendingJob(WRITE_FALLBACK_SETTINGS_FILES_JOB_ID) != null) {
            return;
        }
        // Back up all settings files
        final PersistableBundle bundle = new PersistableBundle();
        final File globalSettingsFile = mSettingsRegistry.getSettingsFile(
                makeKey(SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM));
        final File systemSettingsFile = mSettingsRegistry.getSettingsFile(
                makeKey(SETTINGS_TYPE_SYSTEM, UserHandle.USER_SYSTEM));
        final File secureSettingsFile = mSettingsRegistry.getSettingsFile(
                makeKey(SETTINGS_TYPE_SECURE, UserHandle.USER_SYSTEM));
        final File ssaidSettingsFile = mSettingsRegistry.getSettingsFile(
                makeKey(SETTINGS_TYPE_SSAID, UserHandle.USER_SYSTEM));
        final File configSettingsFile = mSettingsRegistry.getSettingsFile(
                makeKey(SETTINGS_TYPE_CONFIG, UserHandle.USER_SYSTEM));
        bundle.putString(TABLE_GLOBAL, globalSettingsFile.getAbsolutePath());
        bundle.putString(TABLE_SYSTEM, systemSettingsFile.getAbsolutePath());
        bundle.putString(TABLE_SECURE, secureSettingsFile.getAbsolutePath());
        bundle.putString(TABLE_SSAID, ssaidSettingsFile.getAbsolutePath());
        bundle.putString(TABLE_CONFIG, configSettingsFile.getAbsolutePath());
        // Schedule the job to write the fallback files, once daily when phone is charging
        jobScheduler.schedule(new JobInfo.Builder(WRITE_FALLBACK_SETTINGS_FILES_JOB_ID,
                new ComponentName(context, WriteFallbackSettingsFilesJobService.class))
                .setExtras(bundle)
                .setPeriodic(ONE_DAY_INTERVAL_MILLIS)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build());
    }

    /**
     * For each file in the given list, if it exists, copy it to a back up file. Ignore failures.
     * @param filePaths List of paths of files that need to be backed up
     */
    public static void writeFallBackSettingsFiles(List<String> filePaths) {
        final int numFiles = filePaths.size();
        for (int i = 0; i < numFiles; i++) {
            final String filePath = filePaths.get(i);
            final File originalFile = new File(filePath);
            if (SettingsState.stateFileExists(originalFile)) {
                final File fallBackFile = new File(filePath + FALLBACK_FILE_SUFFIX);
                try {
                    FileUtils.copy(originalFile, fallBackFile);
                } catch (IOException ex) {
                    Slog.w(LOG_TAG, "Failed to write fallback file for: " + filePath);
                }
            }
        }
    }

    final class SettingsRegistry {
        private static final String DROPBOX_TAG_USERLOG = "restricted_profile_ssaid";

        private static final String SETTINGS_FILE_GLOBAL = "settings_global.xml";
        private static final String SETTINGS_FILE_SYSTEM = "settings_system.xml";
        private static final String SETTINGS_FILE_SECURE = "settings_secure.xml";
        private static final String SETTINGS_FILE_SSAID = "settings_ssaid.xml";
        private static final String SETTINGS_FILE_CONFIG = "settings_config.xml";

        private static final String SSAID_USER_KEY = "userkey";

        private final SparseArray<SettingsState> mSettingsStates = new SparseArray<>();

        private GenerationRegistry mGenerationRegistry;

        private final Handler mHandler;

        private final BackupManager mBackupManager;

        private String mSettingsCreationBuildId;

        SettingsRegistry(Looper looper) {
            mHandler = new MyHandler(looper);
            mGenerationRegistry = new GenerationRegistry(UserManager.getMaxSupportedUsers());
            mBackupManager = new BackupManager(getContext());
        }

        @GuardedBy("mLock")
        private void generateUserKeyLocked(int userId) {
            // Generate a random key for each user used for creating a new ssaid.
            final byte[] keyBytes = new byte[32];
            final SecureRandom rand = new SecureRandom();
            rand.nextBytes(keyBytes);

            // Convert to string for storage in settings table.
            final String userKey = HexEncoding.encodeToString(keyBytes, true /* upperCase */);

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

        @GuardedBy("mLock")
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
            if (userKey == null || userKey.length() % 2 != 0) {
                throw new IllegalStateException("User key invalid");
            }

            // Convert the user's key back to a byte array.
            final byte[] keyBytes = HexEncoding.decode(userKey);

            // Validate that the key is of expected length.
            // Keys are currently 32 bytes, but were once 16 bytes during Android O development.
            if (keyBytes.length != 16 && keyBytes.length != 32) {
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
            final String ssaid = HexEncoding.encodeToString(m.doFinal(), false /* upperCase */)
                    .substring(0, 16);

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

        @GuardedBy("mLock")
        private void syncSsaidTableOnStartLocked() {
            // Verify that each user's packages and ssaid's are in sync.
            for (UserInfo user : mUserManager.getAliveUsers()) {
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

        @GuardedBy("mLock")
        public List<String> getSettingsNamesLocked(int type, int userId) {
            final int key = makeKey(type, userId);
            SettingsState settingsState = mSettingsStates.get(key);
            if (settingsState == null) {
                return new ArrayList<>();
            }
            return settingsState.getSettingNamesLocked();
        }

        @GuardedBy("mLock")
        public SparseBooleanArray getKnownUsersLocked() {
            SparseBooleanArray users = new SparseBooleanArray();
            for (int i = mSettingsStates.size()-1; i >= 0; i--) {
                users.put(getUserIdFromKey(mSettingsStates.keyAt(i)), true);
            }
            return users;
        }

        @GuardedBy("mLock")
        @Nullable
        public SettingsState getSettingsLocked(int type, int userId) {
            final int key = makeKey(type, userId);
            return mSettingsStates.get(key);
        }

        @GuardedBy("mLock")
        @Nullable
        private SettingsState getOrCreateSettingsStateLocked(int key) {
            SettingsState settingsState = mSettingsStates.get(key);
            if (settingsState != null) {
                return settingsState;
            }

            if (!ensureSettingsForUserLocked(getUserIdFromKey(key))) {
                return null;
            }
            return mSettingsStates.get(key);
        }

        @GuardedBy("mLock")
        public boolean ensureSettingsForUserLocked(int userId) {
            // First make sure this user actually exists.
            if (mUserManager.getUserInfo(userId) == null) {
                Slog.wtf(LOG_TAG, "Requested user " + userId + " does not exist");
                return false;
            }

            // Migrate the setting for this user if needed.
            migrateLegacySettingsForUserIfNeededLocked(userId);

            // Ensure config settings loaded if owner.
            if (userId == UserHandle.USER_SYSTEM) {
                final int configKey
                        = makeKey(SETTINGS_TYPE_CONFIG, UserHandle.USER_SYSTEM);
                ensureSettingsStateLocked(configKey);
            }

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

        @GuardedBy("mLock")
        private void ensureSettingsStateLocked(int key) {
            if (mSettingsStates.get(key) == null) {
                final int maxBytesPerPackage = getMaxBytesPerPackageForType(getTypeFromKey(key));
                SettingsState settingsState = new SettingsState(getContext(), mLock,
                        getSettingsFile(key), key, maxBytesPerPackage, mHandlerThread.getLooper());
                mSettingsStates.put(key, settingsState);
            }
        }

        @GuardedBy("mLock")
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
                    systemSettingsState.destroyLocked(() -> mSettingsStates.remove(systemKey));
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
                    secureSettingsState.destroyLocked(() -> mSettingsStates.remove(secureKey));
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
                    ssaidSettingsState.destroyLocked(() -> mSettingsStates.remove(ssaidKey));
                }
            }

            // Nuke generation tracking data
            mGenerationRegistry.onUserRemoved(userId);
        }

        @GuardedBy("mLock")
        public boolean insertSettingLocked(int type, int userId, String name, String value,
                String tag, boolean makeDefault, String packageName, boolean forceNotify,
                Set<String> criticalSettings, boolean overrideableByRestore) {
            return insertSettingLocked(type, userId, name, value, tag, makeDefault, false,
                    packageName, forceNotify, criticalSettings, overrideableByRestore);
        }

        @GuardedBy("mLock")
        public boolean insertSettingLocked(int type, int userId, String name, String value,
                String tag, boolean makeDefault, boolean forceNonSystemPackage, String packageName,
                boolean forceNotify, Set<String> criticalSettings, boolean overrideableByRestore) {
            if (overrideableByRestore != Settings.DEFAULT_OVERRIDEABLE_BY_RESTORE) {
                getContext().enforceCallingOrSelfPermission(
                        Manifest.permission.MODIFY_SETTINGS_OVERRIDEABLE_BY_RESTORE,
                        "Caller is not allowed to modify settings overrideable by restore");
            }
            final int key = makeKey(type, userId);

            boolean success = false;
            boolean wasUnsetNonPredefinedSetting = false;
            SettingsState settingsState = getOrCreateSettingsStateLocked(key);
            if (settingsState != null) {
                if (!isSettingPreDefined(name, type) && !settingsState.hasSetting(name)) {
                    wasUnsetNonPredefinedSetting = true;
                }
                success = settingsState.insertSettingLocked(name, value,
                        tag, makeDefault, forceNonSystemPackage, packageName,
                        overrideableByRestore);
            }

            if (success && criticalSettings != null && criticalSettings.contains(name)) {
                settingsState.persistSettingsLocked();
            }

            if (forceNotify || success) {
                notifyForSettingsChange(key, name);
                if (wasUnsetNonPredefinedSetting) {
                    // Increment the generation number for all non-predefined, unset settings,
                    // because a new non-predefined setting has been inserted
                    mGenerationRegistry.incrementGenerationForUnsetSettings(key);
                }
            }
            if (success) {
                logSettingChanged(userId, name, type, CHANGE_TYPE_INSERT);
            }
            return success;
        }

        /**
         * Set Config Settings using consumed keyValues, returns true if the keyValues can be set,
         * false otherwise.
         */
        @GuardedBy("mLock")
        public boolean setConfigSettingsLocked(int key, String prefix,
                Map<String, String> keyValues, String packageName) {
            SettingsState settingsState = getOrCreateSettingsStateLocked(key);
            if (settingsState != null) {
                if (settingsState.isNewConfigBannedLocked(prefix, keyValues)) {
                    return false;
                }
                settingsState.unbanAllConfigIfBannedConfigUpdatedLocked(prefix);
                List<String> changedSettings =
                        settingsState.setSettingsLocked(prefix, keyValues, packageName);
                if (!changedSettings.isEmpty()) {
                    reportDeviceConfigUpdate(prefix);
                    notifyForConfigSettingsChangeLocked(key, prefix, changedSettings);
                }
            }
            // keyValues aren't banned and can be set
            return true;
        }

        @GuardedBy("mLock")
        public boolean deleteSettingLocked(int type, int userId, String name, boolean forceNotify,
                Set<String> criticalSettings) {
            final int key = makeKey(type, userId);

            boolean success = false;
            SettingsState settingsState = getOrCreateSettingsStateLocked(key);
            if (settingsState != null) {
                success = settingsState.deleteSettingLocked(name);
            }

            if (success && criticalSettings != null && criticalSettings.contains(name)) {
                settingsState.persistSettingsLocked();
            }

            if (forceNotify || success) {
                notifyForSettingsChange(key, name);
            }
            if (success) {
                logSettingChanged(userId, name, type, CHANGE_TYPE_DELETE);
            }
            return success;
        }

        @GuardedBy("mLock")
        public boolean updateSettingLocked(int type, int userId, String name, String value,
                String tag, boolean makeDefault, String packageName, boolean forceNotify,
                Set<String> criticalSettings) {
            final int key = makeKey(type, userId);

            boolean success = false;
            SettingsState settingsState = getOrCreateSettingsStateLocked(key);
            if (settingsState != null) {
                success = settingsState.updateSettingLocked(name, value, tag,
                        makeDefault, packageName);
            }

            if (success && criticalSettings != null && criticalSettings.contains(name)) {
                settingsState.persistSettingsLocked();
            }

            if (forceNotify || success) {
                notifyForSettingsChange(key, name);
            }
            if (success) {
                logSettingChanged(userId, name, type, CHANGE_TYPE_UPDATE);
            }
            return success;
        }

        @GuardedBy("mLock")
        public Setting getSettingLocked(int type, int userId, String name) {
            final int key = makeKey(type, userId);

            SettingsState settingsState = mSettingsStates.get(key);
            if (settingsState == null) {
                return null;
            }

            // getSettingLocked will return non-null result
            return settingsState.getSettingLocked(name);
        }

        private static boolean shouldExcludeSettingFromReset(Setting setting, String prefix) {
            // If a prefix was specified, exclude settings whose names don't start with it.
            if (prefix != null && !setting.getName().startsWith(prefix)) {
                return true;
            }
            // Never reset SECURE_FRP_MODE, as it could be abused to bypass FRP via RescueParty.
            return Global.SECURE_FRP_MODE.equals(setting.getName());
        }

        @GuardedBy("mLock")
        public boolean resetSettingsLocked(int type, int userId, String packageName, int mode,
                String tag) {
            return resetSettingsLocked(type, userId, packageName, mode, tag, /*prefix=*/
                    null);
        }

        @GuardedBy("mLock")
        public boolean resetSettingsLocked(int type, int userId, String packageName, int mode,
                String tag, @Nullable String prefix) {
            final int key = makeKey(type, userId);
            SettingsState settingsState = getOrCreateSettingsStateLocked(key);
            if (settingsState == null) {
                return false;
            }

            boolean success = false;
            banConfigurationIfNecessary(type, prefix, settingsState);
            switch (mode) {
                case Settings.RESET_MODE_PACKAGE_DEFAULTS -> {
                    for (String name : settingsState.getSettingNamesLocked()) {
                        boolean someSettingChanged = false;
                        Setting setting = settingsState.getSettingLocked(name);
                        if (packageName.equals(setting.getPackageName())) {
                            if ((tag != null && !tag.equals(setting.getTag()))
                                    || shouldExcludeSettingFromReset(setting, prefix)) {
                                continue;
                            }
                            if (settingsState.resetSettingLocked(name)) {
                                someSettingChanged = true;
                                notifyForSettingsChange(key, name);
                                logSettingChanged(userId, name, type, CHANGE_TYPE_RESET);
                            }
                        }
                        if (someSettingChanged) {
                            settingsState.persistSettingsLocked();
                            success = true;
                        }
                    }
                }
                case Settings.RESET_MODE_UNTRUSTED_DEFAULTS -> {
                    for (String name : settingsState.getSettingNamesLocked()) {
                        boolean someSettingChanged = false;
                        Setting setting = settingsState.getSettingLocked(name);
                        if (!SettingsState.isSystemPackage(getContext(),
                                setting.getPackageName())) {
                            if (shouldExcludeSettingFromReset(setting, prefix)) {
                                continue;
                            }
                            if (settingsState.resetSettingLocked(name)) {
                                someSettingChanged = true;
                                notifyForSettingsChange(key, name);
                                logSettingChanged(userId, name, type, CHANGE_TYPE_RESET);
                            }
                        }
                        if (someSettingChanged) {
                            settingsState.persistSettingsLocked();
                            success = true;
                        }
                    }
                }
                case Settings.RESET_MODE_UNTRUSTED_CHANGES -> {
                    for (String name : settingsState.getSettingNamesLocked()) {
                        boolean someSettingChanged = false;
                        Setting setting = settingsState.getSettingLocked(name);
                        if (!SettingsState.isSystemPackage(getContext(),
                                setting.getPackageName())) {
                            if (shouldExcludeSettingFromReset(setting, prefix)) {
                                continue;
                            }
                            if (setting.isDefaultFromSystem()) {
                                if (settingsState.resetSettingLocked(name)) {
                                    someSettingChanged = true;
                                    notifyForSettingsChange(key, name);
                                    logSettingChanged(userId, name, type, CHANGE_TYPE_RESET);
                                }
                            } else if (settingsState.deleteSettingLocked(name)) {
                                someSettingChanged = true;
                                notifyForSettingsChange(key, name);
                                logSettingChanged(userId, name, type, CHANGE_TYPE_DELETE);
                            }
                        }
                        if (someSettingChanged) {
                            settingsState.persistSettingsLocked();
                            success = true;
                        }
                    }
                }
                case Settings.RESET_MODE_TRUSTED_DEFAULTS -> {
                    for (String name : settingsState.getSettingNamesLocked()) {
                        Setting setting = settingsState.getSettingLocked(name);
                        boolean someSettingChanged = false;
                        if (shouldExcludeSettingFromReset(setting, prefix)) {
                            continue;
                        }
                        if (setting.isDefaultFromSystem()) {
                            if (settingsState.resetSettingLocked(name)) {
                                someSettingChanged = true;
                                notifyForSettingsChange(key, name);
                                logSettingChanged(userId, name, type, CHANGE_TYPE_RESET);
                            }
                        } else if (settingsState.deleteSettingLocked(name)) {
                            someSettingChanged = true;
                            notifyForSettingsChange(key, name);
                            logSettingChanged(userId, name, type, CHANGE_TYPE_DELETE);
                        }
                        if (someSettingChanged) {
                            settingsState.persistSettingsLocked();
                            success = true;
                        }
                    }
                }
            }
            return success;
        }

        @GuardedBy("mLock")
        public void removeSettingsForPackageLocked(String packageName, int userId) {
            // Global and secure settings are signature protected. Apps signed
            // by the platform certificate are generally not uninstalled  and
            // the main exception is tests. We trust components signed
            // by the platform certificate and do not do a clean up after them.

            final int systemKey = makeKey(SETTINGS_TYPE_SYSTEM, userId);
            SettingsState systemSettings = mSettingsStates.get(systemKey);
            if (systemSettings != null) {
                systemSettings.removeSettingsForPackageLocked(packageName);
            }
        }

        @GuardedBy("mLock")
        public void onUidRemovedLocked(int uid) {
            final SettingsState ssaidSettings = getSettingsLocked(SETTINGS_TYPE_SSAID,
                    UserHandle.getUserId(uid));
            if (ssaidSettings != null) {
                ssaidSettings.deleteSettingLocked(Integer.toString(uid));
            }
        }

        @GuardedBy("mLock")
        private void migrateAllLegacySettingsIfNeededLocked() {
            final int key = makeKey(SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
            File globalFile = getSettingsFile(key);
            if (SettingsState.stateFileExists(globalFile)) {
                return;
            }

            mSettingsCreationBuildId = Build.ID;

            final long identity = Binder.clearCallingIdentity();
            try {
                List<UserInfo> users = mUserManager.getAliveUsers();

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

        @GuardedBy("mLock")
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

        @GuardedBy("mLock")
        private void migrateLegacySettingsForUserLocked(DatabaseHelper dbHelper,
                SQLiteDatabase database, int userId) {
            // Move over the system settings.
            final int systemKey = makeKey(SETTINGS_TYPE_SYSTEM, userId);
            ensureSettingsStateLocked(systemKey);
            SettingsState systemSettings = mSettingsStates.get(systemKey);
            migrateLegacySettingsLocked(systemSettings, database, TABLE_SYSTEM);
            systemSettings.persistSettingsLocked();

            // Move over the secure settings.
            // Do this after System settings, since this is the first thing we check when deciding
            // to skip over migration from db to xml for a secondary user.
            final int secureKey = makeKey(SETTINGS_TYPE_SECURE, userId);
            ensureSettingsStateLocked(secureKey);
            SettingsState secureSettings = mSettingsStates.get(secureKey);
            migrateLegacySettingsLocked(secureSettings, database, TABLE_SECURE);
            ensureSecureSettingAndroidIdSetLocked(secureSettings);
            secureSettings.persistSettingsLocked();

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
                globalSettings.persistSettingsLocked();
            }

            // Drop the database as now all is moved and persisted.
            if (DROP_DATABASE_ON_MIGRATION) {
                dbHelper.dropDatabase();
            } else {
                dbHelper.backupDatabase();
            }
        }

        @GuardedBy("mLock")
        private void migrateLegacySettingsLocked(SettingsState settingsState,
                SQLiteDatabase database, String table) {
            SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
            queryBuilder.setTables(table);

            Cursor cursor = queryBuilder.query(database, LEGACY_SQL_COLUMNS,
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

        @GuardedBy("mLock")
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
            mGenerationRegistry.incrementGeneration(key, name);

            if (isGlobalSettingsKey(key) || isConfigSettingsKey(key)) {
                final long token = Binder.clearCallingIdentity();
                try {
                    notifySettingChangeForRunningUsers(key, name);
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
                } else if (isSystemSettingsKey(key)) {
                    maybeNotifyProfiles(getTypeFromKey(key), userId, uri, name,
                            sSystemCloneToManagedSettings);
                    maybeNotifyProfiles(SETTINGS_TYPE_SYSTEM, userId, uri, name,
                            sSystemCloneFromParentOnDependency.keySet());
                }
            }

            // Always notify that our data changed
            mHandler.obtainMessage(MyHandler.MSG_NOTIFY_DATA_CHANGED).sendToTarget();
        }

        private void logSettingChanged(int userId, String name, int type, int changeType) {
            FrameworkStatsLog.write(FrameworkStatsLog.SETTINGS_PROVIDER_SETTING_CHANGED, userId,
                    name, type, changeType);
        }

        @GuardedBy("mLock")
        private void notifyForConfigSettingsChangeLocked(int key, String prefix,
                List<String> changedSettings) {

            // Increment the generation first, so observers always see the new value
            mGenerationRegistry.incrementGeneration(key, prefix);

            StringBuilder stringBuilder = new StringBuilder(prefix);
            for (int i = 0; i < changedSettings.size(); ++i) {
                stringBuilder.append(changedSettings.get(i).split("/")[1]).append("/");
            }

            final long token = Binder.clearCallingIdentity();
            try {
                notifySettingChangeForRunningUsers(key, stringBuilder.toString());
            } finally {
                Binder.restoreCallingIdentity(token);
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
                        mGenerationRegistry.incrementGeneration(key, name);
                        mHandler.obtainMessage(MyHandler.MSG_NOTIFY_URI_CHANGED,
                                profileId, 0, uri).sendToTarget();
                    }
                }
            }
        }

        private void notifySettingChangeForRunningUsers(int key, String name) {
            // Important: No need to update generation for each user as there
            // is a singleton generation entry for the global settings which
            // is already incremented be the caller.
            final Uri uri = getNotificationUriFor(key, name);
            final List<UserInfo> users = mUserManager.getAliveUsers();
            for (int i = 0; i < users.size(); i++) {
                final int userId = users.get(i).id;
                if (mUserManager.isUserRunning(UserHandle.of(userId))) {
                    mHandler.obtainMessage(MyHandler.MSG_NOTIFY_URI_CHANGED,
                            userId, 0, uri).sendToTarget();
                }
            }
        }

        private boolean shouldBan(int type) {
            if (SETTINGS_TYPE_CONFIG != type) {
                return false;
            }
            final int callingUid = Binder.getCallingUid();
            final int appId = UserHandle.getAppId(callingUid);

            // Only non-shell resets should result in namespace banning
            return appId != SHELL_UID;
        }

        private void banConfigurationIfNecessary(int type, @Nullable String prefix,
                SettingsState settingsState) {
            // Banning should be performed only for Settings.Config and for non-shell reset calls
            if (!shouldBan(type)) {
                return;
            }
            if (prefix != null) {
                settingsState.banConfigurationLocked(prefix, getAllConfigFlags(prefix));
            } else {
                Set<String> configPrefixes = settingsState.getAllConfigPrefixesLocked();
                for (String configPrefix : configPrefixes) {
                    settingsState.banConfigurationLocked(configPrefix,
                            getAllConfigFlags(configPrefix));
                }
            }
        }

        private static File getSettingsFile(int key) {
            final int userId = getUserIdFromKey(key);
            final int type = getTypeFromKey(key);
            final File userSystemDirectory = Environment.getUserSystemDirectory(userId);
            return switch (type) {
                case SETTINGS_TYPE_CONFIG -> new File(userSystemDirectory, SETTINGS_FILE_CONFIG);
                case SETTINGS_TYPE_GLOBAL -> new File(userSystemDirectory, SETTINGS_FILE_GLOBAL);
                case SETTINGS_TYPE_SYSTEM -> new File(userSystemDirectory, SETTINGS_FILE_SYSTEM);
                case SETTINGS_TYPE_SECURE -> new File(userSystemDirectory, SETTINGS_FILE_SECURE);
                case SETTINGS_TYPE_SSAID -> new File(userSystemDirectory, SETTINGS_FILE_SSAID);
                default -> throw new IllegalArgumentException("Invalid settings key:" + key);
            };
        }

        private Uri getNotificationUriFor(int key, String name) {
            if (isConfigSettingsKey(key)) {
                return (name != null) ? Uri.withAppendedPath(Settings.Config.CONTENT_URI, name)
                        : Settings.Config.CONTENT_URI;
            } else if (isGlobalSettingsKey(key)) {
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
                case SETTINGS_TYPE_CONFIG, SETTINGS_TYPE_GLOBAL, SETTINGS_TYPE_SECURE,
                        SETTINGS_TYPE_SSAID -> {
                    return SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED;
                }
                default -> {
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
                    case MSG_NOTIFY_URI_CHANGED -> {
                        final int userId = msg.arg1;
                        Uri uri = (Uri) msg.obj;
                        try {
                            getContext().getContentResolver().notifyChange(uri, null, true, userId);
                        } catch (SecurityException e) {
                            Slog.w(LOG_TAG, "Failed to notify for " + userId + ": " + uri, e);
                        }
                        if (DEBUG) {
                            Slog.v(LOG_TAG, "Notifying for " + userId + ": " + uri);
                        }
                    }
                    case MSG_NOTIFY_DATA_CHANGED -> {
                        mBackupManager.dataChanged();
                        scheduleWriteFallbackFilesJob();
                    }
                }
            }
        }

        private final class UpgradeController {
            private static final int SETTINGS_VERSION = 224;

            private final int mUserId;

            public UpgradeController(int userId) {
                mUserId = userId;
            }

            @GuardedBy("mLock")
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

            @GuardedBy("mLock")
            private SettingsState getGlobalSettingsLocked() {
                return getSettingsLocked(SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
            }

            @GuardedBy("mLock")
            private SettingsState getSecureSettingsLocked(int userId) {
                return getSettingsLocked(SETTINGS_TYPE_SECURE, userId);
            }

            @GuardedBy("mLock")
            private SettingsState getSsaidSettingsLocked(int userId) {
                return getSettingsLocked(SETTINGS_TYPE_SSAID, userId);
            }

            @GuardedBy("mLock")
            private SettingsState getSystemSettingsLocked(int userId) {
                return getSettingsLocked(SETTINGS_TYPE_SYSTEM, userId);
            }

            /**
             * You must perform all necessary mutations to bring the settings
             * for this user from the old to the new version. When you add a new
             * upgrade step you *must* update SETTINGS_VERSION.
             *
             * All settings modifications should be made through
             * {@link SettingsState#insertSettingOverrideableByRestoreLocked(String, String, String,
             * boolean, String)} so that restore can override those values if needed.
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
            @GuardedBy("mLock")
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
                    secureSettings.insertSettingOverrideableByRestoreLocked(
                            Settings.Secure.DOUBLE_TAP_TO_WAKE,
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
                        secureSettings.insertSettingOverrideableByRestoreLocked(
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
                            globalSettings.insertSettingOverrideableByRestoreLocked(
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
                    globalSettings.insertSettingOverrideableByRestoreLocked(
                            Settings.Global.BLUETOOTH_DISABLED_PROFILES, defaultDisabledProfiles,
                            null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    currentVersion = 124;
                }

                if (currentVersion == 124) {
                    // Version 124: allow OEMs to set a default value for whether IME should be
                    // shown when a physical keyboard is connected.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    Setting currentSetting = secureSettings.getSettingLocked(
                            Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD);
                    if (currentSetting.isNull()) {
                        secureSettings.insertSettingOverrideableByRestoreLocked(
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
                        List<ComponentName> l = mSysConfigManager.getDefaultVrComponents();

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
                            secureSettings.insertSettingOverrideableByRestoreLocked(
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
                            secureSettings.insertSettingOverrideableByRestoreLocked(
                                    Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                                    showNotifications.getValue(), null, true,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }

                        final Setting allowPrivate = systemSecureSettings.getSettingLocked(
                                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
                        if (!allowPrivate.isNull()) {
                            final SettingsState secureSettings = getSecureSettingsLocked(userId);
                            secureSettings.insertSettingOverrideableByRestoreLocked(
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
                        systemSecureSettings.insertSettingOverrideableByRestoreLocked(
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
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.Secure.DOZE_PICK_UP_GESTURE, "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE, "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 131;
                }

                if (currentVersion == 131) {
                    // Initialize new multi-press timeout to default value
                    final SettingsState systemSecureSettings = getSecureSettingsLocked(userId);
                    final String oldValue = systemSecureSettings.getSettingLocked(
                            Settings.Secure.MULTI_PRESS_TIMEOUT).getValue();
                    if (TextUtils.equals(null, oldValue)) {
                        systemSecureSettings.insertSettingOverrideableByRestoreLocked(
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
                    systemSecureSettings.insertSettingOverrideableByRestoreLocked(
                            Settings.Secure.SYNC_PARENT_SOUNDS, defaultSyncParentSounds,
                            null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    currentVersion = 133;
                }

                if (currentVersion == 133) {
                    // Version 133: Add default end button behavior
                    final SettingsState systemSettings = getSystemSettingsLocked(userId);
                    if (systemSettings.getSettingLocked(Settings.System.END_BUTTON_BEHAVIOR)
                            .isNull()) {
                        String defaultEndButtonBehavior = Integer.toString(getContext()
                                .getResources().getInteger(R.integer.def_end_button_behavior));
                        systemSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.System.END_BUTTON_BEHAVIOR, defaultEndButtonBehavior, null,
                                true, SettingsState.SYSTEM_PACKAGE_NAME);
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
                        isUpgrade = mPackageManager.isDeviceUpgrading();
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
                                ssaidSettings.insertSettingOverrideableByRestoreLocked(uid,
                                        legacySsaid, null, true, info.packageName);
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

                        secureSetting.insertSettingOverrideableByRestoreLocked(
                                Settings.Secure.INSTALL_NON_MARKET_APPS, "1", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                        // For managed profiles with profile owners, DevicePolicyManagerService
                        // may want to set the user restriction in this case
                        secureSetting.insertSettingOverrideableByRestoreLocked(
                                Settings.Secure.UNKNOWN_SOURCES_DEFAULT_REVERSED, "1", null,
                                true, SettingsState.SYSTEM_PACKAGE_NAME);
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
                            globalSettings.insertSettingOverrideableByRestoreLocked(
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
                            secureSettings.insertSettingOverrideableByRestoreLocked(
                                    Settings.Secure.AUTOFILL_SERVICE, defaultValue, null, true,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
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
                        globalSettings.persistSettingsLocked();
                    }

                    SettingsState secureSettings = getSecureSettingsLocked(mUserId);
                    ensureLegacyDefaultValueAndSystemSetUpdatedLocked(secureSettings, userId);
                    secureSettings.persistSettingsLocked();

                    SettingsState systemSettings = getSystemSettingsLocked(mUserId);
                    ensureLegacyDefaultValueAndSystemSetUpdatedLocked(systemSettings, userId);
                    systemSettings.persistSettingsLocked();

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
                            globalSettings.insertSettingOverrideableByRestoreLocked(
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
                            systemSecureSettings.insertSettingOverrideableByRestoreLocked(
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
                        globalSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.Global.MOBILE_DATA_ALWAYS_ON,
                                getContext().getResources().getBoolean(
                                        R.bool.def_mobile_data_always_on) ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 150;
                }

                if (currentVersion == 150) {
                    // Version 151: Removed.
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
                        systemSecureSettings.insertSettingOverrideableByRestoreLocked(
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
                            systemSecureSettings.insertSettingOverrideableByRestoreLocked(
                                    Settings.Secure.BACKUP_LOCAL_TRANSPORT_PARAMETERS, defaultValue,
                                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }

                    }
                    currentVersion = 155;
                }

                if (currentVersion == 155) {
                    // Version 156: migrated to version 184
                    currentVersion = 156;
                }

                if (currentVersion == 156) {
                    // Version 157: Set a default value for zen duration,
                    // in version 169, zen duration is moved to secure settings
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final Setting currentSetting = globalSettings.getSettingLocked(
                            Global.ZEN_DURATION);
                    if (currentSetting.isNull()) {
                        String defaultZenDuration = Integer.toString(getContext()
                                .getResources().getInteger(R.integer.def_zen_duration));
                        globalSettings.insertSettingOverrideableByRestoreLocked(
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
                            globalSettings.insertSettingOverrideableByRestoreLocked(
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
                            secureSettings.insertSettingOverrideableByRestoreLocked(
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
                        globalSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY,
                                Integer.toString(getContext().getResources().getInteger(
                                        R.integer.def_max_sound_trigger_detection_service_ops_per_day)),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    oldValue = globalSettings.getSettingLocked(
                            Global.SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT).getValue();
                    if (TextUtils.equals(null, oldValue)) {
                        globalSettings.insertSettingOverrideableByRestoreLocked(
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
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.VOLUME_HUSH_GESTURE,
                                Integer.toString(Secure.VOLUME_HUSH_VIBRATE),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 162;
                }

                if (currentVersion == 162) {
                    // Version 162: REMOVED: Add a gesture for silencing phones
                    currentVersion = 163;
                }

                if (currentVersion == 163) {
                    // Version 163: Update default value of
                    // MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY from old to new default
                    final SettingsState settings = getGlobalSettingsLocked();
                    final Setting currentSetting = settings.getSettingLocked(
                            Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY);
                    if (currentSetting.isDefaultFromSystem()) {
                        settings.insertSettingOverrideableByRestoreLocked(
                                Settings.Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY,
                                Integer.toString(getContext().getResources().getInteger(
                                        R.integer
                                        .def_max_sound_trigger_detection_service_ops_per_day)),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 164;
                }

                if (currentVersion == 164) {
                    // Version 164: REMOVED: show zen upgrade notification
                    currentVersion = 165;
                }

                if (currentVersion == 165) {
                    // Version 165: MOVED: Show zen settings suggestion and zen updated settings
                    // moved to secure settings and are set in version 169
                    currentVersion = 166;
                }

                if (currentVersion == 166) {
                    // Version 166: add default values for hush gesture used and manual ringer
                    // toggle
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    Setting currentHushUsedSetting = secureSettings.getSettingLocked(
                            Secure.HUSH_GESTURE_USED);
                    if (currentHushUsedSetting.isNull()) {
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.Secure.HUSH_GESTURE_USED, "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    Setting currentRingerToggleCountSetting = secureSettings.getSettingLocked(
                            Secure.MANUAL_RINGER_TOGGLE_COUNT);
                    if (currentRingerToggleCountSetting.isNull()) {
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.Secure.MANUAL_RINGER_TOGGLE_COUNT, "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 167;
                }

                if (currentVersion == 167) {
                    // Version 167: MOVED - Settings.Global.CHARGING_VIBRATION_ENABLED moved to
                    // Settings.Secure.CHARGING_VIBRATION_ENABLED, set in version 170
                    currentVersion = 168;
                }

                if (currentVersion == 168) {
                    // Version 168: by default, vibrate for phone calls
                    final SettingsState systemSettings = getSystemSettingsLocked(userId);
                    final Setting currentSetting = systemSettings.getSettingLocked(
                            Settings.System.VIBRATE_WHEN_RINGING);
                    if (currentSetting.isNull()) {
                        systemSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.System.VIBRATE_WHEN_RINGING,
                                getContext().getResources().getBoolean(
                                        R.bool.def_vibrate_when_ringing) ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 169;
                }

                if (currentVersion == 169) {
                    // Version 169: Set the default value for Secure Settings ZEN_DURATION,
                    // SHOW_ZEN_SETTINGS_SUGGESTION, ZEN_SETTINGS_UPDATE and
                    // ZEN_SETTINGS_SUGGESTION_VIEWED

                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final Setting globalZenDuration = globalSettings.getSettingLocked(
                            Global.ZEN_DURATION);

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting secureZenDuration = secureSettings.getSettingLocked(
                            Secure.ZEN_DURATION);

                    // ZEN_DURATION
                    if (!globalZenDuration.isNull()) {
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.ZEN_DURATION, globalZenDuration.getValue(), null, false,
                                SettingsState.SYSTEM_PACKAGE_NAME);

                        // set global zen duration setting to null since it's deprecated
                        globalSettings.insertSettingOverrideableByRestoreLocked(
                                Global.ZEN_DURATION, null, null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    } else if (secureZenDuration.isNull()) {
                        String defaultZenDuration = Integer.toString(getContext()
                                .getResources().getInteger(R.integer.def_zen_duration));
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.ZEN_DURATION, defaultZenDuration, null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    // SHOW_ZEN_SETTINGS_SUGGESTION
                    final Setting currentShowZenSettingSuggestion = secureSettings.getSettingLocked(
                            Secure.SHOW_ZEN_SETTINGS_SUGGESTION);
                    if (currentShowZenSettingSuggestion.isNull()) {
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.SHOW_ZEN_SETTINGS_SUGGESTION, "1",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    // ZEN_SETTINGS_UPDATED
                    final Setting currentUpdatedSetting = secureSettings.getSettingLocked(
                            Secure.ZEN_SETTINGS_UPDATED);
                    if (currentUpdatedSetting.isNull()) {
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.ZEN_SETTINGS_UPDATED, "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    // ZEN_SETTINGS_SUGGESTION_VIEWED
                    final Setting currentSettingSuggestionViewed = secureSettings.getSettingLocked(
                            Secure.ZEN_SETTINGS_SUGGESTION_VIEWED);
                    if (currentSettingSuggestionViewed.isNull()) {
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.ZEN_SETTINGS_SUGGESTION_VIEWED, "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 170;
                }

                if (currentVersion == 170) {
                    // Version 170: Set the default value for Secure Settings:
                    // CHARGING_SOUNDS_ENABLED and CHARGING_VIBRATION_ENABLED

                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);

                    // CHARGING_SOUNDS_ENABLED
                    final Setting globalChargingSoundEnabled = globalSettings.getSettingLocked(
                            Global.CHARGING_SOUNDS_ENABLED);
                    final Setting secureChargingSoundsEnabled = secureSettings.getSettingLocked(
                            Secure.CHARGING_SOUNDS_ENABLED);

                    if (!globalChargingSoundEnabled.isNull()) {
                        if (secureChargingSoundsEnabled.isNull()) {
                            secureSettings.insertSettingOverrideableByRestoreLocked(
                                    Secure.CHARGING_SOUNDS_ENABLED,
                                    globalChargingSoundEnabled.getValue(), null, false,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }

                        // set global charging_sounds_enabled setting to null since it's deprecated
                        globalSettings.insertSettingOverrideableByRestoreLocked(
                                Global.CHARGING_SOUNDS_ENABLED, null, null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    } else if (secureChargingSoundsEnabled.isNull()) {
                        String defChargingSoundsEnabled = getContext().getResources()
                                .getBoolean(R.bool.def_charging_sounds_enabled) ? "1" : "0";
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.CHARGING_SOUNDS_ENABLED, defChargingSoundsEnabled, null,
                                true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    // CHARGING_VIBRATION_ENABLED
                    final Setting secureChargingVibrationEnabled = secureSettings.getSettingLocked(
                            Secure.CHARGING_VIBRATION_ENABLED);

                    if (secureChargingVibrationEnabled.isNull()) {
                        String defChargingVibrationEnabled = getContext().getResources()
                                .getBoolean(R.bool.def_charging_vibration_enabled) ? "1" : "0";
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.CHARGING_VIBRATION_ENABLED, defChargingVibrationEnabled,
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 171;
                }

                if (currentVersion == 171) {
                    // Version 171: by default, add STREAM_VOICE_CALL to list of streams that can
                    // be muted.
                    final SettingsState systemSettings = getSystemSettingsLocked(userId);
                    final Setting currentSetting = systemSettings.getSettingLocked(
                              Settings.System.MUTE_STREAMS_AFFECTED);
                    if (!currentSetting.isNull()) {
                        try {
                            int currentSettingIntegerValue = Integer.parseInt(
                                    currentSetting.getValue());
                            if ((currentSettingIntegerValue
                                 & (1 << AudioManager.STREAM_VOICE_CALL)) == 0) {
                                systemSettings.insertSettingOverrideableByRestoreLocked(
                                    Settings.System.MUTE_STREAMS_AFFECTED,
                                    Integer.toString(
                                        currentSettingIntegerValue
                                        | (1 << AudioManager.STREAM_VOICE_CALL)),
                                    null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                            }
                        } catch (NumberFormatException e) {
                            // remove the setting in case it is not a valid integer
                            Slog.w("Failed to parse integer value of MUTE_STREAMS_AFFECTED"
                                   + "setting, removing setting", e);
                            systemSettings.deleteSettingLocked(
                                Settings.System.MUTE_STREAMS_AFFECTED);
                        }

                    }
                    currentVersion = 172;
                }

                if (currentVersion == 172) {
                    // Version 172: Set the default value for Secure Settings: LOCATION_MODE

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);

                    final Setting locationMode = secureSettings.getSettingLocked(
                            Secure.LOCATION_MODE);

                    if (locationMode.isNull()) {
                        final Setting locationProvidersAllowed = secureSettings.getSettingLocked(
                                Secure.LOCATION_PROVIDERS_ALLOWED);

                        final int defLocationMode;
                        if (locationProvidersAllowed.isNull()) {
                            defLocationMode = getContext().getResources().getInteger(
                                    R.integer.def_location_mode);
                        } else {
                            defLocationMode =
                                    !TextUtils.isEmpty(locationProvidersAllowed.getValue())
                                            ? Secure.LOCATION_MODE_ON
                                            : Secure.LOCATION_MODE_OFF;
                        }
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.LOCATION_MODE, Integer.toString(defLocationMode),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 173;
                }

                if (currentVersion == 173) {
                    // Version 173: Set the default value for Secure Settings: NOTIFICATION_BUBBLES
                    // Removed. Moved NOTIFICATION_BUBBLES to Global Settings.
                    currentVersion = 174;
                }

                if (currentVersion == 174) {
                    // Version 174: Set the default value for Global Settings: APPLY_RAMPING_RINGER
                    // Removed. Moved APPLY_RAMPING_RINGER to System Settings, set in version 206.

                    currentVersion = 175;
                }

                if (currentVersion == 175) {
                    // Version 175: Set the default value for System Settings:
                    // RING_VIBRATION_INTENSITY. If the notification vibration intensity has been
                    // set and ring vibration intensity hasn't, the ring vibration intensity should
                    // followed notification vibration intensity.

                    final SettingsState systemSettings = getSystemSettingsLocked(userId);

                    Setting notificationVibrationIntensity = systemSettings.getSettingLocked(
                            Settings.System.NOTIFICATION_VIBRATION_INTENSITY);

                    Setting ringVibrationIntensity = systemSettings.getSettingLocked(
                            Settings.System.RING_VIBRATION_INTENSITY);

                    if (!notificationVibrationIntensity.isNull()
                            && ringVibrationIntensity.isNull()) {
                        systemSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.System.RING_VIBRATION_INTENSITY,
                                notificationVibrationIntensity.getValue(),
                                null , true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 176;
                }

                if (currentVersion == 176) {
                    // Version 176: Migrate the existing swipe up setting into the resource overlay
                    //              for the navigation bar interaction mode.  We do so only if the
                    //              setting is set.

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting swipeUpSetting = secureSettings.getSettingLocked(
                            "swipe_up_to_switch_apps_enabled");
                    if (swipeUpSetting != null && !swipeUpSetting.isNull()
                            && swipeUpSetting.getValue().equals("1")) {
                        final IOverlayManager overlayManager = IOverlayManager.Stub.asInterface(
                                ServiceManager.getService(Context.OVERLAY_SERVICE));
                        try {
                            overlayManager.setEnabledExclusiveInCategory(
                                    NAV_BAR_MODE_2BUTTON_OVERLAY, UserHandle.USER_CURRENT);
                        } catch (SecurityException | IllegalStateException | RemoteException e) {
                            throw new IllegalStateException(
                                    "Failed to set nav bar interaction mode overlay");
                        }
                    }

                    currentVersion = 177;
                }

                if (currentVersion == 177) {
                    // Version 177: Set the default value for Secure Settings: AWARE_ENABLED

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);

                    final Setting awareEnabled = secureSettings.getSettingLocked(
                            Secure.AWARE_ENABLED);

                    if (awareEnabled.isNull()) {
                        final boolean defAwareEnabled = getContext().getResources().getBoolean(
                                R.bool.def_aware_enabled);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.AWARE_ENABLED, defAwareEnabled ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 178;
                }

                if (currentVersion == 178) {
                    // Version 178: Set the default value for Secure Settings:
                    // SKIP_GESTURE & SILENCE_GESTURE

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);

                    final Setting skipGesture = secureSettings.getSettingLocked(
                            Secure.SKIP_GESTURE);

                    if (skipGesture.isNull()) {
                        final boolean defSkipGesture = getContext().getResources().getBoolean(
                                R.bool.def_skip_gesture);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.SKIP_GESTURE, defSkipGesture ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    final Setting silenceGesture = secureSettings.getSettingLocked(
                            Secure.SILENCE_GESTURE);

                    if (silenceGesture.isNull()) {
                        final boolean defSilenceGesture = getContext().getResources().getBoolean(
                                R.bool.def_silence_gesture);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.SILENCE_GESTURE, defSilenceGesture ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 179;
                }

                if (currentVersion == 179) {
                    // Version 178: Reset the default for Secure Settings: NOTIFICATION_BUBBLES
                    // This is originally set in version 173, however, the default value changed
                    // so this step is to ensure the value is updated to the correct default.

                    // Removed. Moved NOTIFICATION_BUBBLES to Global Settings.
                    currentVersion = 180;
                }

                if (currentVersion == 180) {
                    // Version 180: Set the default value for Secure Settings: AWARE_LOCK_ENABLED

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);

                    final Setting awareLockEnabled = secureSettings.getSettingLocked(
                            Secure.AWARE_LOCK_ENABLED);

                    if (awareLockEnabled.isNull()) {
                        final boolean defAwareLockEnabled = getContext().getResources().getBoolean(
                                R.bool.def_aware_lock_enabled);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.AWARE_LOCK_ENABLED, defAwareLockEnabled ? "1" : "0",
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 181;
                }

                if (currentVersion == 181) {
                    // Version cd : by default, add STREAM_BLUETOOTH_SCO to list of streams that can
                    // be muted.
                    final SettingsState systemSettings = getSystemSettingsLocked(userId);
                    final Setting currentSetting = systemSettings.getSettingLocked(
                              Settings.System.MUTE_STREAMS_AFFECTED);
                    if (!currentSetting.isNull()) {
                        try {
                            int currentSettingIntegerValue = Integer.parseInt(
                                    currentSetting.getValue());
                            if ((currentSettingIntegerValue
                                    & (1 << AudioManager.STREAM_BLUETOOTH_SCO)) == 0) {
                                systemSettings.insertSettingOverrideableByRestoreLocked(
                                        Settings.System.MUTE_STREAMS_AFFECTED,
                                        Integer.toString(
                                        currentSettingIntegerValue
                                        | (1 << AudioManager.STREAM_BLUETOOTH_SCO)),
                                        null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                            }
                        } catch (NumberFormatException e) {
                            // remove the setting in case it is not a valid integer
                            Slog.w("Failed to parse integer value of MUTE_STREAMS_AFFECTED"
                                    + "setting, removing setting", e);
                            systemSettings.deleteSettingLocked(
                                    Settings.System.MUTE_STREAMS_AFFECTED);
                        }

                    }
                    currentVersion = 182;
                }

                if (currentVersion == 182) {
                    // Remove secure bubble settings; it's in global now.
                    getSecureSettingsLocked(userId).deleteSettingLocked("notification_bubbles");

                    // Removed. Updated NOTIFICATION_BUBBLES to be true by default, see 184.
                    currentVersion = 183;
                }

                if (currentVersion == 183) {
                    // Version 183: Set default values for WIRELESS_CHARGING_STARTED_SOUND
                    // and CHARGING_STARTED_SOUND
                    final SettingsState globalSettings = getGlobalSettingsLocked();

                    final String oldValueWireless = globalSettings.getSettingLocked(
                            Global.WIRELESS_CHARGING_STARTED_SOUND).getValue();
                    final String oldValueWired = globalSettings.getSettingLocked(
                            Global.CHARGING_STARTED_SOUND).getValue();

                    final String defaultValueWireless = getContext().getResources().getString(
                            R.string.def_wireless_charging_started_sound);
                    final String defaultValueWired = getContext().getResources().getString(
                            R.string.def_charging_started_sound);

                    // wireless charging sound
                    if (oldValueWireless == null
                            || TextUtils.equals(oldValueWireless, defaultValueWired)) {
                        if (!TextUtils.isEmpty(defaultValueWireless)) {
                            globalSettings.insertSettingOverrideableByRestoreLocked(
                                    Global.WIRELESS_CHARGING_STARTED_SOUND, defaultValueWireless,
                                    null /* tag */, true /* makeDefault */,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        } else if (!TextUtils.isEmpty(defaultValueWired)) {
                            // if the wireless sound is empty, use the wired charging sound
                            globalSettings.insertSettingOverrideableByRestoreLocked(
                                    Global.WIRELESS_CHARGING_STARTED_SOUND, defaultValueWired,
                                    null /* tag */, true /* makeDefault */,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }

                    // wired charging sound
                    if (oldValueWired == null && !TextUtils.isEmpty(defaultValueWired)) {
                        globalSettings.insertSettingOverrideableByRestoreLocked(
                                Global.CHARGING_STARTED_SOUND, defaultValueWired,
                                null /* tag */, true /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 184;
                }

                if (currentVersion == 184) {
                    // Version 184: Reset the default for Global Settings: NOTIFICATION_BUBBLES
                    // This is originally set in version 182, however, the default value changed
                    // so this step is to ensure the value is updated to the correct default.

                    // Removed. Bubbles moved to secure settings. See version 197.
                    currentVersion = 185;
                }

                if (currentVersion == 185) {
                    // Deprecate ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED, and migrate it
                    // to ACCESSIBILITY_BUTTON_TARGETS.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting magnifyNavbarEnabled = secureSettings.getSettingLocked(
                            Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED);
                    if ("1".equals(magnifyNavbarEnabled.getValue())) {
                        secureSettings.insertSettingLocked(
                                Secure.ACCESSIBILITY_BUTTON_TARGETS,
                                ACCESSIBILITY_SHORTCUT_TARGET_MAGNIFICATION_CONTROLLER,
                                null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    secureSettings.deleteSettingLocked(
                            Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED);
                    currentVersion = 186;
                }

                if (currentVersion == 186) {
                    // Remove unused wifi settings
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_rtt_background_exec_gap_ms");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "network_recommendation_request_timeout_ms");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_suspend_optimizations_enabled");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_is_unusable_event_metrics_enabled");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_data_stall_min_tx_bad");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_data_stall_min_tx_success_without_rx");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_link_speed_metrics_enabled");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_pno_frequency_culling_enabled");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_pno_recency_sorting_enabled");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_link_probing_enabled");
                    getGlobalSettingsLocked().deleteSettingLocked(
                            "wifi_saved_state");
                    currentVersion = 187;
                }

                if (currentVersion == 187) {
                    // Migrate adaptive sleep setting from System to Secure.
                    if (userId == UserHandle.USER_OWNER) {
                        // Remove from the system settings.
                        SettingsState systemSettings = getSystemSettingsLocked(userId);
                        String name = Settings.System.ADAPTIVE_SLEEP;
                        Setting setting = systemSettings.getSettingLocked(name);
                        systemSettings.deleteSettingLocked(name);

                        // Add to the secure settings.
                        SettingsState secureSettings = getSecureSettingsLocked(userId);
                        secureSettings.insertSettingLocked(name, setting.getValue(), null /* tag */,
                                false /* makeDefault */, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 188;
                }

                if (currentVersion == 188) {
                    // Deprecate ACCESSIBILITY_SHORTCUT_ENABLED, and migrate it
                    // to ACCESSIBILITY_SHORTCUT_TARGET_SERVICE.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting shortcutEnabled = secureSettings.getSettingLocked(
                            "accessibility_shortcut_enabled");
                    if ("0".equals(shortcutEnabled.getValue())) {
                        // Clear shortcut key targets list setting.
                        secureSettings.insertSettingLocked(
                                Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                                "", null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    secureSettings.deleteSettingLocked("accessibility_shortcut_enabled");
                    currentVersion = 189;
                }

                if (currentVersion == 189) {
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting showNotifications = secureSettings.getSettingLocked(
                            Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS);
                    final Setting allowPrivateNotifications = secureSettings.getSettingLocked(
                            Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
                    if ("1".equals(showNotifications.getValue())
                            && "1".equals(allowPrivateNotifications.getValue())) {
                        secureSettings.insertSettingLocked(
                                Secure.POWER_MENU_LOCKED_SHOW_CONTENT,
                                "1", null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    } else if ("0".equals(showNotifications.getValue())
                            || "0".equals(allowPrivateNotifications.getValue())) {
                        secureSettings.insertSettingLocked(
                                Secure.POWER_MENU_LOCKED_SHOW_CONTENT,
                                "0", null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 190;
                }

                if (currentVersion == 190) {
                    // Version 190: get HDMI auto device off from overlay
                    // HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED settings option was removed
                    currentVersion = 191;
                }

                if (currentVersion == 191) {
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    int mode = getContext().getResources().getInteger(
                            com.android.internal.R.integer.config_navBarInteractionMode);
                    if (mode == NAV_BAR_MODE_GESTURAL) {
                        switchToDefaultGestureNavBackInset(userId, secureSettings);
                    }
                    migrateBackGestureSensitivity(Secure.BACK_GESTURE_INSET_SCALE_LEFT, userId,
                            secureSettings);
                    migrateBackGestureSensitivity(Secure.BACK_GESTURE_INSET_SCALE_RIGHT, userId,
                            secureSettings);
                    currentVersion = 192;
                }

                if (currentVersion == 192) {
                    // Version 192: set the default value for magnification capabilities.
                    // If the device supports magnification area and magnification is enabled
                    // by the user, set it to full-screen, and set a value to show a prompt
                    // when using the magnification first time after upgrading.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting magnificationCapabilities = secureSettings.getSettingLocked(
                            Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY);
                    final boolean supportMagnificationArea = getContext().getResources().getBoolean(
                            com.android.internal.R.bool.config_magnification_area);
                    final String supportShowPrompt = supportMagnificationArea ? "1" : "0";
                    if (magnificationCapabilities.isNull()) {
                        final int capability = supportMagnificationArea
                                ? getContext().getResources().getInteger(
                                        R.integer.def_accessibility_magnification_capabilities)
                                : Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
                        secureSettings.insertSettingLocked(
                                Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY,
                                String.valueOf(capability),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);

                        if (isMagnificationSettingsOn(secureSettings)) {
                            secureSettings.insertSettingLocked(
                                    Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY, String.valueOf(
                                            Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN),
                                    null, false  /* makeDefault */,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                            secureSettings.insertSettingLocked(
                                    Secure.ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT,
                                    supportShowPrompt,
                                    null, false /* makeDefault */,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }
                    currentVersion = 193;
                }

                if (currentVersion == 193) {
                    // Version 193: remove obsolete LOCATION_PROVIDERS_ALLOWED settings
                    getSecureSettingsLocked(userId).deleteSettingLocked(
                            Secure.LOCATION_PROVIDERS_ALLOWED);
                    currentVersion = 194;
                }

                if (currentVersion == 194) {
                    // Version 194: migrate the GNSS_SATELLITE_BLOCKLIST setting
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final Setting newSetting = globalSettings.getSettingLocked(
                            Global.GNSS_SATELLITE_BLOCKLIST);
                    final String oldName = "gnss_satellite_blacklist";
                    final Setting oldSetting = globalSettings.getSettingLocked(oldName);
                    if (newSetting.isNull() && !oldSetting.isNull()) {
                        globalSettings.insertSettingLocked(
                                Global.GNSS_SATELLITE_BLOCKLIST, oldSetting.getValue(), null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                        globalSettings.deleteSettingLocked(oldName);
                    }
                    currentVersion = 195;
                }

                if (currentVersion == 195) {
                    // Version 195: delete obsolete manged services settings
                    getSecureSettingsLocked(userId).deleteSettingLocked(
                            Secure.ENABLED_NOTIFICATION_ASSISTANT);
                    getSecureSettingsLocked(userId).deleteSettingLocked(
                            Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES);
                    currentVersion = 196;
                }

                if (currentVersion == 196) {
                    // Version 196: Set the default value for Secure Settings:
                    // SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED & ONE_HANDED_MODE_ENABLED
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting swipeNotification = secureSettings.getSettingLocked(
                            Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED);
                    if (swipeNotification.isNull()) {
                        final boolean defSwipeNotification = getContext().getResources()
                                .getBoolean(R.bool.def_swipe_bottom_to_notification_enabled);
                        secureSettings.insertSettingLocked(
                                Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED,
                                defSwipeNotification ? "1" : "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    final Setting oneHandedModeEnabled = secureSettings.getSettingLocked(
                            Secure.ONE_HANDED_MODE_ENABLED);
                    if (oneHandedModeEnabled.isNull()) {
                        final boolean defOneHandedModeEnabled = getContext().getResources()
                                .getBoolean(R.bool.def_one_handed_mode_enabled);
                        secureSettings.insertSettingLocked(
                                Secure.ONE_HANDED_MODE_ENABLED,
                                defOneHandedModeEnabled ? "1" : "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 197;
                }

                if (currentVersion == 197) {
                    // Version 197: Set the default value for Global Settings:
                    // DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final Setting enableNonResizableMultiWindow = globalSettings.getSettingLocked(
                            Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW);
                    if (enableNonResizableMultiWindow.isNull()) {
                        final boolean defEnableNonResizableMultiWindow = getContext().getResources()
                                .getBoolean(R.bool.def_enable_non_resizable_multi_window);
                        globalSettings.insertSettingLocked(
                                Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW,
                                defEnableNonResizableMultiWindow ? "1" : "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 198;
                }

                if (currentVersion == 198) {
                    // Version 198: Set the default value for accessibility button. If the user
                    // uses accessibility button in the navigation bar to trigger their
                    // accessibility features (check if ACCESSIBILITY_BUTTON_TARGETS has value)
                    // then leave accessibility button mode in the navigation bar, otherwise, set it
                    // to the floating menu.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting accessibilityButtonMode = secureSettings.getSettingLocked(
                            Secure.ACCESSIBILITY_BUTTON_MODE);
                    if (accessibilityButtonMode.isNull()) {
                        if (isAccessibilityButtonInNavigationBarOn(secureSettings)) {
                            secureSettings.insertSettingLocked(Secure.ACCESSIBILITY_BUTTON_MODE,
                                    String.valueOf(
                                            Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR),
                                    /*tag= */ null, /* makeDefault= */ false,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        } else {
                            final int defAccessibilityButtonMode =
                                    getContext().getResources().getInteger(
                                            R.integer.def_accessibility_button_mode);
                            secureSettings.insertSettingLocked(Secure.ACCESSIBILITY_BUTTON_MODE,
                                    String.valueOf(defAccessibilityButtonMode), /* tag= */
                                    null, /* makeDefault= */ true,
                                    SettingsState.SYSTEM_PACKAGE_NAME);

                            if (hasValueInA11yButtonTargets(secureSettings)) {
                                secureSettings.insertSettingLocked(
                                        Secure.ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT,
                                        /* enabled */ "1",
                                        /* tag= */ null,
                                        /* makeDefault= */ false,
                                        SettingsState.SYSTEM_PACKAGE_NAME);
                            }
                        }
                    }

                    currentVersion = 199;
                }

                if (currentVersion == 199) {
                    // Version 199: Bubbles moved to secure settings. Use the global value for
                    // the newly inserted secure setting; we'll delete the global value in the
                    // next version step.
                    // If this is a new profile, check if a secure setting exists for the
                    // owner of the profile and use that value for the work profile.
                    int owningId = resolveOwningUserIdForSecureSetting(userId,
                            NOTIFICATION_BUBBLES);
                    Setting previous = getGlobalSettingsLocked()
                            .getSettingLocked("notification_bubbles");
                    Setting secureBubbles = getSecureSettingsLocked(owningId)
                            .getSettingLocked(NOTIFICATION_BUBBLES);
                    String oldValue = "1";
                    if (!previous.isNull()) {
                        oldValue = previous.getValue();
                    } else if (!secureBubbles.isNull()) {
                        oldValue = secureBubbles.getValue();
                    }
                    if (secureBubbles.isNull()) {
                        boolean isDefault = oldValue.equals("1");
                        getSecureSettingsLocked(userId).insertSettingLocked(
                                Secure.NOTIFICATION_BUBBLES, oldValue, null /* tag */,
                                isDefault, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 200;
                }

                if (currentVersion == 200) {
                    // Version 200: delete the global bubble setting which was moved to secure in
                    // version 199.
                    getGlobalSettingsLocked().deleteSettingLocked("notification_bubbles");
                    currentVersion = 201;
                }

                if (currentVersion == 201) {
                    // Version 201: Set the default value for Secure Settings:
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting oneHandedModeActivated = secureSettings.getSettingLocked(
                            Secure.ONE_HANDED_MODE_ACTIVATED);
                    if (oneHandedModeActivated.isNull()) {
                        final boolean defOneHandedModeActivated = getContext().getResources()
                                .getBoolean(R.bool.def_one_handed_mode_activated);
                        secureSettings.insertSettingLocked(
                                Secure.ONE_HANDED_MODE_ACTIVATED,
                                defOneHandedModeActivated ? "1" : "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 202;
                }

                if (currentVersion == 202) {
                    // Version 202: Power menu has been removed, and the privacy setting
                    // has been split into two for wallet and controls
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting showLockedContent = secureSettings.getSettingLocked(
                            Secure.POWER_MENU_LOCKED_SHOW_CONTENT);
                    if (!showLockedContent.isNull()) {
                        String currentValue = showLockedContent.getValue();

                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.LOCKSCREEN_SHOW_CONTROLS,
                                currentValue, null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.LOCKSCREEN_SHOW_WALLET,
                                currentValue, null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 203;
                }

                if (currentVersion == 203) {
                    // Version 203: initialize entries migrated from wear settings provide.
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.HAS_PAY_TOKENS, false);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.GMS_CHECKIN_TIMEOUT_MIN, 6);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.HOTWORD_DETECTION_ENABLED,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_hotwordDetectionEnabled));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.SMART_REPLIES_ENABLED, true);
                    Setting locationMode =
                            getSecureSettingsLocked(userId).getSettingLocked(Secure.LOCATION_MODE);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.OBTAIN_PAIRED_DEVICE_LOCATION,
                            !locationMode.isNull()
                                    && !Integer.toString(Secure.LOCATION_MODE_OFF)
                                            .equals(locationMode.getValue()));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.PHONE_PLAY_STORE_AVAILABILITY,
                            Global.Wearable.PHONE_PLAY_STORE_AVAILABILITY_UNKNOWN);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.BUG_REPORT,
                            "user".equals(Build.TYPE) // is user build?
                                    ? Global.Wearable.BUG_REPORT_DISABLED
                                    : Global.Wearable.BUG_REPORT_ENABLED);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.SMART_ILLUMINATE_ENABLED,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_smartIlluminateEnabled));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.CLOCKWORK_AUTO_TIME,
                            Global.Wearable.SYNC_TIME_FROM_PHONE);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.CLOCKWORK_AUTO_TIME_ZONE,
                            Global.Wearable.SYNC_TIME_ZONE_FROM_PHONE);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.CLOCKWORK_24HR_TIME, false);
                    initGlobalSettingsDefaultValLocked(Global.Wearable.AUTO_WIFI, true);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.WIFI_POWER_SAVE,
                            getContext()
                                    .getResources()
                                    .getInteger(
                                            R.integer
                                                    .def_wearable_offChargerWifiUsageLimitMinutes));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.ALT_BYPASS_WIFI_REQUIREMENT_TIME_MILLIS, 0L);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.SETUP_SKIPPED, Global.Wearable.SETUP_SKIPPED_UNKNOWN);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.LAST_CALL_FORWARD_ACTION,
                            Global.Wearable.CALL_FORWARD_NO_LAST_ACTION);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.MUTE_WHEN_OFF_BODY_ENABLED,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_muteWhenOffBodyEnabled));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.WEAR_OS_VERSION_STRING, "");
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.SIDE_BUTTON,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_sideButtonPresent));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.ANDROID_WEAR_VERSION,
                            Long.parseLong(
                                    getContext()
                                            .getResources()
                                            .getString(R.string.def_wearable_androidWearVersion)));
                    final int editionGlobal = 1;
                    final int editionLocal = 2;
                    boolean isLe = getContext().getPackageManager().hasSystemFeature("cn.google");
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.SYSTEM_EDITION, isLe ? editionLocal : editionGlobal);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.SYSTEM_CAPABILITIES, getWearSystemCapabilities(isLe));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.WEAR_PLATFORM_MR_NUMBER,
                            SystemProperties.getInt("ro.cw_build.platform_mr", 0));
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.MOBILE_SIGNAL_DETECTOR,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_mobileSignalDetectorAllowed));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.AMBIENT_ENABLED,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_ambientEnabled));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.AMBIENT_TILT_TO_WAKE,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_tiltToWakeEnabled));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.AMBIENT_LOW_BIT_ENABLED_DEV, false);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.AMBIENT_TOUCH_TO_WAKE,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_touchToWakeEnabled));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.AMBIENT_TILT_TO_BRIGHT,
                            getContext()
                                    .getResources()
                                    .getBoolean(R.bool.def_wearable_tiltToBrightEnabled));
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.DECOMPOSABLE_WATCHFACE, false);
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.AMBIENT_FORCE_WHEN_DOCKED,
                            SystemProperties.getBoolean("ro.ambient.force_when_docked", false));
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.AMBIENT_LOW_BIT_ENABLED,
                            SystemProperties.getBoolean("ro.ambient.low_bit_enabled", false));
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.AMBIENT_PLUGGED_TIMEOUT_MIN,
                            SystemProperties.getInt("ro.ambient.plugged_timeout_min", -1));
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.PAIRED_DEVICE_OS_TYPE,
                            Settings.Global.Wearable.PAIRED_DEVICE_OS_TYPE_UNKNOWN);
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.USER_HFP_CLIENT_SETTING,
                            Settings.Global.Wearable.HFP_CLIENT_UNSET);
                    Setting disabledProfileSetting =
                            getGlobalSettingsLocked()
                                    .getSettingLocked(Settings.Global.BLUETOOTH_DISABLED_PROFILES);
                    final long disabledProfileSettingValue =
                            disabledProfileSetting.isNull()
                                    ? 0
                                    : Long.parseLong(disabledProfileSetting.getValue());
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.COMPANION_OS_VERSION,
                            Settings.Global.Wearable.COMPANION_OS_VERSION_UNDEFINED);
                    final boolean defaultBurnInProtectionEnabled =
                            getContext()
                                    .getResources()
                                    .getBoolean(
                                            com.android
                                                    .internal
                                                    .R
                                                    .bool
                                                    .config_enableBurnInProtection);
                    final boolean forceBurnInProtection =
                            SystemProperties.getBoolean("persist.debug.force_burn_in", false);
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.BURN_IN_PROTECTION_ENABLED,
                            defaultBurnInProtectionEnabled || forceBurnInProtection);

                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.CLOCKWORK_SYSUI_PACKAGE,
                            getContext()
                                    .getResources()
                                    .getString(
                                            com.android.internal.R.string.config_wearSysUiPackage));
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.CLOCKWORK_SYSUI_MAIN_ACTIVITY,
                            getContext()
                                    .getResources()
                                    .getString(
                                            com.android
                                                    .internal
                                                    .R
                                                    .string
                                                    .config_wearSysUiMainActivity));

                    currentVersion = 204;
                }

                if (currentVersion == 204) {
                    // Version 204: Replace 'wifi' or 'cell' tiles with 'internet' if existed.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting currentValue = secureSettings.getSettingLocked(Secure.QS_TILES);
                    if (!currentValue.isNull()) {
                        String tileList = currentValue.getValue();
                        String[] tileSplit = tileList.split(",");
                        final ArrayList<String> tiles = new ArrayList<String>();
                        boolean hasInternetTile = false;
                        for (int i = 0; i < tileSplit.length; i++) {
                            String tile = tileSplit[i].trim();
                            if (tile.isEmpty()) continue;
                            tiles.add(tile);
                            if (tile.equals("internet")) hasInternetTile = true;
                        }
                        if (!hasInternetTile) {
                            if (tiles.contains("wifi")) {
                                // Replace the WiFi with Internet, and remove the Cell
                                tiles.set(tiles.indexOf("wifi"), "internet");
                                tiles.remove("cell");
                            } else if (tiles.contains("cell")) {
                                // Replace the Cell with Internet
                                tiles.set(tiles.indexOf("cell"), "internet");
                            }
                        } else {
                            tiles.remove("wifi");
                            tiles.remove("cell");
                        }
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.QS_TILES,
                                TextUtils.join(",", tiles),
                                null /* tag */,
                                true /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 205;
                }

                if (currentVersion == 205) {
                    // Version 205: Set the default value for QR Code Scanner Setting:
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting showQRCodeScannerOnLockScreen = secureSettings.getSettingLocked(
                            Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER);
                    if (showQRCodeScannerOnLockScreen.isNull()) {
                        final boolean defLockScreenShowQrCodeScanner = getContext().getResources()
                                .getBoolean(R.bool.def_lock_screen_show_qr_code_scanner);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER,
                                defLockScreenShowQrCodeScanner ? "1" : "0", null, true,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    currentVersion = 206;
                }

                if (currentVersion == 206) {
                    // Version 206: APPLY_RAMPING_RINGER moved to System settings. Use the old value
                    // for the newly inserted system setting and keep it to be restored to other
                    // users. Set default value if global value is not set.
                    final SettingsState systemSettings = getSystemSettingsLocked(userId);
                    Setting globalValue = getGlobalSettingsLocked()
                            .getSettingLocked(Global.APPLY_RAMPING_RINGER);
                    Setting currentValue = systemSettings
                            .getSettingLocked(Settings.System.APPLY_RAMPING_RINGER);
                    if (currentValue.isNull()) {
                        if (!globalValue.isNull()) {
                            // Recover settings from Global.
                            systemSettings.insertSettingOverrideableByRestoreLocked(
                                    Settings.System.APPLY_RAMPING_RINGER, globalValue.getValue(),
                                    globalValue.getTag(), globalValue.isDefaultFromSystem(),
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        } else {
                            // Set default value.
                            systemSettings.insertSettingOverrideableByRestoreLocked(
                                    Settings.System.APPLY_RAMPING_RINGER,
                                    getContext().getResources().getBoolean(
                                            R.bool.def_apply_ramping_ringer) ? "1" : "0",
                                    null /* tag */, true /* makeDefault */,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }
                    currentVersion = 207;
                }

                if (currentVersion == 207) {
                    // Version 207: Reset the
                    // Secure#ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT as enabled
                    // status for showing the tooltips.
                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting accessibilityButtonMode = secureSettings.getSettingLocked(
                            Secure.ACCESSIBILITY_BUTTON_MODE);
                    if (!accessibilityButtonMode.isNull()
                            && accessibilityButtonMode.getValue().equals(
                            String.valueOf(ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU))) {
                        if (isGestureNavigateEnabled()
                                && hasValueInA11yButtonTargets(secureSettings)) {
                            secureSettings.insertSettingLocked(
                                    Secure.ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT,
                                    /* enabled */ "1",
                                    /* tag= */ null,
                                    /* makeDefault= */ false,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }

                    currentVersion = 208;
                }

                if (currentVersion == 208) {
                    // Unused
                    currentVersion = 209;
                }
                if (currentVersion == 209) {
                    // removed now that feature is enabled for everyone
                    currentVersion = 210;
                }
                if (currentVersion == 210) {
                    // Unused. Moved to version 217.
                    currentVersion = 211;
                }
                if (currentVersion == 211) {
                    // Unused. Moved to version 217.
                    currentVersion = 212;
                }

                if (currentVersion == 212) {
                    // Unused. Moved to version 217.
                    currentVersion = 213;
                }

                if (currentVersion == 213) {
                    final ComponentName accessibilityMenuToMigrate =
                            AccessibilityUtils.getAccessibilityMenuComponentToMigrate(
                                    getContext().getPackageManager(), userId);
                    if (accessibilityMenuToMigrate != null) {
                        final SettingsState secureSettings = getSecureSettingsLocked(userId);
                        final String toRemove = accessibilityMenuToMigrate.flattenToString();
                        final String toAdd = ACCESSIBILITY_MENU_IN_SYSTEM.flattenToString();
                        // Migrate the accessibility shortcuts and enabled state.
                        migrateColonDelimitedStringSettingLocked(secureSettings,
                                Secure.ACCESSIBILITY_BUTTON_TARGETS, toRemove, toAdd);
                        migrateColonDelimitedStringSettingLocked(secureSettings,
                                Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT, toRemove, toAdd);
                        migrateColonDelimitedStringSettingLocked(secureSettings,
                                Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, toRemove, toAdd);
                        migrateColonDelimitedStringSettingLocked(secureSettings,
                                Secure.ENABLED_ACCESSIBILITY_SERVICES, toRemove, toAdd);
                    }
                    currentVersion = 214;
                }

                if (currentVersion == 214) {
                    // Version 214: Removed, moved to version 216
                    currentVersion = 215;
                }

                if (currentVersion == 215) {
                    // Version 215: default |def_airplane_mode_radios| and
                    // |airplane_mode_toggleable_radios| changed to remove NFC & add UWB.
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final String oldApmRadiosValue = globalSettings.getSettingLocked(
                            Settings.Global.AIRPLANE_MODE_RADIOS).getValue();
                    if (TextUtils.equals("cell,bluetooth,wifi,nfc,wimax", oldApmRadiosValue)) {
                        globalSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.Global.AIRPLANE_MODE_RADIOS,
                                getContext().getResources().getString(
                                        R.string.def_airplane_mode_radios),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }
                    final String oldApmToggleableRadiosValue = globalSettings.getSettingLocked(
                            Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS).getValue();
                    if (TextUtils.equals("bluetooth,wifi,nfc", oldApmToggleableRadiosValue)) {
                        globalSettings.insertSettingOverrideableByRestoreLocked(
                                Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                                getContext().getResources().getString(
                                        R.string.airplane_mode_toggleable_radios),
                                null, true, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    currentVersion = 216;
                }

                if (currentVersion == 216) {
                    // Version 216: Set a default value for Credential Manager service.
                    // We are doing this migration again because of an incorrect setting.

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting currentSetting = secureSettings
                            .getSettingLocked(Settings.Secure.CREDENTIAL_SERVICE);
                    if (currentSetting.isNull()) {
                        final int resourceId =
                            com.android.internal.R.array.config_enabledCredentialProviderService;
                        final Resources resources = getContext().getResources();
                        // If the config has not be defined we might get an exception.
                        final List<String> providers = new ArrayList<>();
                        try {
                            providers.addAll(Arrays.asList(resources.getStringArray(resourceId)));
                        } catch (Resources.NotFoundException e) {
                            Slog.w(LOG_TAG,
                                "Get default array Cred Provider not found: " + e.toString());
                        }

                        if (!providers.isEmpty()) {
                            final String defaultValue = String.join(":", providers);
                            Slog.d(LOG_TAG, "Setting [" + defaultValue + "] as CredMan Service "
                                    + "for user " + userId);
                            secureSettings.insertSettingOverrideableByRestoreLocked(
                                    Settings.Secure.CREDENTIAL_SERVICE, defaultValue, null, true,
                                    SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }

                    currentVersion = 217;
                }

                if (currentVersion == 217) {
                    // Version 217: merge and rebase wear settings init logic.

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final SettingsState globalSettings = getGlobalSettingsLocked();

                    // Following init logic is moved from version 210 to this version in order to
                    // resolve version conflict with wear branch.
                    final Setting currentSetting = secureSettings.getSettingLocked(
                            Secure.STATUS_BAR_SHOW_VIBRATE_ICON);
                    if (currentSetting.isNull()) {
                        final int defaultValueVibrateIconEnabled = getContext().getResources()
                                .getInteger(R.integer.def_statusBarVibrateIconEnabled);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
                                String.valueOf(defaultValueVibrateIconEnabled),
                                null /* tag */, true /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    // Set default value for Secure#LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS
                    // Following init logic is moved from version 211 to this version in order to
                    // resolve version conflict with wear branch.
                    final Setting lockScreenUnseenSetting = secureSettings
                            .getSettingLocked(Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS);
                    if (lockScreenUnseenSetting.isNull()) {
                        final boolean defSetting = getContext().getResources()
                                .getBoolean(R.bool.def_lock_screen_show_only_unseen_notifications);
                        secureSettings.insertSettingOverrideableByRestoreLocked(
                                Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
                                defSetting ? "1" : "0",
                                null /* tag */,
                                true /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    // Following init logic is moved from version 212 to this version in order to
                    // resolve version conflict with wear branch.
                    final Setting bugReportInPowerMenu = globalSettings.getSettingLocked(
                            Global.BUGREPORT_IN_POWER_MENU);

                    if (!bugReportInPowerMenu.isNull()) {
                        Slog.i(LOG_TAG, "Setting bugreport_in_power_menu to "
                                + bugReportInPowerMenu.getValue() + " in Secure settings.");
                        secureSettings.insertSettingLocked(
                                Secure.BUGREPORT_IN_POWER_MENU,
                                bugReportInPowerMenu.getValue(), null /* tag */,
                                false /* makeDefault */, SettingsState.SYSTEM_PACKAGE_NAME);

                        // set global bug_report_in_power_menu setting to null since it's deprecated
                        Slog.i(LOG_TAG, "Setting bugreport_in_power_menu to null"
                                + " in Global settings since it's deprecated.");
                        globalSettings.insertSettingLocked(
                                Global.BUGREPORT_IN_POWER_MENU, null /* value */, null /* tag */,
                                true /* makeDefault */, SettingsState.SYSTEM_PACKAGE_NAME);
                    }

                    // Following init logic is rebased from wear OS branch.
                    // Initialize default value of tether configuration to unknown.
                    initGlobalSettingsDefaultValLocked(
                            Settings.Global.Wearable.TETHER_CONFIG_STATE,
                            Global.Wearable.TETHERED_CONFIG_UNKNOWN);
                    // Init paired device location setting from resources.
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.OBTAIN_PAIRED_DEVICE_LOCATION,
                            getContext()
                                    .getResources()
                                    .getInteger(R.integer.def_paired_device_location_mode));
                    // Init media packages from resources.
                    final String mediaControlsPackage = getContext().getResources().getString(
                            com.android.internal.R.string.config_wearMediaControlsPackage);
                    final String mediaSessionsPackage = getContext().getResources().getString(
                            com.android.internal.R.string.config_wearMediaSessionsPackage);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.WEAR_MEDIA_CONTROLS_PACKAGE,
                            mediaControlsPackage);
                    initGlobalSettingsDefaultValLocked(
                            Global.Wearable.WEAR_MEDIA_SESSIONS_PACKAGE,
                            mediaSessionsPackage);

                    currentVersion = 218;
                }

                if (currentVersion == 218) {
                    // Version 219: Removed
                    currentVersion = 219;
                }

                if (currentVersion == 219) {

                    final SettingsState secureSettings = getSecureSettingsLocked(userId);
                    final Setting currentSetting = secureSettings
                            .getSettingLocked(Settings.Secure.CREDENTIAL_SERVICE_PRIMARY);
                    if (currentSetting.isNull()) {
                        final int resourceId =
                              com.android.internal.R.array.config_primaryCredentialProviderService;
                        final Resources resources = getContext().getResources();
                        // If the config has not be defined we might get an exception.
                        final List<String> providers = new ArrayList<>();
                        try {
                            providers.addAll(Arrays.asList(resources.getStringArray(resourceId)));
                        } catch (Resources.NotFoundException e) {
                            Slog.w(LOG_TAG,
                                    "Get default array Cred Provider not found: " + e.toString());
                        }

                        if (!providers.isEmpty()) {
                            final String defaultValue = String.join(":", providers);
                            Slog.d(LOG_TAG, "Setting [" + defaultValue + "] as CredMan Service "
                                    + "for user " + userId);
                            secureSettings.insertSettingOverrideableByRestoreLocked(
                                    Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, defaultValue, null,
                                    true, SettingsState.SYSTEM_PACKAGE_NAME);
                        }
                    }
                    currentVersion = 220;
                }

                if (currentVersion == 220) {
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final Setting enableBackAnimation =
                            globalSettings.getSettingLocked(Global.ENABLE_BACK_ANIMATION);
                    if (enableBackAnimation.isNull()) {
                        final boolean defEnableBackAnimation =
                                getContext()
                                        .getResources()
                                        .getBoolean(R.bool.def_enable_back_animation);
                        initGlobalSettingsDefaultValLocked(
                                Settings.Global.ENABLE_BACK_ANIMATION, defEnableBackAnimation);
                    }
                    currentVersion = 221;
                }

                if (currentVersion == 221) {
                    // Version 221: Set a default value for wifi always requested
                    final SettingsState globalSettings = getGlobalSettingsLocked();
                    final Setting enableWifiAlwaysRequested =
                            globalSettings.getSettingLocked(Global.WIFI_ALWAYS_REQUESTED);
                    if (enableWifiAlwaysRequested.isNull()) {
                        final boolean defEnableWifiAlwaysRequested =
                                getContext()
                                        .getResources()
                                        .getBoolean(R.bool.def_enable_wifi_always_requested);
                        initGlobalSettingsDefaultValLocked(
                                Settings.Global.WIFI_ALWAYS_REQUESTED,
                                defEnableWifiAlwaysRequested);
                    }
                    currentVersion = 222;
                }

                // Version 222: Set peak refresh rate and min refresh rate to infinity if it's
                // meant to be the highest possible refresh rate. This is needed so that we can
                // back up and restore those settings on other devices. Other devices might have
                // different highest possible refresh rates.
                if (currentVersion == 222) {
                    final SettingsState systemSettings = getSystemSettingsLocked(userId);
                    final Setting peakRefreshRateSetting =
                            systemSettings.getSettingLocked(Settings.System.PEAK_REFRESH_RATE);
                    final Setting minRefreshRateSetting =
                            systemSettings.getSettingLocked(Settings.System.MIN_REFRESH_RATE);
                    float highestRefreshRate = RefreshRateSettingsUtils
                            .findHighestRefreshRateForDefaultDisplay(getContext());

                    if (!peakRefreshRateSetting.isNull()) {
                        try {
                            float peakRefreshRate =
                                    Float.parseFloat(peakRefreshRateSetting.getValue());
                            if (Math.round(peakRefreshRate) == Math.round(highestRefreshRate)) {
                                systemSettings.insertSettingLocked(
                                        Settings.System.PEAK_REFRESH_RATE,
                                        String.valueOf(Float.POSITIVE_INFINITY),
                                        /* tag= */ null,
                                        /* makeDefault= */ false,
                                        SettingsState.SYSTEM_PACKAGE_NAME);
                            }
                        } catch (NumberFormatException e) {
                            // Do nothing. Leave the value as is.
                        }
                    }

                    if (!minRefreshRateSetting.isNull()) {
                        try {
                            float minRefreshRate =
                                    Float.parseFloat(minRefreshRateSetting.getValue());
                            if (Math.round(minRefreshRate) == Math.round(highestRefreshRate)) {
                                systemSettings.insertSettingLocked(
                                        Settings.System.MIN_REFRESH_RATE,
                                        String.valueOf(Float.POSITIVE_INFINITY),
                                        /* tag= */ null,
                                        /* makeDefault= */ false,
                                        SettingsState.SYSTEM_PACKAGE_NAME);
                            }
                        } catch (NumberFormatException e) {
                            // Do nothing. Leave the value as is.
                        }
                    }
                }

                currentVersion = 223;

                // Version 223: make charging constraint update criteria customizable.
                if (currentVersion == 223) {
                    initGlobalSettingsDefaultValLocked(
                            Global.BATTERY_CHARGING_STATE_UPDATE_DELAY,
                            getContext().getResources().getInteger(
                                    R.integer.def_battery_charging_state_update_delay_ms));

                    initGlobalSettingsDefaultValLocked(
                            Global.BATTERY_CHARGING_STATE_ENFORCE_LEVEL,
                            getContext().getResources().getInteger(
                                    R.integer.def_battery_charging_state_enforce_level)
                    );
                    currentVersion = 224;
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

            @GuardedBy("mLock")
            private void initGlobalSettingsDefaultValLocked(String key, boolean val) {
                initGlobalSettingsDefaultValLocked(key, val ? "1" : "0");
            }

            @GuardedBy("mLock")
            private void initGlobalSettingsDefaultValLocked(String key, int val) {
                initGlobalSettingsDefaultValLocked(key, String.valueOf(val));
            }

            @GuardedBy("mLock")
            private void initGlobalSettingsDefaultValLocked(String key, long val) {
                initGlobalSettingsDefaultValLocked(key, String.valueOf(val));
            }

            @GuardedBy("mLock")
            private void initGlobalSettingsDefaultValLocked(String key, String val) {
                final SettingsState globalSettings = getGlobalSettingsLocked();
                Setting currentSetting = globalSettings.getSettingLocked(key);
                if (currentSetting.isNull()) {
                    globalSettings.insertSettingOverrideableByRestoreLocked(
                            key,
                            val,
                            null /* tag */,
                            true /* makeDefault */,
                            SettingsState.SYSTEM_PACKAGE_NAME);
                }
            }

            private long getWearSystemCapabilities(boolean isLe) {
                // Capability constants are imported from
                // com.google.android.clockwork.common.system.WearableConstants.
                final int capabilityCompanionLegacyCalling = 5;
                final int capabilitySpeaker = 6;
                final int capabilitySetupProtocommChannel = 7;
                long capabilities =
                        Long.parseLong(
                                getContext().getResources()
                                .getString(
                                        isLe ? R.string.def_wearable_leSystemCapabilities
                                                : R.string.def_wearable_systemCapabilities));
                PackageManager pm = getContext().getPackageManager();
                if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                    capabilities |= getBitMask(capabilityCompanionLegacyCalling);
                }
                if (pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) {
                    capabilities |= getBitMask(capabilitySpeaker);
                }
                capabilities |= getBitMask(capabilitySetupProtocommChannel);
                return capabilities;
            }

            private long getBitMask(int capability) {
                return 1 << (capability - 1);
            }
        }

        /**
         * Previously, We were using separate overlay packages for different back inset sizes. Now,
         * we have a single overlay package for gesture navigation mode, and set the inset size via
         * a secure.settings field.
         *
         * If a non-default overlay package is enabled, then enable the default overlay exclusively,
         * and set the calculated inset size difference as a scale value in secure.settings.
         */
        private void switchToDefaultGestureNavBackInset(int userId, SettingsState secureSettings) {
            try {
                final IOverlayManager om = IOverlayManager.Stub.asInterface(
                        ServiceManager.getService(Context.OVERLAY_SERVICE));
                final OverlayInfo info = om.getOverlayInfo(NAV_BAR_MODE_GESTURAL_OVERLAY, userId);
                if (info != null && !info.isEnabled()) {
                    final int curInset = getContext().getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.config_backGestureInset);
                    om.setEnabledExclusiveInCategory(NAV_BAR_MODE_GESTURAL_OVERLAY, userId);
                    final int defInset = getContext().getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.config_backGestureInset);

                    final float scale = defInset == 0 ? 1.0f : ((float) curInset) / defInset;
                    if (scale != 1.0f) {
                        secureSettings.insertSettingLocked(
                                Secure.BACK_GESTURE_INSET_SCALE_LEFT,
                                Float.toString(scale), null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                        secureSettings.insertSettingLocked(
                                Secure.BACK_GESTURE_INSET_SCALE_RIGHT,
                                Float.toString(scale), null /* tag */, false /* makeDefault */,
                                SettingsState.SYSTEM_PACKAGE_NAME);
                        if (DEBUG) {
                            Slog.v(LOG_TAG, "Moved back sensitivity for user " + userId
                                    + " to scale " + scale);
                        }
                    }
                }
            } catch (SecurityException | IllegalStateException | RemoteException e) {
                Slog.e(LOG_TAG, "Failed to switch to default gesture nav overlay for user "
                        + userId);
            }
        }

        private void migrateBackGestureSensitivity(String side, int userId,
                SettingsState secureSettings) {
            final Setting currentScale = secureSettings.getSettingLocked(side);
            if (currentScale.isNull()) {
                return;
            }
            float current = 1.0f;
            try {
                current = Float.parseFloat(currentScale.getValue());
            } catch (NumberFormatException e) {
                // Do nothing. Overwrite with default value.
            }

            // Inset scale migration across all devices
            //     Old(24dp): 0.66  0.75  0.83  1.00  1.08  1.33  1.66
            //     New(30dp): 0.60  0.60  1.00  1.00  1.00  1.00  1.33
            final float low = 0.76f;   // Values smaller than this will map to 0.6
            final float high = 1.65f;  // Values larger than this will map to 1.33
            float newScale;
            if (current < low) {
                newScale = 0.6f;
            } else if (current < high) {
                newScale = 1.0f;
            } else {
                newScale = 1.33f;
            }
            secureSettings.insertSettingLocked(side, Float.toString(newScale),
                    null /* tag */, false /* makeDefault */,
                    SettingsState.SYSTEM_PACKAGE_NAME);
            if (DEBUG) {
                Slog.v(LOG_TAG, "Changed back sensitivity from " + current + " to " + newScale
                        + " for user " + userId + " on " + side);
            }
        }

        @GuardedBy("mLock")
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
                try {
                    final boolean systemSet = SettingsState.isSystemPackage(
                            getContext(), setting.getPackageName());
                    if (systemSet) {
                        settings.insertSettingOverrideableByRestoreLocked(name, setting.getValue(),
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

        private boolean isMagnificationSettingsOn(SettingsState secureSettings) {
            if ("1".equals(secureSettings.getSettingLocked(
                    Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED).getValue())) {
                return true;
            }

            final Set<String> a11yButtonTargets = transformColonDelimitedStringToSet(
                    secureSettings.getSettingLocked(
                            Secure.ACCESSIBILITY_BUTTON_TARGETS).getValue());
            if (a11yButtonTargets != null && a11yButtonTargets.contains(
                    MAGNIFICATION_CONTROLLER_NAME)) {
                return true;
            }

            final Set<String> a11yShortcutServices = transformColonDelimitedStringToSet(
                    secureSettings.getSettingLocked(
                            Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE).getValue());
            if (a11yShortcutServices != null && a11yShortcutServices.contains(
                    MAGNIFICATION_CONTROLLER_NAME)) {
                return true;
            }
            return false;
        }

        @Nullable
        private Set<String> transformColonDelimitedStringToSet(String value) {
            if (TextUtils.isEmpty(value)) return null;
            final TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(value);
            final Set<String> items = new HashSet<>();
            while (splitter.hasNext()) {
                final String str = splitter.next();
                if (TextUtils.isEmpty(str)) {
                    continue;
                }
                items.add(str);
            }
            return items;
        }

        @GuardedBy("mLock")
        private void migrateColonDelimitedStringSettingLocked(SettingsState settingsState,
                String setting, String toRemove, String toAdd) {
            final Set<String> componentNames = transformColonDelimitedStringToSet(
                    settingsState.getSettingLocked(setting).getValue());
            if (componentNames != null && componentNames.contains(toRemove)) {
                componentNames.remove(toRemove);
                componentNames.add(toAdd);
                settingsState.insertSettingLocked(
                        setting,
                        TextUtils.join(":", componentNames),
                        null /* tag */, false /* makeDefault */,
                        SettingsState.SYSTEM_PACKAGE_NAME);
            }
        }

        private boolean isAccessibilityButtonInNavigationBarOn(SettingsState secureSettings) {
            return hasValueInA11yButtonTargets(secureSettings) && !isGestureNavigateEnabled();
        }

        private boolean isGestureNavigateEnabled() {
            final int navigationMode = getContext().getResources().getInteger(
                    com.android.internal.R.integer.config_navBarInteractionMode);
            return navigationMode == NAV_BAR_MODE_GESTURAL;
        }

        private boolean hasValueInA11yButtonTargets(SettingsState secureSettings) {
            final Setting a11yButtonTargetsSettings =
                    secureSettings.getSettingLocked(Secure.ACCESSIBILITY_BUTTON_TARGETS);

            return !a11yButtonTargetsSettings.isNull()
                    && !TextUtils.isEmpty(a11yButtonTargetsSettings.getValue());
        }

        @NonNull
        public GenerationRegistry getGenerationRegistry() {
            return mGenerationRegistry;
        }
    }
}
