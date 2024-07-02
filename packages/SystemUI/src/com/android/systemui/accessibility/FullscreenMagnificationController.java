/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static android.view.WindowManager.LayoutParams;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.UiContext;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.Display;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.util.leak.RotationUtils;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

class FullscreenMagnificationController implements ComponentCallbacks {

    private static final String TAG = "FullscreenMagnificationController";
    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;
    private final WindowManager mWindowManager;
    private final IWindowManager mIWindowManager;
    private Supplier<SurfaceControlViewHost> mScvhSupplier;
    private SurfaceControlViewHost mSurfaceControlViewHost = null;
    private SurfaceControl mBorderSurfaceControl = null;
    private Rect mWindowBounds;
    private SurfaceControl.Transaction mTransaction;
    private View mFullscreenBorder = null;
    private int mBorderOffset;
    private final int mDisplayId;
    private static final Region sEmptyRegion = new Region();
    private ValueAnimator mShowHideBorderAnimator;
    private Handler mHandler;
    private Executor mExecutor;
    private boolean mFullscreenMagnificationActivated = false;
    private final Configuration mConfiguration;
    private final Runnable mShowBorderRunnable = this::showBorderWithNullCheck;
    private int mRotation;
    private final IRotationWatcher mRotationWatcher = new IRotationWatcher.Stub() {
        @Override
        public void onRotationChanged(final int rotation) {
            handleScreenRotation();
        }
    };
    private final long mLongAnimationTimeMs;

    FullscreenMagnificationController(
            @UiContext Context context,
            @Main Handler handler,
            @Main Executor executor,
            AccessibilityManager accessibilityManager,
            WindowManager windowManager,
            IWindowManager iWindowManager,
            Supplier<SurfaceControlViewHost> scvhSupplier) {
        this(context, handler, executor, accessibilityManager,
                windowManager, iWindowManager, scvhSupplier,
                new SurfaceControl.Transaction(), null);
    }

    @VisibleForTesting
    FullscreenMagnificationController(
            @UiContext Context context,
            @Main Handler handler,
            @Main Executor executor,
            AccessibilityManager accessibilityManager,
            WindowManager windowManager,
            IWindowManager iWindowManager,
            Supplier<SurfaceControlViewHost> scvhSupplier,
            SurfaceControl.Transaction transaction,
            ValueAnimator valueAnimator) {
        mContext = context;
        mHandler = handler;
        mExecutor = executor;
        mAccessibilityManager = accessibilityManager;
        mWindowManager = windowManager;
        mIWindowManager = iWindowManager;
        mWindowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        mTransaction = transaction;
        mScvhSupplier = scvhSupplier;
        mBorderOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnifier_border_width_fullscreen_with_offset)
                - mContext.getResources().getDimensionPixelSize(
                R.dimen.magnifier_border_width_fullscreen);
        mDisplayId = mContext.getDisplayId();
        mConfiguration = new Configuration(context.getResources().getConfiguration());
        mLongAnimationTimeMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_longAnimTime);
        mShowHideBorderAnimator = (valueAnimator == null)
                ? createNullTargetObjectAnimator() : valueAnimator;
        mShowHideBorderAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation, boolean isReverse) {
                if (isReverse) {
                    // The animation was played in reverse, which means we are hiding the border.
                    // We would like to perform clean up after the border is fully hidden.
                    cleanUpBorder();
                }
            }
        });
    }

    private ValueAnimator createNullTargetObjectAnimator() {
        final ValueAnimator valueAnimator =
                ObjectAnimator.ofFloat(/* target= */ null, View.ALPHA, 0f, 1f);
        Interpolator interpolator = new AccelerateDecelerateInterpolator();

        valueAnimator.setInterpolator(interpolator);
        valueAnimator.setDuration(mLongAnimationTimeMs);
        return valueAnimator;
    }

    /**
     * Check the fullscreen magnification activation status, and proceed corresponding actions when
     * there is an activation change.
     */
    @UiThread
    void onFullscreenMagnificationActivationChanged(boolean activated) {
        final boolean changed = (mFullscreenMagnificationActivated != activated);
        if (changed) {
            mFullscreenMagnificationActivated = activated;
            if (activated) {
                createFullscreenMagnificationBorder();
            } else {
                removeFullscreenMagnificationBorder();
            }
        }
    }

    /**
     * This method should only be called when fullscreen magnification is changed from activated
     * to inactivated.
     */
    @UiThread
    private void removeFullscreenMagnificationBorder() {
        if (mHandler.hasCallbacks(mShowBorderRunnable)) {
            mHandler.removeCallbacks(mShowBorderRunnable);
        }
        mContext.unregisterComponentCallbacks(this);

        mShowHideBorderAnimator.reverse();
    }

    private void cleanUpBorder() {
        if (mSurfaceControlViewHost != null) {
            mSurfaceControlViewHost.release();
            mSurfaceControlViewHost = null;
        }

        if (mFullscreenBorder != null) {
            mFullscreenBorder = null;
            try {
                mIWindowManager.removeRotationWatcher(mRotationWatcher);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove rotation watcher", e);
            }
        }
    }

    /**
     * This method should only be called when fullscreen magnification is changed from inactivated
     * to activated.
     */
    @UiThread
    private void createFullscreenMagnificationBorder() {
        onConfigurationChanged(mContext.getResources().getConfiguration());
        mContext.registerComponentCallbacks(this);

        if (mSurfaceControlViewHost == null) {
            // Create the view only if it does not exist yet. If we are trying to enable fullscreen
            // magnification before it was fully disabled, we use the previous view instead of
            // creating a new one.
            mFullscreenBorder = LayoutInflater.from(mContext)
                    .inflate(R.layout.fullscreen_magnification_border, null);
            // Set the initial border view alpha manually so we won't show the border accidentally
            // after we apply show() to the SurfaceControl and before the animation starts to run.
            mFullscreenBorder.setAlpha(0f);
            mShowHideBorderAnimator.setTarget(mFullscreenBorder);
            mSurfaceControlViewHost = mScvhSupplier.get();
            mSurfaceControlViewHost.setView(mFullscreenBorder, getBorderLayoutParams());
            mBorderSurfaceControl = mSurfaceControlViewHost.getSurfacePackage().getSurfaceControl();
            try {
                mIWindowManager.watchRotation(mRotationWatcher, Display.DEFAULT_DISPLAY);
            } catch (Exception e) {
                Log.w(TAG, "Failed to register rotation watcher", e);
            }
        }

        mTransaction
                .addTransactionCommittedListener(
                        mExecutor,
                        () -> {
                            if (mShowHideBorderAnimator.isRunning()) {
                                // Since the method is only called when there is an activation
                                // status change, the running animator is hiding the border.
                                mShowHideBorderAnimator.reverse();
                            } else {
                                mShowHideBorderAnimator.start();
                            }
                        })
                .setPosition(mBorderSurfaceControl, -mBorderOffset, -mBorderOffset)
                .setLayer(mBorderSurfaceControl, Integer.MAX_VALUE)
                .show(mBorderSurfaceControl)
                .apply();

        mAccessibilityManager.attachAccessibilityOverlayToDisplay(
                mDisplayId, mBorderSurfaceControl);

        applyTouchableRegion();
    }

    /**
     * Since the device corners are not perfectly rounded, we would like to create a thick stroke,
     * and set negative offset to the border view to fill up the spaces between the border and the
     * device corners.
     */
    private LayoutParams getBorderLayoutParams() {
        LayoutParams params =  new LayoutParams(
                mWindowBounds.width() + 2 * mBorderOffset,
                mWindowBounds.height() + 2 * mBorderOffset,
                LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                LayoutParams.FLAG_NOT_TOUCH_MODAL | LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.setTrustedOverlay();
        return params;
    }

    private void applyTouchableRegion() {
        // Sometimes this can get posted and run after deleteWindowMagnification() is called.
        if (mFullscreenBorder == null) return;

        AttachedSurfaceControl surfaceControl = mSurfaceControlViewHost.getRootSurfaceControl();

        // The touchable region of the mFullscreenBorder will be empty since we are going to allow
        // all touch events to go through this view.
        surfaceControl.setTouchableRegion(sEmptyRegion);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        final int configDiff = newConfig.diff(mConfiguration);
        mConfiguration.setTo(newConfig);
        onConfigurationChanged(configDiff);
    }

    @VisibleForTesting
    void onConfigurationChanged(int configDiff) {
        boolean reCreateWindow = false;
        if ((configDiff & ActivityInfo.CONFIG_DENSITY) != 0
                || (configDiff & ActivityInfo.CONFIG_SCREEN_SIZE) != 0
                || (configDiff & ActivityInfo.CONFIG_ORIENTATION) != 0) {
            updateDimensions();
            mWindowBounds.set(mWindowManager.getCurrentWindowMetrics().getBounds());
            reCreateWindow = true;
        }

        if (mFullscreenBorder == null) {
            return;
        }

        if (reCreateWindow) {
            final int newWidth = mWindowBounds.width() + 2 * mBorderOffset;
            final int newHeight = mWindowBounds.height() + 2 * mBorderOffset;
            mSurfaceControlViewHost.relayout(newWidth, newHeight);
        }

        // Rotating from Landscape to ReverseLandscape will not trigger the config changes in
        // CONFIG_SCREEN_SIZE and CONFIG_ORIENTATION. Therefore, we would like to check the device
        // rotation separately.
        // Since there's a possibility that {@link onConfigurationChanged} comes before
        // {@link onRotationChanged}, we would like to handle screen rotation in either case that
        // happens earlier.
        int newRotation = RotationUtils.getRotation(mContext);
        if (newRotation != mRotation) {
            mRotation = newRotation;
            handleScreenRotation();
        }
    }

    private boolean isActivated() {
        return mFullscreenBorder != null;
    }

    private void handleScreenRotation() {
        if (!isActivated()) {
            return;
        }

        if (mHandler.hasCallbacks(mShowBorderRunnable)) {
            mHandler.removeCallbacks(mShowBorderRunnable);
        }

        // We hide the border immediately as early as possible to beat the redrawing of window
        // in response to the orientation change so users won't see a weird shape border.
        mHandler.postAtFrontOfQueue(() -> {
            mFullscreenBorder.setAlpha(0f);
        });

        mHandler.postDelayed(mShowBorderRunnable, mLongAnimationTimeMs);
    }

    private void showBorderWithNullCheck() {
        if (mShowHideBorderAnimator != null) {
            mShowHideBorderAnimator.start();
        }
    }

    private void updateDimensions() {
        mBorderOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnifier_border_width_fullscreen_with_offset)
                - mContext.getResources().getDimensionPixelSize(
                        R.dimen.magnifier_border_width_fullscreen);
    }

    @Override
    public void onLowMemory() {

    }
}
