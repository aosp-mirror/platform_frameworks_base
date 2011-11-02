/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.inputmethodservice;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * A SoftInputWindow is a Dialog that is intended to be used for a top-level input
 * method window.  It will be displayed along the edge of the screen, moving
 * the application user interface away from it so that the focused item is
 * always visible.
 */
class SoftInputWindow extends Dialog {
    final KeyEvent.DispatcherState mDispatcherState;
    private final Rect mBounds = new Rect();
    
    public void setToken(IBinder token) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.token = token;
        getWindow().setAttributes(lp);
    }
    
    /**
     * Create a SoftInputWindow that uses a custom style.
     * 
     * @param context The Context in which the DockWindow should run. In
     *        particular, it uses the window manager and theme from this context
     *        to present its UI.
     * @param theme A style resource describing the theme to use for the window.
     *        See <a href="{@docRoot}reference/available-resources.html#stylesandthemes">Style
     *        and Theme Resources</a> for more information about defining and
     *        using styles. This theme is applied on top of the current theme in
     *        <var>context</var>. If 0, the default dialog theme will be used.
     */
    public SoftInputWindow(Context context, int theme,
            KeyEvent.DispatcherState dispatcherState) {
        super(context, theme);
        mDispatcherState = dispatcherState;
        initDockWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        mDispatcherState.reset();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        getWindow().getDecorView().getHitRect(mBounds);

        if (ev.isWithinBoundsNoHistory(mBounds.left, mBounds.top,
                mBounds.right - 1, mBounds.bottom - 1)) {
            return super.dispatchTouchEvent(ev);
        } else {
            MotionEvent temp = ev.clampNoHistory(mBounds.left, mBounds.top,
                    mBounds.right - 1, mBounds.bottom - 1);
            boolean handled = super.dispatchTouchEvent(temp);
            temp.recycle();
            return handled;
        }
    }

    /**
     * Get the size of the DockWindow.
     * 
     * @return If the DockWindow sticks to the top or bottom of the screen, the
     *         return value is the height of the DockWindow, and its width is
     *         equal to the width of the screen; If the DockWindow sticks to the
     *         left or right of the screen, the return value is the width of the
     *         DockWindow, and its height is equal to the height of the screen.
     */
    public int getSize() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();

        if (lp.gravity == Gravity.TOP || lp.gravity == Gravity.BOTTOM) {
            return lp.height;
        } else {
            return lp.width;
        }
    }

    /**
     * Set the size of the DockWindow.
     * 
     * @param size If the DockWindow sticks to the top or bottom of the screen,
     *        <var>size</var> is the height of the DockWindow, and its width is
     *        equal to the width of the screen; If the DockWindow sticks to the
     *        left or right of the screen, <var>size</var> is the width of the
     *        DockWindow, and its height is equal to the height of the screen.
     */
    public void setSize(int size) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();

        if (lp.gravity == Gravity.TOP || lp.gravity == Gravity.BOTTOM) {
            lp.width = -1;
            lp.height = size;
        } else {
            lp.width = size;
            lp.height = -1;
        }
        getWindow().setAttributes(lp);
    }

    /**
     * Set which boundary of the screen the DockWindow sticks to.
     * 
     * @param gravity The boundary of the screen to stick. See {#link
     *        android.view.Gravity.LEFT}, {#link android.view.Gravity.TOP},
     *        {#link android.view.Gravity.BOTTOM}, {#link
     *        android.view.Gravity.RIGHT}.
     */
    public void setGravity(int gravity) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();

        boolean oldIsVertical = (lp.gravity == Gravity.TOP || lp.gravity == Gravity.BOTTOM);

        lp.gravity = gravity;

        boolean newIsVertical = (lp.gravity == Gravity.TOP || lp.gravity == Gravity.BOTTOM);

        if (oldIsVertical != newIsVertical) {
            int tmp = lp.width;
            lp.width = lp.height;
            lp.height = tmp;
            getWindow().setAttributes(lp);
        }
    }

    private void initDockWindow() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();

        lp.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        lp.setTitle("InputMethod");

        lp.gravity = Gravity.BOTTOM;
        lp.width = -1;
        // Let the input method window's orientation follow sensor based rotation
        // Turn this off for now, it is very problematic.
        //lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;

        getWindow().setAttributes(lp);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }
}
