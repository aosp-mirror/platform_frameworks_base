/* Copyright (C) 2010 The Android Open Source Project
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

import java.lang.ref.WeakReference;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.TableMaskFilter;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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
    private static final int DEFAULT_ANIMATION_DURATION = 400;
    private static final int MINIMUM_ANIMATION_DURATION = 50;
    private static final int STACK_RELAYOUT_DURATION = 100;

    /**
     * Parameters effecting the perspective visuals
     */
    private static final float PERSPECTIVE_SHIFT_FACTOR_Y = 0.1f;
    private static final float PERSPECTIVE_SHIFT_FACTOR_X = 0.1f;

    private float mPerspectiveShiftX;
    private float mPerspectiveShiftY;
    private float mNewPerspectiveShiftX;
    private float mNewPerspectiveShiftY;

    @SuppressWarnings({"FieldCanBeLocal"})
    private static final float PERSPECTIVE_SCALE_FACTOR = 0f;

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
    private static final float SWIPE_THRESHOLD_RATIO = 0.2f;

    /**
     * Specifies the total distance, relative to the size of the stack,
     * that views will be slid, either up or down
     */
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

    private final Rect mTouchRect = new Rect();

    private static final int MIN_TIME_BETWEEN_INTERACTION_AND_AUTOADVANCE = 5000;

    private static final long MIN_TIME_BETWEEN_SCROLLS = 100;

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
    private boolean mTransitionIsSetup = false;
    private int mResOutColor;
    private int mClickColor;

    private static HolographicHelper sHolographicHelper;
    private ImageView mHighlight;
    private ImageView mClickFeedback;
    private boolean mClickFeedbackIsValid = false;
    private StackSlider mStackSlider;
    private boolean mFirstLayoutHappened = false;
    private long mLastInteractionTime = 0;
    private long mLastScrollTime;
    private int mStackMode;
    private int mFramePadding;
    private final Rect stackInvalidateRect = new Rect();

    /**
     * {@inheritDoc}
     */
    public StackView(Context context) {
        this(context, null);
    }

    /**
     * {@inheritDoc}
     */
    public StackView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.stackViewStyle);
    }

    /**
     * {@inheritDoc}
     */
    public StackView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.StackView, defStyleAttr, 0);

        mResOutColor = a.getColor(
                com.android.internal.R.styleable.StackView_resOutColor, 0);
        mClickColor = a.getColor(
                com.android.internal.R.styleable.StackView_clickColor, 0);

        a.recycle();
        initStackView();
    }

    private void initStackView() {
        configureViewAnimator(NUM_ACTIVE_VIEWS, 1);
        setStaticTransformationsEnabled(true);
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mActivePointerId = INVALID_POINTER;

        mHighlight = new ImageView(getContext());
        mHighlight.setLayoutParams(new LayoutParams(mHighlight));
        addViewInLayout(mHighlight, -1, new LayoutParams(mHighlight));

        mClickFeedback = new ImageView(getContext());
        mClickFeedback.setLayoutParams(new LayoutParams(mClickFeedback));
        addViewInLayout(mClickFeedback, -1, new LayoutParams(mClickFeedback));
        mClickFeedback.setVisibility(INVISIBLE);

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
    void transformViewForTransition(int fromIndex, int toIndex, final View view, boolean animate) {
        if (!animate) {
            ((StackFrame) view).cancelSliderAnimator();
            view.setRotationX(0f);
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.setVerticalOffset(0);
            lp.setHorizontalOffset(0);
        }

        if (fromIndex == -1 && toIndex == getNumActiveViews() -1) {
            transformViewAtIndex(toIndex, view, false);
            view.setVisibility(VISIBLE);
            view.setAlpha(1.0f);
        } else if (fromIndex == 0 && toIndex == 1) {
            // Slide item in
            ((StackFrame) view).cancelSliderAnimator();
            view.setVisibility(VISIBLE);

            int duration = Math.round(mStackSlider.getDurationForNeutralPosition(mYVelocity));
            StackSlider animationSlider = new StackSlider(mStackSlider);
            animationSlider.setView(view);

            if (animate) {
                PropertyValuesHolder slideInY = PropertyValuesHolder.ofFloat("YProgress", 0.0f);
                PropertyValuesHolder slideInX = PropertyValuesHolder.ofFloat("XProgress", 0.0f);
                ObjectAnimator slideIn = ObjectAnimator.ofPropertyValuesHolder(animationSlider,
                        slideInX, slideInY);
                slideIn.setDuration(duration);
                slideIn.setInterpolator(new LinearInterpolator());
                ((StackFrame) view).setSliderAnimator(slideIn);
                slideIn.start();
            } else {
                animationSlider.setYProgress(0f);
                animationSlider.setXProgress(0f);
            }
        } else if (fromIndex == 1 && toIndex == 0) {
            // Slide item out
            ((StackFrame) view).cancelSliderAnimator();
            int duration = Math.round(mStackSlider.getDurationForOffscreenPosition(mYVelocity));

            StackSlider animationSlider = new StackSlider(mStackSlider);
            animationSlider.setView(view);
            if (animate) {
                PropertyValuesHolder slideOutY = PropertyValuesHolder.ofFloat("YProgress", 1.0f);
                PropertyValuesHolder slideOutX = PropertyValuesHolder.ofFloat("XProgress", 0.0f);
                ObjectAnimator slideOut = ObjectAnimator.ofPropertyValuesHolder(animationSlider,
                        slideOutX, slideOutY);
                slideOut.setDuration(duration);
                slideOut.setInterpolator(new LinearInterpolator());
                ((StackFrame) view).setSliderAnimator(slideOut);
                slideOut.start();
            } else {
                animationSlider.setYProgress(1.0f);
                animationSlider.setXProgress(0f);
            }
        } else if (toIndex == 0) {
            // Make sure this view that is "waiting in the wings" is invisible
            view.setAlpha(0.0f);
            view.setVisibility(INVISIBLE);
        } else if ((fromIndex == 0 || fromIndex == 1) && toIndex > 1) {
            view.setVisibility(VISIBLE);
            view.setAlpha(1.0f);
            view.setRotationX(0f);
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.setVerticalOffset(0);
            lp.setHorizontalOffset(0);
        } else if (fromIndex == -1) {
            view.setAlpha(1.0f);
            view.setVisibility(VISIBLE);
        } else if (toIndex == -1) {
            if (animate) {
                postDelayed(new Runnable() {
                    public void run() {
                        view.setAlpha(0);
                    }
                }, STACK_RELAYOUT_DURATION);
            } else {
                view.setAlpha(0f);
            }
        }

        // Implement the faked perspective
        if (toIndex != -1) {
            transformViewAtIndex(toIndex, view, animate);
        }
    }

    private void transformViewAtIndex(int index, final View view, boolean animate) {
        final float maxPerspectiveShiftY = mPerspectiveShiftY;
        final float maxPerspectiveShiftX = mPerspectiveShiftX;

        if (mStackMode == ITEMS_SLIDE_DOWN) {
            index = mMaxNumActiveViews - index - 1;
            if (index == mMaxNumActiveViews - 1) index--;
        } else {
            index--;
            if (index < 0) index++;
        }

        float r = (index * 1.0f) / (mMaxNumActiveViews - 2);

        final float scale = 1 - PERSPECTIVE_SCALE_FACTOR * (1 - r);

        float perspectiveTranslationY = r * maxPerspectiveShiftY;
        float scaleShiftCorrectionY = (scale - 1) *
                (getMeasuredHeight() * (1 - PERSPECTIVE_SHIFT_FACTOR_Y) / 2.0f);
        final float transY = perspectiveTranslationY + scaleShiftCorrectionY;

        float perspectiveTranslationX = (1 - r) * maxPerspectiveShiftX;
        float scaleShiftCorrectionX =  (1 - scale) *
                (getMeasuredWidth() * (1 - PERSPECTIVE_SHIFT_FACTOR_X) / 2.0f);
        final float transX = perspectiveTranslationX + scaleShiftCorrectionX;

        // If this view is currently being animated for a certain position, we need to cancel
        // this animation so as not to interfere with the new transformation.
        if (view instanceof StackFrame) {
            ((StackFrame) view).cancelTransformAnimator();
        }

        if (animate) {
            PropertyValuesHolder translationX = PropertyValuesHolder.ofFloat("translationX", transX);
            PropertyValuesHolder translationY = PropertyValuesHolder.ofFloat("translationY", transY);
            PropertyValuesHolder scalePropX = PropertyValuesHolder.ofFloat("scaleX", scale);
            PropertyValuesHolder scalePropY = PropertyValuesHolder.ofFloat("scaleY", scale);

            ObjectAnimator oa = ObjectAnimator.ofPropertyValuesHolder(view, scalePropX, scalePropY,
                    translationY, translationX);
            oa.setDuration(STACK_RELAYOUT_DURATION);
            if (view instanceof StackFrame) {
                ((StackFrame) view).setTransformAnimator(oa);
            }
            oa.start();
        } else {
            view.setTranslationX(transX);
            view.setTranslationY(transY);
            view.setScaleX(scale);
            view.setScaleY(scale);
        }
    }

    private void setupStackSlider(View v, int mode) {
        mStackSlider.setMode(mode);
        if (v != null) {
            mHighlight.setImageBitmap(sHolographicHelper.createResOutline(v, mResOutColor));
            mHighlight.setRotation(v.getRotation());
            mHighlight.setTranslationY(v.getTranslationY());
            mHighlight.setTranslationX(v.getTranslationX());
            mHighlight.bringToFront();
            v.bringToFront();
            mStackSlider.setView(v);

            v.setVisibility(VISIBLE);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @android.view.RemotableViewMethod
    public void showNext() {
        if (mSwipeGestureType != GESTURE_NONE) return;
        if (!mTransitionIsSetup) {
            View v = getViewAtRelativeIndex(1);
            if (v != null) {
                setupStackSlider(v, StackSlider.NORMAL_MODE);
                mStackSlider.setYProgress(0);
                mStackSlider.setXProgress(0);
            }
        }
        super.showNext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @android.view.RemotableViewMethod
    public void showPrevious() {
        if (mSwipeGestureType != GESTURE_NONE) return;
        if (!mTransitionIsSetup) {
            View v = getViewAtRelativeIndex(0);
            if (v != null) {
                setupStackSlider(v, StackSlider.NORMAL_MODE);
                mStackSlider.setYProgress(1);
                mStackSlider.setXProgress(0);
            }
        }
        super.showPrevious();
    }

    @Override
    void showOnly(int childIndex, boolean animate) {
        super.showOnly(childIndex, animate);

        // Here we need to make sure that the z-order of the children is correct
        for (int i = mCurrentWindowEnd; i >= mCurrentWindowStart; i--) {
            int index = modulo(i, getWindowSize());
            ViewAndMetaData vm = mViewsMap.get(index);
            if (vm != null) {
                View v = mViewsMap.get(index).view;
                if (v != null) v.bringToFront();
            }
        }
        if (mHighlight != null) {
            mHighlight.bringToFront();
        }
        mTransitionIsSetup = false;
        mClickFeedbackIsValid = false;
    }

    void updateClickFeedback() {
        if (!mClickFeedbackIsValid) {
            View v = getViewAtRelativeIndex(1);
            if (v != null) {
                mClickFeedback.setImageBitmap(
                        sHolographicHelper.createClickOutline(v, mClickColor));
                mClickFeedback.setTranslationX(v.getTranslationX());
                mClickFeedback.setTranslationY(v.getTranslationY());
            }
            mClickFeedbackIsValid = true;
        }
    }

    @Override
    void showTapFeedback(View v) {
        updateClickFeedback();
        mClickFeedback.setVisibility(VISIBLE);
        mClickFeedback.bringToFront();
        invalidate();
    }

    @Override
    void hideTapFeedback(View v) {
        mClickFeedback.setVisibility(INVISIBLE);
        invalidate();
    }

    private void updateChildTransforms() {
        for (int i = 0; i < getNumActiveViews(); i++) {
            View v = getViewAtRelativeIndex(i);
            if (v != null) {
                transformViewAtIndex(i, v, false);
            }
        }
    }

    private static class StackFrame extends FrameLayout {
        WeakReference<ObjectAnimator> transformAnimator;
        WeakReference<ObjectAnimator> sliderAnimator;

        public StackFrame(Context context) {
            super(context);
        }

        void setTransformAnimator(ObjectAnimator oa) {
            transformAnimator = new WeakReference<ObjectAnimator>(oa);
        }

        void setSliderAnimator(ObjectAnimator oa) {
            sliderAnimator = new WeakReference<ObjectAnimator>(oa);
        }

        boolean cancelTransformAnimator() {
            if (transformAnimator != null) {
                ObjectAnimator oa = transformAnimator.get();
                if (oa != null) {
                    oa.cancel();
                    return true;
                }
            }
            return false;
        }

        boolean cancelSliderAnimator() {
            if (sliderAnimator != null) {
                ObjectAnimator oa = sliderAnimator.get();
                if (oa != null) {
                    oa.cancel();
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    FrameLayout getFrameForChild() {
        StackFrame fl = new StackFrame(mContext);
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
        boolean expandClipRegion = false;

        canvas.getClipBounds(stackInvalidateRect);
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child =  getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if ((lp.horizontalOffset == 0 && lp.verticalOffset == 0) ||
                    child.getAlpha() == 0f || child.getVisibility() != VISIBLE) {
                lp.resetInvalidateRect();
            }
            Rect childInvalidateRect = lp.getInvalidateRect();
            if (!childInvalidateRect.isEmpty()) {
                expandClipRegion = true;
                stackInvalidateRect.union(childInvalidateRect);
            }
        }

        // We only expand the clip bounds if necessary.
        if (expandClipRegion) {
            canvas.save(Canvas.CLIP_SAVE_FLAG);
            canvas.clipRect(stackInvalidateRect, Region.Op.UNION);
            super.dispatchDraw(canvas);
            canvas.restore();
        } else {
            super.dispatchDraw(canvas);
        }
    }

    private void onLayout() {
        if (!mFirstLayoutHappened) {
            mFirstLayoutHappened = true;
            updateChildTransforms();
        }

        final int newSlideAmount = Math.round(SLIDE_UP_RATIO * getMeasuredHeight());
        if (mSlideAmount != newSlideAmount) {
            mSlideAmount = newSlideAmount;
            mSwipeThreshold = Math.round(SWIPE_THRESHOLD_RATIO * newSlideAmount);
        }

        if (Float.compare(mPerspectiveShiftY, mNewPerspectiveShiftY) != 0 ||
                Float.compare(mPerspectiveShiftX, mNewPerspectiveShiftX) != 0) {

            mPerspectiveShiftY = mNewPerspectiveShiftY;
            mPerspectiveShiftX = mNewPerspectiveShiftX;
            updateChildTransforms();
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL: {
                    final float vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (vscroll < 0) {
                        pacedScroll(false);
                        return true;
                    } else if (vscroll > 0) {
                        pacedScroll(true);
                        return true;
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    // This ensures that the frequency of stack flips caused by scrolls is capped
    private void pacedScroll(boolean up) {
        long timeSinceLastScroll = System.currentTimeMillis() - mLastScrollTime;
        if (timeSinceLastScroll > MIN_TIME_BETWEEN_SCROLLS) {
            if (up) {
                showPrevious();
            } else {
                showNext();
            }
            mLastScrollTime = System.currentTimeMillis();
        }
    }

    /**
     * {@inheritDoc}
     */
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
            final int swipeGestureType = deltaY < 0 ? GESTURE_SLIDE_UP : GESTURE_SLIDE_DOWN;
            cancelLongPress();
            requestDisallowInterceptTouchEvent(true);

            if (mAdapter == null) return;
            final int adapterCount = getCount();

            int activeIndex;
            if (mStackMode == ITEMS_SLIDE_UP) {
                activeIndex = (swipeGestureType == GESTURE_SLIDE_DOWN) ? 0 : 1;
            } else {
                activeIndex = (swipeGestureType == GESTURE_SLIDE_DOWN) ? 1 : 0;
            }

            boolean endOfStack = mLoopViews && adapterCount == 1 && 
                ((mStackMode == ITEMS_SLIDE_UP && swipeGestureType == GESTURE_SLIDE_UP) ||
                 (mStackMode == ITEMS_SLIDE_DOWN && swipeGestureType == GESTURE_SLIDE_DOWN));
            boolean beginningOfStack = mLoopViews && adapterCount == 1 && 
                ((mStackMode == ITEMS_SLIDE_DOWN && swipeGestureType == GESTURE_SLIDE_UP) ||
                 (mStackMode == ITEMS_SLIDE_UP && swipeGestureType == GESTURE_SLIDE_DOWN));

            int stackMode;
            if (mLoopViews && !beginningOfStack && !endOfStack) {
                stackMode = StackSlider.NORMAL_MODE;
            } else if (mCurrentWindowStartUnbounded + activeIndex == -1 || beginningOfStack) {
                activeIndex++;
                stackMode = StackSlider.BEGINNING_OF_STACK_MODE;
            } else if (mCurrentWindowStartUnbounded + activeIndex == adapterCount - 1 || endOfStack) {
                stackMode = StackSlider.END_OF_STACK_MODE;
            } else {
                stackMode = StackSlider.NORMAL_MODE;
            }

            mTransitionIsSetup = stackMode == StackSlider.NORMAL_MODE;

            View v = getViewAtRelativeIndex(activeIndex);
            if (v == null) return;

            setupStackSlider(v, stackMode);

            // We only register this gesture if we've made it this far without a problem
            mSwipeGestureType = swipeGestureType;
            cancelHandleClick();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        super.onTouchEvent(ev);

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

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int activePointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(activePointerIndex);
        if (pointerId == mActivePointerId) {

            int activeViewIndex = (mSwipeGestureType == GESTURE_SLIDE_DOWN) ? 0 : 1;

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

                    mTouchRect.set(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
                    if (mTouchRect.contains(Math.round(x), Math.round(y))) {
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
        mLastInteractionTime = System.currentTimeMillis();

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
            // We reset the gesture variable, because otherwise we will ignore showPrevious() /
            // showNext();
            mSwipeGestureType = GESTURE_NONE;

            // Swipe threshold exceeded, swipe down
            if (mStackMode == ITEMS_SLIDE_UP) {
                showPrevious();
            } else {
                showNext();
            }
            mHighlight.bringToFront();
        } else if (deltaY < -mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_UP
                && mStackSlider.mMode == StackSlider.NORMAL_MODE) {
            // We reset the gesture variable, because otherwise we will ignore showPrevious() /
            // showNext();
            mSwipeGestureType = GESTURE_NONE;

            // Swipe threshold exceeded, swipe up
            if (mStackMode == ITEMS_SLIDE_UP) {
                showNext();
            } else {
                showPrevious();
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
            PropertyValuesHolder snapBackY = PropertyValuesHolder.ofFloat("YProgress", finalYProgress);
            PropertyValuesHolder snapBackX = PropertyValuesHolder.ofFloat("XProgress", 0.0f);
            ObjectAnimator pa = ObjectAnimator.ofPropertyValuesHolder(animationSlider,
                    snapBackX, snapBackY);
            pa.setDuration(duration);
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
            PropertyValuesHolder snapBackY =
                    PropertyValuesHolder.ofFloat("YProgress",finalYProgress);
            PropertyValuesHolder snapBackX = PropertyValuesHolder.ofFloat("XProgress", 0.0f);
            ObjectAnimator pa = ObjectAnimator.ofPropertyValuesHolder(animationSlider,
                    snapBackX, snapBackY);
            pa.setDuration(duration);
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
            if (mView == null) return;

            final LayoutParams viewLp = (LayoutParams) mView.getLayoutParams();
            final LayoutParams highlightLp = (LayoutParams) mHighlight.getLayoutParams();

            int stackDirection = (mStackMode == ITEMS_SLIDE_UP) ? 1 : -1;

            // We need to prevent any clipping issues which may arise by setting a layer type.
            // This doesn't come for free however, so we only want to enable it when required.
            if (Float.compare(0f, mYProgress) != 0 && Float.compare(1.0f, mYProgress) != 0) {
                if (mView.getLayerType() == LAYER_TYPE_NONE) {
                    mView.setLayerType(LAYER_TYPE_HARDWARE, null);
                }
            } else {
                if (mView.getLayerType() != LAYER_TYPE_NONE) {
                    mView.setLayerType(LAYER_TYPE_NONE, null);
                }
            }

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
                case END_OF_STACK_MODE:
                    r = r * 0.2f;
                    viewLp.setVerticalOffset(Math.round(-stackDirection * r * mSlideAmount));
                    highlightLp.setVerticalOffset(Math.round(-stackDirection * r * mSlideAmount));
                    mHighlight.setAlpha(highlightAlphaInterpolator(r));
                    break;
                case BEGINNING_OF_STACK_MODE:
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

            if (mView == null) return;
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

        // Used for animations
        @SuppressWarnings({"UnusedDeclaration"})
        public float getYProgress() {
            return mYProgress;
        }

        // Used for animations
        @SuppressWarnings({"UnusedDeclaration"})
        public float getXProgress() {
            return mXProgress;
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
        checkForAndHandleDataChanged();

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            int childRight = mPaddingLeft + child.getMeasuredWidth();
            int childBottom = mPaddingTop + child.getMeasuredHeight();
            LayoutParams lp = (LayoutParams) child.getLayoutParams();

            child.layout(mPaddingLeft + lp.horizontalOffset, mPaddingTop + lp.verticalOffset,
                    childRight + lp.horizontalOffset, childBottom + lp.verticalOffset);

        }
        onLayout();
    }

    @Override
    public void advance() {
        long timeSinceLastInteraction = System.currentTimeMillis() - mLastInteractionTime;

        if (mAdapter == null) return;
        final int adapterCount = getCount();
        if (adapterCount == 1 && mLoopViews) return;

        if (mSwipeGestureType == GESTURE_NONE &&
                timeSinceLastInteraction > MIN_TIME_BETWEEN_INTERACTION_AND_AUTOADVANCE) {
            showNext();
        }
    }

    private void measureChildren() {
        final int count = getChildCount();

        final int measuredWidth = getMeasuredWidth();
        final int measuredHeight = getMeasuredHeight();

        final int childWidth = Math.round(measuredWidth*(1-PERSPECTIVE_SHIFT_FACTOR_X))
                - mPaddingLeft - mPaddingRight;
        final int childHeight = Math.round(measuredHeight*(1-PERSPECTIVE_SHIFT_FACTOR_Y))
                - mPaddingTop - mPaddingBottom;

        int maxWidth = 0;
        int maxHeight = 0;

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.AT_MOST));

            if (child != mHighlight && child != mClickFeedback) {
                final int childMeasuredWidth = child.getMeasuredWidth();
                final int childMeasuredHeight = child.getMeasuredHeight();
                if (childMeasuredWidth > maxWidth) {
                    maxWidth = childMeasuredWidth;
                }
                if (childMeasuredHeight > maxHeight) {
                    maxHeight = childMeasuredHeight;
                }
            }
        }

        mNewPerspectiveShiftX = PERSPECTIVE_SHIFT_FACTOR_X * measuredWidth;
        mNewPerspectiveShiftY = PERSPECTIVE_SHIFT_FACTOR_Y * measuredHeight;

        // If we have extra space, we try and spread the items out
        if (maxWidth > 0 && count > 0 && maxWidth < childWidth) {
            mNewPerspectiveShiftX = measuredWidth - maxWidth;
        }

        if (maxHeight > 0 && count > 0 && maxHeight < childHeight) {
            mNewPerspectiveShiftY = measuredHeight - maxHeight;
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
        float factorY = 1/(1 - PERSPECTIVE_SHIFT_FACTOR_Y);
        if (heightSpecMode == MeasureSpec.UNSPECIFIED) {
            heightSpecSize = haveChildRefSize ?
                    Math.round(mReferenceChildHeight * (1 + factorY)) +
                    mPaddingTop + mPaddingBottom : 0;
        } else if (heightSpecMode == MeasureSpec.AT_MOST) {
            if (haveChildRefSize) {
                int height = Math.round(mReferenceChildHeight * (1 + factorY))
                        + mPaddingTop + mPaddingBottom;
                if (height <= heightSpecSize) {
                    heightSpecSize = height;
                } else {
                    heightSpecSize |= MEASURED_STATE_TOO_SMALL;

                }
            } else {
                heightSpecSize = 0;
            }
        }

        float factorX = 1/(1 - PERSPECTIVE_SHIFT_FACTOR_X);
        if (widthSpecMode == MeasureSpec.UNSPECIFIED) {
            widthSpecSize = haveChildRefSize ?
                    Math.round(mReferenceChildWidth * (1 + factorX)) +
                    mPaddingLeft + mPaddingRight : 0;
        } else if (heightSpecMode == MeasureSpec.AT_MOST) {
            if (haveChildRefSize) {
                int width = mReferenceChildWidth + mPaddingLeft + mPaddingRight;
                if (width <= widthSpecSize) {
                    widthSpecSize = width;
                } else {
                    widthSpecSize |= MEASURED_STATE_TOO_SMALL;
                }
            } else {
                widthSpecSize = 0;
            }
        }
        setMeasuredDimension(widthSpecSize, heightSpecSize);
        measureChildren();
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(StackView.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(StackView.class.getName());
    }

    class LayoutParams extends ViewGroup.LayoutParams {
        int horizontalOffset;
        int verticalOffset;
        View mView;
        private final Rect parentRect = new Rect();
        private final Rect invalidateRect = new Rect();
        private final RectF invalidateRectf = new RectF();
        private final Rect globalInvalidateRect = new Rect();

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

        void invalidateGlobalRegion(View v, Rect r) {
            // We need to make a new rect here, so as not to modify the one passed
            globalInvalidateRect.set(r);
            globalInvalidateRect.union(0, 0, getWidth(), getHeight());
            View p = v;
            if (!(v.getParent() != null && v.getParent() instanceof View)) return;

            boolean firstPass = true;
            parentRect.set(0, 0, 0, 0);
            while (p.getParent() != null && p.getParent() instanceof View
                    && !parentRect.contains(globalInvalidateRect)) {
                if (!firstPass) {
                    globalInvalidateRect.offset(p.getLeft() - p.getScrollX(), p.getTop()
                            - p.getScrollY());
                }
                firstPass = false;
                p = (View) p.getParent();
                parentRect.set(p.getScrollX(), p.getScrollY(),
                        p.getWidth() + p.getScrollX(), p.getHeight() + p.getScrollY());
                p.invalidate(globalInvalidateRect.left, globalInvalidateRect.top,
                        globalInvalidateRect.right, globalInvalidateRect.bottom);
            }

            p.invalidate(globalInvalidateRect.left, globalInvalidateRect.top,
                    globalInvalidateRect.right, globalInvalidateRect.bottom);
        }

        Rect getInvalidateRect() {
            return invalidateRect;
        }

        void resetInvalidateRect() {
            invalidateRect.set(0, 0, 0, 0);
        }

        // This is public so that ObjectAnimator can access it
        public void setVerticalOffset(int newVerticalOffset) {
            setOffsets(horizontalOffset, newVerticalOffset);
        }

        public void setHorizontalOffset(int newHorizontalOffset) {
            setOffsets(newHorizontalOffset, verticalOffset);
        }

        public void setOffsets(int newHorizontalOffset, int newVerticalOffset) {
            int horizontalOffsetDelta = newHorizontalOffset - horizontalOffset;
            horizontalOffset = newHorizontalOffset;
            int verticalOffsetDelta = newVerticalOffset - verticalOffset;
            verticalOffset = newVerticalOffset;

            if (mView != null) {
                mView.requestLayout();
                int left = Math.min(mView.getLeft() + horizontalOffsetDelta, mView.getLeft());
                int right = Math.max(mView.getRight() + horizontalOffsetDelta, mView.getRight());
                int top = Math.min(mView.getTop() + verticalOffsetDelta, mView.getTop());
                int bottom = Math.max(mView.getBottom() + verticalOffsetDelta, mView.getBottom());

                invalidateRectf.set(left, top, right, bottom);

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
        private static final int RES_OUT = 0;
        private static final int CLICK_FEEDBACK = 1;
        private float mDensity;
        private BlurMaskFilter mSmallBlurMaskFilter;
        private BlurMaskFilter mLargeBlurMaskFilter;
        private final Canvas mCanvas = new Canvas();
        private final Canvas mMaskCanvas = new Canvas();
        private final int[] mTmpXY = new int[2];
        private final Matrix mIdentityMatrix = new Matrix();

        HolographicHelper(Context context) {
            mDensity = context.getResources().getDisplayMetrics().density;

            mHolographicPaint.setFilterBitmap(true);
            mHolographicPaint.setMaskFilter(TableMaskFilter.CreateClipTable(0, 30));
            mErasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            mErasePaint.setFilterBitmap(true);

            mSmallBlurMaskFilter = new BlurMaskFilter(2 * mDensity, BlurMaskFilter.Blur.NORMAL);
            mLargeBlurMaskFilter = new BlurMaskFilter(4 * mDensity, BlurMaskFilter.Blur.NORMAL);
        }

        Bitmap createClickOutline(View v, int color) {
            return createOutline(v, CLICK_FEEDBACK, color);
        }

        Bitmap createResOutline(View v, int color) {
            return createOutline(v, RES_OUT, color);
        }

        Bitmap createOutline(View v, int type, int color) {
            mHolographicPaint.setColor(color);
            if (type == RES_OUT) {
                mBlurPaint.setMaskFilter(mSmallBlurMaskFilter);
            } else if (type == CLICK_FEEDBACK) {
                mBlurPaint.setMaskFilter(mLargeBlurMaskFilter);
            }

            if (v.getMeasuredWidth() == 0 || v.getMeasuredHeight() == 0) {
                return null;
            }

            Bitmap bitmap = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                    Bitmap.Config.ARGB_8888);
            mCanvas.setBitmap(bitmap);

            float rotationX = v.getRotationX();
            float rotation = v.getRotation();
            float translationY = v.getTranslationY();
            float translationX = v.getTranslationX();
            v.setRotationX(0);
            v.setRotation(0);
            v.setTranslationY(0);
            v.setTranslationX(0);
            v.draw(mCanvas);
            v.setRotationX(rotationX);
            v.setRotation(rotation);
            v.setTranslationY(translationY);
            v.setTranslationX(translationX);

            drawOutline(mCanvas, bitmap);
            mCanvas.setBitmap(null);
            return bitmap;
        }

        void drawOutline(Canvas dest, Bitmap src) {
            final int[] xy = mTmpXY;
            Bitmap mask = src.extractAlpha(mBlurPaint, xy);
            mMaskCanvas.setBitmap(mask);
            mMaskCanvas.drawBitmap(src, -xy[0], -xy[1], mErasePaint);
            dest.drawColor(0, PorterDuff.Mode.CLEAR);
            dest.setMatrix(mIdentityMatrix);
            dest.drawBitmap(mask, xy[0], xy[1], mHolographicPaint);
            mMaskCanvas.setBitmap(null);
            mask.recycle();
        }
    }
}
