/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.graphics.Color;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;

import com.android.internal.R;
import com.android.internal.policy.PhoneWindow;

import java.util.ArrayList;

/**
 * This class represents the special screen elements to control a window on freeform
 * environment.
 * As such this class handles the following things:
 * <ul>
 * <li>The caption, containing the system buttons like maximize, close and such as well as
 * allowing the user to drag the window around.</li>
 * </ul>
 * After creating the view, the function {@link #setPhoneWindow} needs to be called to make
 * the connection to it's owning PhoneWindow.
 * Note: At this time the application can change various attributes of the DecorView which
 * will break things (in settle/unexpected ways):
 * <ul>
 * <li>setOutlineProvider</li>
 * <li>setSurfaceFormat</li>
 * <li>..</li>
 * </ul>
 *
 * Although this ViewGroup has only two direct sub-Views, its behavior is more complex due to
 * overlaying caption on the content and drawing.
 *
 * First, no matter where the content View gets added, it will always be the first child and the
 * caption will be the second. This way the caption will always be drawn on top of the content when
 * overlaying is enabled.
 *
 * Second, the touch dispatch is customized to handle overlaying. This is what happens when touch
 * is dispatched on the caption area while overlaying it on content:
 * <ul>
 * <li>DecorCaptionView.onInterceptTouchEvent() will try intercepting the touch events if the
 * down action is performed on top close or maximize buttons; the reason for that is we want these
 * buttons to always work.</li>
 * <li>The content View will receive the touch event. Mind that content is actually underneath the
 * caption, so we need to introduce our own dispatch ordering. We achieve this by overriding
 * {@link #buildTouchDispatchChildList()}.</li>
 * <li>If the touch event is not consumed by the content View, it will go to the caption View
 * and the dragging logic will be executed.</li>
 * </ul>
 */
public class DecorCaptionView extends ViewGroup implements View.OnTouchListener,
        GestureDetector.OnGestureListener {
    private final static String TAG = "DecorCaptionView";
    private PhoneWindow mOwner = null;
    private boolean mShow = false;

    // True if the window is being dragged.
    private boolean mDragging = false;

    // True when the left mouse button got released while dragging.
    private boolean mLeftMouseButtonReleased;

    private boolean mOverlayWithAppContent = false;

    private View mCaption;
    private View mContent;
    private View mMaximize;
    private View mClose;

    // Fields for detecting drag events.
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mCheckForDragging;
    private int mDragSlop;

    // Fields for detecting and intercepting click events on close/maximize.
    private ArrayList<View> mTouchDispatchList = new ArrayList<>(2);
    // We use the gesture detector to detect clicks on close/maximize buttons and to be consistent
    // with existing click detection.
    private GestureDetector mGestureDetector;
    private final Rect mCloseRect = new Rect();
    private final Rect mMaximizeRect = new Rect();
    private View mClickTarget;

    public DecorCaptionView(Context context) {
        super(context);
        init(context);
    }

    public DecorCaptionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DecorCaptionView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        mDragSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mGestureDetector = new GestureDetector(context, this);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCaption = getChildAt(0);
    }

    public void setPhoneWindow(PhoneWindow owner, boolean show) {
        mOwner = owner;
        mShow = show;
        mOverlayWithAppContent = owner.isOverlayWithDecorCaptionEnabled();
        if (mOverlayWithAppContent) {
            // The caption is covering the content, so we make its background transparent to make
            // the content visible.
            mCaption.setBackgroundColor(Color.TRANSPARENT);
        }
        updateCaptionVisibility();
        // By changing the outline provider to BOUNDS, the window can remove its
        // background without removing the shadow.
        mOwner.getDecorView().setOutlineProvider(ViewOutlineProvider.BOUNDS);
        mMaximize = findViewById(R.id.maximize_window);
        mClose = findViewById(R.id.close_window);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // If the user starts touch on the maximize/close buttons, we immediately intercept, so
        // that these buttons are always clickable.
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            final int x = (int) ev.getX();
            final int y = (int) ev.getY();
            if (mMaximizeRect.contains(x, y)) {
                mClickTarget = mMaximize;
            }
            if (mCloseRect.contains(x, y)) {
                mClickTarget = mClose;
            }
        }
        return mClickTarget != null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mClickTarget != null) {
            mGestureDetector.onTouchEvent(event);
            final int action = event.getAction();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mClickTarget = null;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        // Note: There are no mixed events. When a new device gets used (e.g. 1. Mouse, 2. touch)
        // the old input device events get cancelled first. So no need to remember the kind of
        // input device we are listening to.
        final int x = (int) e.getX();
        final int y = (int) e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!mShow) {
                    // When there is no caption we should not react to anything.
                    return false;
                }
                // Checking for a drag action is started if we aren't dragging already and the
                // starting event is either a left mouse button or any other input device.
                if (((e.getToolType(e.getActionIndex()) != MotionEvent.TOOL_TYPE_MOUSE ||
                        (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0))) {
                    mCheckForDragging = true;
                    mTouchDownX = x;
                    mTouchDownY = y;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!mDragging && mCheckForDragging && passedSlop(x, y)) {
                    mCheckForDragging = false;
                    mDragging = true;
                    mLeftMouseButtonReleased = false;
                    startMovingTask(e.getRawX(), e.getRawY());
                } else if (mDragging && !mLeftMouseButtonReleased) {
                    if (e.getToolType(e.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE &&
                            (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) == 0) {
                        // There is no separate mouse button up call and if the user mixes mouse
                        // button drag actions, we stop dragging once he releases the button.
                        mLeftMouseButtonReleased = true;
                        break;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!mDragging) {
                    break;
                }
                // Abort the ongoing dragging.
                mDragging = false;
                return !mCheckForDragging;
        }
        return mDragging || mCheckForDragging;
    }

    @Override
    public ArrayList<View> buildTouchDispatchChildList() {
        mTouchDispatchList.ensureCapacity(3);
        if (mCaption != null) {
            mTouchDispatchList.add(mCaption);
        }
        if (mContent != null) {
            mTouchDispatchList.add(mContent);
        }
        return mTouchDispatchList;
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    private boolean passedSlop(int x, int y) {
        return Math.abs(x - mTouchDownX) > mDragSlop || Math.abs(y - mTouchDownY) > mDragSlop;
    }

    /**
     * The phone window configuration has changed and the caption needs to be updated.
     * @param show True if the caption should be shown.
     */
    public void onConfigurationChanged(boolean show) {
        mShow = show;
        updateCaptionVisibility();
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (!(params instanceof MarginLayoutParams)) {
            throw new IllegalArgumentException(
                    "params " + params + " must subclass MarginLayoutParams");
        }
        // Make sure that we never get more then one client area in our view.
        if (index >= 2 || getChildCount() >= 2) {
            throw new IllegalStateException("DecorCaptionView can only handle 1 client view");
        }
        // To support the overlaying content in the caption, we need to put the content view as the
        // first child to get the right Z-Ordering.
        super.addView(child, 0, params);
        mContent = child;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int captionHeight;
        if (mCaption.getVisibility() != View.GONE) {
            measureChildWithMargins(mCaption, widthMeasureSpec, 0, heightMeasureSpec, 0);
            captionHeight = mCaption.getMeasuredHeight();
        } else {
            captionHeight = 0;
        }
        if (mContent != null) {
            if (mOverlayWithAppContent) {
                measureChildWithMargins(mContent, widthMeasureSpec, 0, heightMeasureSpec, 0);
            } else {
                measureChildWithMargins(mContent, widthMeasureSpec, 0, heightMeasureSpec,
                        captionHeight);
            }
        }

        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int captionHeight;
        if (mCaption.getVisibility() != View.GONE) {
            mCaption.layout(0, 0, mCaption.getMeasuredWidth(), mCaption.getMeasuredHeight());
            captionHeight = mCaption.getBottom() - mCaption.getTop();
            mMaximize.getHitRect(mMaximizeRect);
            mClose.getHitRect(mCloseRect);
        } else {
            captionHeight = 0;
            mMaximizeRect.setEmpty();
            mCloseRect.setEmpty();
        }

        if (mContent != null) {
            if (mOverlayWithAppContent) {
                mContent.layout(0, 0, mContent.getMeasuredWidth(), mContent.getMeasuredHeight());
            } else {
                mContent.layout(0, captionHeight, mContent.getMeasuredWidth(),
                        captionHeight + mContent.getMeasuredHeight());
            }
        }

        // This assumes that the caption bar is at the top.
        mOwner.notifyRestrictedCaptionAreaCallback(mMaximize.getLeft(), mMaximize.getTop(),
                mClose.getRight(), mClose.getBottom());
    }
    /**
     * Determine if the workspace is entirely covered by the window.
     * @return Returns true when the window is filling the entire screen/workspace.
     **/
    private boolean isFillingScreen() {
        return (0 != ((getWindowSystemUiVisibility() | getSystemUiVisibility()) &
                (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_LOW_PROFILE)));
    }

    /**
     * Updates the visibility of the caption.
     **/
    private void updateCaptionVisibility() {
        // Don't show the caption if the window has e.g. entered full screen.
        boolean invisible = isFillingScreen() || !mShow;
        mCaption.setVisibility(invisible ? GONE : VISIBLE);
        mCaption.setOnTouchListener(this);
    }

    /**
     * Maximize the window by moving it to the maximized workspace stack.
     **/
    private void maximizeWindow() {
        Window.WindowControllerCallback callback = mOwner.getWindowControllerCallback();
        if (callback != null) {
            try {
                callback.exitFreeformMode();
            } catch (RemoteException ex) {
                Log.e(TAG, "Cannot change task workspace.");
            }
        }
    }

    public boolean isCaptionShowing() {
        return mShow;
    }

    public int getCaptionHeight() {
        return (mCaption != null) ? mCaption.getHeight() : 0;
    }

    public void removeContentView() {
        if (mContent != null) {
            removeView(mContent);
            mContent = null;
        }
    }

    public View getCaption() {
        return mCaption;
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(MarginLayoutParams.MATCH_PARENT,
                MarginLayoutParams.MATCH_PARENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        if (mClickTarget == mMaximize) {
            maximizeWindow();
        } else if (mClickTarget == mClose) {
            mOwner.dispatchOnWindowDismissed(
                    true /*finishTask*/, false /*suppressWindowTransition*/);
        }
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }
}
