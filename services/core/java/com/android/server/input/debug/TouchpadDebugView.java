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

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.ViewConfiguration;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.input.TouchpadFingerState;
import com.android.server.input.TouchpadHardwareProperties;
import com.android.server.input.TouchpadHardwareState;

import java.util.Objects;

public class TouchpadDebugView extends LinearLayout {
    private static final float MAX_SCREEN_WIDTH_PROPORTION = 0.4f;
    private static final float MAX_SCREEN_HEIGHT_PROPORTION = 0.4f;
    private static final float MIN_SCALE_FACTOR = 10f;
    private static final float TEXT_SIZE_SP = 16.0f;
    private static final float DEFAULT_RES_X = 47f;
    private static final float DEFAULT_RES_Y = 45f;
    private static final int TEXT_PADDING_DP = 12;
    private static final int ROUNDED_CORNER_RADIUS_DP = 24;
    private static final int BUTTON_PRESSED_BACKGROUND_COLOR = Color.rgb(118, 151, 99);
    private static final int BUTTON_RELEASED_BACKGROUND_COLOR = Color.rgb(84, 85, 169);

    /**
     * Input device ID for the touchpad that this debug view is displaying.
     */
    private final int mTouchpadId;
    private static final String TAG = "TouchpadDebugView";

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
    private int mLatestGestureType = 0;
    private TextView mGestureInfoView;
    private TextView mNameView;

    @NonNull
    private TouchpadHardwareState mLastTouchpadState =
            new TouchpadHardwareState(0, 0 /* buttonsDown */, 0, 0,
                    new TouchpadFingerState[0]);
    private TouchpadVisualizationView mTouchpadVisualizationView;
    private final TouchpadHardwareProperties mTouchpadHardwareProperties;

    public TouchpadDebugView(Context context, int touchpadId,
                             TouchpadHardwareProperties touchpadHardwareProperties) {
        super(context);
        mTouchpadId = touchpadId;
        mWindowManager =
                Objects.requireNonNull(getContext().getSystemService(WindowManager.class));
        mTouchpadHardwareProperties = touchpadHardwareProperties;
        init(context, touchpadId);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

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
        updateScreenDimensions();
        setOrientation(VERTICAL);
        setLayoutParams(new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));
        setBackgroundColor(Color.TRANSPARENT);

        mNameView = new TextView(context);
        mNameView.setBackgroundColor(BUTTON_RELEASED_BACKGROUND_COLOR);
        mNameView.setTextSize(TEXT_SIZE_SP);
        mNameView.setText(Objects.requireNonNull(Objects.requireNonNull(
                        mContext.getSystemService(InputManager.class))
                .getInputDevice(touchpadId)).getName());
        mNameView.setGravity(Gravity.CENTER);
        mNameView.setTextColor(Color.WHITE);
        int paddingInDP = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, TEXT_PADDING_DP,
                getResources().getDisplayMetrics());
        mNameView.setPadding(paddingInDP, paddingInDP, paddingInDP, paddingInDP);
        mNameView.setLayoutParams(
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mTouchpadVisualizationView = new TouchpadVisualizationView(context,
                mTouchpadHardwareProperties);

        mGestureInfoView = new TextView(context);
        mGestureInfoView.setTextSize(TEXT_SIZE_SP);
        mGestureInfoView.setText("Latest Gesture: ");
        mGestureInfoView.setGravity(Gravity.CENTER);
        mGestureInfoView.setPadding(paddingInDP, paddingInDP, paddingInDP, paddingInDP);
        mGestureInfoView.setLayoutParams(
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        updateTheme(getResources().getConfiguration().uiMode);

        addView(mNameView);
        addView(mTouchpadVisualizationView);
        addView(mGestureInfoView);

        updateViewsDimensions();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(() -> {
            final ViewRootImpl viewRootImpl = getRootView().getViewRootImpl();
            if (viewRootImpl == null) {
                Slog.d("TouchpadDebugView", "ViewRootImpl is null.");
                return;
            }

            SurfaceControl surfaceControl = viewRootImpl.getSurfaceControl();
            if (surfaceControl != null && surfaceControl.isValid()) {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.setCornerRadius(surfaceControl,
                            TypedValue.applyDimension(COMPLEX_UNIT_DIP,
                                    ROUNDED_CORNER_RADIUS_DP,
                                    getResources().getDisplayMetrics())).apply();
                }
            } else {
                Slog.d("TouchpadDebugView", "SurfaceControl is invalid or has been released.");
            }
        }, 100);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
            return false;
        }

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
        Slog.d(TAG, "You tapped the window!");
        return true;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        updateTheme(newConfig.uiMode);
        updateScreenDimensions();
        updateViewsDimensions();

        // Adjust view position to stay within screen bounds after rotation
        mWindowLayoutParams.x =
                Math.max(0, Math.min(mWindowLayoutParams.x, mScreenWidth - getWidth()));
        mWindowLayoutParams.y =
                Math.max(0, Math.min(mWindowLayoutParams.y, mScreenHeight - getHeight()));
        mWindowManager.updateViewLayout(this, mWindowLayoutParams);
    }

    private void updateTheme(int uiMode) {
        int currentNightMode = uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
            setNightModeTheme();
        } else {
            setLightModeTheme();
        }
    }

    private void setLightModeTheme() {
        mTouchpadVisualizationView.setLightModeTheme();
        mGestureInfoView.setBackgroundColor(Color.WHITE);
        mGestureInfoView.setTextColor(Color.BLACK);
    }

    private void setNightModeTheme() {
        mTouchpadVisualizationView.setNightModeTheme();
        mGestureInfoView.setBackgroundColor(Color.BLACK);
        mGestureInfoView.setTextColor(Color.WHITE);
    }

    private boolean isSlopExceeded(float deltaX, float deltaY) {
        return deltaX * deltaX + deltaY * deltaY >= mTouchSlop * mTouchSlop;
    }

    private void updateViewsDimensions() {
        float resX = mTouchpadHardwareProperties.getResX() == 0f ? DEFAULT_RES_X
                : mTouchpadHardwareProperties.getResX();
        float resY = mTouchpadHardwareProperties.getResY() == 0f ? DEFAULT_RES_Y
                : mTouchpadHardwareProperties.getResY();

        float touchpadHeightMm = Math.abs(
                mTouchpadHardwareProperties.getBottom() - mTouchpadHardwareProperties.getTop())
                / resY;
        float touchpadWidthMm = Math.abs(
                mTouchpadHardwareProperties.getLeft() - mTouchpadHardwareProperties.getRight())
                / resX;

        float maxViewWidthPx = mScreenWidth * MAX_SCREEN_WIDTH_PROPORTION;
        float maxViewHeightPx = mScreenHeight * MAX_SCREEN_HEIGHT_PROPORTION;

        float minScaleFactorPx = TypedValue.applyDimension(COMPLEX_UNIT_DIP, MIN_SCALE_FACTOR,
                getResources().getDisplayMetrics());

        float scaleFactorBasedOnWidth =
                touchpadWidthMm * minScaleFactorPx > maxViewWidthPx ? maxViewWidthPx
                        / touchpadWidthMm : minScaleFactorPx;
        float scaleFactorBasedOnHeight =
                touchpadHeightMm * minScaleFactorPx > maxViewHeightPx ? maxViewHeightPx
                        / touchpadHeightMm : minScaleFactorPx;
        float scaleFactorUsed = Math.min(scaleFactorBasedOnHeight, scaleFactorBasedOnWidth);

        mTouchpadVisualizationView.setLayoutParams(
                new LayoutParams((int) (touchpadWidthMm * scaleFactorUsed),
                        (int) (touchpadHeightMm * scaleFactorUsed)));

        mTouchpadVisualizationView.updateScaleFactor(scaleFactorUsed);
        mTouchpadVisualizationView.invalidate();
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

    @VisibleForTesting
    TextView getGestureInfoView() {
        return mGestureInfoView;
    }

    /**
     * Notify the view of a change in TouchpadHardwareState and changing the
     * color of the view based on the status of the button click.
     */
    public void updateHardwareState(TouchpadHardwareState touchpadHardwareState, int deviceId) {
        if (deviceId != mTouchpadId) {
            return;
        }

        mTouchpadVisualizationView.onTouchpadHardwareStateNotified(touchpadHardwareState);
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
        Slog.d(TAG, "You clicked me!");
        mNameView.setBackgroundColor(BUTTON_PRESSED_BACKGROUND_COLOR);
    }

    private void onTouchpadButtonRelease() {
        Slog.d(TAG, "You released the click");
        mNameView.setBackgroundColor(BUTTON_RELEASED_BACKGROUND_COLOR);
    }

    /**
     * Notify the view of any new gesture on the touchpad and displaying its name
     */
    public void updateGestureInfo(int newGestureType, int deviceId) {
        if (deviceId == mTouchpadId && mLatestGestureType != newGestureType) {
            mGestureInfoView.setText(getGestureText(newGestureType));
            mLatestGestureType = newGestureType;
        }
    }

    @NonNull
    static String getGestureText(int gestureType) {
        // These values are a representation of the GestureType enum in the
        // external/libchrome-gestures/include/gestures.h library in the C++ code
        String mGestureName = switch (gestureType) {
            case 1 -> "Move, 1 Finger";
            case 2 -> "Scroll, 2 Fingers";
            case 3 -> "Buttons Change, 1 Fingers";
            case 4 -> "Fling";
            case 5 -> "Swipe, 3 Fingers";
            case 6 -> "Pinch, 2 Fingers";
            case 7 -> "Swipe Lift, 3 Fingers";
            case 8 -> "Metrics";
            case 9 -> "Four Finger Swipe, 4 Fingers";
            case 10 -> "Four Finger Swipe Lift, 4 Fingers";
            case 11 -> "Mouse Wheel";
            default -> "Unknown Gesture";
        };
        return "Latest Gesture: " + mGestureName;
    }
}
