/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static com.android.systemui.biometrics.SidefpsController.SFPS_AFFORDANCE_WIDTH;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.AttributeSet;
import android.view.Surface;
import android.widget.FrameLayout;

/**
 * A view containing a normal drawable view for sidefps events.
 */
public class SidefpsView extends FrameLayout {
    private static final String TAG = "SidefpsView";
    private static final int POINTER_SIZE_PX = 50;
    private static final int ROUND_RADIUS = 15;

    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mSensorRectPaint;
    @NonNull private final Paint mPointerText;
    @NonNull private final Context mContext;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;
    @Surface.Rotation private int mOrientation;

    public SidefpsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        super.setWillNotDraw(false);
        mContext = context;
        mPointerText = new Paint(0 /* flags */);
        mPointerText.setAntiAlias(true);
        mPointerText.setColor(Color.WHITE);
        mPointerText.setTextSize(POINTER_SIZE_PX);

        mSensorRect = new RectF();
        mSensorRectPaint = new Paint(0 /* flags */);
        mSensorRectPaint.setAntiAlias(true);
        mSensorRectPaint.setColor(Color.BLUE); // TODO: Fix Color
        mSensorRectPaint.setStyle(Paint.Style.FILL);
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(mSensorRect, ROUND_RADIUS, ROUND_RADIUS, mSensorRectPaint);
        int x, y;
        if (mOrientation == Surface.ROTATION_90 || mOrientation == Surface.ROTATION_270) {
            x = mSensorProps.sensorRadius + 10;
            y = SFPS_AFFORDANCE_WIDTH / 2 + 15;
        } else {
            x = SFPS_AFFORDANCE_WIDTH / 2 - 10;
            y = mSensorProps.sensorRadius + 30;
        }
        canvas.drawText(
                ">",
                x,
                y,
                mPointerText
        );
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mOrientation = mContext.getDisplay().getRotation();
        if (mOrientation == Surface.ROTATION_90 || mOrientation == Surface.ROTATION_270) {
            right = mSensorProps.sensorRadius * 2;
            bottom = SFPS_AFFORDANCE_WIDTH;
        } else {
            right = SFPS_AFFORDANCE_WIDTH;
            bottom = mSensorProps.sensorRadius * 2;
        }

        mSensorRect.set(
                0,
                0,
                right,
                bottom);
    }
}
