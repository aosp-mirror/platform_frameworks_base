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

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.backup.IBackupManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;

import java.util.Locale;

public class SettingsHelper {
    private static final String SILENT_RINGTONE = "_silent";
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
    static {
        sBroadcastOnRestore = new ArraySet<String>(3);
        sBroadcastOnRestore.add(Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);
        sBroadcastOnRestore.add(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        sBroadcastOnRestore.add(Settings.Secure.ENABLED_INPUT_METHODS);
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
            Uri destination, String name, String value) {
        // Will we need a post-restore broadcast for this element?
        String oldValue = null;
        boolean sendBroadcast = false;
        final SettingsLookup table;

        if (destination.equals(Settings.Secure.CONTENT_URI)) {
            table = sSecureLookup;
        } else if (destination.equals(Settings.System.CONTENT_URI)) {
            table = sSystemLookup;
        } else { /* must be GLOBAL; this was preflighted by the caller */
            table = sGlobalLookup;
        }

        if (sBroadcastOnRestore.contains(name)) {
            oldValue = table.lookup(cr, name, UserHandle.USER_OWNER);
            sendBroadcast = true;
        }

        try {
            if (Settings.System.SCREEN_BRIGHTNESS.equals(name)) {
                setBrightness(Integer.parseInt(value));
                // fall through to the ordinary write to settings
            } else if (Settings.System.SOUND_EFFECTS_ENABLED.equals(name)) {
                setSoundEffects(Integer.parseInt(value) == 1);
                // fall through to the ordinary write to settings
            } else if (Settings.Secure.LOCATION_PROVIDERS_ALLOWED.equals(name)) {
                setGpsLocation(value);
                return;
            } else if (Settings.Secure.BACKUP_AUTO_RESTORE.equals(name)) {
                setAutoRestore(Integer.parseInt(value) == 1);
            } else if (isAlreadyConfiguredCriticalAccessibilitySetting(name)) {
                return;
            } else if (Settings.System.RINGTONE.equals(name)
                    || Settings.System.NOTIFICATION_SOUND.equals(name)) {
                setRingtone(name, value);
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
        } finally {
            // If this was an element of interest, send the "we just restored it"
            // broadcast with the historical value now that the new value has
            // been committed and observers kicked off.
            if (sendBroadcast) {
                Intent intent = new Intent(Intent.ACTION_SETTING_RESTORED)
                        .setPackage("android").addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra(Intent.EXTRA_SETTING_NAME, name)
                        .putExtra(Intent.EXTRA_SETTING_NEW_VALUE, value)
                        .putExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE, oldValue);
                context.sendBroadcastAsUser(intent, UserHandle.OWNER, null);
            }
        }
    }

    public String onBackupValue(String name, String value) {
        // Special processing for backing up ringtones & notification sounds
        if (Settings.System.RINGTONE.equals(name)
                || Settings.System.NOTIFICATION_SOUND.equals(name)) {
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
                    // Backup a null notification sound as silent
                    return SILENT_RINGTONE;
                }
            } else {
                return getCanonicalRingtoneValue(value);
            }
        }
        // Return the original value
        return value;
    }

    /**
     * Sets the ringtone of type specified by the name.
     *
     * @param name should be Settings.System.RINGTONE or Settings.System.NOTIFICATION_SOUND.
     * @param value can be a canonicalized uri or "_silent" to indicate a silent (null) ringtone.
     */
    private void setRingtone(String name, String value) {
        // If it's null, don't change the default
        if (value == null) return;
        Uri ringtoneUri = null;
        if (SILENT_RINGTONE.equals(value)) {
            ringtoneUri = null;
        } else {
            Uri canonicalUri = Uri.parse(value);
            ringtoneUri = mContext.getContentResolver().uncanonicalize(canonicalUri);
            if (ringtoneUri == null) {
                // Unrecognized or invalid Uri, don't restore
                return;
            }
        }
        final int ringtoneType = Settings.System.RINGTONE.equals(name)
                ? RingtoneManager.TYPE_RINGTONE : RingtoneManager.TYPE_NOTIFICATION;
        RingtoneManager.setActualDefaultRingtoneUri(mContext, ringtoneType, ringtoneUri);
    }

    private String getCanonicalRingtoneValue(String value) {
        final Uri ringtoneUri = Uri.parse(value);
        final Uri canonicalUri = mContext.getContentResolver().canonicalize(ringtoneUri);
        return canonicalUri == null ? null : canonicalUri.toString();
    }

    private boolean isAlreadyConfiguredCriticalAccessibilitySetting(String name) {
        // These are the critical accessibility settings that are required for a
        // blind user to be able to interact with the device. If these settings are
        // already configured, we will not overwrite them. If they are already set,
        // it means that the user has performed a global gesture to enable accessibility
        // and definitely needs these features working after the restore.
        if (Settings.Secure.ACCESSIBILITY_ENABLED.equals(name)
                || Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION.equals(name)
                || Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD.equals(name)
                || Settings.Secure.TOUCH_EXPLORATION_ENABLED.equals(name)) {
            return Settings.Secure.getInt(mContext.getContentResolver(), name, 0) != 0;
        } else if (Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES.equals(name)
                || Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(name)) {
            return !TextUtils.isEmpty(Settings.Secure.getString(
                    mContext.getContentResolver(), name));
        }
        return false;
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

    private void setGpsLocation(String value) {
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)) {
            return;
        }
        final String GPS = LocationManager.GPS_PROVIDER;
        boolean enabled =
                GPS.equals(value) ||
                value.startsWith(GPS + ",") ||
                value.endsWith("," + GPS) ||
                value.contains("," + GPS + ",");
        Settings.Secure.setLocationProviderEnabled(
                mContext.getContentResolver(), GPS, enabled);
    }

    private void setSoundEffects(boolean enable) {
        if (enable) {
            mAudioManager.loadSoundEffects();
        } else {
            mAudioManager.unloadSoundEffects();
        }
    }

    private void setBrightness(int brightness) {
        try {
            IPowerManager power = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));
            if (power != null) {
                power.setTemporaryScreenBrightnessSettingOverride(brightness);
            }
        } catch (RemoteException doe) {

        }
    }

    byte[] getLocaleData() {
        Configuration conf = mContext.getResources().getConfiguration();
        final Locale loc = conf.locale;
        String localeString = loc.getLanguage();
        String country = loc.getCountry();
        if (!TextUtils.isEmpty(country)) {
            localeString += "-" + country;
        }
        return localeString.getBytes();
    }

    /**
     * Sets the locale specified. Input data is the byte representation of a
     * BCP-47 language tag. For backwards compatibility, strings of the form
     * {@code ll_CC} are also accepted, where {@code ll} is a two letter language
     * code and {@code CC} is a two letter country code.
     *
     * @param data the locale string in bytes.
     */
    void setLocaleData(byte[] data, int size) {
        // Check if locale was set by the user:
        Configuration conf = mContext.getResources().getConfiguration();
        // TODO: The following is not working as intended because the network is forcing a locale
        // change after registering. Need to find some other way to detect if the user manually
        // changed the locale
        if (conf.userSetLocale) return; // Don't change if user set it in the SetupWizard

        final String[] availableLocales = mContext.getAssets().getLocales();
        // Replace "_" with "-" to deal with older backups.
        String localeCode = new String(data, 0, size).replace('_', '-');
        Locale loc = null;
        for (int i = 0; i < availableLocales.length; i++) {
            if (availableLocales[i].equals(localeCode)) {
                loc = Locale.forLanguageTag(localeCode);
                break;
            }
        }
        if (loc == null) return; // Couldn't find the saved locale in this version of the software

        try {
            IActivityManager am = ActivityManagerNative.getDefault();
            Configuration config = am.getConfiguration();
            config.locale = loc;
            // indicate this isn't some passing default - the user wants this remembered
            config.userSetLocale = true;

            am.updateConfiguration(config);
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
