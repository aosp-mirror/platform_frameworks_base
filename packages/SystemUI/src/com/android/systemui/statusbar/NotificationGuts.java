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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.stack.StackStateAnimator;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationGuts extends FrameLayout {
    private static final String TAG = "NotificationGuts";
    private static final long CLOSE_GUTS_DELAY = 8000;

    private Drawable mBackground;
    private int mClipTopAmount;
    private int mClipBottomAmount;
    private int mActualHeight;
    private boolean mExposed;

    private Handler mHandler;
    private Runnable mFalsingCheck;
    private boolean mNeedsFalsingProtection;
    private OnGutsClosedListener mClosedListener;
    private OnHeightChangedListener mHeightListener;

    private GutsContent mGutsContent;

    public interface GutsContent {

        public void setGutsParent(NotificationGuts listener);

        /**
         * Return the view to be shown in the notification guts.
         */
        public View getContentView();

        /**
         * Return the actual height of the content.
         */
        public int getActualHeight();

        /**
         * Called when the guts view have been told to close, typically after an outside
         * interaction.
         *
         * @param save whether the state should be saved.
         * @param force whether the guts view should be forced closed regardless of state.
         * @return if closing the view has been handled.
         */
        public boolean handleCloseControls(boolean save, boolean force);

        /**
         * Return whether the notification associated with these guts is set to be removed.
         */
        public boolean willBeRemoved();

        /**
         * Return whether these guts are a leavebehind (e.g. {@link NotificationSnooze}).
         */
        public default boolean isLeavebehind() {
            return false;
        }

        /**
         * Return whether something changed and needs to be saved, possibly requiring a bouncer.
         */
        boolean shouldBeSaved();
    }

    public interface OnGutsClosedListener {
        public void onGutsClosed(NotificationGuts guts);
    }

    public interface OnHeightChangedListener {
        public void onHeightChanged(NotificationGuts guts);
    }

    interface OnSettingsClickListener {
        void onClick(View v, int appUid);
    }

    public NotificationGuts(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        mHandler = new Handler();
        mFalsingCheck = new Runnable() {
            @Override
            public void run() {
                if (mNeedsFalsingProtection && mExposed) {
                    closeControls(-1 /* x */, -1 /* y */, false /* save */, false /* force */);
                }
            }
        };
        final TypedArray ta = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Theme, 0, 0);
        ta.recycle();
    }

    public NotificationGuts(Context context) {
        this(context, null);
    }

    public void setGutsContent(GutsContent content) {
        mGutsContent = content;
        removeAllViews();
        addView(mGutsContent.getContentView());
    }

    public GutsContent getGutsContent() {
        return mGutsContent;
    }

    public void resetFalsingCheck() {
        mHandler.removeCallbacks(mFalsingCheck);
        if (mNeedsFalsingProtection && mExposed) {
            mHandler.postDelayed(mFalsingCheck, CLOSE_GUTS_DELAY);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        draw(canvas, mBackground);
    }

    private void draw(Canvas canvas, Drawable drawable) {
        int top = mClipTopAmount;
        int bottom = mActualHeight - mClipBottomAmount;
        if (drawable != null && top < bottom) {
            drawable.setBounds(0, top, getWidth(), bottom);
            drawable.draw(canvas);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = mContext.getDrawable(R.drawable.notification_guts_bg);
        if (mBackground != null) {
            mBackground.setCallback(this);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        drawableStateChanged(mBackground);
    }

    private void drawableStateChanged(Drawable d) {
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    public void openControls(
            boolean shouldDoCircularReveal,
            int x,
            int y,
            boolean needsFalsingProtection,
            @Nullable Runnable onAnimationEnd) {
        animateOpen(shouldDoCircularReveal, x, y, onAnimationEnd);
        setExposed(true /* exposed */, needsFalsingProtection);
    }

    /**
     * Hide controls if they are visible
     * @param leavebehinds true if leavebehinds should be closed
     * @param controls true if controls should be closed
     * @param x x coordinate to animate the close circular reveal with
     * @param y y coordinate to animate the close circular reveal with
     * @param force whether the guts should be force-closed regardless of state.
     */
    public void closeControls(boolean leavebehinds, boolean controls, int x, int y, boolean force) {
        if (mGutsContent != null) {
            if ((mGutsContent.isLeavebehind() && leavebehinds)
                    || (!mGutsContent.isLeavebehind() && controls)) {
                closeControls(x, y, mGutsContent.shouldBeSaved(), force);
            }
        }
    }

    /**
     * Closes any exposed guts/views.
     *
     * @param x x coordinate to animate the close circular reveal with
     * @param y y coordinate to animate the close circular reveal with
     * @param save whether the state should be saved
     * @param force whether the guts should be force-closed regardless of state.
     */
    public void closeControls(int x, int y, boolean save, boolean force) {
        // First try to dismiss any blocking helper.
        boolean wasBlockingHelperDismissed =
                Dependency.get(NotificationBlockingHelperManager.class)
                        .dismissCurrentBlockingHelper();

        if (getWindowToken() == null) {
            if (mClosedListener != null) {
                mClosedListener.onGutsClosed(this);
            }
            return;
        }

        if (mGutsContent == null
                || !mGutsContent.handleCloseControls(save, force)
                || wasBlockingHelperDismissed) {
            // We only want to do a circular reveal if we're not showing the blocking helper.
            animateClose(x, y, !wasBlockingHelperDismissed /* shouldDoCircularReveal */);

            setExposed(false, mNeedsFalsingProtection);
            if (mClosedListener != null) {
                mClosedListener.onGutsClosed(this);
            }
        }
    }

    /** Animates in the guts view via either a fade or a circular reveal. */
    private void animateOpen(
            boolean shouldDoCircularReveal, int x, int y, @Nullable Runnable onAnimationEnd) {
        if (isAttachedToWindow()) {
            if (shouldDoCircularReveal) {
                double horz = Math.max(getWidth() - x, x);
                double vert = Math.max(getHeight() - y, y);
                float r = (float) Math.hypot(horz, vert);
                // Circular reveal originating at (x, y)
                Animator a = ViewAnimationUtils.createCircularReveal(this, x, y, 0, r);
                a.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
                a.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
                a.addListener(new AnimateOpenListener(onAnimationEnd));
                a.start();
            } else {
                // Fade in content
                this.setAlpha(0f);
                this.animate()
                        .alpha(1f)
                        .setDuration(StackStateAnimator.ANIMATION_DURATION_BLOCKING_HELPER_FADE)
                        .setInterpolator(Interpolators.ALPHA_IN)
                        .setListener(new AnimateOpenListener(onAnimationEnd))
                        .start();
            }
        } else {
            Log.w(TAG, "Failed to animate guts open");
        }
    }


    /** Animates out the guts view via either a fade or a circular reveal. */
    @VisibleForTesting
    void animateClose(int x, int y, boolean shouldDoCircularReveal) {
        if (isAttachedToWindow()) {
            if (shouldDoCircularReveal) {
                // Circular reveal originating at (x, y)
                if (x == -1 || y == -1) {
                    x = (getLeft() + getRight()) / 2;
                    y = (getTop() + getHeight() / 2);
                }
                double horz = Math.max(getWidth() - x, x);
                double vert = Math.max(getHeight() - y, y);
                float r = (float) Math.hypot(horz, vert);
                Animator a = ViewAnimationUtils.createCircularReveal(this,
                        x, y, r, 0);
                a.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
                a.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
                a.addListener(new AnimateCloseListener(this /* view */));
                a.start();
            } else {
                // Fade in the blocking helper.
                this.animate()
                        .alpha(0f)
                        .setDuration(StackStateAnimator.ANIMATION_DURATION_BLOCKING_HELPER_FADE)
                        .setInterpolator(Interpolators.ALPHA_OUT)
                        .setListener(new AnimateCloseListener(this /* view */))
                        .start();
            }
        } else {
            Log.w(TAG, "Failed to animate guts close");
        }
    }

    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        invalidate();
    }

    public int getActualHeight() {
        return mActualHeight;
    }

    public int getIntrinsicHeight() {
        return mGutsContent != null && mExposed ? mGutsContent.getActualHeight() : getHeight();
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setClosedListener(OnGutsClosedListener listener) {
        mClosedListener = listener;
    }

    public void setHeightChangedListener(OnHeightChangedListener listener) {
        mHeightListener = listener;
    }

    protected void onHeightChanged() {
        if (mHeightListener != null) {
            mHeightListener.onHeightChanged(this);
        }
    }

    @VisibleForTesting
    void setExposed(boolean exposed, boolean needsFalsingProtection) {
        final boolean wasExposed = mExposed;
        mExposed = exposed;
        mNeedsFalsingProtection = needsFalsingProtection;
        if (mExposed && mNeedsFalsingProtection) {
            resetFalsingCheck();
        } else {
            mHandler.removeCallbacks(mFalsingCheck);
        }
        if (wasExposed != mExposed && mGutsContent != null) {
            final View contentView = mGutsContent.getContentView();
            contentView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            if (mExposed) {
                contentView.requestAccessibilityFocus();
            }
        }
    }

    public boolean willBeRemoved() {
        return mGutsContent != null ? mGutsContent.willBeRemoved() : false;
    }

    public boolean isExposed() {
        return mExposed;
    }

    public boolean isLeavebehind() {
        return mGutsContent != null && mGutsContent.isLeavebehind();
    }

    /** Listener for animations executed in {@link #animateOpen(boolean, int, int, Runnable)}. */
    private static class AnimateOpenListener extends AnimatorListenerAdapter {
        final Runnable mOnAnimationEnd;

        private AnimateOpenListener(Runnable onAnimationEnd) {
            mOnAnimationEnd = onAnimationEnd;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            super.onAnimationEnd(animation);
            if (mOnAnimationEnd != null) {
                mOnAnimationEnd.run();
            }
        }
    }

    /** Listener for animations executed in {@link #animateClose(int, int, boolean)}. */
    private static class AnimateCloseListener extends AnimatorListenerAdapter {
        final View mView;

        private AnimateCloseListener(View view) {
            mView = view;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            super.onAnimationEnd(animation);
            mView.setVisibility(View.GONE);
        }
    }
}
