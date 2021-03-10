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

package com.android.wm.shell.onehanded;

import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.Nullable;
import android.content.Context;
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
import android.view.WindowManager;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;

import java.io.PrintWriter;

/**
 * The class manage swipe up and down gesture for 3-Button mode navigation,
 * others(e.g, 2-button, full gesture mode) are handled by Launcher quick steps.
 * TODO(b/160934654) Migrate to Launcher quick steps
 */
public class OneHandedGestureHandler implements OneHandedTransitionCallback,
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
    private final WindowManager mWindowManager;

    private boolean mPassedSlop;
    private boolean mAllowGesture;
    private boolean mIsEnabled;
    private int mNavGestureHeight;
    private boolean mIsThreeButtonModeEnabled;
    private int mRotation = Surface.ROTATION_0;

    @VisibleForTesting
    InputMonitor mInputMonitor;
    @VisibleForTesting
    InputEventReceiver mInputEventReceiver;
    private final ShellExecutor mMainExecutor;
    @VisibleForTesting
    @Nullable
    OneHandedGestureEventCallback mGestureEventCallback;
    private Rect mGestureRegion = new Rect();
    private boolean mIsStopGesture;

    /**
     * Constructor of OneHandedGestureHandler, we only handle the gesture of
     * {@link Display#DEFAULT_DISPLAY}
     *
     * @param context                  {@link Context}
     * @param displayController        {@link DisplayController}
     */
    public OneHandedGestureHandler(Context context, WindowManager windowManager,
            DisplayController displayController, ViewConfiguration viewConfig,
            ShellExecutor mainExecutor) {
        mWindowManager = windowManager;
        mMainExecutor = mainExecutor;
        displayController.addDisplayChangingController(this);
        mNavGestureHeight = getNavBarSize(context,
                displayController.getDisplayLayout(DEFAULT_DISPLAY));
        mDragDistThreshold = context.getResources().getDimensionPixelSize(
                R.dimen.gestures_onehanded_drag_threshold);
        final float slop = viewConfig.getScaledTouchSlop();
        mSquaredSlop = slop * slop;

        updateIsEnabled();
    }

    /**
     * Notified by {@link OneHandedController}, when user update settings of Enabled or Disabled
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

    void onThreeButtonModeEnabled(boolean isEnabled) {
        mIsThreeButtonModeEnabled = isEnabled;
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
                        if (distance > mDragDistThreshold) {
                            mIsStopGesture = true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (mLastPos.y >= mDownPos.y && mPassedSlop) {
                        mGestureEventCallback.onStart();
                    } else if (mIsStopGesture) {
                        mGestureEventCallback.onStop();
                    }
                    clearState();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    clearState();
                    break;
                default:
                    break;
            }
        }
    }

    private void clearState() {
        mPassedSlop = false;
        mIsStopGesture = false;
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

    private int getNavBarSize(Context context, @Nullable DisplayLayout displayLayout) {
        if (displayLayout != null) {
            return displayLayout.navBarFrameHeight();
        } else {
            return isRotated()
                    ? context.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_height_landscape)
                    : context.getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_height);
        }
    }

    private void updateIsEnabled() {
        disposeInputChannel();

        // Either OHM or swipe notification shade can activate in portrait mode only
        if (mIsEnabled && mIsThreeButtonModeEnabled && !isRotated()) {
            final Rect displaySize = mWindowManager.getCurrentWindowMetrics().getBounds();
            // Register input event receiver to monitor the touch region of NavBar gesture height
            mGestureRegion.set(0, displaySize.height() - mNavGestureHeight, displaySize.width(),
                    displaySize.height());
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "onehanded-gesture-offset", DEFAULT_DISPLAY);
            try {
                mMainExecutor.executeBlocking(() -> {
                    mInputEventReceiver = new EventReceiver(
                            mInputMonitor.getInputChannel(), Looper.myLooper());
                });
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to create input event receiver", e);
            }
        }
    }

    private void onInputEvent(InputEvent ev) {
        if (ev instanceof MotionEvent) {
            onMotionEvent((MotionEvent) ev);
        }
    }

    @Override
    public void onRotateDisplay(int displayId, int fromRotation, int toRotation,
            WindowContainerTransaction t) {
        mRotation = toRotation;
        updateIsEnabled();
    }

    // TODO: Use BatchedInputEventReceiver
    private class EventReceiver extends InputEventReceiver {
        EventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        public void onInputEvent(InputEvent event) {
            OneHandedGestureHandler.this.onInputEvent(event);
            finishInputEvent(event, true);
        }
    }

    private boolean isRotated() {
        return mRotation == Surface.ROTATION_90 || mRotation == Surface.ROTATION_270;
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

    void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG + "States: ");
        pw.print(innerPrefix + "mIsEnabled=");
        pw.println(mIsEnabled);
        pw.print(innerPrefix + "mNavGestureHeight=");
        pw.println(mNavGestureHeight);
        pw.print(innerPrefix + "mIsThreeButtonModeEnabled=");
        pw.println(mIsThreeButtonModeEnabled);
        pw.print(innerPrefix + "isLandscape=");
        pw.println(isRotated());
    }

    /**
     * The touch(gesture) events to notify {@link OneHandedController} start or stop one handed
     */
    public interface OneHandedGestureEventCallback {
        /**
         * Handles the start gesture.
         */
        void onStart();

        /**
         * Handles the exit gesture.
         */
        void onStop();
    }
}
