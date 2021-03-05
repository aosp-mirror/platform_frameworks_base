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

package android.provider;

import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.Manifest.permission.WRITE_DEVICE_CONFIG;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings.ResetMode;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Device level configuration parameters which can be tuned by a separate configuration service.
 * Namespaces that end in "_native" such as {@link #NAMESPACE_NETD_NATIVE} are intended to be used
 * by native code and should be pushed to system properties to make them accessible.
 *
 * @hide
 */
@SystemApi
public final class DeviceConfig {
    /**
     * The content:// style URL for the config table.
     *
     * @hide
     */
    public static final Uri CONTENT_URI = Uri.parse("content://" + Settings.AUTHORITY + "/config");

    /**
     * Namespace for activity manager related features. These features will be applied
     * immediately upon change.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_ACTIVITY_MANAGER = "activity_manager";

    /**
     * Namespace for all activity manager related features that are used at the native level.
     * These features are applied at reboot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT =
            "activity_manager_native_boot";

    /**
     * Namespace for AlarmManager configurations.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public static final String NAMESPACE_ALARM_MANAGER = "alarm_manager";

    /**
     * Namespace for all app compat related features.  These features will be applied
     * immediately upon change.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_APP_COMPAT = "app_compat";

    /**
     * Namespace for all app hibernation related features.
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_APP_HIBERNATION = "app_hibernation";

    /**
     * Namespace for app standby configurations.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final String NAMESPACE_APP_STANDBY = "app_standby";

    /**
     * Namespace for AttentionManagerService related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_ATTENTION_MANAGER_SERVICE = "attention_manager_service";

    /**
     * Namespace for autofill feature that provides suggestions across all apps when
     * the user interacts with input fields.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_AUTOFILL = "autofill";

    /**
     * Namespace for battery saver feature.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_BATTERY_SAVER = "battery_saver";

    /**
     * Namespace for blobstore feature that allows apps to share data blobs.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_BLOBSTORE = "blobstore";

    /**
     * Namespace for all Bluetooth related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_BLUETOOTH = "bluetooth";

    /**
     * Namespace for features relating to clipboard.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_CLIPBOARD = "clipboard";

    /**
     * Namespace for all networking connectivity related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_CONNECTIVITY = "connectivity";

    /**
     * Namespace for content capture feature used by on-device machine intelligence
     * to provide suggestions in a privacy-safe manner.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_CONTENT_CAPTURE = "content_capture";

    /**
     * Namespace for device idle configurations.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public static final String NAMESPACE_DEVICE_IDLE = "device_idle";

    /**
     * Namespace for how dex runs. The feature requires a reboot to reach a clean state.
     *
     * @deprecated No longer used
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final String NAMESPACE_DEX_BOOT = "dex_boot";

    /**
     * Namespace for display manager related features. The names to access the properties in this
     * namespace should be defined in {@link android.hardware.display.DisplayManager}.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_DISPLAY_MANAGER = "display_manager";

    /**
     * Namespace for all Game Driver features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_GAME_DRIVER = "game_driver";

    /**
     * Namespace for all input-related features that are used at the native level.
     * These features are applied at reboot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_INPUT_NATIVE_BOOT = "input_native_boot";

    /**
     * Namespace for attention-based features provided by on-device machine intelligence.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_INTELLIGENCE_ATTENTION = "intelligence_attention";

    /**
     * Definitions for properties related to Content Suggestions.
     *
     * @hide
     */
    public static final String NAMESPACE_INTELLIGENCE_CONTENT_SUGGESTIONS =
            "intelligence_content_suggestions";

    /**
     * Namespace for JobScheduler configurations.
     * @hide
     */
    @TestApi
    public static final String NAMESPACE_JOB_SCHEDULER = "jobscheduler";

    /**
     * Namespace for all media native related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_MEDIA_NATIVE = "media_native";

    /**
     * Namespace for all netd related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_NETD_NATIVE = "netd_native";

    /**
     * Namespace for features related to the Package Manager Service.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_PACKAGE_MANAGER_SERVICE = "package_manager_service";

    /**
     * Namespace for features related to the Profcollect native Service.
     * These features are applied at reboot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_PROFCOLLECT_NATIVE_BOOT = "profcollect_native_boot";

    /**
     * Namespace for features related to Reboot Readiness detection.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_REBOOT_READINESS = "reboot_readiness";

    /**
     * Namespace for Rollback flags that are applied immediately.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_ROLLBACK = "rollback";

    /**
     * Namespace for Rollback flags that are applied after a reboot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_ROLLBACK_BOOT = "rollback_boot";

    /**
     * Namespace for Rotation Resolver Manager Service.
     *
     * @hide
     */
    public static final String NAMESPACE_ROTATION_RESOLVER = "rotation_resolver";

    /**
     * Namespace for all runtime related features that don't require a reboot to become active.
     * There are no feature flags using NAMESPACE_RUNTIME.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_RUNTIME = "runtime";

    /**
     * Namespace for all runtime related features that require system properties for accessing
     * the feature flags from C++ or Java language code. One example is the app image startup
     * cache feature use_app_image_startup_cache.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_RUNTIME_NATIVE = "runtime_native";

    /**
     * Namespace for all runtime native boot related features. Boot in this case refers to the
     * fact that the properties only take affect after rebooting the device.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_RUNTIME_NATIVE_BOOT = "runtime_native_boot";

    /**
     * Namespace for system scheduler related features. These features will be applied
     * immediately upon change.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_SCHEDULER = "scheduler";

    /**
     * Namespace for settings statistics features.
     *
     * @hide
     */
    public static final String NAMESPACE_SETTINGS_STATS = "settings_stats";

    /**
     * Namespace for storage-related features.
     *
     * @deprecated Replace storage namespace with storage_native_boot.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final String NAMESPACE_STORAGE = "storage";

    /**
     * Namespace for storage-related features, including native and boot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_STORAGE_NATIVE_BOOT = "storage_native_boot";

    /**
     * Namespace for swcodec native related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_SWCODEC_NATIVE = "swcodec_native";


    /**
     * Namespace for System UI related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_SYSTEMUI = "systemui";

    /**
     * Namespace for system time and time zone detection related features / behavior.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_SYSTEM_TIME = "system_time";

    /**
     * Telephony related properties.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_TELEPHONY = "telephony";

    /**
     * Namespace for TextClassifier related features.
     *
     * @hide
     * @see android.provider.Settings.Global.TEXT_CLASSIFIER_CONSTANTS
     */
    @SystemApi
    public static final String NAMESPACE_TEXTCLASSIFIER = "textclassifier";

    /**
     * Namespace for contacts provider related features.
     *
     * @hide
     */
    public static final String NAMESPACE_CONTACTS_PROVIDER = "contacts_provider";

    /**
     * Namespace for settings ui related features
     *
     * @hide
     */
    public static final String NAMESPACE_SETTINGS_UI = "settings_ui";

    /**
     * Namespace for android related features, i.e. for flags that affect not just a single
     * component, but the entire system.
     *
     * The keys for this namespace are defined in {@link AndroidDeviceConfig}.
     *
     * @hide
     */
    @TestApi
    public static final String NAMESPACE_ANDROID = "android";

    /**
     * Namespace for window manager related features.
     *
     * @hide
     */
    public static final String NAMESPACE_WINDOW_MANAGER = "window_manager";

    /**
     * Namespace for window manager features accessible by native code and
     * loaded once per boot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_WINDOW_MANAGER_NATIVE_BOOT = "window_manager_native_boot";

    /**
     * List of namespaces which can be read without READ_DEVICE_CONFIG permission
     *
     * @hide
     */
    @NonNull
    private static final List<String> PUBLIC_NAMESPACES =
            Arrays.asList(NAMESPACE_TEXTCLASSIFIER, NAMESPACE_RUNTIME);
    /**
     * Privacy related properties definitions.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_PRIVACY = "privacy";

    /**
     * Namespace for biometrics related features
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_BIOMETRICS = "biometrics";

    /**
     * Permission related properties definitions.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_PERMISSIONS = "permissions";

    /**
     * Namespace for ota related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_OTA = "ota";

    /**
     * Namespace for all widget related features.
     *
     * @hide
     */
    public static final String NAMESPACE_WIDGET = "widget";

    /**
     * Namespace for connectivity thermal power manager features.
     *
     * @hide
     */
    public static final String NAMESPACE_CONNECTIVITY_THERMAL_POWER_MANAGER =
            "connectivity_thermal_power_manager";

    /**
     * Namespace for all statsd java features that can be applied immediately.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_STATSD_JAVA = "statsd_java";

    /**
     * Namespace for all statsd java features that are applied on boot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_STATSD_JAVA_BOOT = "statsd_java_boot";

    /**
     * Namespace for all statsd native features that can be applied immediately.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_STATSD_NATIVE = "statsd_native";

    /**
     * Namespace for all statsd native features that are applied on boot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_STATSD_NATIVE_BOOT = "statsd_native_boot";

    /**
     * Namespace for configuration related features.
     *
     * @hide
     */
    public static final String NAMESPACE_CONFIGURATION = "configuration";

    /**
     * LatencyTracker properties definitions.
     *
     * @hide
     */
    public static final String NAMESPACE_LATENCY_TRACKER = "latency_tracker";

    /**
     * InteractionJankMonitor properties definitions.
     *
     * @hide
     */
    public static final String NAMESPACE_INTERACTION_JANK_MONITOR = "interaction_jank_monitor";

    /**
     * Namespace for game overlay related features.
     *
     * @hide
     */
    public static final String NAMESPACE_GAME_OVERLAY = "game_overlay";

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static ArrayMap<OnPropertiesChangedListener, Pair<String, Executor>> sListeners =
            new ArrayMap<>();
    @GuardedBy("sLock")
    private static Map<String, Pair<ContentObserver, Integer>> sNamespaces = new HashMap<>();
    private static final String TAG = "DeviceConfig";

    // Should never be invoked
    private DeviceConfig() {
    }

    /**
     * Look up the value of a property for a particular namespace.
     *
     * @param namespace The namespace containing the property to look up.
     * @param name      The name of the property to look up.
     * @return the corresponding value, or null if not present.
     * @hide
     */
    @SystemApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static String getProperty(@NonNull String namespace, @NonNull String name) {
        // Fetch all properties for the namespace at once and cache them in the local process, so we
        // incur the cost of the IPC less often. Lookups happen much more frequently than updates,
        // and we want to optimize the former.
        return getProperties(namespace, name).getString(name, null);
    }

    /**
     * Look up the values of multiple properties for a particular namespace. The lookup is atomic,
     * such that the values of these properties cannot change between the time when the first is
     * fetched and the time when the last is fetched.
     * <p>
     * Each call to {@link #setProperties(Properties)} is also atomic and ensures that either none
     * or all of the change is picked up here, but never only part of it.
     *
     * @param namespace The namespace containing the properties to look up.
     * @param names     The names of properties to look up, or empty to fetch all properties for the
     *                  given namespace.
     * @return {@link Properties} object containing the requested properties. This reflects the
     *     state of these properties at the time of the lookup, and is not updated to reflect any
     *     future changes. The keyset of this Properties object will contain only the intersection
     *     of properties already set and properties requested via the names parameter. Properties
     *     that are already set but were not requested will not be contained here. Properties that
     *     are not set, but were requested will not be contained here either.
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static Properties getProperties(@NonNull String namespace, @NonNull String ... names) {
        ContentResolver contentResolver = ActivityThread.currentApplication().getContentResolver();
        return new Properties(namespace,
                Settings.Config.getStrings(contentResolver, namespace, Arrays.asList(names)));
    }

    /**
     * Look up the String value of a property for a particular namespace.
     *
     * @param namespace    The namespace containing the property to look up.
     * @param name         The name of the property to look up.
     * @param defaultValue The value to return if the property does not exist or has no non-null
     *                     value.
     * @return the corresponding value, or defaultValue if none exists.
     * @hide
     */
    @SystemApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static String getString(@NonNull String namespace, @NonNull String name,
            @Nullable String defaultValue) {
        String value = getProperty(namespace, name);
        return value != null ? value : defaultValue;
    }

    /**
     * Look up the boolean value of a property for a particular namespace.
     *
     * @param namespace The namespace containing the property to look up.
     * @param name      The name of the property to look up.
     * @param defaultValue The value to return if the property does not exist or has no non-null
     *                     value.
     * @return the corresponding value, or defaultValue if none exists.
     * @hide
     */
    @SystemApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static boolean getBoolean(@NonNull String namespace, @NonNull String name,
            boolean defaultValue) {
        String value = getProperty(namespace, name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    /**
     * Look up the int value of a property for a particular namespace.
     *
     * @param namespace The namespace containing the property to look up.
     * @param name      The name of the property to look up.
     * @param defaultValue The value to return if the property does not exist, has no non-null
     *                     value, or fails to parse into an int.
     * @return the corresponding value, or defaultValue if either none exists or it does not parse.
     * @hide
     */
    @SystemApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static int getInt(@NonNull String namespace, @NonNull String name, int defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Parsing integer failed for " + namespace + ":" + name);
            return defaultValue;
        }
    }

    /**
     * Look up the long value of a property for a particular namespace.
     *
     * @param namespace The namespace containing the property to look up.
     * @param name      The name of the property to look up.
     * @param defaultValue The value to return if the property does not exist, has no non-null
     *                     value, or fails to parse into a long.
     * @return the corresponding value, or defaultValue if either none exists or it does not parse.
     * @hide
     */
    @SystemApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static long getLong(@NonNull String namespace, @NonNull String name, long defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Parsing long failed for " + namespace + ":" + name);
            return defaultValue;
        }
    }

    /**
     * Look up the float value of a property for a particular namespace.
     *
     * @param namespace The namespace containing the property to look up.
     * @param name      The name of the property to look up.
     * @param defaultValue The value to return if the property does not exist, has no non-null
     *                     value, or fails to parse into a float.
     * @return the corresponding value, or defaultValue if either none exists or it does not parse.
     * @hide
     */
    @SystemApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static float getFloat(@NonNull String namespace, @NonNull String name,
            float defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Parsing float failed for " + namespace + ":" + name);
            return defaultValue;
        }
    }

    /**
     * Create a new property with the the provided name and value in the provided namespace, or
     * update the value of such a property if it already exists. The same name can exist in multiple
     * namespaces and might have different values in any or all namespaces.
     * <p>
     * The method takes an argument indicating whether to make the value the default for this
     * property.
     * <p>
     * All properties stored for a particular scope can be reverted to their default values
     * by passing the namespace to {@link #resetToDefaults(int, String)}.
     *
     * @param namespace   The namespace containing the property to create or update.
     * @param name        The name of the property to create or update.
     * @param value       The value to store for the property.
     * @param makeDefault Whether to make the new value the default one.
     * @return True if the value was set, false if the storage implementation throws errors.
     * @hide
     * @see #resetToDefaults(int, String).
     */
    @SystemApi
    @RequiresPermission(WRITE_DEVICE_CONFIG)
    public static boolean setProperty(@NonNull String namespace, @NonNull String name,
            @Nullable String value, boolean makeDefault) {
        ContentResolver contentResolver = ActivityThread.currentApplication().getContentResolver();
        return Settings.Config.putString(contentResolver, namespace, name, value, makeDefault);
    }

    /**
     * Set all of the properties for a specific namespace. Pre-existing properties will be updated
     * and new properties will be added if necessary. Any pre-existing properties for the specific
     * namespace which are not part of the provided {@link Properties} object will be deleted from
     * the namespace. These changes are all applied atomically, such that no calls to read or reset
     * these properties can happen in the middle of this update.
     * <p>
     * Each call to {@link #getProperties(String, String...)} is also atomic and ensures that either
     * none or all of this update is picked up, but never only part of it.
     *
     * @param properties the complete set of properties to set for a specific namespace.
     * @throws BadConfigException if the provided properties are banned by RescueParty.
     * @return True if the values were set, false otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(WRITE_DEVICE_CONFIG)
    public static boolean setProperties(@NonNull Properties properties) throws BadConfigException {
        ContentResolver contentResolver = ActivityThread.currentApplication().getContentResolver();
        return Settings.Config.setStrings(contentResolver, properties.getNamespace(),
                properties.mMap);
    }

    /**
     * Reset properties to their default values.
     * <p>
     * The method accepts an optional namespace parameter. If provided, only properties set within
     * that namespace will be reset. Otherwise, all properties will be reset.
     *
     * @param resetMode The reset mode to use.
     * @param namespace Optionally, the specific namespace which resets will be limited to.
     * @hide
     * @see #setProperty(String, String, String, boolean)
     */
    @SystemApi
    @RequiresPermission(WRITE_DEVICE_CONFIG)
    public static void resetToDefaults(@ResetMode int resetMode, @Nullable String namespace) {
        ContentResolver contentResolver = ActivityThread.currentApplication().getContentResolver();
        Settings.Config.resetToDefaults(contentResolver, resetMode, namespace);
    }

    /**
     * Add a listener for property changes.
     * <p>
     * This listener will be called whenever properties in the specified namespace change. Callbacks
     * will be made on the specified executor. Future calls to this method with the same listener
     * will replace the old namespace and executor. Remove the listener entirely by calling
     * {@link #removeOnPropertiesChangedListener(OnPropertiesChangedListener)}.
     *
     * @param namespace                   The namespace containing properties to monitor.
     * @param executor                    The executor which will be used to run callbacks.
     * @param onPropertiesChangedListener The listener to add.
     * @hide
     * @see #removeOnPropertiesChangedListener(OnPropertiesChangedListener)
     */
    @SystemApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static void addOnPropertiesChangedListener(
            @NonNull String namespace,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPropertiesChangedListener onPropertiesChangedListener) {
        enforceReadPermission(ActivityThread.currentApplication().getApplicationContext(),
                namespace);
        synchronized (sLock) {
            Pair<String, Executor> oldNamespace = sListeners.get(onPropertiesChangedListener);
            if (oldNamespace == null) {
                // Brand new listener, add it to the list.
                sListeners.put(onPropertiesChangedListener, new Pair<>(namespace, executor));
                incrementNamespace(namespace);
            } else if (namespace.equals(oldNamespace.first)) {
                // Listener is already registered for this namespace, update executor just in case.
                sListeners.put(onPropertiesChangedListener, new Pair<>(namespace, executor));
            } else {
                // Update this listener from an old namespace to the new one.
                decrementNamespace(sListeners.get(onPropertiesChangedListener).first);
                sListeners.put(onPropertiesChangedListener, new Pair<>(namespace, executor));
                incrementNamespace(namespace);
            }
        }
    }

    /**
     * Remove a listener for property changes. The listener will receive no further notification of
     * property changes.
     *
     * @param onPropertiesChangedListener The listener to remove.
     * @hide
     * @see #addOnPropertiesChangedListener(String, Executor, OnPropertiesChangedListener)
     */
    @SystemApi
    public static void removeOnPropertiesChangedListener(
            @NonNull OnPropertiesChangedListener onPropertiesChangedListener) {
        Preconditions.checkNotNull(onPropertiesChangedListener);
        synchronized (sLock) {
            if (sListeners.containsKey(onPropertiesChangedListener)) {
                decrementNamespace(sListeners.get(onPropertiesChangedListener).first);
                sListeners.remove(onPropertiesChangedListener);
            }
        }
    }

    private static Uri createNamespaceUri(@NonNull String namespace) {
        Preconditions.checkNotNull(namespace);
        return CONTENT_URI.buildUpon().appendPath(namespace).build();
    }

    /**
     * Increment the count used to represent the number of listeners subscribed to the given
     * namespace. If this is the first (i.e. incrementing from 0 to 1) for the given namespace, a
     * ContentObserver is registered.
     *
     * @param namespace The namespace to increment the count for.
     */
    @GuardedBy("sLock")
    private static void incrementNamespace(@NonNull String namespace) {
        Preconditions.checkNotNull(namespace);
        Pair<ContentObserver, Integer> namespaceCount = sNamespaces.get(namespace);
        if (namespaceCount != null) {
            sNamespaces.put(namespace, new Pair<>(namespaceCount.first, namespaceCount.second + 1));
        } else {
            // This is a new namespace, register a ContentObserver for it.
            ContentObserver contentObserver = new ContentObserver(null) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (uri != null) {
                        handleChange(uri);
                    }
                }
            };
            ActivityThread.currentApplication().getContentResolver()
                    .registerContentObserver(createNamespaceUri(namespace), true, contentObserver);
            sNamespaces.put(namespace, new Pair<>(contentObserver, 1));
        }
    }

    /**
     * Decrement the count used to represent the number of listeners subscribed to the given
     * namespace. If this is the final decrement call (i.e. decrementing from 1 to 0) for the given
     * namespace, the ContentObserver that had been tracking it will be removed.
     *
     * @param namespace The namespace to decrement the count for.
     */
    @GuardedBy("sLock")
    private static void decrementNamespace(@NonNull String namespace) {
        Preconditions.checkNotNull(namespace);
        Pair<ContentObserver, Integer> namespaceCount = sNamespaces.get(namespace);
        if (namespaceCount == null) {
            // This namespace is not registered and does not need to be decremented
            return;
        } else if (namespaceCount.second > 1) {
            sNamespaces.put(namespace, new Pair<>(namespaceCount.first, namespaceCount.second - 1));
        } else {
            // Decrementing a namespace to zero means we no longer need its ContentObserver.
            ActivityThread.currentApplication().getContentResolver()
                    .unregisterContentObserver(namespaceCount.first);
            sNamespaces.remove(namespace);
        }
    }

    private static void handleChange(@NonNull Uri uri) {
        Preconditions.checkNotNull(uri);
        List<String> pathSegments = uri.getPathSegments();
        // pathSegments(0) is "config"
        final String namespace = pathSegments.get(1);
        Properties.Builder propBuilder = new Properties.Builder(namespace);
        try {
            Properties allProperties = getProperties(namespace);
            for (int i = 2; i < pathSegments.size(); ++i) {
                String key = pathSegments.get(i);
                propBuilder.setString(key, allProperties.getString(key, null));
            }
        } catch (SecurityException e) {
            // Silently failing to not crash binder or listener threads.
            Log.e(TAG, "OnPropertyChangedListener update failed: permission violation.");
            return;
        }
        Properties properties = propBuilder.build();

        synchronized (sLock) {
            for (int i = 0; i < sListeners.size(); i++) {
                if (namespace.equals(sListeners.valueAt(i).first)) {
                    final OnPropertiesChangedListener listener = sListeners.keyAt(i);
                    sListeners.valueAt(i).second.execute(() -> {
                        listener.onPropertiesChanged(properties);
                    });
                }
            }
        }
    }

    /**
     * Enforces READ_DEVICE_CONFIG permission if namespace is not one of public namespaces.
     * @hide
     */
    public static void enforceReadPermission(Context context, String namespace) {
        if (context.checkCallingOrSelfPermission(READ_DEVICE_CONFIG)
                != PackageManager.PERMISSION_GRANTED) {
            if (!PUBLIC_NAMESPACES.contains(namespace)) {
                throw new SecurityException("Permission denial: reading from settings requires:"
                        + READ_DEVICE_CONFIG);
            }
        }
    }

    /**
     * Returns list of namespaces that can be read without READ_DEVICE_CONFIG_PERMISSION;
     * @hide
     */
    public static @NonNull List<String> getPublicNamespaces() {
        return PUBLIC_NAMESPACES;
    }

    /**
     * Interface for monitoring changes to properties. Implementations will receive callbacks when
     * properties change, including a {@link Properties} object which contains a single namespace
     * and all of the properties which changed for that namespace. This includes properties which
     * were added, updated, or deleted. This is not necessarily a complete list of all properties
     * belonging to the namespace, as properties which don't change are omitted.
     * <p>
     * Override {@link #onPropertiesChanged(Properties)} to handle callbacks for changes.
     *
     * @hide
     */
    @SystemApi
    public interface OnPropertiesChangedListener {
        /**
         * Called when one or more properties have changed, providing a Properties object with all
         * of the changed properties. This object will contain only properties which have changed,
         * not the complete set of all properties belonging to the namespace.
         *
         * @param properties Contains the complete collection of properties which have changed for a
         *                   single namespace. This includes only those which were added, updated,
         *                   or deleted.
         */
        void onPropertiesChanged(@NonNull Properties properties);
    }

    /**
     * Thrown by {@link #setProperties(Properties)} when a configuration is rejected. This
     * happens if RescueParty has identified a bad configuration and reset the namespace.
     *
     * @hide
     */
    @SystemApi
    public static class BadConfigException extends Exception {}

    /**
     * A mapping of properties to values, as well as a single namespace which they all belong to.
     *
     * @hide
     */
    @SystemApi
    public static class Properties {
        private final String mNamespace;
        private final HashMap<String, String> mMap;
        private Set<String> mKeyset;

        /**
         * Create a mapping of properties to values and the namespace they belong to.
         *
         * @param namespace The namespace these properties belong to.
         * @param keyValueMap A map between property names and property values.
         * @hide
         */
        public Properties(@NonNull String namespace, @Nullable Map<String, String> keyValueMap) {
            Preconditions.checkNotNull(namespace);
            mNamespace = namespace;
            mMap = new HashMap();
            if (keyValueMap != null) {
                mMap.putAll(keyValueMap);
            }
        }

        /**
         * @return the namespace all properties within this instance belong to.
         */
        @NonNull
        public String getNamespace() {
            return mNamespace;
        }

        /**
         * @return the non-null set of property names.
         */
        @NonNull
        public Set<String> getKeyset() {
            if (mKeyset == null) {
                mKeyset = Collections.unmodifiableSet(mMap.keySet());
            }
            return mKeyset;
        }

        /**
         * Look up the String value of a property.
         *
         * @param name         The name of the property to look up.
         * @param defaultValue The value to return if the property has not been defined.
         * @return the corresponding value, or defaultValue if none exists.
         */
        @Nullable
        public String getString(@NonNull String name, @Nullable String defaultValue) {
            Preconditions.checkNotNull(name);
            String value = mMap.get(name);
            return value != null ? value : defaultValue;
        }

        /**
         * Look up the boolean value of a property.
         *
         * @param name         The name of the property to look up.
         * @param defaultValue The value to return if the property has not been defined.
         * @return the corresponding value, or defaultValue if none exists.
         */
        public boolean getBoolean(@NonNull String name, boolean defaultValue) {
            Preconditions.checkNotNull(name);
            String value = mMap.get(name);
            return value != null ? Boolean.parseBoolean(value) : defaultValue;
        }

        /**
         * Look up the int value of a property.
         *
         * @param name         The name of the property to look up.
         * @param defaultValue The value to return if the property has not been defined or fails to
         *                     parse into an int.
         * @return the corresponding value, or defaultValue if no valid int is available.
         */
        public int getInt(@NonNull String name, int defaultValue) {
            Preconditions.checkNotNull(name);
            String value = mMap.get(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Parsing int failed for " + name);
                return defaultValue;
            }
        }

        /**
         * Look up the long value of a property.
         *
         * @param name         The name of the property to look up.
         * @param defaultValue The value to return if the property has not been defined. or fails to
         *                     parse into a long.
         * @return the corresponding value, or defaultValue if no valid long is available.
         */
        public long getLong(@NonNull String name, long defaultValue) {
            Preconditions.checkNotNull(name);
            String value = mMap.get(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Parsing long failed for " + name);
                return defaultValue;
            }
        }

        /**
         * Look up the int value of a property.
         *
         * @param name         The name of the property to look up.
         * @param defaultValue The value to return if the property has not been defined. or fails to
         *                     parse into a float.
         * @return the corresponding value, or defaultValue if no valid float is available.
         */
        public float getFloat(@NonNull String name, float defaultValue) {
            Preconditions.checkNotNull(name);
            String value = mMap.get(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Parsing float failed for " + name);
                return defaultValue;
            }
        }

        /**
         * Builder class for the construction of {@link Properties} objects.
         */
        public static final class Builder {
            @NonNull
            private final String mNamespace;
            @NonNull
            private final Map<String, String> mKeyValues = new HashMap<>();

            /**
             * Create a new Builders for the specified namespace.
             * @param namespace non null namespace.
             */
            public Builder(@NonNull String namespace) {
                mNamespace = namespace;
            }

            /**
             * Add a new property with the specified key and value.
             * @param name non null name of the property.
             * @param value nullable string value of the property.
             * @return this Builder object
             */
            @NonNull
            public Builder setString(@NonNull String name, @Nullable String value) {
                mKeyValues.put(name, value);
                return this;
            }

            /**
             * Add a new property with the specified key and value.
             * @param name non null name of the property.
             * @param value nullable string value of the property.
             * @return this Builder object
             */
            @NonNull
            public Builder setBoolean(@NonNull String name, boolean value) {
                mKeyValues.put(name, Boolean.toString(value));
                return this;
            }

            /**
             * Add a new property with the specified key and value.
             * @param name non null name of the property.
             * @param value int value of the property.
             * @return this Builder object
             */
            @NonNull
            public Builder setInt(@NonNull String name, int value) {
                mKeyValues.put(name, Integer.toString(value));
                return this;
            }

            /**
             * Add a new property with the specified key and value.
             * @param name non null name of the property.
             * @param value long value of the property.
             * @return this Builder object
             */
            @NonNull
            public Builder setLong(@NonNull String name, long value) {
                mKeyValues.put(name, Long.toString(value));
                return this;
            }

            /**
             * Add a new property with the specified key and value.
             * @param name non null name of the property.
             * @param value float value of the property.
             * @return this Builder object
             */
            @NonNull
            public Builder setFloat(@NonNull String name, float value) {
                mKeyValues.put(name, Float.toString(value));
                return this;
            }

            /**
             * Create a new {@link Properties} object.
             * @return non null Properties.
             */
            @NonNull
            public Properties build() {
                return new Properties(mNamespace, mKeyValues);
            }
        }
    }

}
