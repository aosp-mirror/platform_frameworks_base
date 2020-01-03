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

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.types.ExtractionType;
import com.android.internal.colorextraction.types.Tonal;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
    private final Tonal mTonal;
    private boolean mHasMediaArtwork;
    private final GradientColors mNeutralColorsLock;
    private final GradientColors mBackdropColors;

    @Inject
    public SysuiColorExtractor(Context context, ConfigurationController configurationController) {
        this(context, new Tonal(context), configurationController,
                context.getSystemService(WallpaperManager.class), false /* immediately */);
    }

    @VisibleForTesting
    public SysuiColorExtractor(Context context, ExtractionType type,
            ConfigurationController configurationController,
            WallpaperManager wallpaperManager, boolean immediately) {
        super(context, type, immediately, wallpaperManager);
        mTonal = type instanceof Tonal ? (Tonal) type : new Tonal(context);
        mNeutralColorsLock = new GradientColors();
        configurationController.addCallback(this);

        mBackdropColors = new GradientColors();
        mBackdropColors.setMainColor(Color.BLACK);

        // Listen to all users instead of only the current one.
        wallpaperManager.removeOnColorsChangedListener(this);
        wallpaperManager.addOnColorsChangedListener(this, null /* handler */,
                UserHandle.USER_ALL);
    }

    @Override
    protected void extractWallpaperColors() {
        super.extractWallpaperColors();
        // mTonal is final but this method will be invoked by the base class during its ctor.
        if (mTonal == null || mNeutralColorsLock == null) {
            return;
        }
        mTonal.applyFallback(mLockColors == null ? mSystemColors : mLockColors, mNeutralColorsLock);
    }

    @Override
    public void onColorsChanged(WallpaperColors colors, int which, int userId) {
        if (userId != KeyguardUpdateMonitor.getCurrentUser()) {
            // Colors do not belong to current user, ignoring.
            return;
        }
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            mTonal.applyFallback(colors, mNeutralColorsLock);
        }
        super.onColorsChanged(colors, which);
    }

    @Override
    public void onUiModeChanged() {
        extractWallpaperColors();
        triggerColorsChanged(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }

    @Override
    public GradientColors getColors(int which, int type) {
        if (mHasMediaArtwork && (which & WallpaperManager.FLAG_LOCK) != 0) {
            return mBackdropColors;
        }
        return super.getColors(which, type);
    }

    /**
     * Colors that should be using for scrims.
     *
     * They will be:
     * - A light gray if the wallpaper is light
     * - A dark gray if the wallpaper is very dark or we're in night mode.
     * - Black otherwise
     */
    public GradientColors getNeutralColors() {
        return mHasMediaArtwork ? mBackdropColors : mNeutralColorsLock;
    }

    public void setHasMediaArtwork(boolean hasBackdrop) {
        if (mHasMediaArtwork != hasBackdrop) {
            mHasMediaArtwork = hasBackdrop;
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
        pw.println("  Neutral colors: " + mNeutralColorsLock);
        pw.println("  Has media backdrop: " + mHasMediaArtwork);

    }
}
