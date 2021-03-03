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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.statusbar.phone.StatusBar;

/**
 * Base class for views containing UDFPS animations. Note that this is a FrameLayout so that we
 * can support multiple child views drawing on the same region around the sensor location.
 */
public abstract class UdfpsAnimationView extends FrameLayout implements DozeReceiver,
        StatusBar.ExpansionChangedListener {

    private static final String TAG = "UdfpsAnimationView";

    @Nullable protected abstract UdfpsAnimation getUdfpsAnimation();

    @NonNull private UdfpsView mParent;
    @NonNull private RectF mSensorRect;
    private int mAlpha;

    public UdfpsAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mSensorRect = new RectF();
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (getUdfpsAnimation() != null) {
            final int alpha = mParent.shouldPauseAuth() ? mAlpha : 255;
            getUdfpsAnimation().setAlpha(alpha);
            getUdfpsAnimation().draw(canvas);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getUdfpsAnimation().onDestroy();
    }

    private int expansionToAlpha(float expansion) {
        // Fade to 0 opacity when reaching this expansion amount
        final float maxExpansion = 0.4f;

        if (expansion >= maxExpansion) {
            return 0; // transparent
        }

        final float percent = expansion / maxExpansion;
        return (int) ((1 - percent) * 255);
    }

    void onIlluminationStarting() {
        getUdfpsAnimation().setIlluminationShowing(true);
        postInvalidate();
    }

    void onIlluminationStopped() {
        getUdfpsAnimation().setIlluminationShowing(false);
        postInvalidate();
    }

    void setParent(@NonNull UdfpsView parent) {
        mParent = parent;
    }

    void onSensorRectUpdated(@NonNull RectF sensorRect) {
        mSensorRect = sensorRect;
        if (getUdfpsAnimation() != null) {
            getUdfpsAnimation().onSensorRectUpdated(mSensorRect);
        }
    }

    void updateColor() {
        if (getUdfpsAnimation() != null) {
            getUdfpsAnimation().updateColor();
        }
        postInvalidate();
    }

    @Override
    public void dozeTimeTick() {
        if (getUdfpsAnimation() instanceof DozeReceiver) {
            ((DozeReceiver) getUdfpsAnimation()).dozeTimeTick();
        }
    }

    @Override
    public void onExpansionChanged(float expansion, boolean expanded) {
        mAlpha = expansionToAlpha(expansion);
        postInvalidate();
    }

    public int getPaddingX() {
        if (getUdfpsAnimation() == null) {
            return 0;
        }
        return getUdfpsAnimation().getPaddingX();
    }

    public int getPaddingY() {
        if (getUdfpsAnimation() == null) {
            return 0;
        }
        return getUdfpsAnimation().getPaddingY();
    }
}
