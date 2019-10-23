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

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Observer;

import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManager.DockEventListener;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.settings.CurrentUserObservable;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.util.InjectionInflationController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages custom clock faces for AOD and lock screen.
 */
@Singleton
public final class ClockManager {

    private static final String TAG = "ClockOptsProvider";

    private final AvailableClocks mPreviewClocks;
    private final List<Supplier<ClockPlugin>> mBuiltinClocks = new ArrayList<>();

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final SettingsWrapper mSettingsWrapper;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CurrentUserObservable mCurrentUserObservable;

    /**
     * Observe settings changes to know when to switch the clock face.
     */
    private final ContentObserver mContentObserver =
            new ContentObserver(mMainHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri, int userId) {
                    super.onChange(selfChange, uri, userId);
                    if (Objects.equals(userId,
                            mCurrentUserObservable.getCurrentUser().getValue())) {
                        reload();
                    }
                }
            };

    /**
     * Observe user changes and react by potentially loading the custom clock for the new user.
     */
    private final Observer<Integer> mCurrentUserObserver = (newUserId) -> reload();

    private final PluginManager mPluginManager;
    @Nullable private final DockManager mDockManager;

    /**
     * Observe changes to dock state to know when to switch the clock face.
     */
    private final DockEventListener mDockEventListener =
            new DockEventListener() {
                @Override
                public void onEvent(int event) {
                    mIsDocked = (event == DockManager.STATE_DOCKED
                            || event == DockManager.STATE_DOCKED_HIDE);
                    reload();
                }
            };

    /**
     * When docked, the DOCKED_CLOCK_FACE setting will be checked for the custom clock face
     * to show.
     */
    private boolean mIsDocked;

    /**
     * Listeners for onClockChanged event.
     *
     * Each listener must receive a separate clock plugin instance. Otherwise, there could be
     * problems like attempting to attach a view that already has a parent. To deal with this issue,
     * each listener is associated with a collection of available clocks. When onClockChanged is
     * fired the current clock plugin instance is retrieved from that listeners available clocks.
     */
    private final Map<ClockChangedListener, AvailableClocks> mListeners = new ArrayMap<>();

    private final int mWidth;
    private final int mHeight;

    @Inject
    public ClockManager(Context context, InjectionInflationController injectionInflater,
            PluginManager pluginManager, SysuiColorExtractor colorExtractor,
            @Nullable DockManager dockManager) {
        this(context, injectionInflater, pluginManager, colorExtractor,
                context.getContentResolver(), new CurrentUserObservable(context),
                new SettingsWrapper(context.getContentResolver()), dockManager);
    }

    @VisibleForTesting
    ClockManager(Context context, InjectionInflationController injectionInflater,
            PluginManager pluginManager, SysuiColorExtractor colorExtractor,
            ContentResolver contentResolver, CurrentUserObservable currentUserObservable,
            SettingsWrapper settingsWrapper, DockManager dockManager) {
        mContext = context;
        mPluginManager = pluginManager;
        mContentResolver = contentResolver;
        mSettingsWrapper = settingsWrapper;
        mCurrentUserObservable = currentUserObservable;
        mDockManager = dockManager;
        mPreviewClocks = new AvailableClocks();

        Resources res = context.getResources();
        LayoutInflater layoutInflater = injectionInflater.injectable(LayoutInflater.from(context));

        addBuiltinClock(() -> new DefaultClockController(res, layoutInflater, colorExtractor));
        addBuiltinClock(() -> new BubbleClockController(res, layoutInflater, colorExtractor));
        addBuiltinClock(() -> new AnalogClockController(res, layoutInflater, colorExtractor));
        addBuiltinClock(() -> new TypeClockController(res, layoutInflater, colorExtractor));
        addBuiltinClock(() -> new BinaryClockController(res, layoutInflater, colorExtractor));

        // Store the size of the display for generation of clock preview.
        DisplayMetrics dm = res.getDisplayMetrics();
        mWidth = dm.widthPixels;
        mHeight = dm.heightPixels;
    }

    /**
     * Add listener to be notified when clock implementation should change.
     */
    public void addOnClockChangedListener(ClockChangedListener listener) {
        if (mListeners.isEmpty()) {
            register();
        }
        AvailableClocks availableClocks = new AvailableClocks();
        for (int i = 0; i < mBuiltinClocks.size(); i++) {
            availableClocks.addClockPlugin(mBuiltinClocks.get(i).get());
        }
        mListeners.put(listener, availableClocks);
        mPluginManager.addPluginListener(availableClocks, ClockPlugin.class, true);
        reload();
    }

    /**
     * Remove listener added with {@link addOnClockChangedListener}.
     */
    public void removeOnClockChangedListener(ClockChangedListener listener) {
        AvailableClocks availableClocks = mListeners.remove(listener);
        mPluginManager.removePluginListener(availableClocks);
        if (mListeners.isEmpty()) {
            unregister();
        }
    }

    /**
     * Get information about available clock faces.
     */
    List<ClockInfo> getClockInfos() {
        return mPreviewClocks.getInfo();
    }

    /**
     * Get the current clock.
     * @return current custom clock or null for default.
     */
    @Nullable
    ClockPlugin getCurrentClock() {
        return mPreviewClocks.getCurrentClock();
    }

    @VisibleForTesting
    boolean isDocked() {
        return mIsDocked;
    }

    @VisibleForTesting
    ContentObserver getContentObserver() {
        return mContentObserver;
    }

    private void addBuiltinClock(Supplier<ClockPlugin> pluginSupplier) {
        ClockPlugin plugin = pluginSupplier.get();
        mPreviewClocks.addClockPlugin(plugin);
        mBuiltinClocks.add(pluginSupplier);
    }

    private void register() {
        mPluginManager.addPluginListener(mPreviewClocks, ClockPlugin.class, true);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                false, mContentObserver, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOCKED_CLOCK_FACE),
                false, mContentObserver, UserHandle.USER_ALL);
        mCurrentUserObservable.getCurrentUser().observeForever(mCurrentUserObserver);
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
        }
    }

    private void unregister() {
        mPluginManager.removePluginListener(mPreviewClocks);
        mContentResolver.unregisterContentObserver(mContentObserver);
        mCurrentUserObservable.getCurrentUser().removeObserver(mCurrentUserObserver);
        if (mDockManager != null) {
            mDockManager.removeListener(mDockEventListener);
        }
    }

    private void reload() {
        mPreviewClocks.reload();
        mListeners.forEach((listener, clocks) -> {
            clocks.reload();
            ClockPlugin clock = clocks.getCurrentClock();
            if (clock instanceof DefaultClockController) {
                listener.onClockChanged(null);
            } else {
                listener.onClockChanged(clock);
            }
        });
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
     * Collection of available clocks.
     */
    private final class AvailableClocks implements PluginListener<ClockPlugin> {

        /**
         * Map from expected value stored in settings to plugin for custom clock face.
         */
        private final Map<String, ClockPlugin> mClocks = new ArrayMap<>();

        /**
         * Metadata about available clocks, such as name and preview images.
         */
        private final List<ClockInfo> mClockInfo = new ArrayList<>();

        /**
         * Active ClockPlugin.
         */
        @Nullable private ClockPlugin mCurrentClock;

        @Override
        public void onPluginConnected(ClockPlugin plugin, Context pluginContext) {
            addClockPlugin(plugin);
            reload();
            if (plugin == mCurrentClock) {
                ClockManager.this.reload();
            }
        }

        @Override
        public void onPluginDisconnected(ClockPlugin plugin) {
            boolean isCurrentClock = plugin == mCurrentClock;
            removeClockPlugin(plugin);
            reload();
            if (isCurrentClock) {
                ClockManager.this.reload();
            }
        }

        /**
         * Get the current clock.
         * @return current custom clock or null for default.
         */
        @Nullable
        ClockPlugin getCurrentClock() {
            return mCurrentClock;
        }

        /**
         * Get information about available clock faces.
         */
        List<ClockInfo> getInfo() {
            return mClockInfo;
        }

        /**
         * Adds a clock plugin to the collection of available clocks.
         *
         * @param plugin The plugin to add.
         */
        void addClockPlugin(ClockPlugin plugin) {
            final String id = plugin.getClass().getName();
            mClocks.put(plugin.getClass().getName(), plugin);
            mClockInfo.add(ClockInfo.builder()
                    .setName(plugin.getName())
                    .setTitle(plugin.getTitle())
                    .setId(id)
                    .setThumbnail(plugin::getThumbnail)
                    .setPreview(() -> plugin.getPreview(mWidth, mHeight))
                    .build());
        }

        private void removeClockPlugin(ClockPlugin plugin) {
            final String id = plugin.getClass().getName();
            mClocks.remove(id);
            for (int i = 0; i < mClockInfo.size(); i++) {
                if (id.equals(mClockInfo.get(i).getId())) {
                    mClockInfo.remove(i);
                    break;
                }
            }
        }

        /**
         * Update the current clock.
         */
        void reload() {
            mCurrentClock = getClockPlugin();
        }

        private ClockPlugin getClockPlugin() {
            ClockPlugin plugin = null;
            if (ClockManager.this.isDocked()) {
                final String name = mSettingsWrapper.getDockedClockFace(
                        mCurrentUserObservable.getCurrentUser().getValue());
                if (name != null) {
                    plugin = mClocks.get(name);
                    if (plugin != null) {
                        return plugin;
                    }
                }
            }
            final String name = mSettingsWrapper.getLockScreenCustomClockFace(
                    mCurrentUserObservable.getCurrentUser().getValue());
            if (name != null) {
                plugin = mClocks.get(name);
            }
            return plugin;
        }
    }
}
