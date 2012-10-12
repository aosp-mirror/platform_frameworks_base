/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.TranslateAnimation;
import android.view.animation.Animation.AnimationListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ImageView.ScaleType;

import com.android.internal.R;

/**
 * A special widget containing two Sliders and a threshold for each.  Moving either slider beyond
 * the threshold will cause the registered OnTriggerListener.onTrigger() to be called with
 * whichHandle being {@link OnTriggerListener#LEFT_HANDLE} or {@link OnTriggerListener#RIGHT_HANDLE}
 * Equivalently, selecting a tab will result in a call to
 * {@link OnTriggerListener#onGrabbedStateChange(View, int)} with one of these two states. Releasing
 * the tab will result in whichHandle being {@link OnTriggerListener#NO_HANDLE}.
 *
 */
public class SlidingTab extends ViewGroup {
    private static final String LOG_TAG = "SlidingTab";
    private static final boolean DBG = false;
    private static final int HORIZONTAL = 0; // as defined in attrs.xml
    private static final int VERTICAL = 1;

    // TODO: Make these configurable
    private static final float THRESHOLD = 2.0f / 3.0f;
    private static final long VIBRATE_SHORT = 30;
    private static final long VIBRATE_LONG = 40;
    private static final int TRACKING_MARGIN = 50;
    private static final int ANIM_DURATION = 250; // Time for most animations (in ms)
    private static final int ANIM_TARGET_TIME = 500; // Time to show targets (in ms)
    private boolean mHoldLeftOnTransition = true;
    private boolean mHoldRightOnTransition = true;

    private OnTriggerListener mOnTriggerListener;
    private int mGrabbedState = OnTriggerListener.NO_HANDLE;
    private boolean mTriggered = false;
    private Vibrator mVibrator;
    private final float mDensity; // used to scale dimensions for bitmaps.

    /**
     * Either {@link #HORIZONTAL} or {@link #VERTICAL}.
     */
    private final int mOrientation;

    private final Slider mLeftSlider;
    private final Slider mRightSlider;
    private Slider mCurrentSlider;
    private boolean mTracking;
    private float mThreshold;
    private Slider mOtherSlider;
    private boolean mAnimating;
    private final Rect mTmpRect;

    /**
     * Listener used to reset the view when the current animation completes.
     */
    private final AnimationListener mAnimationDoneListener = new AnimationListener() {
        public void onAnimationStart(Animation animation) {

        }

        public void onAnimationRepeat(Animation animation) {

        }

        public void onAnimationEnd(Animation animation) {
            onAnimationDone();
        }
    };

    /**
     * Interface definition for a callback to be invoked when a tab is triggered
     * by moving it beyond a threshold.
     */
    public interface OnTriggerListener {
        /**
         * The interface was triggered because the user let go of the handle without reaching the
         * threshold.
         */
        public static final int NO_HANDLE = 0;

        /**
         * The interface was triggered because the user grabbed the left handle and moved it past
         * the threshold.
         */
        public static final int LEFT_HANDLE = 1;

        /**
         * The interface was triggered because the user grabbed the right handle and moved it past
         * the threshold.
         */
        public static final int RIGHT_HANDLE = 2;

        /**
         * Called when the user moves a handle beyond the threshold.
         *
         * @param v The view that was triggered.
         * @param whichHandle  Which "dial handle" the user grabbed,
         *        either {@link #LEFT_HANDLE}, {@link #RIGHT_HANDLE}.
         */
        void onTrigger(View v, int whichHandle);

        /**
         * Called when the "grabbed state" changes (i.e. when the user either grabs or releases
         * one of the handles.)
         *
         * @param v the view that was triggered
         * @param grabbedState the new state: {@link #NO_HANDLE}, {@link #LEFT_HANDLE},
         * or {@link #RIGHT_HANDLE}.
         */
        void onGrabbedStateChange(View v, int grabbedState);
    }

    /**
     * Simple container class for all things pertinent to a slider.
     * A slider consists of 3 Views:
     *
     * {@link #tab} is the tab shown on the screen in the default state.
     * {@link #text} is the view revealed as the user slides the tab out.
     * {@link #target} is the target the user must drag the slider past to trigger the slider.
     *
     */
    private static class Slider {
        /**
         * Tab alignment - determines which side the tab should be drawn on
         */
        public static final int ALIGN_LEFT = 0;
        public static final int ALIGN_RIGHT = 1;
        public static final int ALIGN_TOP = 2;
        public static final int ALIGN_BOTTOM = 3;
        public static final int ALIGN_UNKNOWN = 4;

        /**
         * States for the view.
         */
        private static final int STATE_NORMAL = 0;
        private static final int STATE_PRESSED = 1;
        private static final int STATE_ACTIVE = 2;

        private final ImageView tab;
        private final TextView text;
        private final ImageView target;
        private int currentState = STATE_NORMAL;
        private int alignment = ALIGN_UNKNOWN;
        private int alignment_value;

        /**
         * Constructor
         *
         * @param parent the container view of this one
         * @param tabId drawable for the tab
         * @param barId drawable for the bar
         * @param targetId drawable for the target
         */
        Slider(ViewGroup parent, int tabId, int barId, int targetId) {
            // Create tab
            tab = new ImageView(parent.getContext());
            tab.setBackgroundResource(tabId);
            tab.setScaleType(ScaleType.CENTER);
            tab.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT));

            // Create hint TextView
            text = new TextView(parent.getContext());
            text.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT));
            text.setBackgroundResource(barId);
            text.setTextAppearance(parent.getContext(), R.style.TextAppearance_SlidingTabNormal);
            // hint.setSingleLine();  // Hmm.. this causes the text to disappear off-screen

            // Create target
            target = new ImageView(parent.getContext());
            target.setImageResource(targetId);
            target.setScaleType(ScaleType.CENTER);
            target.setLayoutParams(
                    new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            target.setVisibility(View.INVISIBLE);

            parent.addView(target); // this needs to be first - relies on painter's algorithm
            parent.addView(tab);
            parent.addView(text);
        }

        void setIcon(int iconId) {
            tab.setImageResource(iconId);
        }

        void setTabBackgroundResource(int tabId) {
            tab.setBackgroundResource(tabId);
        }

        void setBarBackgroundResource(int barId) {
            text.setBackgroundResource(barId);
        }

        void setHintText(int resId) {
            text.setText(resId);
        }

        void hide() {
            boolean horiz = alignment == ALIGN_LEFT || alignment == ALIGN_RIGHT;
            int dx = horiz ? (alignment == ALIGN_LEFT ? alignment_value - tab.getRight()
                    : alignment_value - tab.getLeft()) : 0;
            int dy = horiz ? 0 : (alignment == ALIGN_TOP ? alignment_value - tab.getBottom()
                    : alignment_value - tab.getTop());

            Animation trans = new TranslateAnimation(0, dx, 0, dy);
            trans.setDuration(ANIM_DURATION);
            trans.setFillAfter(true);
            tab.startAnimation(trans);
            text.startAnimation(trans);
            target.setVisibility(View.INVISIBLE);
        }

        void show(boolean animate) {
            text.setVisibility(View.VISIBLE);
            tab.setVisibility(View.VISIBLE);
            //target.setVisibility(View.INVISIBLE);
            if (animate) {
                boolean horiz = alignment == ALIGN_LEFT || alignment == ALIGN_RIGHT;
                int dx = horiz ? (alignment == ALIGN_LEFT ? tab.getWidth() : -tab.getWidth()) : 0;
                int dy = horiz ? 0: (alignment == ALIGN_TOP ? tab.getHeight() : -tab.getHeight());

                Animation trans = new TranslateAnimation(-dx, 0, -dy, 0);
                trans.setDuration(ANIM_DURATION);
                tab.startAnimation(trans);
                text.startAnimation(trans);
            }
        }

        void setState(int state) {
            text.setPressed(state == STATE_PRESSED);
            tab.setPressed(state == STATE_PRESSED);
            if (state == STATE_ACTIVE) {
                final int[] activeState = new int[] {com.android.internal.R.attr.state_active};
                if (text.getBackground().isStateful()) {
                    text.getBackground().setState(activeState);
                }
                if (tab.getBackground().isStateful()) {
                    tab.getBackground().setState(activeState);
                }
                text.setTextAppearance(text.getContext(), R.style.TextAppearance_SlidingTabActive);
            } else {
                text.setTextAppearance(text.getContext(), R.style.TextAppearance_SlidingTabNormal);
            }
            currentState = state;
        }

        void showTarget() {
            AlphaAnimation alphaAnim = new AlphaAnimation(0.0f, 1.0f);
            alphaAnim.setDuration(ANIM_TARGET_TIME);
            target.startAnimation(alphaAnim);
            target.setVisibility(View.VISIBLE);
        }

        void reset(boolean animate) {
            setState(STATE_NORMAL);
            text.setVisibility(View.VISIBLE);
            text.setTextAppearance(text.getContext(), R.style.TextAppearance_SlidingTabNormal);
            tab.setVisibility(View.VISIBLE);
            target.setVisibility(View.INVISIBLE);
            final boolean horiz = alignment == ALIGN_LEFT || alignment == ALIGN_RIGHT;
            int dx = horiz ? (alignment == ALIGN_LEFT ?  alignment_value - tab.getLeft()
                    : alignment_value - tab.getRight()) : 0;
            int dy = horiz ? 0 : (alignment == ALIGN_TOP ? alignment_value - tab.getTop()
                    : alignment_value - tab.getBottom());
            if (animate) {
                TranslateAnimation trans = new TranslateAnimation(0, dx, 0, dy);
                trans.setDuration(ANIM_DURATION);
                trans.setFillAfter(false);
                text.startAnimation(trans);
                tab.startAnimation(trans);
            } else {
                if (horiz) {
                    text.offsetLeftAndRight(dx);
                    tab.offsetLeftAndRight(dx);
                } else {
                    text.offsetTopAndBottom(dy);
                    tab.offsetTopAndBottom(dy);
                }
                text.clearAnimation();
                tab.clearAnimation();
                target.clearAnimation();
            }
        }

        void setTarget(int targetId) {
            target.setImageResource(targetId);
        }

        /**
         * Layout the given widgets within the parent.
         *
         * @param l the parent's left border
         * @param t the parent's top border
         * @param r the parent's right border
         * @param b the parent's bottom border
         * @param alignment which side to align the widget to
         */
        void layout(int l, int t, int r, int b, int alignment) {
            this.alignment = alignment;
            final Drawable tabBackground = tab.getBackground();
            final int handleWidth = tabBackground.getIntrinsicWidth();
            final int handleHeight = tabBackground.getIntrinsicHeight();
            final Drawable targetDrawable = target.getDrawable();
            final int targetWidth = targetDrawable.getIntrinsicWidth();
            final int targetHeight = targetDrawable.getIntrinsicHeight();
            final int parentWidth = r - l;
            final int parentHeight = b - t;

            final int leftTarget = (int) (THRESHOLD * parentWidth) - targetWidth + handleWidth / 2;
            final int rightTarget = (int) ((1.0f - THRESHOLD) * parentWidth) - handleWidth / 2;
            final int left = (parentWidth - handleWidth) / 2;
            final int right = left + handleWidth;

            if (alignment == ALIGN_LEFT || alignment == ALIGN_RIGHT) {
                // horizontal
                final int targetTop = (parentHeight - targetHeight) / 2;
                final int targetBottom = targetTop + targetHeight;
                final int top = (parentHeight - handleHeight) / 2;
                final int bottom = (parentHeight + handleHeight) / 2;
                if (alignment == ALIGN_LEFT) {
                    tab.layout(0, top, handleWidth, bottom);
                    text.layout(0 - parentWidth, top, 0, bottom);
                    text.setGravity(Gravity.RIGHT);
                    target.layout(leftTarget, targetTop, leftTarget + targetWidth, targetBottom);
                    alignment_value = l;
                } else {
                    tab.layout(parentWidth - handleWidth, top, parentWidth, bottom);
                    text.layout(parentWidth, top, parentWidth + parentWidth, bottom);
                    target.layout(rightTarget, targetTop, rightTarget + targetWidth, targetBottom);
                    text.setGravity(Gravity.TOP);
                    alignment_value = r;
                }
            } else {
                // vertical
                final int targetLeft = (parentWidth - targetWidth) / 2;
                final int targetRight = (parentWidth + targetWidth) / 2;
                final int top = (int) (THRESHOLD * parentHeight) + handleHeight / 2 - targetHeight;
                final int bottom = (int) ((1.0f - THRESHOLD) * parentHeight) - handleHeight / 2;
                if (alignment == ALIGN_TOP) {
                    tab.layout(left, 0, right, handleHeight);
                    text.layout(left, 0 - parentHeight, right, 0);
                    target.layout(targetLeft, top, targetRight, top + targetHeight);
                    alignment_value = t;
                } else {
                    tab.layout(left, parentHeight - handleHeight, right, parentHeight);
                    text.layout(left, parentHeight, right, parentHeight + parentHeight);
                    target.layout(targetLeft, bottom, targetRight, bottom + targetHeight);
                    alignment_value = b;
                }
            }
        }

        public void updateDrawableStates() {
            setState(currentState);
        }

        /**
         * Ensure all the dependent widgets are measured.
         */
        public void measure() {
            tab.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            text.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        }

        /**
         * Get the measured tab width. Must be called after {@link Slider#measure()}.
         * @return
         */
        public int getTabWidth() {
            return tab.getMeasuredWidth();
        }

        /**
         * Get the measured tab width. Must be called after {@link Slider#measure()}.
         * @return
         */
        public int getTabHeight() {
            return tab.getMeasuredHeight();
        }

        /**
         * Start animating the slider. Note we need two animations since a ValueAnimator
         * keeps internal state of the invalidation region which is just the view being animated.
         *
         * @param anim1
         * @param anim2
         */
        public void startAnimation(Animation anim1, Animation anim2) {
            tab.startAnimation(anim1);
            text.startAnimation(anim2);
        }

        public void hideTarget() {
            target.clearAnimation();
            target.setVisibility(View.INVISIBLE);
        }
    }

    public SlidingTab(Context context) {
        this(context, null);
    }

    /**
     * Constructor used when this widget is created from a layout file.
     */
    public SlidingTab(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Allocate a temporary once that can be used everywhere.
        mTmpRect = new Rect();

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SlidingTab);
        mOrientation = a.getInt(R.styleable.SlidingTab_orientation, HORIZONTAL);
        a.recycle();

        Resources r = getResources();
        mDensity = r.getDisplayMetrics().density;
        if (DBG) log("- Density: " + mDensity);

        mLeftSlider = new Slider(this,
                R.drawable.jog_tab_left_generic,
                R.drawable.jog_tab_bar_left_generic,
                R.drawable.jog_tab_target_gray);
        mRightSlider = new Slider(this,
                R.drawable.jog_tab_right_generic,
                R.drawable.jog_tab_bar_right_generic,
                R.drawable.jog_tab_target_gray);

        // setBackgroundColor(0x80808080);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSpecSize =  MeasureSpec.getSize(widthMeasureSpec);

        int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSpecSize =  MeasureSpec.getSize(heightMeasureSpec);

        if (DBG) {
            if (widthSpecMode == MeasureSpec.UNSPECIFIED 
                    || heightSpecMode == MeasureSpec.UNSPECIFIED) {
                Log.e("SlidingTab", "SlidingTab cannot have UNSPECIFIED MeasureSpec"
                        +"(wspec=" + widthSpecMode + ", hspec=" + heightSpecMode + ")",
                        new RuntimeException(LOG_TAG + "stack:"));
            }
        }

        mLeftSlider.measure();
        mRightSlider.measure();
        final int leftTabWidth = mLeftSlider.getTabWidth();
        final int rightTabWidth = mRightSlider.getTabWidth();
        final int leftTabHeight = mLeftSlider.getTabHeight();
        final int rightTabHeight = mRightSlider.getTabHeight();
        final int width;
        final int height;
        if (isHorizontal()) {
            width = Math.max(widthSpecSize, leftTabWidth + rightTabWidth);
            height = Math.max(leftTabHeight, rightTabHeight);
        } else {
            width = Math.max(leftTabWidth, rightTabHeight);
            height = Math.max(heightSpecSize, leftTabHeight + rightTabHeight);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        final int action = event.getAction();
        final float x = event.getX();
        final float y = event.getY();

        if (mAnimating) {
            return false;
        }

        View leftHandle = mLeftSlider.tab;
        leftHandle.getHitRect(mTmpRect);
        boolean leftHit = mTmpRect.contains((int) x, (int) y);

        View rightHandle = mRightSlider.tab;
        rightHandle.getHitRect(mTmpRect);
        boolean rightHit = mTmpRect.contains((int)x, (int) y);

        if (!mTracking && !(leftHit || rightHit)) {
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mTracking = true;
                mTriggered = false;
                vibrate(VIBRATE_SHORT);
                if (leftHit) {
                    mCurrentSlider = mLeftSlider;
                    mOtherSlider = mRightSlider;
                    mThreshold = isHorizontal() ? THRESHOLD : 1.0f - THRESHOLD;
                    setGrabbedState(OnTriggerListener.LEFT_HANDLE);
                } else {
                    mCurrentSlider = mRightSlider;
                    mOtherSlider = mLeftSlider;
                    mThreshold = isHorizontal() ? 1.0f - THRESHOLD : THRESHOLD;
                    setGrabbedState(OnTriggerListener.RIGHT_HANDLE);
                }
                mCurrentSlider.setState(Slider.STATE_PRESSED);
                mCurrentSlider.showTarget();
                mOtherSlider.hide();
                break;
            }
        }

        return true;
    }

    /**
     * Reset the tabs to their original state and stop any existing animation.
     * Animate them back into place if animate is true.
     *
     * @param animate
     */
    public void reset(boolean animate) {
        mLeftSlider.reset(animate);
        mRightSlider.reset(animate);
        if (!animate) {
            mAnimating = false;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        // Clear animations so sliders don't continue to animate when we show the widget again.
        if (visibility != getVisibility() && visibility == View.INVISIBLE) {
           reset(false);
        }
        super.setVisibility(visibility);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTracking) {
            final int action = event.getAction();
            final float x = event.getX();
            final float y = event.getY();

            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    if (withinView(x, y, this) ) {
                        moveHandle(x, y);
                        float position = isHorizontal() ? x : y;
                        float target = mThreshold * (isHorizontal() ? getWidth() : getHeight());
                        boolean thresholdReached;
                        if (isHorizontal()) {
                            thresholdReached = mCurrentSlider == mLeftSlider ?
                                    position > target : position < target;
                        } else {
                            thresholdReached = mCurrentSlider == mLeftSlider ?
                                    position < target : position > target;
                        }
                        if (!mTriggered && thresholdReached) {
                            mTriggered = true;
                            mTracking = false;
                            mCurrentSlider.setState(Slider.STATE_ACTIVE);
                            boolean isLeft = mCurrentSlider == mLeftSlider;
                            dispatchTriggerEvent(isLeft ?
                                OnTriggerListener.LEFT_HANDLE : OnTriggerListener.RIGHT_HANDLE);

                            startAnimating(isLeft ? mHoldLeftOnTransition : mHoldRightOnTransition);
                            setGrabbedState(OnTriggerListener.NO_HANDLE);
                        }
                        break;
                    }
                    // Intentionally fall through - we're outside tracking rectangle

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelGrab();
                    break;
            }
        }

        return mTracking || super.onTouchEvent(event);
    }

    private void cancelGrab() {
        mTracking = false;
        mTriggered = false;
        mOtherSlider.show(true);
        mCurrentSlider.reset(false);
        mCurrentSlider.hideTarget();
        mCurrentSlider = null;
        mOtherSlider = null;
        setGrabbedState(OnTriggerListener.NO_HANDLE);
    }

    void startAnimating(final boolean holdAfter) {
        mAnimating = true;
        final Animation trans1;
        final Animation trans2;
        final Slider slider = mCurrentSlider;
        final Slider other = mOtherSlider;
        final int dx;
        final int dy;
        if (isHorizontal()) {
            int right = slider.tab.getRight();
            int width = slider.tab.getWidth();
            int left = slider.tab.getLeft();
            int viewWidth = getWidth();
            int holdOffset = holdAfter ? 0 : width; // how much of tab to show at the end of anim
            dx =  slider == mRightSlider ? - (right + viewWidth - holdOffset)
                    : (viewWidth - left) + viewWidth - holdOffset;
            dy = 0;
        } else {
            int top = slider.tab.getTop();
            int bottom = slider.tab.getBottom();
            int height = slider.tab.getHeight();
            int viewHeight = getHeight();
            int holdOffset = holdAfter ? 0 : height; // how much of tab to show at end of anim
            dx = 0;
            dy =  slider == mRightSlider ? (top + viewHeight - holdOffset)
                    : - ((viewHeight - bottom) + viewHeight - holdOffset);
        }
        trans1 = new TranslateAnimation(0, dx, 0, dy);
        trans1.setDuration(ANIM_DURATION);
        trans1.setInterpolator(new LinearInterpolator());
        trans1.setFillAfter(true);
        trans2 = new TranslateAnimation(0, dx, 0, dy);
        trans2.setDuration(ANIM_DURATION);
        trans2.setInterpolator(new LinearInterpolator());
        trans2.setFillAfter(true);

        trans1.setAnimationListener(new AnimationListener() {
            public void onAnimationEnd(Animation animation) {
                Animation anim;
                if (holdAfter) {
                    anim = new TranslateAnimation(dx, dx, dy, dy);
                    anim.setDuration(1000); // plenty of time for transitions
                    mAnimating = false;
                } else {
                    anim = new AlphaAnimation(0.5f, 1.0f);
                    anim.setDuration(ANIM_DURATION);
                    resetView();
                }
                anim.setAnimationListener(mAnimationDoneListener);

                /* Animation can be the same for these since the animation just holds */
                mLeftSlider.startAnimation(anim, anim);
                mRightSlider.startAnimation(anim, anim);
            }

            public void onAnimationRepeat(Animation animation) {

            }

            public void onAnimationStart(Animation animation) {

            }

        });

        slider.hideTarget();
        slider.startAnimation(trans1, trans2);
    }

    private void onAnimationDone() {
        resetView();
        mAnimating = false;
    }

    private boolean withinView(final float x, final float y, final View view) {
        return isHorizontal() && y > - TRACKING_MARGIN && y < TRACKING_MARGIN + view.getHeight()
            || !isHorizontal() && x > -TRACKING_MARGIN && x < TRACKING_MARGIN + view.getWidth();
    }

    private boolean isHorizontal() {
        return mOrientation == HORIZONTAL;
    }

    private void resetView() {
        mLeftSlider.reset(false);
        mRightSlider.reset(false);
        // onLayout(true, getLeft(), getTop(), getLeft() + getWidth(), getTop() + getHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (!changed) return;

        // Center the widgets in the view
        mLeftSlider.layout(l, t, r, b, isHorizontal() ? Slider.ALIGN_LEFT : Slider.ALIGN_BOTTOM);
        mRightSlider.layout(l, t, r, b, isHorizontal() ? Slider.ALIGN_RIGHT : Slider.ALIGN_TOP);
    }

    private void moveHandle(float x, float y) {
        final View handle = mCurrentSlider.tab;
        final View content = mCurrentSlider.text;
        if (isHorizontal()) {
            int deltaX = (int) x - handle.getLeft() - (handle.getWidth() / 2);
            handle.offsetLeftAndRight(deltaX);
            content.offsetLeftAndRight(deltaX);
        } else {
            int deltaY = (int) y - handle.getTop() - (handle.getHeight() / 2);
            handle.offsetTopAndBottom(deltaY);
            content.offsetTopAndBottom(deltaY);
        }
        invalidate(); // TODO: be more conservative about what we're invalidating
    }

    /**
     * Sets the left handle icon to a given resource.
     *
     * The resource should refer to a Drawable object, or use 0 to remove
     * the icon.
     *
     * @param iconId the resource ID of the icon drawable
     * @param targetId the resource of the target drawable
     * @param barId the resource of the bar drawable (stateful)
     * @param tabId the resource of the
     */
    public void setLeftTabResources(int iconId, int targetId, int barId, int tabId) {
        mLeftSlider.setIcon(iconId);
        mLeftSlider.setTarget(targetId);
        mLeftSlider.setBarBackgroundResource(barId);
        mLeftSlider.setTabBackgroundResource(tabId);
        mLeftSlider.updateDrawableStates();
    }

    /**
     * Sets the left handle hint text to a given resource string.
     *
     * @param resId
     */
    public void setLeftHintText(int resId) {
        if (isHorizontal()) {
            mLeftSlider.setHintText(resId);
        }
    }

    /**
     * Sets the right handle icon to a given resource.
     *
     * The resource should refer to a Drawable object, or use 0 to remove
     * the icon.
     *
     * @param iconId the resource ID of the icon drawable
     * @param targetId the resource of the target drawable
     * @param barId the resource of the bar drawable (stateful)
     * @param tabId the resource of the
     */
    public void setRightTabResources(int iconId, int targetId, int barId, int tabId) {
        mRightSlider.setIcon(iconId);
        mRightSlider.setTarget(targetId);
        mRightSlider.setBarBackgroundResource(barId);
        mRightSlider.setTabBackgroundResource(tabId);
        mRightSlider.updateDrawableStates();
    }

    /**
     * Sets the left handle hint text to a given resource string.
     *
     * @param resId
     */
    public void setRightHintText(int resId) {
        if (isHorizontal()) {
            mRightSlider.setHintText(resId);
        }
    }

    public void setHoldAfterTrigger(boolean holdLeft, boolean holdRight) {
        mHoldLeftOnTransition = holdLeft;
        mHoldRightOnTransition = holdRight;
    }

    /**
     * Triggers haptic feedback.
     */
    private synchronized void vibrate(long duration) {
        final boolean hapticEnabled = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
                UserHandle.USER_CURRENT) != 0;
        if (hapticEnabled) {
            if (mVibrator == null) {
                mVibrator = (android.os.Vibrator) getContext()
                        .getSystemService(Context.VIBRATOR_SERVICE);
            }
            mVibrator.vibrate(duration);
        }
    }

    /**
     * Registers a callback to be invoked when the user triggers an event.
     *
     * @param listener the OnDialTriggerListener to attach to this view
     */
    public void setOnTriggerListener(OnTriggerListener listener) {
        mOnTriggerListener = listener;
    }

    /**
     * Dispatches a trigger event to listener. Ignored if a listener is not set.
     * @param whichHandle the handle that triggered the event.
     */
    private void dispatchTriggerEvent(int whichHandle) {
        vibrate(VIBRATE_LONG);
        if (mOnTriggerListener != null) {
            mOnTriggerListener.onTrigger(this, whichHandle);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // When visibility changes and the user has a tab selected, unselect it and
        // make sure their callback gets called.
        if (changedView == this && visibility != VISIBLE
                && mGrabbedState != OnTriggerListener.NO_HANDLE) {
            cancelGrab();
        }
    }

    /**
     * Sets the current grabbed state, and dispatches a grabbed state change
     * event to our listener.
     */
    private void setGrabbedState(int newState) {
        if (newState != mGrabbedState) {
            mGrabbedState = newState;
            if (mOnTriggerListener != null) {
                mOnTriggerListener.onGrabbedStateChange(this, mGrabbedState);
            }
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
