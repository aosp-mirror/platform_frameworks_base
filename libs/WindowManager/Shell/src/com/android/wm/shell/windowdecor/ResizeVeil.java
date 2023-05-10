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
    private static final int RESIZE_ALPHA_DURATION = 200;
    private final Context mContext;
    private final Supplier<SurfaceControl.Builder> mSurfaceControlBuilderSupplier;
    private final Supplier<SurfaceControl.Transaction> mSurfaceControlTransactionSupplier;
    private final Drawable mAppIcon;
    private SurfaceControl mParentSurface;
    private SurfaceControl mVeilSurface;
    private final RunningTaskInfo mTaskInfo;
    private SurfaceControlViewHost mViewHost;
    private final Display mDisplay;

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

        final ImageView appIcon = mViewHost.getView().findViewById(R.id.veil_application_icon);
        appIcon.setImageDrawable(mAppIcon);
    }

    /**
     * Animate veil's alpha to 1, fading it in.
     */
    public void showVeil(SurfaceControl parentSurface) {
        // Parent surface can change, ensure it is up to date.
        SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        if (!parentSurface.equals(mParentSurface)) {
            t.reparent(mVeilSurface, parentSurface);
            mParentSurface = parentSurface;
        }

        int backgroundColorId = getBackgroundColorId();
        mViewHost.getView().setBackgroundColor(mContext.getColor(backgroundColorId));

        t.show(mVeilSurface)
                .apply();
        final ValueAnimator animator = new ValueAnimator();
        animator.setFloatValues(0f, 1f);
        animator.setDuration(RESIZE_ALPHA_DURATION);
        animator.addUpdateListener(animation -> {
            t.setAlpha(mVeilSurface, animator.getAnimatedFraction());
            t.apply();
        });
        animator.start();
    }

    /**
     * Update veil bounds to match bounds changes.
     * @param newBounds bounds to update veil to.
     */
    public void relayout(Rect newBounds) {
        SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        mViewHost.relayout(newBounds.width(), newBounds.height());
        t.setWindowCrop(mVeilSurface, newBounds.width(), newBounds.height());
        t.setPosition(mParentSurface, newBounds.left, newBounds.top);
        t.setWindowCrop(mParentSurface, newBounds.width(), newBounds.height());
        mViewHost.getView().getViewRootImpl().applyTransactionOnDraw(t);
    }

    /**
     * Animate veil's alpha to 0, fading it out.
     */
    public void hideVeil() {
        final View resizeVeilView = mViewHost.getView();
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
