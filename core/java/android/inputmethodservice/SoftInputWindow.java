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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.Debug;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.lang.annotation.Retention;

/**
 * A SoftInputWindow is a Dialog that is intended to be used for a top-level input
 * method window.  It will be displayed along the edge of the screen, moving
 * the application user interface away from it so that the focused item is
 * always visible.
 * @hide
 */
public class SoftInputWindow extends Dialog {
    private static final boolean DEBUG = false;
    private static final String TAG = "SoftInputWindow";

    final String mName;
    final Callback mCallback;
    final KeyEvent.Callback mKeyEventCallback;
    final KeyEvent.DispatcherState mDispatcherState;
    final int mWindowType;
    final int mGravity;
    final boolean mTakesFocus;
    private final Rect mBounds = new Rect();

    @Retention(SOURCE)
    @IntDef(value = {SoftInputWindowState.TOKEN_PENDING, SoftInputWindowState.TOKEN_SET,
            SoftInputWindowState.SHOWN_AT_LEAST_ONCE, SoftInputWindowState.REJECTED_AT_LEAST_ONCE})
    private @interface SoftInputWindowState {
        /**
         * The window token is not set yet.
         */
        int TOKEN_PENDING = 0;
        /**
         * The window token was set, but the window is not shown yet.
         */
        int TOKEN_SET = 1;
        /**
         * The window was shown at least once.
         */
        int SHOWN_AT_LEAST_ONCE = 2;
        /**
         * {@link android.view.WindowManager.BadTokenException} was sent when calling
         * {@link Dialog#show()} at least once.
         */
        int REJECTED_AT_LEAST_ONCE = 3;
        /**
         * The window is considered destroyed.  Any incoming request should be ignored.
         */
        int DESTROYED = 4;
    }

    @SoftInputWindowState
    private int mWindowState = SoftInputWindowState.TOKEN_PENDING;

    public interface Callback {
        public void onBackPressed();
    }

    public void setToken(IBinder token) {
        switch (mWindowState) {
            case SoftInputWindowState.TOKEN_PENDING:
                // Normal scenario.  Nothing to worry about.
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.token = token;
                getWindow().setAttributes(lp);
                updateWindowState(SoftInputWindowState.TOKEN_SET);
                return;
            case SoftInputWindowState.TOKEN_SET:
            case SoftInputWindowState.SHOWN_AT_LEAST_ONCE:
            case SoftInputWindowState.REJECTED_AT_LEAST_ONCE:
                throw new IllegalStateException("setToken can be called only once");
            case SoftInputWindowState.DESTROYED:
                // Just ignore.  Since there are multiple event queues from the token is issued
                // in the system server to the timing when it arrives here, it can be delivered
                // after the is already destroyed.  No one should be blamed because of such an
                // unfortunate but possible scenario.
                Log.i(TAG, "Ignoring setToken() because window is already destroyed.");
                return;
            default:
                throw new IllegalStateException("Unexpected state=" + mWindowState);
        }
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
     * @param gravity The boundary of the screen to stick. See {@link
     *        android.view.Gravity.LEFT}, {@link android.view.Gravity.TOP},
     *        {@link android.view.Gravity.BOTTOM}, {@link
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

    @Override
    public final void show() {
        switch (mWindowState) {
            case SoftInputWindowState.TOKEN_PENDING:
                throw new IllegalStateException("Window token is not set yet.");
            case SoftInputWindowState.TOKEN_SET:
            case SoftInputWindowState.SHOWN_AT_LEAST_ONCE:
                // Normal scenario.  Nothing to worry about.
                try {
                    super.show();
                    updateWindowState(SoftInputWindowState.SHOWN_AT_LEAST_ONCE);
                } catch (WindowManager.BadTokenException e) {
                    // Just ignore this exception.  Since show() can be requested from other
                    // components such as the system and there could be multiple event queues before
                    // the request finally arrives here, the system may have already invalidated the
                    // window token attached to our window.  In such a scenario, receiving
                    // BadTokenException here is an expected behavior.  We just ignore it and update
                    // the state so that we do not touch this window later.
                    Log.i(TAG, "Probably the IME window token is already invalidated."
                            + " show() does nothing.");
                    updateWindowState(SoftInputWindowState.REJECTED_AT_LEAST_ONCE);
                }
                return;
            case SoftInputWindowState.REJECTED_AT_LEAST_ONCE:
                // Just ignore.  In general we cannot completely avoid this kind of race condition.
                Log.i(TAG, "Not trying to call show() because it was already rejected once.");
                return;
            case SoftInputWindowState.DESTROYED:
                // Just ignore.  In general we cannot completely avoid this kind of race condition.
                Log.i(TAG, "Ignoring show() because the window is already destroyed.");
                return;
            default:
                throw new IllegalStateException("Unexpected state=" + mWindowState);
        }
    }

    final void dismissForDestroyIfNecessary() {
        switch (mWindowState) {
            case SoftInputWindowState.TOKEN_PENDING:
            case SoftInputWindowState.TOKEN_SET:
                // nothing to do because the window has never been shown.
                updateWindowState(SoftInputWindowState.DESTROYED);
                return;
            case SoftInputWindowState.SHOWN_AT_LEAST_ONCE:
                // Disable exit animation for the current IME window
                // to avoid the race condition between the exit and enter animations
                // when the current IME is being switched to another one.
                try {
                    getWindow().setWindowAnimations(0);
                    dismiss();
                } catch (WindowManager.BadTokenException e) {
                    // Just ignore this exception.  Since show() can be requested from other
                    // components such as the system and there could be multiple event queues before
                    // the request finally arrives here, the system may have already invalidated the
                    // window token attached to our window.  In such a scenario, receiving
                    // BadTokenException here is an expected behavior.  We just ignore it and update
                    // the state so that we do not touch this window later.
                    Log.i(TAG, "Probably the IME window token is already invalidated. "
                            + "No need to dismiss it.");
                }
                // Either way, consider that the window is destroyed.
                updateWindowState(SoftInputWindowState.DESTROYED);
                return;
            case SoftInputWindowState.REJECTED_AT_LEAST_ONCE:
                // Just ignore.  In general we cannot completely avoid this kind of race condition.
                Log.i(TAG,
                        "Not trying to dismiss the window because it is most likely unnecessary.");
                // Anyway, consider that the window is destroyed.
                updateWindowState(SoftInputWindowState.DESTROYED);
                return;
            case SoftInputWindowState.DESTROYED:
                throw new IllegalStateException(
                        "dismissForDestroyIfNecessary can be called only once");
            default:
                throw new IllegalStateException("Unexpected state=" + mWindowState);
        }
    }

    private void updateWindowState(@SoftInputWindowState int newState) {
        if (DEBUG) {
            if (mWindowState != newState) {
                Log.d(TAG, "WindowState: " + stateToString(mWindowState) + " -> "
                        + stateToString(newState) + " @ " + Debug.getCaller());
            }
        }
        mWindowState = newState;
    }

    private static String stateToString(@SoftInputWindowState int state) {
        switch (state) {
            case SoftInputWindowState.TOKEN_PENDING:
                return "TOKEN_PENDING";
            case SoftInputWindowState.TOKEN_SET:
                return "TOKEN_SET";
            case SoftInputWindowState.SHOWN_AT_LEAST_ONCE:
                return "SHOWN_AT_LEAST_ONCE";
            case SoftInputWindowState.REJECTED_AT_LEAST_ONCE:
                return "REJECTED_AT_LEAST_ONCE";
            case SoftInputWindowState.DESTROYED:
                return "DESTROYED";
            default:
                throw new IllegalStateException("Unknown state=" + state);
        }
    }
}
