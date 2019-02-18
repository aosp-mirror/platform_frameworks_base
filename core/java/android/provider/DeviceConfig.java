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
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings.ResetMode;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Device level configuration parameters which can be tuned by a separate configuration service.
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
     * Namespace for all Game Driver features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_GAME_DRIVER = "game_driver";

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
     * Namespace for content capture feature used by on-device machine intelligence
     * to provide suggestions in a privacy-safe manner.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final String NAMESPACE_CONTENT_CAPTURE = "content_capture";

    /**
     * Namespace for all input-related features that are used at the native level.
     * These features are applied at reboot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_INPUT_NATIVE_BOOT = "input_native_boot";

    /**
     * Namespace for all netd related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_NETD_NATIVE = "netd_native";

    /**
     * Namespace for System UI related features.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_SYSTEMUI = "systemui";

    /**
     * Namespace for all runtime related features.
     *
     * @hide
     */
    @SystemApi
    public interface Runtime {
        String NAMESPACE = "runtime";

        /**
         * Whether or not we use the precompiled layout.
         */
        String USE_PRECOMPILED_LAYOUT = "view.precompiled_layout_enabled";
    }

    /**
     * Namespace for all runtime native related features.
     *
     * @hide
     */
    @SystemApi
    public interface RuntimeNative {
        String NAMESPACE = "runtime_native";

        /**
         * Zygote flags. See {@link com.internal.os.Zygote}.
         */

        /**
         * If {@code true}, enables the blastula pool feature.
         *
         * @hide for internal use only
         */
        String BLASTULA_POOL_ENABLED = "blastula_pool_enabled";

        /**
         * The maximum number of processes to keep in the blastula pool.
         *
         * @hide for internal use only
         */
        String BLASTULA_POOL_SIZE_MAX = "blastula_pool_size_max";

        /**
         * The minimum number of processes to keep in the blastula pool.
         *
         * @hide for internal use only
         */
        String BLASTULA_POOL_SIZE_MIN = "blastula_pool_size_min";

        /**
         * The threshold used to determine if the pool should be refilled.
         *
         * @hide for internal use only
         */
        String BLASTULA_POOL_REFILL_THRESHOLD = "blastula_refill_threshold";
    }

    /**
     * Namespace for all runtime native boot related features. Boot in this case refers to the
     * fact that the properties only take affect after rebooting the device.
     *
     * @hide
     */
    @SystemApi
    public interface RuntimeNativeBoot {
        String NAMESPACE = "runtime_native_boot";
    }

    /**
     * Namespace for all media native related features.
     *
     * @hide
     */
    @SystemApi
    public interface MediaNative {
        String NAMESPACE = "media_native";
    }

    /**
     * Namespace for all activity manager related features that are used at the native level.
     * These features are applied at reboot.
     *
     * @hide
     */
    @SystemApi
    public interface ActivityManagerNativeBoot {
        String NAMESPACE = "activity_manager_native_boot";
        String OFFLOAD_QUEUE_ENABLED = "offload_queue_enabled";
    }

    /**
     * Namespace for attention-based features provided by on-device machine intelligence.
     *
     * @hide
     */
    @SystemApi
    public interface IntelligenceAttention {
        String NAMESPACE = "intelligence_attention";

        /** If {@code true}, enables the attention features. */
        String ATTENTION_ENABLED = "attention_enabled";

        /** Settings for the attention features. */
        String ATTENTION_SETTINGS = "attention_settings";
    }

    /**
     * Privacy related properties definitions.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public interface Privacy {
        String NAMESPACE = "privacy";

        /**
         * Whether to show the Permissions Hub.
         *
         * @hide
         */
        @SystemApi
        String PROPERTY_PERMISSIONS_HUB_ENABLED = "permissions_hub_enabled";

        /**
         * Whether to show location access check notifications.
         */
        String PROPERTY_LOCATION_ACCESS_CHECK_ENABLED = "location_access_check_enabled";

        /**
         * Whether to disable the new device identifier access restrictions.
         *
         * @hide
         */
        String PROPERTY_DEVICE_IDENTIFIER_ACCESS_RESTRICTIONS_DISABLED =
                "device_identifier_access_restrictions_disabled";
    }

    /**
     * Telephony related properties definitions.
     *
     * @hide
     */
    @SystemApi
    public interface Telephony {
        String NAMESPACE = "telephony";
        /**
         * Ringer ramping time in milliseconds.
         */
        String RAMPING_RINGER_DURATION = "ramping_ringer_duration";
        /**
         * Whether to apply ramping ringer on incoming phone calls.
         */
        String RAMPING_RINGER_ENABLED = "ramping_ringer_enabled";
        /**
         * Vibration time in milliseconds before ramping ringer starts.
         */
        String RAMPING_RINGER_VIBRATION_DURATION = "ramping_ringer_vibration_duration";
    }

    /**
     * Namespace for how dex runs.  The feature may requires reboot to a clean state.
     *
     * @hide
     */
    @SystemApi
    public interface DexBoot {
        String NAMESPACE = "dex_boot";
        String PRIV_APPS_OOB_ENABLED = "priv_apps_oob_enabled";
        String PRIV_APPS_OOB_WHITELIST = "priv_apps_oob_whitelist";
    }

    /**
     * Namespace for activity manager related features. These features will be applied
     * immediately upon change.
     *
     * @hide
     */
    @SystemApi
    public interface ActivityManager {
        String NAMESPACE = "activity_manager";

        /**
         * App compaction flags. See {@link com.android.server.am.AppCompactor}.
         */
        String KEY_USE_COMPACTION = "use_compaction";
        String KEY_COMPACT_ACTION_1 = "compact_action_1";
        String KEY_COMPACT_ACTION_2 = "compact_action_2";
        String KEY_COMPACT_THROTTLE_1 = "compact_throttle_1";
        String KEY_COMPACT_THROTTLE_2 = "compact_throttle_2";
        String KEY_COMPACT_THROTTLE_3 = "compact_throttle_3";
        String KEY_COMPACT_THROTTLE_4 = "compact_throttle_4";
        String KEY_COMPACT_STATSD_SAMPLE_RATE = "compact_statsd_sample_rate";

        /**
         * Maximum number of cached processes. See
         * {@link com.android.server.am.ActivityManagerConstants}.
         */
        String KEY_MAX_CACHED_PROCESSES = "max_cached_processes";
    }

    /**
     * Namespace for {@link AttentionManagerService} related features.
     *
     * @hide
     */
    @SystemApi
    public interface AttentionManagerService {
        String NAMESPACE = "attention_manager_service";

        /** If {@code true}, enables {@link AttentionManagerService} features. */
        String SERVICE_ENABLED = "service_enabled";

        /** Allows a CTS to inject a fake implementation. */
        String COMPONENT_NAME = "component_name";
    }

    /**
     * Namespace for Rollback.
     *
     * @hide
     */
    @SystemApi
    public interface Rollback {
        String NAMESPACE = "rollback";

        String BOOT_NAMESPACE = "rollback_boot";

        /**
         * Timeout in milliseconds for enabling package rollback.
         */
        String ENABLE_ROLLBACK_TIMEOUT = "enable_rollback_timeout";

       /**
        * The lifetime duration of rollback packages in millis
        */
        String ROLLBACK_LIFETIME_IN_MILLIS = "rollback_lifetime_in_millis";
    }

    /**
     * Namespace for storage-related features.
     *
     * @hide
     */
    @SystemApi
    public interface Storage {
        String NAMESPACE = "storage";

        /**
         * If {@code 1}, enables the isolated storage feature. If {@code -1},
         * disables the isolated storage feature. If {@code 0}, uses the default
         * value from the build system.
         */
        String ISOLATED_STORAGE_ENABLED = "isolated_storage_enabled";
    }

    /**
     * Namespace for system scheduler related features. These features will be applied
     * immediately upon change.
     *
     * @hide
     */
    @SystemApi
    public interface Scheduler {
        String NAMESPACE = "scheduler";

        /**
         * Flag for enabling fast metrics collection in system scheduler.
         * A flag value of '' or '0' means the fast metrics collection is not
         * enabled. Otherwise fast metrics collection is enabled and flag value
         * is the order id.
         */
        String ENABLE_FAST_METRICS_COLLECTION = "enable_fast_metrics_collection";
    }

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static Map<OnPropertyChangedListener, Pair<String, Executor>> sListeners =
            new HashMap<>();
    @GuardedBy("sLock")
    private static Map<String, Pair<ContentObserver, Integer>> sNamespaces = new HashMap<>();

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
    public static String getProperty(String namespace, String name) {
        ContentResolver contentResolver = ActivityThread.currentApplication().getContentResolver();
        String compositeName = createCompositeName(namespace, name);
        return Settings.Config.getString(contentResolver, compositeName);
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
    public static boolean setProperty(
            String namespace, String name, String value, boolean makeDefault) {
        ContentResolver contentResolver = ActivityThread.currentApplication().getContentResolver();
        String compositeName = createCompositeName(namespace, name);
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
     */
    @SystemApi
    @TestApi
    @RequiresPermission(READ_DEVICE_CONFIG)
    public static void addOnPropertyChangedListener(
            @NonNull String namespace,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPropertyChangedListener onPropertyChangedListener) {
        // TODO enforce READ_DEVICE_CONFIG permission
        synchronized (sLock) {
            Pair<String, Executor> oldNamespace = sListeners.get(onPropertyChangedListener);
            if (oldNamespace == null) {
                // Brand new listener, add it to the list.
                sListeners.put(onPropertyChangedListener, new Pair<>(namespace, executor));
                incrementNamespace(namespace);
            } else if (namespace.equals(oldNamespace.first)) {
                // Listener is already registered for this namespace, update executor just in case.
                sListeners.put(onPropertyChangedListener, new Pair<>(namespace, executor));
            } else {
                // Update this listener from an old namespace to the new one.
                decrementNamespace(sListeners.get(onPropertyChangedListener).first);
                sListeners.put(onPropertyChangedListener, new Pair<>(namespace, executor));
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
     */
    @SystemApi
    @TestApi
    public static void removeOnPropertyChangedListener(
            OnPropertyChangedListener onPropertyChangedListener) {
        synchronized (sLock) {
            if (sListeners.containsKey(onPropertyChangedListener)) {
                decrementNamespace(sListeners.get(onPropertyChangedListener).first);
                sListeners.remove(onPropertyChangedListener);
            }
        }
    }

    private static String createCompositeName(String namespace, String name) {
        return namespace + "/" + name;
    }

    private static Uri createNamespaceUri(String namespace) {
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
    private static void incrementNamespace(String namespace) {
        Pair<ContentObserver, Integer> namespaceCount = sNamespaces.get(namespace);
        if (namespaceCount != null) {
            sNamespaces.put(namespace, new Pair<>(namespaceCount.first, namespaceCount.second + 1));
        } else {
            // This is a new namespace, register a ContentObserver for it.
            ContentObserver contentObserver = new ContentObserver(null) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    handleChange(uri);
                }
            };
            ActivityThread.currentApplication().getContentResolver()
                    .registerContentObserver(createNamespaceUri(namespace), true, contentObserver);
            sNamespaces.put(namespace, new Pair<>(contentObserver, 1));
        }
    }

    /**
     * Decrement the count used to represent th enumber of listeners subscribed to the given
     * namespace. If this is the final decrement call (i.e. decrementing from 1 to 0) for the given
     * namespace, the ContentObserver that had been tracking it will be removed.
     *
     * @param namespace The namespace to decrement the count for.
     */
    @GuardedBy("sLock")
    private static void decrementNamespace(String namespace) {
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

    private static void handleChange(Uri uri) {
        List<String> pathSegments = uri.getPathSegments();
        // pathSegments(0) is "config"
        String namespace = pathSegments.get(1);
        String name = pathSegments.get(2);
        String value = getProperty(namespace, name);
        synchronized (sLock) {
            for (OnPropertyChangedListener listener : sListeners.keySet()) {
                if (namespace.equals(sListeners.get(listener).first)) {
                    sListeners.get(listener).second.execute(new Runnable() {
                        @Override
                        public void run() {
                            listener.onPropertyChanged(namespace, name, value);
                        }

                    });
                }
            }
        }
    }

    /**
     * Interface for monitoring to properties.
     * <p>
     * Override {@link #onPropertyChanged(String, String, String)} to handle callbacks for changes.
     *
     * @hide
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
        void onPropertyChanged(String namespace, String name, String value);
    }
}
