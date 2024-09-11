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

    private final TouchpadHardwareProperties mTouchpadHardwareProperties;

    TouchpadHardwareState mLatestHardwareState = new TouchpadHardwareState(0, 0, 0, 0,
            new TouchpadFingerState[]{});

    private final Paint mOvalPaint;

    public TouchpadVisualizationView(Context context,
            TouchpadHardwareProperties touchpadHardwareProperties) {
        super(context);
        mTouchpadHardwareProperties = touchpadHardwareProperties;
        mOvalPaint = new Paint();
        mOvalPaint.setAntiAlias(true);
        mOvalPaint.setARGB(255, 0, 0, 0);
        mOvalPaint.setStyle(Paint.Style.STROKE);
    }

    private final RectF mOvalRect = new RectF();

    private void drawOval(Canvas canvas, float x, float y, float major, float minor, float angle,
            Paint paint) {
        canvas.save(Canvas.MATRIX_SAVE_FLAG);
        canvas.rotate(angle, x, y);
        mOvalRect.left = x - minor / 2;
        mOvalRect.right = x + minor / 2;
        mOvalRect.top = y - major / 2;
        mOvalRect.bottom = y + major / 2;
        canvas.drawOval(mOvalRect, paint);
        canvas.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (TouchpadFingerState touchpadFingerState : mLatestHardwareState.getFingerStates()) {
            float newX = translateRange(mTouchpadHardwareProperties.getLeft(),
                    mTouchpadHardwareProperties.getRight(), 0, getWidth(),
                    touchpadFingerState.getPositionX());

            float newY = translateRange(mTouchpadHardwareProperties.getTop(),
                    mTouchpadHardwareProperties.getBottom(), 0, getHeight(),
                    touchpadFingerState.getPositionY());

            float newAngle = -translateRange(mTouchpadHardwareProperties.getOrientationMinimum(),
                    mTouchpadHardwareProperties.getOrientationMaximum(), 0, 360,
                    touchpadFingerState.getOrientation());

            float newTouchMajor =
                    touchpadFingerState.getTouchMajor() / mTouchpadHardwareProperties.getResX();
            float newTouchMinor =
                    touchpadFingerState.getTouchMinor() / mTouchpadHardwareProperties.getResY();

            drawOval(canvas, newX, newY, newTouchMajor, newTouchMinor, newAngle, mOvalPaint);
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
