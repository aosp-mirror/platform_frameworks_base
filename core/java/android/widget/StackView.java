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
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
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
     * These specify the different gesture states
     */
    private final int GESTURE_NONE = 0;
    private final int GESTURE_SLIDE_UP = 1;
    private final int GESTURE_SLIDE_DOWN = 2;

    /**
     * Specifies how far you need to swipe (up or down) before it
     * will be consider a completed gesture when you lift your finger
     */
    private final float SWIPE_THRESHOLD_RATIO = 0.35f;
    private final float SLIDE_UP_RATIO = 0.7f;

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
    private boolean mGestureComplete = false;
    private float mInitialY;
    private float mInitialX;
    private int mActivePointerId;
    private int mYOffset = 0;
    private int mYVelocity = 0;
    private int mSwipeGestureType = GESTURE_NONE;
    private int mViewHeight;
    private int mSwipeThreshold;
    private int mTouchSlop;
    private int mMaximumVelocity;
    private VelocityTracker mVelocityTracker;

    private boolean mFirstLayoutHappened = false;

    // TODO: temp hack to get this thing started
    int mIndex = 5;

    public StackView(Context context) {
        super(context);
        initStackView();
    }

    public StackView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initStackView();
    }

    private void initStackView() {
        configureViewAnimator(4, 2);
        setStaticTransformationsEnabled(true);
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();// + 5;
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mActivePointerId = INVALID_POINTER;
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
            PropertyAnimator fadeIn = new PropertyAnimator(DEFAULT_ANIMATION_DURATION,
                    view, "alpha", view.getAlpha(), 1.0f);
            fadeIn.start();
        } else if (fromIndex == mNumActiveViews - 1 && toIndex == mNumActiveViews - 2) {
            // Slide item in
            view.setVisibility(VISIBLE);
            LayoutParams lp = (LayoutParams) view.getLayoutParams();

            int largestDuration = (int) Math.round(
                    (lp.verticalOffset*1.0f/-mViewHeight)*DEFAULT_ANIMATION_DURATION);
            int duration = largestDuration;
            if (mYVelocity != 0) {
                duration = 1000*(0 - lp.verticalOffset)/Math.abs(mYVelocity);
            }

            duration = Math.min(duration, largestDuration);
            duration = Math.max(duration, MINIMUM_ANIMATION_DURATION);

            PropertyAnimator slideDown = new PropertyAnimator(duration, lp,
                    "verticalOffset", lp.verticalOffset, 0);
            slideDown.start();

            PropertyAnimator fadeIn = new PropertyAnimator(duration, view,
                    "alpha", view.getAlpha(), 1.0f);
            fadeIn.start();
        } else if (fromIndex == mNumActiveViews - 2 && toIndex == mNumActiveViews - 1) {
            // Slide item out
            LayoutParams lp = (LayoutParams) view.getLayoutParams();

            int largestDuration = (int) Math.round(
                    (1 - (lp.verticalOffset*1.0f/-mViewHeight))*DEFAULT_ANIMATION_DURATION);
            int duration = largestDuration;
            if (mYVelocity != 0) {
                duration = 1000*(lp.verticalOffset + mViewHeight)/Math.abs(mYVelocity);
            }

            duration = Math.min(duration, largestDuration);
            duration = Math.max(duration, MINIMUM_ANIMATION_DURATION);

            PropertyAnimator slideUp = new PropertyAnimator(duration, lp,
                    "verticalOffset", lp.verticalOffset, -mViewHeight);
            slideUp.start();

            PropertyAnimator fadeOut = new PropertyAnimator(duration, view,
                    "alpha", view.getAlpha(), 0.0f);
            fadeOut.start();
        } else if (fromIndex == -1 && toIndex == mNumActiveViews - 1) {
            // Make sure this view that is "waiting in the wings" is invisible
            view.setAlpha(0.0f);
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
        float rotation;

        if (!mRotations.containsKey(child)) {
            rotation = (float) (Math.random()*26 - 13);
            mRotations.put(child, rotation);
        } else {
            rotation = mRotations.get(child);
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
            mViewHeight = (int) Math.round(SLIDE_UP_RATIO*getMeasuredHeight());
            mSwipeThreshold = (int) Math.round(SWIPE_THRESHOLD_RATIO*mViewHeight);

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

                if ((int) Math.abs(deltaY) > mTouchSlop && mSwipeGestureType == GESTURE_NONE) {
                    mSwipeGestureType = deltaY < 0 ? GESTURE_SLIDE_UP : GESTURE_SLIDE_DOWN;
                    mGestureComplete = false;
                    cancelLongPress();
                    requestDisallowInterceptTouchEvent(true);
                }
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
                mGestureComplete = true;
            }
        }

        return mSwipeGestureType != GESTURE_NONE;
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
        float deltaY = newY - mInitialY;

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                if ((int) Math.abs(deltaY) > mTouchSlop && mSwipeGestureType == GESTURE_NONE) {
                    mSwipeGestureType = deltaY < 0 ? GESTURE_SLIDE_UP : GESTURE_SLIDE_DOWN;
                    mGestureComplete = false;
                    cancelLongPress();
                    requestDisallowInterceptTouchEvent(true);
                }

                if (!mGestureComplete) {
                    if (mSwipeGestureType == GESTURE_SLIDE_DOWN) {
                        View v = getViewAtRelativeIndex(mNumActiveViews - 1);
                        if (v != null) {
                            // This view is present but hidden, make sure it's visible
                            // if they pull down
                            v.setVisibility(VISIBLE);

                            float r = (deltaY-mTouchSlop)*1.0f / (mSwipeThreshold);
                            mYOffset = Math.min(-mViewHeight + (int)  Math.round(
                                    r*mSwipeThreshold) - mTouchSlop, 0);
                            LayoutParams lp = (LayoutParams) v.getLayoutParams();
                            lp.setVerticalOffset(mYOffset);

                            float alpha = Math.max(0.0f, 1.0f - (1.0f*mYOffset/-mViewHeight));
                            alpha = Math.min(1.0f, alpha);
                            v.setAlpha(alpha);
                        }
                        return true;
                    } else if (mSwipeGestureType == GESTURE_SLIDE_UP) {
                        View v = getViewAtRelativeIndex(mNumActiveViews - 2);

                        if (v != null) {
                            float r = -(deltaY*1.0f + mTouchSlop) / (mSwipeThreshold);
                            mYOffset = Math.min((int) Math.round(r*-mSwipeThreshold), 0);
                            LayoutParams lp = (LayoutParams) v.getLayoutParams();
                            lp.setVerticalOffset(mYOffset);

                            float alpha = Math.max(0.0f, 1.0f - (1.0f*mYOffset/-mViewHeight));
                            alpha = Math.min(1.0f, alpha);
                            v.setAlpha(alpha);
                        }
                        return true;
                    }
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
                mGestureComplete = true;
                mSwipeGestureType = GESTURE_NONE;
                mYOffset = 0;
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
                    if (touchRect.contains((int) Math.round(x), (int) Math.round(y))) {
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

        if (deltaY > mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_DOWN &&
                !mGestureComplete) {
            // Swipe threshold exceeded, swipe down
            showNext();
        } else if (deltaY < -mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_UP &&
                !mGestureComplete) {
            // Swipe threshold exceeded, swipe up
            showPrevious();
        } else if (mSwipeGestureType == GESTURE_SLIDE_UP && !mGestureComplete) {
            // Didn't swipe up far enough, snap back down
            View v = getViewAtRelativeIndex(mNumActiveViews - 2);
            if (v != null) {
                // Compute the animation duration based on how far they pulled it up
                LayoutParams lp = (LayoutParams) v.getLayoutParams();
                int duration = (int) Math.round(
                        lp.verticalOffset*1.0f/-mViewHeight*DEFAULT_ANIMATION_DURATION);
                duration = Math.max(MINIMUM_ANIMATION_DURATION, duration);

                // Animate back down
                PropertyAnimator slideDown = new PropertyAnimator(duration, lp,
                        "verticalOffset", lp.verticalOffset, 0);
                slideDown.start();
                PropertyAnimator fadeIn = new PropertyAnimator(duration, v,
                        "alpha",v.getAlpha(), 1.0f);
                fadeIn.start();
            }
        } else if (mSwipeGestureType == GESTURE_SLIDE_DOWN && !mGestureComplete) {
            // Didn't swipe down far enough, snap back up
            View v = getViewAtRelativeIndex(mNumActiveViews - 1);
            if (v != null) {
                // Compute the animation duration based on how far they pulled it down
                LayoutParams lp = (LayoutParams) v.getLayoutParams();
                int duration = (int) Math.round(
                        (1 - lp.verticalOffset*1.0f/-mViewHeight)*DEFAULT_ANIMATION_DURATION);
                duration = Math.max(MINIMUM_ANIMATION_DURATION, duration);

                // Animate back up
                PropertyAnimator slideUp = new PropertyAnimator(duration, lp,
                        "verticalOffset", lp.verticalOffset, -mViewHeight);
                slideUp.start();
                PropertyAnimator fadeOut = new PropertyAnimator(duration, v,
                        "alpha",v.getAlpha(), 0.0f);
                fadeOut.start();
            }
        }

        mActivePointerId = INVALID_POINTER;
        mGestureComplete = true;
        mSwipeGestureType = GESTURE_NONE;
        mYOffset = 0;
    }

    @Override
    public void onRemoteAdapterConnected() {
        super.onRemoteAdapterConnected();
        setDisplayedChild(mIndex);
    }
}
