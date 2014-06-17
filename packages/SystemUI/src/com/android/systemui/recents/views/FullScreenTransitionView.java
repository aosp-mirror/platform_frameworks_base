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
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.systemui.recents.Console;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;


/**
 * The full screen transition view that gets animated down from the full screen into a task
 * thumbnail view.
 */
public class FullScreenTransitionView extends FrameLayout {

    /** The FullScreenTransitionView callbacks */
    public interface FullScreenTransitionViewCallbacks {
        void onEnterAnimationComplete(boolean canceled);
    }

    RecentsConfiguration mConfig;

    FullScreenTransitionViewCallbacks mCb;

    ImageView mScreenshotView;

    Rect mClipRect = new Rect();

    boolean mIsAnimating;
    AnimatorSet mEnterAnimation;

    public FullScreenTransitionView(Context context, FullScreenTransitionViewCallbacks cb) {
        super(context);
        mConfig = RecentsConfiguration.getInstance();
        mCb = cb;
        mScreenshotView = new ImageView(context);
        mScreenshotView.setScaleType(ImageView.ScaleType.FIT_XY);
        mScreenshotView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(mScreenshotView);
        setClipTop(getClipTop());
        setClipBottom(getClipBottom());
        setWillNotDraw(false);
    }

    /** Sets the top clip */
    public void setClipTop(int clip) {
        mClipRect.top = clip;
        postInvalidateOnAnimation();
    }

    /** Gets the top clip */
    public int getClipTop() {
        return mClipRect.top;
    }

    /** Sets the bottom clip */
    public void setClipBottom(int clip) {
        mClipRect.bottom = clip;
        postInvalidateOnAnimation();
    }

    /** Gets the top clip */
    public int getClipBottom() {
        return mClipRect.bottom;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mClipRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
    }

    @Override
    public void draw(Canvas canvas) {
        int restoreCount = canvas.save(Canvas.CLIP_SAVE_FLAG | Canvas.CLIP_TO_LAYER_SAVE_FLAG);
        canvas.clipRect(mClipRect);
        super.draw(canvas);
        canvas.restoreToCount(restoreCount);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Prepares the screenshot view for the transition into Recents */
    public void prepareAnimateOnEnterRecents(Bitmap screenshot) {
        if (!mConfig.launchedFromAppWithScreenshot) return;

        if (Console.Enabled) {
            Console.logStartTracingTime(Constants.Log.App.TimeRecentsScreenshotTransition,
                    Constants.Log.App.TimeRecentsScreenshotTransitionKey);
        }

        setClipTop(0);
        setClipBottom(getMeasuredHeight());
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
        setVisibility(View.INVISIBLE);
        mScreenshotView.setImageDrawable(null);
    }

    /** Animates this view as it enters recents */
    public void animateOnEnterRecents(ViewAnimation.TaskViewEnterContext ctx,
                                      final Runnable postAnimRunnable) {
        if (Console.Enabled) {
            Console.logTraceTime(Constants.Log.App.TimeRecentsScreenshotTransition,
                    Constants.Log.App.TimeRecentsScreenshotTransitionKey, "Starting");
        }

        // Cancel the current animation
        if (mEnterAnimation != null) {
            mEnterAnimation.removeAllListeners();
            mEnterAnimation.cancel();
        }

        // Calculate the bottom clip
        float scale = (float) ctx.taskRect.width() / getMeasuredWidth();
        int translationY = -mConfig.systemInsets.top + ctx.stackRectSansPeek.top +
                ctx.transform.translationY;
        int clipBottom = mConfig.systemInsets.top + (int) (ctx.taskRect.height() / scale);

        // Enable the HW Layers on the screenshot view
        mScreenshotView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Compose the animation
        mEnterAnimation = new AnimatorSet();
        mEnterAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Notify any callbacks
                mCb.onEnterAnimationComplete(false);
                // Run the given post-anim runnable
                postAnimRunnable.run();
                // Mark that we are no longer animating
                mIsAnimating = false;
                // Disable the HW Layers on this view
                setLayerType(View.LAYER_TYPE_NONE, null);

                if (Console.Enabled) {
                    Console.logTraceTime(Constants.Log.App.TimeRecentsScreenshotTransition,
                            Constants.Log.App.TimeRecentsScreenshotTransitionKey, "Completed");
                }
            }
        });
        mEnterAnimation.setStartDelay(0);
        mEnterAnimation.setDuration(475);
        mEnterAnimation.setInterpolator(mConfig.fastOutSlowInInterpolator);
        mEnterAnimation.playTogether(
                ObjectAnimator.ofInt(this, "clipTop", mConfig.systemInsets.top),
                ObjectAnimator.ofInt(this, "clipBottom", clipBottom),
                ObjectAnimator.ofFloat(this, "translationY", translationY),
                ObjectAnimator.ofFloat(this, "scaleX", scale),
                ObjectAnimator.ofFloat(this, "scaleY", scale)
        );
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

            // Compose the animation
            mEnterAnimation = new AnimatorSet();
            mEnterAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // Notify any callbacks
                    mCb.onEnterAnimationComplete(true);
                    // Run the given post-anim runnable
                    postAnimRunnable.run();
                    // Mark that we are no longer animating
                    mIsAnimating = false;
                    // Disable the HW Layers on the screenshot view
                    mScreenshotView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
            });
            mEnterAnimation.setDuration(475);
            mEnterAnimation.setInterpolator(mConfig.fastOutSlowInInterpolator);
            mEnterAnimation.playTogether(
                    ObjectAnimator.ofInt(this, "clipTop", 0),
                    ObjectAnimator.ofInt(this, "clipBottom", getMeasuredHeight()),
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
