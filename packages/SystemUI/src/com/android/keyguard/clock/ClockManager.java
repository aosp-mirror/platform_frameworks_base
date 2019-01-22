/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;

import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages custom clock faces.
 */
@Singleton
public final class ClockManager {

    private final LayoutInflater mLayoutInflater;
    private final ContentResolver mContentResolver;

    /**
     * Observe settings changes to know when to switch the clock face.
     */
    private final ContentObserver mContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    if (mClockExtension != null) {
                        mClockExtension.reload();
                    }
                }
            };

    private final ExtensionController mExtensionController;
    /**
     * Used to select between plugin or default implementations of ClockPlugin interface.
     */
    private Extension<ClockPlugin> mClockExtension;
    /**
     * Consumer that accepts the a new ClockPlugin implementation when the Extension reloads.
     */
    private final Consumer<ClockPlugin> mClockPluginConsumer = this::setClockPlugin;

    private final List<ClockChangedListener> mListeners = new ArrayList<>();

    @Inject
    public ClockManager(Context context, ExtensionController extensionController) {
        mExtensionController = extensionController;
        mLayoutInflater = LayoutInflater.from(context);
        mContentResolver = context.getContentResolver();
    }

    /**
     * Add listener to be notified when clock implementation should change.
     */
    public void addOnClockChangedListener(ClockChangedListener listener) {
        if (mListeners.isEmpty()) {
            register();
        }
        mListeners.add(listener);
        if (mClockExtension != null) {
            mClockExtension.reload();
        }
    }

    /**
     * Remove listener added with {@link addOnClockChangedListener}.
     */
    public void removeOnClockChangedListener(ClockChangedListener listener) {
        mListeners.remove(listener);
        if (mListeners.isEmpty()) {
            unregister();
        }
    }

    private void setClockPlugin(ClockPlugin plugin) {
        for (int i = 0; i < mListeners.size(); i++) {
            // It probably doesn't make sense to supply the same plugin instances to multiple
            // listeners. This should be fine for now since there is only a single listener.
            mListeners.get(i).onClockChanged(plugin);
        }
    }

    private void register() {
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                false, mContentObserver);
        mClockExtension = mExtensionController.newExtension(ClockPlugin.class)
            .withPlugin(ClockPlugin.class)
            .withCallback(mClockPluginConsumer)
            // Using withDefault even though this isn't the default as a workaround.
            // ExtensionBuilder doesn't provide the ability to supply a ClockPlugin
            // instance based off of the value of a setting. Since multiple "default"
            // can be provided, using a supplier that changes the settings value.
            // A null return will cause Extension#reload to look at the next "default"
            // supplier.
            .withDefault(
                    new SettingsGattedSupplier(
                        mContentResolver,
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                        BubbleClockController.class.getName(),
                            () -> BubbleClockController.build(mLayoutInflater)))
            .withDefault(
                    new SettingsGattedSupplier(
                        mContentResolver,
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                        StretchAnalogClockController.class.getName(),
                            () -> StretchAnalogClockController.build(mLayoutInflater)))
            .withDefault(
                    new SettingsGattedSupplier(
                        mContentResolver,
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                        TypeClockController.class.getName(),
                            () -> TypeClockController.build(mLayoutInflater)))
            .build();
    }

    private void unregister() {
        mContentResolver.unregisterContentObserver(mContentObserver);
        mClockExtension.destroy();
    }

    /**
     * Listener for events that should cause the custom clock face to change.
     */
    public interface ClockChangedListener {
        /**
         * Called when custom clock should change.
         *
         * @param clock Custom clock face to use. A null value indicates the default clock face.
         */
        void onClockChanged(ClockPlugin clock);
    }

    /**
     * Supplier that only gets an instance when a settings value matches expected value.
     */
    private static class SettingsGattedSupplier implements Supplier<ClockPlugin> {

        private final ContentResolver mContentResolver;
        private final String mKey;
        private final String mValue;
        private final Supplier<ClockPlugin> mSupplier;

        /**
         * Constructs a supplier that changes secure setting key against value.
         *
         * @param contentResolver Used to look up settings value.
         * @param key Settings key.
         * @param value If the setting matches this values that get supplies a ClockPlugin
         *        instance.
         * @param supplier Supplier of ClockPlugin instance, only used if the setting
         *        matches value.
         */
        SettingsGattedSupplier(ContentResolver contentResolver, String key, String value,
                Supplier<ClockPlugin> supplier) {
            mContentResolver = contentResolver;
            mKey = key;
            mValue = value;
            mSupplier = supplier;
        }

        /**
         * Returns null if the settings value doesn't match the expected value.
         *
         * A null return causes Extension#reload to skip this supplier and move to the next.
         */
        @Override
        public ClockPlugin get() {
            final String currentValue = Settings.Secure.getString(mContentResolver, mKey);
            return Objects.equals(currentValue, mValue) ? mSupplier.get() : null;
        }
    }
}
