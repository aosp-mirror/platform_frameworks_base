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
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
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
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.systemui.Flags;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.util.leak.RotationUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class FullscreenMagnificationController implements ComponentCallbacks {

    private static final String TAG = "FullscreenMagController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

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
    private int mBorderStoke;
    private final int mDisplayId;
    private static final Region sEmptyRegion = new Region();
    @VisibleForTesting
    @Nullable
    ValueAnimator mShowHideBorderAnimator;
    private Handler mHandler;
    private Executor mExecutor;
    private final Configuration mConfiguration;
    private final Runnable mHideBorderImmediatelyRunnable = this::hideBorderImmediately;
    private final Runnable mShowBorderRunnable = this::showBorder;
    private int mRotation;
    private final IRotationWatcher mRotationWatcher = new IRotationWatcher.Stub() {
        @Override
        public void onRotationChanged(final int rotation) {
            handleScreenRotation();
        }
    };
    private final long mLongAnimationTimeMs;
    private final DisplayManager mDisplayManager;
    private final DisplayManager.DisplayListener mDisplayListener;
    private String mCurrentDisplayUniqueId;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DISABLED,
            DISABLING,
            ENABLING,
            ENABLED
    })
    @interface FullscreenMagnificationActivationState {}
    private static final int DISABLED = 0;
    private static final int DISABLING  = 1;
    private static final int ENABLING = 2;
    private static final int ENABLED = 3;
    @FullscreenMagnificationActivationState
    private int mActivationState = DISABLED;

    public FullscreenMagnificationController(
            @UiContext Context context,
            @Main Handler handler,
            @Main Executor executor,
            DisplayManager displayManager,
            AccessibilityManager accessibilityManager,
            WindowManager windowManager,
            IWindowManager iWindowManager,
            Supplier<SurfaceControlViewHost> scvhSupplier) {
        this(context, handler, executor, displayManager, accessibilityManager,
                windowManager, iWindowManager, scvhSupplier,
                new SurfaceControl.Transaction());
    }

    @VisibleForTesting
    FullscreenMagnificationController(
            @UiContext Context context,
            @Main Handler handler,
            @Main Executor executor,
            DisplayManager displayManager,
            AccessibilityManager accessibilityManager,
            WindowManager windowManager,
            IWindowManager iWindowManager,
            Supplier<SurfaceControlViewHost> scvhSupplier,
            SurfaceControl.Transaction transaction) {
        mContext = context;
        mHandler = handler;
        mExecutor = executor;
        mAccessibilityManager = accessibilityManager;
        mWindowManager = windowManager;
        mIWindowManager = iWindowManager;
        mWindowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        mTransaction = transaction;
        mScvhSupplier = scvhSupplier;
        updateDimensions();
        mDisplayId = mContext.getDisplayId();
        mConfiguration = new Configuration(context.getResources().getConfiguration());
        mLongAnimationTimeMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_longAnimTime);
        mCurrentDisplayUniqueId = mContext.getDisplayNoVerify().getUniqueId();
        mDisplayManager = displayManager;
        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                // Do nothing
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                // Do nothing
            }

            @Override
            public void onDisplayChanged(int displayId) {
                final String uniqueId = mContext.getDisplayNoVerify().getUniqueId();
                if (uniqueId.equals(mCurrentDisplayUniqueId)) {
                    // Same unique ID means the physical display doesn't change. Early return.
                    return;
                }
                mCurrentDisplayUniqueId = uniqueId;
                mHandler.post(FullscreenMagnificationController.this::applyCornerRadiusToBorder);
            }
        };
    }

    @VisibleForTesting
    @UiThread
    ValueAnimator createShowTargetAnimator(@NonNull View target) {
        if (mShowHideBorderAnimator != null) {
            mShowHideBorderAnimator.cancel();
        }

        final ValueAnimator valueAnimator =
                ObjectAnimator.ofFloat(target, View.ALPHA, 0f, 1f);
        Interpolator interpolator = new AccelerateInterpolator();

        valueAnimator.setInterpolator(interpolator);
        valueAnimator.setDuration(mLongAnimationTimeMs);
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                // This could be called when the animation ends or is canceled. Therefore, we need
                // to check the state of fullscreen magnification for the following actions. We only
                // update the state to ENABLED when the previous state is ENABLING which implies
                // fullscreen magnification is experiencing an ongoing create border process.
                mHandler.post(() -> {
                    if (getState() == ENABLING) {
                        setState(ENABLED);
                    }
                });
            }});
        return valueAnimator;
    }

    @VisibleForTesting
    @UiThread
    ValueAnimator createHideTargetAnimator(@NonNull View target) {
        if (mShowHideBorderAnimator != null) {
            mShowHideBorderAnimator.cancel();
        }

        final ValueAnimator valueAnimator =
                ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0f);
        Interpolator interpolator = new DecelerateInterpolator();

        valueAnimator.setInterpolator(interpolator);
        valueAnimator.setDuration(mLongAnimationTimeMs);
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                // This could be called when the animation ends or is canceled. Therefore, we need
                // to check the state of fullscreen magnification for the following actions. Border
                // cleanup should only happens after a removal process.
                mHandler.post(() -> {
                    if (getState() == DISABLING) {
                        cleanUpBorder();
                    }
                });
            }});
        return valueAnimator;
    }

    /**
     * Check the fullscreen magnification activation status, and proceed corresponding actions when
     * there is an activation change.
     */
    @UiThread
    public void onFullscreenMagnificationActivationChanged(boolean activated) {
        if (activated) {
            createFullscreenMagnificationBorder();
        } else {
            removeFullscreenMagnificationBorder();
        }
    }

    /**
     * This method should only be called when fullscreen magnification is changed from activated
     * to inactivated.
     */
    @UiThread
    private void removeFullscreenMagnificationBorder() {
        int state = getState();
        if (state == DISABLING || state == DISABLED) {
            // If there is an ongoing disable process or it is already disabled, return
            return;
        }
        // The state should be updated as early as possible so others could check
        // the ongoing process.
        setState(DISABLING);
        mShowHideBorderAnimator = createHideTargetAnimator(mFullscreenBorder);
        mShowHideBorderAnimator.start();
    }

    @VisibleForTesting
    @UiThread
    void cleanUpBorder() {
        mContext.unregisterComponentCallbacks(this);

        if (Flags.updateCornerRadiusOnDisplayChanged()) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }

        if (mSurfaceControlViewHost != null) {
            mSurfaceControlViewHost.release();
            mSurfaceControlViewHost = null;
        }

        if (mFullscreenBorder != null) {
            if (mHandler.hasCallbacks(mHideBorderImmediatelyRunnable)) {
                mHandler.removeCallbacks(mHideBorderImmediatelyRunnable);
            }
            if (mHandler.hasCallbacks(mShowBorderRunnable)) {
                mHandler.removeCallbacks(mShowBorderRunnable);
            }
            mFullscreenBorder = null;
            try {
                mIWindowManager.removeRotationWatcher(mRotationWatcher);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove rotation watcher", e);
            }
        }
        setState(DISABLED);
    }

    /**
     * This method should only be called when fullscreen magnification is changed from inactivated
     * to activated.
     */
    @UiThread
    private void createFullscreenMagnificationBorder() {
        int state = getState();
        if (state == ENABLING || state == ENABLED) {
            // If there is an ongoing enable process or it is already enabled, return
            return;
        }
        // The state should be updated as early as possible so others could check
        // the ongoing process.
        setState(ENABLING);

        if (mShowHideBorderAnimator != null) {
            mShowHideBorderAnimator.cancel();
        }

        onConfigurationChanged(mContext.getResources().getConfiguration());
        mContext.registerComponentCallbacks(this);

        if (mSurfaceControlViewHost == null) {
            // Create the view only if it does not exist yet. If we are trying to enable
            // fullscreen magnification before it was fully disabled, we use the previous view
            // instead of creating a new one.
            mFullscreenBorder = LayoutInflater.from(mContext)
                    .inflate(R.layout.fullscreen_magnification_border, null);
            // Set the initial border view alpha manually so we won't show the border
            // accidentally after we apply show() to the SurfaceControl and before the
            // animation starts to run.
            mFullscreenBorder.setAlpha(0f);
            mSurfaceControlViewHost = mScvhSupplier.get();
            mSurfaceControlViewHost.setView(mFullscreenBorder, getBorderLayoutParams());
            mBorderSurfaceControl =
                    mSurfaceControlViewHost.getSurfacePackage().getSurfaceControl();
            try {
                mIWindowManager.watchRotation(mRotationWatcher, Display.DEFAULT_DISPLAY);
            } catch (Exception e) {
                Log.w(TAG, "Failed to register rotation watcher", e);
            }
            if (Flags.updateCornerRadiusOnDisplayChanged()) {
                applyCornerRadiusToBorder();
            }
        }

        mTransaction
                .addTransactionCommittedListener(
                        mExecutor,
                        this::showBorder)
                .setPosition(mBorderSurfaceControl, -mBorderOffset, -mBorderOffset)
                .setLayer(mBorderSurfaceControl, Integer.MAX_VALUE)
                .show(mBorderSurfaceControl)
                .apply();

        mAccessibilityManager.attachAccessibilityOverlayToDisplay(
                mDisplayId, mBorderSurfaceControl);
        if (Flags.updateCornerRadiusOnDisplayChanged()) {
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        }

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
            if (Flags.updateCornerRadiusOnDisplayChanged()) {
                // Recenter the border
                mTransaction.setPosition(
                        mBorderSurfaceControl, -mBorderOffset, -mBorderOffset).apply();
            }
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

        // We hide the border immediately as early as possible to beat the redrawing of
        // window in response to the orientation change so users won't see a weird shape
        // border.
        mHandler.postAtFrontOfQueue(mHideBorderImmediatelyRunnable);
        mHandler.postDelayed(mShowBorderRunnable, mLongAnimationTimeMs);
    }

    @UiThread
    private void hideBorderImmediately() {
        if (mShowHideBorderAnimator != null) {
            mShowHideBorderAnimator.cancel();
        }
        mFullscreenBorder.setAlpha(0f);
    }

    @UiThread
    private void showBorder() {
        mShowHideBorderAnimator = createShowTargetAnimator(mFullscreenBorder);
        mShowHideBorderAnimator.start();
    }

    private void updateDimensions() {
        mBorderOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnifier_border_width_fullscreen_with_offset)
                - mContext.getResources().getDimensionPixelSize(
                        R.dimen.magnifier_border_width_fullscreen);
        mBorderStoke = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnifier_border_width_fullscreen_with_offset);
    }

    @UiThread
    @VisibleForTesting
    void applyCornerRadiusToBorder() {
        if (!isActivated()) {
            return;
        }
        if (!(mFullscreenBorder.getBackground() instanceof GradientDrawable)) {
            // Wear doesn't use the same magnification border background. So early return here.
            return;
        }

        float cornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(mContext);
        GradientDrawable backgroundDrawable = (GradientDrawable) mFullscreenBorder.getBackground();
        backgroundDrawable.setStroke(
                mBorderStoke,
                mContext.getResources().getColor(
                        R.color.magnification_border_color, mContext.getTheme()));
        backgroundDrawable.setCornerRadius(cornerRadius);
    }

    @UiThread
    private void setState(@FullscreenMagnificationActivationState int state) {
        if (DEBUG) {
            Log.d(TAG, "setState from " + mActivationState + " to " + state);
        }
        mActivationState = state;
    }

    @VisibleForTesting
    @UiThread
    int getState() {
        return mActivationState;
    }

    @Override
    public void onLowMemory() {

    }
}
