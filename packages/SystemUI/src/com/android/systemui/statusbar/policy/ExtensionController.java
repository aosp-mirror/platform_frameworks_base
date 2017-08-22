/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.content.Context;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Utility class used to select between a plugin, tuner settings, and a default implementation
 * of an interface.
 */
public interface ExtensionController {

    <T> ExtensionBuilder<T> newExtension(Class<T> cls);

    interface Extension<T> {
        T get();
        Context getContext();
        void destroy();
        void addCallback(Consumer<T> callback);
        /**
         * Triggers the extension to cycle through each of the sources again because something
         * (like configuration) may have changed.
         */
        T reload();

        /**
         * Null out the cached item for the purpose of memory saving, should only be done
         * when any other references are already gotten.
         * @param isDestroyed
         */
        void clearItem(boolean isDestroyed);
    }

    interface ExtensionBuilder<T> {
        ExtensionBuilder<T> withTunerFactory(TunerFactory<T> factory);
        <P extends T> ExtensionBuilder<T> withPlugin(Class<P> cls);
        <P extends T> ExtensionBuilder<T> withPlugin(Class<P> cls, String action);
        <P> ExtensionBuilder<T> withPlugin(Class<P> cls, String action,
                PluginConverter<T, P> converter);
        ExtensionBuilder<T> withDefault(Supplier<T> def);
        ExtensionBuilder<T> withCallback(Consumer<T> callback);
        ExtensionBuilder<T> withUiMode(int mode, Supplier<T> def);
        ExtensionBuilder<T> withFeature(String feature, Supplier<T> def);
        Extension build();
    }

    public interface PluginConverter<T, P> {
        T getInterfaceFromPlugin(P plugin);
    }

    public interface TunerFactory<T> {
        String[] keys();
        T create(Map<String, String> settings);
    }
}
