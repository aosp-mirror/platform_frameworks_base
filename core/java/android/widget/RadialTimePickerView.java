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
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;

import java.text.DateFormatSymbols;
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
    private static final int AMPM = 3;

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

    // Alpha level of color for selected circle.
    private static final int ALPHA_AMPM_SELECTED = ALPHA_SELECTOR;

    // Alpha level of color for pressed circle.
    private static final int ALPHA_AMPM_PRESSED = 255; // was 175

    private static final float COSINE_30_DEGREES = ((float) Math.sqrt(3)) * 0.5f;
    private static final float SINE_30_DEGREES = 0.5f;

    private static final int DEGREES_FOR_ONE_HOUR = 30;
    private static final int DEGREES_FOR_ONE_MINUTE = 6;

    private static final int[] HOURS_NUMBERS = {12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] HOURS_NUMBERS_24 = {0, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23};
    private static final int[] MINUTES_NUMBERS = {0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55};

    private static final int CENTER_RADIUS = 2;

    private static final int[] STATE_SET_SELECTED = new int[] {R.attr.state_selected};

    private static int[] sSnapPrefer30sMap = new int[361];

    private final String[] mHours12Texts = new String[12];
    private final String[] mOuterHours24Texts = new String[12];
    private final String[] mInnerHours24Texts = new String[12];
    private final String[] mMinutesTexts = new String[12];

    private final String[] mAmPmText = new String[2];

    private final Paint[] mPaint = new Paint[2];
    private final int[] mColor = new int[2];
    private final IntHolder[] mAlpha = new IntHolder[2];

    private final Paint mPaintCenter = new Paint();

    private final Paint[][] mPaintSelector = new Paint[2][3];
    private final int[][] mColorSelector = new int[2][3];
    private final IntHolder[][] mAlphaSelector = new IntHolder[2][3];

    private final Paint mPaintAmPmText = new Paint();
    private final Paint[] mPaintAmPmCircle = new Paint[2];

    private final Paint mPaintBackground = new Paint();
    private final Paint mPaintDisabled = new Paint();
    private final Paint mPaintDebug = new Paint();

    private Typeface mTypeface;

    private boolean mIs24HourMode;
    private boolean mShowHours;
    private boolean mIsOnInnerCircle;

    private int mXCenter;
    private int mYCenter;

    private float[] mCircleRadius = new float[3];

    private int mMinHypotenuseForInnerNumber;
    private int mMaxHypotenuseForOuterNumber;
    private int mHalfwayHypotenusePoint;

    private float[] mTextSize = new float[2];
    private float mInnerTextSize;

    private float[][] mTextGridHeights = new float[2][7];
    private float[][] mTextGridWidths = new float[2][7];

    private float[] mInnerTextGridHeights = new float[7];
    private float[] mInnerTextGridWidths = new float[7];

    private String[] mOuterTextHours;
    private String[] mInnerTextHours;
    private String[] mOuterTextMinutes;

    private float[] mCircleRadiusMultiplier = new float[2];
    private float[] mNumbersRadiusMultiplier = new float[3];

    private float[] mTextSizeMultiplier = new float[3];

    private float[] mAnimationRadiusMultiplier = new float[3];

    private float mTransitionMidRadiusMultiplier;
    private float mTransitionEndRadiusMultiplier;

    private AnimatorSet mTransition;
    private InvalidateUpdateListener mInvalidateUpdateListener = new InvalidateUpdateListener();

    private int[] mLineLength = new int[3];
    private int[] mSelectionRadius = new int[3];
    private float mSelectionRadiusMultiplier;
    private int[] mSelectionDegrees = new int[3];

    private int mAmPmCircleRadius;
    private float mAmPmYCenter;

    private float mAmPmCircleRadiusMultiplier;
    private int mAmPmTextColor;

    private float mLeftIndicatorXCenter;
    private float mRightIndicatorXCenter;

    private int mAmPmUnselectedColor;
    private int mAmPmSelectedColor;

    private int mAmOrPm;
    private int mAmOrPmPressed;

    private int mDisabledAlpha;

    private RectF mRectF = new RectF();
    private boolean mInputEnabled = true;
    private OnValueSelectedListener mListener;

    private final ArrayList<Animator> mHoursToMinutesAnims = new ArrayList<Animator>();
    private final ArrayList<Animator> mMinuteToHoursAnims = new ArrayList<Animator>();

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

    public RadialTimePickerView(Context context, AttributeSet attrs)  {
        this(context, attrs, R.attr.timePickerStyle);
    }

    public RadialTimePickerView(Context context, AttributeSet attrs, int defStyle)  {
        super(context, attrs);

        // Pull disabled alpha from theme.
        final TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.disabledAlpha, outValue, true);
        mDisabledAlpha = (int) (outValue.getFloat() * 255 + 0.5f);

        // process style attributes
        final Resources res = getResources();
        final TypedArray a = mContext.obtainStyledAttributes(attrs, R.styleable.TimePicker,
                defStyle, 0);

        ColorStateList amPmBackgroundColor = a.getColorStateList(
                R.styleable.TimePicker_amPmBackgroundColor);
        if (amPmBackgroundColor == null) {
            amPmBackgroundColor = res.getColorStateList(
                    R.color.timepicker_default_ampm_unselected_background_color_material);
        }

        // Obtain the backup selected color. If the background color state
        // list doesn't have a state for selected, we'll use this color.
        final int amPmSelectedColor = a.getColor(R.styleable.TimePicker_amPmSelectedBackgroundColor,
                res.getColor(R.color.timepicker_default_ampm_selected_background_color_material));
        amPmBackgroundColor = ColorStateList.addFirstIfMissing(
                amPmBackgroundColor, R.attr.state_selected, amPmSelectedColor);

        mAmPmSelectedColor = amPmBackgroundColor.getColorForState(
                STATE_SET_SELECTED, amPmSelectedColor);
        mAmPmUnselectedColor = amPmBackgroundColor.getDefaultColor();

        mAmPmTextColor = a.getColor(R.styleable.TimePicker_amPmTextColor,
                res.getColor(R.color.timepicker_default_text_color_material));

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

        mPaintAmPmText.setColor(mAmPmTextColor);
        mPaintAmPmText.setTypeface(mTypeface);
        mPaintAmPmText.setAntiAlias(true);
        mPaintAmPmText.setTextAlign(Paint.Align.CENTER);

        mPaintAmPmCircle[AM] = new Paint();
        mPaintAmPmCircle[AM].setAntiAlias(true);
        mPaintAmPmCircle[PM] = new Paint();
        mPaintAmPmCircle[PM].setAntiAlias(true);

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
        mAmOrPmPressed = -1;

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

        // Initial values
        final Calendar calendar = Calendar.getInstance(Locale.getDefault());
        final int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        final int currentMinute = calendar.get(Calendar.MINUTE);

        setCurrentHour(currentHour);
        setCurrentMinute(currentMinute);

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
        mIs24HourMode = is24HourMode;
        setCurrentHour(hour);
        setCurrentMinute(minute);
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

    public void setCurrentHour(int hour) {
        final int degrees = (hour % 12) * DEGREES_FOR_ONE_HOUR;
        mSelectionDegrees[HOURS] = degrees;
        mSelectionDegrees[HOURS_INNER] = degrees;
        mAmOrPm = ((hour % 24) < 12) ? AM : PM;
        if (mIs24HourMode) {
            mIsOnInnerCircle = (mAmOrPm == AM);
        } else {
            mIsOnInnerCircle = false;
        }
        initData();
        updateLayoutData();
        invalidate();
    }

    // Return hours in 0-23 range
    public int getCurrentHour() {
        int hours =
                mSelectionDegrees[mIsOnInnerCircle ? HOURS_INNER : HOURS] / DEGREES_FOR_ONE_HOUR;
        if (mIs24HourMode) {
            if (mIsOnInnerCircle) {
                hours = hours % 12;
                if (hours == 0) {
                    hours = 12;
                }
            } else {
                if (hours != 0) {
                    hours += 12;
                }
            }
        } else {
            hours = hours % 12;
            if (hours == 0) {
                if (mAmOrPm == PM) {
                    hours = 12;
                }
            } else {
                if (mAmOrPm == PM) {
                    hours += 12;
                }
            }
        }
        return hours;
    }

    public void setCurrentMinute(int minute) {
        mSelectionDegrees[MINUTES] = (minute % 60) * DEGREES_FOR_ONE_MINUTE;
        invalidate();
    }

    // Returns minutes in 0-59 range
    public int getCurrentMinute() {
        return (mSelectionDegrees[MINUTES] / DEGREES_FOR_ONE_MINUTE);
    }

    public void setAmOrPm(int val) {
        mAmOrPm = (val % 2);
        invalidate();
    }

    public int getAmOrPm() {
        return mAmOrPm;
    }

    public void swapAmPm() {
        mAmOrPm = (mAmOrPm == AM) ? PM : AM;
        invalidate();
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

        String[] amPmTexts = new DateFormatSymbols().getAmPmStrings();
        mAmPmText[AM] = amPmTexts[0];
        mAmPmText[PM] = amPmTexts[1];
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

        mAmPmCircleRadiusMultiplier = Float.parseFloat(
                res.getString(R.string.timepicker_ampm_circle_radius_multiplier));

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

        if (!mIs24HourMode) {
            // We'll need to draw the AM/PM circles, so the main circle will need to have
            // a slightly higher center. To keep the entire view centered vertically, we'll
            // have to push it up by half the radius of the AM/PM circles.
            int amPmCircleRadius = (int) (mCircleRadius[HOURS] * mAmPmCircleRadiusMultiplier);
            mYCenter -= amPmCircleRadius / 2;
        }

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

        mAmPmCircleRadius = (int) (mCircleRadius[HOURS] * mAmPmCircleRadiusMultiplier);
        mPaintAmPmText.setTextSize(mAmPmCircleRadius * 3 / 4);

        // Line up the vertical center of the AM/PM circles with the bottom of the main circle.
        mAmPmYCenter = mYCenter + mCircleRadius[HOURS];

        // Line up the horizontal edges of the AM/PM circles with the horizontal edges
        // of the main circle
        mLeftIndicatorXCenter = mXCenter - mCircleRadius[HOURS] + mAmPmCircleRadius;
        mRightIndicatorXCenter = mXCenter + mCircleRadius[HOURS] - mAmPmCircleRadius;
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
        if (!mIs24HourMode) {
            drawAmPm(canvas);
        }

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

    private void drawAmPm(Canvas canvas) {
        final boolean isLayoutRtl = isLayoutRtl();

        int amColor = mAmPmUnselectedColor;
        int amAlpha = ALPHA_OPAQUE;
        int pmColor = mAmPmUnselectedColor;
        int pmAlpha = ALPHA_OPAQUE;
        if (mAmOrPm == AM) {
            amColor = mAmPmSelectedColor;
            amAlpha = ALPHA_AMPM_SELECTED;
        } else if (mAmOrPm == PM) {
            pmColor = mAmPmSelectedColor;
            pmAlpha = ALPHA_AMPM_SELECTED;
        }
        if (mAmOrPmPressed == AM) {
            amColor = mAmPmSelectedColor;
            amAlpha = ALPHA_AMPM_PRESSED;
        } else if (mAmOrPmPressed == PM) {
            pmColor = mAmPmSelectedColor;
            pmAlpha = ALPHA_AMPM_PRESSED;
        }

        // Draw the two circles
        mPaintAmPmCircle[AM].setColor(amColor);
        mPaintAmPmCircle[AM].setAlpha(getMultipliedAlpha(amColor, amAlpha));
        canvas.drawCircle(isLayoutRtl ? mRightIndicatorXCenter : mLeftIndicatorXCenter,
                mAmPmYCenter, mAmPmCircleRadius, mPaintAmPmCircle[AM]);

        mPaintAmPmCircle[PM].setColor(pmColor);
        mPaintAmPmCircle[PM].setAlpha(getMultipliedAlpha(pmColor, pmAlpha));
        canvas.drawCircle(isLayoutRtl ? mLeftIndicatorXCenter : mRightIndicatorXCenter,
                mAmPmYCenter, mAmPmCircleRadius, mPaintAmPmCircle[PM]);

        // Draw the AM/PM texts on top
        mPaintAmPmText.setColor(mAmPmTextColor);
        float textYCenter = mAmPmYCenter -
                (int) (mPaintAmPmText.descent() + mPaintAmPmText.ascent()) / 2;

        canvas.drawText(isLayoutRtl ? mAmPmText[PM] : mAmPmText[AM], mLeftIndicatorXCenter,
                textYCenter, mPaintAmPmText);
        canvas.drawText(isLayoutRtl ? mAmPmText[AM] : mAmPmText[PM], mRightIndicatorXCenter,
                textYCenter, mPaintAmPmText);
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
        mRectF = new RectF(left, top, right, bottom);
        canvas.drawRect(mRectF, mPaintDebug);

        // Draw outer rectangle for background
        left = mXCenter - mCircleRadius[HOURS];
        top = mYCenter - mCircleRadius[HOURS];
        right = mXCenter + mCircleRadius[HOURS];
        bottom = mYCenter + mCircleRadius[HOURS];
        mRectF.set(left, top, right, bottom);
        canvas.drawRect(mRectF, mPaintDebug);

        // Draw outer view rectangle
        mRectF.set(0, 0, getWidth(), getHeight());
        canvas.drawRect(mRectF, mPaintDebug);

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

        canvas.drawText(selected.toString(), x, y, paint);
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
    private void setAnimationRadiusMultiplierHours(float animationRadiusMultiplier) {
        mAnimationRadiusMultiplier[HOURS] = animationRadiusMultiplier;
        mAnimationRadiusMultiplier[HOURS_INNER] = animationRadiusMultiplier;
    }

    // Used for animating the minutes by changing their radius
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
        double degrees = Math.toDegrees(Math.asin(opposite / hypotenuse));

        // Now we have to translate to the correct quadrant.
        boolean rightSide = (x > mXCenter);
        boolean topSide = (y < mYCenter);
        if (rightSide && topSide) {
            degrees = 90 - degrees;
        } else if (rightSide && !topSide) {
            degrees = 90 + degrees;
        } else if (!rightSide && !topSide) {
            degrees = 270 - degrees;
        } else if (!rightSide && topSide) {
            degrees = 270 + degrees;
        }
        return (int) degrees;
    }

    private int getIsTouchingAmOrPm(float x, float y) {
        final boolean isLayoutRtl = isLayoutRtl();
        int squaredYDistance = (int) ((y - mAmPmYCenter) * (y - mAmPmYCenter));

        int distanceToAmCenter = (int) Math.sqrt(
                (x - mLeftIndicatorXCenter) * (x - mLeftIndicatorXCenter) + squaredYDistance);
        if (distanceToAmCenter <= mAmPmCircleRadius) {
            return (isLayoutRtl ? PM : AM);
        }

        int distanceToPmCenter = (int) Math.sqrt(
                (x - mRightIndicatorXCenter) * (x - mRightIndicatorXCenter) + squaredYDistance);
        if (distanceToPmCenter <= mAmPmCircleRadius) {
            return (isLayoutRtl ? AM : PM);
        }

        // Neither was close enough.
        return -1;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if(!mInputEnabled) {
            return true;
        }

        final float eventX = event.getX();
        final float eventY = event.getY();

        int degrees;
        int snapDegrees;
        boolean result = false;

        switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                mAmOrPmPressed = getIsTouchingAmOrPm(eventX, eventY);
                if (mAmOrPmPressed != -1) {
                    result = true;
                } else {
                    degrees = getDegreesFromXY(eventX, eventY);
                    if (degrees != -1) {
                        snapDegrees = (mShowHours ?
                                snapOnly30s(degrees, 0) : snapPrefer30s(degrees)) % 360;
                        if (mShowHours) {
                            mSelectionDegrees[HOURS] = snapDegrees;
                            mSelectionDegrees[HOURS_INNER] = snapDegrees;
                        } else {
                            mSelectionDegrees[MINUTES] = snapDegrees;
                        }
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (mListener != null) {
                            if (mShowHours) {
                                mListener.onValueSelected(HOURS, getCurrentHour(), false);
                            } else  {
                                mListener.onValueSelected(MINUTES, getCurrentMinute(), false);
                            }
                        }
                        result = true;
                    }
                }
                invalidate();
                return result;

            case MotionEvent.ACTION_UP:
                mAmOrPmPressed = getIsTouchingAmOrPm(eventX, eventY);
                if (mAmOrPmPressed != -1) {
                    if (mAmOrPm != mAmOrPmPressed) {
                        swapAmPm();
                    }
                    mAmOrPmPressed = -1;
                    if (mListener != null) {
                        mListener.onValueSelected(AMPM, getCurrentHour(), true);
                    }
                    result = true;
                } else {
                    degrees = getDegreesFromXY(eventX, eventY);
                    if (degrees != -1) {
                        snapDegrees = (mShowHours ?
                                snapOnly30s(degrees, 0) : snapPrefer30s(degrees)) % 360;
                        if (mShowHours) {
                            mSelectionDegrees[HOURS] = snapDegrees;
                            mSelectionDegrees[HOURS_INNER] = snapDegrees;
                        } else {
                            mSelectionDegrees[MINUTES] = snapDegrees;
                        }
                        if (mListener != null) {
                            if (mShowHours) {
                                mListener.onValueSelected(HOURS, getCurrentHour(), true);
                            } else  {
                                mListener.onValueSelected(MINUTES, getCurrentMinute(), true);
                            }
                        }
                        result = true;
                    }
                }
                if (result) {
                    invalidate();
                }
                return result;

            default:
                break;
        }
        return false;
    }

    /**
     * Necessary for accessibility, to ensure we support "scrolling" forward and backward
     * in the circle.
     */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    /**
     * Announce the currently-selected time when launched.
     */
    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Clear the event's current text so that only the current time will be spoken.
            event.getText().clear();
            Time time = new Time();
            time.hour = getCurrentHour();
            time.minute = getCurrentMinute();
            long millis = time.normalize(true);
            int flags = DateUtils.FORMAT_SHOW_TIME;
            if (mIs24HourMode) {
                flags |= DateUtils.FORMAT_24HOUR;
            }
            String timeString = DateUtils.formatDateTime(getContext(), millis, flags);
            event.getText().add(timeString);
            return true;
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }

    /**
     * When scroll forward/backward events are received, jump the time to the higher/lower
     * discrete, visible value on the circle.
     */
    @SuppressLint("NewApi")
    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (super.performAccessibilityAction(action, arguments)) {
            return true;
        }

        int changeMultiplier = 0;
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            changeMultiplier = 1;
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            changeMultiplier = -1;
        }
        if (changeMultiplier != 0) {
            int value = 0;
            int stepSize = 0;
            if (mShowHours) {
                stepSize = DEGREES_FOR_ONE_HOUR;
                value = getCurrentHour() % 12;
            } else {
                stepSize = DEGREES_FOR_ONE_MINUTE;
                value = getCurrentMinute();
            }

            int degrees = value * stepSize;
            degrees = snapOnly30s(degrees, changeMultiplier);
            value = degrees / stepSize;
            int maxValue = 0;
            int minValue = 0;
            if (mShowHours) {
                if (mIs24HourMode) {
                    maxValue = 23;
                } else {
                    maxValue = 12;
                    minValue = 1;
                }
            } else {
                maxValue = 55;
            }
            if (value > maxValue) {
                // If we scrolled forward past the highest number, wrap around to the lowest.
                value = minValue;
            } else if (value < minValue) {
                // If we scrolled backward past the lowest number, wrap around to the highest.
                value = maxValue;
            }
            if (mShowHours) {
                setCurrentHour(value);
                if (mListener != null) {
                    mListener.onValueSelected(HOURS, value, false);
                }
            } else {
                setCurrentMinute(value);
                if (mListener != null) {
                    mListener.onValueSelected(MINUTES, value, false);
                }
            }
            return true;
        }

        return false;
    }

    public void setInputEnabled(boolean inputEnabled) {
        mInputEnabled = inputEnabled;
        invalidate();
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
