/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;


public class VerticalTextSpinner extends View {

    private static final int SELECTOR_ARROW_HEIGHT = 15;
    
    private static final int TEXT_SPACING = 18;
    private static final int TEXT_MARGIN_RIGHT = 25;
    private static final int TEXT_SIZE = 22;
    
    /* Keep the calculations as this is really a for loop from
     * -2 to 2 but precalculated so we don't have to do in the onDraw.
     */
    private static final int TEXT1_Y = (TEXT_SIZE * (-2 + 2)) + (TEXT_SPACING * (-2 + 1));
    private static final int TEXT2_Y = (TEXT_SIZE * (-1 + 2)) + (TEXT_SPACING * (-1 + 1));
    private static final int TEXT3_Y = (TEXT_SIZE * (0 + 2)) + (TEXT_SPACING * (0 + 1));
    private static final int TEXT4_Y = (TEXT_SIZE * (1 + 2)) + (TEXT_SPACING * (1 + 1));
    private static final int TEXT5_Y = (TEXT_SIZE * (2 + 2)) + (TEXT_SPACING * (2 + 1));
    
    private static final int SCROLL_MODE_NONE = 0;
    private static final int SCROLL_MODE_UP = 1;
    private static final int SCROLL_MODE_DOWN = 2;
    
    private static final long DEFAULT_SCROLL_INTERVAL_MS = 400;
    private static final int SCROLL_DISTANCE = TEXT_SIZE + TEXT_SPACING;
    private static final int MIN_ANIMATIONS = 4;
    
    private final Drawable mBackgroundFocused;
    private final Drawable mSelectorFocused;
    private final Drawable mSelectorNormal;
    private final int mSelectorDefaultY;
    private final int mSelectorMinY;
    private final int mSelectorMaxY;
    private final int mSelectorHeight;
    private final TextPaint mTextPaintDark;
    private final TextPaint mTextPaintLight;
    
    private int mSelectorY;
    private Drawable mSelector;
    private int mDownY;
    private boolean isDraggingSelector;
    private int mScrollMode;
    private long mScrollInterval;
    private boolean mIsAnimationRunning;
    private boolean mStopAnimation;
    private boolean mWrapAround = true;
    
    private int mTotalAnimatedDistance;
    private int mNumberOfAnimations;
    private long mDelayBetweenAnimations;
    private int mDistanceOfEachAnimation;
    
    private String[] mTextList;
    private int mCurrentSelectedPos;
    private OnChangedListener mListener;
    
    private String mText1;
    private String mText2;
    private String mText3;
    private String mText4;
    private String mText5;
    
    public interface OnChangedListener {
        void onChanged(
                VerticalTextSpinner spinner, int oldPos, int newPos, String[] items);
    }
    
    public VerticalTextSpinner(Context context) {
        this(context, null);
    }
    
    public VerticalTextSpinner(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VerticalTextSpinner(Context context, AttributeSet attrs,
            int defStyle) {
        super(context, attrs, defStyle);
        
        mBackgroundFocused = context.getResources().getDrawable(com.android.internal.R.drawable.pickerbox_background);
        mSelectorFocused = context.getResources().getDrawable(com.android.internal.R.drawable.pickerbox_selected);
        mSelectorNormal = context.getResources().getDrawable(com.android.internal.R.drawable.pickerbox_unselected);
        
        mSelectorHeight = mSelectorFocused.getIntrinsicHeight();
        mSelectorDefaultY = (mBackgroundFocused.getIntrinsicHeight() - mSelectorHeight) / 2;
        mSelectorMinY = 0;
        mSelectorMaxY = mBackgroundFocused.getIntrinsicHeight() - mSelectorHeight;
        
        mSelector = mSelectorNormal;
        mSelectorY = mSelectorDefaultY;
        
        mTextPaintDark = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaintDark.setTextSize(TEXT_SIZE);
        mTextPaintDark.setColor(context.getResources().getColor(com.android.internal.R.color.primary_text_light));
        
        mTextPaintLight = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaintLight.setTextSize(TEXT_SIZE);
        mTextPaintLight.setColor(context.getResources().getColor(com.android.internal.R.color.secondary_text_dark));
        
        mScrollMode = SCROLL_MODE_NONE;
        mScrollInterval = DEFAULT_SCROLL_INTERVAL_MS;
        calculateAnimationValues();
    }
    
    public void setOnChangeListener(OnChangedListener listener) {
        mListener = listener;
    }
    
    public void setItems(String[] textList) {
        mTextList = textList;
        calculateTextPositions();
    }
    
    public void setSelectedPos(int selectedPos) {
        mCurrentSelectedPos = selectedPos;
        calculateTextPositions();
        postInvalidate();
    }
    
    public void setScrollInterval(long interval) {
        mScrollInterval = interval;
        calculateAnimationValues();
    }
    
    public void setWrapAround(boolean wrap) {
        mWrapAround = wrap;
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        
        /* This is a bit confusing, when we get the key event
         * DPAD_DOWN we actually roll the spinner up. When the
         * key event is DPAD_UP we roll the spinner down.
         */
        if ((keyCode == KeyEvent.KEYCODE_DPAD_UP) && canScrollDown()) {
            mScrollMode = SCROLL_MODE_DOWN;
            scroll();
            mStopAnimation = true;
            return true;
        } else if ((keyCode == KeyEvent.KEYCODE_DPAD_DOWN) && canScrollUp()) {
            mScrollMode = SCROLL_MODE_UP;
            scroll();
            mStopAnimation = true;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean canScrollDown() {
        return (mCurrentSelectedPos > 0) || mWrapAround;
    }

    private boolean canScrollUp() {
        return ((mCurrentSelectedPos < (mTextList.length - 1)) || mWrapAround);
    }
    
    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
            Rect previouslyFocusedRect) {
        if (gainFocus) {
            setBackgroundDrawable(mBackgroundFocused);
            mSelector = mSelectorFocused;
        } else {
            setBackgroundDrawable(null);
            mSelector = mSelectorNormal;
            mSelectorY = mSelectorDefaultY;
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {        
        final int action = event.getAction();
        final int y = (int) event.getY();

        switch (action) {
        case MotionEvent.ACTION_DOWN:
            requestFocus();
            mDownY = y;
            isDraggingSelector = (y >= mSelectorY) && (y <= (mSelectorY + mSelector.getIntrinsicHeight()));
            break;

        case MotionEvent.ACTION_MOVE:
            if (isDraggingSelector) {
                int top = mSelectorDefaultY + (y - mDownY);
                if (top <= mSelectorMinY && canScrollDown()) {
                    mSelectorY = mSelectorMinY;
                    mStopAnimation = false;
                    if (mScrollMode != SCROLL_MODE_DOWN) {
                        mScrollMode = SCROLL_MODE_DOWN;
                        scroll();
                    }
                } else if (top >= mSelectorMaxY && canScrollUp()) {
                    mSelectorY = mSelectorMaxY;
                    mStopAnimation = false;
                    if (mScrollMode != SCROLL_MODE_UP) {
                        mScrollMode = SCROLL_MODE_UP;
                        scroll();
                    }
                } else {
                    mSelectorY = top;
                    mStopAnimation = true;
                }
            }
            break;
            
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
        default:
            mSelectorY = mSelectorDefaultY;
            mStopAnimation = true;
            invalidate();
            break;
        }
        return true;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        
        /* The bounds of the selector */
        final int selectorLeft = 0;
        final int selectorTop = mSelectorY;
        final int selectorRight = mMeasuredWidth;
        final int selectorBottom = mSelectorY + mSelectorHeight;
        
        /* Draw the selector */
        mSelector.setBounds(selectorLeft, selectorTop, selectorRight, selectorBottom);
        mSelector.draw(canvas);
        
        if (mTextList == null) {
            
            /* We're not setup with values so don't draw anything else */
            return;
        }
        
        final TextPaint textPaintDark = mTextPaintDark;
        if (hasFocus()) {
            
            /* The bounds of the top area where the text should be light */
            final int topLeft = 0;
            final int topTop = 0;
            final int topRight = selectorRight;
            final int topBottom = selectorTop + SELECTOR_ARROW_HEIGHT;

            /* Assign a bunch of local finals for performance */
            final String text1 = mText1;
            final String text2 = mText2;
            final String text3 = mText3;
            final String text4 = mText4;
            final String text5 = mText5;
            final TextPaint textPaintLight = mTextPaintLight;
            
            /*
             * Draw the 1st, 2nd and 3rd item in light only, clip it so it only
             * draws in the area above the selector
             */
            canvas.save();
            canvas.clipRect(topLeft, topTop, topRight, topBottom);
            drawText(canvas, text1, TEXT1_Y
                    + mTotalAnimatedDistance, textPaintLight);
            drawText(canvas, text2, TEXT2_Y
                    + mTotalAnimatedDistance, textPaintLight);
            drawText(canvas, text3,
                    TEXT3_Y + mTotalAnimatedDistance, textPaintLight);
            canvas.restore();

            /*
             * Draw the 2nd, 3rd and 4th clipped to the selector bounds in dark
             * paint
             */
            canvas.save();
            canvas.clipRect(selectorLeft, selectorTop + SELECTOR_ARROW_HEIGHT,
                    selectorRight, selectorBottom - SELECTOR_ARROW_HEIGHT);
            drawText(canvas, text2, TEXT2_Y
                    + mTotalAnimatedDistance, textPaintDark);
            drawText(canvas, text3,
                    TEXT3_Y + mTotalAnimatedDistance, textPaintDark);
            drawText(canvas, text4,
                    TEXT4_Y + mTotalAnimatedDistance, textPaintDark);
            canvas.restore();

            /* The bounds of the bottom area where the text should be light */
            final int bottomLeft = 0;
            final int bottomTop = selectorBottom - SELECTOR_ARROW_HEIGHT;
            final int bottomRight = selectorRight;
            final int bottomBottom = mMeasuredHeight;

            /*
             * Draw the 3rd, 4th and 5th in white text, clip it so it only draws
             * in the area below the selector.
             */
            canvas.save();
            canvas.clipRect(bottomLeft, bottomTop, bottomRight, bottomBottom);
            drawText(canvas, text3,
                    TEXT3_Y + mTotalAnimatedDistance, textPaintLight);
            drawText(canvas, text4,
                    TEXT4_Y + mTotalAnimatedDistance, textPaintLight);
            drawText(canvas, text5,
                    TEXT5_Y + mTotalAnimatedDistance, textPaintLight);
            canvas.restore();
            
        } else {
            drawText(canvas, mText3, TEXT3_Y, textPaintDark);
        }
        if (mIsAnimationRunning) {
            if ((Math.abs(mTotalAnimatedDistance) + mDistanceOfEachAnimation) > SCROLL_DISTANCE) {
                mTotalAnimatedDistance = 0;
                if (mScrollMode == SCROLL_MODE_UP) {
                    int oldPos = mCurrentSelectedPos;
                    int newPos = getNewIndex(1);
                    if (newPos >= 0) {
                        mCurrentSelectedPos = newPos;
                        if (mListener != null) {
                            mListener.onChanged(this, oldPos, mCurrentSelectedPos, mTextList);
                        }
                    }
                    if (newPos < 0 || ((newPos >= mTextList.length - 1) && !mWrapAround)) {
                        mStopAnimation = true;
                    }
                    calculateTextPositions();
                } else if (mScrollMode == SCROLL_MODE_DOWN) {
                    int oldPos = mCurrentSelectedPos;
                    int newPos = getNewIndex(-1);
                    if (newPos >= 0) {
                        mCurrentSelectedPos = newPos;
                        if (mListener != null) {
                            mListener.onChanged(this, oldPos, mCurrentSelectedPos, mTextList);
                        }
                    }
                    if (newPos < 0 || (newPos == 0 && !mWrapAround)) {
                        mStopAnimation = true;
                    }
                    calculateTextPositions();
                }
                if (mStopAnimation) {
                    final int previousScrollMode = mScrollMode;
                    
                    /* No longer scrolling, we wait till the current animation
                     * completes then we stop.
                     */
                    mIsAnimationRunning = false;
                    mStopAnimation = false;
                    mScrollMode = SCROLL_MODE_NONE;
                    
                    /* If the current selected item is an empty string
                     * scroll past it.
                     */
                    if ("".equals(mTextList[mCurrentSelectedPos])) {
                       mScrollMode = previousScrollMode;
                       scroll();
                       mStopAnimation = true;
                    }
                }
            } else {
                if (mScrollMode == SCROLL_MODE_UP) {
                    mTotalAnimatedDistance -= mDistanceOfEachAnimation;
                } else if (mScrollMode == SCROLL_MODE_DOWN) {
                    mTotalAnimatedDistance += mDistanceOfEachAnimation;
                }
            }
            if (mDelayBetweenAnimations > 0) {
                postInvalidateDelayed(mDelayBetweenAnimations);
            } else {
                invalidate();
            }
        }
    }

    /**
     * Called every time the text items or current position
     * changes. We calculate store we don't have to calculate
     * onDraw.
     */
    private void calculateTextPositions() {
        mText1 = getTextToDraw(-2);
        mText2 = getTextToDraw(-1);
        mText3 = getTextToDraw(0);
        mText4 = getTextToDraw(1);
        mText5 = getTextToDraw(2);
    }
    
    private String getTextToDraw(int offset) {
        int index = getNewIndex(offset);
        if (index < 0) {
            return "";
        }
        return mTextList[index];
    }

    private int getNewIndex(int offset) {
        int index = mCurrentSelectedPos + offset;
        if (index < 0) {
            if (mWrapAround) {
                index += mTextList.length;
            } else {
                return -1;
            }
        } else if (index >= mTextList.length) {
            if (mWrapAround) {
                index -= mTextList.length;
            } else {
                return -1;
            }
        }
        return index;
    }
    
    private void scroll() {
        if (mIsAnimationRunning) {
            return;
        }
        mTotalAnimatedDistance = 0;
        mIsAnimationRunning = true;
        invalidate();
    }

    private void calculateAnimationValues() {
        mNumberOfAnimations = (int) mScrollInterval / SCROLL_DISTANCE;
        if (mNumberOfAnimations < MIN_ANIMATIONS) {
            mNumberOfAnimations = MIN_ANIMATIONS;
            mDistanceOfEachAnimation = SCROLL_DISTANCE / mNumberOfAnimations;
            mDelayBetweenAnimations = 0;
        } else {
            mDistanceOfEachAnimation = SCROLL_DISTANCE / mNumberOfAnimations;
            mDelayBetweenAnimations = mScrollInterval / mNumberOfAnimations;
        }
    }
    
    private void drawText(Canvas canvas, String text, int y, TextPaint paint) {
        int width = (int) paint.measureText(text);
        int x = getMeasuredWidth() - width - TEXT_MARGIN_RIGHT;
        canvas.drawText(text, x, y, paint);
    }
    
    public int getCurrentSelectedPos() {
        return mCurrentSelectedPos;
    }
}
