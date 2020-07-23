/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.onehanded;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.window.WindowContainerTransaction;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NavigationModeController;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The class manage swipe up and down gesture for 3-Button mode navigation,
 * others(e.g, 2-button, full gesture mode) are handled by Launcher quick steps.
 */
@Singleton
public class OneHandedGestureHandler implements OneHandedTransitionCallback,
            NavigationModeController.ModeChangedListener,
            DisplayChangeController.OnDisplayChangingListener {
    private static final String TAG = "OneHandedGestureHandler";
    private static final boolean DEBUG_GESTURE = false;

    private static final int ANGLE_MAX = 150;
    private static final int ANGLE_MIN = 30;
    private final float mDragDistThreshold;
    private final float mSquaredSlop;
    private final PointF mDownPos = new PointF();
    private final PointF mLastPos = new PointF();
    private final PointF mStartDragPos = new PointF();
    private boolean mPassedSlop;

    private boolean mAllowGesture;
    private boolean mIsEnabled;
    private int mNavGestureHeight;
    private boolean mIsThreeButtonModeEnable;
    private int mRotation = Surface.ROTATION_0;

    @VisibleForTesting
    InputMonitor mInputMonitor;
    @VisibleForTesting
    InputEventReceiver mInputEventReceiver;
    private DisplayController mDisplayController;
    @VisibleForTesting
    @Nullable
    OneHandedGestureEventCallback mGestureEventCallback;
    private Rect mGestureRegion = new Rect();

    /**
     * Constructor of OneHandedGestureHandler, we only handle the gesture of
     * {@link Display#DEFAULT_DISPLAY}
     *
     * @param context           {@link Context}
     * @param displayController {@link DisplayController}
     * @param navigationModeController {@link NavigationModeController}
     */
    @Inject
    public OneHandedGestureHandler(Context context, DisplayController displayController,
            NavigationModeController navigationModeController) {
        mDisplayController = displayController;
        displayController.addDisplayChangingController(this);
        final int NavBarMode = navigationModeController.addListener(this);
        mIsThreeButtonModeEnable = (NavBarMode == NAV_BAR_MODE_3BUTTON);
        mNavGestureHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_gesture_height);
        mDragDistThreshold = context.getResources().getDimensionPixelSize(
                R.dimen.gestures_onehanded_drag_threshold);
        final float slop = ViewConfiguration.get(context).getScaledTouchSlop();
        mSquaredSlop =  slop * slop;
        updateIsEnabled();
    }

    /**
     * Notified by {@link OneHandedManager}, when user update settings of Enabled or Disabled
     *
     * @param isEnabled is one handed settings enabled or not
     */
    public void onOneHandedEnabled(boolean isEnabled) {
        if (DEBUG_GESTURE) {
            Log.d(TAG, "onOneHandedEnabled, isEnabled = " + isEnabled);
        }
        mIsEnabled = isEnabled;
        updateIsEnabled();
    }

    /**
     * Register {@link OneHandedGestureEventCallback} to receive onStart(), onStop() callback
     */
    public void setGestureEventListener(OneHandedGestureEventCallback callback) {
        mGestureEventCallback = callback;
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mAllowGesture = isWithinTouchRegion(ev.getX(), ev.getY())
                    && mRotation == Surface.ROTATION_0;
            if (mAllowGesture) {
                mDownPos.set(ev.getX(), ev.getY());
                mLastPos.set(mDownPos);
            }
            if (DEBUG_GESTURE) {
                Log.d(TAG, "ACTION_DOWN, mDownPos=" + mDownPos + ", mAllowGesture="
                        + mAllowGesture);
            }
        } else if (mAllowGesture) {
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    mLastPos.set(ev.getX(), ev.getY());
                    if (!mPassedSlop) {
                        if (squaredHypot(mLastPos.x - mDownPos.x, mLastPos.y - mDownPos.y)
                                > mSquaredSlop) {
                            mStartDragPos.set(mLastPos.x, mLastPos.y);
                            if (isValidStartAngle(
                                    mDownPos.x - mLastPos.x, mDownPos.y - mLastPos.y)
                                    || isValidExitAngle(
                                    mDownPos.x - mLastPos.x, mDownPos.y - mLastPos.y)) {
                                mPassedSlop = true;
                                mInputMonitor.pilferPointers();
                            }
                        }
                    } else {
                        float distance = (float) Math.hypot(mLastPos.x - mDownPos.x,
                                mLastPos.y - mDownPos.y);
                        if (distance > mDragDistThreshold && mPassedSlop) {
                            mGestureEventCallback.onStop();
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (mLastPos.y >= mDownPos.y && mPassedSlop) {
                        mGestureEventCallback.onStart();
                    }
                    mPassedSlop = false;
                    mAllowGesture = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    mPassedSlop = false;
                    mAllowGesture = false;
                    break;
                default:
                    break;
            }
        }
    }

    private void disposeInputChannel() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }

        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private boolean isWithinTouchRegion(float x, float y) {
        if (DEBUG_GESTURE) {
            Log.d(TAG, "isWithinTouchRegion(), mGestureRegion=" + mGestureRegion + ", downX=" + x
                    + ", downY=" + y);
        }
        return mGestureRegion.contains(Math.round(x), Math.round(y));
    }

    private void updateIsEnabled() {
        disposeInputChannel();

        if (mIsEnabled && mIsThreeButtonModeEnable) {
            final Point displaySize = new Point();
            if (mDisplayController != null) {
                final Display display = mDisplayController.getDisplay(DEFAULT_DISPLAY);
                if (display != null) {
                    display.getRealSize(displaySize);
                }
            }
            // Register input event receiver to monitor the touch region of NavBar gesture height
            mGestureRegion.set(0, displaySize.y - mNavGestureHeight, displaySize.x,
                    displaySize.y);
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "onehanded-gesture-offset", DEFAULT_DISPLAY);
            mInputEventReceiver = new SysUiInputEventReceiver(
                    mInputMonitor.getInputChannel(), Looper.getMainLooper());
        }
    }

    private void onInputEvent(InputEvent ev) {
        if (ev instanceof MotionEvent) {
            onMotionEvent((MotionEvent) ev);
        }
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        if (DEBUG_GESTURE) {
            Log.d(TAG, "onNavigationModeChanged, mode =" + mode);
        }
        mIsThreeButtonModeEnable = (mode == NAV_BAR_MODE_3BUTTON);
        updateIsEnabled();
    }

    @Override
    public void onRotateDisplay(int displayId, int fromRotation, int toRotation,
            WindowContainerTransaction t) {
        mRotation = toRotation;
    }

    private class SysUiInputEventReceiver extends InputEventReceiver {
        SysUiInputEventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        public void onInputEvent(InputEvent event) {
            OneHandedGestureHandler.this.onInputEvent(event);
            finishInputEvent(event, true);
        }
    }

    private boolean isValidStartAngle(float deltaX, float deltaY) {
        final float angle = (float) Math.toDegrees(Math.atan2(deltaY, deltaX));
        return angle > -(ANGLE_MAX) && angle < -(ANGLE_MIN);
    }

    private boolean isValidExitAngle(float deltaX, float deltaY) {
        final float angle = (float) Math.toDegrees(Math.atan2(deltaY, deltaX));
        return angle > ANGLE_MIN && angle < ANGLE_MAX;
    }

    private float squaredHypot(float x, float y) {
        return x * x + y * y;
    }

    /**
     * The touch(gesture) events to notify {@link OneHandedManager} start or stop one handed
     */
    public interface OneHandedGestureEventCallback {
        /**
         * Handle the start event event, and return whether the event was consumed.
         */
        boolean onStart();

        /**
         * Handle the exit event event, and return whether the event was consumed.
         */
        boolean onStop();
    }
}
