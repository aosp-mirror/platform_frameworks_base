/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.colorextraction;

import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.types.ExtractionType;
import com.android.internal.colorextraction.types.Tonal;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * ColorExtractor aware of wallpaper visibility
 */
@Singleton
public class SysuiColorExtractor extends ColorExtractor implements Dumpable,
        ConfigurationController.ConfigurationListener {
    private static final String TAG = "SysuiColorExtractor";

    public static final int SCRIM_TYPE_REGULAR = 1;
    public static final int SCRIM_TYPE_LIGHT = 2;
    public static final int SCRIM_TYPE_DARK = 3;

    @IntDef(prefix = {"SCRIM_TYPE_"}, value = {
            SCRIM_TYPE_REGULAR,
            SCRIM_TYPE_LIGHT,
            SCRIM_TYPE_DARK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScrimType {
    }

    private final Tonal mTonal;
    private final OverviewProxyService mOverviewProxyService;
    private boolean mWallpaperVisible;
    private boolean mHasBackdrop;
    // Colors to return when the wallpaper isn't visible
    private final GradientColors mWpHiddenColors;

    @Inject
    public SysuiColorExtractor(Context context, ConfigurationController configurationController,
            OverviewProxyService overviewProxyService) {
        this(context, new Tonal(context), configurationController, true, overviewProxyService);
    }

    @VisibleForTesting
    public SysuiColorExtractor(Context context, ExtractionType type,
            ConfigurationController configurationController, boolean registerVisibility,
            OverviewProxyService overviewProxyService) {
        super(context, type, false /* immediately */);
        mTonal = type instanceof Tonal ? (Tonal) type : new Tonal(context);
        mWpHiddenColors = new GradientColors();
        mOverviewProxyService = overviewProxyService;
        configurationController.addCallback(this);

        WallpaperColors systemColors = getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        updateDefaultGradients(systemColors);

        if (registerVisibility) {
            try {
                IWindowManager windowManagerService = WindowManagerGlobal.getWindowManagerService();
                Handler handler = Handler.getMain();
                boolean visible = windowManagerService.registerWallpaperVisibilityListener(
                        new IWallpaperVisibilityListener.Stub() {
                            @Override
                            public void onWallpaperVisibilityChanged(boolean newVisibility,
                                    int displayId) throws RemoteException {
                                handler.post(() -> setWallpaperVisible(newVisibility));
                            }
                        }, Display.DEFAULT_DISPLAY);
                setWallpaperVisible(visible);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't listen to wallpaper visibility changes", e);
            }
        }

        WallpaperManager wallpaperManager = context.getSystemService(WallpaperManager.class);
        if (wallpaperManager != null) {
            // Listen to all users instead of only the current one.
            wallpaperManager.removeOnColorsChangedListener(this);
            wallpaperManager.addOnColorsChangedListener(this, null /* handler */,
                    UserHandle.USER_ALL);
        }
    }

    private void updateDefaultGradients(WallpaperColors colors) {
        mTonal.applyFallback(colors, mWpHiddenColors);
    }

    @Override
    public void onColorsChanged(WallpaperColors colors, int which, int userId) {
        if (userId != KeyguardUpdateMonitor.getCurrentUser()) {
            // Colors do not belong to current user, ignoring.
            return;
        }

        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
            updateDefaultGradients(colors);
        }
        super.onColorsChanged(colors, which);
    }

    @Override
    public void onUiModeChanged() {
        WallpaperColors systemColors = getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        updateDefaultGradients(systemColors);
        triggerColorsChanged(WallpaperManager.FLAG_SYSTEM);
    }

    @Override
    protected void triggerColorsChanged(int which) {
        super.triggerColorsChanged(which);

        if (mWpHiddenColors != null && (which & WallpaperManager.FLAG_SYSTEM) != 0) {
            @ColorInt int colorInt = mWpHiddenColors.getMainColor();
            @ScrimType int scrimType;
            if (colorInt == Tonal.MAIN_COLOR_LIGHT) {
                scrimType = SCRIM_TYPE_LIGHT;
            } else if (colorInt == Tonal.MAIN_COLOR_DARK) {
                scrimType = SCRIM_TYPE_DARK;
            } else {
                scrimType = SCRIM_TYPE_REGULAR;
            }
            mOverviewProxyService.onScrimColorsChanged(colorInt, scrimType);
        }
    }

    /**
     * Colors the should be using for scrims.
     *
     * They will be:
     * - A light gray if the wallpaper is light
     * - A dark gray if the wallpaper is very dark or we're in night mode.
     * - Black otherwise
     */
    public GradientColors getNeutralColors() {
        return mWpHiddenColors;
    }

    /**
     * Get TYPE_NORMAL colors when wallpaper is visible, or fallback otherwise.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @return colors
     */
    @Override
    public GradientColors getColors(int which) {
        return getColors(which, TYPE_DARK);
    }

    /**
     * Wallpaper colors when the wallpaper is visible, fallback otherwise.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @param type TYPE_NORMAL, TYPE_DARK or TYPE_EXTRA_DARK
     * @return colors
     */
    @Override
    public GradientColors getColors(int which, int type) {
        return getColors(which, type, false /* ignoreVisibility */);
    }

    /**
     * Get TYPE_NORMAL colors, possibly ignoring wallpaper visibility.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @param ignoreWallpaperVisibility whether you want fallback colors or not if the wallpaper
     *                                  isn't visible
     * @return
     */
    public GradientColors getColors(int which, boolean ignoreWallpaperVisibility) {
        return getColors(which, TYPE_NORMAL, ignoreWallpaperVisibility);
    }

    /**
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @param type TYPE_NORMAL, TYPE_DARK or TYPE_EXTRA_DARK
     * @param ignoreWallpaperVisibility true if true wallpaper colors should be returning
     *                                  if it's visible or not
     * @return colors
     */
    public GradientColors getColors(int which, int type, boolean ignoreWallpaperVisibility) {
        // mWallpaperVisible only handles the "system wallpaper" and will be always set to false
        // if we have different lock and system wallpapers.
        if (which == WallpaperManager.FLAG_SYSTEM) {
            if (mWallpaperVisible || ignoreWallpaperVisibility) {
                return super.getColors(which, type);
            } else {
                return mWpHiddenColors;
            }
        } else {
            if (mHasBackdrop) {
                return mWpHiddenColors;
            } else {
                return super.getColors(which, type);
            }
        }
    }

    @VisibleForTesting
    void setWallpaperVisible(boolean visible) {
        if (mWallpaperVisible != visible) {
            mWallpaperVisible = visible;
            triggerColorsChanged(WallpaperManager.FLAG_SYSTEM);
        }
    }

    public void setHasBackdrop(boolean hasBackdrop) {
        if (mHasBackdrop != hasBackdrop) {
            mHasBackdrop = hasBackdrop;
            triggerColorsChanged(WallpaperManager.FLAG_LOCK);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SysuiColorExtractor:");

        pw.println("  Current wallpaper colors:");
        pw.println("    system: " + mSystemColors);
        pw.println("    lock: " + mLockColors);

        GradientColors[] system = mGradientColors.get(WallpaperManager.FLAG_SYSTEM);
        GradientColors[] lock = mGradientColors.get(WallpaperManager.FLAG_LOCK);
        pw.println("  Gradients:");
        pw.println("    system: " + Arrays.toString(system));
        pw.println("    lock: " + Arrays.toString(lock));
        pw.println("  Default scrim: " + mWpHiddenColors);

    }
}
