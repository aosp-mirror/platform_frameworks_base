/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.plugin;

import android.annotation.Nullable;
import android.content.Context;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SystemServerClassLoaderFactory;
import com.android.server.display.feature.DisplayManagerFlags;

import dalvik.system.PathClassLoader;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;

/**
 * Responsible for loading Plugins. Plugins and PluginSupplier are loaded from
 * standalone system jar.
 * Plugin manager will look for PROVIDER_IMPL_CLASS in configured jar.
 * After device booted, PluginManager will delegate this call to each Plugin
 */
public class PluginManager {
    private static final String PROVIDER_IMPL_CLASS =
            "com.android.server.display.plugin.PluginsProviderImpl";
    private static final String TAG = "PluginManager";

    private final DisplayManagerFlags mFlags;
    private final PluginStorage mPluginStorage;
    private final List<Plugin> mPlugins;

    public PluginManager(Context context, DisplayManagerFlags flags) {
        this(context, flags, new Injector());
    }

    @VisibleForTesting
    PluginManager(Context context, DisplayManagerFlags flags, Injector injector) {
        mFlags = flags;
        mPluginStorage = injector.getPluginStorage();
        if (mFlags.isPluginManagerEnabled()) {
            mPlugins = Collections.unmodifiableList(injector.loadPlugins(context, mPluginStorage));
            Slog.d(TAG, "loaded Plugins:" + mPlugins);
        } else {
            mPlugins = List.of();
            Slog.d(TAG, "PluginManager disabled");
        }
    }

    /**
     * Forwards boot completed event to Plugins
     */
    public void onBootCompleted() {
        mPlugins.forEach(Plugin::onBootCompleted);
    }

    /**
     * Adds change listener for particular plugin type
     */
    public <T> void subscribe(PluginType<T> type, PluginChangeListener<T> listener) {
        mPluginStorage.addListener(type, listener);
    }

    /**
     * Removes change listener
     */
    public <T> void unsubscribe(PluginType<T> type, PluginChangeListener<T> listener) {
        mPluginStorage.removeListener(type, listener);
    }

    /**
     * Print the object's state and debug information into the given stream.
     */
    public void dump(PrintWriter pw) {
        pw.println("PluginManager:");
        mPluginStorage.dump(pw);
        for (Plugin plugin : mPlugins) {
            plugin.dump(pw);
        }
    }

    /**
     * Listens for changes in PluginStorage for a particular type
     * @param <T> plugin value type
     */
    public interface PluginChangeListener<T> {
        /**
         * Called when Plugin value changed
         */
        void onChanged(@Nullable T value);
    }

    static class Injector {
        PluginStorage getPluginStorage() {
            return new PluginStorage();
        }

        List<Plugin> loadPlugins(Context context, PluginStorage storage) {
            String providerJarPath = context
                    .getString(com.android.internal.R.string.config_pluginsProviderJarPath);
            Slog.d(TAG, "loading plugins from:" + providerJarPath);
            if (TextUtils.isEmpty(providerJarPath)) {
                return List.of();
            }
            try {
                PathClassLoader pathClassLoader =
                        SystemServerClassLoaderFactory.getOrCreateClassLoader(
                                providerJarPath, getClass().getClassLoader(), false);
                @SuppressWarnings("PrivateApi")
                Class<? extends PluginsProvider> cp = pathClassLoader.loadClass(PROVIDER_IMPL_CLASS)
                        .asSubclass(PluginsProvider.class);
                PluginsProvider provider = cp.getDeclaredConstructor().newInstance();
                return provider.getPlugins(context, storage);
            } catch (ClassNotFoundException e) {
                Slog.e(TAG, "loading failed: " + PROVIDER_IMPL_CLASS + " is not found in"
                        + providerJarPath, e);
            } catch (InvocationTargetException | InstantiationException | IllegalAccessException
                     | NoSuchMethodException e) {
                Slog.e(TAG, "Class instantiation failed", e);
            }
            return List.of();
        }
    }
}
