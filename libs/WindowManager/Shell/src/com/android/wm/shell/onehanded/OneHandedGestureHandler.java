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
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;

import java.io.PrintWriter;

/**
 * The class manage swipe up and down gesture for 3-Button mode navigation, others(e.g, 2-button,
 * full gesture mode) are handled by Launcher quick steps. TODO(b/160934654) Migrate to Launcher
 * quick steps
 */
public class OneHandedGestureHandler implements OneHandedTransitionCallback {
    private static final String TAG = "OneHandedGestureHandler";

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
     * Constructor of OneHandedGestureHandler, we only handle the gesture of {@link
     * Display#DEFAULT_DISPLAY}
     *
     * @param context       Any context
     * @param displayLayout Current {@link DisplayLayout} from controller
     * @param viewConfig    {@link ViewConfiguration} to obtain touch slop
     * @param mainExecutor  The wm-shell main executor
     */
    public OneHandedGestureHandler(Context context,
            DisplayLayout displayLayout,
            ViewConfiguration viewConfig,
            ShellExecutor mainExecutor) {
        mMainExecutor = mainExecutor;
        mDragDistThreshold = context.getResources().getDimensionPixelSize(
                R.dimen.gestures_onehanded_drag_threshold);

        final float slop = viewConfig.getScaledTouchSlop();
        mSquaredSlop = slop * slop;
        onDisplayChanged(displayLayout);
        updateIsEnabled();
    }

    /**
     * Notifies by {@link OneHandedController}, when swipe down gesture is enabled on 3 button
     * navigation bar mode.
     *
     * @param isEnabled Either one handed mode or swipe for notification function enabled or not
     */
    public void onGestureEnabled(boolean isEnabled) {
        mIsEnabled = isEnabled;
        updateIsEnabled();
    }

    void onThreeButtonModeEnabled(boolean isEnabled) {
        mIsThreeButtonModeEnabled = isEnabled;
        updateIsEnabled();
    }

    /**
     * Registers {@link OneHandedGestureEventCallback} to receive onStart(), onStop() callback
     */
    public void setGestureEventListener(OneHandedGestureEventCallback callback) {
        mGestureEventCallback = callback;
    }

    /**
     * Called when onDisplayAdded() or onDisplayRemoved() callback
     * @param displayLayout The latest {@link DisplayLayout} representing current displayId
     */
    public void onDisplayChanged(DisplayLayout displayLayout) {
        mNavGestureHeight = getNavBarSize(displayLayout);
        mGestureRegion.set(0, displayLayout.height() - mNavGestureHeight, displayLayout.width(),
                displayLayout.height());
        mRotation = displayLayout.rotation();
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mAllowGesture = isWithinTouchRegion(ev.getX(), ev.getY()) && isGestureAvailable();
            if (mAllowGesture) {
                mDownPos.set(ev.getX(), ev.getY());
                mLastPos.set(mDownPos);
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
        return mGestureRegion.contains(Math.round(x), Math.round(y));
    }

    private int getNavBarSize(@NonNull DisplayLayout displayLayout) {
        return isGestureAvailable() ? displayLayout.navBarFrameHeight() : 0 /* In landscape */;
    }

    private void updateIsEnabled() {
        disposeInputChannel();

        if (mIsEnabled && mIsThreeButtonModeEnabled && isGestureAvailable()) {
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

    /**
     * Handler for display rotation changes by {@link DisplayLayout}
     *
     * @param displayLayout The rotated displayLayout
     */
    public void onRotateDisplay(DisplayLayout displayLayout) {
        mRotation = displayLayout.rotation();
        mNavGestureHeight = getNavBarSize(displayLayout);
        mGestureRegion.set(0, displayLayout.height() - mNavGestureHeight, displayLayout.width(),
                displayLayout.height());
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

    private boolean isGestureAvailable() {
        // Either OHM or swipe notification shade can activate in portrait mode only
        return mRotation == Surface.ROTATION_0 || mRotation == Surface.ROTATION_180;
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
        pw.print(innerPrefix + "mAllowGesture=");
        pw.println(mAllowGesture);
        pw.print(innerPrefix + "mIsEnabled=");
        pw.println(mIsEnabled);
        pw.print(innerPrefix + "mGestureRegion=");
        pw.println(mGestureRegion);
        pw.print(innerPrefix + "mNavGestureHeight=");
        pw.println(mNavGestureHeight);
        pw.print(innerPrefix + "mIsThreeButtonModeEnabled=");
        pw.println(mIsThreeButtonModeEnabled);
        pw.print(innerPrefix + "mRotation=");
        pw.println(mRotation);
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
