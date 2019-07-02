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
@TestApi
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
     * Namespace for all app compat related features.  These features will be applied
     * immediately upon change.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_APP_COMPAT = "app_compat";

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
    @TestApi
    public static final String NAMESPACE_AUTOFILL = "autofill";

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
    @TestApi
    public static final String NAMESPACE_CONTENT_CAPTURE = "content_capture";

    /**
     * Namespace for how dex runs. The feature requires a reboot to reach a clean state.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_DEX_BOOT = "dex_boot";

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
     * Namespace for Rollback flags that are applied immediately.
     *
     * @hide
     */
    @SystemApi @TestApi
    public static final String NAMESPACE_ROLLBACK = "rollback";

    /**
     * Namespace for Rollback flags that are applied after a reboot.
     *
     * @hide
     */
    @SystemApi @TestApi
    public static final String NAMESPACE_ROLLBACK_BOOT = "rollback_boot";

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
     * Namespace for storage-related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_STORAGE = "storage";

    /**
     * Namespace for System UI related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_SYSTEMUI = "systemui";

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
     * Namespace for window manager related features. The names to access the properties in this
     * namespace should be defined in {@link WindowManager}.
     *
     * @hide
     */
    @TestApi
    public static final String NAMESPACE_WINDOW_MANAGER = "android:window_manager";

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
    @TestApi
    public static final String NAMESPACE_PRIVACY = "privacy";

    /**
     * Interface for accessing keys belonging to {@link #NAMESPACE_WINDOW_MANAGER}.
     * @hide
     */
    @TestApi
    public interface WindowManager {

        /**
         * Key for accessing the system gesture exclusion limit (an integer in dp).
         *
         * @see android.provider.DeviceConfig#NAMESPACE_WINDOW_MANAGER
         * @hide
         */
        @TestApi
        String KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP = "system_gesture_exclusion_limit_dp";

        /**
         * Key for controlling whether system gestures are implicitly excluded by windows requesting
         * sticky immersive mode from apps that are targeting an SDK prior to Q.
         *
         * @see android.provider.DeviceConfig#NAMESPACE_WINDOW_MANAGER
         * @hide
         */
        @TestApi
        String KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE =
                "system_gestures_excluded_by_pre_q_sticky_immersive";
    }

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static ArrayMap<OnPropertyChangedListener, Pair<String, Executor>> sSingleListeners =
            new ArrayMap<>();
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
    @TestApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static String getProperty(@NonNull String namespace, @NonNull String name) {
        ContentResolver contentResolver = ActivityThread.currentApplication().getContentResolver();
        String compositeName = createCompositeName(namespace, name);
        return Settings.Config.getString(contentResolver, compositeName);
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
    @TestApi
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
    @TestApi
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
    @TestApi
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
    @TestApi
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
    @TestApi
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
    @TestApi
    @RequiresPermission(WRITE_DEVICE_CONFIG)
    public static boolean setProperty(@NonNull String namespace, @NonNull String name,
            @Nullable String value, boolean makeDefault) {
        String compositeName = createCompositeName(namespace, name);
        ContentResolver contentResolver = ActivityThread.currentApplication().getContentResolver();
        return Settings.Config.putString(contentResolver, compositeName, value, makeDefault);
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
    @TestApi
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
     * {@link #removeOnPropertyChangedListener(OnPropertyChangedListener)}.
     *
     * @param namespace                 The namespace containing properties to monitor.
     * @param executor                  The executor which will be used to run callbacks.
     * @param onPropertyChangedListener The listener to add.
     * @hide
     * @see #removeOnPropertyChangedListener(OnPropertyChangedListener)
     * @removed
     */
    @SystemApi
    @TestApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static void addOnPropertyChangedListener(
            @NonNull String namespace,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPropertyChangedListener onPropertyChangedListener) {
        enforceReadPermission(ActivityThread.currentApplication().getApplicationContext(),
                namespace);
        synchronized (sLock) {
            Pair<String, Executor> oldNamespace = sSingleListeners.get(onPropertyChangedListener);
            if (oldNamespace == null) {
                // Brand new listener, add it to the list.
                sSingleListeners.put(onPropertyChangedListener, new Pair<>(namespace, executor));
                incrementNamespace(namespace);
            } else if (namespace.equals(oldNamespace.first)) {
                // Listener is already registered for this namespace, update executor just in case.
                sSingleListeners.put(onPropertyChangedListener, new Pair<>(namespace, executor));
            } else {
                // Update this listener from an old namespace to the new one.
                decrementNamespace(sSingleListeners.get(onPropertyChangedListener).first);
                sSingleListeners.put(onPropertyChangedListener, new Pair<>(namespace, executor));
                incrementNamespace(namespace);
            }
        }
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
    @TestApi
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
     * @param onPropertyChangedListener The listener to remove.
     * @hide
     * @see #addOnPropertyChangedListener(String, Executor, OnPropertyChangedListener)
     * @removed
     */
    @SystemApi
    @TestApi
    public static void removeOnPropertyChangedListener(
            @NonNull OnPropertyChangedListener onPropertyChangedListener) {
        Preconditions.checkNotNull(onPropertyChangedListener);
        synchronized (sLock) {
            if (sSingleListeners.containsKey(onPropertyChangedListener)) {
                decrementNamespace(sSingleListeners.get(onPropertyChangedListener).first);
                sSingleListeners.remove(onPropertyChangedListener);
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
    @TestApi
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

    private static String createCompositeName(@NonNull String namespace, @NonNull String name) {
        Preconditions.checkNotNull(namespace);
        Preconditions.checkNotNull(name);
        return namespace + "/" + name;
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
        final String name = pathSegments.get(2);
        final String value;
        try {
            value = getProperty(namespace, name);
        } catch (SecurityException e) {
            // Silently failing to not crash binder or listener threads.
            Log.e(TAG, "OnPropertyChangedListener update failed: permission violation.");
            return;
        }
        synchronized (sLock) {
            // OnPropertiesChangedListeners
            for (int i = 0; i < sListeners.size(); i++) {
                if (namespace.equals(sListeners.valueAt(i).first)) {
                    final int j = i;
                    sListeners.valueAt(i).second.execute(new Runnable() {
                        @Override
                        public void run() {
                            Map<String, String> propertyMap = new HashMap(1);
                            propertyMap.put(name, value);
                            sListeners.keyAt(j)
                                    .onPropertiesChanged(new Properties(namespace, propertyMap));
                        }

                    });
                }
            }
            // OnPropertyChangedListeners
            for (int i = 0; i < sSingleListeners.size(); i++) {
                if (namespace.equals(sSingleListeners.valueAt(i).first)) {
                    final int j = i;
                    sSingleListeners.valueAt(i).second.execute(new Runnable() {
                        @Override
                        public void run() {
                            sSingleListeners.keyAt(j).onPropertyChanged(namespace, name, value);
                        }

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
     * Interface for monitoring single property changes.
     * <p>
     * Override {@link #onPropertyChanged(String, String, String)} to handle callbacks for changes.
     *
     * @hide
     * @removed
     */
    @SystemApi
    @TestApi
    public interface OnPropertyChangedListener {
        /**
         * Called when a property has changed.
         *
         * @param namespace The namespace containing the property which has changed.
         * @param name      The name of the property which has changed.
         * @param value     The new value of the property which has changed.
         */
        void onPropertyChanged(@NonNull String namespace, @NonNull String name,
                @Nullable String value);
    }

    /**
     * Interface for monitoring changes to properties.
     * <p>
     * Override {@link #onPropertiesChanged(Properties)} to handle callbacks for changes.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public interface OnPropertiesChangedListener {
        /**
         * Called when one or more properties have changed.
         *
         * @param properties Contains the complete collection of properties which have changed for a
         *                   single namespace.
         */
        void onPropertiesChanged(@NonNull Properties properties);
    }

    /**
     * A mapping of properties to values, as well as a single namespace which they all belong to.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static class Properties {
        private final String mNamespace;
        private final HashMap<String, String> mMap;

        /**
         * Create a mapping of properties to values and the namespace they belong to.
         *
         * @param namespace The namespace these properties belong to.
         * @param keyValueMap A map between property names and property values.
         */
        Properties(@NonNull String namespace, @Nullable Map<String, String> keyValueMap) {
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
            return mMap.keySet();
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
    }
}
