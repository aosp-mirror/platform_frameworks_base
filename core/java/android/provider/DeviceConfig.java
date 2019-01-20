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
public final class DeviceConfig {
    /**
     * The content:// style URL for the config table.
     *
     * @hide
     */
    public static final Uri CONTENT_URI = Uri.parse("content://" + Settings.AUTHORITY + "/config");

    /**
     * Namespace for all Game Driver features.
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
    public static final String NAMESPACE_AUTOFILL = "autofill";

    /**
     * Namespace for content capture feature used by on-device machine intelligence
     * to provide suggestions in a privacy-safe manner.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_CONTENT_CAPTURE = "content_capture";

    /**
     * Namespace for all input-related features that are used at the native level.
     * These features are applied at reboot.
     *
     * @hide
     */
    @SystemApi
    public static final String NAMESPACE_INPUT_NATIVE_BOOT = "input_native_boot";

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
     * @param name The name of the property to look up.
     * @return the corresponding value, or null if not present.
     *
     * @hide
     */
    @SystemApi
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
     * @param namespace The namespace containing the property to create or update.
     * @param name The name of the property to create or update.
     * @param value The value to store for the property.
     * @param makeDefault Whether to make the new value the default one.
     * @return True if the value was set, false if the storage implementation throws errors.
     * @see #resetToDefaults(int, String).
     *
     * @hide
     */
    @SystemApi
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
     * @see #setProperty(String, String, String, boolean)
     *
     * @hide
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
     * {@link #removeOnPropertyChangedListener(OnPropertyChangedListener)}.
     *
     * @param namespace The namespace containing properties to monitor.
     * @param executor The executor which will be used to run callbacks.
     * @param onPropertyChangedListener The listener to add.
     * @see #removeOnPropertyChangedListener(OnPropertyChangedListener)
     *
     * @hide
     */
    @SystemApi
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
     * @see #addOnPropertyChangedListener(String, Executor, OnPropertyChangedListener)
     *
     * @hide
     */
    @SystemApi
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
     */
    public interface OnPropertyChangedListener {
        /**
         * Called when a property has changed.
         *
         * @param namespace The namespace containing the property which has changed.
         * @param name The name of the property which has changed.
         * @param value The new value of the property which has changed.
         */
        void onPropertyChanged(String namespace, String name, String value);
    }
}
