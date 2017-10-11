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

import com.android.systemui.Dependency;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import android.content.Context;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ExtensionControllerImpl implements ExtensionController {

    @Override
    public <T> ExtensionBuilder<T> newExtension(Class<T> cls) {
        return new ExtensionBuilder<>();
    }

    private interface Producer<T> {
        T get();
        void destroy();
    }

    private class ExtensionBuilder<T> implements ExtensionController.ExtensionBuilder<T> {

        private ExtensionImpl<T> mExtension = new ExtensionImpl<>();

        @Override
        public ExtensionController.ExtensionBuilder<T> withTunerFactory(TunerFactory<T> factory) {
            mExtension.addTunerFactory(factory, factory.keys());
            return this;
        }

        @Override
        public <P extends T> ExtensionController.ExtensionBuilder<T> withPlugin(Class<P> cls) {
            return withPlugin(cls, PluginManager.getAction(cls));
        }

        @Override
        public <P extends T> ExtensionController.ExtensionBuilder<T> withPlugin(Class<P> cls,
                String action) {
            return withPlugin(cls, action, null);
        }

        @Override
        public <P> ExtensionController.ExtensionBuilder<T> withPlugin(Class<P> cls,
                String action, PluginConverter<T, P> converter) {
            mExtension.addPlugin(action, cls, converter);
            return this;
        }

        @Override
        public ExtensionController.ExtensionBuilder<T> withDefault(Supplier<T> def) {
            mExtension.addDefault(def);
            return this;
        }

        @Override
        public ExtensionController.ExtensionBuilder<T> withCallback(
                Consumer<T> callback) {
            mExtension.mCallbacks.add(callback);
            return this;
        }

        @Override
        public ExtensionController.Extension build() {
            // Manually sort, plugins first, tuners second, defaults last.
            Collections.sort(mExtension.mProducers, (o1, o2) -> {
                if (o1 instanceof ExtensionImpl.PluginItem) {
                    if (o2 instanceof ExtensionImpl.PluginItem) {
                        return 0;
                    } else {
                        return -1;
                    }
                }
                if (o1 instanceof ExtensionImpl.TunerItem) {
                    if (o2 instanceof ExtensionImpl.PluginItem) {
                        return 1;
                    } else if (o2 instanceof ExtensionImpl.TunerItem) {
                        return 0;
                    } else {
                        return -1;
                    }
                }
                return 0;
            });
            mExtension.notifyChanged();
            return mExtension;
        }
    }

    private class ExtensionImpl<T> implements ExtensionController.Extension<T> {
        private final ArrayList<Producer<T>> mProducers = new ArrayList<>();
        private final ArrayList<Consumer<T>> mCallbacks = new ArrayList<>();
        private T mItem;

        @Override
        public T get() {
            return mItem;
        }

        @Override
        public void destroy() {
            for (int i = 0; i < mProducers.size(); i++) {
                mProducers.get(i).destroy();
            }
        }

        @Override
        public T reload() {
            notifyChanged();
            return get();
        }

        private void notifyChanged() {
            for (int i = 0; i < mProducers.size(); i++) {
                final T item = mProducers.get(i).get();
                if (item != null) {
                    mItem = item;
                    break;
                }
            }
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).accept(mItem);
            }
        }

        public void addDefault(Supplier<T> def) {
            mProducers.add(new Default(def));
        }

        public <P> void addPlugin(String action, Class<P> cls, PluginConverter<T, P> converter) {
            mProducers.add(new PluginItem(action, cls, converter));
        }

        public void addTunerFactory(TunerFactory<T> factory, String[] keys) {
            mProducers.add(new TunerItem(factory, factory.keys()));
        }

        private class PluginItem<P extends Plugin> implements Producer<T>, PluginListener<P> {
            private final PluginConverter<T, P> mConverter;
            private T mItem;

            public PluginItem(String action, Class<P> cls, PluginConverter<T, P> converter) {
                mConverter = converter;
                Dependency.get(PluginManager.class).addPluginListener(action, this, cls);
            }

            @Override
            public void onPluginConnected(P plugin, Context pluginContext) {
                if (mConverter != null) {
                    mItem = mConverter.getInterfaceFromPlugin(plugin);
                } else {
                    mItem = (T) plugin;
                }
                notifyChanged();
            }

            @Override
            public void onPluginDisconnected(P plugin) {
                mItem = null;
                notifyChanged();
            }

            @Override
            public T get() {
                return mItem;
            }

            @Override
            public void destroy() {
                Dependency.get(PluginManager.class).removePluginListener(this);
            }
        }

        private class TunerItem<T> implements Producer<T>, Tunable {
            private final TunerFactory<T> mFactory;
            private final ArrayMap<String, String> mSettings = new ArrayMap<>();
            private T mItem;

            public TunerItem(TunerFactory<T> factory, String... setting) {
                mFactory = factory;
                Dependency.get(TunerService.class).addTunable(this, setting);
            }

            @Override
            public T get() {
                return mItem;
            }

            @Override
            public void destroy() {
                Dependency.get(TunerService.class).removeTunable(this);
            }

            @Override
            public void onTuningChanged(String key, String newValue) {
                mSettings.put(key, newValue);
                mItem = mFactory.create(mSettings);
                notifyChanged();
            }
        }

        private class Default<T> implements Producer<T> {
            private final Supplier<T> mSupplier;

            public Default(Supplier<T> supplier) {
                mSupplier = supplier;
            }

            @Override
            public T get() {
                return mSupplier.get();
            }

            @Override
            public void destroy() {

            }
        }
    }
}
