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
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ImageView.ScaleType;
import com.android.internal.R;

/**
 * A special widget containing two Sliders and a threshold for each.  Moving either slider beyond
 * the threshold will cause the registered OnTriggerListener.onTrigger() to be called with
 * {@link OnTriggerListener#LEFT_HANDLE} or {@link OnTriggerListener#RIGHT_HANDLE} to be called.
 *
 */
public class SlidingTab extends ViewGroup {
    private static final int ANIMATION_DURATION = 250; // animation transition duration (in ms)
    private static final String LOG_TAG = "SlidingTab";
    private static final boolean DBG = false;
    private static final int HORIZONTAL = 0; // as defined in attrs.xml
    private static final int VERTICAL = 1;
    private static final int MSG_ANIMATE = 100;

    // TODO: Make these configurable
    private static final float TARGET_ZONE = 2.0f / 3.0f;
    private static final long VIBRATE_SHORT = 30;
    private static final long VIBRATE_LONG = 40;

    private OnTriggerListener mOnTriggerListener;
    private int mGrabbedState = OnTriggerListener.NO_HANDLE;
    private boolean mTriggered = false;
    private Vibrator mVibrator;
    private float mDensity; // used to scale dimensions for bitmaps.

    private final SlidingTabHandler mHandler = new SlidingTabHandler();

    /**
     * Either {@link #HORIZONTAL} or {@link #VERTICAL}.
     */
    private int mOrientation;

    private Slider mLeftSlider;
    private Slider mRightSlider;
    private Slider mCurrentSlider;
    private boolean mTracking;
    private float mTargetZone;
    private Slider mOtherSlider;
    private boolean mAnimating;

    /**
     * Interface definition for a callback to be invoked when a tab is triggered
     * by moving it beyond a target zone.
     */
    public interface OnTriggerListener {
        /**
         * The interface was triggered because the user let go of the handle without reaching the
         * target zone.
         */
        public static final int NO_HANDLE = 0;

        /**
         * The interface was triggered because the user grabbed the left handle and moved it past
         * the target zone.
         */
        public static final int LEFT_HANDLE = 1;

        /**
         * The interface was triggered because the user grabbed the right handle and moved it past
         * the target zone.
         */
        public static final int RIGHT_HANDLE = 2;

        /**
         * Called when the user moves a handle beyond the target zone.
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

        /**
         * States for the view.
         */
        private static final int STATE_NORMAL = 0;
        private static final int STATE_PRESSED = 1;
        private static final int STATE_ACTIVE = 2;

        private final ImageView tab;
        private final TextView text;
        private final ImageView target;

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
                    LayoutParams.FILL_PARENT));
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
            // TODO: Text should be blank if widget is vertical
            text.setText(resId); 
        }

        void hide() {
            // TODO: Animate off the screen
            text.setVisibility(View.INVISIBLE);
            tab.setVisibility(View.INVISIBLE);
            target.setVisibility(View.INVISIBLE);
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
        }

        void showTarget() {
            target.setVisibility(View.VISIBLE);
        }

        void reset() {
            setState(STATE_NORMAL);
            text.setVisibility(View.VISIBLE);
            text.setTextAppearance(text.getContext(), R.style.TextAppearance_SlidingTabNormal);
            tab.setVisibility(View.VISIBLE);
            target.setVisibility(View.INVISIBLE);
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
            final Drawable tabBackground = tab.getBackground();
            final int handleWidth = tabBackground.getIntrinsicWidth();
            final int handleHeight = tabBackground.getIntrinsicHeight();
            final Drawable targetDrawable = target.getDrawable();
            final int targetWidth = targetDrawable.getIntrinsicWidth();
            final int targetHeight = targetDrawable.getIntrinsicHeight();
            final int parentWidth = r - l;
            final int parentHeight = b - t;

            final int leftTarget = (int) (TARGET_ZONE * parentWidth) - targetWidth + handleWidth / 2;
            final int rightTarget = (int) ((1.0f - TARGET_ZONE) * parentWidth) - handleWidth / 2;
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
                } else {
                    tab.layout(parentWidth - handleWidth, top, parentWidth, bottom);
                    text.layout(parentWidth, top, parentWidth + parentWidth, bottom);
                    target.layout(rightTarget, targetTop, rightTarget + targetWidth, targetBottom);
                    text.setGravity(Gravity.TOP);
                }
            } else {
                // vertical
                final int targetLeft = (parentWidth - targetWidth) / 2;
                final int targetRight = (parentWidth + targetWidth) / 2;
                final int top = (int) (TARGET_ZONE * parentHeight) + handleHeight / 2 - targetHeight;
                final int bottom = (int) ((1.0f - TARGET_ZONE) * parentHeight) - handleHeight / 2;
                if (alignment == ALIGN_TOP) {
                    tab.layout(left, 0, right, handleHeight);
                    text.layout(left, 0 - parentHeight, right, 0);
                    target.layout(targetLeft, top, targetRight, top + targetHeight);
                } else {
                    tab.layout(left, parentHeight - handleHeight, right, parentHeight);
                    text.layout(left, parentHeight, right, parentHeight + parentHeight);
                    target.layout(targetLeft, bottom, targetRight, bottom + targetHeight);
                }
            }
        }

        public int getTabWidth() {
            return tab.getDrawable().getIntrinsicWidth();
        }

        public int getTabHeight() {
            return tab.getDrawable().getIntrinsicHeight();
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

        if (widthSpecMode == MeasureSpec.UNSPECIFIED || heightSpecMode == MeasureSpec.UNSPECIFIED) {
            throw new RuntimeException(LOG_TAG + " cannot have UNSPECIFIED dimensions");
        }

        final float density = mDensity;
        final int leftTabWidth = (int) (density * mLeftSlider.getTabWidth() + 0.5f);
        final int rightTabWidth = (int) (density * mRightSlider.getTabWidth() + 0.5f);
        final int leftTabHeight = (int) (density * mLeftSlider.getTabHeight() + 0.5f);
        final int rightTabHeight = (int) (density * mRightSlider.getTabHeight() + 0.5f);
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

        final Rect frame = new Rect();

        if (mAnimating) {
            return false;
        }

        View leftHandle = mLeftSlider.tab;
        leftHandle.getHitRect(frame);
        boolean leftHit = frame.contains((int) x, (int) y);

        View rightHandle = mRightSlider.tab;
        rightHandle.getHitRect(frame);
        boolean rightHit = frame.contains((int)x, (int) y);

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
                    mTargetZone = isHorizontal() ? TARGET_ZONE : 1.0f - TARGET_ZONE;
                    setGrabbedState(OnTriggerListener.LEFT_HANDLE);
                } else {
                    mCurrentSlider = mRightSlider;
                    mOtherSlider = mLeftSlider;
                    mTargetZone = isHorizontal() ? 1.0f - TARGET_ZONE : TARGET_ZONE;
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTracking) {
            final int action = event.getAction();
            final float x = event.getX();
            final float y = event.getY();
            final View handle = mCurrentSlider.tab;
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    moveHandle(x, y);
                    float position = isHorizontal() ? x : y;
                    float target = mTargetZone * (isHorizontal() ? getWidth() : getHeight());
                    boolean targetZoneReached;
                    if (isHorizontal()) {
                        targetZoneReached = mCurrentSlider == mLeftSlider ?
                                position > target : position < target;
                    } else {
                        targetZoneReached = mCurrentSlider == mLeftSlider ?
                                position < target : position > target;
                    }
                    if (!mTriggered && targetZoneReached) {
                        mTriggered = true;
                        mTracking = false;
                        mCurrentSlider.setState(Slider.STATE_ACTIVE);
                        dispatchTriggerEvent(mCurrentSlider == mLeftSlider ?
                            OnTriggerListener.LEFT_HANDLE : OnTriggerListener.RIGHT_HANDLE);

                        // TODO: This is a place holder for the real animation. It just holds
                        // the screen for the duration of the animation for now.
                        mAnimating = true;
                        mHandler.postDelayed(new Runnable() {
                            public void run() {
                                resetView();
                                mAnimating = false;
                            }
                        }, ANIMATION_DURATION);
                    }

                    if (isHorizontal() && (y <= handle.getBottom() && y >= handle.getTop()) ||
                            !isHorizontal() && (x >= handle.getLeft() && x <= handle.getRight()) ) {
                        break;
                    }
                    // Intentionally fall through - we're outside tracking rectangle

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mTracking = false;
                    mTriggered = false;
                    resetView();
                    setGrabbedState(OnTriggerListener.NO_HANDLE);
                    break;
            }
        }

        return mTracking || super.onTouchEvent(event);
    }

    private boolean isHorizontal() {
        return mOrientation == HORIZONTAL;
    }

    private void resetView() {
        mLeftSlider.reset();
        mRightSlider.reset();
        onLayout(true, getLeft(), getTop(), getLeft() + getWidth(), getTop() + getHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (!changed) return;

        // Center the widgets in the view
        mLeftSlider.layout(l, t, r, b, isHorizontal() ? Slider.ALIGN_LEFT : Slider.ALIGN_BOTTOM);
        mRightSlider.layout(l, t, r, b, isHorizontal() ? Slider.ALIGN_RIGHT : Slider.ALIGN_TOP);

        invalidate(); // TODO: be more conservative about what we're invalidating
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
    }

    /**
     * Sets the left handle hint text to a given resource string.
     *
     * @param resId
     */
    public void setLeftHintText(int resId) {
        mLeftSlider.setHintText(resId);
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
    }

    /**
     * Sets the left handle hint text to a given resource string.
     *
     * @param resId
     */
    public void setRightHintText(int resId) {
        mRightSlider.setHintText(resId);
    }

    /**
     * Triggers haptic feedback.
     */
    private synchronized void vibrate(long duration) {
        if (mVibrator == null) {
            mVibrator = (android.os.Vibrator)
                    getContext().getSystemService(Context.VIBRATOR_SERVICE);
        }
        mVibrator.vibrate(duration);
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

    private class SlidingTabHandler extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_ANIMATE:
                    doAnimation();
                    break;
            }
        }
    }

    private void doAnimation() {
        if (mAnimating) {

        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
