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

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ConcurrentUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Executor;

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

    private static final int STORAGE_SYSPROPS = 0;
    private static final int STORAGE_GLOBAL_SETTINGS = 1;
    private static final int STORAGE_SHARED_PREFS = 2;

    @IntDef({
        STORAGE_SYSPROPS,
        STORAGE_GLOBAL_SETTINGS,
        STORAGE_SHARED_PREFS,
    })
    private @interface Storage {}

    private static final String VALUE_TYPE_STRING = "string";
    private static final String VALUE_TYPE_INT = "int";

    @StringDef({
        VALUE_TYPE_STRING,
        VALUE_TYPE_INT,
    })
    private @interface ValueType {}

    @NonNull private final Context mContext;
    @NonNull private final StorageAdapter mStorageAdapter;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<Setting, ArrayMap<SettingChangeListener, Executor>>
            mSettingChangeListeners = new ArrayMap<>();

    private SettingsObserver mSettingsObserver;

    private LinkedHashMap<String, Setting> mSettings = new LinkedHashMap<>();

    /**
     * Exception thrown when the CEC Configuration setup verification fails.
     * This usually means a settings lacks default value or storage/storage key.
     */
    public static class VerificationException extends RuntimeException {
        public VerificationException(String message) {
            super(message);
        }
    }

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

    private class Value {
        private final String mStringValue;
        private final Integer mIntValue;

        Value(@NonNull String value) {
            mStringValue = value;
            mIntValue = null;
        }

        Value(@NonNull Integer value) {
            mStringValue = null;
            mIntValue = value;
        }

        String getStringValue() {
            return mStringValue;
        }

        Integer getIntValue() {
            return mIntValue;
        }
    }

    protected class Setting {
        @NonNull private final Context mContext;
        @NonNull private final @CecSettingName String mName;
        private final boolean mUserConfigurable;

        private Value mDefaultValue = null;
        private List<Value> mAllowedValues = new ArrayList<>();

        Setting(@NonNull Context context,
                @NonNull @CecSettingName String name,
                int userConfResId) {
            mContext = context;
            mName = name;
            mUserConfigurable = mContext.getResources().getBoolean(userConfResId);
        }

        public @CecSettingName String getName() {
            return mName;
        }

        public @ValueType String getValueType() {
            return getDefaultValue().getStringValue() != null
                    ? VALUE_TYPE_STRING
                    : VALUE_TYPE_INT;
        }

        public Value getDefaultValue() {
            if (mDefaultValue == null) {
                throw new VerificationException("Invalid CEC setup for '"
                    + this.getName() + "' setting. "
                    + "Setting has no default value.");
            }
            return mDefaultValue;
        }

        public boolean getUserConfigurable() {
            return mUserConfigurable;
        }

        private void registerValue(@NonNull Value value,
                                   int allowedResId, int defaultResId) {
            if (mContext.getResources().getBoolean(allowedResId)) {
                mAllowedValues.add(value);
                if (mContext.getResources().getBoolean(defaultResId)) {
                    if (mDefaultValue != null) {
                        throw new VerificationException("Invalid CEC setup for '"
                            + this.getName() + "' setting. "
                            + "Setting already has a default value.");
                    }
                    mDefaultValue = value;
                }
            }
        }

        public void registerValue(@NonNull String value, int allowedResId,
                                  int defaultResId) {
            registerValue(new Value(value), allowedResId, defaultResId);
        }

        public void registerValue(int value, int allowedResId,
                                  int defaultResId) {
            registerValue(new Value(value), allowedResId, defaultResId);
        }


        public List<Value> getAllowedValues() {
            return mAllowedValues;
        }
    }

    @VisibleForTesting
    HdmiCecConfig(@NonNull Context context,
                  @NonNull StorageAdapter storageAdapter) {
        mContext = context;
        mStorageAdapter = storageAdapter;

        Setting hdmiCecEnabled = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                R.bool.config_cecHdmiCecEnabled_userConfigurable);
        hdmiCecEnabled.registerValue(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED,
                R.bool.config_cecHdmiCecControlEnabled_allowed,
                R.bool.config_cecHdmiCecControlEnabled_default);
        hdmiCecEnabled.registerValue(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED,
                R.bool.config_cecHdmiCecControlDisabled_allowed,
                R.bool.config_cecHdmiCecControlDisabled_default);

        Setting hdmiCecVersion = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                R.bool.config_cecHdmiCecVersion_userConfigurable);
        hdmiCecVersion.registerValue(HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                R.bool.config_cecHdmiCecVersion14b_allowed,
                R.bool.config_cecHdmiCecVersion14b_default);
        hdmiCecVersion.registerValue(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                R.bool.config_cecHdmiCecVersion20_allowed,
                R.bool.config_cecHdmiCecVersion20_default);

        Setting powerControlMode = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                R.bool.config_cecPowerControlMode_userConfigurable);
        powerControlMode.registerValue(HdmiControlManager.POWER_CONTROL_MODE_TV,
                R.bool.config_cecPowerControlModeTv_allowed,
                R.bool.config_cecPowerControlModeTv_default);
        powerControlMode.registerValue(HdmiControlManager.POWER_CONTROL_MODE_BROADCAST,
                R.bool.config_cecPowerControlModeBroadcast_allowed,
                R.bool.config_cecPowerControlModeBroadcast_default);
        powerControlMode.registerValue(HdmiControlManager.POWER_CONTROL_MODE_NONE,
                R.bool.config_cecPowerControlModeNone_allowed,
                R.bool.config_cecPowerControlModeNone_default);

        Setting powerStateChangeOnActiveSourceLost = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                R.bool.config_cecPowerStateChangeOnActiveSourceLost_userConfigurable);
        powerStateChangeOnActiveSourceLost.registerValue(
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE,
                R.bool.config_cecPowerStateChangeOnActiveSourceLostNone_allowed,
                R.bool.config_cecPowerStateChangeOnActiveSourceLostNone_default);
        powerStateChangeOnActiveSourceLost.registerValue(
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW,
                R.bool.config_cecPowerStateChangeOnActiveSourceLostStandbyNow_allowed,
                R.bool.config_cecPowerStateChangeOnActiveSourceLostStandbyNow_default);

        Setting systemAudioModeMuting = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                R.bool.config_cecSystemAudioModeMuting_userConfigurable);
        systemAudioModeMuting.registerValue(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_ENABLED,
                R.bool.config_cecSystemAudioModeMutingEnabled_allowed,
                R.bool.config_cecSystemAudioModeMutingEnabled_default);
        systemAudioModeMuting.registerValue(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED,
                R.bool.config_cecSystemAudioModeMutingDisabled_allowed,
                R.bool.config_cecSystemAudioModeMutingDisabled_default);

        Setting volumeControlMode = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                R.bool.config_cecVolumeControlMode_userConfigurable);
        volumeControlMode.registerValue(HdmiControlManager.VOLUME_CONTROL_ENABLED,
                R.bool.config_cecVolumeControlModeEnabled_allowed,
                R.bool.config_cecVolumeControlModeEnabled_default);
        volumeControlMode.registerValue(HdmiControlManager.VOLUME_CONTROL_DISABLED,
                R.bool.config_cecVolumeControlModeDisabled_allowed,
                R.bool.config_cecVolumeControlModeDisabled_default);

        Setting tvWakeOnOneTouchPlay = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                R.bool.config_cecTvWakeOnOneTouchPlay_userConfigurable);
        tvWakeOnOneTouchPlay.registerValue(HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED,
                R.bool.config_cecTvWakeOnOneTouchPlayEnabled_allowed,
                R.bool.config_cecTvWakeOnOneTouchPlayEnabled_default);
        tvWakeOnOneTouchPlay.registerValue(HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED,
                R.bool.config_cecTvWakeOnOneTouchPlayDisabled_allowed,
                R.bool.config_cecTvWakeOnOneTouchPlayDisabled_default);

        Setting tvSendStandbyOnSleep = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                R.bool.config_cecTvSendStandbyOnSleep_userConfigurable);
        tvSendStandbyOnSleep.registerValue(HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED,
                R.bool.config_cecTvSendStandbyOnSleepEnabled_allowed,
                R.bool.config_cecTvSendStandbyOnSleepEnabled_default);
        tvSendStandbyOnSleep.registerValue(HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_DISABLED,
                R.bool.config_cecTvSendStandbyOnSleepDisabled_allowed,
                R.bool.config_cecTvSendStandbyOnSleepDisabled_default);

        Setting rcProfileTv = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV,
                R.bool.config_cecRcProfileTv_userConfigurable);
        rcProfileTv.registerValue(HdmiControlManager.RC_PROFILE_TV_NONE,
                R.bool.config_cecRcProfileTvNone_allowed,
                R.bool.config_cecRcProfileTvNone_default);
        rcProfileTv.registerValue(HdmiControlManager.RC_PROFILE_TV_ONE,
                R.bool.config_cecRcProfileTvOne_allowed,
                R.bool.config_cecRcProfileTvOne_default);
        rcProfileTv.registerValue(HdmiControlManager.RC_PROFILE_TV_TWO,
                R.bool.config_cecRcProfileTvTwo_allowed,
                R.bool.config_cecRcProfileTvTwo_default);
        rcProfileTv.registerValue(HdmiControlManager.RC_PROFILE_TV_THREE,
                R.bool.config_cecRcProfileTvThree_allowed,
                R.bool.config_cecRcProfileTvThree_default);
        rcProfileTv.registerValue(HdmiControlManager.RC_PROFILE_TV_FOUR,
                R.bool.config_cecRcProfileTvFour_allowed,
                R.bool.config_cecRcProfileTvFour_default);

        Setting rcProfileSourceRootMenu = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU,
                R.bool.config_cecRcProfileSourceRootMenu_userConfigurable);
        rcProfileSourceRootMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_ROOT_MENU_HANDLED,
                R.bool.config_cecRcProfileSourceRootMenuHandled_allowed,
                R.bool.config_cecRcProfileSourceRootMenuHandled_default);
        rcProfileSourceRootMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_ROOT_MENU_NOT_HANDLED,
                R.bool.config_cecRcProfileSourceRootMenuNotHandled_allowed,
                R.bool.config_cecRcProfileSourceRootMenuNotHandled_default);

        Setting rcProfileSourceSetupMenu = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU,
                R.bool.config_cecRcProfileSourceSetupMenu_userConfigurable);
        rcProfileSourceSetupMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_SETUP_MENU_HANDLED,
                R.bool.config_cecRcProfileSourceSetupMenuHandled_allowed,
                R.bool.config_cecRcProfileSourceSetupMenuHandled_default);
        rcProfileSourceSetupMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_SETUP_MENU_NOT_HANDLED,
                R.bool.config_cecRcProfileSourceSetupMenuNotHandled_allowed,
                R.bool.config_cecRcProfileSourceSetupMenuNotHandled_default);

        Setting rcProfileSourceContentsMenu = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU,
                R.bool.config_cecRcProfileSourceContentsMenu_userConfigurable);
        rcProfileSourceContentsMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_CONTENTS_MENU_HANDLED,
                R.bool.config_cecRcProfileSourceContentsMenuHandled_allowed,
                R.bool.config_cecRcProfileSourceContentsMenuHandled_default);
        rcProfileSourceContentsMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_CONTENTS_MENU_NOT_HANDLED,
                R.bool.config_cecRcProfileSourceContentsMenuNotHandled_allowed,
                R.bool.config_cecRcProfileSourceContentsMenuNotHandled_default);

        Setting rcProfileSourceTopMenu = registerSetting(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                R.bool.config_cecRcProfileSourceTopMenu_userConfigurable);
        rcProfileSourceTopMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_TOP_MENU_HANDLED,
                R.bool.config_cecRcProfileSourceTopMenuHandled_allowed,
                R.bool.config_cecRcProfileSourceTopMenuHandled_default);
        rcProfileSourceTopMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_TOP_MENU_NOT_HANDLED,
                R.bool.config_cecRcProfileSourceTopMenuNotHandled_allowed,
                R.bool.config_cecRcProfileSourceTopMenuNotHandled_default);

        Setting rcProfileSourceMediaContextSensitiveMenu = registerSetting(
                HdmiControlManager
                    .CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU,
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenu_userConfigurable);
        rcProfileSourceMediaContextSensitiveMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_MEDIA_CONTEXT_SENSITIVE_MENU_HANDLED,
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenuHandled_allowed,
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenuHandled_default);
        rcProfileSourceMediaContextSensitiveMenu.registerValue(
                HdmiControlManager.RC_PROFILE_SOURCE_MEDIA_CONTEXT_SENSITIVE_MENU_NOT_HANDLED,
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenuNotHandled_allowed,
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenuNotHandled_default);

        verifySettings();
    }

    HdmiCecConfig(@NonNull Context context) {
        this(context, new StorageAdapter(context));
    }

    private Setting registerSetting(@NonNull @CecSettingName String name,
                               int userConfResId) {
        Setting setting = new Setting(mContext, name, userConfResId);
        mSettings.put(name, setting);
        return setting;
    }

    private void verifySettings() {
        for (Setting setting: mSettings.values()) {
            // This will throw an exception when a setting
            // doesn't have a default value assigned.
            setting.getDefaultValue();
            getStorage(setting);
            getStorageKey(setting);
        }
    }

    @Nullable
    private Setting getSetting(@NonNull String name) {
        return mSettings.containsKey(name) ? mSettings.get(name) : null;
    }

    @Storage
    private int getStorage(@NonNull Setting setting) {
        switch (setting.getName()) {
            case HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED:
                return STORAGE_GLOBAL_SETTINGS;
            case HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION:
                return STORAGE_SHARED_PREFS;
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
                throw new VerificationException("Invalid CEC setting '" + setting.getName()
                        + "' storage.");
        }
    }

    private String getStorageKey(@NonNull Setting setting) {
        switch (setting.getName()) {
            case HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED:
                return Global.HDMI_CONTROL_ENABLED;
            case HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION:
                return setting.getName();
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
                throw new VerificationException("Invalid CEC setting '" + setting.getName()
                    + "' storage key.");
        }
    }

    protected String retrieveValue(@NonNull Setting setting, @NonNull String defaultValue) {
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

    protected void storeValue(@NonNull Setting setting, @NonNull String value) {
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

    private void notifyGlobalSettingChanged(String setting) {
        switch (setting) {
            case Global.HDMI_CONTROL_ENABLED:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED);
                break;
            case Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE);
                break;
            case Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE);
                break;
            case Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY);
                break;
            case Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED:
                notifySettingChanged(HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP);
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

    protected void notifySettingChanged(@NonNull Setting setting) {
        synchronized (mLock) {
            ArrayMap<SettingChangeListener, Executor> listeners =
                    mSettingChangeListeners.get(setting);
            if (listeners == null) {
                return;  // No listeners registered, do nothing.
            }
            for (Entry<SettingChangeListener, Executor> entry: listeners.entrySet()) {
                SettingChangeListener listener = entry.getKey();
                Executor executor = entry.getValue();
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        listener.onChange(setting.getName());
                    }
                });
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
                Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED,
                Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED,
                Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
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
     * Register change listener for a given setting name using DirectExecutor.
     */
    public void registerChangeListener(@NonNull @CecSettingName String name,
                                       SettingChangeListener listener) {
        registerChangeListener(name, listener, ConcurrentUtils.DIRECT_EXECUTOR);
    }

    /**
     * Register change listener for a given setting name and executor.
     */
    public void registerChangeListener(@NonNull @CecSettingName String name,
                                       SettingChangeListener listener,
                                       Executor executor) {
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
                mSettingChangeListeners.put(setting, new ArrayMap<>());
            }
            mSettingChangeListeners.get(setting).put(listener, executor);
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
                ArrayMap<SettingChangeListener, Executor> listeners =
                        mSettingChangeListeners.get(setting);
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
        return new ArrayList<>(mSettings.keySet());
    }

    /**
     * Returns a list of user-modifiable settings based on the XML metadata.
     */
    public @CecSettingName List<String> getUserSettings() {
        List<String> settings = new ArrayList<>();
        for (Setting setting: mSettings.values()) {
            if (setting.getUserConfigurable()) {
                settings.add(setting.getName());
            }
        }
        return settings;
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
        for (Value allowedValue : setting.getAllowedValues()) {
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
        for (Value allowedValue : setting.getAllowedValues()) {
            allowedValues.add(allowedValue.getIntValue());
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
        return getSetting(name).getDefaultValue().getIntValue();
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
        String defaultValue = Integer.toString(setting.getDefaultValue().getIntValue());
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
