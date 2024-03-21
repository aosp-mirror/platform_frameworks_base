/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.ColorRes;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.widget.ImageView;
import android.window.TaskConstants;

import com.android.wm.shell.R;
import com.android.wm.shell.common.SurfaceUtils;

import java.util.function.Supplier;

/**
 * Creates and updates a veil that covers task contents on resize.
 */
public class ResizeVeil {
    private static final int RESIZE_ALPHA_DURATION = 100;

    private static final int VEIL_CONTAINER_LAYER = TaskConstants.TASK_CHILD_LAYER_RESIZE_VEIL;
    /** The background is a child of the veil container layer and goes at the bottom. */
    private static final int VEIL_BACKGROUND_LAYER = 0;
    /** The icon is a child of the veil container layer and goes in front of the background. */
    private static final int VEIL_ICON_LAYER = 1;

    private final Context mContext;
    private final Supplier<SurfaceControl.Builder> mSurfaceControlBuilderSupplier;
    private final Supplier<SurfaceControl.Transaction> mSurfaceControlTransactionSupplier;
    private final SurfaceSession mSurfaceSession = new SurfaceSession();
    private final Drawable mAppIcon;
    private ImageView mIconView;
    private int mIconSize;
    private SurfaceControl mParentSurface;

    /** A container surface to host the veil background and icon child surfaces. */
    private SurfaceControl mVeilSurface;
    /** A color surface for the veil background. */
    private SurfaceControl mBackgroundSurface;
    /** A surface that hosts a windowless window with the app icon. */
    private SurfaceControl mIconSurface;

    private final RunningTaskInfo mTaskInfo;
    private SurfaceControlViewHost mViewHost;
    private final Display mDisplay;
    private ValueAnimator mVeilAnimator;

    public ResizeVeil(Context context, Drawable appIcon, RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier, Display display,
            Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier) {
        mContext = context;
        mAppIcon = appIcon;
        mSurfaceControlBuilderSupplier = surfaceControlBuilderSupplier;
        mSurfaceControlTransactionSupplier = surfaceControlTransactionSupplier;
        mTaskInfo = taskInfo;
        mParentSurface = taskSurface;
        mDisplay = display;
        setupResizeVeil();
    }

    /**
     * Create the veil in its default invisible state.
     */
    private void setupResizeVeil() {
        mVeilSurface = mSurfaceControlBuilderSupplier.get()
                .setContainerLayer()
                .setName("Resize veil of Task=" + mTaskInfo.taskId)
                .setHidden(true)
                .setParent(mParentSurface)
                .setCallsite("ResizeVeil#setupResizeVeil")
                .build();
        mBackgroundSurface = SurfaceUtils.makeColorLayer(mVeilSurface,
                "Resize veil background of Task=" + mTaskInfo.taskId, mSurfaceSession);
        mIconSurface = mSurfaceControlBuilderSupplier.get()
                .setName("Resize veil icon of Task= " + mTaskInfo.taskId)
                .setContainerLayer()
                .setParent(mVeilSurface)
                .setHidden(true)
                .setCallsite("ResizeVeil#setupResizeVeil")
                .build();

        mIconSize = mContext.getResources()
                .getDimensionPixelSize(R.dimen.desktop_mode_resize_veil_icon_size);
        final View root = LayoutInflater.from(mContext)
                .inflate(R.layout.desktop_mode_resize_veil, null /* root */);
        mIconView = root.findViewById(R.id.veil_application_icon);
        mIconView.setImageDrawable(mAppIcon);

        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(
                        mIconSize,
                        mIconSize,
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSPARENT);
        lp.setTitle("Resize veil icon window of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();

        final WindowlessWindowManager wwm = new WindowlessWindowManager(mTaskInfo.configuration,
                mIconSurface, null /* hostInputToken */);
        mViewHost = new SurfaceControlViewHost(mContext, mDisplay, wwm, "ResizeVeil");
        mViewHost.setView(root, lp);
    }

    /**
     * Shows the veil surface/view.
     *
     * @param t the transaction to apply in sync with the veil draw
     * @param parentSurface the surface that the veil should be a child of
     * @param taskBounds the bounds of the task that owns the veil
     * @param fadeIn if true, the veil will fade-in with an animation, if false, it will be shown
     *               immediately
     */
    public void showVeil(SurfaceControl.Transaction t, SurfaceControl parentSurface,
            Rect taskBounds, boolean fadeIn) {
        // Parent surface can change, ensure it is up to date.
        if (!parentSurface.equals(mParentSurface)) {
            t.reparent(mVeilSurface, parentSurface);
            mParentSurface = parentSurface;
        }

        t.show(mVeilSurface);
        t.setLayer(mVeilSurface, VEIL_CONTAINER_LAYER);
        t.setLayer(mIconSurface, VEIL_ICON_LAYER);
        t.setLayer(mBackgroundSurface, VEIL_BACKGROUND_LAYER);
        t.setColor(mBackgroundSurface,
                Color.valueOf(mContext.getColor(getBackgroundColorId())).getComponents());

        relayout(taskBounds, t);
        if (fadeIn) {
            cancelAnimation();
            final SurfaceControl.Transaction veilAnimT = mSurfaceControlTransactionSupplier.get();
            mVeilAnimator = new ValueAnimator();
            mVeilAnimator.setFloatValues(0f, 1f);
            mVeilAnimator.setDuration(RESIZE_ALPHA_DURATION);
            mVeilAnimator.addUpdateListener(animation -> {
                veilAnimT.setAlpha(mBackgroundSurface, mVeilAnimator.getAnimatedFraction());
                veilAnimT.apply();
            });
            mVeilAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    veilAnimT.show(mBackgroundSurface)
                            .setAlpha(mBackgroundSurface, 0)
                            .apply();
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    veilAnimT.setAlpha(mBackgroundSurface, 1).apply();
                }
            });

            final SurfaceControl.Transaction iconAnimT = mSurfaceControlTransactionSupplier.get();
            final ValueAnimator iconAnimator = new ValueAnimator();
            iconAnimator.setFloatValues(0f, 1f);
            iconAnimator.setDuration(RESIZE_ALPHA_DURATION);
            iconAnimator.addUpdateListener(animation -> {
                iconAnimT.setAlpha(mIconSurface, animation.getAnimatedFraction());
                iconAnimT.apply();
            });
            iconAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    iconAnimT.show(mIconSurface)
                            .setAlpha(mIconSurface, 0)
                            .apply();
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    iconAnimT.setAlpha(mIconSurface, 1).apply();
                }
            });
            // Let the animators show it with the correct alpha value once the animation starts.
            t.hide(mIconSurface);
            t.hide(mBackgroundSurface);
            t.apply();

            mVeilAnimator.start();
            iconAnimator.start();
        } else {
            // Show the veil immediately.
            t.show(mIconSurface);
            t.show(mBackgroundSurface);
            t.setAlpha(mIconSurface, 1);
            t.setAlpha(mBackgroundSurface, 1);
            t.apply();
        }
    }

    /**
     * Animate veil's alpha to 1, fading it in.
     */
    public void showVeil(SurfaceControl parentSurface, Rect taskBounds) {
        SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        showVeil(t, parentSurface, taskBounds, true /* fadeIn */);
    }

    /**
     * Update veil bounds to match bounds changes.
     * @param newBounds bounds to update veil to.
     */
    private void relayout(Rect newBounds, SurfaceControl.Transaction t) {
        t.setWindowCrop(mVeilSurface, newBounds.width(), newBounds.height());
        final PointF iconPosition = calculateAppIconPosition(newBounds);
        t.setPosition(mIconSurface, iconPosition.x, iconPosition.y);
        t.setPosition(mParentSurface, newBounds.left, newBounds.top);
        t.setWindowCrop(mParentSurface, newBounds.width(), newBounds.height());
    }

    /**
     * Calls relayout to update task and veil bounds.
     * @param newBounds bounds to update veil to.
     */
    public void updateResizeVeil(Rect newBounds) {
        SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        updateResizeVeil(t, newBounds);
    }

    /**
     * Calls relayout to update task and veil bounds.
     * Finishes veil fade in if animation is currently running; this is to prevent empty space
     * being visible behind the transparent veil during a fast resize.
     *
     * @param t a transaction to be applied in sync with the veil draw.
     * @param newBounds bounds to update veil to.
     */
    public void updateResizeVeil(SurfaceControl.Transaction t, Rect newBounds) {
        if (mVeilAnimator != null && mVeilAnimator.isStarted()) {
            mVeilAnimator.removeAllUpdateListeners();
            mVeilAnimator.end();
        }
        relayout(newBounds, t);
        t.apply();
    }

    /**
     * Animate veil's alpha to 0, fading it out.
     */
    public void hideVeil() {
        cancelAnimation();
        mVeilAnimator = new ValueAnimator();
        mVeilAnimator.setFloatValues(1, 0);
        mVeilAnimator.setDuration(RESIZE_ALPHA_DURATION);
        mVeilAnimator.addUpdateListener(animation -> {
            SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
            t.setAlpha(mBackgroundSurface, 1 - mVeilAnimator.getAnimatedFraction());
            t.setAlpha(mIconSurface, 1 - mVeilAnimator.getAnimatedFraction());
            t.apply();
        });
        mVeilAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
                t.hide(mBackgroundSurface);
                t.hide(mIconSurface);
                t.apply();
            }
        });
        mVeilAnimator.start();
    }

    @ColorRes
    private int getBackgroundColorId() {
        Configuration configuration = mContext.getResources().getConfiguration();
        if ((configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES) {
            return R.color.desktop_mode_resize_veil_dark;
        } else {
            return R.color.desktop_mode_resize_veil_light;
        }
    }

    private PointF calculateAppIconPosition(Rect parentBounds) {
        return new PointF((float) parentBounds.width() / 2 - (float) mIconSize / 2,
                (float) parentBounds.height() / 2 - (float) mIconSize / 2);
    }

    private void cancelAnimation() {
        if (mVeilAnimator != null) {
            mVeilAnimator.removeAllUpdateListeners();
            mVeilAnimator.cancel();
        }
    }

    /**
     * Dispose of veil when it is no longer needed, likely on close of its container decor.
     */
    void dispose() {
        cancelAnimation();
        mVeilAnimator = null;

        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }
        final SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        if (mBackgroundSurface != null) {
            t.remove(mBackgroundSurface);
            mBackgroundSurface = null;
        }
        if (mIconSurface != null) {
            t.remove(mIconSurface);
            mIconSurface = null;
        }
        if (mVeilSurface != null) {
            t.remove(mVeilSurface);
            mVeilSurface = null;
        }
        t.apply();
    }
}
