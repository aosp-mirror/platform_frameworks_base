/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import android.animation.PropertyValuesHolder;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.TableMaskFilter;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.View.MeasureSpec;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.LinearInterpolator;
import android.widget.RemoteViews.RemoteView;

@RemoteView
/**
 * A view that displays its children in a stack and allows users to discretely swipe
 * through the children.
 */
public class StackView extends AdapterViewAnimator {
    private final String TAG = "StackView";

    /**
     * Default animation parameters
     */
    private final int DEFAULT_ANIMATION_DURATION = 400;
    private final int MINIMUM_ANIMATION_DURATION = 50;

    /**
     * Parameters effecting the perspective visuals
     */
    private static float PERSPECTIVE_SHIFT_FACTOR = 0.12f;
    private static float PERSPECTIVE_SCALE_FACTOR = 0.35f;

    /**
     * Represent the two possible stack modes, one where items slide up, and the other
     * where items slide down. The perspective is also inverted between these two modes.
     */
    private static final int ITEMS_SLIDE_UP = 0;
    private static final int ITEMS_SLIDE_DOWN = 1;

    /**
     * These specify the different gesture states
     */
    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_SLIDE_UP = 1;
    private static final int GESTURE_SLIDE_DOWN = 2;

    /**
     * Specifies how far you need to swipe (up or down) before it
     * will be consider a completed gesture when you lift your finger
     */
    private static final float SWIPE_THRESHOLD_RATIO = 0.35f;
    private static final float SLIDE_UP_RATIO = 0.7f;

    /**
     * Sentinel value for no current active pointer.
     * Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    /**
     * Number of active views in the stack. One fewer view is actually visible, as one is hidden.
     */
    private static final int NUM_ACTIVE_VIEWS = 5;

    private static final int FRAME_PADDING = 4;

    /**
     * These variables are all related to the current state of touch interaction
     * with the stack
     */
    private float mInitialY;
    private float mInitialX;
    private int mActivePointerId;
    private int mYVelocity = 0;
    private int mSwipeGestureType = GESTURE_NONE;
    private int mSlideAmount;
    private int mSwipeThreshold;
    private int mTouchSlop;
    private int mMaximumVelocity;
    private VelocityTracker mVelocityTracker;

    private static HolographicHelper sHolographicHelper;
    private ImageView mHighlight;
    private StackSlider mStackSlider;
    private boolean mFirstLayoutHappened = false;
    private ViewGroup mAncestorContainingAllChildren = null;
    private int mAncestorHeight = 0;
    private int mStackMode;
    private int mFramePadding;

    public StackView(Context context) {
        super(context);
        initStackView();
    }

    public StackView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initStackView();
    }

    private void initStackView() {
        configureViewAnimator(NUM_ACTIVE_VIEWS, NUM_ACTIVE_VIEWS - 2);
        setStaticTransformationsEnabled(true);
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mActivePointerId = INVALID_POINTER;

        mHighlight = new ImageView(getContext());
        mHighlight.setLayoutParams(new LayoutParams(mHighlight));
        addViewInLayout(mHighlight, -1, new LayoutParams(mHighlight));
        mStackSlider = new StackSlider();

        if (sHolographicHelper == null) {
            sHolographicHelper = new HolographicHelper(mContext);
        }
        setClipChildren(false);
        setClipToPadding(false);

        // This sets the form of the StackView, which is currently to have the perspective-shifted
        // views above the active view, and have items slide down when sliding out. The opposite is
        // available by using ITEMS_SLIDE_UP.
        mStackMode = ITEMS_SLIDE_DOWN;

        // This is a flag to indicate the the stack is loading for the first time
        mWhichChild = -1;

        // Adjust the frame padding based on the density, since the highlight changes based
        // on the density
        final float density = mContext.getResources().getDisplayMetrics().density;
        mFramePadding = (int) Math.ceil(density * FRAME_PADDING);
    }

    /**
     * Animate the views between different relative indexes within the {@link AdapterViewAnimator}
     */
    void animateViewForTransition(int fromIndex, int toIndex, View view) {
        if (fromIndex == -1 && toIndex == 0) {
            // Fade item in
            if (view.getAlpha() == 1) {
                view.setAlpha(0);
            }
            view.setVisibility(VISIBLE);

            ObjectAnimator<Float> fadeIn = new ObjectAnimator<Float>(DEFAULT_ANIMATION_DURATION,
                    view, "alpha", view.getAlpha(), 1.0f);
            fadeIn.start();
        } else if (fromIndex == mNumActiveViews - 1 && toIndex == mNumActiveViews - 2) {
            // Slide item in
            view.setVisibility(VISIBLE);

            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            int duration = Math.round(mStackSlider.getDurationForNeutralPosition(mYVelocity));

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyValuesHolder<Float> slideInY =
                    new PropertyValuesHolder<Float>("YProgress", 0.0f);
            PropertyValuesHolder<Float> slideInX =
                    new PropertyValuesHolder<Float>("XProgress", 0.0f);
            ObjectAnimator pa = new ObjectAnimator(duration, animationSlider,
                    slideInX, slideInY);
            pa.setInterpolator(new LinearInterpolator());
            pa.start();
        } else if (fromIndex == mNumActiveViews - 2 && toIndex == mNumActiveViews - 1) {
            // Slide item out
            LayoutParams lp = (LayoutParams) view.getLayoutParams();

            int duration = Math.round(mStackSlider.getDurationForOffscreenPosition(mYVelocity));

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyValuesHolder<Float> slideOutY =
                    new PropertyValuesHolder<Float>("YProgress", 1.0f);
            PropertyValuesHolder<Float> slideOutX =
                    new PropertyValuesHolder<Float>("XProgress", 0.0f);
            ObjectAnimator pa = new ObjectAnimator(duration, animationSlider,
                   slideOutX, slideOutY);
            pa.setInterpolator(new LinearInterpolator());
            pa.start();
        } else if (fromIndex == -1 && toIndex == mNumActiveViews - 1) {
            // Make sure this view that is "waiting in the wings" is invisible
            view.setAlpha(0.0f);
            view.setVisibility(INVISIBLE);
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.setVerticalOffset(-mSlideAmount);
        } else if (toIndex == -1) {
            // Fade item out
            ObjectAnimator<Float> fadeOut = new ObjectAnimator<Float>
                    (DEFAULT_ANIMATION_DURATION, view, "alpha", view.getAlpha(), 0.0f);
            fadeOut.start();
        }

        // Implement the faked perspective
        if (toIndex != -1) {
            transformViewAtIndex(toIndex, view);
        }
    }

    private void transformViewAtIndex(int index, View view) {
        float maxPerpectiveShift = mMeasuredHeight * PERSPECTIVE_SHIFT_FACTOR;

        if (index == mNumActiveViews -1) index--;

        float r = (index * 1.0f) / (mNumActiveViews - 2);

        float scale = 1 - PERSPECTIVE_SCALE_FACTOR * (1 - r);
        PropertyValuesHolder<Float> scaleX = new PropertyValuesHolder<Float>("scaleX", scale);
        PropertyValuesHolder<Float> scaleY = new PropertyValuesHolder<Float>("scaleY", scale);

        r = (float) Math.pow(r, 2);

        int stackDirection = (mStackMode == ITEMS_SLIDE_UP) ? 1 : -1;
        float perspectiveTranslation = -stackDirection * r * maxPerpectiveShift;
        float scaleShiftCorrection = stackDirection * (1 - scale) *
                (mMeasuredHeight * (1 - PERSPECTIVE_SHIFT_FACTOR) / 2.0f);
        float transY = perspectiveTranslation + scaleShiftCorrection;

        PropertyValuesHolder<Float> translationY =
                new PropertyValuesHolder<Float>("translationY", transY);
        ObjectAnimator pa = new ObjectAnimator(100, view, scaleX, scaleY, translationY);
        pa.start();
    }

    private void updateChildTransforms() {
        for (int i = 0; i < mNumActiveViews - 1; i++) {
            View v = getViewAtRelativeIndex(i);
            if (v != null) {
                transformViewAtIndex(i, v);
            }
        }
    }

    @Override
    FrameLayout getFrameForChild() {
        FrameLayout fl = new FrameLayout(mContext);
        fl.setPadding(mFramePadding, mFramePadding, mFramePadding, mFramePadding);
        return fl;
    }

    /**
     * Apply any necessary tranforms for the child that is being added.
     */
    void applyTransformForChildAtIndex(View child, int relativeIndex) {
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }

    // TODO: right now, this code walks up the hierarchy as far as needed and disables clipping
    // so that the stack's children can draw outside of the stack's bounds. This is fine within
    // the context of widgets in the launcher, but is destructive in general, as the clipping
    // values are not being reset. For this to be a full framework level widget, we will need
    // framework level support for drawing outside of a parent's bounds.
    private void disableParentalClipping() {
        if (mAncestorContainingAllChildren != null) {
            ViewGroup vg = this;
            while (vg.getParent() != null && vg.getParent() instanceof ViewGroup) {
                if (vg == mAncestorContainingAllChildren) break;
                vg = (ViewGroup) vg.getParent();
                vg.setClipChildren(false);
                vg.setClipToPadding(false);
            }
        }
    }

    private void onLayout() {
        if (!mFirstLayoutHappened) {
            mSlideAmount = Math.round(SLIDE_UP_RATIO * getMeasuredHeight());
            updateChildTransforms();
            mSwipeThreshold = Math.round(SWIPE_THRESHOLD_RATIO * mSlideAmount);
            mFirstLayoutHappened = true;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getAction();
        switch(action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                if (mActivePointerId == INVALID_POINTER) {
                    mInitialX = ev.getX();
                    mInitialY = ev.getY();
                    mActivePointerId = ev.getPointerId(0);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex == INVALID_POINTER) {
                    // no data for our primary pointer, this shouldn't happen, log it
                    Log.d(TAG, "Error: No data for our primary pointer.");
                    return false;
                }
                float newY = ev.getY(pointerIndex);
                float deltaY = newY - mInitialY;

                beginGestureIfNeeded(deltaY);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(ev);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mActivePointerId = INVALID_POINTER;
                mSwipeGestureType = GESTURE_NONE;
            }
        }

        return mSwipeGestureType != GESTURE_NONE;
    }

    private void beginGestureIfNeeded(float deltaY) {
        if ((int) Math.abs(deltaY) > mTouchSlop && mSwipeGestureType == GESTURE_NONE) {
            int swipeGestureType = deltaY < 0 ? GESTURE_SLIDE_UP : GESTURE_SLIDE_DOWN;
            cancelLongPress();
            requestDisallowInterceptTouchEvent(true);

            int activeIndex;
            if (mStackMode == ITEMS_SLIDE_UP) {
                activeIndex = (swipeGestureType == GESTURE_SLIDE_DOWN) ?
                        mNumActiveViews - 1 : mNumActiveViews - 2;
            } else {
                activeIndex = (swipeGestureType == GESTURE_SLIDE_DOWN) ?
                        mNumActiveViews - 2 : mNumActiveViews - 1;
            }

            if (mAdapter == null) return;

            if (mLoopViews) {
                mStackSlider.setMode(StackSlider.NORMAL_MODE);
            } else if (mCurrentWindowStartUnbounded + activeIndex == 0) {
                mStackSlider.setMode(StackSlider.BEGINNING_OF_STACK_MODE);
            } else if (mCurrentWindowStartUnbounded + activeIndex == mAdapter.getCount()) {
                activeIndex--;
                mStackSlider.setMode(StackSlider.END_OF_STACK_MODE);
            } else {
                mStackSlider.setMode(StackSlider.NORMAL_MODE);
            }

            View v = getViewAtRelativeIndex(activeIndex);
            if (v == null) return;

            mHighlight.setImageBitmap(sHolographicHelper.createOutline(v));
            mHighlight.setRotation(v.getRotation());
            mHighlight.setTranslationY(v.getTranslationY());
            mHighlight.bringToFront();
            v.bringToFront();
            mStackSlider.setView(v);

            if (swipeGestureType == GESTURE_SLIDE_DOWN)
                v.setVisibility(VISIBLE);

            // We only register this gesture if we've made it this far without a problem
            mSwipeGestureType = swipeGestureType;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getAction();
        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        if (pointerIndex == INVALID_POINTER) {
            // no data for our primary pointer, this shouldn't happen, log it
            Log.d(TAG, "Error: No data for our primary pointer.");
            return false;
        }

        float newY = ev.getY(pointerIndex);
        float newX = ev.getX(pointerIndex);
        float deltaY = newY - mInitialY;
        float deltaX = newX - mInitialX;
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                beginGestureIfNeeded(deltaY);

                float rx = deltaX / (mSlideAmount * 1.0f);
                if (mSwipeGestureType == GESTURE_SLIDE_DOWN) {
                    float r = (deltaY - mTouchSlop * 1.0f) / mSlideAmount * 1.0f;
                    if (mStackMode == ITEMS_SLIDE_DOWN) r = 1 - r;
                    mStackSlider.setYProgress(1 - r);
                    mStackSlider.setXProgress(rx);
                    return true;
                } else if (mSwipeGestureType == GESTURE_SLIDE_UP) {
                    float r = -(deltaY + mTouchSlop * 1.0f) / mSlideAmount * 1.0f;
                    if (mStackMode == ITEMS_SLIDE_DOWN) r = 1 - r;
                    mStackSlider.setYProgress(r);
                    mStackSlider.setXProgress(rx);
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                handlePointerUp(ev);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(ev);
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                mActivePointerId = INVALID_POINTER;
                mSwipeGestureType = GESTURE_NONE;
                break;
            }
        }
        return true;
    }

    private final Rect touchRect = new Rect();
    private void onSecondaryPointerUp(MotionEvent ev) {
        final int activePointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(activePointerIndex);
        if (pointerId == mActivePointerId) {

            int activeViewIndex = (mSwipeGestureType == GESTURE_SLIDE_DOWN) ? mNumActiveViews - 1
                    : mNumActiveViews - 2;

            View v = getViewAtRelativeIndex(activeViewIndex);
            if (v == null) return;

            // Our primary pointer has gone up -- let's see if we can find
            // another pointer on the view. If so, then we should replace
            // our primary pointer with this new pointer and adjust things
            // so that the view doesn't jump
            for (int index = 0; index < ev.getPointerCount(); index++) {
                if (index != activePointerIndex) {

                    float x = ev.getX(index);
                    float y = ev.getY(index);

                    touchRect.set(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
                    if (touchRect.contains(Math.round(x), Math.round(y))) {
                        float oldX = ev.getX(activePointerIndex);
                        float oldY = ev.getY(activePointerIndex);

                        // adjust our frame of reference to avoid a jump
                        mInitialY += (y - oldY);
                        mInitialX += (x - oldX);

                        mActivePointerId = ev.getPointerId(index);
                        if (mVelocityTracker != null) {
                            mVelocityTracker.clear();
                        }
                        // ok, we're good, we found a new pointer which is touching the active view
                        return;
                    }
                }
            }
            // if we made it this far, it means we didn't find a satisfactory new pointer :(,
            // so end the gesture
            handlePointerUp(ev);
        }
    }

    private void handlePointerUp(MotionEvent ev) {
        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        float newY = ev.getY(pointerIndex);
        int deltaY = (int) (newY - mInitialY);

        if (mVelocityTracker != null) {
            mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
            mYVelocity = (int) mVelocityTracker.getYVelocity(mActivePointerId);
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        if (deltaY > mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_DOWN
                && mStackSlider.mMode == StackSlider.NORMAL_MODE) {
            // Swipe threshold exceeded, swipe down
            if (mStackMode == ITEMS_SLIDE_UP) {
                showNext();
            } else {
                showPrevious();
            }
            mHighlight.bringToFront();
        } else if (deltaY < -mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_UP
                && mStackSlider.mMode == StackSlider.NORMAL_MODE) {
            // Swipe threshold exceeded, swipe up
            if (mStackMode == ITEMS_SLIDE_UP) {
                showPrevious();
            } else {
                showNext();
            }

            mHighlight.bringToFront();
        } else if (mSwipeGestureType == GESTURE_SLIDE_UP ) {
            // Didn't swipe up far enough, snap back down
            int duration;
            float finalYProgress = (mStackMode == ITEMS_SLIDE_DOWN) ? 1 : 0;
            if (mStackMode == ITEMS_SLIDE_UP || mStackSlider.mMode != StackSlider.NORMAL_MODE) {
                duration = Math.round(mStackSlider.getDurationForNeutralPosition());
            } else {
                duration = Math.round(mStackSlider.getDurationForOffscreenPosition());
            }

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyValuesHolder<Float> snapBackY =
                    new PropertyValuesHolder<Float>("YProgress", finalYProgress);
            PropertyValuesHolder<Float> snapBackX =
                    new PropertyValuesHolder<Float>("XProgress", 0.0f);
            ObjectAnimator pa = new ObjectAnimator(duration, animationSlider,
                    snapBackX, snapBackY);
            pa.setInterpolator(new LinearInterpolator());
            pa.start();
        } else if (mSwipeGestureType == GESTURE_SLIDE_DOWN) {
            // Didn't swipe down far enough, snap back up
            float finalYProgress = (mStackMode == ITEMS_SLIDE_DOWN) ? 0 : 1;
            int duration;
            if (mStackMode == ITEMS_SLIDE_DOWN || mStackSlider.mMode != StackSlider.NORMAL_MODE) {
                duration = Math.round(mStackSlider.getDurationForNeutralPosition());
            } else {
                duration = Math.round(mStackSlider.getDurationForOffscreenPosition());
            }

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyValuesHolder<Float> snapBackY =
                    new PropertyValuesHolder<Float>("YProgress", finalYProgress);
            PropertyValuesHolder<Float> snapBackX =
                    new PropertyValuesHolder<Float>("XProgress", 0.0f);
            ObjectAnimator pa = new ObjectAnimator(duration, animationSlider,
                    snapBackX, snapBackY);
            pa.start();
        }

        mActivePointerId = INVALID_POINTER;
        mSwipeGestureType = GESTURE_NONE;
    }

    private class StackSlider {
        View mView;
        float mYProgress;
        float mXProgress;

        static final int NORMAL_MODE = 0;
        static final int BEGINNING_OF_STACK_MODE = 1;
        static final int END_OF_STACK_MODE = 2;

        int mMode = NORMAL_MODE;

        public StackSlider() {
        }

        public StackSlider(StackSlider copy) {
            mView = copy.mView;
            mYProgress = copy.mYProgress;
            mXProgress = copy.mXProgress;
            mMode = copy.mMode;
        }

        private float cubic(float r) {
            return (float) (Math.pow(2 * r - 1, 3) + 1) / 2.0f;
        }

        private float highlightAlphaInterpolator(float r) {
            float pivot = 0.4f;
            if (r < pivot) {
                return 0.85f * cubic(r / pivot);
            } else {
                return 0.85f * cubic(1 - (r - pivot) / (1 - pivot));
            }
        }

        private float viewAlphaInterpolator(float r) {
            float pivot = 0.3f;
            if (r > pivot) {
                return (r - pivot) / (1 - pivot);
            } else {
                return 0;
            }
        }

        private float rotationInterpolator(float r) {
            float pivot = 0.2f;
            if (r < pivot) {
                return 0;
            } else {
                return (r - pivot) / (1 - pivot);
            }
        }

        void setView(View v) {
            mView = v;
        }

        public void setYProgress(float r) {
            // enforce r between 0 and 1
            r = Math.min(1.0f, r);
            r = Math.max(0, r);

            mYProgress = r;
            final LayoutParams viewLp = (LayoutParams) mView.getLayoutParams();
            final LayoutParams highlightLp = (LayoutParams) mHighlight.getLayoutParams();

            int stackDirection = (mStackMode == ITEMS_SLIDE_UP) ? 1 : -1;

            switch (mMode) {
                case NORMAL_MODE:
                    viewLp.setVerticalOffset(Math.round(-r * stackDirection * mSlideAmount));
                    highlightLp.setVerticalOffset(Math.round(-r * stackDirection * mSlideAmount));
                    mHighlight.setAlpha(highlightAlphaInterpolator(r));

                    float alpha = viewAlphaInterpolator(1 - r);

                    // We make sure that views which can't be seen (have 0 alpha) are also invisible
                    // so that they don't interfere with click events.
                    if (mView.getAlpha() == 0 && alpha != 0 && mView.getVisibility() != VISIBLE) {
                        mView.setVisibility(VISIBLE);
                    } else if (alpha == 0 && mView.getAlpha() != 0
                            && mView.getVisibility() == VISIBLE) {
                        mView.setVisibility(INVISIBLE);
                    }

                    mView.setAlpha(alpha);
                    mView.setRotationX(stackDirection * 90.0f * rotationInterpolator(r));
                    mHighlight.setRotationX(stackDirection * 90.0f * rotationInterpolator(r));
                    break;
                case BEGINNING_OF_STACK_MODE:
                    r = r * 0.2f;
                    viewLp.setVerticalOffset(Math.round(-stackDirection * r * mSlideAmount));
                    highlightLp.setVerticalOffset(Math.round(-stackDirection * r * mSlideAmount));
                    mHighlight.setAlpha(highlightAlphaInterpolator(r));
                    break;
                case END_OF_STACK_MODE:
                    r = (1-r) * 0.2f;
                    viewLp.setVerticalOffset(Math.round(stackDirection * r * mSlideAmount));
                    highlightLp.setVerticalOffset(Math.round(stackDirection * r * mSlideAmount));
                    mHighlight.setAlpha(highlightAlphaInterpolator(r));
                    break;
            }
        }

        public void setXProgress(float r) {
            // enforce r between 0 and 1
            r = Math.min(2.0f, r);
            r = Math.max(-2.0f, r);

            mXProgress = r;

            final LayoutParams viewLp = (LayoutParams) mView.getLayoutParams();
            final LayoutParams highlightLp = (LayoutParams) mHighlight.getLayoutParams();

            r *= 0.2f;
            viewLp.setHorizontalOffset(Math.round(r * mSlideAmount));
            highlightLp.setHorizontalOffset(Math.round(r * mSlideAmount));
        }

        void setMode(int mode) {
            mMode = mode;
        }

        float getDurationForNeutralPosition() {
            return getDuration(false, 0);
        }

        float getDurationForOffscreenPosition() {
            return getDuration(true, 0);
        }

        float getDurationForNeutralPosition(float velocity) {
            return getDuration(false, velocity);
        }

        float getDurationForOffscreenPosition(float velocity) {
            return getDuration(true, velocity);
        }

        private float getDuration(boolean invert, float velocity) {
            if (mView != null) {
                final LayoutParams viewLp = (LayoutParams) mView.getLayoutParams();

                float d = (float) Math.sqrt(Math.pow(viewLp.horizontalOffset, 2) +
                        Math.pow(viewLp.verticalOffset, 2));
                float maxd = (float) Math.sqrt(Math.pow(mSlideAmount, 2) +
                        Math.pow(0.4f * mSlideAmount, 2));

                if (velocity == 0) {
                    return (invert ? (1 - d / maxd) : d / maxd) * DEFAULT_ANIMATION_DURATION;
                } else {
                    float duration = invert ? d / Math.abs(velocity) :
                            (maxd - d) / Math.abs(velocity);
                    if (duration < MINIMUM_ANIMATION_DURATION ||
                            duration > DEFAULT_ANIMATION_DURATION) {
                        return getDuration(invert, 0);
                    } else {
                        return duration;
                    }
                }
            }
            return 0;
        }

        public float getYProgress() {
            return mYProgress;
        }

        public float getXProgress() {
            return mXProgress;
        }
    }

    @Override
    public void onRemoteAdapterConnected() {
        super.onRemoteAdapterConnected();
        // On first run, we want to set the stack to the end.
        if (mAdapter != null && mWhichChild == -1) {
            mWhichChild = mAdapter.getCount() - 1;
        }
        if (mWhichChild >= 0) {
            setDisplayedChild(mWhichChild);
        }
    }

    LayoutParams createOrReuseLayoutParams(View v) {
        final ViewGroup.LayoutParams currentLp = v.getLayoutParams();
        if (currentLp instanceof LayoutParams) {
            LayoutParams lp = (LayoutParams) currentLp;
            lp.setHorizontalOffset(0);
            lp.setVerticalOffset(0);
            lp.width = 0;
            lp.width = 0;
            return lp;
        }
        return new LayoutParams(v);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean dataChanged = mDataChanged;
        if (dataChanged) {
            handleDataChanged();

            // if the data changes, mWhichChild might be out of the bounds of the adapter
            // in this case, we reset mWhichChild to the beginning
            if (mWhichChild >= mAdapter.getCount())
                mWhichChild = 0;

            showOnly(mWhichChild, true, true);
            refreshChildren();
        }

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            int childRight = mPaddingLeft + child.getMeasuredWidth();
            int childBottom = mPaddingTop + child.getMeasuredHeight();
            LayoutParams lp = (LayoutParams) child.getLayoutParams();

            child.layout(mPaddingLeft + lp.horizontalOffset, mPaddingTop + lp.verticalOffset,
                    childRight + lp.horizontalOffset, childBottom + lp.verticalOffset);

        }

        mDataChanged = false;
        onLayout();
    }

    private void measureChildren() {
        final int count = getChildCount();
        final int childWidth = mMeasuredWidth - mPaddingLeft - mPaddingRight;
        final int childHeight = Math.round(mMeasuredHeight*(1-PERSPECTIVE_SHIFT_FACTOR))
                - mPaddingTop - mPaddingBottom;

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);
        final int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);

        boolean haveChildRefSize = (mReferenceChildWidth != -1 && mReferenceChildHeight != -1);

        // We need to deal with the case where our parent hasn't told us how
        // big we should be. In this case we should
        float factor = 1/(1 - PERSPECTIVE_SHIFT_FACTOR);
        if (heightSpecMode == MeasureSpec.UNSPECIFIED) {
            heightSpecSize = haveChildRefSize ?
                    Math.round(mReferenceChildHeight * (1 + factor)) +
                    mPaddingTop + mPaddingBottom : 0;
        } else if (heightSpecMode == MeasureSpec.AT_MOST) {
            heightSpecSize = haveChildRefSize ? Math.min(
                    Math.round(mReferenceChildHeight * (1 + factor)) + mPaddingTop +
                    mPaddingBottom, heightSpecSize) : 0;
        }

        if (widthSpecMode == MeasureSpec.UNSPECIFIED) {
            widthSpecSize = haveChildRefSize ? mReferenceChildWidth + mPaddingLeft +
                    mPaddingRight : 0;
        } else if (heightSpecMode == MeasureSpec.AT_MOST) {
            widthSpecSize = haveChildRefSize ? Math.min(mReferenceChildWidth + mPaddingLeft +
                    mPaddingRight, widthSpecSize) : 0;
        }

        setMeasuredDimension(widthSpecSize, heightSpecSize);
        measureChildren();
    }

    class LayoutParams extends ViewGroup.LayoutParams {
        int horizontalOffset;
        int verticalOffset;
        View mView;

        LayoutParams(View view) {
            super(0, 0);
            width = 0;
            height = 0;
            horizontalOffset = 0;
            verticalOffset = 0;
            mView = view;
        }

        LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
            horizontalOffset = 0;
            verticalOffset = 0;
            width = 0;
            height = 0;
        }

        private Rect parentRect = new Rect();
        void invalidateGlobalRegion(View v, Rect r) {
            View p = v;
            if (!(v.getParent() != null && v.getParent() instanceof View)) return;

            boolean firstPass = true;
            parentRect.set(0, 0, 0, 0);
            int depth = 0;
            while (p.getParent() != null && p.getParent() instanceof View
                    && !parentRect.contains(r)) {
                if (!firstPass) {
                    r.offset(p.getLeft() - p.getScrollX(), p.getTop() - p.getScrollY());
                    depth++;
                }
                firstPass = false;
                p = (View) p.getParent();
                parentRect.set(p.getScrollX(), p.getScrollY(),
                               p.getWidth() + p.getScrollX(), p.getHeight() + p.getScrollY());

                // TODO: we need to stop early here if we've hit the edge of the screen
                // so as to prevent us from walking too high in the hierarchy. A lot of this
                // code might become a lot more straightforward.
            }

            if (depth > mAncestorHeight) {
                mAncestorContainingAllChildren = (ViewGroup) p;
                mAncestorHeight = depth;
                disableParentalClipping();
            }

            p.invalidate(r.left, r.top, r.right, r.bottom);
        }

        private Rect invalidateRect = new Rect();
        private RectF invalidateRectf = new RectF();
        // This is public so that ObjectAnimator can access it
        public void setVerticalOffset(int newVerticalOffset) {
            int offsetDelta = newVerticalOffset - verticalOffset;
            verticalOffset = newVerticalOffset;

            if (mView != null) {
                mView.requestLayout();
                int top = Math.min(mView.getTop() + offsetDelta, mView.getTop());
                int bottom = Math.max(mView.getBottom() + offsetDelta, mView.getBottom());

                invalidateRectf.set(mView.getLeft(),  top, mView.getRight(), bottom);

                float xoffset = -invalidateRectf.left;
                float yoffset = -invalidateRectf.top;
                invalidateRectf.offset(xoffset, yoffset);
                mView.getMatrix().mapRect(invalidateRectf);
                invalidateRectf.offset(-xoffset, -yoffset);
                invalidateRect.set((int) Math.floor(invalidateRectf.left),
                        (int) Math.floor(invalidateRectf.top),
                        (int) Math.ceil(invalidateRectf.right),
                        (int) Math.ceil(invalidateRectf.bottom));

                invalidateGlobalRegion(mView, invalidateRect);
            }
        }

        public void setHorizontalOffset(int newHorizontalOffset) {
            int offsetDelta = newHorizontalOffset - horizontalOffset;
            horizontalOffset = newHorizontalOffset;

            if (mView != null) {
                mView.requestLayout();
                int left = Math.min(mView.getLeft() + offsetDelta, mView.getLeft());
                int right = Math.max(mView.getRight() + offsetDelta, mView.getRight());
                invalidateRectf.set(left,  mView.getTop(), right, mView.getBottom());

                float xoffset = -invalidateRectf.left;
                float yoffset = -invalidateRectf.top;
                invalidateRectf.offset(xoffset, yoffset);
                mView.getMatrix().mapRect(invalidateRectf);
                invalidateRectf.offset(-xoffset, -yoffset);

                invalidateRect.set((int) Math.floor(invalidateRectf.left),
                        (int) Math.floor(invalidateRectf.top),
                        (int) Math.ceil(invalidateRectf.right),
                        (int) Math.ceil(invalidateRectf.bottom));

                invalidateGlobalRegion(mView, invalidateRect);
            }
        }
    }

    private static class HolographicHelper {
        private final Paint mHolographicPaint = new Paint();
        private final Paint mErasePaint = new Paint();
        private final Paint mBlurPaint = new Paint();

        HolographicHelper(Context context) {
            initializePaints(context);
        }

        void initializePaints(Context context) {
            final float density = context.getResources().getDisplayMetrics().density;

            mHolographicPaint.setColor(0xff6699ff);
            mHolographicPaint.setFilterBitmap(true);
            mHolographicPaint.setMaskFilter(TableMaskFilter.CreateClipTable(0, 30));
            mErasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            mErasePaint.setFilterBitmap(true);
            mBlurPaint.setMaskFilter(new BlurMaskFilter(2*density, BlurMaskFilter.Blur.NORMAL));
        }

        Bitmap createOutline(View v) {
            if (v.getMeasuredWidth() == 0 || v.getMeasuredHeight() == 0) {
                return null;
            }

            Bitmap bitmap = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            float rotationX = v.getRotationX();
            float rotation = v.getRotation();
            float translationY = v.getTranslationY();
            v.setRotationX(0);
            v.setRotation(0);
            v.setTranslationY(0);
            v.draw(canvas);
            v.setRotationX(rotationX);
            v.setRotation(rotation);
            v.setTranslationY(translationY);

            drawOutline(canvas, bitmap);
            return bitmap;
        }

        final Matrix id = new Matrix();
        void drawOutline(Canvas dest, Bitmap src) {
            int[] xy = new int[2];
            Bitmap mask = src.extractAlpha(mBlurPaint, xy);
            Canvas maskCanvas = new Canvas(mask);
            maskCanvas.drawBitmap(src, -xy[0], -xy[1], mErasePaint);
            dest.drawColor(0, PorterDuff.Mode.CLEAR);
            dest.setMatrix(id);
            dest.drawBitmap(mask, xy[0], xy[1], mHolographicPaint);
            mask.recycle();
        }
    }
}
