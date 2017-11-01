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
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.os.PowerSaveState;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
            ServiceType.DATA_SAVER})
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
    }

    private static final String TAG = "BatterySaverPolicy";

    // Value of batterySaverGpsMode such that GPS isn't affected by battery saver mode.
    public static final int GPS_MODE_NO_CHANGE = 0;
    // Value of batterySaverGpsMode such that GPS is disabled when battery saver mode
    // is enabled and the screen is off.
    public static final int GPS_MODE_DISABLED_WHEN_SCREEN_OFF = 1;
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

    private ContentResolver mContentResolver;

    public BatterySaverPolicy(Handler handler) {
        super(handler);
    }

    public void start(ContentResolver contentResolver) {
        mContentResolver = contentResolver;

        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.BATTERY_SAVER_CONSTANTS), false, this);
        onChange(true, null);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        final String value = Settings.Global.getString(mContentResolver,
                Settings.Global.BATTERY_SAVER_CONSTANTS);
        updateConstants(value);
    }

    @VisibleForTesting
    void updateConstants(final String value) {
        synchronized (BatterySaverPolicy.this) {
            try {
                mParser.setString(value);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad battery saver constants");
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

            // Get default value from Settings.Secure
            final int defaultGpsMode = Settings.Secure.getInt(mContentResolver, SECURE_KEY_GPS_MODE,
                    GPS_MODE_DISABLED_WHEN_SCREEN_OFF);
            mGpsMode = mParser.getInt(KEY_GPS_MODE, defaultGpsMode);
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
                default:
                    return builder.setBatterySaverEnabled(realMode)
                            .build();
            }
        }
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Battery saver policy");
        pw.println("  Settings " + Settings.Global.BATTERY_SAVER_CONSTANTS);
        pw.println("  value: " + Settings.Global.getString(mContentResolver,
                Settings.Global.BATTERY_SAVER_CONSTANTS));

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

    }
}
