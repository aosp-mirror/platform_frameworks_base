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

package android.widget;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.Log;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.android.internal.R;
import com.android.internal.widget.ExploreByTouchHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/**
 * View to show a clock circle picker (with one or two picking circles)
 *
 * @hide
 */
public class RadialTimePickerView extends View implements View.OnTouchListener {
    private static final String TAG = "ClockView";

    private static final boolean DEBUG = false;

    private static final int DEBUG_COLOR = 0x20FF0000;
    private static final int DEBUG_TEXT_COLOR = 0x60FF0000;
    private static final int DEBUG_STROKE_WIDTH = 2;

    private static final int HOURS = 0;
    private static final int MINUTES = 1;
    private static final int HOURS_INNER = 2;

    private static final int SELECTOR_CIRCLE = 0;
    private static final int SELECTOR_DOT = 1;
    private static final int SELECTOR_LINE = 2;

    private static final int AM = 0;
    private static final int PM = 1;

    // Opaque alpha level
    private static final int ALPHA_OPAQUE = 255;

    // Transparent alpha level
    private static final int ALPHA_TRANSPARENT = 0;

    // Alpha level of color for selector.
    private static final int ALPHA_SELECTOR = 60; // was 51

    private static final float COSINE_30_DEGREES = ((float) Math.sqrt(3)) * 0.5f;
    private static final float SINE_30_DEGREES = 0.5f;

    private static final int DEGREES_FOR_ONE_HOUR = 30;
    private static final int DEGREES_FOR_ONE_MINUTE = 6;

    private static final int[] HOURS_NUMBERS = {12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] HOURS_NUMBERS_24 = {0, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23};
    private static final int[] MINUTES_NUMBERS = {0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55};

    private static final int CENTER_RADIUS = 2;

    private static int[] sSnapPrefer30sMap = new int[361];

    private final InvalidateUpdateListener mInvalidateUpdateListener =
            new InvalidateUpdateListener();

    private final String[] mHours12Texts = new String[12];
    private final String[] mOuterHours24Texts = new String[12];
    private final String[] mInnerHours24Texts = new String[12];
    private final String[] mMinutesTexts = new String[12];

    private final Paint[] mPaint = new Paint[2];
    private final int[] mColor = new int[2];
    private final IntHolder[] mAlpha = new IntHolder[2];

    private final Paint mPaintCenter = new Paint();

    private final Paint[][] mPaintSelector = new Paint[2][3];
    private final int[][] mColorSelector = new int[2][3];
    private final IntHolder[][] mAlphaSelector = new IntHolder[2][3];

    private final Paint mPaintBackground = new Paint();
    private final Paint mPaintDebug = new Paint();

    private final Typeface mTypeface;

    private final float[] mCircleRadius = new float[3];

    private final float[] mTextSize = new float[2];

    private final float[][] mTextGridHeights = new float[2][7];
    private final float[][] mTextGridWidths = new float[2][7];

    private final float[] mInnerTextGridHeights = new float[7];
    private final float[] mInnerTextGridWidths = new float[7];

    private final float[] mCircleRadiusMultiplier = new float[2];
    private final float[] mNumbersRadiusMultiplier = new float[3];

    private final float[] mTextSizeMultiplier = new float[3];

    private final float[] mAnimationRadiusMultiplier = new float[3];

    private final float mTransitionMidRadiusMultiplier;
    private final float mTransitionEndRadiusMultiplier;

    private final int[] mLineLength = new int[3];
    private final int[] mSelectionRadius = new int[3];
    private final float mSelectionRadiusMultiplier;
    private final int[] mSelectionDegrees = new int[3];

    private final ArrayList<Animator> mHoursToMinutesAnims = new ArrayList<Animator>();
    private final ArrayList<Animator> mMinuteToHoursAnims = new ArrayList<Animator>();

    private final RadialPickerTouchHelper mTouchHelper;

    private float mInnerTextSize;

    private boolean mIs24HourMode;
    private boolean mShowHours;

    /**
     * When in 24-hour mode, indicates that the current hour is between
     * 1 and 12 (inclusive).
     */
    private boolean mIsOnInnerCircle;

    private int mXCenter;
    private int mYCenter;

    private int mMinHypotenuseForInnerNumber;
    private int mMaxHypotenuseForOuterNumber;
    private int mHalfwayHypotenusePoint;

    private String[] mOuterTextHours;
    private String[] mInnerTextHours;
    private String[] mOuterTextMinutes;
    private AnimatorSet mTransition;

    private int mAmOrPm;
    private int mDisabledAlpha;

    private OnValueSelectedListener mListener;

    private boolean mInputEnabled = true;

    public interface OnValueSelectedListener {
        void onValueSelected(int pickerIndex, int newValue, boolean autoAdvance);
    }

    static {
        // Prepare mapping to snap touchable degrees to selectable degrees.
        preparePrefer30sMap();
    }

    /**
     * Split up the 360 degrees of the circle among the 60 selectable values. Assigns a larger
     * selectable area to each of the 12 visible values, such that the ratio of space apportioned
     * to a visible value : space apportioned to a non-visible value will be 14 : 4.
     * E.g. the output of 30 degrees should have a higher range of input associated with it than
     * the output of 24 degrees, because 30 degrees corresponds to a visible number on the clock
     * circle (5 on the minutes, 1 or 13 on the hours).
     */
    private static void preparePrefer30sMap() {
        // We'll split up the visible output and the non-visible output such that each visible
        // output will correspond to a range of 14 associated input degrees, and each non-visible
        // output will correspond to a range of 4 associate input degrees, so visible numbers
        // are more than 3 times easier to get than non-visible numbers:
        // {354-359,0-7}:0, {8-11}:6, {12-15}:12, {16-19}:18, {20-23}:24, {24-37}:30, etc.
        //
        // If an output of 30 degrees should correspond to a range of 14 associated degrees, then
        // we'll need any input between 24 - 37 to snap to 30. Working out from there, 20-23 should
        // snap to 24, while 38-41 should snap to 36. This is somewhat counter-intuitive, that you
        // can be touching 36 degrees but have the selection snapped to 30 degrees; however, this
        // inconsistency isn't noticeable at such fine-grained degrees, and it affords us the
        // ability to aggressively prefer the visible values by a factor of more than 3:1, which
        // greatly contributes to the selectability of these values.

        // The first output is 0, and each following output will increment by 6 {0, 6, 12, ...}.
        int snappedOutputDegrees = 0;
        // Count of how many inputs we've designated to the specified output.
        int count = 1;
        // How many input we expect for a specified output. This will be 14 for output divisible
        // by 30, and 4 for the remaining output. We'll special case the outputs of 0 and 360, so
        // the caller can decide which they need.
        int expectedCount = 8;
        // Iterate through the input.
        for (int degrees = 0; degrees < 361; degrees++) {
            // Save the input-output mapping.
            sSnapPrefer30sMap[degrees] = snappedOutputDegrees;
            // If this is the last input for the specified output, calculate the next output and
            // the next expected count.
            if (count == expectedCount) {
                snappedOutputDegrees += 6;
                if (snappedOutputDegrees == 360) {
                    expectedCount = 7;
                } else if (snappedOutputDegrees % 30 == 0) {
                    expectedCount = 14;
                } else {
                    expectedCount = 4;
                }
                count = 1;
            } else {
                count++;
            }
        }
    }

    /**
     * Returns mapping of any input degrees (0 to 360) to one of 60 selectable output degrees,
     * where the degrees corresponding to visible numbers (i.e. those divisible by 30) will be
     * weighted heavier than the degrees corresponding to non-visible numbers.
     * See {@link #preparePrefer30sMap()} documentation for the rationale and generation of the
     * mapping.
     */
    private static int snapPrefer30s(int degrees) {
        if (sSnapPrefer30sMap == null) {
            return -1;
        }
        return sSnapPrefer30sMap[degrees];
    }

    /**
     * Returns mapping of any input degrees (0 to 360) to one of 12 visible output degrees (all
     * multiples of 30), where the input will be "snapped" to the closest visible degrees.
     * @param degrees The input degrees
     * @param forceHigherOrLower The output may be forced to either the higher or lower step, or may
     * be allowed to snap to whichever is closer. Use 1 to force strictly higher, -1 to force
     * strictly lower, and 0 to snap to the closer one.
     * @return output degrees, will be a multiple of 30
     */
    private static int snapOnly30s(int degrees, int forceHigherOrLower) {
        final int stepSize = DEGREES_FOR_ONE_HOUR;
        int floor = (degrees / stepSize) * stepSize;
        final int ceiling = floor + stepSize;
        if (forceHigherOrLower == 1) {
            degrees = ceiling;
        } else if (forceHigherOrLower == -1) {
            if (degrees == floor) {
                floor -= stepSize;
            }
            degrees = floor;
        } else {
            if ((degrees - floor) < (ceiling - degrees)) {
                degrees = floor;
            } else {
                degrees = ceiling;
            }
        }
        return degrees;
    }

    @SuppressWarnings("unused")
    public RadialTimePickerView(Context context)  {
        this(context, null);
    }

    public RadialTimePickerView(Context context, AttributeSet attrs)  {
        this(context, attrs, R.attr.timePickerStyle);
    }

    public RadialTimePickerView(Context context, AttributeSet attrs, int defStyleAttr)  {
        this(context, attrs, defStyleAttr, 0);
    }

    public RadialTimePickerView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes)  {
        super(context, attrs);

        // Pull disabled alpha from theme.
        final TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.disabledAlpha, outValue, true);
        mDisabledAlpha = (int) (outValue.getFloat() * 255 + 0.5f);

        // process style attributes
        final Resources res = getResources();
        final TypedArray a = mContext.obtainStyledAttributes(attrs, R.styleable.TimePicker,
                defStyleAttr, defStyleRes);

        mTypeface = Typeface.create("sans-serif", Typeface.NORMAL);

        // Initialize all alpha values to opaque.
        for (int i = 0; i < mAlpha.length; i++) {
            mAlpha[i] = new IntHolder(ALPHA_OPAQUE);
        }
        for (int i = 0; i < mAlphaSelector.length; i++) {
            for (int j = 0; j < mAlphaSelector[i].length; j++) {
                mAlphaSelector[i][j] = new IntHolder(ALPHA_OPAQUE);
            }
        }

        final int numbersTextColor = a.getColor(R.styleable.TimePicker_numbersTextColor,
                res.getColor(R.color.timepicker_default_text_color_material));

        mPaint[HOURS] = new Paint();
        mPaint[HOURS].setAntiAlias(true);
        mPaint[HOURS].setTextAlign(Paint.Align.CENTER);
        mColor[HOURS] = numbersTextColor;

        mPaint[MINUTES] = new Paint();
        mPaint[MINUTES].setAntiAlias(true);
        mPaint[MINUTES].setTextAlign(Paint.Align.CENTER);
        mColor[MINUTES] = numbersTextColor;

        mPaintCenter.setColor(numbersTextColor);
        mPaintCenter.setAntiAlias(true);
        mPaintCenter.setTextAlign(Paint.Align.CENTER);

        mPaintSelector[HOURS][SELECTOR_CIRCLE] = new Paint();
        mPaintSelector[HOURS][SELECTOR_CIRCLE].setAntiAlias(true);
        mColorSelector[HOURS][SELECTOR_CIRCLE] = a.getColor(
                R.styleable.TimePicker_numbersSelectorColor,
                R.color.timepicker_default_selector_color_material);

        mPaintSelector[HOURS][SELECTOR_DOT] = new Paint();
        mPaintSelector[HOURS][SELECTOR_DOT].setAntiAlias(true);
        mColorSelector[HOURS][SELECTOR_DOT] = a.getColor(
                R.styleable.TimePicker_numbersSelectorColor,
                R.color.timepicker_default_selector_color_material);

        mPaintSelector[HOURS][SELECTOR_LINE] = new Paint();
        mPaintSelector[HOURS][SELECTOR_LINE].setAntiAlias(true);
        mPaintSelector[HOURS][SELECTOR_LINE].setStrokeWidth(2);
        mColorSelector[HOURS][SELECTOR_LINE] = a.getColor(
                R.styleable.TimePicker_numbersSelectorColor,
                R.color.timepicker_default_selector_color_material);

        mPaintSelector[MINUTES][SELECTOR_CIRCLE] = new Paint();
        mPaintSelector[MINUTES][SELECTOR_CIRCLE].setAntiAlias(true);
        mColorSelector[MINUTES][SELECTOR_CIRCLE] = a.getColor(
                R.styleable.TimePicker_numbersSelectorColor,
                R.color.timepicker_default_selector_color_material);

        mPaintSelector[MINUTES][SELECTOR_DOT] = new Paint();
        mPaintSelector[MINUTES][SELECTOR_DOT].setAntiAlias(true);
        mColorSelector[MINUTES][SELECTOR_DOT] = a.getColor(
                R.styleable.TimePicker_numbersSelectorColor,
                R.color.timepicker_default_selector_color_material);

        mPaintSelector[MINUTES][SELECTOR_LINE] = new Paint();
        mPaintSelector[MINUTES][SELECTOR_LINE].setAntiAlias(true);
        mPaintSelector[MINUTES][SELECTOR_LINE].setStrokeWidth(2);
        mColorSelector[MINUTES][SELECTOR_LINE] = a.getColor(
                R.styleable.TimePicker_numbersSelectorColor,
                R.color.timepicker_default_selector_color_material);

        mPaintBackground.setColor(a.getColor(R.styleable.TimePicker_numbersBackgroundColor,
                res.getColor(R.color.timepicker_default_numbers_background_color_material)));
        mPaintBackground.setAntiAlias(true);

        if (DEBUG) {
            mPaintDebug.setColor(DEBUG_COLOR);
            mPaintDebug.setAntiAlias(true);
            mPaintDebug.setStrokeWidth(DEBUG_STROKE_WIDTH);
            mPaintDebug.setStyle(Paint.Style.STROKE);
            mPaintDebug.setTextAlign(Paint.Align.CENTER);
        }

        mShowHours = true;
        mIs24HourMode = false;
        mAmOrPm = AM;

        // Set up accessibility components.
        mTouchHelper = new RadialPickerTouchHelper();
        setAccessibilityDelegate(mTouchHelper);

        if (getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        initHoursAndMinutesText();
        initData();

        mTransitionMidRadiusMultiplier =  Float.parseFloat(
                res.getString(R.string.timepicker_transition_mid_radius_multiplier));
        mTransitionEndRadiusMultiplier = Float.parseFloat(
                res.getString(R.string.timepicker_transition_end_radius_multiplier));

        mTextGridHeights[HOURS] = new float[7];
        mTextGridHeights[MINUTES] = new float[7];

        mSelectionRadiusMultiplier = Float.parseFloat(
                res.getString(R.string.timepicker_selection_radius_multiplier));

        a.recycle();

        setOnTouchListener(this);
        setClickable(true);

        // Initial values
        final Calendar calendar = Calendar.getInstance(Locale.getDefault());
        final int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        final int currentMinute = calendar.get(Calendar.MINUTE);

        setCurrentHourInternal(currentHour, false, false);
        setCurrentMinuteInternal(currentMinute, false);

        setHapticFeedbackEnabled(true);
    }

    /**
     * Measure the view to end up as a square, based on the minimum of the height and width.
     */
    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int measuredWidth = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int measuredHeight = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int minDimension = Math.min(measuredWidth, measuredHeight);

        super.onMeasure(MeasureSpec.makeMeasureSpec(minDimension, widthMode),
                MeasureSpec.makeMeasureSpec(minDimension, heightMode));
    }

    public void initialize(int hour, int minute, boolean is24HourMode) {
        if (mIs24HourMode != is24HourMode) {
            mIs24HourMode = is24HourMode;
            initData();
        }

        setCurrentHourInternal(hour, false, false);
        setCurrentMinuteInternal(minute, false);
    }

    public void setCurrentItemShowing(int item, boolean animate) {
        switch (item){
            case HOURS:
                showHours(animate);
                break;
            case MINUTES:
                showMinutes(animate);
                break;
            default:
                Log.e(TAG, "ClockView does not support showing item " + item);
        }
    }

    public int getCurrentItemShowing() {
        return mShowHours ? HOURS : MINUTES;
    }

    public void setOnValueSelectedListener(OnValueSelectedListener listener) {
        mListener = listener;
    }

    /**
     * Sets the current hour in 24-hour time.
     *
     * @param hour the current hour between 0 and 23 (inclusive)
     */
    public void setCurrentHour(int hour) {
        setCurrentHourInternal(hour, true, false);
    }

    /**
     * Sets the current hour.
     *
     * @param hour The current hour
     * @param callback Whether the value listener should be invoked
     * @param autoAdvance Whether the listener should auto-advance to the next
     *                    selection mode, e.g. hour to minutes
     */
    private void setCurrentHourInternal(int hour, boolean callback, boolean autoAdvance) {
        final int degrees = (hour % 12) * DEGREES_FOR_ONE_HOUR;
        mSelectionDegrees[HOURS] = degrees;
        mSelectionDegrees[HOURS_INNER] = degrees;

        // 0 is 12 AM (midnight) and 12 is 12 PM (noon).
        final int amOrPm = (hour == 0 || (hour % 24) < 12) ? AM : PM;
        final boolean isOnInnerCircle = mIs24HourMode && hour >= 1 && hour <= 12;
        if (mAmOrPm != amOrPm || mIsOnInnerCircle != isOnInnerCircle) {
            mAmOrPm = amOrPm;
            mIsOnInnerCircle = isOnInnerCircle;

            initData();
            updateLayoutData();
            mTouchHelper.invalidateRoot();
        }

        invalidate();

        if (callback && mListener != null) {
            mListener.onValueSelected(HOURS, hour, autoAdvance);
        }
    }

    /**
     * Returns the current hour in 24-hour time.
     *
     * @return the current hour between 0 and 23 (inclusive)
     */
    public int getCurrentHour() {
        return getHourForDegrees(
                mSelectionDegrees[mIsOnInnerCircle ? HOURS_INNER : HOURS], mIsOnInnerCircle);
    }

    private int getHourForDegrees(int degrees, boolean innerCircle) {
        int hour = (degrees / DEGREES_FOR_ONE_HOUR) % 12;
        if (mIs24HourMode) {
            // Convert the 12-hour value into 24-hour time based on where the
            // selector is positioned.
            if (innerCircle && hour == 0) {
                // Inner circle is 1 through 12.
                hour = 12;
            } else if (!innerCircle && hour != 0) {
                // Outer circle is 13 through 23 and 0.
                hour += 12;
            }
        } else if (mAmOrPm == PM) {
            hour += 12;
        }
        return hour;
    }

    private int getDegreesForHour(int hour) {
        // Convert to be 0-11.
        if (mIs24HourMode) {
            if (hour >= 12) {
                hour -= 12;
            }
        } else if (hour == 12) {
            hour = 0;
        }
        return hour * DEGREES_FOR_ONE_HOUR;
    }

    public void setCurrentMinute(int minute) {
        setCurrentMinuteInternal(minute, true);
    }

    private void setCurrentMinuteInternal(int minute, boolean callback) {
        mSelectionDegrees[MINUTES] = (minute % 60) * DEGREES_FOR_ONE_MINUTE;

        invalidate();

        if (callback && mListener != null) {
            mListener.onValueSelected(MINUTES, minute, false);
        }
    }

    // Returns minutes in 0-59 range
    public int getCurrentMinute() {
        return getMinuteForDegrees(mSelectionDegrees[MINUTES]);
    }

    private int getMinuteForDegrees(int degrees) {
        return degrees / DEGREES_FOR_ONE_MINUTE;
    }

    private int getDegreesForMinute(int minute) {
        return minute * DEGREES_FOR_ONE_MINUTE;
    }

    public void setAmOrPm(int val) {
        mAmOrPm = (val % 2);
        invalidate();
        mTouchHelper.invalidateRoot();
    }

    public int getAmOrPm() {
        return mAmOrPm;
    }

    public void showHours(boolean animate) {
        if (mShowHours) return;
        mShowHours = true;
        if (animate) {
            startMinutesToHoursAnimation();
        }
        initData();
        updateLayoutData();
        invalidate();
    }

    public void showMinutes(boolean animate) {
        if (!mShowHours) return;
        mShowHours = false;
        if (animate) {
            startHoursToMinutesAnimation();
        }
        initData();
        updateLayoutData();
        invalidate();
    }

    private void initHoursAndMinutesText() {
        // Initialize the hours and minutes numbers.
        for (int i = 0; i < 12; i++) {
            mHours12Texts[i] = String.format("%d", HOURS_NUMBERS[i]);
            mOuterHours24Texts[i] = String.format("%02d", HOURS_NUMBERS_24[i]);
            mInnerHours24Texts[i] = String.format("%d", HOURS_NUMBERS[i]);
            mMinutesTexts[i] = String.format("%02d", MINUTES_NUMBERS[i]);
        }
    }

    private void initData() {
        if (mIs24HourMode) {
            mOuterTextHours = mOuterHours24Texts;
            mInnerTextHours = mInnerHours24Texts;
        } else {
            mOuterTextHours = mHours12Texts;
            mInnerTextHours = null;
        }

        mOuterTextMinutes = mMinutesTexts;

        final Resources res = getResources();

        if (mShowHours) {
            if (mIs24HourMode) {
                mCircleRadiusMultiplier[HOURS] = Float.parseFloat(
                        res.getString(R.string.timepicker_circle_radius_multiplier_24HourMode));
                mNumbersRadiusMultiplier[HOURS] = Float.parseFloat(
                        res.getString(R.string.timepicker_numbers_radius_multiplier_outer));
                mTextSizeMultiplier[HOURS] = Float.parseFloat(
                        res.getString(R.string.timepicker_text_size_multiplier_outer));

                mNumbersRadiusMultiplier[HOURS_INNER] = Float.parseFloat(
                        res.getString(R.string.timepicker_numbers_radius_multiplier_inner));
                mTextSizeMultiplier[HOURS_INNER] = Float.parseFloat(
                        res.getString(R.string.timepicker_text_size_multiplier_inner));
            } else {
                mCircleRadiusMultiplier[HOURS] = Float.parseFloat(
                        res.getString(R.string.timepicker_circle_radius_multiplier));
                mNumbersRadiusMultiplier[HOURS] = Float.parseFloat(
                        res.getString(R.string.timepicker_numbers_radius_multiplier_normal));
                mTextSizeMultiplier[HOURS] = Float.parseFloat(
                        res.getString(R.string.timepicker_text_size_multiplier_normal));
            }
        } else {
            mCircleRadiusMultiplier[MINUTES] = Float.parseFloat(
                    res.getString(R.string.timepicker_circle_radius_multiplier));
            mNumbersRadiusMultiplier[MINUTES] = Float.parseFloat(
                    res.getString(R.string.timepicker_numbers_radius_multiplier_normal));
            mTextSizeMultiplier[MINUTES] = Float.parseFloat(
                    res.getString(R.string.timepicker_text_size_multiplier_normal));
        }

        mAnimationRadiusMultiplier[HOURS] = 1;
        mAnimationRadiusMultiplier[HOURS_INNER] = 1;
        mAnimationRadiusMultiplier[MINUTES] = 1;

        mAlpha[HOURS].setValue(mShowHours ? ALPHA_OPAQUE : ALPHA_TRANSPARENT);
        mAlpha[MINUTES].setValue(mShowHours ? ALPHA_TRANSPARENT : ALPHA_OPAQUE);

        mAlphaSelector[HOURS][SELECTOR_CIRCLE].setValue(
                mShowHours ? ALPHA_SELECTOR : ALPHA_TRANSPARENT);
        mAlphaSelector[HOURS][SELECTOR_DOT].setValue(
                mShowHours ? ALPHA_OPAQUE : ALPHA_TRANSPARENT);
        mAlphaSelector[HOURS][SELECTOR_LINE].setValue(
                mShowHours ? ALPHA_SELECTOR : ALPHA_TRANSPARENT);

        mAlphaSelector[MINUTES][SELECTOR_CIRCLE].setValue(
                mShowHours ? ALPHA_TRANSPARENT : ALPHA_SELECTOR);
        mAlphaSelector[MINUTES][SELECTOR_DOT].setValue(
                mShowHours ? ALPHA_TRANSPARENT : ALPHA_OPAQUE);
        mAlphaSelector[MINUTES][SELECTOR_LINE].setValue(
                mShowHours ? ALPHA_TRANSPARENT : ALPHA_SELECTOR);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        updateLayoutData();
    }

    private void updateLayoutData() {
        mXCenter = getWidth() / 2;
        mYCenter = getHeight() / 2;

        final int min = Math.min(mXCenter, mYCenter);

        mCircleRadius[HOURS] = min * mCircleRadiusMultiplier[HOURS];
        mCircleRadius[HOURS_INNER] = min * mCircleRadiusMultiplier[HOURS];
        mCircleRadius[MINUTES] = min * mCircleRadiusMultiplier[MINUTES];

        mMinHypotenuseForInnerNumber = (int) (mCircleRadius[HOURS]
                * mNumbersRadiusMultiplier[HOURS_INNER]) - mSelectionRadius[HOURS];
        mMaxHypotenuseForOuterNumber = (int) (mCircleRadius[HOURS]
                * mNumbersRadiusMultiplier[HOURS]) + mSelectionRadius[HOURS];
        mHalfwayHypotenusePoint = (int) (mCircleRadius[HOURS]
                * ((mNumbersRadiusMultiplier[HOURS] + mNumbersRadiusMultiplier[HOURS_INNER]) / 2));

        mTextSize[HOURS] = mCircleRadius[HOURS] * mTextSizeMultiplier[HOURS];
        mTextSize[MINUTES] = mCircleRadius[MINUTES] * mTextSizeMultiplier[MINUTES];

        if (mIs24HourMode) {
            mInnerTextSize = mCircleRadius[HOURS] * mTextSizeMultiplier[HOURS_INNER];
        }

        calculateGridSizesHours();
        calculateGridSizesMinutes();

        mSelectionRadius[HOURS] = (int) (mCircleRadius[HOURS] * mSelectionRadiusMultiplier);
        mSelectionRadius[HOURS_INNER] = mSelectionRadius[HOURS];
        mSelectionRadius[MINUTES] = (int) (mCircleRadius[MINUTES] * mSelectionRadiusMultiplier);

        mTouchHelper.invalidateRoot();
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (!mInputEnabled) {
            canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), mDisabledAlpha);
        } else {
            canvas.save();
        }

        calculateGridSizesHours();
        calculateGridSizesMinutes();

        drawCircleBackground(canvas);
        drawSelector(canvas);

        drawTextElements(canvas, mTextSize[HOURS], mTypeface, mOuterTextHours,
                mTextGridWidths[HOURS], mTextGridHeights[HOURS], mPaint[HOURS],
                mColor[HOURS], mAlpha[HOURS].getValue());

        if (mIs24HourMode && mInnerTextHours != null) {
            drawTextElements(canvas, mInnerTextSize, mTypeface, mInnerTextHours,
                    mInnerTextGridWidths, mInnerTextGridHeights, mPaint[HOURS],
                    mColor[HOURS], mAlpha[HOURS].getValue());
        }

        drawTextElements(canvas, mTextSize[MINUTES], mTypeface, mOuterTextMinutes,
                mTextGridWidths[MINUTES], mTextGridHeights[MINUTES], mPaint[MINUTES],
                mColor[MINUTES], mAlpha[MINUTES].getValue());

        drawCenter(canvas);

        if (DEBUG) {
            drawDebug(canvas);
        }

        canvas.restore();
    }

    private void drawCircleBackground(Canvas canvas) {
        canvas.drawCircle(mXCenter, mYCenter, mCircleRadius[HOURS], mPaintBackground);
    }

    private void drawCenter(Canvas canvas) {
        canvas.drawCircle(mXCenter, mYCenter, CENTER_RADIUS, mPaintCenter);
    }

    private void drawSelector(Canvas canvas) {
        drawSelector(canvas, mIsOnInnerCircle ? HOURS_INNER : HOURS);
        drawSelector(canvas, MINUTES);
    }

    private int getMultipliedAlpha(int argb, int alpha) {
        return (int) (Color.alpha(argb) * (alpha / 255.0) + 0.5);
    }

    private void drawSelector(Canvas canvas, int index) {
        // Calculate the current radius at which to place the selection circle.
        mLineLength[index] = (int) (mCircleRadius[index]
                * mNumbersRadiusMultiplier[index] * mAnimationRadiusMultiplier[index]);

        double selectionRadians = Math.toRadians(mSelectionDegrees[index]);

        int pointX = mXCenter + (int) (mLineLength[index] * Math.sin(selectionRadians));
        int pointY = mYCenter - (int) (mLineLength[index] * Math.cos(selectionRadians));

        int color;
        int alpha;
        Paint paint;

        // Draw the selection circle
        color = mColorSelector[index % 2][SELECTOR_CIRCLE];
        alpha = mAlphaSelector[index % 2][SELECTOR_CIRCLE].getValue();
        paint = mPaintSelector[index % 2][SELECTOR_CIRCLE];
        paint.setColor(color);
        paint.setAlpha(getMultipliedAlpha(color, alpha));
        canvas.drawCircle(pointX, pointY, mSelectionRadius[index], paint);

        // Draw the dot if needed
        if (mSelectionDegrees[index] % 30 != 0) {
            // We're not on a direct tick
            color = mColorSelector[index % 2][SELECTOR_DOT];
            alpha = mAlphaSelector[index % 2][SELECTOR_DOT].getValue();
            paint = mPaintSelector[index % 2][SELECTOR_DOT];
            paint.setColor(color);
            paint.setAlpha(getMultipliedAlpha(color, alpha));
            canvas.drawCircle(pointX, pointY, (mSelectionRadius[index] * 2 / 7), paint);
        } else {
            // We're not drawing the dot, so shorten the line to only go as far as the edge of the
            // selection circle
            int lineLength = mLineLength[index] - mSelectionRadius[index];
            pointX = mXCenter + (int) (lineLength * Math.sin(selectionRadians));
            pointY = mYCenter - (int) (lineLength * Math.cos(selectionRadians));
        }

        // Draw the line
        color = mColorSelector[index % 2][SELECTOR_LINE];
        alpha = mAlphaSelector[index % 2][SELECTOR_LINE].getValue();
        paint = mPaintSelector[index % 2][SELECTOR_LINE];
        paint.setColor(color);
        paint.setAlpha(getMultipliedAlpha(color, alpha));
        canvas.drawLine(mXCenter, mYCenter, pointX, pointY, paint);
    }

    private void drawDebug(Canvas canvas) {
        // Draw outer numbers circle
        final float outerRadius = mCircleRadius[HOURS] * mNumbersRadiusMultiplier[HOURS];
        canvas.drawCircle(mXCenter, mYCenter, outerRadius, mPaintDebug);

        // Draw inner numbers circle
        final float innerRadius = mCircleRadius[HOURS] * mNumbersRadiusMultiplier[HOURS_INNER];
        canvas.drawCircle(mXCenter, mYCenter, innerRadius, mPaintDebug);

        // Draw outer background circle
        canvas.drawCircle(mXCenter, mYCenter, mCircleRadius[HOURS], mPaintDebug);

        // Draw outer rectangle for circles
        float left = mXCenter - outerRadius;
        float top = mYCenter - outerRadius;
        float right = mXCenter + outerRadius;
        float bottom = mYCenter + outerRadius;
        canvas.drawRect(left, top, right, bottom, mPaintDebug);

        // Draw outer rectangle for background
        left = mXCenter - mCircleRadius[HOURS];
        top = mYCenter - mCircleRadius[HOURS];
        right = mXCenter + mCircleRadius[HOURS];
        bottom = mYCenter + mCircleRadius[HOURS];
        canvas.drawRect(left, top, right, bottom, mPaintDebug);

        // Draw outer view rectangle
        canvas.drawRect(0, 0, getWidth(), getHeight(), mPaintDebug);

        // Draw selected time
        final String selected = String.format("%02d:%02d", getCurrentHour(), getCurrentMinute());

        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        TextView tv = new TextView(getContext());
        tv.setLayoutParams(lp);
        tv.setText(selected);
        tv.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        Paint paint = tv.getPaint();
        paint.setColor(DEBUG_TEXT_COLOR);

        final int width = tv.getMeasuredWidth();

        float height = paint.descent() - paint.ascent();
        float x = mXCenter - width / 2;
        float y = mYCenter + 1.5f * height;

        canvas.drawText(selected, x, y, paint);
    }

    private void calculateGridSizesHours() {
        // Calculate the text positions
        float numbersRadius = mCircleRadius[HOURS]
                * mNumbersRadiusMultiplier[HOURS] * mAnimationRadiusMultiplier[HOURS];

        // Calculate the positions for the 12 numbers in the main circle.
        calculateGridSizes(mPaint[HOURS], numbersRadius, mXCenter, mYCenter,
                mTextSize[HOURS], mTextGridHeights[HOURS], mTextGridWidths[HOURS]);

        // If we have an inner circle, calculate those positions too.
        if (mIs24HourMode) {
            float innerNumbersRadius = mCircleRadius[HOURS_INNER]
                    * mNumbersRadiusMultiplier[HOURS_INNER]
                    * mAnimationRadiusMultiplier[HOURS_INNER];

            calculateGridSizes(mPaint[HOURS], innerNumbersRadius, mXCenter, mYCenter,
                    mInnerTextSize, mInnerTextGridHeights, mInnerTextGridWidths);
        }
    }

    private void calculateGridSizesMinutes() {
        // Calculate the text positions
        float numbersRadius = mCircleRadius[MINUTES]
                * mNumbersRadiusMultiplier[MINUTES] * mAnimationRadiusMultiplier[MINUTES];

        // Calculate the positions for the 12 numbers in the main circle.
        calculateGridSizes(mPaint[MINUTES], numbersRadius, mXCenter, mYCenter,
                mTextSize[MINUTES], mTextGridHeights[MINUTES], mTextGridWidths[MINUTES]);
    }


    /**
     * Using the trigonometric Unit Circle, calculate the positions that the text will need to be
     * drawn at based on the specified circle radius. Place the values in the textGridHeights and
     * textGridWidths parameters.
     */
    private static void calculateGridSizes(Paint paint, float numbersRadius, float xCenter,
            float yCenter, float textSize, float[] textGridHeights, float[] textGridWidths) {
        /*
         * The numbers need to be drawn in a 7x7 grid, representing the points on the Unit Circle.
         */
        final float offset1 = numbersRadius;
        // cos(30) = a / r => r * cos(30)
        final float offset2 = numbersRadius * COSINE_30_DEGREES;
        // sin(30) = o / r => r * sin(30)
        final float offset3 = numbersRadius * SINE_30_DEGREES;

        paint.setTextSize(textSize);
        // We'll need yTextBase to be slightly lower to account for the text's baseline.
        yCenter -= (paint.descent() + paint.ascent()) / 2;

        textGridHeights[0] = yCenter - offset1;
        textGridWidths[0] = xCenter - offset1;
        textGridHeights[1] = yCenter - offset2;
        textGridWidths[1] = xCenter - offset2;
        textGridHeights[2] = yCenter - offset3;
        textGridWidths[2] = xCenter - offset3;
        textGridHeights[3] = yCenter;
        textGridWidths[3] = xCenter;
        textGridHeights[4] = yCenter + offset3;
        textGridWidths[4] = xCenter + offset3;
        textGridHeights[5] = yCenter + offset2;
        textGridWidths[5] = xCenter + offset2;
        textGridHeights[6] = yCenter + offset1;
        textGridWidths[6] = xCenter + offset1;
    }

    /**
     * Draw the 12 text values at the positions specified by the textGrid parameters.
     */
    private void drawTextElements(Canvas canvas, float textSize, Typeface typeface, String[] texts,
            float[] textGridWidths, float[] textGridHeights, Paint paint, int color, int alpha) {
        paint.setTextSize(textSize);
        paint.setTypeface(typeface);
        paint.setColor(color);
        paint.setAlpha(getMultipliedAlpha(color, alpha));
        canvas.drawText(texts[0], textGridWidths[3], textGridHeights[0], paint);
        canvas.drawText(texts[1], textGridWidths[4], textGridHeights[1], paint);
        canvas.drawText(texts[2], textGridWidths[5], textGridHeights[2], paint);
        canvas.drawText(texts[3], textGridWidths[6], textGridHeights[3], paint);
        canvas.drawText(texts[4], textGridWidths[5], textGridHeights[4], paint);
        canvas.drawText(texts[5], textGridWidths[4], textGridHeights[5], paint);
        canvas.drawText(texts[6], textGridWidths[3], textGridHeights[6], paint);
        canvas.drawText(texts[7], textGridWidths[2], textGridHeights[5], paint);
        canvas.drawText(texts[8], textGridWidths[1], textGridHeights[4], paint);
        canvas.drawText(texts[9], textGridWidths[0], textGridHeights[3], paint);
        canvas.drawText(texts[10], textGridWidths[1], textGridHeights[2], paint);
        canvas.drawText(texts[11], textGridWidths[2], textGridHeights[1], paint);
    }

    // Used for animating the hours by changing their radius
    @SuppressWarnings("unused")
    private void setAnimationRadiusMultiplierHours(float animationRadiusMultiplier) {
        mAnimationRadiusMultiplier[HOURS] = animationRadiusMultiplier;
        mAnimationRadiusMultiplier[HOURS_INNER] = animationRadiusMultiplier;
    }

    // Used for animating the minutes by changing their radius
    @SuppressWarnings("unused")
    private void setAnimationRadiusMultiplierMinutes(float animationRadiusMultiplier) {
        mAnimationRadiusMultiplier[MINUTES] = animationRadiusMultiplier;
    }

    private static ObjectAnimator getRadiusDisappearAnimator(Object target,
            String radiusPropertyName, InvalidateUpdateListener updateListener,
            float midRadiusMultiplier, float endRadiusMultiplier) {
        Keyframe kf0, kf1, kf2;
        float midwayPoint = 0.2f;
        int duration = 500;

        kf0 = Keyframe.ofFloat(0f, 1);
        kf1 = Keyframe.ofFloat(midwayPoint, midRadiusMultiplier);
        kf2 = Keyframe.ofFloat(1f, endRadiusMultiplier);
        PropertyValuesHolder radiusDisappear = PropertyValuesHolder.ofKeyframe(
                radiusPropertyName, kf0, kf1, kf2);

        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(
                target, radiusDisappear).setDuration(duration);
        animator.addUpdateListener(updateListener);
        return animator;
    }

    private static ObjectAnimator getRadiusReappearAnimator(Object target,
            String radiusPropertyName, InvalidateUpdateListener updateListener,
            float midRadiusMultiplier, float endRadiusMultiplier) {
        Keyframe kf0, kf1, kf2, kf3;
        float midwayPoint = 0.2f;
        int duration = 500;

        // Set up animator for reappearing.
        float delayMultiplier = 0.25f;
        float transitionDurationMultiplier = 1f;
        float totalDurationMultiplier = transitionDurationMultiplier + delayMultiplier;
        int totalDuration = (int) (duration * totalDurationMultiplier);
        float delayPoint = (delayMultiplier * duration) / totalDuration;
        midwayPoint = 1 - (midwayPoint * (1 - delayPoint));

        kf0 = Keyframe.ofFloat(0f, endRadiusMultiplier);
        kf1 = Keyframe.ofFloat(delayPoint, endRadiusMultiplier);
        kf2 = Keyframe.ofFloat(midwayPoint, midRadiusMultiplier);
        kf3 = Keyframe.ofFloat(1f, 1);
        PropertyValuesHolder radiusReappear = PropertyValuesHolder.ofKeyframe(
                radiusPropertyName, kf0, kf1, kf2, kf3);

        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(
                target, radiusReappear).setDuration(totalDuration);
        animator.addUpdateListener(updateListener);
        return animator;
    }

    private static ObjectAnimator getFadeOutAnimator(IntHolder target, int startAlpha, int endAlpha,
                InvalidateUpdateListener updateListener) {
        int duration = 500;
        ObjectAnimator animator = ObjectAnimator.ofInt(target, "value", startAlpha, endAlpha);
        animator.setDuration(duration);
        animator.addUpdateListener(updateListener);

        return animator;
    }

    private static ObjectAnimator getFadeInAnimator(IntHolder target, int startAlpha, int endAlpha,
                InvalidateUpdateListener updateListener) {
        Keyframe kf0, kf1, kf2;
        int duration = 500;

        // Set up animator for reappearing.
        float delayMultiplier = 0.25f;
        float transitionDurationMultiplier = 1f;
        float totalDurationMultiplier = transitionDurationMultiplier + delayMultiplier;
        int totalDuration = (int) (duration * totalDurationMultiplier);
        float delayPoint = (delayMultiplier * duration) / totalDuration;

        kf0 = Keyframe.ofInt(0f, startAlpha);
        kf1 = Keyframe.ofInt(delayPoint, startAlpha);
        kf2 = Keyframe.ofInt(1f, endAlpha);
        PropertyValuesHolder fadeIn = PropertyValuesHolder.ofKeyframe("value", kf0, kf1, kf2);

        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(
                target, fadeIn).setDuration(totalDuration);
        animator.addUpdateListener(updateListener);
        return animator;
    }

    private class InvalidateUpdateListener implements ValueAnimator.AnimatorUpdateListener {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            RadialTimePickerView.this.invalidate();
        }
    }

    private void startHoursToMinutesAnimation() {
        if (mHoursToMinutesAnims.size() == 0) {
            mHoursToMinutesAnims.add(getRadiusDisappearAnimator(this,
                    "animationRadiusMultiplierHours", mInvalidateUpdateListener,
                    mTransitionMidRadiusMultiplier, mTransitionEndRadiusMultiplier));
            mHoursToMinutesAnims.add(getFadeOutAnimator(mAlpha[HOURS],
                    ALPHA_OPAQUE, ALPHA_TRANSPARENT, mInvalidateUpdateListener));
            mHoursToMinutesAnims.add(getFadeOutAnimator(mAlphaSelector[HOURS][SELECTOR_CIRCLE],
                    ALPHA_SELECTOR, ALPHA_TRANSPARENT, mInvalidateUpdateListener));
            mHoursToMinutesAnims.add(getFadeOutAnimator(mAlphaSelector[HOURS][SELECTOR_DOT],
                    ALPHA_OPAQUE, ALPHA_TRANSPARENT, mInvalidateUpdateListener));
            mHoursToMinutesAnims.add(getFadeOutAnimator(mAlphaSelector[HOURS][SELECTOR_LINE],
                    ALPHA_SELECTOR, ALPHA_TRANSPARENT, mInvalidateUpdateListener));

            mHoursToMinutesAnims.add(getRadiusReappearAnimator(this,
                    "animationRadiusMultiplierMinutes", mInvalidateUpdateListener,
                    mTransitionMidRadiusMultiplier, mTransitionEndRadiusMultiplier));
            mHoursToMinutesAnims.add(getFadeInAnimator(mAlpha[MINUTES],
                    ALPHA_TRANSPARENT, ALPHA_OPAQUE, mInvalidateUpdateListener));
            mHoursToMinutesAnims.add(getFadeInAnimator(mAlphaSelector[MINUTES][SELECTOR_CIRCLE],
                    ALPHA_TRANSPARENT, ALPHA_SELECTOR, mInvalidateUpdateListener));
            mHoursToMinutesAnims.add(getFadeInAnimator(mAlphaSelector[MINUTES][SELECTOR_DOT],
                    ALPHA_TRANSPARENT, ALPHA_OPAQUE, mInvalidateUpdateListener));
            mHoursToMinutesAnims.add(getFadeInAnimator(mAlphaSelector[MINUTES][SELECTOR_LINE],
                    ALPHA_TRANSPARENT, ALPHA_SELECTOR, mInvalidateUpdateListener));
        }

        if (mTransition != null && mTransition.isRunning()) {
            mTransition.end();
        }
        mTransition = new AnimatorSet();
        mTransition.playTogether(mHoursToMinutesAnims);
        mTransition.start();
    }

    private void startMinutesToHoursAnimation() {
        if (mMinuteToHoursAnims.size() == 0) {
            mMinuteToHoursAnims.add(getRadiusDisappearAnimator(this,
                    "animationRadiusMultiplierMinutes", mInvalidateUpdateListener,
                    mTransitionMidRadiusMultiplier, mTransitionEndRadiusMultiplier));
            mMinuteToHoursAnims.add(getFadeOutAnimator(mAlpha[MINUTES],
                    ALPHA_OPAQUE, ALPHA_TRANSPARENT, mInvalidateUpdateListener));
            mMinuteToHoursAnims.add(getFadeOutAnimator(mAlphaSelector[MINUTES][SELECTOR_CIRCLE],
                    ALPHA_SELECTOR, ALPHA_TRANSPARENT, mInvalidateUpdateListener));
            mMinuteToHoursAnims.add(getFadeOutAnimator(mAlphaSelector[MINUTES][SELECTOR_DOT],
                    ALPHA_OPAQUE, ALPHA_TRANSPARENT, mInvalidateUpdateListener));
            mMinuteToHoursAnims.add(getFadeOutAnimator(mAlphaSelector[MINUTES][SELECTOR_LINE],
                    ALPHA_SELECTOR, ALPHA_TRANSPARENT, mInvalidateUpdateListener));

            mMinuteToHoursAnims.add(getRadiusReappearAnimator(this,
                    "animationRadiusMultiplierHours", mInvalidateUpdateListener,
                    mTransitionMidRadiusMultiplier, mTransitionEndRadiusMultiplier));
            mMinuteToHoursAnims.add(getFadeInAnimator(mAlpha[HOURS],
                    ALPHA_TRANSPARENT, ALPHA_OPAQUE, mInvalidateUpdateListener));
            mMinuteToHoursAnims.add(getFadeInAnimator(mAlphaSelector[HOURS][SELECTOR_CIRCLE],
                    ALPHA_TRANSPARENT, ALPHA_SELECTOR, mInvalidateUpdateListener));
            mMinuteToHoursAnims.add(getFadeInAnimator(mAlphaSelector[HOURS][SELECTOR_DOT],
                    ALPHA_TRANSPARENT, ALPHA_OPAQUE, mInvalidateUpdateListener));
            mMinuteToHoursAnims.add(getFadeInAnimator(mAlphaSelector[HOURS][SELECTOR_LINE],
                    ALPHA_TRANSPARENT, ALPHA_SELECTOR, mInvalidateUpdateListener));
        }

        if (mTransition != null && mTransition.isRunning()) {
            mTransition.end();
        }
        mTransition = new AnimatorSet();
        mTransition.playTogether(mMinuteToHoursAnims);
        mTransition.start();
    }

    private int getDegreesFromXY(float x, float y) {
        final double hypotenuse = Math.sqrt(
                (y - mYCenter) * (y - mYCenter) + (x - mXCenter) * (x - mXCenter));

        // Basic check if we're outside the range of the disk
        if (hypotenuse > mCircleRadius[HOURS]) {
            return -1;
        }
        // Check
        if (mIs24HourMode && mShowHours) {
            if (hypotenuse >= mMinHypotenuseForInnerNumber
                    && hypotenuse <= mHalfwayHypotenusePoint) {
                mIsOnInnerCircle = true;
            } else if (hypotenuse <= mMaxHypotenuseForOuterNumber
                    && hypotenuse >= mHalfwayHypotenusePoint) {
                mIsOnInnerCircle = false;
            } else {
                return -1;
            }
        } else {
            final int index =  (mShowHours) ? HOURS : MINUTES;
            final float length = (mCircleRadius[index] * mNumbersRadiusMultiplier[index]);
            final int distanceToNumber = (int) Math.abs(hypotenuse - length);
            final int maxAllowedDistance =
                    (int) (mCircleRadius[index] * (1 - mNumbersRadiusMultiplier[index]));
            if (distanceToNumber > maxAllowedDistance) {
                return -1;
            }
        }

        final float opposite = Math.abs(y - mYCenter);
        int degrees = (int) (Math.toDegrees(Math.asin(opposite / hypotenuse)) + 0.5);

        // Now we have to translate to the correct quadrant.
        final boolean rightSide = (x > mXCenter);
        final boolean topSide = (y < mYCenter);
        if (rightSide) {
            if (topSide) {
                degrees = 90 - degrees;
            } else {
                degrees = 90 + degrees;
            }
        } else {
            if (topSide) {
                degrees = 270 + degrees;
            } else {
                degrees = 270 - degrees;
            }
        }
        return degrees;
    }

    boolean mChangedDuringTouch = false;

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (!mInputEnabled) {
            return true;
        }

        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_DOWN) {
            boolean forceSelection = false;
            boolean autoAdvance = false;

            if (action == MotionEvent.ACTION_DOWN) {
                // This is a new event stream, reset whether the value changed.
                mChangedDuringTouch = false;
            } else if (action == MotionEvent.ACTION_UP) {
                autoAdvance = true;

                // If we saw a down/up pair without the value changing, assume
                // this is a single-tap selection and force a change.
                if (!mChangedDuringTouch) {
                    forceSelection = true;
                }
            }

            mChangedDuringTouch |= handleTouchInput(
                    event.getX(), event.getY(), forceSelection, autoAdvance);
        }

        return true;
    }

    private boolean handleTouchInput(
            float x, float y, boolean forceSelection, boolean autoAdvance) {
        // Calling getDegreesFromXY has side effects, so cache
        // whether we used to be on the inner circle.
        final boolean wasOnInnerCircle = mIsOnInnerCircle;
        final int degrees = getDegreesFromXY(x, y);
        if (degrees == -1) {
            return false;
        }

        final int[] selectionDegrees = mSelectionDegrees;
        final int type;
        final int newValue;
        final boolean valueChanged;

        if (mShowHours) {
            final int snapDegrees = snapOnly30s(degrees, 0) % 360;
            valueChanged = selectionDegrees[HOURS] != snapDegrees
                    || selectionDegrees[HOURS_INNER] != snapDegrees
                    || wasOnInnerCircle != mIsOnInnerCircle;

            selectionDegrees[HOURS] = snapDegrees;
            selectionDegrees[HOURS_INNER] = snapDegrees;
            type = HOURS;
            newValue = getCurrentHour();
        } else {
            final int snapDegrees = snapPrefer30s(degrees) % 360;
            valueChanged = selectionDegrees[MINUTES] != snapDegrees;

            selectionDegrees[MINUTES] = snapDegrees;
            type = MINUTES;
            newValue = getCurrentMinute();
        }

        if (valueChanged || forceSelection || autoAdvance) {
            // Fire the listener even if we just need to auto-advance.
            if (mListener != null) {
                mListener.onValueSelected(type, newValue, autoAdvance);
            }

            // Only provide feedback if the value actually changed.
            if (valueChanged || forceSelection) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // First right-of-refusal goes the touch exploration helper.
        if (mTouchHelper.dispatchHoverEvent(event)) {
            return true;
        }
        return super.dispatchHoverEvent(event);
    }

    public void setInputEnabled(boolean inputEnabled) {
        mInputEnabled = inputEnabled;
        invalidate();
    }

    private class RadialPickerTouchHelper extends ExploreByTouchHelper {
        private final Rect mTempRect = new Rect();

        private final int TYPE_HOUR = 1;
        private final int TYPE_MINUTE = 2;

        private final int SHIFT_TYPE = 0;
        private final int MASK_TYPE = 0xF;

        private final int SHIFT_VALUE = 8;
        private final int MASK_VALUE = 0xFF;

        /** Increment in which virtual views are exposed for minutes. */
        private final int MINUTE_INCREMENT = 5;

        public RadialPickerTouchHelper() {
            super(RadialTimePickerView.this);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);

            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (super.performAccessibilityAction(host, action, arguments)) {
                return true;
            }

            switch (action) {
                case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
                    adjustPicker(1);
                    return true;
                case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
                    adjustPicker(-1);
                    return true;
            }

            return false;
        }

        private void adjustPicker(int step) {
            final int stepSize;
            final int initialValue;
            final int maxValue;
            final int minValue;
            if (mShowHours) {
                stepSize = DEGREES_FOR_ONE_HOUR;
                initialValue = getCurrentHour() % 12;

                if (mIs24HourMode) {
                    maxValue = 23;
                    minValue = 0;
                } else {
                    maxValue = 12;
                    minValue = 1;
                }
            } else {
                stepSize = DEGREES_FOR_ONE_MINUTE;
                initialValue = getCurrentMinute();

                maxValue = 55;
                minValue = 0;
            }

            final int steppedValue = snapOnly30s(initialValue * stepSize, step) / stepSize;
            final int clampedValue = MathUtils.constrain(steppedValue, minValue, maxValue);
            if (mShowHours) {
                setCurrentHour(clampedValue);
            } else {
                setCurrentMinute(clampedValue);
            }
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            final int id;

            // Calling getDegreesXY() has side-effects, so we need to cache the
            // current inner circle value and restore after the call.
            final boolean wasOnInnerCircle = mIsOnInnerCircle;
            final int degrees = getDegreesFromXY(x, y);
            final boolean isOnInnerCircle = mIsOnInnerCircle;
            mIsOnInnerCircle = wasOnInnerCircle;

            if (degrees != -1) {
                final int snapDegrees = snapOnly30s(degrees, 0) % 360;
                if (mShowHours) {
                    final int hour24 = getHourForDegrees(snapDegrees, isOnInnerCircle);
                    final int hour = mIs24HourMode ? hour24 : hour24To12(hour24);
                    id = makeId(TYPE_HOUR, hour);
                } else {
                    final int current = getCurrentMinute();
                    final int touched = getMinuteForDegrees(degrees);
                    final int snapped = getMinuteForDegrees(snapDegrees);

                    // If the touched minute is closer to the current minute
                    // than it is to the snapped minute, return current.
                    final int minute;
                    if (Math.abs(current - touched) < Math.abs(snapped - touched)) {
                        minute = current;
                    } else {
                        minute = snapped;
                    }
                    id = makeId(TYPE_MINUTE, minute);
                }
            } else {
                id = INVALID_ID;
            }

            return id;
        }

        @Override
        protected void getVisibleVirtualViews(IntArray virtualViewIds) {
            if (mShowHours) {
                final int min = mIs24HourMode ? 0 : 1;
                final int max = mIs24HourMode ? 23 : 12;
                for (int i = min; i <= max ; i++) {
                    virtualViewIds.add(makeId(TYPE_HOUR, i));
                }
            } else {
                final int current = getCurrentMinute();
                for (int i = 0; i < 60; i += MINUTE_INCREMENT) {
                    virtualViewIds.add(makeId(TYPE_MINUTE, i));

                    // If the current minute falls between two increments,
                    // insert an extra node for it.
                    if (current > i && current < i + MINUTE_INCREMENT) {
                        virtualViewIds.add(makeId(TYPE_MINUTE, current));
                    }
                }
            }
        }

        @Override
        protected void onPopulateEventForVirtualView(int virtualViewId, AccessibilityEvent event) {
            event.setClassName(getClass().getName());

            final int type = getTypeFromId(virtualViewId);
            final int value = getValueFromId(virtualViewId);
            final CharSequence description = getVirtualViewDescription(type, value);
            event.setContentDescription(description);
        }

        @Override
        protected void onPopulateNodeForVirtualView(int virtualViewId, AccessibilityNodeInfo node) {
            node.setClassName(getClass().getName());
            node.addAction(AccessibilityAction.ACTION_CLICK);

            final int type = getTypeFromId(virtualViewId);
            final int value = getValueFromId(virtualViewId);
            final CharSequence description = getVirtualViewDescription(type, value);
            node.setContentDescription(description);

            getBoundsForVirtualView(virtualViewId, mTempRect);
            node.setBoundsInParent(mTempRect);

            final boolean selected = isVirtualViewSelected(type, value);
            node.setSelected(selected);

            final int nextId = getVirtualViewIdAfter(type, value);
            if (nextId != INVALID_ID) {
                node.setTraversalBefore(RadialTimePickerView.this, nextId);
            }
        }

        private int getVirtualViewIdAfter(int type, int value) {
            if (type == TYPE_HOUR) {
                final int nextValue = value + 1;
                final int max = mIs24HourMode ? 23 : 12;
                if (nextValue <= max) {
                    return makeId(type, nextValue);
                }
            } else if (type == TYPE_MINUTE) {
                final int current = getCurrentMinute();
                final int snapValue = value - (value % MINUTE_INCREMENT);
                final int nextValue = snapValue + MINUTE_INCREMENT;
                if (value < current && nextValue > current) {
                    // The current value is between two snap values.
                    return makeId(type, current);
                } else if (nextValue < 60) {
                    return makeId(type, nextValue);
                }
            }
            return INVALID_ID;
        }

        @Override
        protected boolean onPerformActionForVirtualView(int virtualViewId, int action,
                Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                final int type = getTypeFromId(virtualViewId);
                final int value = getValueFromId(virtualViewId);
                if (type == TYPE_HOUR) {
                    final int hour = mIs24HourMode ? value : hour12To24(value, mAmOrPm);
                    setCurrentHour(hour);
                    return true;
                } else if (type == TYPE_MINUTE) {
                    setCurrentMinute(value);
                    return true;
                }
            }
            return false;
        }

        private int hour12To24(int hour12, int amOrPm) {
            int hour24 = hour12;
            if (hour12 == 12) {
                if (amOrPm == AM) {
                    hour24 = 0;
                }
            } else if (amOrPm == PM) {
                hour24 += 12;
            }
            return hour24;
        }

        private int hour24To12(int hour24) {
            if (hour24 == 0) {
                return 12;
            } else if (hour24 > 12) {
                return hour24 - 12;
            } else {
                return hour24;
            }
        }

        private void getBoundsForVirtualView(int virtualViewId, Rect bounds) {
            final float radius;
            final int type = getTypeFromId(virtualViewId);
            final int value = getValueFromId(virtualViewId);
            final float centerRadius;
            final float degrees;
            if (type == TYPE_HOUR) {
                final boolean innerCircle = mIs24HourMode && value > 0 && value <= 12;
                if (innerCircle) {
                    centerRadius = mCircleRadius[HOURS_INNER] * mNumbersRadiusMultiplier[HOURS_INNER];
                    radius = mSelectionRadius[HOURS_INNER];
                } else {
                    centerRadius = mCircleRadius[HOURS] * mNumbersRadiusMultiplier[HOURS];
                    radius = mSelectionRadius[HOURS];
                }

                degrees = getDegreesForHour(value);
            } else if (type == TYPE_MINUTE) {
                centerRadius = mCircleRadius[MINUTES] * mNumbersRadiusMultiplier[MINUTES];
                degrees = getDegreesForMinute(value);
                radius = mSelectionRadius[MINUTES];
            } else {
                // This should never happen.
                centerRadius = 0;
                degrees = 0;
                radius = 0;
            }

            final double radians = Math.toRadians(degrees);
            final float xCenter = mXCenter + centerRadius * (float) Math.sin(radians);
            final float yCenter = mYCenter - centerRadius * (float) Math.cos(radians);

            bounds.set((int) (xCenter - radius), (int) (yCenter - radius),
                    (int) (xCenter + radius), (int) (yCenter + radius));
        }

        private CharSequence getVirtualViewDescription(int type, int value) {
            final CharSequence description;
            if (type == TYPE_HOUR || type == TYPE_MINUTE) {
                description = Integer.toString(value);
            } else {
                description = null;
            }
            return description;
        }

        private boolean isVirtualViewSelected(int type, int value) {
            final boolean selected;
            if (type == TYPE_HOUR) {
                selected = getCurrentHour() == value;
            } else if (type == TYPE_MINUTE) {
                selected = getCurrentMinute() == value;
            } else {
                selected = false;
            }
            return selected;
        }

        private int makeId(int type, int value) {
            return type << SHIFT_TYPE | value << SHIFT_VALUE;
        }

        private int getTypeFromId(int id) {
            return id >>> SHIFT_TYPE & MASK_TYPE;
        }

        private int getValueFromId(int id) {
            return id >>> SHIFT_VALUE & MASK_VALUE;
        }
    }

    private static class IntHolder {
        private int mValue;

        public IntHolder(int value) {
            mValue = value;
        }

        public void setValue(int value) {
            mValue = value;
        }

        public int getValue() {
            return mValue;
        }
    }
}
