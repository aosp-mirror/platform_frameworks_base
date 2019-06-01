/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist.ui;

import android.annotation.ColorInt;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.View;

import com.android.systemui.R;

import java.util.ArrayList;

/**
 * Shows lights at the bottom of the phone, marking the invocation progress.
 */
public class InvocationLightsView extends View {

    private static final String TAG = "InvocationLightsView";

    private static final int LIGHT_HEIGHT_DP = 3;
    // minimum light length as a fraction of the corner length
    private static final float MINIMUM_CORNER_RATIO = .6f;

    protected final ArrayList<EdgeLight> mAssistInvocationLights = new ArrayList<>();
    protected final PerimeterPathGuide mGuide;

    private final Paint mPaint = new Paint();
    // Path used to render lights. One instance is used to draw all lights and is cached to avoid
    // allocation on each frame.
    private final Path mPath = new Path();
    private final int mViewHeight;

    // Allocate variable for screen location lookup to avoid memory alloc onDraw()
    private int[] mScreenLocation = new int[2];

    public InvocationLightsView(Context context) {
        this(context, null);
    }

    public InvocationLightsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public InvocationLightsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public InvocationLightsView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        int strokeWidth = DisplayUtils.convertDpToPx(LIGHT_HEIGHT_DP, context);
        mPaint.setStrokeWidth(strokeWidth);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.MITER);
        mPaint.setAntiAlias(true);

        int cornerRadiusBottom = DisplayUtils.getCornerRadiusBottom(context);
        int cornerRadiusTop = DisplayUtils.getCornerRadiusTop(context);
        int displayWidth = DisplayUtils.getWidth(context);
        int displayHeight = DisplayUtils.getHeight(context);
        CircularCornerPathRenderer cornerPathRenderer = new CircularCornerPathRenderer(
                cornerRadiusBottom, cornerRadiusTop, displayWidth, displayHeight);
        mGuide = new PerimeterPathGuide(context, cornerPathRenderer,
                strokeWidth / 2, displayWidth, displayHeight);

        mViewHeight = Math.max(cornerRadiusBottom, cornerRadiusTop);

        @ColorInt int lightColor = getResources().getColor(R.color.default_invocation_lights_color);
        for (int i = 0; i < 4; i++) {
            mAssistInvocationLights.add(new EdgeLight(lightColor, 0, 0));
        }
    }

    /**
     * Updates positions of the invocation lights based on the progress (a float between 0 and 1).
     * The lights begin at the device corners and expand inward until they meet at the center.
     */
    public void onInvocationProgress(float progress) {
        if (progress == 0) {
            setVisibility(View.GONE);
        } else {
            float cornerLengthNormalized =
                    mGuide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM_LEFT);
            float arcLengthNormalized = cornerLengthNormalized * MINIMUM_CORNER_RATIO;
            float arcOffsetNormalized = (cornerLengthNormalized - arcLengthNormalized) / 2f;

            float minLightLength = arcLengthNormalized / 2;
            float maxLightLength = mGuide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM) / 4f;

            float lightLength = MathUtils.lerp(minLightLength, maxLightLength, progress);

            float leftStart = (-cornerLengthNormalized + arcOffsetNormalized) * (1 - progress);
            float rightStart = mGuide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM)
                    + (cornerLengthNormalized - arcOffsetNormalized) * (1 - progress);

            setLight(0, leftStart, lightLength);
            setLight(1, leftStart + lightLength, lightLength);
            setLight(2, rightStart - (lightLength * 2), lightLength);
            setLight(3, rightStart - lightLength, lightLength);
            setVisibility(View.VISIBLE);
        }
        invalidate();
    }

    /**
     * Hides and resets the invocation lights.
     */
    public void hide() {
        setVisibility(GONE);
        for (EdgeLight light : mAssistInvocationLights) {
            light.setLength(0);
        }
    }

    /**
     * Sets the invocation light colors, from left to right.
     */
    public void setColors(@ColorInt int color1, @ColorInt int color2,
            @ColorInt int color3, @ColorInt int color4) {
        mAssistInvocationLights.get(0).setColor(color1);
        mAssistInvocationLights.get(1).setColor(color2);
        mAssistInvocationLights.get(2).setColor(color3);
        mAssistInvocationLights.get(3).setColor(color4);
    }

    @Override
    protected void onFinishInflate() {
        getLayoutParams().height = mViewHeight;
        requestLayout();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        int rotation = getContext().getDisplay().getRotation();
        mGuide.setRotation(rotation);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // If the view doesn't take up the whole screen, offset the canvas by its translation
        // distance such that PerimeterPathGuide's paths are drawn properly based upon the actual
        // screen edges.
        getLocationOnScreen(mScreenLocation);
        canvas.translate(-mScreenLocation[0], -mScreenLocation[1]);

        // if the lights are different colors, the inner ones need to be drawn last and with a
        // square cap so that the join between lights is straight
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        renderLight(mAssistInvocationLights.get(0), canvas);
        renderLight(mAssistInvocationLights.get(3), canvas);

        mPaint.setStrokeCap(Paint.Cap.SQUARE);
        renderLight(mAssistInvocationLights.get(1), canvas);
        renderLight(mAssistInvocationLights.get(2), canvas);
    }

    protected void setLight(int index, float offset, float length) {
        if (index < 0 || index >= 4) {
            Log.w(TAG, "invalid invocation light index: " + index);
        }
        mAssistInvocationLights.get(index).setOffset(offset);
        mAssistInvocationLights.get(index).setLength(length);
    }

    private void renderLight(EdgeLight light, Canvas canvas) {
        mGuide.strokeSegment(mPath, light.getOffset(), light.getOffset() + light.getLength());
        mPaint.setColor(light.getColor());
        canvas.drawPath(mPath, mPaint);
    }

}
