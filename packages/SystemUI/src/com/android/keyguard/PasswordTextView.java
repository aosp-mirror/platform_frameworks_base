/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.systemui.Flags.notifyPasswordTextViewUserActivityInBackground;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;

import java.util.ArrayList;

/**
 * A View similar to a textView which contains password text and can animate when the text is
 * changed
 */
public class PasswordTextView extends BasePasswordTextView {
    public static final long APPEAR_DURATION = 160;
    public static final long DISAPPEAR_DURATION = 160;
    private static final float DOT_OVERSHOOT_FACTOR = 1.5f;
    private static final long DOT_APPEAR_DURATION_OVERSHOOT = 320;
    private static final long RESET_DELAY_PER_ELEMENT = 40;
    private static final long RESET_MAX_DELAY = 200;

    /**
     * The overlap between the text disappearing and the dot appearing animation
     */
    private static final long DOT_APPEAR_TEXT_DISAPPEAR_OVERLAP_DURATION = 130;

    /**
     * The duration the text needs to stay there at least before it can morph into a dot
     */
    private static final long TEXT_REST_DURATION_AFTER_APPEAR = 100;

    /**
     * The duration the text should be visible, starting with the appear animation
     */
    private static final long TEXT_VISIBILITY_DURATION = 1300;

    /**
     * The position in time from [0,1] where the overshoot should be finished and the settle back
     * animation of the dot should start
     */
    private static final float OVERSHOOT_TIME_POSITION = 0.5f;

    /**
     * The raw text size, will be multiplied by the scaled density when drawn
     */
    private int mTextHeightRaw;
    private final int mGravity;
    private ArrayList<CharState> mTextChars = new ArrayList<>();
    private int mDotSize;
    private PowerManager mPM;
    private int mCharPadding;
    private final Paint mDrawPaint = new Paint();
    private int mDrawColor;
    private Interpolator mAppearInterpolator;
    private Interpolator mDisappearInterpolator;
    private Interpolator mFastOutSlowInInterpolator;

    public PasswordTextView(Context context) {
        this(context, null);
    }

    public PasswordTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PasswordTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public PasswordTextView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        TypedArray a = context.obtainStyledAttributes(attrs, android.R.styleable.View);
        try {
            // If defined, use the provided values. If not, set them to true by default.
            boolean isFocusable = a.getBoolean(android.R.styleable.View_focusable,
                    /* defValue= */ true);
            boolean isFocusableInTouchMode = a.getBoolean(
                    android.R.styleable.View_focusableInTouchMode, /* defValue= */ true);
            setFocusable(isFocusable);
            setFocusableInTouchMode(isFocusableInTouchMode);
        } finally {
            a.recycle();
        }
        a = context.obtainStyledAttributes(attrs, R.styleable.PasswordTextView);
        try {
            mTextHeightRaw = a.getInt(R.styleable.PasswordTextView_scaledTextSize, 0);
            mGravity = a.getInt(R.styleable.PasswordTextView_android_gravity, Gravity.CENTER);
            mDotSize = a.getDimensionPixelSize(R.styleable.PasswordTextView_dotSize,
                    getContext().getResources().getDimensionPixelSize(R.dimen.password_dot_size));
            mCharPadding = a.getDimensionPixelSize(R.styleable.PasswordTextView_charPadding,
                    getContext().getResources().getDimensionPixelSize(
                            R.dimen.password_char_padding));
            mDrawColor = a.getColor(R.styleable.PasswordTextView_android_textColor, Color.WHITE);
            mDrawPaint.setColor(mDrawColor);

        } finally {
            a.recycle();
        }

        mDrawPaint.setFlags(Paint.SUBPIXEL_TEXT_FLAG | Paint.ANTI_ALIAS_FLAG);
        mDrawPaint.setTextAlign(Paint.Align.CENTER);
        mDrawPaint.setTypeface(Typeface.create(
                context.getString(com.android.internal.R.string.config_headlineFontFamily), 0));
        mAppearInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.linear_out_slow_in);
        mDisappearInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_linear_in);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_slow_in);
        mPM = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        setWillNotDraw(false);
    }

    @Override
    protected PinShapeInput inflatePinShapeInput(boolean isPinHinting) {
        if (isPinHinting) {
            return (PinShapeInput) LayoutInflater.from(mContext).inflate(
                    R.layout.keyguard_pin_shape_hinting_view, null);
        } else {
            return (PinShapeInput) LayoutInflater.from(mContext).inflate(
                    R.layout.keyguard_pin_shape_non_hinting_view, null);
        }
    }

    @Override
    protected boolean shouldSendAccessibilityEvent() {
        return isFocused() || isSelected() && isShown();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Do not use legacy draw animations for pin shapes.
        if (mUsePinShapes) {
            super.onDraw(canvas);
            return;
        }

        float totalDrawingWidth = getDrawingWidth();
        float currentDrawPosition;
        if ((mGravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT) {
            if ((mGravity & Gravity.RELATIVE_LAYOUT_DIRECTION) != 0
                    && getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                currentDrawPosition = getWidth() - getPaddingRight() - totalDrawingWidth;
            } else {
                currentDrawPosition = getPaddingLeft();
            }
        } else {
            float maxRight = getWidth() - getPaddingRight() - totalDrawingWidth;
            float center = getWidth() / 2f - totalDrawingWidth / 2f;
            currentDrawPosition = center > 0 ? center : maxRight;
        }
        int length = mTextChars.size();
        Rect bounds = getCharBounds();
        int charHeight = (bounds.bottom - bounds.top);
        float yPosition =
                (getHeight() - getPaddingBottom() - getPaddingTop()) / 2 + getPaddingTop();
        canvas.clipRect(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(),
                getHeight() - getPaddingBottom());
        float charLength = bounds.right - bounds.left;
        for (int i = 0; i < length; i++) {
            CharState charState = mTextChars.get(i);
            float charWidth = charState.draw(canvas, currentDrawPosition, charHeight, yPosition,
                    charLength);
            currentDrawPosition += charWidth;
        }
    }

    @Override
    protected void onAppend(char c, int newLength) {
        int visibleChars = mTextChars.size();
        CharState charState;
        if (newLength > visibleChars) {
            charState = obtainCharState(c);
            mTextChars.add(charState);
        } else {
            charState = mTextChars.get(newLength - 1);
            charState.whichChar = c;
        }
        charState.startAppearAnimation();

        // ensure that the previous element is being swapped
        if (newLength > 1) {
            CharState previousState = mTextChars.get(newLength - 2);
            if (previousState.isDotSwapPending) {
                previousState.swapToDotWhenAppearFinished();
            }
        }
    }

    @Override
    protected void onDelete(int index) {
        CharState charState = mTextChars.get(index);
        charState.startRemoveAnimation(0, 0);
    }

    @Override
    protected void onReset(boolean animated) {
        if (animated) {
            int length = mTextChars.size();
            int middleIndex = (length - 1) / 2;
            long delayPerElement = RESET_DELAY_PER_ELEMENT;
            for (int i = 0; i < length; i++) {
                CharState charState = mTextChars.get(i);
                int delayIndex;
                if (i <= middleIndex) {
                    delayIndex = i * 2;
                } else {
                    int distToMiddle = i - middleIndex;
                    delayIndex = (length - 1) - (distToMiddle - 1) * 2;
                }
                long startDelay = delayIndex * delayPerElement;
                startDelay = Math.min(startDelay, RESET_MAX_DELAY);
                long maxDelay = delayPerElement * (length - 1);
                maxDelay = Math.min(maxDelay, RESET_MAX_DELAY) + DISAPPEAR_DURATION;
                charState.startRemoveAnimation(startDelay, maxDelay);
                charState.removeDotSwapCallbacks();
            }
        } else {
            mTextChars.clear();
        }
    }

    @Override
    protected void onUserActivity() {
        if (!notifyPasswordTextViewUserActivityInBackground()) {
            mPM.userActivity(SystemClock.uptimeMillis(), false);
        }
        super.onUserActivity();
    }

    /**
     * Reload colors from resources.
     **/
    public void reloadColors() {
        mDrawColor = Utils.getColorAttr(getContext(),
                android.R.attr.textColorPrimary).getDefaultColor();
        mDrawPaint.setColor(mDrawColor);
        if (mPinShapeInput != null) {
            mPinShapeInput.setDrawColor(mDrawColor);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        mTextHeightRaw = getContext().getResources().getInteger(
                R.integer.scaled_password_text_size);
    }

    private Rect getCharBounds() {
        float textHeight = mTextHeightRaw * getResources().getDisplayMetrics().scaledDensity;
        mDrawPaint.setTextSize(textHeight);
        Rect bounds = new Rect();
        mDrawPaint.getTextBounds("0", 0, 1, bounds);
        return bounds;
    }

    private float getDrawingWidth() {
        int width = 0;
        int length = mTextChars.size();
        Rect bounds = getCharBounds();
        int charLength = bounds.right - bounds.left;
        for (int i = 0; i < length; i++) {
            CharState charState = mTextChars.get(i);
            if (i != 0) {
                width += mCharPadding * charState.currentWidthFactor;
            }
            width += charLength * charState.currentWidthFactor;
        }
        return width;
    }

    private CharState obtainCharState(char c) {
        CharState charState = new CharState();
        charState.whichChar = c;
        return charState;
    }

    @Override
    protected CharSequence getTransformedText() {
        int textLength = mTextChars.size();
        StringBuilder stringBuilder = new StringBuilder(textLength);
        for (int i = 0; i < textLength; i++) {
            CharState charState = mTextChars.get(i);
            // If the dot is disappearing, the character is disappearing entirely. Consider
            // it gone.
            if (charState.dotAnimator != null && !charState.dotAnimationIsGrowing) {
                continue;
            }
            stringBuilder.append(charState.isCharVisibleForA11y() ? charState.whichChar : DOT);
        }
        return stringBuilder;
    }

    private class CharState {
        char whichChar;
        ValueAnimator textAnimator;
        boolean textAnimationIsGrowing;
        Animator dotAnimator;
        boolean dotAnimationIsGrowing;
        ValueAnimator widthAnimator;
        boolean widthAnimationIsGrowing;
        float currentTextSizeFactor;
        float currentDotSizeFactor;
        float currentWidthFactor;
        boolean isDotSwapPending;
        float currentTextTranslationY = 1.0f;
        ValueAnimator textTranslateAnimator;

        Animator.AnimatorListener removeEndListener = new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCancelled) {
                    mTextChars.remove(CharState.this);
                    cancelAnimator(textTranslateAnimator);
                    textTranslateAnimator = null;
                }
            }

            @Override
            public void onAnimationStart(Animator animation) {
                mCancelled = false;
            }
        };

        Animator.AnimatorListener dotFinishListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dotAnimator = null;
            }
        };

        Animator.AnimatorListener textFinishListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                textAnimator = null;
            }
        };

        Animator.AnimatorListener textTranslateFinishListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                textTranslateAnimator = null;
            }
        };

        Animator.AnimatorListener widthFinishListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                widthAnimator = null;
            }
        };

        private ValueAnimator.AnimatorUpdateListener mDotSizeUpdater =
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        currentDotSizeFactor = (float) animation.getAnimatedValue();
                        invalidate();
                    }
                };

        private ValueAnimator.AnimatorUpdateListener mTextSizeUpdater =
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        boolean textVisibleBefore = isCharVisibleForA11y();
                        float beforeTextSizeFactor = currentTextSizeFactor;
                        currentTextSizeFactor = (float) animation.getAnimatedValue();
                        if (textVisibleBefore != isCharVisibleForA11y()) {
                            currentTextSizeFactor = beforeTextSizeFactor;
                            CharSequence beforeText = getTransformedText();
                            currentTextSizeFactor = (float) animation.getAnimatedValue();
                            int indexOfThisChar = mTextChars.indexOf(CharState.this);
                            if (indexOfThisChar >= 0) {
                                sendAccessibilityEventTypeViewTextChanged(beforeText,
                                        indexOfThisChar, 1, 1);
                            }
                        }
                        invalidate();
                    }
                };

        private ValueAnimator.AnimatorUpdateListener mTextTranslationUpdater =
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        currentTextTranslationY = (float) animation.getAnimatedValue();
                        invalidate();
                    }
                };

        private ValueAnimator.AnimatorUpdateListener mWidthUpdater =
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        currentWidthFactor = (float) animation.getAnimatedValue();
                        invalidate();
                    }
                };

        private Runnable dotSwapperRunnable = new Runnable() {
            @Override
            public void run() {
                performSwap();
                isDotSwapPending = false;
            }
        };

        void startRemoveAnimation(long startDelay, long widthDelay) {
            boolean dotNeedsAnimation =
                    (currentDotSizeFactor > 0.0f && dotAnimator == null) || (dotAnimator != null
                            && dotAnimationIsGrowing);
            boolean textNeedsAnimation =
                    (currentTextSizeFactor > 0.0f && textAnimator == null) || (textAnimator != null
                            && textAnimationIsGrowing);
            boolean widthNeedsAnimation =
                    (currentWidthFactor > 0.0f && widthAnimator == null) || (widthAnimator != null
                            && widthAnimationIsGrowing);
            if (dotNeedsAnimation) {
                startDotDisappearAnimation(startDelay);
            }
            if (textNeedsAnimation) {
                startTextDisappearAnimation(startDelay);
            }
            if (widthNeedsAnimation) {
                startWidthDisappearAnimation(widthDelay);
            }
        }

        void startAppearAnimation() {
            boolean dotNeedsAnimation =
                    !mShowPassword && (dotAnimator == null || !dotAnimationIsGrowing);
            boolean textNeedsAnimation =
                    mShowPassword && (textAnimator == null || !textAnimationIsGrowing);
            boolean widthNeedsAnimation = (widthAnimator == null || !widthAnimationIsGrowing);
            if (dotNeedsAnimation) {
                startDotAppearAnimation(0);
            }
            if (textNeedsAnimation) {
                startTextAppearAnimation();
            }
            if (widthNeedsAnimation) {
                startWidthAppearAnimation();
            }
            if (mShowPassword) {
                postDotSwap(TEXT_VISIBILITY_DURATION);
            }
        }

        /**
         * Posts a runnable which ensures that the text will be replaced by a dot after {@link
         * com.android.keyguard.PasswordTextView#TEXT_VISIBILITY_DURATION}.
         */
        private void postDotSwap(long delay) {
            removeDotSwapCallbacks();
            postDelayed(dotSwapperRunnable, delay);
            isDotSwapPending = true;
        }

        private void removeDotSwapCallbacks() {
            removeCallbacks(dotSwapperRunnable);
            isDotSwapPending = false;
        }

        void swapToDotWhenAppearFinished() {
            removeDotSwapCallbacks();
            if (textAnimator != null) {
                long remainingDuration =
                        textAnimator.getDuration() - textAnimator.getCurrentPlayTime();
                postDotSwap(remainingDuration + TEXT_REST_DURATION_AFTER_APPEAR);
            } else {
                performSwap();
            }
        }

        private void performSwap() {
            startTextDisappearAnimation(0);
            startDotAppearAnimation(
                    DISAPPEAR_DURATION - DOT_APPEAR_TEXT_DISAPPEAR_OVERLAP_DURATION);
        }

        private void startWidthDisappearAnimation(long widthDelay) {
            cancelAnimator(widthAnimator);
            widthAnimator = ValueAnimator.ofFloat(currentWidthFactor, 0.0f);
            widthAnimator.addUpdateListener(mWidthUpdater);
            widthAnimator.addListener(widthFinishListener);
            widthAnimator.addListener(removeEndListener);
            widthAnimator.setDuration((long) (DISAPPEAR_DURATION * currentWidthFactor));
            widthAnimator.setStartDelay(widthDelay);
            widthAnimator.start();
            widthAnimationIsGrowing = false;
        }

        private void startTextDisappearAnimation(long startDelay) {
            cancelAnimator(textAnimator);
            textAnimator = ValueAnimator.ofFloat(currentTextSizeFactor, 0.0f);
            textAnimator.addUpdateListener(mTextSizeUpdater);
            textAnimator.addListener(textFinishListener);
            textAnimator.setInterpolator(mDisappearInterpolator);
            textAnimator.setDuration((long) (DISAPPEAR_DURATION * currentTextSizeFactor));
            textAnimator.setStartDelay(startDelay);
            textAnimator.start();
            textAnimationIsGrowing = false;
        }

        private void startDotDisappearAnimation(long startDelay) {
            cancelAnimator(dotAnimator);
            ValueAnimator animator = ValueAnimator.ofFloat(currentDotSizeFactor, 0.0f);
            animator.addUpdateListener(mDotSizeUpdater);
            animator.addListener(dotFinishListener);
            animator.setInterpolator(mDisappearInterpolator);
            long duration = (long) (DISAPPEAR_DURATION * Math.min(currentDotSizeFactor, 1.0f));
            animator.setDuration(duration);
            animator.setStartDelay(startDelay);
            animator.start();
            dotAnimator = animator;
            dotAnimationIsGrowing = false;
        }

        private void startWidthAppearAnimation() {
            cancelAnimator(widthAnimator);
            widthAnimator = ValueAnimator.ofFloat(currentWidthFactor, 1.0f);
            widthAnimator.addUpdateListener(mWidthUpdater);
            widthAnimator.addListener(widthFinishListener);
            widthAnimator.setDuration((long) (APPEAR_DURATION * (1f - currentWidthFactor)));
            widthAnimator.start();
            widthAnimationIsGrowing = true;
        }

        private void startTextAppearAnimation() {
            cancelAnimator(textAnimator);
            textAnimator = ValueAnimator.ofFloat(currentTextSizeFactor, 1.0f);
            textAnimator.addUpdateListener(mTextSizeUpdater);
            textAnimator.addListener(textFinishListener);
            textAnimator.setInterpolator(mAppearInterpolator);
            textAnimator.setDuration((long) (APPEAR_DURATION * (1f - currentTextSizeFactor)));
            textAnimator.start();
            textAnimationIsGrowing = true;

            // handle translation
            if (textTranslateAnimator == null) {
                textTranslateAnimator = ValueAnimator.ofFloat(1.0f, 0.0f);
                textTranslateAnimator.addUpdateListener(mTextTranslationUpdater);
                textTranslateAnimator.addListener(textTranslateFinishListener);
                textTranslateAnimator.setInterpolator(mAppearInterpolator);
                textTranslateAnimator.setDuration(APPEAR_DURATION);
                textTranslateAnimator.start();
            }
        }

        private void startDotAppearAnimation(long delay) {
            cancelAnimator(dotAnimator);
            if (!mShowPassword) {
                // We perform an overshoot animation
                ValueAnimator overShootAnimator = ValueAnimator.ofFloat(currentDotSizeFactor,
                        DOT_OVERSHOOT_FACTOR);
                overShootAnimator.addUpdateListener(mDotSizeUpdater);
                overShootAnimator.setInterpolator(mAppearInterpolator);
                long overShootDuration =
                        (long) (DOT_APPEAR_DURATION_OVERSHOOT * OVERSHOOT_TIME_POSITION);
                overShootAnimator.setDuration(overShootDuration);
                ValueAnimator settleBackAnimator = ValueAnimator.ofFloat(DOT_OVERSHOOT_FACTOR,
                        1.0f);
                settleBackAnimator.addUpdateListener(mDotSizeUpdater);
                settleBackAnimator.setDuration(DOT_APPEAR_DURATION_OVERSHOOT - overShootDuration);
                settleBackAnimator.addListener(dotFinishListener);
                AnimatorSet animatorSet = new AnimatorSet();
                animatorSet.playSequentially(overShootAnimator, settleBackAnimator);
                animatorSet.setStartDelay(delay);
                animatorSet.start();
                dotAnimator = animatorSet;
            } else {
                ValueAnimator growAnimator = ValueAnimator.ofFloat(currentDotSizeFactor, 1.0f);
                growAnimator.addUpdateListener(mDotSizeUpdater);
                growAnimator.setDuration((long) (APPEAR_DURATION * (1.0f - currentDotSizeFactor)));
                growAnimator.addListener(dotFinishListener);
                growAnimator.setStartDelay(delay);
                growAnimator.start();
                dotAnimator = growAnimator;
            }
            dotAnimationIsGrowing = true;
        }

        private void cancelAnimator(Animator animator) {
            if (animator != null) {
                animator.cancel();
            }
        }

        /**
         * Draw this char to the canvas.
         *
         * @return The width this character contributes, including padding.
         */
        public float draw(Canvas canvas, float currentDrawPosition, int charHeight, float yPosition,
                float charLength) {
            boolean textVisible = currentTextSizeFactor > 0;
            boolean dotVisible = currentDotSizeFactor > 0;
            float charWidth = charLength * currentWidthFactor;
            if (textVisible) {
                float currYPosition = yPosition + charHeight / 2.0f * currentTextSizeFactor
                        + charHeight * currentTextTranslationY * 0.8f;
                canvas.save();
                float centerX = currentDrawPosition + charWidth / 2;
                canvas.translate(centerX, currYPosition);
                canvas.scale(currentTextSizeFactor, currentTextSizeFactor);
                canvas.drawText(Character.toString(whichChar), 0, 0, mDrawPaint);
                canvas.restore();
            }
            if (dotVisible) {
                canvas.save();
                float centerX = currentDrawPosition + charWidth / 2;
                canvas.translate(centerX, yPosition);
                canvas.drawCircle(0, 0, mDotSize / 2 * currentDotSizeFactor, mDrawPaint);
                canvas.restore();
            }
            return charWidth + mCharPadding * currentWidthFactor;
        }

        public boolean isCharVisibleForA11y() {
            // The text has size 0 when it is first added, but we want to count it as visible if
            // it will become visible presently. Count text as visible if an animator
            // is configured to make it grow.
            boolean textIsGrowing = textAnimator != null && textAnimationIsGrowing;
            return (currentTextSizeFactor > 0) || textIsGrowing;
        }
    }
}
