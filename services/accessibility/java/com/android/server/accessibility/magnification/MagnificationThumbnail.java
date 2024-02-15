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
package com.android.server.accessibility.magnification;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.R;
/**
 *  This class is used to show of magnification thumbnail
 *  from FullScreenMagnification. It is responsible for
 *  show of magnification and fade in/out animation, and
 *  it just only uses in FullScreenMagnification
 */
public class MagnificationThumbnail {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "MagnificationThumbnail";

    private static final int FADE_IN_ANIMATION_DURATION_MS = 200;
    private static final int FADE_OUT_ANIMATION_DURATION_MS = 1000;
    private static final int LINGER_DURATION_MS = 500;

    private Rect mWindowBounds;
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler;

    @VisibleForTesting
    public FrameLayout mThumbnailLayout;

    private View mThumbnailView;
    private int mThumbnailWidth;
    private int mThumbnailHeight;

    private final WindowManager.LayoutParams mBackgroundParams;
    private boolean mVisible = false;

    private static final float ASPECT_RATIO = 14f;
    private static final float BG_ASPECT_RATIO = ASPECT_RATIO / 2f;

    private ObjectAnimator mThumbnailAnimator;
    private boolean mIsFadingIn;

    /**
     * FullScreenMagnificationThumbnail Constructor
     */
    public MagnificationThumbnail(Context context, WindowManager windowManager, Handler handler) {
        mContext = context;
        mWindowManager = windowManager;
        mHandler = handler;
        mWindowBounds =  mWindowManager.getCurrentWindowMetrics().getBounds();
        mBackgroundParams = createLayoutParams();
        mThumbnailWidth = 0;
        mThumbnailHeight = 0;
        mHandler.post(this::createThumbnailLayout);
    }

    @MainThread
    private void createThumbnailLayout() {
        mThumbnailLayout = (FrameLayout) LayoutInflater.from(mContext)
                .inflate(R.layout.thumbnail_background_view, /* root: */ null);
        mThumbnailView =
                mThumbnailLayout.findViewById(R.id.accessibility_magnification_thumbnail_view);
    }

    /**
     * Sets the magnificationBounds for Thumbnail and resets the position on the screen.
     *
     * @param currentBounds the current magnification bounds
     */
    @AnyThread
    public void setThumbnailBounds(Rect currentBounds, float scale, float centerX, float centerY) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setThumbnailBounds " + currentBounds);
        }
        mHandler.post(() -> {
            refreshBackgroundBounds(currentBounds);
            if (mVisible) {
                updateThumbnailMainThread(scale, centerX, centerY);
            }
        });
    }

    @MainThread
    private void refreshBackgroundBounds(Rect currentBounds) {
        mWindowBounds = currentBounds;

        Point magnificationBoundary = getMagnificationThumbnailPadding(mContext);
        mThumbnailWidth = (int) (mWindowBounds.width() / BG_ASPECT_RATIO);
        mThumbnailHeight = (int) (mWindowBounds.height() / BG_ASPECT_RATIO);
        int initX = magnificationBoundary.x;
        int initY = magnificationBoundary.y;
        mBackgroundParams.width = mThumbnailWidth;
        mBackgroundParams.height = mThumbnailHeight;
        mBackgroundParams.x = initX;
        mBackgroundParams.y = initY;

        if (mVisible) {
            mWindowManager.updateViewLayout(mThumbnailLayout, mBackgroundParams);
        }
    }

    @MainThread
    private void showThumbnail() {
        if (DEBUG) {
            Log.d(LOG_TAG, "showThumbnail " + mVisible);
        }
        animateThumbnail(true);
    }

    /**
     * Hides thumbnail and removes the view from the window when finished animating.
     */
    @AnyThread
    public void hideThumbnail() {
        mHandler.post(this::hideThumbnailMainThread);
    }

    @MainThread
    private void hideThumbnailMainThread() {
        if (DEBUG) {
            Log.d(LOG_TAG, "hideThumbnail " + mVisible);
        }
        if (mVisible) {
            animateThumbnail(false);
        }
    }

    /**
     * Animates the thumbnail in or out and resets the timeout to auto-hiding.
     *
     * @param fadeIn true: fade in, false fade out
     */
    @MainThread
    private void animateThumbnail(boolean fadeIn) {
        if (DEBUG) {
            Log.d(
                    LOG_TAG,
                    "setThumbnailAnimation "
                        + " fadeIn: " + fadeIn
                        + " mVisible: " + mVisible
                        + " isFadingIn: " + mIsFadingIn
                        + " isRunning: " + mThumbnailAnimator
            );
        }

        // Reset countdown to hide automatically
        mHandler.removeCallbacks(this::hideThumbnailMainThread);
        if (fadeIn) {
            mHandler.postDelayed(this::hideThumbnailMainThread, LINGER_DURATION_MS);
        }

        if (fadeIn == mIsFadingIn) {
            return;
        }
        mIsFadingIn = fadeIn;

        if (fadeIn && !mVisible) {
            mWindowManager.addView(mThumbnailLayout, mBackgroundParams);
            mVisible = true;
        }

        if (mThumbnailAnimator != null) {
            mThumbnailAnimator.cancel();
        }
        mThumbnailAnimator = ObjectAnimator.ofFloat(
                mThumbnailLayout,
                "alpha",
                fadeIn ? 1f : 0f
        );
        mThumbnailAnimator.setDuration(
                fadeIn ? FADE_IN_ANIMATION_DURATION_MS : FADE_OUT_ANIMATION_DURATION_MS
        );
        mThumbnailAnimator.addListener(new Animator.AnimatorListener() {
            private boolean mIsCancelled;

            @Override
            public void onAnimationStart(@NonNull Animator animation) {

            }

            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                if (DEBUG) {
                    Log.d(
                            LOG_TAG,
                            "onAnimationEnd "
                                + " fadeIn: " + fadeIn
                                + " mVisible: " + mVisible
                                + " mIsCancelled: " + mIsCancelled
                                + " animation: " + animation);
                }
                if (mIsCancelled) {
                    return;
                }
                if (!fadeIn && mVisible) {
                    mWindowManager.removeView(mThumbnailLayout);
                    mVisible = false;
                }
            }

            @Override
            public void onAnimationCancel(@NonNull Animator animation) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "onAnimationCancel "
                            + " fadeIn: " + fadeIn
                            + " mVisible: " + mVisible
                            + " animation: " + animation);
                }
                mIsCancelled = true;
            }

            @Override
            public void onAnimationRepeat(@NonNull Animator animation) {

            }
        });

        mThumbnailAnimator.start();
    }

    /**
     * Scale up/down the current magnification thumbnail spec.
     *
     * <p>Will show/hide the thumbnail with animations when appropriate.
     *
     * @param scale the magnification scale
     * @param centerX the unscaled, screen-relative X coordinate of the center
     *                of the viewport, or {@link Float#NaN} to leave unchanged
     * @param centerY the unscaled, screen-relative Y coordinate of the center
     *                of the viewport, or {@link Float#NaN} to leave unchanged
     */
    @AnyThread
    public void updateThumbnail(float scale, float centerX, float centerY) {
        mHandler.post(() -> updateThumbnailMainThread(scale, centerX, centerY));
    }

    @MainThread
    private void updateThumbnailMainThread(float scale, float centerX, float centerY) {
        // Restart the fadeout countdown (or show if it's hidden)
        showThumbnail();

        var scaleDown = Float.isNaN(scale) ? mThumbnailView.getScaleX() : 1f / scale;
        if (!Float.isNaN(scale)) {
            mThumbnailView.setScaleX(scaleDown);
            mThumbnailView.setScaleY(scaleDown);
        }

        if (!Float.isNaN(centerX)
                && !Float.isNaN(centerY)
                && mThumbnailWidth > 0
                && mThumbnailHeight > 0
        ) {
            var padding = mThumbnailView.getPaddingTop();
            var ratio = 1f / BG_ASPECT_RATIO;
            var centerXScaled = centerX * ratio - (mThumbnailWidth / 2f + padding);
            var centerYScaled = centerY * ratio - (mThumbnailHeight / 2f + padding);

            if (DEBUG) {
                Log.d(
                        LOG_TAG,
                        "updateThumbnail centerXScaled : " + centerXScaled
                                + " centerYScaled : " + centerYScaled
                                + " getTranslationX : " + mThumbnailView.getTranslationX()
                                + " ratio : " + ratio
                );
            }

            mThumbnailView.setTranslationX(centerXScaled);
            mThumbnailView.setTranslationY(centerYScaled);
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSPARENT);
        params.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
        params.gravity = Gravity.BOTTOM | Gravity.LEFT;
        params.setFitInsetsTypes(WindowInsets.Type.ime() | WindowInsets.Type.navigationBars());
        return params;
    }

    private Point getMagnificationThumbnailPadding(Context context) {
        Point thumbnailPaddings = new Point(0, 0);
        final int defaultPadding = mContext.getResources()
                .getDimensionPixelSize(R.dimen.accessibility_magnification_thumbnail_padding);
        thumbnailPaddings.x = defaultPadding;
        thumbnailPaddings.y = defaultPadding;
        return thumbnailPaddings;
    }
}
