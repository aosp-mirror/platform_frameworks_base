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
import android.os.RemoteException;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
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
public class NonClientDecorView extends ViewGroup implements View.OnClickListener {
    private final static String TAG = "NonClientDecorView";
    // The height of a window which has focus in DIP.
    private final int DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP = 20;
    // The height of a window which has not in DIP.
    private final int DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP = 5;

    private PhoneWindow mOwner = null;
    boolean mWindowHasShadow = false;
    boolean mShowDecor = false;

    // The current focus state of the window for updating the window elevation.
    boolean mWindowHasFocus = true;

    // Cludge to address b/22668382: Set the shadow size to the maximum so that the layer
    // size calculation takes the shadow size into account. We set the elevation currently
    // to max until the first layout command has been executed.
    boolean mAllowUpdateElevation = false;

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
        if (mWindowHasShadow) {
            initializeElevation();
        }
        // By changing the outline provider to BOUNDS, the window can remove its
        // background without removing the shadow.
        mOwner.getDecorView().setOutlineProvider(ViewOutlineProvider.BOUNDS);
        findViewById(R.id.maximize_window).setOnClickListener(this);
        findViewById(R.id.close_window).setOnClickListener(this);
    }

    /**
     * The phone window configuration has changed and the decor needs to be updated.
     * @param showDecor True if the decor should be shown.
     * @param windowHasShadow True when the window should show a shadow.
     **/
    public void phoneWindowUpdated(boolean showDecor, boolean windowHasShadow) {
        mShowDecor = showDecor;
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
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // The system inset needs only to be applied to the caption. The client area of
        // the window will automatically be adjusted by the the DecorView.
        WindowInsets insets = getRootWindowInsets();
        int systemMargin = insets.getSystemWindowInsetTop();

        final int leftPos = getPaddingLeft();
        final int rightPos = right - left - getPaddingRight();
        final int topPos = getPaddingTop();
        final int bottomPos = bottom - top - getPaddingBottom();

        // On top we have the caption which has to fill left to right with a fixed height.
        final int width = rightPos - leftPos;
        final View caption = getChildAt(0);

        // If the application changed its SystemUI metrics, we might also have to adapt
        // our shadow elevation.
        updateElevation();
        mAllowUpdateElevation = true;

        // Don't show the decor if the window has e.g. entered full screen.
        final int captionHeight =
                (isFillingScreen() || !mShowDecor) ? 0 : caption.getMeasuredHeight();
        caption.layout(leftPos, topPos + systemMargin, leftPos + width,
                topPos + systemMargin + captionHeight);

        // Note: We should never have more then 1 additional item in here.
        if (getChildCount() > 1) {
            getChildAt(1).layout(leftPos, topPos + captionHeight, leftPos + width, bottomPos);
        }
    }

    @Override
    public void addView(View child, int index, LayoutParams params) {
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
        Window.WindowStackCallback callback = mOwner.getWindowStackCallback();
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
