/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.systemui.R;
import com.android.systemui.recents.RecentsConfiguration;


/**
 * The full screen transition view that gets animated down from the full screen into a task
 * thumbnail view.
 */
public class FullscreenTransitionOverlayView extends FrameLayout {

    /** The FullscreenTransitionOverlayView callbacks */
    public interface FullScreenTransitionViewCallbacks {
        void onEnterAnimationComplete();
    }

    RecentsConfiguration mConfig;

    FullScreenTransitionViewCallbacks mCb;

    ImageView mScreenshotView;
    Rect mClipRect = new Rect();
    Paint mLayerPaint = new Paint();
    PorterDuffColorFilter mDimColorFilter = new PorterDuffColorFilter(0, PorterDuff.Mode.MULTIPLY);

    int mDim;
    int mMaxDim;
    AccelerateInterpolator mDimInterpolator = new AccelerateInterpolator();

    boolean mIsAnimating;
    AnimatorSet mEnterAnimation;

    public FullscreenTransitionOverlayView(Context context) {
        super(context);
    }

    public FullscreenTransitionOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FullscreenTransitionOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public FullscreenTransitionOverlayView(Context context, AttributeSet attrs, int defStyleAttr,
                                           int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        mMaxDim = mConfig.taskStackMaxDim;
        setClipTop(getClipTop());
        setClipBottom(getClipBottom());
        setDim(getDim());
        setWillNotDraw(false);
    }

    @Override
    protected void onFinishInflate() {
        mScreenshotView = (ImageView) findViewById(R.id.image);
    }

    /** Sets the callbacks */
    public void setCallbacks(FullScreenTransitionViewCallbacks cb) {
        mCb = cb;
    }

    /** Sets the top clip */
    public void setClipTop(int clip) {
        mClipRect.top = clip;
        setClipBounds(mClipRect);
    }

    /** Gets the top clip */
    public int getClipTop() {
        return mClipRect.top;
    }

    /** Sets the bottom clip */
    public void setClipBottom(int clip) {
        mClipRect.bottom = clip;
        setClipBounds(mClipRect);
    }

    /** Gets the top clip */
    public int getClipBottom() {
        return mClipRect.bottom;
    }

    /** Returns the current dim. */
    public void setDim(int dim) {
        mDim = dim;
        /*
        int inverse = 255 - mDim;
        mDimColorFilter.setColor(Color.argb(0xFF, inverse, inverse, inverse));
        mLayerPaint.setColorFilter(mDimColorFilter);
        setLayerType(LAYER_TYPE_HARDWARE, mLayerPaint);
        */
    }

    /** Returns the current dim. */
    public int getDim() {
        return mDim;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mClipRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Prepares the screenshot view for the transition into Recents */
    public void prepareAnimateOnEnterRecents(Bitmap screenshot) {
        if (!mConfig.launchedFromAppWithScreenshot) return;

        setClipTop(0);
        setClipBottom(getMeasuredHeight());
        setDim(0);
        setTranslationY(0f);
        setScaleX(1f);
        setScaleY(1f);
        setVisibility(mConfig.launchedFromAppWithScreenshot ? View.VISIBLE : View.INVISIBLE);
        if (screenshot != null) {
            mScreenshotView.setImageBitmap(screenshot);
        } else {
            mScreenshotView.setImageDrawable(null);
        }
    }

    /** Resets the transition view */
    public void reset() {
        setVisibility(View.GONE);
        mScreenshotView.setImageDrawable(null);
    }

    /** Animates this view as it enters recents */
    public void animateOnEnterRecents(ViewAnimation.TaskViewEnterContext ctx,
                                      final Runnable postAnimRunnable) {
        // Cancel the current animation
        if (mEnterAnimation != null) {
            mEnterAnimation.removeAllListeners();
            mEnterAnimation.cancel();
        }

        // Calculate the bottom clip
        Rect targetTaskRect = ctx.targetTaskTransform.rect;
        float scale = (float) targetTaskRect.width() / getMeasuredWidth();
        float scaleYOffset = ((1f - scale) * getMeasuredHeight()) / 2;
        float scaledTopInset = (int) (scale * mConfig.systemInsets.top);
        int translationY = (int) -scaleYOffset + (int) (mConfig.systemInsets.top - scaledTopInset)
                + targetTaskRect.top;
        int clipBottom = mConfig.systemInsets.top + (int) (targetTaskRect.height() / scale);

        // Calculate the dim
        float minScale = TaskStackViewLayoutAlgorithm.StackPeekMinScale;
        float scaleRange = 1f - minScale;
        float dim = (1f - ctx.targetTaskTransform.scale) / scaleRange;
        dim = mDimInterpolator.getInterpolation(Math.min(dim, 1f));
        int toDim = Math.max(0, Math.min(mMaxDim, (int) (dim * 255)));

        // Enable the HW Layers on the screenshot view
        mScreenshotView.setLayerType(View.LAYER_TYPE_HARDWARE, mLayerPaint);

        // Compose the animation
        mEnterAnimation = new AnimatorSet();
        mEnterAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        // Mark that we are no longer animating
                        mIsAnimating = false;
                        // Disable the HW Layers on this view
                        setLayerType(View.LAYER_TYPE_NONE, mLayerPaint);
                        // Notify any callbacks
                        mCb.onEnterAnimationComplete();
                        // Run the given post-anim runnable
                        postAnimRunnable.run();
                    }
                });
            }
        });
        // XXX: Translation y should be negative initially to simulate moving from the top of the screen?
        mEnterAnimation.setStartDelay(0);
        mEnterAnimation.setDuration(475);
        mEnterAnimation.setInterpolator(mConfig.fastOutSlowInInterpolator);
        mEnterAnimation.playTogether(
                // ObjectAnimator.ofInt(this, "clipTop", mConfig.systemInsets.top),
                ObjectAnimator.ofInt(this, "clipBottom", clipBottom),
                ObjectAnimator.ofInt(this, "dim", toDim),
                ObjectAnimator.ofFloat(this, "translationY", translationY),
                ObjectAnimator.ofFloat(this, "scaleX", scale),
                ObjectAnimator.ofFloat(this, "scaleY", scale)
        );
        setClipTop(mConfig.systemInsets.top);
        mEnterAnimation.start();

        mIsAnimating = true;
    }

    /** Animates this view back out of Recents if we were in the process of animating in. */
    public boolean cancelAnimateOnEnterRecents(final Runnable postAnimRunnable) {
        if (mIsAnimating) {
            // Cancel the current animation
            if (mEnterAnimation != null) {
                mEnterAnimation.removeAllListeners();
                mEnterAnimation.cancel();
            }

            // Enable the HW Layers on the screenshot view
            mScreenshotView.setLayerType(View.LAYER_TYPE_HARDWARE, mLayerPaint);

            // Compose the animation
            mEnterAnimation = new AnimatorSet();
            mEnterAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            // Mark that we are no longer animating
                            mIsAnimating = false;
                            // Disable the HW Layers on the screenshot view
                            mScreenshotView.setLayerType(View.LAYER_TYPE_NONE, mLayerPaint);
                            // Notify any callbacks
                            mCb.onEnterAnimationComplete();
                            // Run the given post-anim runnable
                            postAnimRunnable.run();
                        }
                    });
                }
            });
            mEnterAnimation.setDuration(475);
            mEnterAnimation.setInterpolator(mConfig.fastOutSlowInInterpolator);
            mEnterAnimation.playTogether(
                    ObjectAnimator.ofInt(this, "clipTop", 0),
                    ObjectAnimator.ofInt(this, "clipBottom", getMeasuredHeight()),
                    ObjectAnimator.ofInt(this, "dim", 0),
                    ObjectAnimator.ofFloat(this, "translationY", 0f),
                    ObjectAnimator.ofFloat(this, "scaleX", 1f),
                    ObjectAnimator.ofFloat(this, "scaleY", 1f)
            );
            mEnterAnimation.start();

            return true;
        }
        return false;
    }
}
