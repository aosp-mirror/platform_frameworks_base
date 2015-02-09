/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import java.util.ArrayList;
import java.util.Stack;

/**
 * A View similar to a textView which contains password text and can animate when the text is
 * changed
 */
public class PasswordTextView extends View {

    private static final float DOT_OVERSHOOT_FACTOR = 1.5f;
    private static final long DOT_APPEAR_DURATION_OVERSHOOT = 320;
    private static final long APPEAR_DURATION = 160;
    private static final long DISAPPEAR_DURATION = 160;
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
    private final int mTextHeightRaw;
    private ArrayList<CharState> mTextChars = new ArrayList<>();
    private String mText = "";
    private Stack<CharState> mCharPool = new Stack<>();
    private int mDotSize;
    private PowerManager mPM;
    private int mCharPadding;
    private final Paint mDrawPaint = new Paint();
    private Interpolator mAppearInterpolator;
    private Interpolator mDisappearInterpolator;
    private Interpolator mFastOutSlowInInterpolator;
    private boolean mShowPassword;

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
        setFocusableInTouchMode(true);
        setFocusable(true);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PasswordTextView);
        try {
            mTextHeightRaw = a.getInt(R.styleable.PasswordTextView_scaledTextSize, 0);
        } finally {
            a.recycle();
        }
        mDrawPaint.setFlags(Paint.SUBPIXEL_TEXT_FLAG | Paint.ANTI_ALIAS_FLAG);
        mDrawPaint.setTextAlign(Paint.Align.CENTER);
        mDrawPaint.setColor(0xffffffff);
        mDrawPaint.setTypeface(Typeface.create("sans-serif-light", 0));
        mDotSize = getContext().getResources().getDimensionPixelSize(R.dimen.password_dot_size);
        mCharPadding = getContext().getResources().getDimensionPixelSize(R.dimen
                .password_char_padding);
        mShowPassword = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.TEXT_SHOW_PASSWORD, 1) == 1;
        mAppearInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.linear_out_slow_in);
        mDisappearInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_linear_in);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_slow_in);
        mPM = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float totalDrawingWidth = getDrawingWidth();
        float currentDrawPosition = getWidth() / 2 - totalDrawingWidth / 2;
        int length = mTextChars.size();
        Rect bounds = getCharBounds();
        int charHeight = (bounds.bottom - bounds.top);
        float yPosition = getHeight() / 2;
        float charLength = bounds.right - bounds.left;
        for (int i = 0; i < length; i++) {
            CharState charState = mTextChars.get(i);
            float charWidth = charState.draw(canvas, currentDrawPosition, charHeight, yPosition,
                    charLength);
            currentDrawPosition += charWidth;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
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


    public void append(char c) {
        int visibleChars = mTextChars.size();
        String textbefore = mText;
        mText = mText + c;
        int newLength = mText.length();
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
        userActivity();
        sendAccessibilityEventTypeViewTextChanged(textbefore, textbefore.length(), 0, 1);
    }

    private void userActivity() {
        mPM.userActivity(SystemClock.uptimeMillis(), false);
    }

    public void deleteLastChar() {
        int length = mText.length();
        String textbefore = mText;
        if (length > 0) {
            mText = mText.substring(0, length - 1);
            CharState charState = mTextChars.get(length - 1);
            charState.startRemoveAnimation(0, 0);
        }
        userActivity();
        sendAccessibilityEventTypeViewTextChanged(textbefore, textbefore.length() - 1, 1, 0);
    }

    public String getText() {
        return mText;
    }

    private CharState obtainCharState(char c) {
        CharState charState;
        if(mCharPool.isEmpty()) {
            charState = new CharState();
        } else {
            charState = mCharPool.pop();
            charState.reset();
        }
        charState.whichChar = c;
        return charState;
    }

    public void reset(boolean animated) {
        String textbefore = mText;
        mText = "";
        int length = mTextChars.size();
        int middleIndex = (length - 1) / 2;
        long delayPerElement = RESET_DELAY_PER_ELEMENT;
        for (int i = 0; i < length; i++) {
            CharState charState = mTextChars.get(i);
            if (animated) {
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
            } else {
                mCharPool.push(charState);
            }
        }
        if (!animated) {
            mTextChars.clear();
        }
        sendAccessibilityEventTypeViewTextChanged(textbefore, 0, textbefore.length(), 0);
    }

    void sendAccessibilityEventTypeViewTextChanged(String beforeText, int fromIndex,
                                                   int removedCount, int addedCount) {
        if (AccessibilityManager.getInstance(mContext).isEnabled() &&
                (isFocused() || isSelected() && isShown())) {
            if (!shouldSpeakPasswordsForAccessibility()) {
                beforeText = null;
            }
            AccessibilityEvent event =
                    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
            event.setFromIndex(fromIndex);
            event.setRemovedCount(removedCount);
            event.setAddedCount(addedCount);
            event.setBeforeText(beforeText);
            event.setPassword(true);
            sendAccessibilityEventUnchecked(event);
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);

        event.setClassName(PasswordTextView.class.getName());
        event.setPassword(true);
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        super.onPopulateAccessibilityEvent(event);

        if (shouldSpeakPasswordsForAccessibility()) {
            final CharSequence text = mText;
            if (!TextUtils.isEmpty(text)) {
                event.getText().add(text);
            }
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);

        info.setClassName(PasswordTextView.class.getName());
        info.setPassword(true);

        if (shouldSpeakPasswordsForAccessibility()) {
            info.setText(mText);
        }

        info.setEditable(true);

        info.setInputType(InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    /**
     * @return true if the user has explicitly allowed accessibility services
     * to speak passwords.
     */
    private boolean shouldSpeakPasswordsForAccessibility() {
        return (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD, 0,
                UserHandle.USER_CURRENT_OR_SELF) == 1);
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
                    mCharPool.push(CharState.this);
                    reset();
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

        private ValueAnimator.AnimatorUpdateListener dotSizeUpdater
                = new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                currentDotSizeFactor = (float) animation.getAnimatedValue();
                invalidate();
            }
        };

        private ValueAnimator.AnimatorUpdateListener textSizeUpdater
                = new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                currentTextSizeFactor = (float) animation.getAnimatedValue();
                invalidate();
            }
        };

        private ValueAnimator.AnimatorUpdateListener textTranslationUpdater
                = new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                currentTextTranslationY = (float) animation.getAnimatedValue();
                invalidate();
            }
        };

        private ValueAnimator.AnimatorUpdateListener widthUpdater
                = new ValueAnimator.AnimatorUpdateListener() {
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

        void reset() {
            whichChar = 0;
            currentTextSizeFactor = 0.0f;
            currentDotSizeFactor = 0.0f;
            currentWidthFactor = 0.0f;
            cancelAnimator(textAnimator);
            textAnimator = null;
            cancelAnimator(dotAnimator);
            dotAnimator = null;
            cancelAnimator(widthAnimator);
            widthAnimator = null;
            currentTextTranslationY = 1.0f;
            removeDotSwapCallbacks();
        }

        void startRemoveAnimation(long startDelay, long widthDelay) {
            boolean dotNeedsAnimation = (currentDotSizeFactor > 0.0f && dotAnimator == null)
                    || (dotAnimator != null && dotAnimationIsGrowing);
            boolean textNeedsAnimation = (currentTextSizeFactor > 0.0f && textAnimator == null)
                    || (textAnimator != null && textAnimationIsGrowing);
            boolean widthNeedsAnimation = (currentWidthFactor > 0.0f && widthAnimator == null)
                    || (widthAnimator != null && widthAnimationIsGrowing);
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
            boolean dotNeedsAnimation = !mShowPassword
                    && (dotAnimator == null || !dotAnimationIsGrowing);
            boolean textNeedsAnimation = mShowPassword
                    && (textAnimator == null || !textAnimationIsGrowing);
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
                long remainingDuration = textAnimator.getDuration()
                        - textAnimator.getCurrentPlayTime();
                postDotSwap(remainingDuration + TEXT_REST_DURATION_AFTER_APPEAR);
            } else {
                performSwap();
            }
        }

        private void performSwap() {
            startTextDisappearAnimation(0);
            startDotAppearAnimation(DISAPPEAR_DURATION
                    - DOT_APPEAR_TEXT_DISAPPEAR_OVERLAP_DURATION);
        }

        private void startWidthDisappearAnimation(long widthDelay) {
            cancelAnimator(widthAnimator);
            widthAnimator = ValueAnimator.ofFloat(currentWidthFactor, 0.0f);
            widthAnimator.addUpdateListener(widthUpdater);
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
            textAnimator.addUpdateListener(textSizeUpdater);
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
            animator.addUpdateListener(dotSizeUpdater);
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
            widthAnimator.addUpdateListener(widthUpdater);
            widthAnimator.addListener(widthFinishListener);
            widthAnimator.setDuration((long) (APPEAR_DURATION * (1f - currentWidthFactor)));
            widthAnimator.start();
            widthAnimationIsGrowing = true;
        }

        private void startTextAppearAnimation() {
            cancelAnimator(textAnimator);
            textAnimator = ValueAnimator.ofFloat(currentTextSizeFactor, 1.0f);
            textAnimator.addUpdateListener(textSizeUpdater);
            textAnimator.addListener(textFinishListener);
            textAnimator.setInterpolator(mAppearInterpolator);
            textAnimator.setDuration((long) (APPEAR_DURATION * (1f - currentTextSizeFactor)));
            textAnimator.start();
            textAnimationIsGrowing = true;

            // handle translation
            if (textTranslateAnimator == null) {
                textTranslateAnimator = ValueAnimator.ofFloat(1.0f, 0.0f);
                textTranslateAnimator.addUpdateListener(textTranslationUpdater);
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
                overShootAnimator.addUpdateListener(dotSizeUpdater);
                overShootAnimator.setInterpolator(mAppearInterpolator);
                long overShootDuration = (long) (DOT_APPEAR_DURATION_OVERSHOOT
                        * OVERSHOOT_TIME_POSITION);
                overShootAnimator.setDuration(overShootDuration);
                ValueAnimator settleBackAnimator = ValueAnimator.ofFloat(DOT_OVERSHOOT_FACTOR,
                        1.0f);
                settleBackAnimator.addUpdateListener(dotSizeUpdater);
                settleBackAnimator.setDuration(DOT_APPEAR_DURATION_OVERSHOOT - overShootDuration);
                settleBackAnimator.addListener(dotFinishListener);
                AnimatorSet animatorSet = new AnimatorSet();
                animatorSet.playSequentially(overShootAnimator, settleBackAnimator);
                animatorSet.setStartDelay(delay);
                animatorSet.start();
                dotAnimator = animatorSet;
            } else {
                ValueAnimator growAnimator = ValueAnimator.ofFloat(currentDotSizeFactor, 1.0f);
                growAnimator.addUpdateListener(dotSizeUpdater);
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
    }
}
