/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.onehanded;

import static com.android.wm.shell.onehanded.OneHandedState.STATE_ACTIVE;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.animation.LinearInterpolator;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.view.ContextThemeWrapper;

import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Manages OneHanded color background layer areas.
 * To avoid when turning the Dark theme on, users can not clearly identify
 * the screen has entered one handed mode.
 */
public class OneHandedBackgroundPanelOrganizer extends DisplayAreaOrganizer
        implements OneHandedAnimationCallback, OneHandedState.OnStateChangedListener {
    private static final String TAG = "OneHandedBackgroundPanelOrganizer";
    private static final int THEME_COLOR_OFFSET = 10;
    private static final int ALPHA_ANIMATION_DURATION = 200;

    private final Context mContext;
    private final SurfaceSession mSurfaceSession = new SurfaceSession();
    private final OneHandedSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mTransactionFactory;

    private @OneHandedState.State int mCurrentState;
    private ValueAnimator mAlphaAnimator;

    private float mTranslationFraction;
    private float[] mThemeColor;

    /**
     * The background to distinguish the boundary of translated windows and empty region when
     * one handed mode triggered.
     */
    private Rect mBkgBounds;
    private Rect mStableInsets;

    @Nullable
    @VisibleForTesting
    SurfaceControl mBackgroundSurface;
    @Nullable
    private SurfaceControl mParentLeash;

    public OneHandedBackgroundPanelOrganizer(Context context, DisplayLayout displayLayout,
            OneHandedSettingsUtil settingsUtil, Executor executor) {
        super(executor);
        mContext = context;
        mTranslationFraction = settingsUtil.getTranslationFraction(context);
        mTransactionFactory = SurfaceControl.Transaction::new;
        updateThemeColors();
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        mParentLeash = leash;
    }

    @Override
    public List<DisplayAreaAppearedInfo> registerOrganizer(int displayAreaFeature) {
        final List<DisplayAreaAppearedInfo> displayAreaInfos;
        displayAreaInfos = super.registerOrganizer(displayAreaFeature);
        for (int i = 0; i < displayAreaInfos.size(); i++) {
            final DisplayAreaAppearedInfo info = displayAreaInfos.get(i);
            onDisplayAreaAppeared(info.getDisplayAreaInfo(), info.getLeash());
        }
        return displayAreaInfos;
    }

    @Override
    public void unregisterOrganizer() {
        super.unregisterOrganizer();
        removeBackgroundPanelLayer();
        mParentLeash = null;
    }

    @Override
    public void onAnimationUpdate(SurfaceControl.Transaction tx, float xPos, float yPos) {
        final int yTopPos = (mStableInsets.top - mBkgBounds.height()) + Math.round(yPos);
        tx.setPosition(mBackgroundSurface, 0, yTopPos);
    }

    @Nullable
    @VisibleForTesting
    boolean isRegistered() {
        return mParentLeash != null;
    }

    void createBackgroundSurface() {
        mBackgroundSurface = new SurfaceControl.Builder(mSurfaceSession)
                .setBufferSize(mBkgBounds.width(), mBkgBounds.height())
                .setColorLayer()
                .setFormat(PixelFormat.RGB_888)
                .setOpaque(true)
                .setName("one-handed-background-panel")
                .setCallsite("OneHandedBackgroundPanelOrganizer")
                .build();

        // TODO(185890335) Avoid Dimming for mid-range luminance wallpapers flash.
        mAlphaAnimator = ValueAnimator.ofFloat(1.0f, 0.0f);
        mAlphaAnimator.setInterpolator(new LinearInterpolator());
        mAlphaAnimator.setDuration(ALPHA_ANIMATION_DURATION);
        mAlphaAnimator.addUpdateListener(
                animator -> detachBackgroundFromParent(animator));
    }

    void detachBackgroundFromParent(ValueAnimator animator) {
        if (mBackgroundSurface == null || mParentLeash == null) {
            return;
        }
        // TODO(185890335) Avoid Dimming for mid-range luminance wallpapers flash.
        final float currentValue = (float) animator.getAnimatedValue();
        final SurfaceControl.Transaction tx = mTransactionFactory.getTransaction();
        if (currentValue == 0.0f) {
            tx.reparent(mBackgroundSurface, null).apply();
        } else {
            tx.setAlpha(mBackgroundSurface, (float) animator.getAnimatedValue()).apply();
        }
    }

    /**
     * Called when onDisplayAdded() or onDisplayRemoved() callback.
     *
     * @param displayLayout The latest {@link DisplayLayout} representing current displayId
     */
    public void onDisplayChanged(DisplayLayout displayLayout) {
        mStableInsets = displayLayout.stableInsets();
        // Ensure the mBkgBounds is portrait, due to OHM only support on portrait
        if (displayLayout.height() > displayLayout.width()) {
            mBkgBounds = new Rect(0, 0, displayLayout.width(),
                    Math.round(displayLayout.height() * mTranslationFraction) + mStableInsets.top);
        } else {
            mBkgBounds = new Rect(0, 0, displayLayout.height(),
                    Math.round(displayLayout.width() * mTranslationFraction) + mStableInsets.top);
        }
    }

    @VisibleForTesting
    void onStart() {
        if (mBackgroundSurface == null) {
            createBackgroundSurface();
        }
        showBackgroundPanelLayer();
    }

    /**
     * Called when transition finished.
     */
    public void onStopFinished() {
        if (mAlphaAnimator == null) {
            return;
        }
        mAlphaAnimator.start();
    }

    @VisibleForTesting
    void showBackgroundPanelLayer() {
        if (mParentLeash == null) {
            return;
        }

        if (mBackgroundSurface == null) {
            createBackgroundSurface();
        }

        // TODO(185890335) Avoid Dimming for mid-range luminance wallpapers flash.
        if (mAlphaAnimator.isRunning()) {
            mAlphaAnimator.end();
        }

        mTransactionFactory.getTransaction()
                .reparent(mBackgroundSurface, mParentLeash)
                .setAlpha(mBackgroundSurface, 1.0f)
                .setLayer(mBackgroundSurface, -1 /* at bottom-most layer */)
                .setColor(mBackgroundSurface, mThemeColor)
                .show(mBackgroundSurface)
                .apply();
    }

    @VisibleForTesting
    void removeBackgroundPanelLayer() {
        if (mBackgroundSurface == null) {
            return;
        }

        mTransactionFactory.getTransaction()
                .remove(mBackgroundSurface)
                .apply();
        mBackgroundSurface = null;
    }

    /**
     * onConfigurationChanged events for updating tutorial text.
     */
    public void onConfigurationChanged() {
        updateThemeColors();

        if (mCurrentState != STATE_ACTIVE) {
            return;
        }
        showBackgroundPanelLayer();
    }

    private void updateThemeColors() {
        final Context themedContext = new ContextThemeWrapper(mContext,
                com.android.internal.R.style.Theme_DeviceDefault_DayNight);
        final int themeColor = themedContext.getColor(
                R.color.one_handed_tutorial_background_color);
        mThemeColor = new float[]{
                adjustColor(Color.red(themeColor)),
                adjustColor(Color.green(themeColor)),
                adjustColor(Color.blue(themeColor))};
    }

    private float adjustColor(int origColor) {
        return Math.max(origColor - THEME_COLOR_OFFSET, 0) / 255.0f;
    }

    @Override
    public void onStateChanged(int newState) {
        mCurrentState = newState;
    }

    void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG);
        pw.print(innerPrefix + "mBackgroundSurface=");
        pw.println(mBackgroundSurface);
        pw.print(innerPrefix + "mBkgBounds=");
        pw.println(mBkgBounds);
        pw.print(innerPrefix + "mThemeColor=");
        pw.println(mThemeColor);
        pw.print(innerPrefix + "mTranslationFraction=");
        pw.println(mTranslationFraction);
    }
}
