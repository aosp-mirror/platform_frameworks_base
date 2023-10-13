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
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.widget.ImageView;
import android.window.TaskConstants;

import com.android.wm.shell.R;

import java.util.function.Supplier;

/**
 * Creates and updates a veil that covers task contents on resize.
 */
public class ResizeVeil {
    private static final int RESIZE_ALPHA_DURATION = 100;
    private final Context mContext;
    private final Supplier<SurfaceControl.Builder> mSurfaceControlBuilderSupplier;
    private final Supplier<SurfaceControl.Transaction> mSurfaceControlTransactionSupplier;
    private final Drawable mAppIcon;
    private ImageView mIconView;
    private SurfaceControl mParentSurface;
    private SurfaceControl mVeilSurface;
    private final RunningTaskInfo mTaskInfo;
    private SurfaceControlViewHost mViewHost;
    private final Display mDisplay;
    private ValueAnimator mVeilAnimator;

    public ResizeVeil(Context context, Drawable appIcon, RunningTaskInfo taskInfo,
            Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier, Display display,
            Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier) {
        mContext = context;
        mAppIcon = appIcon;
        mSurfaceControlBuilderSupplier = surfaceControlBuilderSupplier;
        mSurfaceControlTransactionSupplier = surfaceControlTransactionSupplier;
        mTaskInfo = taskInfo;
        mDisplay = display;
        setupResizeVeil();
    }

    /**
     * Create the veil in its default invisible state.
     */
    private void setupResizeVeil() {
        SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
        mVeilSurface = builder
                .setName("Resize veil of Task= " + mTaskInfo.taskId)
                .setContainerLayer()
                .build();
        View v = LayoutInflater.from(mContext)
                .inflate(R.layout.desktop_mode_resize_veil, null);

        t.setPosition(mVeilSurface, 0, 0)
            .setLayer(mVeilSurface, TaskConstants.TASK_CHILD_LAYER_RESIZE_VEIL)
            .apply();
        Rect taskBounds = mTaskInfo.configuration.windowConfiguration.getBounds();
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(taskBounds.width(),
                        taskBounds.height(),
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSPARENT);
        lp.setTitle("Resize veil of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        WindowlessWindowManager windowManager = new WindowlessWindowManager(mTaskInfo.configuration,
                mVeilSurface, null /* hostInputToken */);
        mViewHost = new SurfaceControlViewHost(mContext, mDisplay, windowManager, "ResizeVeil");
        mViewHost.setView(v, lp);

        mIconView = mViewHost.getView().findViewById(R.id.veil_application_icon);
        mIconView.setImageDrawable(mAppIcon);
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

        int backgroundColorId = getBackgroundColorId();
        mViewHost.getView().setBackgroundColor(mContext.getColor(backgroundColorId));

        relayout(taskBounds, t);
        if (fadeIn) {
            mVeilAnimator = new ValueAnimator();
            mVeilAnimator.setFloatValues(0f, 1f);
            mVeilAnimator.setDuration(RESIZE_ALPHA_DURATION);
            mVeilAnimator.addUpdateListener(animation -> {
                t.setAlpha(mVeilSurface, mVeilAnimator.getAnimatedFraction());
                t.apply();
            });
            mVeilAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    t.setAlpha(mVeilSurface, 1);
                    t.apply();
                }
            });

            final ValueAnimator iconAnimator = new ValueAnimator();
            iconAnimator.setFloatValues(0f, 1f);
            iconAnimator.setDuration(RESIZE_ALPHA_DURATION);
            iconAnimator.addUpdateListener(animation -> {
                mIconView.setAlpha(animation.getAnimatedFraction());
            });

            t.show(mVeilSurface)
                    .addTransactionCommittedListener(
                            mContext.getMainExecutor(), () -> {
                                mVeilAnimator.start();
                                iconAnimator.start();
                            })
                    .setAlpha(mVeilSurface, 0);
        } else {
            // Show the veil immediately at full opacity.
            t.show(mVeilSurface).setAlpha(mVeilSurface, 1);
        }
        mViewHost.getView().getViewRootImpl().applyTransactionOnDraw(t);
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
        mViewHost.relayout(newBounds.width(), newBounds.height());
        t.setWindowCrop(mVeilSurface, newBounds.width(), newBounds.height());
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
        mViewHost.getView().getViewRootImpl().applyTransactionOnDraw(t);
    }

    /**
     * Animate veil's alpha to 0, fading it out.
     */
    public void hideVeil() {
        final ValueAnimator animator = new ValueAnimator();
        animator.setFloatValues(1, 0);
        animator.setDuration(RESIZE_ALPHA_DURATION);
        animator.addUpdateListener(animation -> {
            SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
            t.setAlpha(mVeilSurface, 1 - animator.getAnimatedFraction());
            t.apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
                t.hide(mVeilSurface);
                t.apply();
            }
        });
        animator.start();
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

    /**
     * Dispose of veil when it is no longer needed, likely on close of its container decor.
     */
    void dispose() {
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }
        if (mVeilSurface != null) {
            final SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
            t.remove(mVeilSurface);
            mVeilSurface = null;
            t.apply();
        }
    }
}
