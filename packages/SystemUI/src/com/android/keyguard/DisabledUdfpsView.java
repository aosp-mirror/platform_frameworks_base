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

package com.android.keyguard;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.AttributeSet;
import android.view.Surface;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A view positioned in the area of the UDPFS sensor.
 */
public class DisabledUdfpsView extends ImageView {
    @NonNull private final RectF mSensorRect;
    @NonNull private final Context mContext;

    // Used to obtain the sensor location.
    @NonNull private FingerprintSensorPropertiesInternal mSensorProps;

    public DisabledUdfpsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mSensorRect = new RectF();
    }

    public void setSensorProperties(@NonNull FingerprintSensorPropertiesInternal properties) {
        mSensorProps = properties;
    }

    // The "h" and "w" are the display's height and width relative to its current rotation.
    private void updateSensorRect(int h, int w) {
        // mSensorProps coordinates assume portrait mode.
        mSensorRect.set(mSensorProps.sensorLocationX - mSensorProps.sensorRadius,
                mSensorProps.sensorLocationY - mSensorProps.sensorRadius,
                mSensorProps.sensorLocationX + mSensorProps.sensorRadius,
                mSensorProps.sensorLocationY + mSensorProps.sensorRadius);

        // Transform mSensorRect if the device is in landscape mode.
        switch (mContext.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                mSensorRect.set(mSensorRect.top, h - mSensorRect.right, mSensorRect.bottom,
                        h - mSensorRect.left);
                break;
            case Surface.ROTATION_270:
                mSensorRect.set(w - mSensorRect.bottom, mSensorRect.left, w - mSensorRect.top,
                        mSensorRect.right);
                break;
            default:
                // Do nothing to stay in portrait mode.
        }

        setX(mSensorRect.left);
        setY(mSensorRect.top);
        setLayoutParams(new FrameLayout.LayoutParams(
                (int) (mSensorRect.right - mSensorRect.left),
                (int) (mSensorRect.bottom - mSensorRect.top)));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Always re-compute the layout regardless of whether "changed" is true. It is usually false
        // when the device goes from landscape to seascape and vice versa, but mSensorRect and
        // its dependencies need to be recalculated to stay at the same physical location on the
        // screen.
        final int w = getLayoutParams().width;
        final int h = getLayoutParams().height;
        updateSensorRect(h, w);
    }
}
