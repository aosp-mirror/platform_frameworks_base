/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input.debug;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.server.input.TouchpadFingerState;
import com.android.server.input.TouchpadHardwareState;

import java.util.Objects;

public class TouchpadDebugView extends LinearLayout {
    /**
     * Input device ID for the touchpad that this debug view is displaying.
     */
    private final int mTouchpadId;

    @NonNull
    private final WindowManager mWindowManager;

    @NonNull
    private final WindowManager.LayoutParams mWindowLayoutParams;

    private final int mTouchSlop;

    private float mTouchDownX;
    private float mTouchDownY;
    private int mScreenWidth;
    private int mScreenHeight;
    private int mWindowLocationBeforeDragX;
    private int mWindowLocationBeforeDragY;
    @NonNull
    private TouchpadHardwareState mLastTouchpadState =
            new TouchpadHardwareState(0, 0 /* buttonsDown */, 0, 0,
                    new TouchpadFingerState[0]);

    public TouchpadDebugView(Context context, int touchpadId) {
        super(context);
        mTouchpadId = touchpadId;
        mWindowManager =
                Objects.requireNonNull(getContext().getSystemService(WindowManager.class));
        init(context, touchpadId);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        // TODO(b/360137366): Use the hardware properties to initialise layout parameters.
        mWindowLayoutParams = new WindowManager.LayoutParams();
        mWindowLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mWindowLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mWindowLayoutParams.privateFlags |=
                WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        mWindowLayoutParams.setFitInsetsTypes(0);
        mWindowLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowLayoutParams.format = PixelFormat.TRANSLUCENT;
        mWindowLayoutParams.setTitle("TouchpadDebugView - display " + mContext.getDisplayId());

        mWindowLayoutParams.x = 40;
        mWindowLayoutParams.y = 100;
        mWindowLayoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
    }

    private void init(Context context, int touchpadId) {
        setOrientation(VERTICAL);
        setLayoutParams(new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));
        setBackgroundColor(Color.TRANSPARENT);

        TextView nameView = new TextView(context);
        nameView.setBackgroundColor(Color.RED);
        nameView.setTextSize(20);
        nameView.setText(Objects.requireNonNull(Objects.requireNonNull(
                        mContext.getSystemService(InputManager.class))
                .getInputDevice(touchpadId)).getName());
        nameView.setGravity(Gravity.CENTER);
        nameView.setTextColor(Color.WHITE);
        nameView.setLayoutParams(new LayoutParams(1000, 200));

        TouchpadVisualisationView touchpadVisualisationView =
                new TouchpadVisualisationView(context);
        touchpadVisualisationView.setBackgroundColor(Color.WHITE);
        touchpadVisualisationView.setLayoutParams(new LayoutParams(1000, 200));

        //TODO(b/365562952): Add a display for recognized gesture info here
        TextView gestureInfoView = new TextView(context);
        gestureInfoView.setBackgroundColor(Color.GRAY);
        gestureInfoView.setTextSize(20);
        gestureInfoView.setText("Touchpad Debug View 3");
        gestureInfoView.setGravity(Gravity.CENTER);
        gestureInfoView.setTextColor(Color.BLACK);
        gestureInfoView.setLayoutParams(new LayoutParams(1000, 200));

        addView(nameView);
        addView(touchpadVisualisationView);
        addView(gestureInfoView);

        updateScreenDimensions();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float deltaX;
        float deltaY;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mWindowLocationBeforeDragX = mWindowLayoutParams.x;
                mWindowLocationBeforeDragY = mWindowLayoutParams.y;
                mTouchDownX = event.getRawX() - mWindowLocationBeforeDragX;
                mTouchDownY = event.getRawY() - mWindowLocationBeforeDragY;
                return true;

            case MotionEvent.ACTION_MOVE:
                deltaX = event.getRawX() - mWindowLayoutParams.x - mTouchDownX;
                deltaY = event.getRawY() - mWindowLayoutParams.y - mTouchDownY;
                if (isSlopExceeded(deltaX, deltaY)) {
                    mWindowLayoutParams.x =
                            Math.max(0, Math.min((int) (event.getRawX() - mTouchDownX),
                                    mScreenWidth - this.getWidth()));
                    mWindowLayoutParams.y =
                            Math.max(0, Math.min((int) (event.getRawY() - mTouchDownY),
                                    mScreenHeight - this.getHeight()));

                    mWindowManager.updateViewLayout(this, mWindowLayoutParams);
                }
                return true;

            case MotionEvent.ACTION_UP:
                deltaX = event.getRawX() - mWindowLayoutParams.x - mTouchDownX;
                deltaY = event.getRawY() - mWindowLayoutParams.y - mTouchDownY;
                if (!isSlopExceeded(deltaX, deltaY)) {
                    performClick();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                // Move the window back to the original position
                mWindowLayoutParams.x = mWindowLocationBeforeDragX;
                mWindowLayoutParams.y = mWindowLocationBeforeDragY;
                mWindowManager.updateViewLayout(this, mWindowLayoutParams);
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        Slog.d("TouchpadDebugView", "You tapped the window!");
        return true;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenDimensions();

        // Adjust view position to stay within screen bounds after rotation
        mWindowLayoutParams.x =
                Math.max(0, Math.min(mWindowLayoutParams.x, mScreenWidth - getWidth()));
        mWindowLayoutParams.y =
                Math.max(0, Math.min(mWindowLayoutParams.y, mScreenHeight - getHeight()));
        mWindowManager.updateViewLayout(this, mWindowLayoutParams);
    }

    private boolean isSlopExceeded(float deltaX, float deltaY) {
        return deltaX * deltaX + deltaY * deltaY >= mTouchSlop * mTouchSlop;
    }

    private void updateScreenDimensions() {
        Rect windowBounds =
                mWindowManager.getCurrentWindowMetrics().getBounds();
        mScreenWidth = windowBounds.width();
        mScreenHeight = windowBounds.height();
    }

    public int getTouchpadId() {
        return mTouchpadId;
    }

    public WindowManager.LayoutParams getWindowLayoutParams() {
        return mWindowLayoutParams;
    }

    public void updateHardwareState(TouchpadHardwareState touchpadHardwareState) {
        if (mLastTouchpadState.getButtonsDown() == 0) {
            if (touchpadHardwareState.getButtonsDown() > 0) {
                onTouchpadButtonPress();
            }
        } else {
            if (touchpadHardwareState.getButtonsDown() == 0) {
                onTouchpadButtonRelease();
            }
        }
        mLastTouchpadState = touchpadHardwareState;
    }

    private void onTouchpadButtonPress() {
        Slog.d("TouchpadDebugView", "You clicked me!");
        getChildAt(0).setBackgroundColor(Color.BLUE);
    }

    private void onTouchpadButtonRelease() {
        Slog.d("TouchpadDebugView", "You released the click");
        getChildAt(0).setBackgroundColor(Color.RED);
    }
}
