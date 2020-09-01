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

import android.animation.ArgbEvaluator;
import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.phone.NavigationBarFragment;
import com.android.systemui.statusbar.phone.NavigationBarTransitions;

import java.util.ArrayList;

/**
 * Shows lights at the bottom of the phone, marking the invocation progress.
 */
public class InvocationLightsView extends View
        implements NavigationBarTransitions.DarkIntensityListener {

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
    private final int mStrokeWidth;
    @ColorInt
    private final int mLightColor;
    @ColorInt
    private final int mDarkColor;

    // Allocate variable for screen location lookup to avoid memory alloc onDraw()
    private int[] mScreenLocation = new int[2];
    private boolean mRegistered = false;
    private boolean mUseNavBarColor = true;

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

        mStrokeWidth = DisplayUtils.convertDpToPx(LIGHT_HEIGHT_DP, context);
        mPaint.setStrokeWidth(mStrokeWidth);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.MITER);
        mPaint.setAntiAlias(true);


        int displayWidth = DisplayUtils.getWidth(context);
        int displayHeight = DisplayUtils.getHeight(context);
        mGuide = new PerimeterPathGuide(context, createCornerPathRenderer(context),
                mStrokeWidth / 2, displayWidth, displayHeight);

        int cornerRadiusBottom = DisplayUtils.getCornerRadiusBottom(context);
        int cornerRadiusTop = DisplayUtils.getCornerRadiusTop(context);
        // ensure that height is non-zero even for square corners
        mViewHeight = Math.max(Math.max(cornerRadiusBottom, cornerRadiusTop),
            DisplayUtils.convertDpToPx(LIGHT_HEIGHT_DP, context));

        final int dualToneDarkTheme = Utils.getThemeAttr(mContext, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(mContext, R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(mContext, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(mContext, dualToneDarkTheme);
        mLightColor = Utils.getColorAttrDefaultColor(lightContext, R.attr.singleToneColor);
        mDarkColor = Utils.getColorAttrDefaultColor(darkContext, R.attr.singleToneColor);

        for (int i = 0; i < 4; i++) {
            mAssistInvocationLights.add(new EdgeLight(Color.TRANSPARENT, 0, 0));
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
            attemptRegisterNavBarListener();

            float cornerLengthNormalized =
                    mGuide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM_LEFT);
            float arcLengthNormalized = cornerLengthNormalized * MINIMUM_CORNER_RATIO;
            float arcOffsetNormalized = (cornerLengthNormalized - arcLengthNormalized) / 2f;

            float minLightLength = 0;
            float maxLightLength = mGuide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM) / 4f;

            float lightLength = MathUtils.lerp(minLightLength, maxLightLength, progress);

            float leftStart = (-cornerLengthNormalized + arcOffsetNormalized) * (1 - progress);
            float rightStart = mGuide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM)
                    + (cornerLengthNormalized - arcOffsetNormalized) * (1 - progress);

            setLight(0, leftStart, leftStart + lightLength);
            setLight(1, leftStart + lightLength, leftStart + lightLength * 2);
            setLight(2, rightStart - (lightLength * 2), rightStart - lightLength);
            setLight(3, rightStart - lightLength, rightStart);
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
            light.setEndpoints(0, 0);
        }
        attemptUnregisterNavBarListener();
    }

    /**
     * Sets all invocation lights to a single color. If color is null, uses the navigation bar
     * color (updated when the nav bar color changes).
     */
    public void setColors(@Nullable @ColorInt Integer color) {
        if (color == null) {
            mUseNavBarColor = true;
            mPaint.setStrokeCap(Paint.Cap.BUTT);
            attemptRegisterNavBarListener();
        } else {
            setColors(color, color, color, color);
        }
    }

    /**
     * Sets the invocation light colors, from left to right.
     */
    public void setColors(@ColorInt int color1, @ColorInt int color2,
            @ColorInt int color3, @ColorInt int color4) {
        mUseNavBarColor = false;
        attemptUnregisterNavBarListener();
        mAssistInvocationLights.get(0).setColor(color1);
        mAssistInvocationLights.get(1).setColor(color2);
        mAssistInvocationLights.get(2).setColor(color3);
        mAssistInvocationLights.get(3).setColor(color4);
    }

    /**
     * Reacts to changes in the navigation bar color
     *
     * @param darkIntensity 0 is the lightest color, 1 is the darkest.
     */
    @Override // NavigationBarTransitions.DarkIntensityListener
    public void onDarkIntensity(float darkIntensity) {
        updateDarkness(darkIntensity);
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

        if (mUseNavBarColor) {
            for (EdgeLight light : mAssistInvocationLights) {
                renderLight(light, canvas);
            }
        } else {
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            renderLight(mAssistInvocationLights.get(0), canvas);
            renderLight(mAssistInvocationLights.get(3), canvas);

            mPaint.setStrokeCap(Paint.Cap.BUTT);
            renderLight(mAssistInvocationLights.get(1), canvas);
            renderLight(mAssistInvocationLights.get(2), canvas);
        }
    }

    protected void setLight(int index, float start, float end) {
        if (index < 0 || index >= 4) {
            Log.w(TAG, "invalid invocation light index: " + index);
        }
        mAssistInvocationLights.get(index).setEndpoints(start, end);
    }

    /**
     * Returns CornerPathRenderer to be used for rendering invocation lights.
     *
     * To render corners that aren't circular, override this method in a subclass.
     */
    protected CornerPathRenderer createCornerPathRenderer(Context context) {
        return new CircularCornerPathRenderer(context);
    }

    /**
     * Receives an intensity from 0 (lightest) to 1 (darkest) and sets the handle color
     * appropriately. Intention is to match the home handle color.
     */
    protected void updateDarkness(float darkIntensity) {
        if (mUseNavBarColor) {
            @ColorInt int invocationColor = (int) ArgbEvaluator.getInstance().evaluate(
                    darkIntensity, mLightColor, mDarkColor);
            boolean changed = true;
            for (EdgeLight light : mAssistInvocationLights) {
                changed &= light.setColor(invocationColor);
            }
            if (changed) {
                invalidate();
            }
        }
    }

    private void renderLight(EdgeLight light, Canvas canvas) {
        if (light.getLength() > 0) {
            mGuide.strokeSegment(mPath, light.getStart(), light.getStart() + light.getLength());
            mPaint.setColor(light.getColor());
            canvas.drawPath(mPath, mPaint);
        }
    }

    private void attemptRegisterNavBarListener() {
        if (!mRegistered) {
            NavigationBarController controller = Dependency.get(NavigationBarController.class);
            if (controller == null) {
                return;
            }

            NavigationBarFragment navBar = controller.getDefaultNavigationBarFragment();
            if (navBar == null) {
                return;
            }

            updateDarkness(navBar.getBarTransitions().addDarkIntensityListener(this));
            mRegistered = true;
        }
    }

    private void attemptUnregisterNavBarListener() {
        if (mRegistered) {
            NavigationBarController controller = Dependency.get(NavigationBarController.class);
            if (controller == null) {
                return;
            }

            NavigationBarFragment navBar = controller.getDefaultNavigationBarFragment();
            if (navBar == null) {
                return;
            }

            navBar.getBarTransitions().removeDarkIntensityListener(this);
            mRegistered = false;
        }
    }
}
