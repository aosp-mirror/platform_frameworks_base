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

import static com.android.systemui.biometrics.SidefpsController.SFPS_INDICATOR_HEIGHT;
import static com.android.systemui.biometrics.SidefpsController.SFPS_INDICATOR_WIDTH;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * A view containing a normal drawable view for sidefps events.
 */
public class SidefpsView extends FrameLayout {
    private static final String TAG = "SidefpsView";
    @NonNull private final RectF mSensorRect;
    @NonNull private final Paint mSensorRectPaint;
    @NonNull private final Paint mPointerText;
    private static final int POINTER_SIZE_PX = 50;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    public SidefpsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        super.setWillNotDraw(false);

        mPointerText = new Paint(0 /* flags */);
        mPointerText.setAntiAlias(true);
        mPointerText.setColor(Color.WHITE);
        mPointerText.setTextSize(POINTER_SIZE_PX);

        mSensorRect = new RectF();
        mSensorRectPaint = new Paint(0 /* flags */);
        mSensorRectPaint.setAntiAlias(true);
        mSensorRectPaint.setColor(Color.BLUE);
        mSensorRectPaint.setStyle(Paint.Style.FILL);
    }

    void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(mSensorRect, mSensorRectPaint);
        canvas.drawText(
                ">",
                SFPS_INDICATOR_WIDTH / 2 - 10, // TODO(b/188690214): retrieve from sensorProps
                SFPS_INDICATOR_HEIGHT / 2 + 30, //TODO(b/188690214): retrieve from sensorProps
                mPointerText
        );
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        mSensorRect.set(
                0,
                0,
                SFPS_INDICATOR_WIDTH, //TODO(b/188690214): retrieve from sensorProps
                SFPS_INDICATOR_HEIGHT); //TODO(b/188690214): retrieve from sensorProps
    }
}
