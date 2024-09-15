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
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Slog;
import android.view.View;

import com.android.server.input.TouchpadFingerState;
import com.android.server.input.TouchpadHardwareProperties;
import com.android.server.input.TouchpadHardwareState;

public class TouchpadVisualizationView extends View {
    private static final String TAG = "TouchpadVizMain";
    private static final boolean DEBUG = true;
    private static final float DEFAULT_RES_X = 47f;
    private static final float DEFAULT_RES_Y = 45f;

    private final TouchpadHardwareProperties mTouchpadHardwareProperties;
    private float mScaleFactor;

    TouchpadHardwareState mLatestHardwareState = new TouchpadHardwareState(0, 0, 0, 0,
            new TouchpadFingerState[]{});

    private final Paint mOvalStrokePaint;
    private final Paint mOvalFillPaint;
    private final RectF mTempOvalRect = new RectF();

    public TouchpadVisualizationView(Context context,
            TouchpadHardwareProperties touchpadHardwareProperties) {
        super(context);
        mTouchpadHardwareProperties = touchpadHardwareProperties;
        mScaleFactor = 1;
        mOvalStrokePaint = new Paint();
        mOvalStrokePaint.setAntiAlias(true);
        mOvalStrokePaint.setARGB(255, 0, 0, 0);
        mOvalStrokePaint.setStyle(Paint.Style.STROKE);
        mOvalFillPaint = new Paint();
        mOvalFillPaint.setAntiAlias(true);
        mOvalFillPaint.setARGB(255, 0, 0, 0);
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
        float maximumPressure = 0;
        for (TouchpadFingerState touchpadFingerState : mLatestHardwareState.getFingerStates()) {
            maximumPressure = Math.max(maximumPressure, touchpadFingerState.getPressure());
        }

        for (TouchpadFingerState touchpadFingerState : mLatestHardwareState.getFingerStates()) {
            float newX = translateRange(mTouchpadHardwareProperties.getLeft(),
                    mTouchpadHardwareProperties.getRight(), 0, getWidth(),
                    touchpadFingerState.getPositionX());

            float newY = translateRange(mTouchpadHardwareProperties.getTop(),
                    mTouchpadHardwareProperties.getBottom(), 0, getHeight(),
                    touchpadFingerState.getPositionY());

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

        mLatestHardwareState = schs;

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

}
