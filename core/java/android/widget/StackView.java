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

import java.util.WeakHashMap;

import android.animation.PropertyAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
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
    private final int DEFAULT_ANIMATION_DURATION = 500;
    private final int MINIMUM_ANIMATION_DURATION = 50;

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

    private final WeakHashMap<View, Float> mRotations = new WeakHashMap<View, Float>();
    private final WeakHashMap<View, Integer>
            mChildrenToApplyTransformsTo = new WeakHashMap<View, Integer>();

    /**
     * Sentinel value for no current active pointer.
     * Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    /**
     * These variables are all related to the current state of touch interaction
     * with the stack
     */
    private float mInitialY;
    private float mInitialX;
    private int mActivePointerId;
    private int mYVelocity = 0;
    private int mSwipeGestureType = GESTURE_NONE;
    private int mViewHeight;
    private int mSwipeThreshold;
    private int mTouchSlop;
    private int mMaximumVelocity;
    private VelocityTracker mVelocityTracker;

    private ImageView mHighlight;
    private StackSlider mStackSlider;
    private boolean mFirstLayoutHappened = false;

    public StackView(Context context) {
        super(context);
        initStackView();
    }

    public StackView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initStackView();
    }

    private void initStackView() {
        configureViewAnimator(4, 2, false);
        setStaticTransformationsEnabled(true);
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mActivePointerId = INVALID_POINTER;

        mHighlight = new ImageView(getContext());
        mHighlight.setLayoutParams(new LayoutParams(mHighlight));
        addViewInLayout(mHighlight, -1, new LayoutParams(mHighlight));
        mStackSlider = new StackSlider();

        if (!sPaintsInitialized) {
            initializePaints();
        }
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

            PropertyAnimator fadeIn = new PropertyAnimator(DEFAULT_ANIMATION_DURATION,
                    view, "alpha", view.getAlpha(), 1.0f);
            fadeIn.start();
        } else if (fromIndex == mNumActiveViews - 1 && toIndex == mNumActiveViews - 2) {
            // Slide item in
            view.setVisibility(VISIBLE);

            LayoutParams lp = (LayoutParams) view.getLayoutParams();

            int largestDuration = Math.round(
                    (lp.verticalOffset*1.0f/-mViewHeight)*DEFAULT_ANIMATION_DURATION);
            int duration = largestDuration;
            if (mYVelocity != 0) {
                duration = 1000*(0 - lp.verticalOffset)/Math.abs(mYVelocity);
            }

            duration = Math.min(duration, largestDuration);
            duration = Math.max(duration, MINIMUM_ANIMATION_DURATION);

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyAnimator slideInY = new PropertyAnimator(duration, animationSlider,
                    "YProgress", mStackSlider.getYProgress(), 0);
            slideInY.setInterpolator(new LinearInterpolator());
            slideInY.start();
            PropertyAnimator slideInX = new PropertyAnimator(duration, animationSlider,
                    "XProgress", mStackSlider.getXProgress(), 0);
            slideInX.setInterpolator(new LinearInterpolator());
            slideInX.start();
        } else if (fromIndex == mNumActiveViews - 2 && toIndex == mNumActiveViews - 1) {
            // Slide item out
            LayoutParams lp = (LayoutParams) view.getLayoutParams();

            int largestDuration = Math.round(mStackSlider.getYProgress()*DEFAULT_ANIMATION_DURATION);
            int duration = largestDuration;
            if (mYVelocity != 0) {
                duration = 1000*(lp.verticalOffset + mViewHeight)/Math.abs(mYVelocity);
            }

            duration = Math.min(duration, largestDuration);
            duration = Math.max(duration, MINIMUM_ANIMATION_DURATION);

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyAnimator slideOutY = new PropertyAnimator(duration, animationSlider,
                    "YProgress", mStackSlider.getYProgress(), 1);
            slideOutY.setInterpolator(new LinearInterpolator());
            slideOutY.start();
            PropertyAnimator slideOutX = new PropertyAnimator(duration, animationSlider,
                    "XProgress", mStackSlider.getXProgress(), 0);
            slideOutX.setInterpolator(new LinearInterpolator());
            slideOutX.start();
        } else if (fromIndex == -1 && toIndex == mNumActiveViews - 1) {
            // Make sure this view that is "waiting in the wings" is invisible
            view.setAlpha(0.0f);
            view.setVisibility(INVISIBLE);
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.setVerticalOffset(-mViewHeight);
        } else if (toIndex == -1) {
            // Fade item out
            PropertyAnimator fadeOut = new PropertyAnimator(DEFAULT_ANIMATION_DURATION,
                    view, "alpha", view.getAlpha(), 0);
            fadeOut.start();
        }
    }

    /**
     * Apply any necessary tranforms for the child that is being added.
     */
    void applyTransformForChildAtIndex(View child, int relativeIndex) {
        if (!mRotations.containsKey(child)) {
            float rotation = (float) (Math.random()*26 - 13);
            mRotations.put(child, rotation);
        }

        // Child has been removed
        if (relativeIndex == -1) {
            if (mRotations.containsKey(child)) {
                mRotations.remove(child);
            }
            if (mChildrenToApplyTransformsTo.containsKey(child)) {
                mChildrenToApplyTransformsTo.remove(child);
            }
        }

        // if this view is already in the layout, we need to
        // wait until layout has finished in order to set the
        // pivot point of the rotation (requiring getMeasuredWidth/Height())
        mChildrenToApplyTransformsTo.put(child, relativeIndex);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (!mChildrenToApplyTransformsTo.isEmpty()) {
            for (View child: mChildrenToApplyTransformsTo.keySet()) {
                if (mRotations.containsKey(child)) {
                    child.setPivotX(child.getMeasuredWidth()/2);
                    child.setPivotY(child.getMeasuredHeight()/2);
                    child.setRotation(mRotations.get(child));
                }
            }
            mChildrenToApplyTransformsTo.clear();
        }

        if (!mFirstLayoutHappened) {
            mViewHeight = Math.round(SLIDE_UP_RATIO*getMeasuredHeight());
            mSwipeThreshold = Math.round(SWIPE_THRESHOLD_RATIO*mViewHeight);

            // TODO: Right now this walks all the way up the view hierarchy and disables
            // ClipChildren and ClipToPadding. We're probably going  to want to reset
            // these flags as well.
            setClipChildren(false);
            ViewGroup view = this;
            while (view.getParent() != null && view.getParent() instanceof ViewGroup) {
                view = (ViewGroup) view.getParent();
                view.setClipChildren(false);
                view.setClipToPadding(false);
            }
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
            mSwipeGestureType = deltaY < 0 ? GESTURE_SLIDE_UP : GESTURE_SLIDE_DOWN;
            cancelLongPress();
            requestDisallowInterceptTouchEvent(true);

            int activeIndex = mSwipeGestureType == GESTURE_SLIDE_DOWN ? mNumActiveViews - 1
                    : mNumActiveViews - 2;

            View v = getViewAtRelativeIndex(activeIndex);
            if (v != null) {
                mHighlight.setImageBitmap(createOutline(v));
                mHighlight.bringToFront();
                v.bringToFront();
                mStackSlider.setView(v);
                if (mSwipeGestureType == GESTURE_SLIDE_DOWN)
                    v.setVisibility(VISIBLE);
            }
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

                float rx = 0.3f*deltaX/(mViewHeight*1.0f);
                if (mSwipeGestureType == GESTURE_SLIDE_DOWN) {
                    float r = (deltaY-mTouchSlop*1.0f)/mViewHeight*1.0f;
                    mStackSlider.setYProgress(1 - r);
                    mStackSlider.setXProgress(rx);
                    return true;
                } else if (mSwipeGestureType == GESTURE_SLIDE_UP) {
                    float r = -(deltaY + mTouchSlop*1.0f)/mViewHeight*1.0f;
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
            // so end the
            handlePointerUp(ev);
        }
    }

    private void handlePointerUp(MotionEvent ev) {
        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        float newY = ev.getY(pointerIndex);
        int deltaY = (int) (newY - mInitialY);

        mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
        mYVelocity = (int) mVelocityTracker.getYVelocity(mActivePointerId);

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        if (deltaY > mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_DOWN) {
            // Swipe threshold exceeded, swipe down
            showNext();
            mHighlight.bringToFront();
        } else if (deltaY < -mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_UP) {
            // Swipe threshold exceeded, swipe up
            showPrevious();
            mHighlight.bringToFront();
        } else if (mSwipeGestureType == GESTURE_SLIDE_UP) {
            // Didn't swipe up far enough, snap back down
            int duration = Math.round(mStackSlider.getYProgress()*DEFAULT_ANIMATION_DURATION);

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyAnimator snapBackY = new PropertyAnimator(duration, animationSlider,
                    "YProgress", mStackSlider.getYProgress(), 0);
            snapBackY.setInterpolator(new LinearInterpolator());
            snapBackY.start();
            PropertyAnimator snapBackX = new PropertyAnimator(duration, animationSlider,
                    "XProgress", mStackSlider.getXProgress(), 0);
            snapBackX.setInterpolator(new LinearInterpolator());
            snapBackX.start();
        } else if (mSwipeGestureType == GESTURE_SLIDE_DOWN) {
            // Didn't swipe down far enough, snap back up
            int duration = Math.round((1 -
                    mStackSlider.getYProgress())*DEFAULT_ANIMATION_DURATION);
            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyAnimator snapBackY = new PropertyAnimator(duration, animationSlider,
                    "YProgress", mStackSlider.getYProgress(), 1);
            snapBackY.setInterpolator(new LinearInterpolator());
            snapBackY.start();
            PropertyAnimator snapBackX = new PropertyAnimator(duration, animationSlider,
                    "XProgress", mStackSlider.getXProgress(), 0);
            snapBackX.setInterpolator(new LinearInterpolator());
            snapBackX.start();
        }

        mActivePointerId = INVALID_POINTER;
        mSwipeGestureType = GESTURE_NONE;
    }

    private class StackSlider {
        View mView;
        float mYProgress;
        float mXProgress;

        public StackSlider() {
        }

        public StackSlider(StackSlider copy) {
            mView = copy.mView;
            mYProgress = copy.mYProgress;
            mXProgress = copy.mXProgress;
        }

        private float cubic(float r) {
            return (float) (Math.pow(2*r-1, 3) + 1)/2.0f;
        }

        private float highlightAlphaInterpolator(float r) {
            float pivot = 0.4f;
            if (r < pivot) {
                return 0.85f*cubic(r/pivot);
            } else {
                return 0.85f*cubic(1 - (r-pivot)/(1-pivot));
            }
        }

        private float viewAlphaInterpolator(float r) {
            float pivot = 0.3f;
            if (r > pivot) {
                return (r - pivot)/(1 - pivot);
            } else {
                return 0;
            }
        }

        private float rotationInterpolator(float r) {
            float pivot = 0.2f;
            if (r < pivot) {
                return 0;
            } else {
                return (r-pivot)/(1-pivot);
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

            viewLp.setVerticalOffset(Math.round(-r*mViewHeight));
            highlightLp.setVerticalOffset(Math.round(-r*mViewHeight));
            mHighlight.setAlpha(highlightAlphaInterpolator(r));

            float alpha = viewAlphaInterpolator(1-r);

            // We make sure that views which can't be seen (have 0 alpha) are also invisible
            // so that they don't interfere with click events.
            if (mView.getAlpha() == 0 && alpha != 0 && mView.getVisibility() != VISIBLE) {
                mView.setVisibility(VISIBLE);
            } else if (alpha == 0 && mView.getAlpha() != 0 && mView.getVisibility() == VISIBLE) {
                mView.setVisibility(INVISIBLE);
            }

            mView.setAlpha(viewAlphaInterpolator(1-r));
            mView.setRotationX(90.0f*rotationInterpolator(r));
            mHighlight.setRotationX(90.0f*rotationInterpolator(r));
        }

        public void setXProgress(float r) {
            // enforce r between 0 and 1
            r = Math.min(1.0f, r);
            r = Math.max(-1.0f, r);

            mXProgress = r;

            final LayoutParams viewLp = (LayoutParams) mView.getLayoutParams();
            final LayoutParams highlightLp = (LayoutParams) mHighlight.getLayoutParams();

            viewLp.setHorizontalOffset(Math.round(r*mViewHeight));
            highlightLp.setHorizontalOffset(Math.round(r*mViewHeight));
        }

        float getYProgress() {
            return mYProgress;
        }

        float getXProgress() {
            return mXProgress;
        }
    }

    @Override
    public void onRemoteAdapterConnected() {
        super.onRemoteAdapterConnected();
        setDisplayedChild(mWhichChild);
    }

    private static final Paint sHolographicPaint = new Paint();
    private static final Paint sErasePaint = new Paint();
    private static boolean sPaintsInitialized = false;
    private static final float STROKE_WIDTH = 3.0f;

    static void initializePaints() {
        sHolographicPaint.setColor(0xff6699ff);
        sHolographicPaint.setFilterBitmap(true);
        sErasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        sErasePaint.setFilterBitmap(true);
        sPaintsInitialized = true;
    }

    static Bitmap createOutline(View v) {
        if (v.getMeasuredWidth() == 0 || v.getMeasuredHeight() == 0) {
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float rotationX = v.getRotationX();
        v.setRotationX(0);
        canvas.concat(v.getMatrix());
        v.draw(canvas);
        v.setRotationX(rotationX);

        Bitmap outlineBitmap = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas outlineCanvas = new Canvas(outlineBitmap);
        drawOutline(outlineCanvas, bitmap);
        bitmap.recycle();
        return outlineBitmap;
    }

    static void drawOutline(Canvas dest, Bitmap src) {
        dest.drawColor(0, PorterDuff.Mode.CLEAR);

        Bitmap mask = src.extractAlpha();
        Matrix id = new Matrix();

        Matrix m = new Matrix();
        float xScale = STROKE_WIDTH*2/(src.getWidth());
        float yScale = STROKE_WIDTH*2/(src.getHeight());
        m.preScale(1+xScale, 1+yScale, src.getWidth()/2, src.getHeight()/2);
        dest.drawBitmap(mask, m, sHolographicPaint);

        dest.drawBitmap(src, id, sErasePaint);
        mask.recycle();
    }
}
