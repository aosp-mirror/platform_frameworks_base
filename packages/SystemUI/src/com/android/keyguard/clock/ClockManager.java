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
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;

import androidx.annotation.VisibleForTesting;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManager.DockEventListener;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.util.InjectionInflationController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages custom clock faces for AOD and lock screen.
 */
@Singleton
public final class ClockManager {

    private final List<ClockInfo> mClockInfos = new ArrayList<>();
    /**
     * Map from expected value stored in settings to supplier of custom clock face.
     */
    private final Map<String, Supplier<ClockPlugin>> mClocks = new ArrayMap<>();
    @Nullable private ClockPlugin mCurrentClock;

    private final ContentResolver mContentResolver;
    private final SettingsWrapper mSettingsWrapper;
    /**
     * Observe settings changes to know when to switch the clock face.
     */
    private final ContentObserver mContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    reload();
                }
            };
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
            @Nullable DockManager dockManager, SysuiColorExtractor colorExtractor) {
        this(context, injectionInflater, dockManager, colorExtractor, context.getContentResolver(),
                new SettingsWrapper(context.getContentResolver()));
    }

    ClockManager(Context context, InjectionInflationController injectionInflater,
            @Nullable DockManager dockManager, SysuiColorExtractor colorExtractor,
            ContentResolver contentResolver, SettingsWrapper settingsWrapper) {
        mDockManager = dockManager;
        mColorExtractor = colorExtractor;
        mContentResolver = contentResolver;
        mSettingsWrapper = settingsWrapper;

        Resources res = context.getResources();
        mClockInfos.add(ClockInfo.builder()
                .setName("default")
                .setTitle(res.getString(R.string.clock_title_default))
                .setId("default")
                .setThumbnail(() -> BitmapFactory.decodeResource(res, R.drawable.default_thumbnail))
                .setPreview(() -> BitmapFactory.decodeResource(res, R.drawable.default_preview))
                .build());
        mClockInfos.add(ClockInfo.builder()
                .setName("bubble")
                .setTitle(res.getString(R.string.clock_title_bubble))
                .setId(BubbleClockController.class.getName())
                .setThumbnail(() -> BitmapFactory.decodeResource(res, R.drawable.bubble_thumbnail))
                .setPreview(() -> getClockPreview(BubbleClockController.class.getName()))
                .build());
        mClockInfos.add(ClockInfo.builder()
                .setName("stretch")
                .setTitle(res.getString(R.string.clock_title_stretch))
                .setId(StretchAnalogClockController.class.getName())
                .setThumbnail(() -> BitmapFactory.decodeResource(res, R.drawable.stretch_thumbnail))
                .setPreview(() -> getClockPreview(StretchAnalogClockController.class.getName()))
                .build());
        mClockInfos.add(ClockInfo.builder()
                .setName("type")
                .setTitle(res.getString(R.string.clock_title_type))
                .setId(TypeClockController.class.getName())
                .setThumbnail(() -> BitmapFactory.decodeResource(res, R.drawable.type_thumbnail))
                .setPreview(() -> getClockPreview(TypeClockController.class.getName()))
                .build());

        LayoutInflater layoutInflater = injectionInflater.injectable(LayoutInflater.from(context));
        mClocks.put(BubbleClockController.class.getName(),
                () -> BubbleClockController.build(layoutInflater));
        mClocks.put(StretchAnalogClockController.class.getName(),
                () -> StretchAnalogClockController.build(layoutInflater));
        mClocks.put(TypeClockController.class.getName(),
                () -> TypeClockController.build(layoutInflater));

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

    /**
     * Generate a realistic preview of a clock face.
     * @param clockId ID of clock to use for preview, should be obtained from {@link getClockInfos}.
     *        Returns null if clockId is not found.
     */
    @Nullable
    private Bitmap getClockPreview(String clockId) {
        Supplier<ClockPlugin> supplier = mClocks.get(clockId);
        if (supplier == null) {
            return null;
        }
        ClockPlugin plugin = supplier.get();

        // Use the big clock view for the preview
        View clockView = plugin.getBigClockView();
        if (clockView == null) {
            return null;
        }

        // Initialize state of plugin before generating preview.
        plugin.setDarkAmount(1f);
        plugin.setTextColor(Color.WHITE);

        ColorExtractor.GradientColors colors = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK,
                true);
        plugin.setColorPalette(colors.supportsDarkText(), colors.getColorPalette());
        plugin.dozeTimeTick();

        // Draw clock view hierarchy to canvas.
        Bitmap bitmap = Bitmap.createBitmap(mWidth, mHeight, Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        clockView.measure(MeasureSpec.makeMeasureSpec(mWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(mHeight, MeasureSpec.EXACTLY));
        clockView.layout(0, 0, mWidth, mHeight);
        canvas.drawColor(Color.BLACK);
        clockView.draw(canvas);

        return bitmap;
    }

    private void notifyClockChanged(ClockPlugin plugin) {
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
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOCKED_CLOCK_FACE),
                false, mContentObserver);
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
        }
    }

    private void unregister() {
        mContentResolver.unregisterContentObserver(mContentObserver);
        if (mDockManager != null) {
            mDockManager.removeListener(mDockEventListener);
        }
    }

    private void reload() {
        mCurrentClock = getClockPlugin();
        notifyClockChanged(mCurrentClock);
    }

    private ClockPlugin getClockPlugin() {
        ClockPlugin plugin = null;
        if (mIsDocked) {
            final String name = mSettingsWrapper.getDockedClockFace();
            if (name != null) {
                Supplier<ClockPlugin> supplier = mClocks.get(name);
                if (supplier != null) {
                    plugin = supplier.get();
                    if (plugin != null) {
                        return plugin;
                    }
                }
            }
        }
        final String name = mSettingsWrapper.getLockScreenCustomClockFace();
        if (name != null) {
            Supplier<ClockPlugin> supplier = mClocks.get(name);
            if (supplier != null) {
                plugin = supplier.get();
            }
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
