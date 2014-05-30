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
 * @hide
 */
public class SoftInputWindow extends Dialog {
    final String mName;
    final Callback mCallback;
    final KeyEvent.Callback mKeyEventCallback;
    final KeyEvent.DispatcherState mDispatcherState;
    final int mWindowType;
    final int mGravity;
    final boolean mTakesFocus;
    private final Rect mBounds = new Rect();

    public interface Callback {
        public void onBackPressed();
    }

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
    public SoftInputWindow(Context context, String name, int theme, Callback callback,
            KeyEvent.Callback keyEventCallback, KeyEvent.DispatcherState dispatcherState,
            int windowType, int gravity, boolean takesFocus) {
        super(context, theme);
        mName = name;
        mCallback = callback;
        mKeyEventCallback = keyEventCallback;
        mDispatcherState = dispatcherState;
        mWindowType = windowType;
        mGravity = gravity;
        mTakesFocus = takesFocus;
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
     * Set which boundary of the screen the DockWindow sticks to.
     * 
     * @param gravity The boundary of the screen to stick. See {#link
     *        android.view.Gravity.LEFT}, {#link android.view.Gravity.TOP},
     *        {#link android.view.Gravity.BOTTOM}, {#link
     *        android.view.Gravity.RIGHT}.
     */
    public void setGravity(int gravity) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.gravity = gravity;
        updateWidthHeight(lp);
        getWindow().setAttributes(lp);
    }

    public int getGravity() {
        return getWindow().getAttributes().gravity;
    }

    private void updateWidthHeight(WindowManager.LayoutParams lp) {
        if (lp.gravity == Gravity.TOP || lp.gravity == Gravity.BOTTOM) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        } else {
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mKeyEventCallback != null && mKeyEventCallback.onKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (mKeyEventCallback != null && mKeyEventCallback.onKeyLongPress(keyCode, event)) {
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mKeyEventCallback != null && mKeyEventCallback.onKeyUp(keyCode, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
        if (mKeyEventCallback != null && mKeyEventCallback.onKeyMultiple(keyCode, count, event)) {
            return true;
        }
        return super.onKeyMultiple(keyCode, count, event);
    }

    public void onBackPressed() {
        if (mCallback != null) {
            mCallback.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }

    private void initDockWindow() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();

        lp.type = mWindowType;
        lp.setTitle(mName);

        lp.gravity = mGravity;
        updateWidthHeight(lp);

        getWindow().setAttributes(lp);

        int windowSetFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        int windowModFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_DIM_BEHIND;

        if (!mTakesFocus) {
            windowSetFlags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            windowSetFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            windowModFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }

        getWindow().setFlags(windowSetFlags, windowModFlags);
    }
}
