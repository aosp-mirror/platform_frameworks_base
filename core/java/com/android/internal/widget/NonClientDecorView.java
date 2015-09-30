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
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.util.Log;
import android.util.TypedValue;

import com.android.internal.R;
import com.android.internal.policy.PhoneWindow;

/**
 * This class represents the special screen elements to control a window on free form
 * environment. All thse screen elements are added in the "non client area" which is the area of
 * the window which is handled by the OS and not the application.
 * As such this class handles the following things:
 * <ul>
 * <li>The caption, containing the system buttons like maximize, close and such as well as
 * allowing the user to drag the window around.</li>
 * <li>The shadow - which is changing dependent on the window focus.</li>
 * <li>The border around the client area (if there is one).</li>
 * <li>The resize handles which allow to resize the window.</li>
 * </ul>
 * After creating the view, the function
 * {@link #setPhoneWindow(PhoneWindow owner, boolean windowHasShadow)} needs to be called to make
 * the connection to it's owning PhoneWindow.
 * Note: At this time the application can change various attributes of the DecorView which
 * will break things (in settle/unexpected ways):
 * <ul>
 * <li>setElevation</li>
 * <li>setOutlineProvider</li>
 * <li>setSurfaceFormat</li>
 * <li>..</li>
 * </ul>
 * This will be mitigated once b/22527834 will be addressed.
 */
public class NonClientDecorView extends LinearLayout
        implements View.OnClickListener, View.OnTouchListener {
    private final static String TAG = "NonClientDecorView";
    // The height of a window which has focus in DIP.
    private final int DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP = 20;
    // The height of a window which has not in DIP.
    private final int DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP = 5;
    private PhoneWindow mOwner = null;
    private boolean mWindowHasShadow = false;
    private boolean mShowDecor = false;

    // True if the window is being dragged.
    private boolean mDragging = false;

    // True when the left mouse button got released while dragging.
    private boolean mLeftMouseButtonReleased;

    // True if this window is resizable (which is currently only true when the decor is shown).
    public boolean mVisible = false;

    // The current focus state of the window for updating the window elevation.
    private boolean mWindowHasFocus = true;

    // Cludge to address b/22668382: Set the shadow size to the maximum so that the layer
    // size calculation takes the shadow size into account. We set the elevation currently
    // to max until the first layout command has been executed.
    private boolean mAllowUpdateElevation = false;

    public NonClientDecorView(Context context) {
        super(context);
    }

    public NonClientDecorView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NonClientDecorView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setPhoneWindow(PhoneWindow owner, boolean showDecor, boolean windowHasShadow) {
        mOwner = owner;
        mWindowHasShadow = windowHasShadow;
        mShowDecor = showDecor;
        updateCaptionVisibility();
        if (mWindowHasShadow) {
            initializeElevation();
        }
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
                if (!mShowDecor) {
                    // When there is no decor we should not react to anything.
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
     * The phone window configuration has changed and the decor needs to be updated.
     * @param showDecor True if the decor should be shown.
     * @param windowHasShadow True when the window should show a shadow.
     **/
    public void phoneWindowUpdated(boolean showDecor, boolean windowHasShadow) {
        mShowDecor = showDecor;
        updateCaptionVisibility();
        if (windowHasShadow != mWindowHasShadow) {
            mWindowHasShadow = windowHasShadow;
            initializeElevation();
        }
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
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        mWindowHasFocus = hasWindowFocus;
        updateElevation();
        super.onWindowFocusChanged(hasWindowFocus);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // If the application changed its SystemUI metrics, we might also have to adapt
        // our shadow elevation.
        updateElevation();
        mAllowUpdateElevation = true;

        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        // Make sure that we never get more then one client area in our view.
        if (index >= 2 || getChildCount() >= 2) {
            throw new IllegalStateException("NonClientDecorView can only handle 1 client view");
        }
        super.addView(child, index, params);
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
        // Don't show the decor if the window has e.g. entered full screen.
        boolean invisible = isFillingScreen() || !mShowDecor;
        View caption = getChildAt(0);
        caption.setVisibility(invisible ? GONE : VISIBLE);
        caption.setOnTouchListener(this);
        mVisible = !invisible;
    }

    /**
     * The elevation gets set for the first time and the framework needs to be informed that
     * the surface layer gets created with the shadow size in mind.
     **/
    private void initializeElevation() {
        // TODO(skuhne): Call setMaxElevation here accordingly after b/22668382 got fixed.
        mAllowUpdateElevation = false;
        if (mWindowHasShadow) {
            updateElevation();
        } else {
            mOwner.setElevation(0);
        }
    }

    /**
     * The shadow height gets controlled by the focus to visualize highlighted windows.
     * Note: This will overwrite application elevation properties.
     * Note: Windows which have (temporarily) changed their attributes to cover the SystemUI
     *       will get no shadow as they are expected to be "full screen".
     **/
    private void updateElevation() {
        float elevation = 0;
        if (mWindowHasShadow) {
            boolean fill = isFillingScreen();
            elevation = fill ? 0 :
                    (mWindowHasFocus ? DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP :
                            DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP);
            // TODO(skuhne): Remove this if clause once b/22668382 got fixed.
            if (!mAllowUpdateElevation && !fill) {
                elevation = DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP;
            }
            // Convert the DP elevation into physical pixels.
            elevation = dipToPx(elevation);
        }
        // Don't change the elevation if it didn't change since it can require some time.
        if (mOwner.getDecorView().getElevation() != elevation) {
            mOwner.setElevation(elevation);
        }
    }

    /**
     * Converts a DIP measure into physical pixels.
     * @param dip The dip value.
     * @return Returns the number of pixels.
     */
    private float dipToPx(float dip) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip,
                getResources().getDisplayMetrics());
    }

    /**
     * Maximize the window by moving it to the maximized workspace stack.
     **/
    private void maximizeWindow() {
        Window.WindowControllerCallback callback = mOwner.getWindowControllerCallback();
        if (callback != null) {
            try {
                callback.changeWindowStack(
                        android.app.ActivityManager.FULLSCREEN_WORKSPACE_STACK_ID);
            } catch (RemoteException ex) {
                Log.e(TAG, "Cannot change task workspace.");
            }
        }
    }
}
