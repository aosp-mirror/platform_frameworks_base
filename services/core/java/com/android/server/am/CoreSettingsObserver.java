/*
 * Copyright (C) 2006-2011 The Android Open Source Project
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
import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.widget.WidgetFlags;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Helper class for watching a set of core settings which the framework
 * propagates to application processes to avoid multiple lookups and potentially
 * disk I/O operations. Note: This class assumes that all core settings reside
 * in {@link Settings.Secure}.
 */
final class CoreSettingsObserver extends ContentObserver {
    private static final String LOG_TAG = CoreSettingsObserver.class.getSimpleName();

    private static class DeviceConfigEntry<T> {
        String namespace;
        String flag;
        String coreSettingKey;
        Class<T> type;
        T defaultValue;

        DeviceConfigEntry(String namespace, String flag, String coreSettingKey, Class<T> type,
                @NonNull T defaultValue) {
            this.namespace = namespace;
            this.flag = flag;
            this.coreSettingKey = coreSettingKey;
            this.type = type;
            this.defaultValue = Objects.requireNonNull(defaultValue);
        }
    }

    // mapping form property name to its type
    @VisibleForTesting
    static final Map<String, Class<?>> sSecureSettingToTypeMap = new HashMap<
            String, Class<?>>();
    @VisibleForTesting
    static final Map<String, Class<?>> sSystemSettingToTypeMap = new HashMap<
            String, Class<?>>();
    @VisibleForTesting
    static final Map<String, Class<?>> sGlobalSettingToTypeMap = new HashMap<
            String, Class<?>>();
    static final List<DeviceConfigEntry> sDeviceConfigEntries = new ArrayList<DeviceConfigEntry>();
    static {
        sSecureSettingToTypeMap.put(Settings.Secure.LONG_PRESS_TIMEOUT, int.class);
        sSecureSettingToTypeMap.put(Settings.Secure.MULTI_PRESS_TIMEOUT, int.class);
        // add other secure settings here...

        sSystemSettingToTypeMap.put(Settings.System.TIME_12_24, String.class);
        // add other system settings here...

        sGlobalSettingToTypeMap.put(Settings.Global.DEBUG_VIEW_ATTRIBUTES, int.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.DEBUG_VIEW_ATTRIBUTES_APPLICATION_PACKAGE, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_DEBUG_PACKAGE, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_GL_DRIVER_ALL_ANGLE, int.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_GL_DRIVER_SELECTION_PKGS, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_GL_DRIVER_SELECTION_VALUES, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_ALLOWLIST, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.ANGLE_EGL_FEATURES, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.SHOW_ANGLE_IN_USE_DIALOG_BOX, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.ENABLE_GPU_DEBUG_LAYERS, int.class);
        sGlobalSettingToTypeMap.put(Settings.Global.GPU_DEBUG_APP, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.GPU_DEBUG_LAYERS, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.GPU_DEBUG_LAYERS_GLES, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.GPU_DEBUG_LAYER_APP, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.UPDATABLE_DRIVER_ALL_APPS, int.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_OPT_IN_APPS, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRERELEASE_OPT_IN_APPS, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_OPT_OUT_APPS, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_DENYLIST, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_ALLOWLIST, String.class);
        sGlobalSettingToTypeMap.put(
                Settings.Global.UPDATABLE_DRIVER_PRODUCTION_DENYLISTS, String.class);
        sGlobalSettingToTypeMap.put(Settings.Global.UPDATABLE_DRIVER_SPHAL_LIBRARIES, String.class);
        // add other global settings here...

        sDeviceConfigEntries.add(new DeviceConfigEntry<Boolean>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.ENABLE_CURSOR_DRAG_FROM_ANYWHERE,
                WidgetFlags.KEY_ENABLE_CURSOR_DRAG_FROM_ANYWHERE, boolean.class,
                WidgetFlags.ENABLE_CURSOR_DRAG_FROM_ANYWHERE_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Integer>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL,
                WidgetFlags.KEY_CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL, int.class,
                WidgetFlags.CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Integer>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.FINGER_TO_CURSOR_DISTANCE,
                WidgetFlags.KEY_FINGER_TO_CURSOR_DISTANCE, int.class,
                WidgetFlags.FINGER_TO_CURSOR_DISTANCE_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Boolean>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.ENABLE_INSERTION_HANDLE_GESTURES,
                WidgetFlags.KEY_ENABLE_INSERTION_HANDLE_GESTURES, boolean.class,
                WidgetFlags.ENABLE_INSERTION_HANDLE_GESTURES_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Integer>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.INSERTION_HANDLE_DELTA_HEIGHT,
                WidgetFlags.KEY_INSERTION_HANDLE_DELTA_HEIGHT, int.class,
                WidgetFlags.INSERTION_HANDLE_DELTA_HEIGHT_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Integer>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.INSERTION_HANDLE_OPACITY,
                WidgetFlags.KEY_INSERTION_HANDLE_OPACITY, int.class,
                WidgetFlags.INSERTION_HANDLE_OPACITY_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Float>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.LINE_SLOP_RATIO,
                WidgetFlags.KEY_LINE_SLOP_RATIO, float.class,
                WidgetFlags.LINE_SLOP_RATIO_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Boolean>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.ENABLE_NEW_MAGNIFIER,
                WidgetFlags.KEY_ENABLE_NEW_MAGNIFIER, boolean.class,
                WidgetFlags.ENABLE_NEW_MAGNIFIER_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Float>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.MAGNIFIER_ZOOM_FACTOR,
                WidgetFlags.KEY_MAGNIFIER_ZOOM_FACTOR, float.class,
                WidgetFlags.MAGNIFIER_ZOOM_FACTOR_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<Float>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.MAGNIFIER_ASPECT_RATIO,
                WidgetFlags.KEY_MAGNIFIER_ASPECT_RATIO, float.class,
                WidgetFlags.MAGNIFIER_ASPECT_RATIO_DEFAULT));
        sDeviceConfigEntries.add(new DeviceConfigEntry<>(
                DeviceConfig.NAMESPACE_WIDGET, WidgetFlags.ANALOG_CLOCK_SECONDS_HAND_FPS,
                WidgetFlags.KEY_ANALOG_CLOCK_SECONDS_HAND_FPS, int.class,
                WidgetFlags.ANALOG_CLOCK_SECONDS_HAND_FPS_DEFAULT));
        // add other device configs here...
    }

    private final Bundle mCoreSettings = new Bundle();

    private final ActivityManagerService mActivityManagerService;

    public CoreSettingsObserver(ActivityManagerService activityManagerService) {
        super(activityManagerService.mHandler);
        mActivityManagerService = activityManagerService;
        beginObserveCoreSettings();
        sendCoreSettings();
    }

    public Bundle getCoreSettingsLocked() {
        return (Bundle) mCoreSettings.clone();
    }

    @Override
    public void onChange(boolean selfChange) {
        synchronized (mActivityManagerService) {
            sendCoreSettings();
        }
    }

    private void sendCoreSettings() {
        populateSettings(mCoreSettings, sSecureSettingToTypeMap);
        populateSettings(mCoreSettings, sSystemSettingToTypeMap);
        populateSettings(mCoreSettings, sGlobalSettingToTypeMap);
        populateSettingsFromDeviceConfig();
        mActivityManagerService.onCoreSettingsChange(mCoreSettings);
    }

    private void beginObserveCoreSettings() {
        for (String setting : sSecureSettingToTypeMap.keySet()) {
            Uri uri = Settings.Secure.getUriFor(setting);
            mActivityManagerService.mContext.getContentResolver().registerContentObserver(
                    uri, false, this);
        }

        for (String setting : sSystemSettingToTypeMap.keySet()) {
            Uri uri = Settings.System.getUriFor(setting);
            mActivityManagerService.mContext.getContentResolver().registerContentObserver(
                    uri, false, this);
        }

        for (String setting : sGlobalSettingToTypeMap.keySet()) {
            Uri uri = Settings.Global.getUriFor(setting);
            mActivityManagerService.mContext.getContentResolver().registerContentObserver(
                    uri, false, this);
        }

        HashSet<String> deviceConfigNamespaces = new HashSet<>();
        for (DeviceConfigEntry entry : sDeviceConfigEntries) {
            if (!deviceConfigNamespaces.contains(entry.namespace)) {
                DeviceConfig.addOnPropertiesChangedListener(
                        entry.namespace, ActivityThread.currentApplication().getMainExecutor(),
                        (DeviceConfig.Properties prop) -> onChange(false));
                deviceConfigNamespaces.add(entry.namespace);
            }
        }
    }

    @VisibleForTesting
    void populateSettings(Bundle snapshot, Map<String, Class<?>> map) {
        final Context context = mActivityManagerService.mContext;
        final ContentResolver cr = context.getContentResolver();
        for (Map.Entry<String, Class<?>> entry : map.entrySet()) {
            String setting = entry.getKey();
            final String value;
            if (map == sSecureSettingToTypeMap) {
                value = Settings.Secure.getStringForUser(cr, setting, cr.getUserId());
            } else if (map == sSystemSettingToTypeMap) {
                value = Settings.System.getStringForUser(cr, setting, cr.getUserId());
            } else {
                value = Settings.Global.getString(cr, setting);
            }
            if (value == null) {
                snapshot.remove(setting);
                continue;
            }
            Class<?> type = entry.getValue();
            if (type == String.class) {
                snapshot.putString(setting, value);
            } else if (type == int.class) {
                snapshot.putInt(setting, Integer.parseInt(value));
            } else if (type == float.class) {
                snapshot.putFloat(setting, Float.parseFloat(value));
            } else if (type == long.class) {
                snapshot.putLong(setting, Long.parseLong(value));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void populateSettingsFromDeviceConfig() {
        for (DeviceConfigEntry<?> entry : sDeviceConfigEntries) {
            if (entry.type == String.class) {
                String defaultValue = ((DeviceConfigEntry<String>) entry).defaultValue;
                mCoreSettings.putString(entry.coreSettingKey,
                        DeviceConfig.getString(entry.namespace, entry.flag, defaultValue));
            } else if (entry.type == int.class) {
                int defaultValue = ((DeviceConfigEntry<Integer>) entry).defaultValue;
                mCoreSettings.putInt(entry.coreSettingKey,
                        DeviceConfig.getInt(entry.namespace, entry.flag, defaultValue));
            } else if (entry.type == float.class) {
                float defaultValue = ((DeviceConfigEntry<Float>) entry).defaultValue;
                mCoreSettings.putFloat(entry.coreSettingKey,
                        DeviceConfig.getFloat(entry.namespace, entry.flag, defaultValue));
            } else if (entry.type == long.class) {
                long defaultValue = ((DeviceConfigEntry<Long>) entry).defaultValue;
                mCoreSettings.putLong(entry.coreSettingKey,
                        DeviceConfig.getLong(entry.namespace, entry.flag, defaultValue));
            } else if (entry.type == boolean.class) {
                boolean defaultValue = ((DeviceConfigEntry<Boolean>) entry).defaultValue;
                mCoreSettings.putInt(entry.coreSettingKey,
                        DeviceConfig.getBoolean(entry.namespace, entry.flag, defaultValue) ? 1 : 0);
            }
        }
    }
}
