/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.backup.IBackupManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.ColorDisplayManager;
import android.icu.util.ULocale;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.LocalePicker;
import com.android.settingslib.devicestate.DeviceStateRotationLockSettingsManager;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

public class SettingsHelper {
    private static final String TAG = "SettingsHelper";
    private static final String SILENT_RINGTONE = "_silent";
    private static final String SETTINGS_REPLACED_KEY = "backup_skip_user_facing_data";
    private static final String SETTING_ORIGINAL_KEY_SUFFIX = "_original";
    private static final String UNICODE_LOCALE_EXTENSION_FW = "fw";
    private static final String UNICODE_LOCALE_EXTENSION_MU = "mu";
    private static final String UNICODE_LOCALE_EXTENSION_NU = "nu";
    private static final float FLOAT_TOLERANCE = 0.01f;

    /** See frameworks/base/core/res/res/values/config.xml#config_longPressOnPowerBehavior **/
    private static final int LONG_PRESS_POWER_NOTHING = 0;
    private static final int LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;
    private static final int LONG_PRESS_POWER_FOR_ASSISTANT = 5;
    /** See frameworks/base/core/res/res/values/config.xml#config_keyChordPowerVolumeUp **/
    private static final int KEY_CHORD_POWER_VOLUME_UP_GLOBAL_ACTIONS = 2;

    private Context mContext;
    private AudioManager mAudioManager;
    private TelephonyManager mTelephonyManager;

    /**
     * A few settings elements are special in that a restore of those values needs to
     * be post-processed by relevant parts of the OS.  A restore of any settings element
     * mentioned in this table will therefore cause the system to send a broadcast with
     * the {@link Intent#ACTION_SETTING_RESTORED} action, with extras naming the
     * affected setting and supplying its pre-restore value for comparison.
     *
     * @see Intent#ACTION_SETTING_RESTORED
     * @see System#SETTINGS_TO_BACKUP
     * @see Secure#SETTINGS_TO_BACKUP
     * @see Global#SETTINGS_TO_BACKUP
     *
     * {@hide}
     */
    private static final ArraySet<String> sBroadcastOnRestore;
    private static final ArraySet<String> sBroadcastOnRestoreSystemUI;
    static {
        sBroadcastOnRestore = new ArraySet<String>(9);
        sBroadcastOnRestore.add(Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);
        sBroadcastOnRestore.add(Settings.Secure.ENABLED_VR_LISTENERS);
        sBroadcastOnRestore.add(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        sBroadcastOnRestore.add(Settings.Global.BLUETOOTH_ON);
        sBroadcastOnRestore.add(Settings.Secure.UI_NIGHT_MODE);
        sBroadcastOnRestore.add(Settings.Secure.DARK_THEME_CUSTOM_START_TIME);
        sBroadcastOnRestore.add(Settings.Secure.DARK_THEME_CUSTOM_END_TIME);
        sBroadcastOnRestore.add(Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED);
        sBroadcastOnRestore.add(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS);
        sBroadcastOnRestore.add(Settings.Secure.ACCESSIBILITY_QS_TARGETS);
        sBroadcastOnRestore.add(Settings.Secure.SCREEN_RESOLUTION_MODE);
        sBroadcastOnRestoreSystemUI = new ArraySet<String>(2);
        sBroadcastOnRestoreSystemUI.add(Settings.Secure.QS_TILES);
        sBroadcastOnRestoreSystemUI.add(Settings.Secure.QS_AUTO_ADDED_TILES);
    }

    private static final ArraySet<String> UNICODE_LOCALE_SUPPORTED_EXTENSIONS = new ArraySet<>();

    /**
     * Current supported extensions are fw (first day of week) and mu (temperature unit) extension.
     * User can set these extensions in Settings app, and it will be appended to the locale,
     * for example: zh-Hant-TW-u-fw-mon-mu-celsius. So after the factory reset, these extensions
     * should be restored as well because they are set by users.
     * We do not put the nu (numbering system) extension here because it is an Android supported
     * extension and defined in some particular locales, for example:
     * ar-Arab-MA-u-nu-arab and ar-Arab-YE-u-nu-latn. See
     * <code>frameworks/base/core/res/res/values/locale_config.xml</code>
     * The nu extension should not be appended to the current/restored locale after factory reset
     * if the current/restored locale does not have it.
     */
    static {
        UNICODE_LOCALE_SUPPORTED_EXTENSIONS.add(UNICODE_LOCALE_EXTENSION_FW);
        UNICODE_LOCALE_SUPPORTED_EXTENSIONS.add(UNICODE_LOCALE_EXTENSION_MU);
    }

    private interface SettingsLookup {
        public String lookup(ContentResolver resolver, String name, int userHandle);
    }

    private static SettingsLookup sSystemLookup = new SettingsLookup() {
        public String lookup(ContentResolver resolver, String name, int userHandle) {
            return Settings.System.getStringForUser(resolver, name, userHandle);
        }
    };

    private static SettingsLookup sSecureLookup = new SettingsLookup() {
        public String lookup(ContentResolver resolver, String name, int userHandle) {
            return Settings.Secure.getStringForUser(resolver, name, userHandle);
        }
    };

    private static SettingsLookup sGlobalLookup = new SettingsLookup() {
        public String lookup(ContentResolver resolver, String name, int userHandle) {
            return Settings.Global.getStringForUser(resolver, name, userHandle);
        }
    };

    public SettingsHelper(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context
                .getSystemService(Context.AUDIO_SERVICE);
        mTelephonyManager = (TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Sets the property via a call to the appropriate API, if any, and returns
     * whether or not the setting should be saved to the database as well.
     * @param name the name of the setting
     * @param value the string value of the setting
     * @return whether to continue with writing the value to the database. In
     * some cases the data will be written by the call to the appropriate API,
     * and in some cases the property value needs to be modified before setting.
     */
    public void restoreValue(Context context, ContentResolver cr, ContentValues contentValues,
            Uri destination, String name, String value, int restoredFromSdkInt) {
        if (isReplacedSystemSetting(name)) {
            return;
        }

        // Will we need a post-restore broadcast for this element?
        String oldValue = null;
        boolean sendBroadcast = false;
        boolean sendBroadcastSystemUI = false;
        final SettingsLookup table;

        if (destination.equals(Settings.Secure.CONTENT_URI)) {
            table = sSecureLookup;
        } else if (destination.equals(Settings.System.CONTENT_URI)) {
            table = sSystemLookup;
        } else { /* must be GLOBAL; this was preflighted by the caller */
            table = sGlobalLookup;
        }

        sendBroadcast = sBroadcastOnRestore.contains(name);
        sendBroadcastSystemUI = sBroadcastOnRestoreSystemUI.contains(name);

        if (sendBroadcast) {
            // TODO: http://b/22388012
            oldValue = table.lookup(cr, name, UserHandle.USER_SYSTEM);
        } else if (sendBroadcastSystemUI) {
            // This is only done for broadcasts sent to system ui as the consumers are known.
            // It would probably be correct to do it for the ones sent to the system, but consumers
            // may be depending on the current behavior.
            oldValue = table.lookup(cr, name, context.getUserId());
        }

        try {
            if (Settings.System.SOUND_EFFECTS_ENABLED.equals(name)) {
                setSoundEffects(Integer.parseInt(value) == 1);
                // fall through to the ordinary write to settings
            } else if (Settings.Secure.BACKUP_AUTO_RESTORE.equals(name)) {
                setAutoRestore(Integer.parseInt(value) == 1);
            } else if (isAlreadyConfiguredCriticalAccessibilitySetting(name)) {
                return;
            } else if (Settings.System.RINGTONE.equals(name)
                    || Settings.System.NOTIFICATION_SOUND.equals(name)
                    || Settings.System.ALARM_ALERT.equals(name)) {
                setRingtone(name, value);
                return;
            } else if (Settings.System.DISPLAY_COLOR_MODE.equals(name)) {
                int mode = Integer.parseInt(value);
                String restoredVendorHint = Settings.System.getString(mContext.getContentResolver(),
                        Settings.System.DISPLAY_COLOR_MODE_VENDOR_HINT);
                final String deviceVendorHint = mContext.getResources().getString(
                        com.android.internal.R.string.config_vendorColorModesRestoreHint);
                boolean displayColorModeVendorModeHintsMatch =
                        !TextUtils.isEmpty(deviceVendorHint)
                                && deviceVendorHint.equals(restoredVendorHint);
                // Replace vendor hint with new device's vendor hint.
                contentValues.clear();
                contentValues.put(Settings.NameValueTable.NAME,
                        Settings.System.DISPLAY_COLOR_MODE_VENDOR_HINT);
                contentValues.put(Settings.NameValueTable.VALUE, deviceVendorHint);
                cr.insert(destination, contentValues);
                // If vendor hints match, modes in the vendor range can be restored. Otherwise, only
                // map standard modes.
                if (!ColorDisplayManager.isStandardColorMode(mode)
                        && !displayColorModeVendorModeHintsMatch) {
                    return;
                }
            } else if (Settings.Global.POWER_BUTTON_LONG_PRESS.equals(name)) {
                setLongPressPowerBehavior(cr, value);
                return;
            } else if (Settings.System.ACCELEROMETER_ROTATION.equals(name)
                    && shouldSkipAutoRotateRestore()) {
                return;
            } else if (Settings.Secure.ACCESSIBILITY_QS_TARGETS.equals(name)) {
                // Don't write it to setting. Let the broadcast receiver in
                // AccessibilityManagerService handle restore/merging logic.
                return;
            }

            // Default case: write the restored value to settings
            contentValues.clear();
            contentValues.put(Settings.NameValueTable.NAME, name);
            contentValues.put(Settings.NameValueTable.VALUE, value);
            cr.insert(destination, contentValues);
        } catch (Exception e) {
            // If we fail to apply the setting, by definition nothing happened
            sendBroadcast = false;
            sendBroadcastSystemUI = false;
            Log.e(TAG, "Failed to restore setting name: " + name + " + value: " + value, e);
        } finally {
            // If this was an element of interest, send the "we just restored it"
            // broadcast with the historical value now that the new value has
            // been committed and observers kicked off.
            if (sendBroadcast || sendBroadcastSystemUI) {
                Intent intent = new Intent(Intent.ACTION_SETTING_RESTORED)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra(Intent.EXTRA_SETTING_NAME, name)
                        .putExtra(Intent.EXTRA_SETTING_NEW_VALUE, value)
                        .putExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE, oldValue)
                        .putExtra(Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT, restoredFromSdkInt);

                if (sendBroadcast) {
                    intent.setPackage("android");
                    context.sendBroadcastAsUser(intent, UserHandle.SYSTEM, null);
                }
                if (sendBroadcastSystemUI) {
                    intent.setPackage(
                            context.getString(com.android.internal.R.string.config_systemUi));
                    context.sendBroadcastAsUser(intent, context.getUser(), null);
                }
            }
        }
    }

    private boolean shouldSkipAutoRotateRestore() {
        // When device state based auto rotation settings are available, let's skip the restoring
        // of the standard auto rotation settings to avoid conflicting setting values.
        return DeviceStateRotationLockSettingsManager.isDeviceStateRotationLockEnabled(mContext);
    }

    public String onBackupValue(String name, String value) {
        // Special processing for backing up ringtones & notification sounds
        if (Settings.System.RINGTONE.equals(name)
                || Settings.System.NOTIFICATION_SOUND.equals(name)
                || Settings.System.ALARM_ALERT.equals(name)) {
            if (value == null) {
                if (Settings.System.RINGTONE.equals(name)) {
                    // For ringtones, we need to distinguish between non-telephony vs telephony
                    if (mTelephonyManager != null && mTelephonyManager.isVoiceCapable()) {
                        // Backup a null ringtone as silent on voice-capable devices
                        return SILENT_RINGTONE;
                    } else {
                        // Skip backup of ringtone on non-telephony devices.
                        return null;
                    }
                } else {
                    // Backup a null notification sound or alarm alert as silent
                    return SILENT_RINGTONE;
                }
            } else {
                return getCanonicalRingtoneValue(value);
            }
        }
        // Return the original value
        return isReplacedSystemSetting(name) ? getRealValueForSystemSetting(name) : value;
    }

    /**
     * The setting value might have been replaced temporarily. If that's the case, return the real
     * value instead of the temporary one.
     */
    @VisibleForTesting
    public String getRealValueForSystemSetting(String setting) {
        // The real value irrespectively of the original setting's namespace is stored in
        // Settings.Secure.
        return Settings.Secure.getString(mContext.getContentResolver(),
                setting + SETTING_ORIGINAL_KEY_SUFFIX);
    }

    @VisibleForTesting
    public boolean isReplacedSystemSetting(String setting) {
        // This list should not be modified.
        if (!Settings.System.SCREEN_OFF_TIMEOUT.equals(setting)) {
            return false;
        }
        // If this flag is set, values for the system settings from the list above have been
        // temporarily replaced. We don't want to back up the temporary value or run restore for
        // such settings.
        // TODO(154822946): Remove this logic in the next release.
        return Settings.Secure.getInt(mContext.getContentResolver(), SETTINGS_REPLACED_KEY,
                /* def */ 0) != 0;
    }

    /**
     * Sets the ringtone of type specified by the name.
     *
     * @param name should be Settings.System.RINGTONE, Settings.System.NOTIFICATION_SOUND
     * or Settings.System.ALARM_ALERT.
     * @param value can be a canonicalized uri or "_silent" to indicate a silent (null) ringtone.
     */
    private void setRingtone(String name, String value) {
        Log.v(TAG, "Set ringtone for name: " + name + " value: " + value);

        // If it's null, don't change the default.
        if (value == null) return;
        final int ringtoneType = getRingtoneType(name);
        if (SILENT_RINGTONE.equals(value)) {
            // SILENT_RINGTONE is a special constant generated by onBackupValue in the source
            // device.
            RingtoneManager.setActualDefaultRingtoneUri(mContext, ringtoneType, null);
            return;
        }

        Uri ringtoneUri = null;
        try {
            ringtoneUri =
                    RingtoneManager.getRingtoneUriForRestore(
                            mContext.getContentResolver(), value, ringtoneType);
        } catch (FileNotFoundException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to resolve " + value + ": " + e);
            // Unrecognized or invalid Uri, don't restore
            return;
        }

        Log.v(TAG, "setActualDefaultRingtoneUri type: " + ringtoneType + ", uri: " + ringtoneUri);
        RingtoneManager.setActualDefaultRingtoneUri(mContext, ringtoneType, ringtoneUri);
    }

    private int getRingtoneType(String name) {
        switch (name) {
            case Settings.System.RINGTONE:
                return RingtoneManager.TYPE_RINGTONE;
            case Settings.System.NOTIFICATION_SOUND:
                return RingtoneManager.TYPE_NOTIFICATION;
            case Settings.System.ALARM_ALERT:
                return RingtoneManager.TYPE_ALARM;
            default:
                throw new IllegalArgumentException("Incorrect ringtone name: " + name);
        }
    }

    private String getCanonicalRingtoneValue(String value) {
        final Uri ringtoneUri = Uri.parse(value);
        final Uri canonicalUri = mContext.getContentResolver().canonicalize(ringtoneUri);
        return canonicalUri == null ? null : canonicalUri.toString();
    }

    private boolean isAlreadyConfiguredCriticalAccessibilitySetting(String name) {
        // These are the critical accessibility settings that are required for users with
        // accessibility needs to be able to interact with the device. If these settings are
        // already configured, we will not overwrite them. If they are already set,
        // it means that the user has performed a global gesture to enable accessibility or set
        // these settings in the Accessibility portion of the Setup Wizard, and definitely needs
        // these features working after the restore.
        switch (name) {
            case Settings.Secure.ACCESSIBILITY_ENABLED:
            case Settings.Secure.TOUCH_EXPLORATION_ENABLED:
            case Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED:
            case Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED:
            case Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED:
            case Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED:
                return Settings.Secure.getInt(mContext.getContentResolver(), name, 0) != 0;
            case Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES:
            case Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES:
            case Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER:
                return !TextUtils.isEmpty(Settings.Secure.getString(
                        mContext.getContentResolver(), name));
            case Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE:
                float defaultScale = mContext.getResources().getFraction(
                        R.fraction.def_accessibility_display_magnification_scale, 1, 1);
                float currentScale = Settings.Secure.getFloat(
                        mContext.getContentResolver(), name, defaultScale);
                return Math.abs(currentScale - defaultScale) >= FLOAT_TOLERANCE;
            case Settings.System.FONT_SCALE:
                return Settings.System.getFloat(mContext.getContentResolver(), name, 1.0f) != 1.0f;
            default:
                return false;
        }
    }

    private void setAutoRestore(boolean enabled) {
        try {
            IBackupManager bm = IBackupManager.Stub.asInterface(
                    ServiceManager.getService(Context.BACKUP_SERVICE));
            if (bm != null) {
                bm.setAutoRestore(enabled);
            }
        } catch (RemoteException e) {}
    }

    private void setSoundEffects(boolean enable) {
        if (enable) {
            mAudioManager.loadSoundEffects();
        } else {
            mAudioManager.unloadSoundEffects();
        }
    }

    /**
     * Correctly sets long press power button Behavior.
     *
     * The issue is that setting for LongPressPower button Behavior is not available on all devices
     * and actually changes default Behavior of two properties - the long press power button
     * and volume up + power button combo. OEM can also reconfigure these Behaviors in config.xml,
     * so we need to be careful that we don't irreversibly change power button Behavior when
     * restoring. Or switch to a non-default button Behavior.
     */
    private void setLongPressPowerBehavior(ContentResolver cr, String value) {
        // We will not restore the value if the long press power setting option is unavailable.
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_longPressOnPowerForAssistantSettingAvailable)) {
            return;
        }

        int longPressOnPowerBehavior;
        try {
            longPressOnPowerBehavior = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return;
        }

        if (longPressOnPowerBehavior < LONG_PRESS_POWER_NOTHING
                || longPressOnPowerBehavior > LONG_PRESS_POWER_FOR_ASSISTANT) {
            return;
        }

        // When user enables long press power for Assistant, we also switch the meaning
        // of Volume Up + Power key chord to the "Show power menu" option.
        // If the user disables long press power for Assistant, we switch back to default OEM
        // Behavior configured in config.xml. If the default Behavior IS "LPP for Assistant",
        // then we fall back to "Long press for Power Menu" Behavior.
        if (longPressOnPowerBehavior == LONG_PRESS_POWER_FOR_ASSISTANT) {
            Settings.Global.putInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS,
                    LONG_PRESS_POWER_FOR_ASSISTANT);
            Settings.Global.putInt(cr, Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                    KEY_CHORD_POWER_VOLUME_UP_GLOBAL_ACTIONS);
        } else {
            // We're restoring "LPP for Assistant Disabled" state, prefer OEM config.xml Behavior
            // if possible.
            int longPressOnPowerDeviceBehavior = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_longPressOnPowerBehavior);
            if (longPressOnPowerDeviceBehavior == LONG_PRESS_POWER_FOR_ASSISTANT) {
                // The default on device IS "LPP for Assistant Enabled" so fall back to power menu.
                Settings.Global.putInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS,
                        LONG_PRESS_POWER_GLOBAL_ACTIONS);
            } else {
                // The default is non-Assistant Behavior, so restore that default.
                Settings.Global.putInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS,
                        longPressOnPowerDeviceBehavior);
            }

            // Clear and restore default power + volume up Behavior as well.
            int powerVolumeUpDefaultBehavior = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_keyChordPowerVolumeUp);
            Settings.Global.putInt(cr, Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                    powerVolumeUpDefaultBehavior);
        }
    }

    /* package */ byte[] getLocaleData() {
        Configuration conf = mContext.getResources().getConfiguration();
        return conf.getLocales().toLanguageTags().getBytes();
    }

    private static Locale toFullLocale(@NonNull Locale locale) {
        if (locale.getScript().isEmpty() || locale.getCountry().isEmpty()) {
            return ULocale.addLikelySubtags(ULocale.forLocale(locale)).toLocale();
        }
        return locale;
    }

    /**
     * Merging the locale came from backup server and current device locale.
     *
     * Merge works with following rules.
     * - The backup locales are appended to the current locale with keeping order.
     *   e.g. current locale "en-US,zh-CN" and backup locale "ja-JP,ko-KR" are merged to
     *   "en-US,zh-CH,ja-JP,ko-KR".
     *
     * - Duplicated locales are dropped.
     *   e.g. current locale "en-US,zh-CN" and backup locale "ja-JP,zh-Hans-CN,en-US" are merged to
     *   "en-US,zh-CN,ja-JP".
     *
     * - Unsupported locales are dropped.
     *   e.g. current locale "en-US" and backup locale "ja-JP,zh-CN" but the supported locales
     *   are "en-US,zh-CN", the merged locale list is "en-US,zh-CN".
     *
     * - The final result locale list only contains the supported locales.
     *   e.g. current locale "en-US" and backup locale "zh-Hans-CN" and supported locales are
     *   "en-US,zh-CN", the merged locale list is "en-US,zh-CN".
     *
     * @param restore The locale list that came from backup server.
     * @param current The device's locale setting.
     * @param supportedLocales The list of language tags supported by this device.
     */
    @VisibleForTesting
    public static LocaleList resolveLocales(LocaleList restore, LocaleList current,
            String[] supportedLocales) {
        final HashMap<Locale, Locale> allLocales = new HashMap<>(supportedLocales.length);
        for (String supportedLocaleStr : supportedLocales) {
            final Locale locale = Locale.forLanguageTag(supportedLocaleStr);
            allLocales.put(toFullLocale(locale), locale);
        }

        // After restoring to reset locales, need to get extensions from restored locale. Get the
        // first restored locale to check its extension.
        final Locale restoredLocale = restore.isEmpty()
                ? Locale.ROOT
                : restore.get(0);
        final ArrayList<Locale> filtered = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            Locale locale = copyExtensionToTargetLocale(restoredLocale, current.get(i));
            allLocales.remove(toFullLocale(locale));
            filtered.add(locale);
        }

        for (int i = 0; i < restore.size(); i++) {
            final Locale restoredLocaleWithExtension = copyExtensionToTargetLocale(restoredLocale,
                    getFilteredLocale(restore.get(i), allLocales));
            if (restoredLocaleWithExtension != null) {
                filtered.add(restoredLocaleWithExtension);
            }
        }
        if (filtered.size() == current.size()) {
            return current;  // Nothing added to current locale list.
        }

        return new LocaleList(filtered.toArray(new Locale[filtered.size()]));
    }

    private static Locale copyExtensionToTargetLocale(Locale restoredLocale,
            Locale targetLocale) {
        if (!restoredLocale.hasExtensions()) {
            return targetLocale;
        }

        if (targetLocale == null) {
            return null;
        }

        Locale.Builder builder = new Locale.Builder()
                .setLocale(targetLocale);
        Set<String> unicodeLocaleKeys = restoredLocale.getUnicodeLocaleKeys();
        unicodeLocaleKeys.stream().forEach(key -> {
            // Copy all supported extensions from restored locales except "nu" extension. The "nu"
            // extension has been added in #getFilteredLocale(Locale, HashMap<Locale, Locale>)
            // already, we don't need to add it again.
            if (UNICODE_LOCALE_SUPPORTED_EXTENSIONS.contains(key)) {
                builder.setUnicodeLocaleKeyword(key, restoredLocale.getUnicodeLocaleType(key));
            }
        });
        return builder.build();
    }

    private static Locale getFilteredLocale(Locale restoreLocale,
            HashMap<Locale, Locale> allLocales) {
        Locale locale = allLocales.remove(toFullLocale(restoreLocale));
        if (locale != null) {
            return locale;
        }

        Locale filteredLocale = new Locale.Builder()
                .setLocale(restoreLocale.stripExtensions())
                .setUnicodeLocaleKeyword(UNICODE_LOCALE_EXTENSION_NU,
                        restoreLocale.getUnicodeLocaleType(UNICODE_LOCALE_EXTENSION_NU))
                .build();
        return allLocales.remove(toFullLocale(filteredLocale));
    }

    /**
     * Sets the locale specified. Input data is the byte representation of comma separated
     * multiple BCP-47 language tags. For backwards compatibility, strings of the form
     * {@code ll_CC} are also accepted, where {@code ll} is a two letter language
     * code and {@code CC} is a two letter country code.
     *
     * @param data the comma separated BCP-47 language tags in bytes.
     */
    /* package */ void setLocaleData(byte[] data, int size) {
        final Configuration conf = mContext.getResources().getConfiguration();

        // Replace "_" with "-" to deal with older backups.
        final String localeCodes = new String(data, 0, size).replace('_', '-');
        final LocaleList localeList = LocaleList.forLanguageTags(localeCodes);
        if (localeList.isEmpty()) {
            return;
        }

        final String[] supportedLocales = LocalePicker.getSupportedLocales(mContext);
        final LocaleList currentLocales = conf.getLocales();

        final LocaleList merged = resolveLocales(localeList, currentLocales, supportedLocales);
        if (merged.equals(currentLocales)) {
            return;
        }

        try {
            IActivityManager am = ActivityManager.getService();
            final Configuration config = new Configuration();
            config.setLocales(merged);
            // indicate this isn't some passing default - the user wants this remembered
            config.userSetLocale = true;

            am.updatePersistentConfigurationWithAttribution(config, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException e) {
            // Intentionally left blank
        }
    }

    /**
     * Informs the audio service of changes to the settings so that
     * they can be re-read and applied.
     */
    void applyAudioSettings() {
        AudioManager am = new AudioManager(mContext);
        am.reloadAudioSettings();
    }
}
