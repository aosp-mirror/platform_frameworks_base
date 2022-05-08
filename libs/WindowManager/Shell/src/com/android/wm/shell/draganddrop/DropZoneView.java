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

package com.android.wm.shell.draganddrop;

import static com.android.wm.shell.animation.Interpolators.FAST_OUT_SLOW_IN;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.IntProperty;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.wm.shell.R;

/**
 * Renders a drop zone area for items being dragged.
 */
public class DropZoneView extends FrameLayout {

    private static final int SPLASHSCREEN_ALPHA_INT = (int) (255 * 0.90f);
    private static final int HIGHLIGHT_ALPHA_INT = 255;
    private static final int MARGIN_ANIMATION_ENTER_DURATION = 400;
    private static final int MARGIN_ANIMATION_EXIT_DURATION = 250;

    private static final FloatProperty<DropZoneView> INSETS =
            new FloatProperty<DropZoneView>("insets") {
                @Override
                public void setValue(DropZoneView v, float percent) {
                    v.setMarginPercent(percent);
                }

                @Override
                public Float get(DropZoneView v) {
                    return v.getMarginPercent();
                }
            };

    private static final IntProperty<ColorDrawable> SPLASHSCREEN_ALPHA =
            new IntProperty<ColorDrawable>("splashscreen") {
                @Override
                public void setValue(ColorDrawable d, int alpha) {
                    d.setAlpha(alpha);
                }

                @Override
                public Integer get(ColorDrawable d) {
                    return d.getAlpha();
                }
            };

    private static final IntProperty<ColorDrawable> HIGHLIGHT_ALPHA =
            new IntProperty<ColorDrawable>("highlight") {
                @Override
                public void setValue(ColorDrawable d, int alpha) {
                    d.setAlpha(alpha);
                }

                @Override
                public Integer get(ColorDrawable d) {
                    return d.getAlpha();
                }
            };

    private final Path mPath = new Path();
    private final float[] mContainerMargin = new float[4];
    private float mCornerRadius;
    private float mBottomInset;
    private int mMarginColor; // i.e. color used for negative space like the container insets
    private int mHighlightColor;

    private boolean mShowingHighlight;
    private boolean mShowingSplash;
    private boolean mShowingMargin;

    // TODO: might be more seamless to animate between splash/highlight color instead of 2 separate
    private ObjectAnimator mSplashAnimator;
    private ObjectAnimator mHighlightAnimator;
    private ObjectAnimator mMarginAnimator;
    private float mMarginPercent;

    // Renders a highlight or neutral transparent color
    private ColorDrawable mDropZoneDrawable;
    // Renders the translucent splashscreen with the app icon in the middle
    private ImageView mSplashScreenView;
    private ColorDrawable mSplashBackgroundDrawable;
    // Renders the margin / insets around the dropzone container
    private MarginView mMarginView;

    public DropZoneView(Context context) {
        this(context, null);
    }

    public DropZoneView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DropZoneView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DropZoneView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setContainerMargin(0, 0, 0, 0); // make sure it's populated

        mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context);
        mMarginColor = getResources().getColor(R.color.taskbar_background);
        mHighlightColor = getResources().getColor(android.R.color.system_accent1_500);

        mDropZoneDrawable = new ColorDrawable();
        mDropZoneDrawable.setColor(mHighlightColor);
        mDropZoneDrawable.setAlpha(0);
        setBackgroundDrawable(mDropZoneDrawable);

        mSplashScreenView = new ImageView(context);
        mSplashScreenView.setScaleType(ImageView.ScaleType.CENTER);
        mSplashBackgroundDrawable = new ColorDrawable();
        mSplashBackgroundDrawable.setColor(Color.WHITE);
        mSplashBackgroundDrawable.setAlpha(SPLASHSCREEN_ALPHA_INT);
        mSplashScreenView.setBackgroundDrawable(mSplashBackgroundDrawable);
        addView(mSplashScreenView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mSplashScreenView.setAlpha(0f);

        mMarginView = new MarginView(context);
        addView(mMarginView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void onThemeChange() {
        mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(getContext());
        mMarginColor = getResources().getColor(R.color.taskbar_background);
        mHighlightColor = getResources().getColor(android.R.color.system_accent1_500);

        final int alpha = mDropZoneDrawable.getAlpha();
        mDropZoneDrawable.setColor(mHighlightColor);
        mDropZoneDrawable.setAlpha(alpha);

        if (mMarginPercent > 0) {
            mMarginView.invalidate();
        }
    }

    /** Sets the desired margins around the drop zone container when fully showing. */
    public void setContainerMargin(float left, float top, float right, float bottom) {
        mContainerMargin[0] = left;
        mContainerMargin[1] = top;
        mContainerMargin[2] = right;
        mContainerMargin[3] = bottom;
        if (mMarginPercent > 0) {
            mMarginView.invalidate();
        }
    }

    /** Sets the bottom inset so the drop zones are above bottom navigation. */
    public void setBottomInset(float bottom) {
        mBottomInset = bottom;
        ((LayoutParams) mSplashScreenView.getLayoutParams()).bottomMargin = (int) bottom;
        if (mMarginPercent > 0) {
            mMarginView.invalidate();
        }
    }

    /** Sets the color and icon to use for the splashscreen when shown. */
    public void setAppInfo(int splashScreenColor, Drawable appIcon) {
        mSplashBackgroundDrawable.setColor(splashScreenColor);
        mSplashScreenView.setImageDrawable(appIcon);
    }

    /** @return an active animator for this view if one exists. */
    @Nullable
    public ObjectAnimator getAnimator() {
        if (mMarginAnimator != null && mMarginAnimator.isRunning()) {
            return mMarginAnimator;
        } else if (mHighlightAnimator != null && mHighlightAnimator.isRunning()) {
            return mHighlightAnimator;
        } else if (mSplashAnimator != null && mSplashAnimator.isRunning()) {
            return mSplashAnimator;
        }
        return null;
    }

    /** Animates the splashscreen to show or hide. */
    public void setShowingSplash(boolean showingSplash) {
        if (mShowingSplash != showingSplash) {
            mShowingSplash = showingSplash;
            animateSplashToState();
        }
    }

    /** Animates the highlight indicating the zone is hovered on or not. */
    public void setShowingHighlight(boolean showingHighlight) {
        if (mShowingHighlight != showingHighlight) {
            mShowingHighlight = showingHighlight;
            animateHighlightToState();
        }
    }

    /** Animates the margins around the drop zone to show or hide. */
    public void setShowingMargin(boolean visible) {
        if (mShowingMargin != visible) {
            mShowingMargin = visible;
            animateMarginToState();
        }
        if (!mShowingMargin) {
            setShowingHighlight(false);
            setShowingSplash(false);
        }
    }

    private void animateSplashToState() {
        if (mSplashAnimator != null) {
            mSplashAnimator.cancel();
        }
        mSplashAnimator = ObjectAnimator.ofInt(mSplashBackgroundDrawable,
                SPLASHSCREEN_ALPHA,
                mSplashBackgroundDrawable.getAlpha(),
                mShowingSplash ? SPLASHSCREEN_ALPHA_INT : 0);
        if (!mShowingSplash) {
            mSplashAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        }
        mSplashAnimator.start();
        mSplashScreenView.animate().alpha(mShowingSplash ? 1f : 0f).start();
    }

    private void animateHighlightToState() {
        if (mHighlightAnimator != null) {
            mHighlightAnimator.cancel();
        }
        mHighlightAnimator = ObjectAnimator.ofInt(mDropZoneDrawable,
                HIGHLIGHT_ALPHA,
                mDropZoneDrawable.getAlpha(),
                mShowingHighlight ? HIGHLIGHT_ALPHA_INT : 0);
        if (!mShowingHighlight) {
            mHighlightAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        }
        mHighlightAnimator.start();
    }

    private void animateMarginToState() {
        if (mMarginAnimator != null) {
            mMarginAnimator.cancel();
        }
        mMarginAnimator = ObjectAnimator.ofFloat(this, INSETS,
                mMarginPercent,
                mShowingMargin ? 1f : 0f);
        mMarginAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        mMarginAnimator.setDuration(mShowingMargin
                ? MARGIN_ANIMATION_ENTER_DURATION
                : MARGIN_ANIMATION_EXIT_DURATION);
        mMarginAnimator.start();
    }

    private void setMarginPercent(float percent) {
        if (percent != mMarginPercent) {
            mMarginPercent = percent;
            mMarginView.invalidate();
        }
    }

    private float getMarginPercent() {
        return mMarginPercent;
    }

    /** Simple view that draws a rounded rect margin around its contents. **/
    private class MarginView extends View {

        MarginView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            mPath.reset();
            mPath.addRoundRect(mContainerMargin[0] * mMarginPercent,
                    mContainerMargin[1] * mMarginPercent,
                    getWidth() - (mContainerMargin[2] * mMarginPercent),
                    getHeight() - (mContainerMargin[3] * mMarginPercent) - mBottomInset,
                    mCornerRadius * mMarginPercent,
                    mCornerRadius * mMarginPercent,
                    Path.Direction.CW);
            mPath.setFillType(Path.FillType.INVERSE_EVEN_ODD);
            canvas.clipPath(mPath);
            canvas.drawColor(mMarginColor);
        }
    }
}
