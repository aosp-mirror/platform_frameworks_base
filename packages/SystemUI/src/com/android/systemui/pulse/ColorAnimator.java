/**
 * Copyright (C) 2016-2022 crDroid Android Project
 * 
 * @author: Randall Rushing <randall.rushing@gmail.com>
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
 * 
 * Produce a smooth HSV color wheel type animation much like a LavaLamp
 * 
 */

package com.android.systemui.pulse;

import android.animation.ValueAnimator;
import android.graphics.Color;

public class ColorAnimator implements ValueAnimator.AnimatorUpdateListener {
    public interface ColorAnimationListener {
        public default void onColorChanged(ColorAnimator colorAnimator, int color) {}
        public default void onStartAnimation(ColorAnimator colorAnimator, int firstColor) {}
        public default void onStopAnimation(ColorAnimator colorAnimator, int lastColor) {}
    }

    public static final int ANIM_DEF_DURATION = 10 * 1000;
    public static final String RED = "#ffff8080";
    public static final String BLUE = "#ff8080ff";

    protected final float[] from = new float[3], to = new float[3], hsv = new float[3];

    protected ValueAnimator mColorAnim;
    protected long mAnimTime = ANIM_DEF_DURATION;
    protected int mFromColor = Color.parseColor(RED);
    protected int mToColor = Color.parseColor(BLUE);
    protected int mLastColor = Color.parseColor(RED);
    protected boolean mIsRunning;

    protected  ColorAnimationListener mListener;

    public ColorAnimator() {
        this(ValueAnimator.ofFloat(0, 1));
    }

    public ColorAnimator(ValueAnimator valueAnimator) {
        this(valueAnimator, ANIM_DEF_DURATION);
    }

    public ColorAnimator(ValueAnimator valueAnimator, long animDurationMillis) {
        this(valueAnimator, animDurationMillis, Color.parseColor(RED), Color.parseColor(BLUE));
    }

    public ColorAnimator(ValueAnimator valueAnimator, long animDurationMillis, int fromColor,
            int toColor) {
        mAnimTime = animDurationMillis;
        mFromColor = fromColor;
        mToColor = toColor;
        mColorAnim = valueAnimator;
        mColorAnim.addUpdateListener(this);
    }

    public void start() {
        stop();
        Color.colorToHSV(mFromColor, from);
        Color.colorToHSV(mToColor, to);
        mColorAnim.setDuration(mAnimTime);
        mColorAnim.setRepeatMode(ValueAnimator.REVERSE);
        mColorAnim.setRepeatCount(ValueAnimator.INFINITE);
        if (mListener != null) {
            mListener.onStartAnimation(this, mFromColor);
        }
        mColorAnim.start();
        mIsRunning = true;
    }

    public void stop() {
        if (mColorAnim.isStarted()) {
            mColorAnim.end();
            mIsRunning = false;
            if (mListener != null) {
                mListener.onStopAnimation(this, mLastColor);
            }
        }
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    public void setAnimationTime(long millis) {
        if (mAnimTime != millis) {
            mAnimTime = millis;
            if (mColorAnim.isRunning()) {
                start();
            }
        }
    }

    public void setColorAnimatorListener(ColorAnimationListener listener) {
        mListener = listener;
    }

    public void removeColorAnimatorListener(ColorAnimationListener listener) {
        mListener = null;
    }

    public void onAnimationUpdate(ValueAnimator animation) {
        // Transition along each axis of HSV (hue, saturation, value)
        hsv[0] = from[0] + (to[0] - from[0]) * animation.getAnimatedFraction();
        hsv[1] = from[1] + (to[1] - from[1]) * animation.getAnimatedFraction();
        hsv[2] = from[2] + (to[2] - from[2]) * animation.getAnimatedFraction();

        mLastColor = Color.HSVToColor(hsv);

        if (mListener != null) {
            mListener.onColorChanged(this, mLastColor);
        }
    }

    public int getCurrentColor() {
        return mLastColor;
    }
}
