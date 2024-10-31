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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Slog;
import android.view.View;

import com.android.server.input.TouchpadFingerState;
import com.android.server.input.TouchpadHardwareProperties;
import com.android.server.input.TouchpadHardwareState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class TouchpadVisualizationView extends View {
    private static final String TAG = "TouchpadVizMain";
    private static final boolean DEBUG = true;
    private static final float DEFAULT_RES_X = 47f;
    private static final float DEFAULT_RES_Y = 45f;
    private static final float MAX_TRACE_HISTORY_DURATION_SECONDS = 1f;

    private final TouchpadHardwareProperties mTouchpadHardwareProperties;
    private float mScaleFactor;

    private final ArrayDeque<TouchpadHardwareState> mHardwareStateHistory =
            new ArrayDeque<TouchpadHardwareState>();
    private final Map<Integer, TouchpadFingerState> mTempFingerStatesByTrackingId = new HashMap<>();

    private final Paint mOvalStrokePaint;
    private final Paint mOvalFillPaint;
    private final Paint mTracePaint;
    private final Paint mCenterPointPaint;
    private final Paint mPressureTextPaint;
    private final RectF mTempOvalRect = new RectF();

    public TouchpadVisualizationView(Context context,
            TouchpadHardwareProperties touchpadHardwareProperties) {
        super(context);
        mTouchpadHardwareProperties = touchpadHardwareProperties;
        mScaleFactor = 1;
        mOvalStrokePaint = new Paint();
        mOvalStrokePaint.setAntiAlias(true);
        mOvalStrokePaint.setStyle(Paint.Style.STROKE);
        mOvalFillPaint = new Paint();
        mOvalFillPaint.setAntiAlias(true);
        mTracePaint = new Paint();
        mTracePaint.setAntiAlias(false);
        mTracePaint.setARGB(255, 0, 0, 255);
        mTracePaint.setStyle(Paint.Style.STROKE);
        mTracePaint.setStrokeWidth(2);
        mCenterPointPaint = new Paint();
        mCenterPointPaint.setAntiAlias(true);
        mCenterPointPaint.setARGB(255, 255, 0, 0);
        mCenterPointPaint.setStrokeWidth(2);
        mPressureTextPaint = new Paint();
        mPressureTextPaint.setAntiAlias(true);
    }

    private void removeOldPoints() {
        float latestTimestamp = mHardwareStateHistory.getLast().getTimestamp();

        while (!mHardwareStateHistory.isEmpty()) {
            TouchpadHardwareState oldestPoint = mHardwareStateHistory.getFirst();
            float onScreenTime = latestTimestamp - oldestPoint.getTimestamp();
            if (onScreenTime >= MAX_TRACE_HISTORY_DURATION_SECONDS) {
                mHardwareStateHistory.removeFirst();
            } else {
                break;
            }
        }
    }

    private void drawOval(Canvas canvas, float x, float y, float major, float minor, float angle) {
        canvas.save(Canvas.MATRIX_SAVE_FLAG);
        canvas.rotate(angle, x, y);
        mTempOvalRect.left = x - minor / 2;
        mTempOvalRect.right = x + minor / 2;
        mTempOvalRect.top = y - major / 2;
        mTempOvalRect.bottom = y + major / 2;
        canvas.drawOval(mTempOvalRect, mOvalStrokePaint);
        canvas.drawOval(mTempOvalRect, mOvalFillPaint);
        canvas.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mHardwareStateHistory.isEmpty()) {
            return;
        }

        TouchpadHardwareState latestHardwareState = mHardwareStateHistory.getLast();

        float maximumPressure = 0;
        for (TouchpadFingerState touchpadFingerState : latestHardwareState.getFingerStates()) {
            maximumPressure = Math.max(maximumPressure, touchpadFingerState.getPressure());
        }

        // Visualizing fingers as ovals
        for (TouchpadFingerState touchpadFingerState : latestHardwareState.getFingerStates()) {
            float newX = translateX(touchpadFingerState.getPositionX());

            float newY = translateY(touchpadFingerState.getPositionY());

            float newAngle = translateRange(0, mTouchpadHardwareProperties.getOrientationMaximum(),
                    0, 90, touchpadFingerState.getOrientation());

            float resX = mTouchpadHardwareProperties.getResX() == 0f ? DEFAULT_RES_X
                    : mTouchpadHardwareProperties.getResX();
            float resY = mTouchpadHardwareProperties.getResY() == 0f ? DEFAULT_RES_Y
                    : mTouchpadHardwareProperties.getResY();

            float newTouchMajor = touchpadFingerState.getTouchMajor() * mScaleFactor / resY;
            float newTouchMinor = touchpadFingerState.getTouchMinor() * mScaleFactor / resX;

            float pressureToOpacity = translateRange(0, maximumPressure, 0, 255,
                    touchpadFingerState.getPressure());
            mOvalFillPaint.setAlpha((int) pressureToOpacity);

            drawOval(canvas, newX, newY, newTouchMajor, newTouchMinor, newAngle);

            String formattedPressure = String.format(Locale.getDefault(), "Ps: %.2f",
                    touchpadFingerState.getPressure());
            float textWidth = mPressureTextPaint.measureText(formattedPressure);

            canvas.drawText(formattedPressure, newX - textWidth / 2,
                    newY - newTouchMajor / 2, mPressureTextPaint);
        }

        mTempFingerStatesByTrackingId.clear();

        // Drawing the trace
        for (TouchpadHardwareState currentHardwareState : mHardwareStateHistory) {
            for (TouchpadFingerState currentFingerState : currentHardwareState.getFingerStates()) {
                TouchpadFingerState prevFingerState = mTempFingerStatesByTrackingId.put(
                        currentFingerState.getTrackingId(), currentFingerState);

                if (prevFingerState == null) {
                    continue;
                }

                float currentX = translateX(currentFingerState.getPositionX());
                float currentY = translateY(currentFingerState.getPositionY());
                float prevX = translateX(prevFingerState.getPositionX());
                float prevY = translateY(prevFingerState.getPositionY());

                canvas.drawLine(prevX, prevY, currentX, currentY, mTracePaint);
                canvas.drawPoint(currentX, currentY, mCenterPointPaint);
            }
        }
    }

    /**
     * Receiving the touchpad hardware state and based on it update the latest hardware state.
     *
     * @param schs The new hardware state received.
     */
    public void onTouchpadHardwareStateNotified(TouchpadHardwareState schs) {
        if (DEBUG) {
            logHardwareState(schs);
        }

        if (!mHardwareStateHistory.isEmpty()
                && mHardwareStateHistory.getLast().getFingerCount() == 0
                && schs.getFingerCount() > 0) {
            mHardwareStateHistory.clear();
        }

        mHardwareStateHistory.addLast(schs);
        removeOldPoints();

        if (DEBUG) {
            logFingerTrace();
        }

        invalidate();
    }

    /**
     * Update the scale factor of the drawings in the view.
     *
     * @param scaleFactor the new scale factor
     */
    public void updateScaleFactor(float scaleFactor) {
        mScaleFactor = scaleFactor;
    }

    /**
     * Change the colors of the objects inside the view to light mode theme.
     */
    public void setLightModeTheme() {
        this.setBackgroundColor(Color.rgb(20, 20, 20));
        mPressureTextPaint.setARGB(255, 255, 255, 255);
        mOvalFillPaint.setARGB(255, 255, 255, 255);
        mOvalStrokePaint.setARGB(255, 255, 255, 255);
    }

    /**
     * Change the colors of the objects inside the view to night mode theme.
     */
    public void setNightModeTheme() {
        this.setBackgroundColor(Color.rgb(240, 240, 240));
        mPressureTextPaint.setARGB(255, 0, 0, 0);
        mOvalFillPaint.setARGB(255, 0, 0, 0);
        mOvalStrokePaint.setARGB(255, 0, 0, 0);
    }

    private float translateX(float x) {
        return translateRange(mTouchpadHardwareProperties.getLeft(),
                mTouchpadHardwareProperties.getRight(), 0, getWidth(), x);
    }

    private float translateY(float y) {
        return translateRange(mTouchpadHardwareProperties.getTop(),
                mTouchpadHardwareProperties.getBottom(), 0, getHeight(), y);
    }

    private float translateRange(float rangeBeforeMin, float rangeBeforeMax,
            float rangeAfterMin, float rangeAfterMax, float value) {
        return rangeAfterMin + (value - rangeBeforeMin) / (rangeBeforeMax - rangeBeforeMin) * (
                rangeAfterMax - rangeAfterMin);
    }

    private void logHardwareState(TouchpadHardwareState schs) {
        Slog.d(TAG, "notifyTouchpadHardwareState: Time: "
                + schs.getTimestamp() + ", No. Buttons: "
                + schs.getButtonsDown() + ", No. Fingers: "
                + schs.getFingerCount() + ", No. Touch: "
                + schs.getTouchCount());

        for (TouchpadFingerState finger : schs.getFingerStates()) {
            Slog.d(TAG, "Finger #" + finger.getTrackingId()
                    + ": touchMajor= " + finger.getTouchMajor()
                    + ", touchMinor= " + finger.getTouchMinor()
                    + ", widthMajor= " + finger.getWidthMajor()
                    + ", widthMinor= " + finger.getWidthMinor()
                    + ", pressure= " + finger.getPressure()
                    + ", orientation= " + finger.getOrientation()
                    + ", positionX= " + finger.getPositionX()
                    + ", positionY= " + finger.getPositionY());
        }
    }

    private void logFingerTrace() {
        Slog.d(TAG, "Trace size= " + mHardwareStateHistory.size());
        for (TouchpadFingerState tfs : mHardwareStateHistory.getLast().getFingerStates()) {
            Slog.d(TAG, "ID= " + tfs.getTrackingId());
        }
    }
}