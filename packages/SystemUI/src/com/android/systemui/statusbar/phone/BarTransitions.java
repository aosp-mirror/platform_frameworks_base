/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.navigation.NavigationController.NavbarOverlayResources;

public class BarTransitions {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_COLORS = false;

    public static final boolean HIGH_END = ActivityManager.isHighEndGfx();

    public static final int MODE_OPAQUE = 0;
    public static final int MODE_SEMI_TRANSPARENT = 1;
    public static final int MODE_TRANSLUCENT = 2;
    public static final int MODE_LIGHTS_OUT = 3;
    public static final int MODE_TRANSPARENT = 4;
    public static final int MODE_WARNING = 5;
    public static final int MODE_LIGHTS_OUT_TRANSPARENT = 6;

    public static final int LIGHTS_IN_DURATION = 250;
    public static final int LIGHTS_OUT_DURATION = 750;
    public static final int BACKGROUND_DURATION = 200;

    private final String mTag;
    private final View mView;
    private final BarBackgroundDrawable mBarBackground;

    private int mMode;
    private boolean mAlwaysOpaque = false;

    public BarTransitions(View view, int gradientResourceId) {
        mTag = "BarTransitions." + view.getClass().getSimpleName();
        mView = view;
        mBarBackground = new BarBackgroundDrawable(mView.getContext(), gradientResourceId);
        if (HIGH_END) {
            mView.setBackground(mBarBackground);
        }
    }

    public int getMode() {
        return mMode;
    }

    public void updateResources(NavbarOverlayResources resourceMap) {}

    /**
     * @param alwaysOpaque if {@code true}, the bar's background will always be opaque, regardless
     *         of what mode it is currently set to.
     */
    public void setAlwaysOpaque(boolean alwaysOpaque) {
        mAlwaysOpaque = alwaysOpaque;
    }

    public boolean isAlwaysOpaque() {
        // Low-end devices do not support translucent modes, fallback to opaque
        return !HIGH_END || mAlwaysOpaque;
    }

    public void transitionTo(int mode, boolean animate) {
        if (isAlwaysOpaque() && (mode == MODE_SEMI_TRANSPARENT || mode == MODE_TRANSLUCENT
                || mode == MODE_TRANSPARENT)) {
            mode = MODE_OPAQUE;
        }
        if (isAlwaysOpaque() && (mode == MODE_LIGHTS_OUT_TRANSPARENT)) {
            mode = MODE_LIGHTS_OUT;
        }
        if (mMode == mode) return;
        int oldMode = mMode;
        mMode = mode;
        if (DEBUG) Log.d(mTag, String.format("%s -> %s animate=%s",
                modeToString(oldMode), modeToString(mode),  animate));
        onTransition(oldMode, mMode, animate);
    }

    protected void onTransition(int oldMode, int newMode, boolean animate) {
        if (HIGH_END) {
            applyModeBackground(oldMode, newMode, animate);
        }
    }

    protected void applyModeBackground(int oldMode, int newMode, boolean animate) {
        if (DEBUG) Log.d(mTag, String.format("applyModeBackground oldMode=%s newMode=%s animate=%s",
                modeToString(oldMode), modeToString(newMode), animate));
        mBarBackground.applyModeBackground(oldMode, newMode, animate);
    }

    public static String modeToString(int mode) {
        if (mode == MODE_OPAQUE) return "MODE_OPAQUE";
        if (mode == MODE_SEMI_TRANSPARENT) return "MODE_SEMI_TRANSPARENT";
        if (mode == MODE_TRANSLUCENT) return "MODE_TRANSLUCENT";
        if (mode == MODE_LIGHTS_OUT) return "MODE_LIGHTS_OUT";
        if (mode == MODE_TRANSPARENT) return "MODE_TRANSPARENT";
        if (mode == MODE_WARNING) return "MODE_WARNING";
        if (mode == MODE_LIGHTS_OUT_TRANSPARENT) return "MODE_LIGHTS_OUT_TRANSPARENT";
        throw new IllegalArgumentException("Unknown mode " + mode);
    }

    public void finishAnimations() {
        mBarBackground.finishAnimation();
    }

    protected boolean isLightsOut(int mode) {
        return mode == MODE_LIGHTS_OUT || mode == MODE_LIGHTS_OUT_TRANSPARENT;
    }

    private static class BarBackgroundDrawable extends Drawable {
        private final int mOpaque;
        private final int mSemiTransparent;
        private final int mTransparent;
        private final int mWarning;
        private final Drawable mGradient;

        private int mMode = -1;
        private boolean mAnimating;
        private long mStartTime;
        private long mEndTime;

        private int mGradientAlpha;
        private int mColor;
        private PorterDuffColorFilter mTintFilter;
        private Paint mPaint = new Paint();

        private int mGradientAlphaStart;
        private int mColorStart;


        public BarBackgroundDrawable(Context context, int gradientResourceId) {
            final Resources res = context.getResources();
            if (DEBUG_COLORS) {
                mOpaque = 0xff0000ff;
                mSemiTransparent = 0x7f0000ff;
                mTransparent = 0x2f0000ff;
                mWarning = 0xffff0000;
            } else {
                mOpaque = context.getColor(R.color.system_bar_background_opaque);
                mSemiTransparent = context.getColor(
                        com.android.internal.R.color.system_bar_background_semi_transparent);
                mTransparent = context.getColor(R.color.system_bar_background_transparent);
                mWarning = context.getColor(com.android.internal.R.color.battery_saver_mode_color);
            }
            mGradient = context.getDrawable(gradientResourceId);
        }

        @Override
        public void setAlpha(int alpha) {
            // noop
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // noop
        }

        @Override
        public void setTint(int color) {
            if (mTintFilter == null) {
                mTintFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            } else {
                mTintFilter.setColor(color);
            }
            invalidateSelf();
        }

        @Override
        public void setTintMode(Mode tintMode) {
            if (mTintFilter == null) {
                mTintFilter = new PorterDuffColorFilter(0, tintMode);
            } else {
                mTintFilter.setMode(tintMode);
            }
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            mGradient.setBounds(bounds);
        }

        public void applyModeBackground(int oldMode, int newMode, boolean animate) {
            if (mMode == newMode) return;
            mMode = newMode;
            mAnimating = animate;
            if (animate) {
                long now = SystemClock.elapsedRealtime();
                mStartTime = now;
                mEndTime = now + BACKGROUND_DURATION;
                mGradientAlphaStart = mGradientAlpha;
                mColorStart = mColor;
            }
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        public void finishAnimation() {
            if (mAnimating) {
                mAnimating = false;
                invalidateSelf();
            }
        }

        @Override
        public void draw(Canvas canvas) {
            int targetGradientAlpha = 0, targetColor = 0;
            if (mMode == MODE_WARNING) {
                targetColor = mWarning;
            } else if (mMode == MODE_TRANSLUCENT) {
                targetColor = mSemiTransparent;
            } else if (mMode == MODE_SEMI_TRANSPARENT) {
                targetColor = mSemiTransparent;
            } else if (mMode == MODE_TRANSPARENT || mMode == MODE_LIGHTS_OUT_TRANSPARENT) {
                targetColor = mTransparent;
            } else {
                targetColor = mOpaque;
            }

            if (!mAnimating) {
                mColor = targetColor;
                mGradientAlpha = targetGradientAlpha;
            } else {
                final long now = SystemClock.elapsedRealtime();
                if (now >= mEndTime) {
                    mAnimating = false;
                    mColor = targetColor;
                    mGradientAlpha = targetGradientAlpha;
                } else {
                    final float t = (now - mStartTime) / (float)(mEndTime - mStartTime);
                    final float v = Math.max(0, Math.min(
                            Interpolators.LINEAR.getInterpolation(t), 1));
                    mGradientAlpha = (int)(v * targetGradientAlpha + mGradientAlphaStart * (1 - v));
                    mColor = Color.argb(
                          (int)(v * Color.alpha(targetColor) + Color.alpha(mColorStart) * (1 - v)),
                          (int)(v * Color.red(targetColor) + Color.red(mColorStart) * (1 - v)),
                          (int)(v * Color.green(targetColor) + Color.green(mColorStart) * (1 - v)),
                          (int)(v * Color.blue(targetColor) + Color.blue(mColorStart) * (1 - v)));
                }
            }
            if (mGradientAlpha > 0) {
                mGradient.setAlpha(mGradientAlpha);
                mGradient.draw(canvas);
            }
            if (Color.alpha(mColor) > 0) {
                mPaint.setColor(mColor);
                if (mTintFilter != null) {
                    mPaint.setColorFilter(mTintFilter);
                }
                canvas.drawPaint(mPaint);
            }
            if (mAnimating) {
                invalidateSelf();  // keep going
            }
        }
    }
}
