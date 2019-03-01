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
import android.app.WallpaperManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

import androidx.annotation.VisibleForTesting;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManager.DockEventListener;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.util.InjectionInflationController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages custom clock faces for AOD and lock screen.
 */
@Singleton
public final class ClockManager {

    private static final String TAG = "ClockOptsProvider";
    private static final String DEFAULT_CLOCK_ID = "default";

    private final List<ClockInfo> mClockInfos = new ArrayList<>();
    /**
     * Map from expected value stored in settings to supplier of custom clock face.
     */
    private final Map<String, ClockPlugin> mClocks = new ArrayMap<>();
    @Nullable private ClockPlugin mCurrentClock;

    private final ContentResolver mContentResolver;
    private final SettingsWrapper mSettingsWrapper;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Observe settings changes to know when to switch the clock face.
     */
    private final ContentObserver mContentObserver =
            new ContentObserver(mMainHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    reload();
                }
            };

    private final PluginListener<ClockPlugin> mClockPluginListener =
            new PluginListener<ClockPlugin>() {
                @Override
                public void onPluginConnected(ClockPlugin plugin, Context pluginContext) {
                    addClockPlugin(plugin);
                    reload();
                }

                @Override
                public void onPluginDisconnected(ClockPlugin plugin) {
                    removeClockPlugin(plugin);
                    reload();
                }
            };

    private final PluginManager mPluginManager;

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
    @Nullable private final DockManager mDockManager;
    /**
     * When docked, the DOCKED_CLOCK_FACE setting will be checked for the custom clock face
     * to show.
     */
    private boolean mIsDocked;

    private final List<ClockChangedListener> mListeners = new ArrayList<>();

    private final SysuiColorExtractor mColorExtractor;
    private final int mWidth;
    private final int mHeight;

    @Inject
    public ClockManager(Context context, InjectionInflationController injectionInflater,
            PluginManager pluginManager, @Nullable DockManager dockManager,
            SysuiColorExtractor colorExtractor) {
        this(context, injectionInflater, pluginManager, dockManager, colorExtractor,
                context.getContentResolver(), new SettingsWrapper(context.getContentResolver()));
    }

    ClockManager(Context context, InjectionInflationController injectionInflater,
            PluginManager pluginManager, @Nullable DockManager dockManager,
            SysuiColorExtractor colorExtractor, ContentResolver contentResolver,
            SettingsWrapper settingsWrapper) {
        mPluginManager = pluginManager;
        mDockManager = dockManager;
        mColorExtractor = colorExtractor;
        mContentResolver = contentResolver;
        mSettingsWrapper = settingsWrapper;

        Resources res = context.getResources();
        LayoutInflater layoutInflater = injectionInflater.injectable(LayoutInflater.from(context));

        addClockPlugin(new DefaultClockController(res, layoutInflater));
        addClockPlugin(new BubbleClockController(res, layoutInflater));
        addClockPlugin(new StretchAnalogClockController(res, layoutInflater));
        addClockPlugin(new TypeClockController(res, layoutInflater));

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
        mListeners.add(listener);
        reload();
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

    /**
     * Get information about available clock faces.
     */
    List<ClockInfo> getClockInfos() {
        return mClockInfos;
    }

    /**
     * Get the current clock.
     * @returns current custom clock or null for default.
     */
    @Nullable
    ClockPlugin getCurrentClock() {
        return mCurrentClock;
    }

    @VisibleForTesting
    boolean isDocked() {
        return mIsDocked;
    }

    @VisibleForTesting
    ContentObserver getContentObserver() {
        return mContentObserver;
    }

    private void addClockPlugin(ClockPlugin plugin) {
        final String id = plugin.getClass().getName();
        mClocks.put(plugin.getClass().getName(), plugin);
        mClockInfos.add(ClockInfo.builder()
                .setName(plugin.getName())
                .setTitle(plugin.getTitle())
                .setId(id)
                .setThumbnail(() -> plugin.getThumbnail())
                .setPreview(() -> getClockPreview(id))
                .build());
    }

    private void removeClockPlugin(ClockPlugin plugin) {
        final String id = plugin.getClass().getName();
        mClocks.remove(id);
        for (int i = 0; i < mClockInfos.size(); i++) {
            if (id.equals(mClockInfos.get(i).getId())) {
                mClockInfos.remove(i);
                break;
            }
        }
    }

    /**
     * Generate a realistic preview of a clock face.
     * @param clockId ID of clock to use for preview, should be obtained from {@link getClockInfos}.
     *        Returns null if clockId is not found.
     */
    @Nullable
    private Bitmap getClockPreview(String clockId) {
        FutureTask<Bitmap> task = new FutureTask<>(new Callable<Bitmap>() {
            @Override
            public Bitmap call() {
                Bitmap bitmap = Bitmap.createBitmap(mWidth, mHeight, Config.ARGB_8888);
                ClockPlugin plugin = mClocks.get(clockId);
                if (plugin == null) {
                    return null;
                }

                // Use the big clock view for the preview
                View clockView = plugin.getBigClockView();
                if (clockView == null) {
                    return null;
                }

                // Initialize state of plugin before generating preview.
                plugin.setDarkAmount(1f);
                plugin.setTextColor(Color.WHITE);

                ColorExtractor.GradientColors colors = mColorExtractor.getColors(
                        WallpaperManager.FLAG_LOCK, true);
                plugin.setColorPalette(colors.supportsDarkText(), colors.getColorPalette());
                plugin.onTimeTick();

                // Draw clock view hierarchy to canvas.
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.BLACK);
                dispatchVisibilityAggregated(clockView, true);
                clockView.measure(MeasureSpec.makeMeasureSpec(mWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(mHeight, MeasureSpec.EXACTLY));
                clockView.layout(0, 0, mWidth, mHeight);
                clockView.draw(canvas);
                return bitmap;
            }
        });

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            mMainHandler.post(task);
        }

        try {
            return task.get();
        } catch (Exception e) {
            Log.e(TAG, "Error completing task", e);
            return null;
        }
    }

    private void dispatchVisibilityAggregated(View view, boolean isVisible) {
        // Similar to View.dispatchVisibilityAggregated implementation.
        final boolean thisVisible = view.getVisibility() == View.VISIBLE;
        if (thisVisible || !isVisible) {
            view.onVisibilityAggregated(isVisible);
        }

        if (view instanceof ViewGroup) {
            isVisible = thisVisible && isVisible;
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();

            for (int i = 0; i < count; i++) {
                dispatchVisibilityAggregated(vg.getChildAt(i), isVisible);
            }
        }
    }

    private void notifyClockChanged(ClockPlugin plugin) {
        for (int i = 0; i < mListeners.size(); i++) {
            // It probably doesn't make sense to supply the same plugin instances to multiple
            // listeners. This should be fine for now since there is only a single listener.
            mListeners.get(i).onClockChanged(plugin);
        }
    }

    private void register() {
        mPluginManager.addPluginListener(mClockPluginListener, ClockPlugin.class, true);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                false, mContentObserver);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOCKED_CLOCK_FACE),
                false, mContentObserver);
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
        }
    }

    private void unregister() {
        mPluginManager.removePluginListener(mClockPluginListener);
        mContentResolver.unregisterContentObserver(mContentObserver);
        if (mDockManager != null) {
            mDockManager.removeListener(mDockEventListener);
        }
    }

    private void reload() {
        mCurrentClock = getClockPlugin();
        if (mCurrentClock instanceof DefaultClockController) {
            notifyClockChanged(null);
        } else {
            notifyClockChanged(mCurrentClock);
        }
    }

    private ClockPlugin getClockPlugin() {
        ClockPlugin plugin = null;
        if (mIsDocked) {
            final String name = mSettingsWrapper.getDockedClockFace();
            if (name != null) {
                plugin = mClocks.get(name);
                if (plugin != null) {
                    return plugin;
                }
            }
        }
        final String name = mSettingsWrapper.getLockScreenCustomClockFace();
        if (name != null) {
            plugin = mClocks.get(name);
        }
        return plugin;
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
}
