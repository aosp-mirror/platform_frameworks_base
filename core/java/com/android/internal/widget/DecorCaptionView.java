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

import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;

import android.content.Context;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.policy.PhoneWindow;

/**
 * This class represents the special screen elements to control a window on freeform
 * environment.
 * As such this class handles the following things:
 * <ul>
 * <li>The caption, containing the system buttons like maximize, close and such as well as
 * allowing the user to drag the window around.</li>
 * After creating the view, the function
 * {@link #setPhoneWindow} needs to be called to make
 * the connection to it's owning PhoneWindow.
 * Note: At this time the application can change various attributes of the DecorView which
 * will break things (in settle/unexpected ways):
 * <ul>
 * <li>setOutlineProvider</li>
 * <li>setSurfaceFormat</li>
 * <li>..</li>
 * </ul>
 */
public class DecorCaptionView extends ViewGroup
        implements View.OnClickListener, View.OnTouchListener {
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

    public DecorCaptionView(Context context) {
        super(context);
    }

    public DecorCaptionView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DecorCaptionView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCaption = getChildAt(0);
    }

    public void setPhoneWindow(PhoneWindow owner, boolean show) {
        mOwner = owner;
        mShow = show;
        mOverlayWithAppContent = owner.getOverlayDecorCaption();
        updateCaptionVisibility();
        // By changing the outline provider to BOUNDS, the window can remove its
        // background without removing the shadow.
        mOwner.getDecorView().setOutlineProvider(ViewOutlineProvider.BOUNDS);

        findViewById(R.id.maximize_window).setOnClickListener(this);
        findViewById(R.id.close_window).setOnClickListener(this);
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        // Note: There are no mixed events. When a new device gets used (e.g. 1. Mouse, 2. touch)
        // the old input device events get cancelled first. So no need to remember the kind of
        // input device we are listening to.
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!mShow) {
                    // When there is no caption we should not react to anything.
                    return false;
                }
                // A drag action is started if we aren't dragging already and the starting event is
                // either a left mouse button or any other input device.
                if (!mDragging &&
                        (e.getToolType(e.getActionIndex()) != MotionEvent.TOOL_TYPE_MOUSE ||
                                (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0)) {
                    mDragging = true;
                    mLeftMouseButtonReleased = false;
                    startMovingTask(e.getRawX(), e.getRawY());
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mDragging && !mLeftMouseButtonReleased) {
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
                return true;
        }
        return mDragging;
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
    public void onClick(View view) {
        if (view.getId() == R.id.maximize_window) {
            maximizeWindow();
        } else if (view.getId() == R.id.close_window) {
            mOwner.dispatchOnWindowDismissed(true /*finishTask*/);
        }
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
        } else {
            captionHeight = 0;
        }

        if (mContent != null) {
            if (mOverlayWithAppContent) {
                mContent.layout(0, 0, mContent.getMeasuredWidth(), mContent.getMeasuredHeight());
            } else {
                mContent.layout(0, captionHeight, mContent.getMeasuredWidth(),
                        captionHeight + mContent.getMeasuredHeight());
            }
        }
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
                callback.changeWindowStack(FULLSCREEN_WORKSPACE_STACK_ID);
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
}
