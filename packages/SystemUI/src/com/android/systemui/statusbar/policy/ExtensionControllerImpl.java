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
import android.content.res.Configuration;
import android.os.Handler;
import android.util.ArrayMap;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.leak.LeakDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class ExtensionControllerImpl implements ExtensionController {

    public static final int SORT_ORDER_PLUGIN  = 0;
    public static final int SORT_ORDER_TUNER   = 1;
    public static final int SORT_ORDER_FEATURE = 2;
    public static final int SORT_ORDER_UI_MODE = 3;
    public static final int SORT_ORDER_DEFAULT = 4;

    private final Context mDefaultContext;
    private final LeakDetector mLeakDetector;
    private final PluginManager mPluginManager;
    private final TunerService mTunerService;
    private final ConfigurationController mConfigurationController;

    /**
     */
    @Inject
    public ExtensionControllerImpl(
            Context context,
            LeakDetector leakDetector,
            PluginManager pluginManager,
            TunerService tunerService,
            ConfigurationController configurationController) {
        mDefaultContext = context;
        mLeakDetector = leakDetector;
        mPluginManager = pluginManager;
        mTunerService = tunerService;
        mConfigurationController = configurationController;
    }

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
            return withPlugin(cls, PluginManager.Helper.getAction(cls));
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
        public ExtensionController.ExtensionBuilder<T> withUiMode(int uiMode,
                Supplier<T> supplier) {
            mExtension.addUiMode(uiMode, supplier);
            return this;
        }

        @Override
        public ExtensionController.ExtensionBuilder<T> withFeature(String feature,
                Supplier<T> supplier) {
            mExtension.addFeature(feature, supplier);
            return this;
        }

        @Override
        public ExtensionController.ExtensionBuilder<T> withCallback(
                Consumer<T> callback) {
            mExtension.mCallbacks.add(callback);
            return this;
        }

        @Override
        public ExtensionController.Extension<T> build() {
            // Sort items in ascending order
            Collections.sort(mExtension.mProducers, Comparator.comparingInt(Item::sortOrder));
            mExtension.notifyChanged();
            return mExtension;
        }
    }

    private class ExtensionImpl<T> implements ExtensionController.Extension<T> {
        private final ArrayList<Item<T>> mProducers = new ArrayList<>();
        private final ArrayList<Consumer<T>> mCallbacks = new ArrayList<>();
        private T mItem;
        private Context mPluginContext;

        public void addCallback(Consumer<T> callback) {
            mCallbacks.add(callback);
        }

        @Override
        public T get() {
            return mItem;
        }

        @Override
        public Context getContext() {
            return mPluginContext != null ? mPluginContext : mDefaultContext;
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

        @Override
        public void clearItem(boolean isDestroyed) {
            if (isDestroyed && mItem != null) {
                mLeakDetector.trackGarbage(mItem);
            }
            mItem = null;
        }

        private void notifyChanged() {
            if (mItem != null) {
                mLeakDetector.trackGarbage(mItem);
            }
            mItem = null;
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
            mProducers.add(new TunerItem(factory, keys));
        }

        public void addUiMode(int uiMode, Supplier<T> mode) {
            mProducers.add(new UiModeItem(uiMode, mode));
        }

        public void addFeature(String feature, Supplier<T> mode) {
            mProducers.add(new FeatureItem<>(feature, mode));
        }

        private class PluginItem<P extends Plugin> implements Item<T>, PluginListener<P> {
            private final PluginConverter<T, P> mConverter;
            private T mItem;

            public PluginItem(String action, Class<P> cls, PluginConverter<T, P> converter) {
                mConverter = converter;
                mPluginManager.addPluginListener(action, this, cls);
            }

            @Override
            public void onPluginConnected(P plugin, Context pluginContext) {
                mPluginContext = pluginContext;
                if (mConverter != null) {
                    mItem = mConverter.getInterfaceFromPlugin(plugin);
                } else {
                    mItem = (T) plugin;
                }
                notifyChanged();
            }

            @Override
            public void onPluginDisconnected(P plugin) {
                mPluginContext = null;
                mItem = null;
                notifyChanged();
            }

            @Override
            public T get() {
                return mItem;
            }

            @Override
            public void destroy() {
                mPluginManager.removePluginListener(this);
            }

            @Override
            public int sortOrder() {
                return SORT_ORDER_PLUGIN;
            }
        }

        private class TunerItem<T> implements Item<T>, Tunable {
            private final TunerFactory<T> mFactory;
            private final ArrayMap<String, String> mSettings = new ArrayMap<>();
            private T mItem;

            public TunerItem(TunerFactory<T> factory, String... setting) {
                mFactory = factory;
                mTunerService.addTunable(this, setting);
            }

            @Override
            public T get() {
                return mItem;
            }

            @Override
            public void destroy() {
                mTunerService.removeTunable(this);
            }

            @Override
            public void onTuningChanged(String key, String newValue) {
                mSettings.put(key, newValue);
                mItem = mFactory.create(mSettings);
                notifyChanged();
            }

            @Override
            public int sortOrder() {
                return SORT_ORDER_TUNER;
            }
        }

        private class UiModeItem<T> implements Item<T>, ConfigurationListener {

            private final int mDesiredUiMode;
            private final Supplier<T> mSupplier;
            private int mUiMode;
            private Handler mHandler = new Handler();

            public UiModeItem(int uiMode, Supplier<T> supplier) {
                mDesiredUiMode = uiMode;
                mSupplier = supplier;
                mUiMode = mDefaultContext.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_TYPE_MASK;
                mConfigurationController.addCallback(this);
            }

            @Override
            public void onConfigChanged(Configuration newConfig) {
                int newMode = newConfig.uiMode & Configuration.UI_MODE_TYPE_MASK;
                if (newMode != mUiMode) {
                    mUiMode = newMode;
                    // Post to make sure we don't have concurrent modifications.
                    mHandler.post(ExtensionImpl.this::notifyChanged);
                }
            }

            @Override
            public T get() {
                return (mUiMode == mDesiredUiMode) ? mSupplier.get() : null;
            }

            @Override
            public void destroy() {
                mConfigurationController.removeCallback(this);
            }

            @Override
            public int sortOrder() {
                return SORT_ORDER_UI_MODE;
            }
        }

        private class FeatureItem<T> implements Item<T> {
            private final String mFeature;
            private final Supplier<T> mSupplier;

            public FeatureItem(String feature, Supplier<T> supplier) {
                mSupplier = supplier;
                mFeature = feature;
            }

            @Override
            public T get() {
                return mDefaultContext.getPackageManager().hasSystemFeature(mFeature)
                        ? mSupplier.get() : null;
            }

            @Override
            public void destroy() {

            }

            @Override
            public int sortOrder() {
                return SORT_ORDER_FEATURE;
            }
        }

        private class Default<T> implements Item<T> {
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

            @Override
            public int sortOrder() {
                return SORT_ORDER_DEFAULT;
            }
        }
    }

    private interface Item<T> extends Producer<T> {
        int sortOrder();
    }
}
