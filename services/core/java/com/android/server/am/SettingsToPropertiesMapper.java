/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;

/**
 * Maps system settings to system properties.
 * <p>The properties are dynamically updated when settings change.
 * @hide
 */
public class SettingsToPropertiesMapper {

    private static final String TAG = "SettingsToPropertiesMapper";

    private static final String SYSTEM_PROPERTY_PREFIX = "persist.device_config.";

    private static final String RESET_PERFORMED_PROPERTY = "device_config.reset_performed";

    private static final String RESET_RECORD_FILE_PATH =
            "/data/server_configurable_flags/reset_flags";

    private static final String SYSTEM_PROPERTY_VALID_CHARACTERS_REGEX = "^[\\w\\.\\-@:]*$";

    private static final String SYSTEM_PROPERTY_INVALID_SUBSTRING = "..";

    private static final int SYSTEM_PROPERTY_MAX_LENGTH = 92;

    // experiment flags added to Global.Settings(before new "Config" provider table is available)
    // will be added under this category.
    private static final String GLOBAL_SETTINGS_CATEGORY = "global_settings";

    // Add the global setting you want to push to native level as experiment flag into this list.
    //
    // NOTE: please grant write permission system property prefix
    // with format persist.device_config.global_settings.[flag_name] in system_server.te and grant
    // read permission in the corresponding .te file your feature belongs to.
    @VisibleForTesting
    static final String[] sGlobalSettings = new String[] {
            Settings.Global.NATIVE_FLAGS_HEALTH_CHECK_ENABLED,
    };

    // TODO(b/282593625): Move this constant to DeviceConfig module
    private static final String NAMESPACE_TETHERING_U_OR_LATER_NATIVE =
            "tethering_u_or_later_native";

    // All the flags under the listed DeviceConfig scopes will be synced to native level.
    //
    // NOTE: please grant write permission system property prefix
    // with format persist.device_config.[device_config_scope]. in system_server.te and grant read
    // permission in the corresponding .te file your feature belongs to.
    @VisibleForTesting
    static final String[] sDeviceConfigScopes = new String[] {
        DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_CAMERA_NATIVE,
        DeviceConfig.NAMESPACE_CONFIGURATION,
        DeviceConfig.NAMESPACE_CONNECTIVITY,
        DeviceConfig.NAMESPACE_EDGETPU_NATIVE,
        DeviceConfig.NAMESPACE_INPUT_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_INTELLIGENCE_CONTENT_SUGGESTIONS,
        DeviceConfig.NAMESPACE_LMKD_NATIVE,
        DeviceConfig.NAMESPACE_MEDIA_NATIVE,
        DeviceConfig.NAMESPACE_MGLRU_NATIVE,
        DeviceConfig.NAMESPACE_NETD_NATIVE,
        DeviceConfig.NAMESPACE_NNAPI_NATIVE,
        DeviceConfig.NAMESPACE_PROFCOLLECT_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_REMOTE_KEY_PROVISIONING_NATIVE,
        DeviceConfig.NAMESPACE_RUNTIME_NATIVE,
        DeviceConfig.NAMESPACE_RUNTIME_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_STATSD_NATIVE,
        DeviceConfig.NAMESPACE_STATSD_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_SURFACE_FLINGER_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_SWCODEC_NATIVE,
        DeviceConfig.NAMESPACE_VENDOR_SYSTEM_NATIVE,
        DeviceConfig.NAMESPACE_VENDOR_SYSTEM_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_VIRTUALIZATION_FRAMEWORK_NATIVE,
        DeviceConfig.NAMESPACE_WINDOW_MANAGER_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_MEMORY_SAFETY_NATIVE_BOOT,
        DeviceConfig.NAMESPACE_MEMORY_SAFETY_NATIVE,
        DeviceConfig.NAMESPACE_HDMI_CONTROL,
        NAMESPACE_TETHERING_U_OR_LATER_NATIVE
    };

    // All the aconfig flags under the listed DeviceConfig scopes will be synced to native level.
    // The list is sorted.
    @VisibleForTesting
    static final String[] sDeviceConfigAconfigScopes = new String[] {
        "accessibility",
        "android_core_networking",
        "android_stylus",
        "aoc",
        "app_widgets",
        "arc_next",
        "avic",
        "bluetooth",
        "brownout_mitigation_audio",
        "build",
        "biometrics",
        "biometrics_framework",
        "biometrics_integration",
        "camera_hal",
        "camera_platform",
        "car_framework",
        "car_perception",
        "car_security",
        "car_telemetry",
        "codec_fwk",
        "companion",
        "content_protection",
        "context_hub",
        "core_experiments_team_internal",
        "core_graphics",
        "core_libraries",
        "dck_framework",
        "devoptions_settings",
        "game",
        "gpu",
        "haptics",
        "hardware_backed_security_mainline",
        "input",
        "lse_desktop_experience",
        "machine_learning",
        "mainline_modularization",
        "mainline_sdk",
        "make_pixel_haptics",
        "media_audio",
        "media_drm",
        "media_reliability",
        "media_tv",
        "media_solutions",
        "nfc",
        "pdf_viewer",
        "perfetto",
        "pixel_audio_android",
        "pixel_biometrics_face",
        "pixel_bluetooth",
        "pixel_connectivity_gps",
        "pixel_system_sw_video",
        "pixel_watch",
        "platform_security",
        "pmw",
        "power",
        "preload_safety",
        "privacy_infra_policy",
        "resource_manager",
        "responsible_apis",
        "rust",
        "safety_center",
        "sensors",
        "system_performance",
        "system_sw_touch",
        "system_sw_usb",
        "statsd",
        "test_suites",
        "text",
        "threadnetwork",
        "tv_system_ui",
        "usb",
        "vibrator",
        "virtualization",
        "virtual_devices",
        "wallet_integration",
        "wear_calling_messaging",
        "wear_connectivity",
        "wear_esim_carriers",
        "wear_frameworks",
        "wear_health_services",
        "wear_media",
        "wear_offload",
        "wear_security",
        "wear_system_health",
        "wear_systems",
        "wear_sysui",
        "window_surfaces",
        "windowing_frontend",
    };

    public static final String NAMESPACE_REBOOT_STAGING = "staged";
    public static final String NAMESPACE_REBOOT_STAGING_DELIMITER = "*";

    private final String[] mGlobalSettings;

    private final String[] mDeviceConfigScopes;

    private final String[] mDeviceConfigAconfigScopes;

    private final ContentResolver mContentResolver;

    @VisibleForTesting
    protected SettingsToPropertiesMapper(ContentResolver contentResolver,
            String[] globalSettings,
            String[] deviceConfigScopes,
            String[] deviceConfigAconfigScopes) {
        mContentResolver = contentResolver;
        mGlobalSettings = globalSettings;
        mDeviceConfigScopes = deviceConfigScopes;
        mDeviceConfigAconfigScopes = deviceConfigAconfigScopes;
    }

    @VisibleForTesting
    void updatePropertiesFromSettings() {
        for (String globalSetting : mGlobalSettings) {
            Uri settingUri = Settings.Global.getUriFor(globalSetting);
            String propName = makePropertyName(GLOBAL_SETTINGS_CATEGORY, globalSetting);
            if (settingUri == null) {
                log("setting uri is null for globalSetting " + globalSetting);
                continue;
            }
            if (propName == null) {
                log("invalid prop name for globalSetting " + globalSetting);
                continue;
            }

            ContentObserver co = new ContentObserver(null) {
                @Override
                public void onChange(boolean selfChange) {
                    updatePropertyFromSetting(globalSetting, propName);
                }
            };

            // only updating on starting up when no native flags reset is performed during current
            // booting.
            if (!isNativeFlagsResetPerformed()) {
                updatePropertyFromSetting(globalSetting, propName);
            }
            mContentResolver.registerContentObserver(settingUri, false, co);
        }

        for (String deviceConfigScope : mDeviceConfigScopes) {
            DeviceConfig.addOnPropertiesChangedListener(
                    deviceConfigScope,
                    AsyncTask.THREAD_POOL_EXECUTOR,
                    (DeviceConfig.Properties properties) -> {
                        String scope = properties.getNamespace();
                        for (String key : properties.getKeyset()) {
                            String propertyName = makePropertyName(scope, key);
                            if (propertyName == null) {
                                log("unable to construct system property for " + scope + "/"
                                        + key);
                                return;
                            }
                            setProperty(propertyName, properties.getString(key, null));

                            // for legacy namespaces, they can also be used for trunk stable
                            // purposes. so push flag also into trunk stable slot in sys prop,
                            // later all legacy usage will be refactored and the sync to old
                            // sys prop slot can be removed.
                            String aconfigPropertyName = makeAconfigFlagPropertyName(scope, key);
                            if (aconfigPropertyName == null) {
                                log("unable to construct system property for " + scope + "/"
                                        + key);
                                return;
                            }
                            setProperty(aconfigPropertyName, properties.getString(key, null));
                        }
                    });
        }

        for (String deviceConfigAconfigScope : mDeviceConfigAconfigScopes) {
            DeviceConfig.addOnPropertiesChangedListener(
                    deviceConfigAconfigScope,
                    AsyncTask.THREAD_POOL_EXECUTOR,
                    (DeviceConfig.Properties properties) -> {
                        String scope = properties.getNamespace();
                        for (String key : properties.getKeyset()) {
                            String aconfigPropertyName = makeAconfigFlagPropertyName(scope, key);
                            if (aconfigPropertyName == null) {
                                log("unable to construct system property for " + scope + "/"
                                        + key);
                                return;
                            }
                            setProperty(aconfigPropertyName, properties.getString(key, null));
                        }
                    });
        }

        // add sys prop sync callback for staged flag values
        DeviceConfig.addOnPropertiesChangedListener(
            NAMESPACE_REBOOT_STAGING,
            AsyncTask.THREAD_POOL_EXECUTOR,
            (DeviceConfig.Properties properties) -> {
              String scope = properties.getNamespace();
              for (String key : properties.getKeyset()) {
                String aconfigPropertyName = makeAconfigFlagStagedPropertyName(key);
                if (aconfigPropertyName == null) {
                    log("unable to construct system property for " + scope + "/" + key);
                    return;
                }
                setProperty(aconfigPropertyName, properties.getString(key, null));
              }
            });
    }

    public static SettingsToPropertiesMapper start(ContentResolver contentResolver) {
        SettingsToPropertiesMapper mapper =  new SettingsToPropertiesMapper(
                contentResolver,
                sGlobalSettings,
                sDeviceConfigScopes,
                sDeviceConfigAconfigScopes);
        mapper.updatePropertiesFromSettings();
        return mapper;
    }

    /**
     * If native level flags reset has been performed as an attempt to recover from a crash loop
     * during current device booting.
     * @return
     */
    public static boolean isNativeFlagsResetPerformed() {
        String value = SystemProperties.get(RESET_PERFORMED_PROPERTY);
        return "true".equals(value);
    }

    /**
     * return an array of native flag categories under which flags got reset during current device
     * booting.
     * @return
     */
    public static @NonNull String[] getResetNativeCategories() {
        if (!isNativeFlagsResetPerformed()) {
            return new String[0];
        }

        String content = getResetFlagsFileContent();
        if (TextUtils.isEmpty(content)) {
            return new String[0];
        }

        String[] property_names = content.split(";");
        HashSet<String> categories = new HashSet<>();
        for (String property_name : property_names) {
            String[] segments = property_name.split("\\.");
            if (segments.length < 3) {
                log("failed to extract category name from property " + property_name);
                continue;
            }
            categories.add(segments[2]);
        }
        return categories.toArray(new String[0]);
    }

    /**
     * system property name constructing rule: "persist.device_config.[category_name].[flag_name]".
     * If the name contains invalid characters or substrings for system property name,
     * will return null.
     * @param categoryName
     * @param flagName
     * @return
     */
    @VisibleForTesting
    static String makePropertyName(String categoryName, String flagName) {
        String propertyName = SYSTEM_PROPERTY_PREFIX + categoryName + "." + flagName;

        if (!propertyName.matches(SYSTEM_PROPERTY_VALID_CHARACTERS_REGEX)
                || propertyName.contains(SYSTEM_PROPERTY_INVALID_SUBSTRING)) {
            return null;
        }

        return propertyName;
    }

    /**
     * system property name constructing rule for staged aconfig flags, the flag name
     * is in the form of [namespace]*[actual flag name], we should push the following
     * to system properties
     * "next_boot.[actual sys prop name]".
     * If the name contains invalid characters or substrings for system property name,
     * will return null.
     * @param flagName
     * @return
     */
    @VisibleForTesting
    static String makeAconfigFlagStagedPropertyName(String flagName) {
        int idx = flagName.indexOf(NAMESPACE_REBOOT_STAGING_DELIMITER);
        if (idx == -1 || idx == flagName.length() - 1 || idx == 0) {
            log("invalid staged flag: " + flagName);
            return null;
        }

        String propertyName = "next_boot." + makeAconfigFlagPropertyName(
                flagName.substring(0, idx), flagName.substring(idx+1));

        if (!propertyName.matches(SYSTEM_PROPERTY_VALID_CHARACTERS_REGEX)
                || propertyName.contains(SYSTEM_PROPERTY_INVALID_SUBSTRING)) {
            return null;
        }

        return propertyName;
    }

    /**
     * system property name constructing rule for aconfig flags:
     * "persist.device_config.aconfig_flags.[category_name].[flag_name]".
     * If the name contains invalid characters or substrings for system property name,
     * will return null.
     * @param categoryName
     * @param flagName
     * @return
     */
    @VisibleForTesting
    static String makeAconfigFlagPropertyName(String categoryName, String flagName) {
        String propertyName = SYSTEM_PROPERTY_PREFIX + "aconfig_flags." +
                              categoryName + "." + flagName;

        if (!propertyName.matches(SYSTEM_PROPERTY_VALID_CHARACTERS_REGEX)
                || propertyName.contains(SYSTEM_PROPERTY_INVALID_SUBSTRING)) {
            return null;
        }

        return propertyName;
    }

    private void setProperty(String key, String value) {
        // Check if need to clear the property
        if (value == null) {
            // It's impossible to remove system property, therefore we check previous value to
            // avoid setting an empty string if the property wasn't set.
            if (TextUtils.isEmpty(SystemProperties.get(key))) {
                return;
            }
            value = "";
        } else if (value.length() > SYSTEM_PROPERTY_MAX_LENGTH) {
            log("key=" + key + " value=" + value + " exceeds system property max length.");
            return;
        }

        try {
            SystemProperties.set(key, value);
        } catch (Exception e) {
            // Failure to set a property can be caused by SELinux denial. This usually indicates
            // that the property wasn't allowlisted in sepolicy.
            // No need to report it on all user devices, only on debug builds.
            log("Unable to set property " + key + " value '" + value + "'", e);
        }
    }

    private static void log(String msg, Exception e) {
        if (Build.IS_DEBUGGABLE) {
            Slog.wtf(TAG, msg, e);
        } else {
            Slog.e(TAG, msg, e);
        }
    }

    private static void log(String msg) {
        if (Build.IS_DEBUGGABLE) {
            Slog.wtf(TAG, msg);
        } else {
            Slog.e(TAG, msg);
        }
    }

    @VisibleForTesting
    static String getResetFlagsFileContent() {
        String content = null;
        try {
            File reset_flag_file = new File(RESET_RECORD_FILE_PATH);
            BufferedReader br = new BufferedReader(new FileReader(reset_flag_file));
            content = br.readLine();

            br.close();
        } catch (IOException ioe) {
            log("failed to read file " + RESET_RECORD_FILE_PATH, ioe);
        }
        return content;
    }

    @VisibleForTesting
    void updatePropertyFromSetting(String settingName, String propName) {
        String settingValue = Settings.Global.getString(mContentResolver, settingName);
        setProperty(propName, settingValue);
    }
}
