/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.power;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.KeyValueListParser;
import android.util.Pair;
import android.util.Slog;
import android.os.PowerSaveState;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.IoThread;

import libcore.io.IoUtils;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class to decide whether to turn on battery saver mode for specific service
 */
public class BatterySaverPolicy extends ContentObserver {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ServiceType.GPS,
            ServiceType.VIBRATION,
            ServiceType.ANIMATION,
            ServiceType.FULL_BACKUP,
            ServiceType.KEYVALUE_BACKUP,
            ServiceType.NETWORK_FIREWALL,
            ServiceType.SCREEN_BRIGHTNESS,
            ServiceType.SOUND,
            ServiceType.BATTERY_STATS,
            ServiceType.DATA_SAVER,
            ServiceType.FORCE_APPS_STANDBY,
            ServiceType.LOWER_MAX_FREQUENCY,
            ServiceType.AOD,
    })
    public @interface ServiceType {
        int NULL = 0;
        int GPS = 1;
        int VIBRATION = 2;
        int ANIMATION = 3;
        int FULL_BACKUP = 4;
        int KEYVALUE_BACKUP = 5;
        int NETWORK_FIREWALL = 6;
        int SCREEN_BRIGHTNESS = 7;
        int SOUND = 8;
        int BATTERY_STATS = 9;
        int DATA_SAVER = 10;

        int FORCE_APPS_STANDBY = 11;
        int LOWER_MAX_FREQUENCY = 13;
        int AOD = 15;
    }

    private static final String TAG = "BatterySaverPolicy";

    // Value of batterySaverGpsMode such that GPS isn't affected by battery saver mode.
    public static final int GPS_MODE_NO_CHANGE = 0;

    // Value of batterySaverGpsMode such that GPS is disabled when battery saver mode
    // is enabled and the screen is off.
    public static final int GPS_MODE_DISABLED_WHEN_SCREEN_OFF = 1;

    public static final int GPS_MODE_REALLY_DISABLED_WHEN_SCREEN_OFF = 2;

    // Secure setting for GPS behavior when battery saver mode is on.
    public static final String SECURE_KEY_GPS_MODE = "batterySaverGpsMode";

    private static final String KEY_GPS_MODE = "gps_mode";
    private static final String KEY_VIBRATION_DISABLED = "vibration_disabled";
    private static final String KEY_ANIMATION_DISABLED = "animation_disabled";
    private static final String KEY_SOUNDTRIGGER_DISABLED = "soundtrigger_disabled";
    private static final String KEY_FIREWALL_DISABLED = "firewall_disabled";
    private static final String KEY_ADJUST_BRIGHTNESS_DISABLED = "adjust_brightness_disabled";
    private static final String KEY_DATASAVER_DISABLED = "datasaver_disabled";
    private static final String KEY_ADJUST_BRIGHTNESS_FACTOR = "adjust_brightness_factor";
    private static final String KEY_FULLBACKUP_DEFERRED = "fullbackup_deferred";
    private static final String KEY_KEYVALUE_DEFERRED = "keyvaluebackup_deferred";
    private static final String KEY_FORCE_APPS_STANDBY_ENABLED = "force_apps_standby_enabled";
    private static final String KEY_POWER_HINT_BLOCK = "power_hint_block";
    private static final String KEY_RED_BAR_ENABLED = "red_bar_enabled";
    private static final String KEY_MAX_FILE_WRITE_RETRIES = "max_file_write_retries";

    private static final String KEY_FILE_OVERRIDE_PREFIX = "file:";
    private static final String KEY_SECURE_SETTINGS_OVERRIDE_PREFIX = "secure:";
    private static final String KEY_GLOBAL_SETTINGS_OVERRIDE_PREFIX = "global:";
    private static final String KEY_SYSTEM_SETTINGS_OVERRIDE_PREFIX = "system:";

    private static final String KEY_FILE_OVERRIDE_PREFIX_NS = "file-ns:";
    private static final String KEY_SECURE_SETTINGS_OVERRIDE_PREFIX_NS = "secure-ns:";
    private static final String KEY_GLOBAL_SETTINGS_OVERRIDE_PREFIX_NS = "global-ns:";
    private static final String KEY_SYSTEM_SETTINGS_OVERRIDE_PREFIX_NS = "system-ns:";

    private static final String KEY_ACTIVATE_BROADCASTS = "bc-act:";
    private static final String KEY_DEACTIVATE_BROADCASTS = "bc-deact:";

    private final KeyValueListParser mParser = new KeyValueListParser(',');

    /**
     * {@code true} if vibration is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_VIBRATION_DISABLED
     */
    private boolean mVibrationDisabled;

    /**
     * {@code true} if animation is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ANIMATION_DISABLED
     */
    private boolean mAnimationDisabled;

    /**
     * {@code true} if sound trigger is disabled in battery saver mode
     * in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_SOUNDTRIGGER_DISABLED
     */
    private boolean mSoundTriggerDisabled;

    /**
     * {@code true} if full backup is deferred in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_FULLBACKUP_DEFERRED
     */
    private boolean mFullBackupDeferred;

    /**
     * {@code true} if key value backup is deferred in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_KEYVALUE_DEFERRED
     */
    private boolean mKeyValueBackupDeferred;

    /**
     * {@code true} if network policy firewall is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_FIREWALL_DISABLED
     */
    private boolean mFireWallDisabled;

    /**
     * {@code true} if adjust brightness is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ADJUST_BRIGHTNESS_DISABLED
     */
    private boolean mAdjustBrightnessDisabled;

    /**
     * {@code true} if data saver is disabled in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_DATASAVER_DISABLED
     */
    private boolean mDataSaverDisabled;

    /**
     * This is the flag to decide the gps mode in battery saver mode.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_GPS_MODE
     */
    private int mGpsMode;

    /**
     * This is the flag to decide the how much to adjust the screen brightness. This is
     * the float value from 0 to 1 where 1 means don't change brightness.
     *
     * @see Settings.Global#BATTERY_SAVER_CONSTANTS
     * @see #KEY_ADJUST_BRIGHTNESS_FACTOR
     */
    private float mAdjustBrightnessFactor;

    private boolean mForceAppsStandbyEnabled;
    private int mPowerHintMask;
    private boolean mRedBarEnabled;

    /**
     * scaling_max_freq wouldn't accept a value lower than the current frequency, so we need to
     * retry, and this limits the max number of retries.
     */
    private int mMaxFileWriteRetries;

    private final ArrayList<Pair<String, String>> mFileOverrides = new ArrayList<>();
    private final ArrayList<Pair<String, String>> mFileOverridesNS = new ArrayList<>();
    private final ArrayList<Pair<String, String>> mFileRestores = new ArrayList<>();

    private final ArrayList<Pair<String, String>> mSecureOverrides = new ArrayList<>();
    private final ArrayList<Pair<String, String>> mGlobalOverrides = new ArrayList<>();
    private final ArrayList<Pair<String, String>> mSystemOverrides = new ArrayList<>();
    private final ArrayList<Pair<String, String>> mSecureOverridesNS = new ArrayList<>();
    private final ArrayList<Pair<String, String>> mGlobalOverridesNS = new ArrayList<>();
    private final ArrayList<Pair<String, String>> mSystemOverridesNS = new ArrayList<>();

    private final ArrayList<Intent> mActivateBroadcasts = new ArrayList<>();
    private final ArrayList<Intent> mDeactivateBroadcasts = new ArrayList<>();

    private static final String SETTING_ORIGINAL_SUFFIX = "_bs_orig";

    private ContentResolver mContentResolver;

    private static final String BATTERY_SAVER_CONSTANTS_DEFAULT_KEY = Settings.Global.BATTERY_SAVER_CONSTANTS + "_om";
    private static String BATTERY_SAVER_CONSTANTS_KEY = BATTERY_SAVER_CONSTANTS_DEFAULT_KEY;

    private boolean mActivated = false;
    private boolean mScreenOn = true;

    private final Context mContext;
    private final Handler mHandler;

    public BatterySaverPolicy(Context context, Handler handler) {
        super(handler);
        mContext = context;
        mHandler = handler;
    }

    public void start(ContentResolver contentResolver) {
        mContentResolver = contentResolver;

        final String key = Settings.Global.getString(mContentResolver, Settings.Global.BATTERY_SAVER_CONSTANTS + "_key");
        if (!TextUtils.isEmpty(key)) {
            BATTERY_SAVER_CONSTANTS_KEY = key;
        }


        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                BATTERY_SAVER_CONSTANTS_KEY), false, this);
        onChange(true, null);

        // In case the device rebooted while battery saver was enabled, restore all settings.
        // restoreAllSettings relies on configuration read by onChange(), so it needs to follow it.
        restoreAllSettings();
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_SCREEN_OFF:
                    updateScreenState();
                    break;
            }
        }
    };


    @Override
    public void onChange(boolean selfChange, Uri uri) {
        final String value = Settings.Global.getString(mContentResolver,
                BATTERY_SAVER_CONSTANTS_KEY);
        updateConstants(value);

        // Also propagate to BATTERY_SAVER_USE_RED_BAR
        Settings.Global.putInt(mContentResolver, Global.BATTERY_SAVER_USE_RED_BAR,
                mRedBarEnabled ? 1 : 0);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter, null, mHandler);

    }

    @VisibleForTesting
    void updateConstants(final String value) {
        synchronized (BatterySaverPolicy.this) {
            Slog.d(TAG, "Updating battery saver settings: " + value);

            try {
                mParser.setString(value);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad battery saver constants: " + value);
            }

            mVibrationDisabled = mParser.getBoolean(KEY_VIBRATION_DISABLED, true);
            mAnimationDisabled = mParser.getBoolean(KEY_ANIMATION_DISABLED, true);
            mSoundTriggerDisabled = mParser.getBoolean(KEY_SOUNDTRIGGER_DISABLED, true);
            mFullBackupDeferred = mParser.getBoolean(KEY_FULLBACKUP_DEFERRED, true);
            mKeyValueBackupDeferred = mParser.getBoolean(KEY_KEYVALUE_DEFERRED, true);
            mFireWallDisabled = mParser.getBoolean(KEY_FIREWALL_DISABLED, false);
            mAdjustBrightnessDisabled = mParser.getBoolean(KEY_ADJUST_BRIGHTNESS_DISABLED, false);
            mAdjustBrightnessFactor = mParser.getFloat(KEY_ADJUST_BRIGHTNESS_FACTOR, 0.5f);
            mDataSaverDisabled = mParser.getBoolean(KEY_DATASAVER_DISABLED, true);

            mForceAppsStandbyEnabled = mParser.getBoolean(KEY_FORCE_APPS_STANDBY_ENABLED, false);
            mPowerHintMask = ~mParser.getInt(KEY_POWER_HINT_BLOCK, 0);

            mRedBarEnabled = mParser.getBoolean(KEY_RED_BAR_ENABLED, true);

            mMaxFileWriteRetries = mParser.getInt(KEY_MAX_FILE_WRITE_RETRIES, 20);

            // Get default value from Settings.Secure
            final int defaultGpsMode = Settings.Secure.getInt(mContentResolver, SECURE_KEY_GPS_MODE,
                    GPS_MODE_DISABLED_WHEN_SCREEN_OFF);
            mGpsMode = mParser.getInt(KEY_GPS_MODE, defaultGpsMode);

            parseOverrides(mFileOverrides, mParser, KEY_FILE_OVERRIDE_PREFIX);
            parseOverrides(mSecureOverrides, mParser, KEY_SECURE_SETTINGS_OVERRIDE_PREFIX);
            parseOverrides(mGlobalOverrides, mParser, KEY_GLOBAL_SETTINGS_OVERRIDE_PREFIX);
            parseOverrides(mSystemOverrides, mParser, KEY_SYSTEM_SETTINGS_OVERRIDE_PREFIX);

            parseOverrides(mFileOverridesNS, mParser, KEY_FILE_OVERRIDE_PREFIX_NS);
            parseOverrides(mSecureOverridesNS, mParser, KEY_SECURE_SETTINGS_OVERRIDE_PREFIX_NS);
            parseOverrides(mGlobalOverridesNS, mParser, KEY_GLOBAL_SETTINGS_OVERRIDE_PREFIX_NS);
            parseOverrides(mSystemOverridesNS, mParser, KEY_SYSTEM_SETTINGS_OVERRIDE_PREFIX_NS);

            parseIntents(mActivateBroadcasts, mParser, KEY_ACTIVATE_BROADCASTS);
            parseIntents(mDeactivateBroadcasts, mParser, KEY_DEACTIVATE_BROADCASTS);

            copyIfEmpty(mFileOverridesNS, mFileOverrides);
            copyIfEmpty(mSecureOverridesNS, mSecureOverrides);
            copyIfEmpty(mGlobalOverridesNS, mGlobalOverrides);
            copyIfEmpty(mSystemOverridesNS, mSystemOverrides);
        }
    }

    private static <T> void copyIfEmpty(ArrayList<T> dest, ArrayList<T> source) {
        if (dest.isEmpty()) {
            dest.addAll(source);
        }
    }

    private static void parseOverrides(ArrayList<Pair<String, String>> target,
            KeyValueListParser parser, String prefix) {
        target.clear();

        for (String origKey : parser.getKeys()) {
            if (origKey.startsWith(prefix)) {
                final String key = origKey.substring(prefix.length());

                target.add(Pair.create(key, parser.getString(origKey, "")));
            }
        }
    }

    private void parseIntents(ArrayList<Intent> target, KeyValueListParser parser,
            String prefix) {
        target.clear();

        for (String origKey : parser.getKeys()) {
            if (origKey.startsWith(prefix)) {
                final String key = origKey.substring(prefix.length());
                final String value = parser.getString(origKey, "");
                if (TextUtils.isEmpty(value)) {
                    continue;
                }
                try {
                    target.add(Intent.parseUri(value, /* flags =*/ 0));
                } catch (URISyntaxException e) {
                    Slog.e(TAG, "Error parsing intent: " + value);
                }
            }
        }
    }

    /**
     * Get the {@link PowerSaveState} based on {@paramref type} and {@paramref realMode}.
     * The result will have {@link PowerSaveState#batterySaverEnabled} and some other
     * parameters when necessary.
     *
     * @param type     type of the service, one of {@link ServiceType}
     * @param realMode whether the battery saver is on by default
     * @return State data that contains battery saver data
     */
    public PowerSaveState getBatterySaverPolicy(@ServiceType int type, boolean realMode) {
        synchronized (BatterySaverPolicy.this) {
            final PowerSaveState.Builder builder = new PowerSaveState.Builder()
                    .setGlobalBatterySaverEnabled(realMode);
            if (!realMode) {
                return builder.setBatterySaverEnabled(realMode)
                        .build();
            }
            switch (type) {
                case ServiceType.GPS:
                    return builder.setBatterySaverEnabled(realMode)
                            .setGpsMode(mGpsMode)
                            .build();
                case ServiceType.ANIMATION:
                    return builder.setBatterySaverEnabled(mAnimationDisabled)
                            .build();
                case ServiceType.FULL_BACKUP:
                    return builder.setBatterySaverEnabled(mFullBackupDeferred)
                            .build();
                case ServiceType.KEYVALUE_BACKUP:
                    return builder.setBatterySaverEnabled(mKeyValueBackupDeferred)
                            .build();
                case ServiceType.NETWORK_FIREWALL:
                    return builder.setBatterySaverEnabled(!mFireWallDisabled)
                            .build();
                case ServiceType.SCREEN_BRIGHTNESS:
                    return builder.setBatterySaverEnabled(!mAdjustBrightnessDisabled)
                            .setBrightnessFactor(mAdjustBrightnessFactor)
                            .build();
                case ServiceType.DATA_SAVER:
                    return builder.setBatterySaverEnabled(!mDataSaverDisabled)
                            .build();
                case ServiceType.SOUND:
                    return builder.setBatterySaverEnabled(mSoundTriggerDisabled)
                            .build();
                case ServiceType.VIBRATION:
                    return builder.setBatterySaverEnabled(mVibrationDisabled)
                            .build();

                case ServiceType.FORCE_APPS_STANDBY:
                    return builder.setBatterySaverEnabled(mForceAppsStandbyEnabled)
                            .build();

                default:
                    return builder.setBatterySaverEnabled(realMode)
                            .build();
            }
        }
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Battery saver policy");
        pw.println("  Settings key: " + BATTERY_SAVER_CONSTANTS_KEY);
        pw.println("  value: " + Settings.Global.getString(mContentResolver,
                BATTERY_SAVER_CONSTANTS_KEY));

        pw.println();
        pw.println("  " + KEY_VIBRATION_DISABLED + "=" + mVibrationDisabled);
        pw.println("  " + KEY_ANIMATION_DISABLED + "=" + mAnimationDisabled);
        pw.println("  " + KEY_FULLBACKUP_DEFERRED + "=" + mFullBackupDeferred);
        pw.println("  " + KEY_KEYVALUE_DEFERRED + "=" + mKeyValueBackupDeferred);
        pw.println("  " + KEY_FIREWALL_DISABLED + "=" + mFireWallDisabled);
        pw.println("  " + KEY_DATASAVER_DISABLED + "=" + mDataSaverDisabled);
        pw.println("  " + KEY_ADJUST_BRIGHTNESS_DISABLED + "=" + mAdjustBrightnessDisabled);
        pw.println("  " + KEY_ADJUST_BRIGHTNESS_FACTOR + "=" + mAdjustBrightnessFactor);
        pw.println("  " + KEY_GPS_MODE + "=" + mGpsMode);

        pw.println("  " + KEY_FORCE_APPS_STANDBY_ENABLED + "=" + mForceAppsStandbyEnabled);
        pw.println("  " + KEY_POWER_HINT_BLOCK + "=" + Integer.toHexString(~mPowerHintMask));
        pw.println("  " + KEY_RED_BAR_ENABLED + "=" + mRedBarEnabled);
        pw.println("  " + KEY_MAX_FILE_WRITE_RETRIES + "=" + mMaxFileWriteRetries);

        pw.println("  Files overrides     =" + mFileOverrides);
        pw.println("  Files overrides (NS)=" + mFileOverridesNS);
        pw.println("  Files restores      =" + mFileRestores);
        pw.println("  Secure overrides     =" + mSecureOverrides);
        pw.println("  Secure overrides (NS)=" + mSecureOverridesNS);
        pw.println("  Global overrides     =" + mGlobalOverrides);
        pw.println("  Global overrides (NS)=" + mGlobalOverridesNS);
        pw.println("  System overrides     =" + mSystemOverrides);
        pw.println("  System overrides (NS)=" + mSystemOverridesNS);

        pw.println("  Activate broadcasts  =" + mActivateBroadcasts);
        pw.println("  Deactivate broadcasts=" + mDeactivateBroadcasts);
    }

    // TODO Move it somewhere else.
    public void startSaver() {
        updateActivateState(true);
    }

    private static void applySettings(ContentResolver cr, int userId,
            ArrayList<Pair<String, String>> overrides,
            SettingsIf settings) {
        for (Pair<String, String> setting : overrides) {
            final String name = setting.first;
            final String value = setting.second;
            final String name_orig = name + SETTING_ORIGINAL_SUFFIX;

            // Keep the original unless we've already done so.
            final String orig = settings.read(cr, name, userId);
            if (settings.read(cr, name_orig, userId) == null) {
                settings.write(cr, name_orig, orig, userId);
            }
            settings.write(cr, name, value, userId);
        }
    }

    public void stopSaver() {
        updateActivateState(false);
    }

    private void updateScreenState() {
        updateActivateState(mActivated);
    }

    private void updateActivateState(boolean activate) {
        final PowerManager pm = mContext.getSystemService(PowerManager.class);
        final boolean newScreenOn = pm.isInteractive();

        if ((activate == mActivated) && (newScreenOn == mScreenOn)) {
            return; // no changes.
        }

        // When we're going to activate, save the original file values.
        // Note we keep the original *settings* in a different way, so no need to save the original settings here.
        if (!mActivated) {
            mFileRestores.clear();

            for (Pair<String, String> files : mFileOverrides) {
                final String name = files.first;
                final String value = files.second;
                try {
                    final String org = IoUtils.readFileAsString(name).trim();
                    mFileRestores.add(Pair.create(name, org));
                } catch (IOException e) {
                    Slog.wtf(TAG, "Can't read from" + name, e);
                }
            }
        }
        boolean wasActivated = mActivated;
        mActivated = activate;
        mScreenOn = newScreenOn;

        if (mActivated) {
            Slog.d(TAG, "Starting battery saver...");

            PowerManagerService.setPowerHintMask(mPowerHintMask);

            writeToFiles(mScreenOn ? mFileOverrides : mFileOverridesNS);

            // Update settings.
            final ContentResolver cr = mContentResolver;
            applySettings(cr, UserHandle.USER_SYSTEM, mScreenOn ? mSecureOverrides : mSecureOverridesNS, SecureSettingsIf.INSTANCE);
            applySettings(cr, UserHandle.USER_SYSTEM, mScreenOn ? mGlobalOverrides : mGlobalOverridesNS, GlobalSettingsIf.INSTANCE);
            applySettings(cr, UserHandle.USER_SYSTEM, mScreenOn ? mSystemOverrides : mSystemOverridesNS, SystemSettingsIf.INSTANCE);
        } else {
            Slog.d(TAG, "Stopping battery saver...");

            PowerManagerService.setPowerHintMask(0xffffffff);

            writeToFiles(mFileRestores);

            restoreAllSettings();
        }
        if (wasActivated != mActivated) {
            final ArrayList<Intent> intents = mActivated ? mActivateBroadcasts : mDeactivateBroadcasts;

            mHandler.post(() -> {
                for (Intent intent : intents) {
                    Slog.i(TAG, "Broadcasting: " + intent);
                    mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                }
            });
        }
    }

    private void restoreAllSettings() {
        Slog.d(TAG, "Restoring settings...");

        // TODO Other users.
        final ContentResolver cr = mContentResolver;
        restoreSettings(cr, UserHandle.USER_SYSTEM, mScreenOn ? mSecureOverrides : mSecureOverridesNS, SecureSettingsIf.INSTANCE);
        restoreSettings(cr, UserHandle.USER_SYSTEM, mScreenOn ? mGlobalOverrides : mGlobalOverridesNS, GlobalSettingsIf.INSTANCE);
        restoreSettings(cr, UserHandle.USER_SYSTEM, mScreenOn ? mSystemOverrides : mSystemOverridesNS, SystemSettingsIf.INSTANCE);
    }

    private static void restoreSettings(ContentResolver cr, int userId,
            ArrayList<Pair<String, String>> overrides,
            SettingsIf settings) {
        for (Pair<String, String> setting : overrides) {
            final String name = setting.first;
            final String name_orig = name + SETTING_ORIGINAL_SUFFIX;

            final String current = settings.read(cr, name, userId);
            final String value = setting.second;

            if (TextUtils.equals(current, value)) {
                // If the user hans't changed it, restore the original value.

                final String orig = settings.read(cr, name_orig, userId);
                if (orig != null) {
                    settings.write(cr, name, orig, userId);
                }
            }

            // Erase the original value.
            settings.write(cr, name_orig, null, userId);
        }
    }

    private static final int SAVE_RETRY_DELAY = 3000;

    private final AtomicInteger mNumRetries = new AtomicInteger();
    private volatile ArrayList<Pair<String, String>> pendingFilesAndValues;

    private final Runnable mWritePendingFiles = () -> {
        writePendingFiles(); // We need to extraact it to a separate method to avoid self-refing.
    };

    private void writeToFiles(ArrayList<Pair<String, String>> filesAndValues) {
        pendingFilesAndValues = filesAndValues;

        mNumRetries.set(0);

        // Grr, we can't write a lower frequency than the current frequency to the max freq.
        // We need a retry logic...
        IoThread.getHandler().post(mWritePendingFiles);
    }

    private void writePendingFiles() {
        final ArrayList<Pair<String, String>> files = pendingFilesAndValues;
        if (files != null) {
            for (Pair<String, String> pair : files) {
                final String name = pair.first;
                final String value = pair.second;
                try {
                    writeToFile(name, value);
                } catch (IOException e) {
                    if (mNumRetries.incrementAndGet() < mMaxFileWriteRetries) {
                        Slog.w(TAG, "Failed to write " + value + " to " + name + ", retrying.");
                        // Retry.
                        IoThread.getHandler().postDelayed(mWritePendingFiles, SAVE_RETRY_DELAY);
                    } else {
                        Slog.wtf(TAG, "Failed to write " + value + " to " + name, e);
                    }
                    return;
                }
            }
            pendingFilesAndValues = null;
        }
    }

    private void writeToFile(String name, String value) throws IOException {
        Slog.d(TAG, "Writing " + value + " to " + name);
        try (FileWriter out = new FileWriter(name)) {
            out.write(value);
        }
    }

    interface SettingsIf {
        void write(ContentResolver cr, String name, String value, int user);
        String read(ContentResolver cr, String name, int user);
    }

    private static class SecureSettingsIf implements SettingsIf {
        public static final SecureSettingsIf INSTANCE = new SecureSettingsIf();

        @Override
        public void write(ContentResolver cr, String name, String value, int user) {
            Slog.d(TAG, "Writing " + value + " to secure." + name);
            Settings.Secure.putStringForUser(cr, name, value, user);
        }

        @Override
        public String read(ContentResolver cr, String name, int user) {
            return Settings.Secure.getStringForUser(cr, name, user);
        }
    }

    private static class GlobalSettingsIf implements SettingsIf {
        public static final GlobalSettingsIf INSTANCE = new GlobalSettingsIf();

        @Override
        public void write(ContentResolver cr, String name, String value, int user) {
            Slog.d(TAG, "Writing " + value + " to global." + name);
            Settings.Global.putStringForUser(cr, name, value, user);
        }

        @Override
        public String read(ContentResolver cr, String name, int user) {
            return Settings.Global.getStringForUser(cr, name, user);
        }
    }

    private static class SystemSettingsIf implements SettingsIf {
        public static final SystemSettingsIf INSTANCE = new SystemSettingsIf();

        @Override
        public void write(ContentResolver cr, String name, String value, int user) {
            Slog.d(TAG, "Writing " + value + " to system." + name);
            Settings.System.putStringForUser(cr, name, value, user);
        }

        @Override
        public String read(ContentResolver cr, String name, int user) {
            return Settings.System.getStringForUser(cr, name, user);
        }
    }

}