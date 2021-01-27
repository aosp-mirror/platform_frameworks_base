/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.hdmi;

import static android.hardware.hdmi.HdmiControlManager.CecSettingName;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.hdmi.HdmiControlManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.cec.config.CecSettings;
import com.android.server.hdmi.cec.config.Setting;
import com.android.server.hdmi.cec.config.Value;
import com.android.server.hdmi.cec.config.XmlParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * The {@link HdmiCecConfig} class is used for getting information about
 * available HDMI CEC settings.
 */
public class HdmiCecConfig {
    private static final String TAG = "HdmiCecConfig";

    private static final String ETC_DIR = "etc";
    private static final String CONFIG_FILE = "cec_config.xml";
    private static final String SHARED_PREFS_DIR = "shared_prefs";
    private static final String SHARED_PREFS_NAME = "cec_config.xml";

    @IntDef({
        STORAGE_SYSPROPS,
        STORAGE_GLOBAL_SETTINGS,
        STORAGE_SHARED_PREFS,
    })
    private @interface Storage {}

    private static final int STORAGE_SYSPROPS = 0;
    private static final int STORAGE_GLOBAL_SETTINGS = 1;
    private static final int STORAGE_SHARED_PREFS = 2;

    private static final String VALUE_TYPE_STRING = "string";
    private static final String VALUE_TYPE_INT = "int";

    @StringDef({
        VALUE_TYPE_STRING,
        VALUE_TYPE_INT,
    })
    private @interface ValueType {}

    @NonNull private final Context mContext;
    @NonNull private final StorageAdapter mStorageAdapter;
    @Nullable private final CecSettings mSystemConfig;
    @Nullable private final CecSettings mVendorOverride;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<Setting, Set<SettingChangeListener>>
            mSettingChangeListeners = new ArrayMap<>();

    private SettingsObserver mSettingsObserver;

    /**
     * Listener used to get notifications when value of a setting changes.
     */
    public interface SettingChangeListener {
        /**
         * Called when value of a setting changes.
         *
         * @param setting name of a CEC setting that changed
         */
        void onChange(@NonNull @CecSettingName String setting);
    }

    /**
     * Setting storage input/output helper class.
     */
    public static class StorageAdapter {
        @NonNull private final Context mContext;
        @NonNull private final SharedPreferences mSharedPrefs;

        StorageAdapter(@NonNull Context context) {
            mContext = context;
            // The package info in the context isn't initialized in the way it is for normal apps,
            // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
            // build the path manually below using the same policy that appears in ContextImpl.
            final Context deviceContext = mContext.createDeviceProtectedStorageContext();
            final File prefsFile = new File(new File(Environment.getDataSystemDirectory(),
                                                     SHARED_PREFS_DIR), SHARED_PREFS_NAME);
            mSharedPrefs = deviceContext.getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
        }

        /**
         * Read the value from a system property.
         * Returns the given default value if the system property is not set.
         */
        public String retrieveSystemProperty(@NonNull String storageKey,
                                             @NonNull String defaultValue) {
            return SystemProperties.get(storageKey, defaultValue);
        }

        /**
         * Write the value to a system property.
         */
        public void storeSystemProperty(@NonNull String storageKey,
                                        @NonNull String value) {
            SystemProperties.set(storageKey, value);
        }

        /**
         * Read the value from a global setting.
         * Returns the given default value if the system property is not set.
         */
        public String retrieveGlobalSetting(@NonNull String storageKey,
                                            @NonNull String defaultValue) {
            String value = Global.getString(mContext.getContentResolver(), storageKey);
            return value != null ? value : defaultValue;
        }

        /**
         * Write the value to a global setting.
         */
        public void storeGlobalSetting(@NonNull String storageKey,
                                       @NonNull String value) {
            Global.putString(mContext.getContentResolver(), storageKey, value);
        }

        /**
         * Read the value from a shared preference.
         * Returns the given default value if the preference is not set.
         */
        public String retrieveSharedPref(@NonNull String storageKey,
                                         @NonNull String defaultValue) {
            return mSharedPrefs.getString(storageKey, defaultValue);
        }

        /**
         * Write the value to a shared preference.
         */
        public void storeSharedPref(@NonNull String storageKey,
                                    @NonNull String value) {
            mSharedPrefs.edit().putString(storageKey, value).apply();
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            String setting = uri.getLastPathSegment();
            HdmiCecConfig.this.notifyGlobalSettingChanged(setting);
        }
    }

    @VisibleForTesting
    HdmiCecConfig(@NonNull Context context,
                  @NonNull StorageAdapter storageAdapter,
                  @Nullable CecSettings systemConfig,
                  @Nullable CecSettings vendorOverride) {
        mContext = context;
        mStorageAdapter = storageAdapter;
        mSystemConfig = systemConfig;
        mVendorOverride = vendorOverride;
        if (mSystemConfig == null) {
            Slog.i(TAG, "CEC system configuration XML missing.");
        }
        if (mVendorOverride == null) {
            Slog.i(TAG, "CEC OEM configuration override XML missing.");
        }
    }

    HdmiCecConfig(@NonNull Context context) {
        this(context, new StorageAdapter(context),
             readSettingsFromFile(Environment.buildPath(Environment.getRootDirectory(),
                                                        ETC_DIR, CONFIG_FILE)),
             readSettingsFromFile(Environment.buildPath(Environment.getVendorDirectory(),
                                                        ETC_DIR, CONFIG_FILE)));
    }

    @Nullable
    private static CecSettings readSettingsFromFile(@NonNull File file) {
        if (!file.exists()) {
            return null;
        }
        if (!file.isFile()) {
            Slog.e(TAG, "CEC configuration is not a file: " + file + ", skipping.");
            return null;
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return XmlParser.read(in);
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing CEC config file: " + file, e);
        }
        return null;
    }

    @NonNull
    @VisibleForTesting
    static HdmiCecConfig createFromStrings(@NonNull Context context,
                                           @NonNull StorageAdapter storageAdapter,
                                           @Nullable String productConfigXml,
                                           @Nullable String vendorOverrideXml) {
        CecSettings productConfig = null;
        CecSettings vendorOverride = null;
        try {
            if (productConfigXml != null) {
                productConfig = XmlParser.read(
                        new ByteArrayInputStream(productConfigXml.getBytes()));
            }
            if (vendorOverrideXml != null) {
                vendorOverride = XmlParser.read(
                        new ByteArrayInputStream(vendorOverrideXml.getBytes()));
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing CEC config strings", e);
        }
        return new HdmiCecConfig(context, storageAdapter, productConfig, vendorOverride);
    }

    @Nullable
    private Setting getSetting(@NonNull String name) {
        if (mSystemConfig == null) {
            return null;
        }
        if (mVendorOverride != null) {
            // First read from the vendor override.
            for (Setting setting : mVendorOverride.getSetting()) {
                if (setting.getName().equals(name)) {
                    return setting;
                }
            }
        }
        // If not found, try the system config.
        for (Setting setting : mSystemConfig.getSetting()) {
            if (setting.getName().equals(name)) {
                return setting;
            }
        }
        return null;
    }

    @Storage
    private int getStorage(@NonNull Setting setting) {
        switch (setting.getName()) {
            case HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED:
                return STORAGE_GLOBAL_SETTINGS;
            case HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION:
                return STORAGE_GLOBAL_SETTINGS;
            case HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE:
                return STORAGE_GLOBAL_SETTINGS;
            case HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE:
                return STORAGE_GLOBAL_SETTINGS;
            case HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST:
                return STORAGE_SHARED_PREFS;
            case HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING:
                return STORAGE_SHARED_PREFS;
            case HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY:
                return STORAGE_GLOBAL_SETTINGS;
            case HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP:
                return STORAGE_GLOBAL_SETTINGS;
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV:
                return STORAGE_SHARED_PREFS;
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU:
                return STORAGE_SHARED_PREFS;
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU:
                return STORAGE_SHARED_PREFS;
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU:
                return STORAGE_SHARED_PREFS;
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU:
                return STORAGE_SHARED_PREFS;
            case HdmiControlManager
                    .CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU:
                return STORAGE_SHARED_PREFS;
            default:
                throw new RuntimeException("Invalid CEC setting '" + setting.getName()
                        + "' storage.");
        }
    }

    private String getStorageKey(@NonNull Setting setting) {
        switch (setting.getName()) {
            case HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED:
                return Global.HDMI_CONTROL_ENABLED;
            case HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION:
                return Global.HDMI_CEC_VERSION;
            case HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE:
                return Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP;
            case HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE:
                return Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED;
            case HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST:
                return setting.getName();
            case HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING:
                return setting.getName();
            case HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY:
                return Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED;
            case HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP:
                return Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED;
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV:
                return setting.getName();
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU:
                return setting.getName();
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU:
                return setting.getName();
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU:
                return setting.getName();
            case HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU:
                return setting.getName();
            case HdmiControlManager
                    .CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU:
                return setting.getName();
            default:
                throw new RuntimeException("Invalid CEC setting '" + setting.getName()
                    + "' storage key.");
        }
    }

    private String retrieveValue(@NonNull Setting setting, @NonNull String defaultValue) {
        @Storage int storage = getStorage(setting);
        String storageKey = getStorageKey(setting);
        if (storage == STORAGE_SYSPROPS) {
            HdmiLogger.debug("Reading '" + storageKey + "' sysprop.");
            return mStorageAdapter.retrieveSystemProperty(storageKey, defaultValue);
        } else if (storage == STORAGE_GLOBAL_SETTINGS) {
            HdmiLogger.debug("Reading '" + storageKey + "' global setting.");
            return mStorageAdapter.retrieveGlobalSetting(storageKey, defaultValue);
        } else if (storage == STORAGE_SHARED_PREFS) {
            HdmiLogger.debug("Reading '" + storageKey + "' shared preference.");
            return mStorageAdapter.retrieveSharedPref(storageKey, defaultValue);
        }
        return null;
    }

    private void storeValue(@NonNull Setting setting, @NonNull String value) {
        @Storage int storage = getStorage(setting);
        String storageKey = getStorageKey(setting);
        if (storage == STORAGE_SYSPROPS) {
            HdmiLogger.debug("Setting '" + storageKey + "' sysprop.");
            mStorageAdapter.storeSystemProperty(storageKey, value);
        } else if (storage == STORAGE_GLOBAL_SETTINGS) {
            HdmiLogger.debug("Setting '" + storageKey + "' global setting.");
            mStorageAdapter.storeGlobalSetting(storageKey, value);
        } else if (storage == STORAGE_SHARED_PREFS) {
            HdmiLogger.debug("Setting '" + storageKey + "' shared pref.");
            mStorageAdapter.storeSharedPref(storageKey, value);
            notifySettingChanged(setting);
        }
    }

    private int getIntValue(@NonNull Value value) {
        return Integer.decode(value.getIntValue());
    }

    private void notifyGlobalSettingChanged(String setting) {
        switch (setting) {
            case Global.HDMI_CONTROL_ENABLED:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED);
                break;
            case Global.HDMI_CEC_VERSION:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION);
                break;
            case Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE);
                break;
            case Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE);
                break;
        }
    }

    private void notifySettingChanged(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        notifySettingChanged(setting);
    }

    private void notifySettingChanged(@NonNull Setting setting) {
        synchronized (mLock) {
            Set<SettingChangeListener> listeners = mSettingChangeListeners.get(setting);
            if (listeners == null) {
                return;  // No listeners registered, do nothing.
            }
            for (SettingChangeListener listener: listeners) {
                listener.onChange(setting.getName());
            }
        }
    }

    /**
     * This method registers Global Setting change observer.
     * Needs to be called once after initialization of HdmiCecConfig.
     */
    public void registerGlobalSettingsObserver(Looper looper) {
        Handler handler = new Handler(looper);
        mSettingsObserver = new SettingsObserver(handler);
        ContentResolver resolver = mContext.getContentResolver();
        String[] settings = new String[] {
                Global.HDMI_CONTROL_ENABLED,
                Global.HDMI_CEC_VERSION,
                Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED,
        };
        for (String setting: settings) {
            resolver.registerContentObserver(Global.getUriFor(setting), false,
                                             mSettingsObserver, UserHandle.USER_ALL);
        }
    }

    /**
     * This method unregisters Global Setting change observer.
     */
    public void unregisterGlobalSettingsObserver() {
        ContentResolver resolver = mContext.getContentResolver();
        resolver.unregisterContentObserver(mSettingsObserver);
    }

    /**
     * Register change listener for a given setting name.
     */
    public void registerChangeListener(@NonNull @CecSettingName String name,
                                       SettingChangeListener listener) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        @Storage int storage = getStorage(setting);
        if (storage != STORAGE_GLOBAL_SETTINGS && storage != STORAGE_SHARED_PREFS) {
            throw new IllegalArgumentException("Change listeners for setting '" + name
                    + "' not supported.");
        }
        synchronized (mLock) {
            if (!mSettingChangeListeners.containsKey(setting)) {
                mSettingChangeListeners.put(setting, new HashSet<>());
            }
            mSettingChangeListeners.get(setting).add(listener);
        }
    }

    /**
     * Remove change listener for a given setting name.
     */
    public void removeChangeListener(@NonNull @CecSettingName String name,
                                     SettingChangeListener listener) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        synchronized (mLock) {
            if (mSettingChangeListeners.containsKey(setting)) {
                Set<SettingChangeListener> listeners = mSettingChangeListeners.get(setting);
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    mSettingChangeListeners.remove(setting);
                }
            }
        }
    }

    /**
     * Returns a list of all settings based on the XML metadata.
     */
    public @CecSettingName List<String> getAllSettings() {
        if (mSystemConfig == null) {
            return new ArrayList<String>();
        }
        List<String> allSettings = new ArrayList<String>();
        for (Setting setting : mSystemConfig.getSetting()) {
            allSettings.add(setting.getName());
        }
        return allSettings;
    }

    /**
     * Returns a list of user-modifiable settings based on the XML metadata.
     */
    public @CecSettingName List<String> getUserSettings() {
        if (mSystemConfig == null) {
            return new ArrayList<String>();
        }
        Set<String> userSettings = new HashSet<String>();
        // First read from the system config.
        for (Setting setting : mSystemConfig.getSetting()) {
            if (setting.getUserConfigurable()) {
                userSettings.add(setting.getName());
            }
        }
        if (mVendorOverride != null) {
            // Next either add or remove based on the vendor override.
            for (Setting setting : mVendorOverride.getSetting()) {
                if (setting.getUserConfigurable()) {
                    userSettings.add(setting.getName());
                } else {
                    userSettings.remove(setting.getName());
                }
            }
        }
        return new ArrayList(userSettings);
    }

    /**
     * For a given setting name returns true if and only if the value type of that
     * setting is a string.
     */
    public boolean isStringValueType(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        return getSetting(name).getValueType().equals(VALUE_TYPE_STRING);
    }

    /**
     * For a given setting name returns true if and only if the value type of that
     * setting is an int.
     */
    public boolean isIntValueType(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        return getSetting(name).getValueType().equals(VALUE_TYPE_INT);
    }

    /**
     * For a given setting name returns values that are allowed for that setting (string).
     */
    public List<String> getAllowedStringValues(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        if (!setting.getValueType().equals(VALUE_TYPE_STRING)) {
            throw new IllegalArgumentException("Setting '" + name
                    + "' is not a string-type setting.");
        }
        List<String> allowedValues = new ArrayList<String>();
        for (Value allowedValue : setting.getAllowedValues().getValue()) {
            allowedValues.add(allowedValue.getStringValue());
        }
        return allowedValues;
    }

    /**
     * For a given setting name returns values that are allowed for that setting (string).
     */
    public List<Integer> getAllowedIntValues(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        if (!setting.getValueType().equals(VALUE_TYPE_INT)) {
            throw new IllegalArgumentException("Setting '" + name
                    + "' is not a string-type setting.");
        }
        List<Integer> allowedValues = new ArrayList<Integer>();
        for (Value allowedValue : setting.getAllowedValues().getValue()) {
            allowedValues.add(getIntValue(allowedValue));
        }
        return allowedValues;
    }

    /**
     * For a given setting name returns the default value for that setting (string).
     */
    public String getDefaultStringValue(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        if (!setting.getValueType().equals(VALUE_TYPE_STRING)) {
            throw new IllegalArgumentException("Setting '" + name
                    + "' is not a string-type setting.");
        }
        return getSetting(name).getDefaultValue().getStringValue();
    }

    /**
     * For a given setting name returns the default value for that setting (int).
     */
    public int getDefaultIntValue(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        if (!setting.getValueType().equals(VALUE_TYPE_INT)) {
            throw new IllegalArgumentException("Setting '" + name
                    + "' is not a string-type setting.");
        }
        return getIntValue(getSetting(name).getDefaultValue());
    }

    /**
     * For a given setting name returns the current value of that setting (string).
     */
    public String getStringValue(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        if (!setting.getValueType().equals(VALUE_TYPE_STRING)) {
            throw new IllegalArgumentException("Setting '" + name
                    + "' is not a string-type setting.");
        }
        HdmiLogger.debug("Getting CEC setting value '" + name + "'.");
        return retrieveValue(setting, setting.getDefaultValue().getStringValue());
    }

    /**
     * For a given setting name returns the current value of that setting (int).
     */
    public int getIntValue(@NonNull @CecSettingName String name) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        if (!setting.getValueType().equals(VALUE_TYPE_INT)) {
            throw new IllegalArgumentException("Setting '" + name
                    + "' is not a int-type setting.");
        }
        HdmiLogger.debug("Getting CEC setting value '" + name + "'.");
        String defaultValue = Integer.toString(getIntValue(setting.getDefaultValue()));
        String value = retrieveValue(setting, defaultValue);
        return Integer.parseInt(value);
    }

    /**
     * For a given setting name and value sets the current value of that setting (string).
     */
    public void setStringValue(@NonNull @CecSettingName String name, @NonNull String value) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        if (!setting.getUserConfigurable()) {
            throw new IllegalArgumentException("Updating CEC setting '" + name + "' prohibited.");
        }
        if (!setting.getValueType().equals(VALUE_TYPE_STRING)) {
            throw new IllegalArgumentException("Setting '" + name
                    + "' is not a string-type setting.");
        }
        if (!getAllowedStringValues(name).contains(value)) {
            throw new IllegalArgumentException("Invalid CEC setting '" + name
                                               + "' value: '" + value + "'.");
        }
        HdmiLogger.debug("Updating CEC setting '" + name + "' to '" + value + "'.");
        storeValue(setting, value);
    }

    /**
     * For a given setting name and value sets the current value of that setting (int).
     */
    public void setIntValue(@NonNull @CecSettingName String name, int value) {
        Setting setting = getSetting(name);
        if (setting == null) {
            throw new IllegalArgumentException("Setting '" + name + "' does not exist.");
        }
        if (!setting.getUserConfigurable()) {
            throw new IllegalArgumentException("Updating CEC setting '" + name + "' prohibited.");
        }
        if (!setting.getValueType().equals(VALUE_TYPE_INT)) {
            throw new IllegalArgumentException("Setting '" + name
                    + "' is not a int-type setting.");
        }
        if (!getAllowedIntValues(name).contains(value)) {
            throw new IllegalArgumentException("Invalid CEC setting '" + name
                                               + "' value: '" + value + "'.");
        }
        HdmiLogger.debug("Updating CEC setting '" + name + "' to '" + value + "'.");
        storeValue(setting, Integer.toString(value));
    }
}
