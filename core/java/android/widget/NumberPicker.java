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

package android.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.Widget;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.NumberKeyListener;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.LayoutInflater.Filter;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.R;

/**
 * A widget that enables the user to select a number form a predefined range.
 * The widget presents an input field and up and down buttons for selecting the
 * current value. Pressing/long pressing the up and down buttons increments and
 * decrements the current value respectively. Touching the input field shows a
 * scroll wheel, tapping on which while shown and not moving allows direct edit
 * of the current value. Sliding motions up or down hide the buttons and the
 * input field, show the scroll wheel, and rotate the latter. Flinging is
 * also supported. The widget enables mapping from positions to strings such
 * that instead the position index the corresponding string is displayed.
 * <p>
 * For an example of using this widget, see {@link android.widget.TimePicker}.
 * </p>
 */
@Widget
public class NumberPicker extends LinearLayout {

    /**
     * The default update interval during long press.
     */
    private static final long DEFAULT_LONG_PRESS_UPDATE_INTERVAL = 300;

    /**
     * The index of the middle selector item.
     */
    private static final int SELECTOR_MIDDLE_ITEM_INDEX = 2;

    /**
     * The coefficient by which to adjust (divide) the max fling velocity.
     */
    private static final int SELECTOR_MAX_FLING_VELOCITY_ADJUSTMENT = 8;

    /**
     * The the duration for adjusting the selector wheel.
     */
    private static final int SELECTOR_ADJUSTMENT_DURATION_MILLIS = 800;

    /**
     * The duration of scrolling to the next/previous value while changing
     * the current value by one, i.e. increment or decrement.
     */
    private static final int CHANGE_CURRENT_BY_ONE_SCROLL_DURATION = 300;

    /**
     * The the delay for showing the input controls after a single tap on the
     * input text.
     */
    private static final int SHOW_INPUT_CONTROLS_DELAY_MILLIS = ViewConfiguration
            .getDoubleTapTimeout();

    /**
     * The strength of fading in the top and bottom while drawing the selector.
     */
    private static final float TOP_AND_BOTTOM_FADING_EDGE_STRENGTH = 0.9f;

    /**
     * The default unscaled height of the selection divider.
     */
    private static final int UNSCALED_DEFAULT_SELECTION_DIVIDER_HEIGHT = 2;

    /**
     * In this state the selector wheel is not shown.
     */
    private static final int SELECTOR_WHEEL_STATE_NONE = 0;

    /**
     * In this state the selector wheel is small.
     */
    private static final int SELECTOR_WHEEL_STATE_SMALL = 1;

    /**
     * In this state the selector wheel is large.
     */
    private static final int SELECTOR_WHEEL_STATE_LARGE = 2;

    /**
     * The alpha of the selector wheel when it is bright.
     */
    private static final int SELECTOR_WHEEL_BRIGHT_ALPHA = 255;

    /**
     * The alpha of the selector wheel when it is dimmed.
     */
    private static final int SELECTOR_WHEEL_DIM_ALPHA = 60;

    /**
     * The alpha for the increment/decrement button when it is transparent.
     */
    private static final int BUTTON_ALPHA_TRANSPARENT = 0;

    /**
     * The alpha for the increment/decrement button when it is opaque.
     */
    private static final int BUTTON_ALPHA_OPAQUE = 1;

    /**
     * The property for setting the selector paint.
     */
    private static final String PROPERTY_SELECTOR_PAINT_ALPHA = "selectorPaintAlpha";

    /**
     * The property for setting the increment/decrement button alpha.
     */
    private static final String PROPERTY_BUTTON_ALPHA = "alpha";

    /**
     * The numbers accepted by the input text's {@link Filter}
     */
    private static final char[] DIGIT_CHARACTERS = new char[] {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
    };

    /**
     * Constant for unspecified size.
     */
    private static final int SIZE_UNSPECIFIED = -1;

    /**
     * Use a custom NumberPicker formatting callback to use two-digit minutes
     * strings like "01". Keeping a static formatter etc. is the most efficient
     * way to do this; it avoids creating temporary objects on every call to
     * format().
     *
     * @hide
     */
    public static final NumberPicker.Formatter TWO_DIGIT_FORMATTER = new NumberPicker.Formatter() {
        final StringBuilder mBuilder = new StringBuilder();

        final java.util.Formatter mFmt = new java.util.Formatter(mBuilder, java.util.Locale.US);

        final Object[] mArgs = new Object[1];

        public String format(int value) {
            mArgs[0] = value;
            mBuilder.delete(0, mBuilder.length());
            mFmt.format("%02d", mArgs);
            return mFmt.toString();
        }
    };

    /**
     * The increment button.
     */
    private final ImageButton mIncrementButton;

    /**
     * The decrement button.
     */
    private final ImageButton mDecrementButton;

    /**
     * The text for showing the current value.
     */
    private final EditText mInputText;

    /**
     * The min height of this widget.
     */
    private final int mMinHeight;

    /**
     * The max height of this widget.
     */
    private final int mMaxHeight;

    /**
     * The max width of this widget.
     */
    private final int mMinWidth;

    /**
     * The max width of this widget.
     */
    private int mMaxWidth;

    /**
     * Flag whether to compute the max width.
     */
    private final boolean mComputeMaxWidth;

    /**
     * The height of the text.
     */
    private final int mTextSize;

    /**
     * The height of the gap between text elements if the selector wheel.
     */
    private int mSelectorTextGapHeight;

    /**
     * The values to be displayed instead the indices.
     */
    private String[] mDisplayedValues;

    /**
     * Lower value of the range of numbers allowed for the NumberPicker
     */
    private int mMinValue;

    /**
     * Upper value of the range of numbers allowed for the NumberPicker
     */
    private int mMaxValue;

    /**
     * Current value of this NumberPicker
     */
    private int mValue;

    /**
     * Listener to be notified upon current value change.
     */
    private OnValueChangeListener mOnValueChangeListener;

    /**
     * Listener to be notified upon scroll state change.
     */
    private OnScrollListener mOnScrollListener;

    /**
     * Formatter for for displaying the current value.
     */
    private Formatter mFormatter;

    /**
     * The speed for updating the value form long press.
     */
    private long mLongPressUpdateInterval = DEFAULT_LONG_PRESS_UPDATE_INTERVAL;

    /**
     * Cache for the string representation of selector indices.
     */
    private final SparseArray<String> mSelectorIndexToStringCache = new SparseArray<String>();

    /**
     * The selector indices whose value are show by the selector.
     */
    private final int[] mSelectorIndices = new int[] {
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE
    };

    /**
     * The {@link Paint} for drawing the selector.
     */
    private final Paint mSelectorWheelPaint;

    /**
     * The height of a selector element (text + gap).
     */
    private int mSelectorElementHeight;

    /**
     * The initial offset of the scroll selector.
     */
    private int mInitialScrollOffset = Integer.MIN_VALUE;

    /**
     * The current offset of the scroll selector.
     */
    private int mCurrentScrollOffset;

    /**
     * The {@link Scroller} responsible for flinging the selector.
     */
    private final Scroller mFlingScroller;

    /**
     * The {@link Scroller} responsible for adjusting the selector.
     */
    private final Scroller mAdjustScroller;

    /**
     * The previous Y coordinate while scrolling the selector.
     */
    private int mPreviousScrollerY;

    /**
     * Handle to the reusable command for setting the input text selection.
     */
    private SetSelectionCommand mSetSelectionCommand;

    /**
     * Handle to the reusable command for adjusting the scroller.
     */
    private AdjustScrollerCommand mAdjustScrollerCommand;

    /**
     * Handle to the reusable command for changing the current value from long
     * press by one.
     */
    private ChangeCurrentByOneFromLongPressCommand mChangeCurrentByOneFromLongPressCommand;

    /**
     * {@link Animator} for showing the up/down arrows.
     */
    private final AnimatorSet mShowInputControlsAnimator;

    /**
     * {@link Animator} for dimming the selector wheel.
     */
    private final Animator mDimSelectorWheelAnimator;

    /**
     * The Y position of the last down event.
     */
    private float mLastDownEventY;

    /**
     * The Y position of the last motion event.
     */
    private float mLastMotionEventY;

    /**
     * Flag if to begin edit on next up event.
     */
    private boolean mBeginEditOnUpEvent;

    /**
     * Flag if to adjust the selector wheel on next up event.
     */
    private boolean mAdjustScrollerOnUpEvent;

    /**
     * The state of the selector wheel.
     */
    private int mSelectorWheelState;

    /**
     * Determines speed during touch scrolling.
     */
    private VelocityTracker mVelocityTracker;

    /**
     * @see ViewConfiguration#getScaledTouchSlop()
     */
    private int mTouchSlop;

    /**
     * @see ViewConfiguration#getScaledMinimumFlingVelocity()
     */
    private int mMinimumFlingVelocity;

    /**
     * @see ViewConfiguration#getScaledMaximumFlingVelocity()
     */
    private int mMaximumFlingVelocity;

    /**
     * Flag whether the selector should wrap around.
     */
    private boolean mWrapSelectorWheel;

    /**
     * The back ground color used to optimize scroller fading.
     */
    private final int mSolidColor;

    /**
     * Flag indicating if this widget supports flinging.
     */
    private final boolean mFlingable;

    /**
     * Divider for showing item to be selected while scrolling
     */
    private final Drawable mSelectionDivider;

    /**
     * The height of the selection divider.
     */
    private final int mSelectionDividerHeight;

    /**
     * Reusable {@link Rect} instance.
     */
    private final Rect mTempRect = new Rect();

    /**
     * The current scroll state of the number picker.
     */
    private int mScrollState = OnScrollListener.SCROLL_STATE_IDLE;

    /**
     * The duration of the animation for showing the input controls.
     */
    private final long mShowInputControlsAnimimationDuration;

    /**
     * Flag whether the scoll wheel and the fading edges have been initialized.
     */
    private boolean mScrollWheelAndFadingEdgesInitialized;

    /**
     * Interface to listen for changes of the current value.
     */
    public interface OnValueChangeListener {

        /**
         * Called upon a change of the current value.
         *
         * @param picker The NumberPicker associated with this listener.
         * @param oldVal The previous value.
         * @param newVal The new value.
         */
        void onValueChange(NumberPicker picker, int oldVal, int newVal);
    }

    /**
     * Interface to listen for the picker scroll state.
     */
    public interface OnScrollListener {

        /**
         * The view is not scrolling.
         */
        public static int SCROLL_STATE_IDLE = 0;

        /**
         * The user is scrolling using touch, and their finger is still on the screen.
         */
        public static int SCROLL_STATE_TOUCH_SCROLL = 1;

        /**
         * The user had previously been scrolling using touch and performed a fling.
         */
        public static int SCROLL_STATE_FLING = 2;

        /**
         * Callback invoked while the number picker scroll state has changed.
         *
         * @param view The view whose scroll state is being reported.
         * @param scrollState The current scroll state. One of
         *            {@link #SCROLL_STATE_IDLE},
         *            {@link #SCROLL_STATE_TOUCH_SCROLL} or
         *            {@link #SCROLL_STATE_IDLE}.
         */
        public void onScrollStateChange(NumberPicker view, int scrollState);
    }

    /**
     * Interface used to format current value into a string for presentation.
     */
    public interface Formatter {

        /**
         * Formats a string representation of the current value.
         *
         * @param value The currently selected value.
         * @return A formatted string representation.
         */
        public String format(int value);
    }

    /**
     * Create a new number picker.
     *
     * @param context The application environment.
     */
    public NumberPicker(Context context) {
        this(context, null);
    }

    /**
     * Create a new number picker.
     *
     * @param context The application environment.
     * @param attrs A collection of attributes.
     */
    public NumberPicker(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.numberPickerStyle);
    }

    /**
     * Create a new number picker
     *
     * @param context the application environment.
     * @param attrs a collection of attributes.
     * @param defStyle The default style to apply to this view.
     */
    public NumberPicker(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        // process style attributes
        TypedArray attributesArray = context.obtainStyledAttributes(attrs,
                R.styleable.NumberPicker, defStyle, 0);
        mSolidColor = attributesArray.getColor(R.styleable.NumberPicker_solidColor, 0);
        mFlingable = attributesArray.getBoolean(R.styleable.NumberPicker_flingable, true);
        mSelectionDivider = attributesArray.getDrawable(R.styleable.NumberPicker_selectionDivider);
        int defSelectionDividerHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                UNSCALED_DEFAULT_SELECTION_DIVIDER_HEIGHT,
                getResources().getDisplayMetrics());
        mSelectionDividerHeight = attributesArray.getDimensionPixelSize(
                R.styleable.NumberPicker_selectionDividerHeight, defSelectionDividerHeight);
        mMinHeight = attributesArray.getDimensionPixelSize(R.styleable.NumberPicker_minHeight,
                SIZE_UNSPECIFIED);
        mMaxHeight = attributesArray.getDimensionPixelSize(R.styleable.NumberPicker_maxHeight,
                SIZE_UNSPECIFIED);
        if (mMinHeight != SIZE_UNSPECIFIED && mMaxHeight != SIZE_UNSPECIFIED
                && mMinHeight > mMaxHeight) {
            throw new IllegalArgumentException("minHeight > maxHeight");
        }
        mMinWidth = attributesArray.getDimensionPixelSize(R.styleable.NumberPicker_minWidth,
                SIZE_UNSPECIFIED);
        mMaxWidth = attributesArray.getDimensionPixelSize(R.styleable.NumberPicker_maxWidth,
                SIZE_UNSPECIFIED);
        if (mMinWidth != SIZE_UNSPECIFIED && mMaxWidth != SIZE_UNSPECIFIED
                && mMinWidth > mMaxWidth) {
            throw new IllegalArgumentException("minWidth > maxWidth");
        }
        mComputeMaxWidth = (mMaxWidth == Integer.MAX_VALUE);
        attributesArray.recycle();

        mShowInputControlsAnimimationDuration = getResources().getInteger(
                R.integer.config_longAnimTime);

        // By default Linearlayout that we extend is not drawn. This is
        // its draw() method is not called but dispatchDraw() is called
        // directly (see ViewGroup.drawChild()). However, this class uses
        // the fading edge effect implemented by View and we need our
        // draw() method to be called. Therefore, we declare we will draw.
        setWillNotDraw(false);
        setSelectorWheelState(SELECTOR_WHEEL_STATE_NONE);

        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.number_picker, this, true);

        OnClickListener onClickListener = new OnClickListener() {
            public void onClick(View v) {
                hideSoftInput();
                mInputText.clearFocus();
                if (v.getId() == R.id.increment) {
                    changeCurrentByOne(true);
                } else {
                    changeCurrentByOne(false);
                }
            }
        };

        OnLongClickListener onLongClickListener = new OnLongClickListener() {
            public boolean onLongClick(View v) {
                hideSoftInput();
                mInputText.clearFocus();
                if (v.getId() == R.id.increment) {
                    postChangeCurrentByOneFromLongPress(true);
                } else {
                    postChangeCurrentByOneFromLongPress(false);
                }
                return true;
            }
        };

        // increment button
        mIncrementButton = (ImageButton) findViewById(R.id.increment);
        mIncrementButton.setOnClickListener(onClickListener);
        mIncrementButton.setOnLongClickListener(onLongClickListener);

        // decrement button
        mDecrementButton = (ImageButton) findViewById(R.id.decrement);
        mDecrementButton.setOnClickListener(onClickListener);
        mDecrementButton.setOnLongClickListener(onLongClickListener);

        // input text
        mInputText = (EditText) findViewById(R.id.numberpicker_input);
        mInputText.setOnFocusChangeListener(new OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    mInputText.selectAll();
                    InputMethodManager inputMethodManager = InputMethodManager.peekInstance();
                    if (inputMethodManager != null) {
                        inputMethodManager.showSoftInput(mInputText, 0);
                    }
                } else {
                    mInputText.setSelection(0, 0);
                    validateInputTextView(v);
                }
            }
        });
        mInputText.setFilters(new InputFilter[] {
            new InputTextFilter()
        });

        mInputText.setRawInputType(InputType.TYPE_CLASS_NUMBER);

        // initialize constants
        mTouchSlop = ViewConfiguration.getTapTimeout();
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumFlingVelocity = configuration.getScaledMaximumFlingVelocity()
                / SELECTOR_MAX_FLING_VELOCITY_ADJUSTMENT;
        mTextSize = (int) mInputText.getTextSize();

        // create the selector wheel paint
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Align.CENTER);
        paint.setTextSize(mTextSize);
        paint.setTypeface(mInputText.getTypeface());
        ColorStateList colors = mInputText.getTextColors();
        int color = colors.getColorForState(ENABLED_STATE_SET, Color.WHITE);
        paint.setColor(color);
        mSelectorWheelPaint = paint;

        // create the animator for showing the input controls
        mDimSelectorWheelAnimator = ObjectAnimator.ofInt(this, PROPERTY_SELECTOR_PAINT_ALPHA,
                SELECTOR_WHEEL_BRIGHT_ALPHA, SELECTOR_WHEEL_DIM_ALPHA);
        final ObjectAnimator showIncrementButton = ObjectAnimator.ofFloat(mIncrementButton,
                PROPERTY_BUTTON_ALPHA, BUTTON_ALPHA_TRANSPARENT, BUTTON_ALPHA_OPAQUE);
        final ObjectAnimator showDecrementButton = ObjectAnimator.ofFloat(mDecrementButton,
                PROPERTY_BUTTON_ALPHA, BUTTON_ALPHA_TRANSPARENT, BUTTON_ALPHA_OPAQUE);
        mShowInputControlsAnimator = new AnimatorSet();
        mShowInputControlsAnimator.playTogether(mDimSelectorWheelAnimator, showIncrementButton,
                showDecrementButton);
        mShowInputControlsAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled = false;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCanceled) {
                    // if canceled => we still want the wheel drawn
                    setSelectorWheelState(SELECTOR_WHEEL_STATE_SMALL);
                }
                mCanceled = false;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (mShowInputControlsAnimator.isRunning()) {
                    mCanceled = true;
                }
            }
        });

        // create the fling and adjust scrollers
        mFlingScroller = new Scroller(getContext(), null, true);
        mAdjustScroller = new Scroller(getContext(), new DecelerateInterpolator(2.5f));

        updateInputTextView();
        updateIncrementAndDecrementButtonsVisibilityState();

        if (mFlingable) {
           if (isInEditMode()) {
               setSelectorWheelState(SELECTOR_WHEEL_STATE_SMALL);
           } else {
                // Start with shown selector wheel and hidden controls. When made
                // visible hide the selector and fade-in the controls to suggest
                // fling interaction.
                setSelectorWheelState(SELECTOR_WHEEL_STATE_LARGE);
                hideInputControls();
           }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int msrdWdth = getMeasuredWidth();
        final int msrdHght = getMeasuredHeight();

        // Increment button at the top.
        final int inctBtnMsrdWdth = mIncrementButton.getMeasuredWidth();
        final int incrBtnLeft = (msrdWdth - inctBtnMsrdWdth) / 2;
        final int incrBtnTop = 0;
        final int incrBtnRight = incrBtnLeft + inctBtnMsrdWdth;
        final int incrBtnBottom = incrBtnTop + mIncrementButton.getMeasuredHeight();
        mIncrementButton.layout(incrBtnLeft, incrBtnTop, incrBtnRight, incrBtnBottom);

        // Input text centered horizontally.
        final int inptTxtMsrdWdth = mInputText.getMeasuredWidth();
        final int inptTxtMsrdHght = mInputText.getMeasuredHeight();
        final int inptTxtLeft = (msrdWdth - inptTxtMsrdWdth) / 2;
        final int inptTxtTop = (msrdHght - inptTxtMsrdHght) / 2;
        final int inptTxtRight = inptTxtLeft + inptTxtMsrdWdth;
        final int inptTxtBottom = inptTxtTop + inptTxtMsrdHght;
        mInputText.layout(inptTxtLeft, inptTxtTop, inptTxtRight, inptTxtBottom);

        // Decrement button at the top.
        final int decrBtnMsrdWdth = mIncrementButton.getMeasuredWidth();
        final int decrBtnLeft = (msrdWdth - decrBtnMsrdWdth) / 2;
        final int decrBtnTop = msrdHght - mDecrementButton.getMeasuredHeight();
        final int decrBtnRight = decrBtnLeft + decrBtnMsrdWdth;
        final int decrBtnBottom = msrdHght;
        mDecrementButton.layout(decrBtnLeft, decrBtnTop, decrBtnRight, decrBtnBottom);

        if (!mScrollWheelAndFadingEdgesInitialized) {
            mScrollWheelAndFadingEdgesInitialized = true;
            // need to do all this when we know our size
            initializeSelectorWheel();
            initializeFadingEdges();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Try greedily to fit the max width and height.
        final int newWidthMeasureSpec = makeMeasureSpec(widthMeasureSpec, mMaxWidth);
        final int newHeightMeasureSpec = makeMeasureSpec(heightMeasureSpec, mMaxHeight);
        super.onMeasure(newWidthMeasureSpec, newHeightMeasureSpec);
        // Flag if we are measured with width or height less than the respective min.
        final int widthSize = resolveSizeAndStateRespectingMinSize(mMinWidth, getMeasuredWidth(),
                widthMeasureSpec);
        final int heightSize = resolveSizeAndStateRespectingMinSize(mMinHeight, getMeasuredHeight(),
                heightMeasureSpec);
        setMeasuredDimension(widthSize, heightSize);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!isEnabled() || !mFlingable) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastMotionEventY = mLastDownEventY = event.getY();
                removeAllCallbacks();
                mShowInputControlsAnimator.cancel();
                mDimSelectorWheelAnimator.cancel();
                mBeginEditOnUpEvent = false;
                mAdjustScrollerOnUpEvent = true;
                if (mSelectorWheelState == SELECTOR_WHEEL_STATE_LARGE) {
                    mSelectorWheelPaint.setAlpha(SELECTOR_WHEEL_BRIGHT_ALPHA);
                    boolean scrollersFinished = mFlingScroller.isFinished()
                            && mAdjustScroller.isFinished();
                    if (!scrollersFinished) {
                        mFlingScroller.forceFinished(true);
                        mAdjustScroller.forceFinished(true);
                        onScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                    }
                    mBeginEditOnUpEvent = scrollersFinished;
                    mAdjustScrollerOnUpEvent = true;
                    hideSoftInput();
                    hideInputControls();
                    return true;
                }
                if (isEventInVisibleViewHitRect(event, mIncrementButton)
                        || isEventInVisibleViewHitRect(event, mDecrementButton)) {
                    return false;
                }
                mAdjustScrollerOnUpEvent = false;
                setSelectorWheelState(SELECTOR_WHEEL_STATE_LARGE);
                hideSoftInput();
                hideInputControls();
                return true;
            case MotionEvent.ACTION_MOVE:
                float currentMoveY = event.getY();
                int deltaDownY = (int) Math.abs(currentMoveY - mLastDownEventY);
                if (deltaDownY > mTouchSlop) {
                    mBeginEditOnUpEvent = false;
                    onScrollStateChange(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                    setSelectorWheelState(SELECTOR_WHEEL_STATE_LARGE);
                    hideSoftInput();
                    hideInputControls();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isEnabled()) {
            return false;
        }
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                float currentMoveY = ev.getY();
                if (mBeginEditOnUpEvent
                        || mScrollState != OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                    int deltaDownY = (int) Math.abs(currentMoveY - mLastDownEventY);
                    if (deltaDownY > mTouchSlop) {
                        mBeginEditOnUpEvent = false;
                        onScrollStateChange(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                    }
                }
                int deltaMoveY = (int) (currentMoveY - mLastMotionEventY);
                scrollBy(0, deltaMoveY);
                invalidate();
                mLastMotionEventY = currentMoveY;
                break;
            case MotionEvent.ACTION_UP:
                if (mBeginEditOnUpEvent) {
                    setSelectorWheelState(SELECTOR_WHEEL_STATE_SMALL);
                    showInputControls(mShowInputControlsAnimimationDuration);
                    mInputText.requestFocus();
                    return true;
                }
                VelocityTracker velocityTracker = mVelocityTracker;
                velocityTracker.computeCurrentVelocity(1000, mMaximumFlingVelocity);
                int initialVelocity = (int) velocityTracker.getYVelocity();
                if (Math.abs(initialVelocity) > mMinimumFlingVelocity) {
                    fling(initialVelocity);
                    onScrollStateChange(OnScrollListener.SCROLL_STATE_FLING);
                } else {
                    if (mAdjustScrollerOnUpEvent) {
                        if (mFlingScroller.isFinished() && mAdjustScroller.isFinished()) {
                            postAdjustScrollerCommand(0);
                        }
                    } else {
                        postAdjustScrollerCommand(SHOW_INPUT_CONTROLS_DELAY_MILLIS);
                    }
                }
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                break;
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (mSelectorWheelState == SELECTOR_WHEEL_STATE_LARGE) {
                    removeAllCallbacks();
                    forceCompleteChangeCurrentByOneViaScroll();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                removeAllCallbacks();
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            removeAllCallbacks();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            removeAllCallbacks();
        }
        return super.dispatchTrackballEvent(event);
    }

    @Override
    public void computeScroll() {
        if (mSelectorWheelState == SELECTOR_WHEEL_STATE_NONE) {
            return;
        }
        Scroller scroller = mFlingScroller;
        if (scroller.isFinished()) {
            scroller = mAdjustScroller;
            if (scroller.isFinished()) {
                return;
            }
        }
        scroller.computeScrollOffset();
        int currentScrollerY = scroller.getCurrY();
        if (mPreviousScrollerY == 0) {
            mPreviousScrollerY = scroller.getStartY();
        }
        scrollBy(0, currentScrollerY - mPreviousScrollerY);
        mPreviousScrollerY = currentScrollerY;
        if (scroller.isFinished()) {
            onScrollerFinished(scroller);
        } else {
            invalidate();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mIncrementButton.setEnabled(enabled);
        mDecrementButton.setEnabled(enabled);
        mInputText.setEnabled(enabled);
    }

    @Override
    public void scrollBy(int x, int y) {
        if (mSelectorWheelState == SELECTOR_WHEEL_STATE_NONE) {
            return;
        }
        int[] selectorIndices = mSelectorIndices;
        if (!mWrapSelectorWheel && y > 0
                && selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] <= mMinValue) {
            mCurrentScrollOffset = mInitialScrollOffset;
            return;
        }
        if (!mWrapSelectorWheel && y < 0
                && selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] >= mMaxValue) {
            mCurrentScrollOffset = mInitialScrollOffset;
            return;
        }
        mCurrentScrollOffset += y;
        while (mCurrentScrollOffset - mInitialScrollOffset > mSelectorTextGapHeight) {
            mCurrentScrollOffset -= mSelectorElementHeight;
            decrementSelectorIndices(selectorIndices);
            changeCurrent(selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX]);
            if (!mWrapSelectorWheel && selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] <= mMinValue) {
                mCurrentScrollOffset = mInitialScrollOffset;
            }
        }
        while (mCurrentScrollOffset - mInitialScrollOffset < -mSelectorTextGapHeight) {
            mCurrentScrollOffset += mSelectorElementHeight;
            incrementSelectorIndices(selectorIndices);
            changeCurrent(selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX]);
            if (!mWrapSelectorWheel && selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] >= mMaxValue) {
                mCurrentScrollOffset = mInitialScrollOffset;
            }
        }
    }

    @Override
    public int getSolidColor() {
        return mSolidColor;
    }

    /**
     * Sets the listener to be notified on change of the current value.
     *
     * @param onValueChangedListener The listener.
     */
    public void setOnValueChangedListener(OnValueChangeListener onValueChangedListener) {
        mOnValueChangeListener = onValueChangedListener;
    }

    /**
     * Set listener to be notified for scroll state changes.
     *
     * @param onScrollListener The listener.
     */
    public void setOnScrollListener(OnScrollListener onScrollListener) {
        mOnScrollListener = onScrollListener;
    }

    /**
     * Set the formatter to be used for formatting the current value.
     * <p>
     * Note: If you have provided alternative values for the values this
     * formatter is never invoked.
     * </p>
     *
     * @param formatter The formatter object. If formatter is <code>null</code>,
     *            {@link String#valueOf(int)} will be used.
     *
     * @see #setDisplayedValues(String[])
     */
    public void setFormatter(Formatter formatter) {
        if (formatter == mFormatter) {
            return;
        }
        mFormatter = formatter;
        initializeSelectorWheelIndices();
        updateInputTextView();
    }

    /**
     * Set the current value for the number picker.
     * <p>
     * If the argument is less than the {@link NumberPicker#getMinValue()} and
     * {@link NumberPicker#getWrapSelectorWheel()} is <code>false</code> the
     * current value is set to the {@link NumberPicker#getMinValue()} value.
     * </p>
     * <p>
     * If the argument is less than the {@link NumberPicker#getMinValue()} and
     * {@link NumberPicker#getWrapSelectorWheel()} is <code>true</code> the
     * current value is set to the {@link NumberPicker#getMaxValue()} value.
     * </p>
     * <p>
     * If the argument is less than the {@link NumberPicker#getMaxValue()} and
     * {@link NumberPicker#getWrapSelectorWheel()} is <code>false</code> the
     * current value is set to the {@link NumberPicker#getMaxValue()} value.
     * </p>
     * <p>
     * If the argument is less than the {@link NumberPicker#getMaxValue()} and
     * {@link NumberPicker#getWrapSelectorWheel()} is <code>true</code> the
     * current value is set to the {@link NumberPicker#getMinValue()} value.
     * </p>
     *
     * @param value The current value.
     * @see #setWrapSelectorWheel(boolean)
     * @see #setMinValue(int)
     * @see #setMaxValue(int)
     */
    public void setValue(int value) {
        if (mValue == value) {
            return;
        }
        if (value < mMinValue) {
            value = mWrapSelectorWheel ? mMaxValue : mMinValue;
        }
        if (value > mMaxValue) {
            value = mWrapSelectorWheel ? mMinValue : mMaxValue;
        }
        mValue = value;
        initializeSelectorWheelIndices();
        updateInputTextView();
        updateIncrementAndDecrementButtonsVisibilityState();
        invalidate();
    }

    /**
     * Hides the soft input of it is active for the input text.
     */
    private void hideSoftInput() {
        InputMethodManager inputMethodManager = InputMethodManager.peekInstance();
        if (inputMethodManager != null && inputMethodManager.isActive(mInputText)) {
            inputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    /**
     * Computes the max width if no such specified as an attribute.
     */
    private void tryComputeMaxWidth() {
        if (!mComputeMaxWidth) {
            return;
        }
        int maxTextWidth = 0;
        if (mDisplayedValues == null) {
            float maxDigitWidth = 0;
            for (int i = 0; i <= 9; i++) {
                final float digitWidth = mSelectorWheelPaint.measureText(String.valueOf(i));
                if (digitWidth > maxDigitWidth) {
                    maxDigitWidth = digitWidth;
                }
            }
            int numberOfDigits = 0;
            int current = mMaxValue;
            while (current > 0) {
                numberOfDigits++;
                current = current / 10;
            }
            maxTextWidth = (int) (numberOfDigits * maxDigitWidth);
        } else {
            final int valueCount = mDisplayedValues.length;
            for (int i = 0; i < valueCount; i++) {
                final float textWidth = mSelectorWheelPaint.measureText(mDisplayedValues[i]);
                if (textWidth > maxTextWidth) {
                    maxTextWidth = (int) textWidth;
                }
            }
        }
        maxTextWidth += mInputText.getPaddingLeft() + mInputText.getPaddingRight();
        if (mMaxWidth != maxTextWidth) {
            if (maxTextWidth > mMinWidth) {
                mMaxWidth = maxTextWidth;
            } else {
                mMaxWidth = mMinWidth;
            }
            invalidate();
        }
    }

    /**
     * Gets whether the selector wheel wraps when reaching the min/max value.
     *
     * @return True if the selector wheel wraps.
     *
     * @see #getMinValue()
     * @see #getMaxValue()
     */
    public boolean getWrapSelectorWheel() {
        return mWrapSelectorWheel;
    }

    /**
     * Sets whether the selector wheel shown during flinging/scrolling should
     * wrap around the {@link NumberPicker#getMinValue()} and
     * {@link NumberPicker#getMaxValue()} values.
     * <p>
     * By default if the range (max - min) is more than five (the number of
     * items shown on the selector wheel) the selector wheel wrapping is
     * enabled.
     * </p>
     *
     * @param wrapSelectorWheel Whether to wrap.
     */
    public void setWrapSelectorWheel(boolean wrapSelectorWheel) {
        if (wrapSelectorWheel && (mMaxValue - mMinValue) < mSelectorIndices.length) {
            throw new IllegalStateException("Range less than selector items count.");
        }
        if (wrapSelectorWheel != mWrapSelectorWheel) {
            mWrapSelectorWheel = wrapSelectorWheel;
            updateIncrementAndDecrementButtonsVisibilityState();
        }
    }

    /**
     * Sets the speed at which the numbers be incremented and decremented when
     * the up and down buttons are long pressed respectively.
     * <p>
     * The default value is 300 ms.
     * </p>
     *
     * @param intervalMillis The speed (in milliseconds) at which the numbers
     *            will be incremented and decremented.
     */
    public void setOnLongPressUpdateInterval(long intervalMillis) {
        mLongPressUpdateInterval = intervalMillis;
    }

    /**
     * Returns the value of the picker.
     *
     * @return The value.
     */
    public int getValue() {
        return mValue;
    }

    /**
     * Returns the min value of the picker.
     *
     * @return The min value
     */
    public int getMinValue() {
        return mMinValue;
    }

    /**
     * Sets the min value of the picker.
     *
     * @param minValue The min value.
     */
    public void setMinValue(int minValue) {
        if (mMinValue == minValue) {
            return;
        }
        if (minValue < 0) {
            throw new IllegalArgumentException("minValue must be >= 0");
        }
        mMinValue = minValue;
        if (mMinValue > mValue) {
            mValue = mMinValue;
        }
        boolean wrapSelectorWheel = mMaxValue - mMinValue > mSelectorIndices.length;
        setWrapSelectorWheel(wrapSelectorWheel);
        initializeSelectorWheelIndices();
        updateInputTextView();
        tryComputeMaxWidth();
    }

    /**
     * Returns the max value of the picker.
     *
     * @return The max value.
     */
    public int getMaxValue() {
        return mMaxValue;
    }

    /**
     * Sets the max value of the picker.
     *
     * @param maxValue The max value.
     */
    public void setMaxValue(int maxValue) {
        if (mMaxValue == maxValue) {
            return;
        }
        if (maxValue < 0) {
            throw new IllegalArgumentException("maxValue must be >= 0");
        }
        mMaxValue = maxValue;
        if (mMaxValue < mValue) {
            mValue = mMaxValue;
        }
        boolean wrapSelectorWheel = mMaxValue - mMinValue > mSelectorIndices.length;
        setWrapSelectorWheel(wrapSelectorWheel);
        initializeSelectorWheelIndices();
        updateInputTextView();
        tryComputeMaxWidth();
    }

    /**
     * Gets the values to be displayed instead of string values.
     *
     * @return The displayed values.
     */
    public String[] getDisplayedValues() {
        return mDisplayedValues;
    }

    /**
     * Sets the values to be displayed.
     *
     * @param displayedValues The displayed values.
     */
    public void setDisplayedValues(String[] displayedValues) {
        if (mDisplayedValues == displayedValues) {
            return;
        }
        mDisplayedValues = displayedValues;
        if (mDisplayedValues != null) {
            // Allow text entry rather than strictly numeric entry.
            mInputText.setRawInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            mInputText.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        }
        updateInputTextView();
        initializeSelectorWheelIndices();
        tryComputeMaxWidth();
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return TOP_AND_BOTTOM_FADING_EDGE_STRENGTH;
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return TOP_AND_BOTTOM_FADING_EDGE_STRENGTH;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // make sure we show the controls only the very
        // first time the user sees this widget
        if (mFlingable && !isInEditMode()) {
            // animate a bit slower the very first time
            showInputControls(mShowInputControlsAnimimationDuration * 2);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeAllCallbacks();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // There is a good reason for doing this. See comments in draw().
    }

    @Override
    public void draw(Canvas canvas) {
        // Dispatch draw to our children only if we are not currently running
        // the animation for simultaneously dimming the scroll wheel and
        // showing in the buttons. This class takes advantage of the View
        // implementation of fading edges effect to draw the selector wheel.
        // However, in View.draw(), the fading is applied after all the children
        // have been drawn and we do not want this fading to be applied to the
        // buttons. Therefore, we draw our children after we have completed
        // drawing ourselves.
        super.draw(canvas);

        // Draw our children if we are not showing the selector wheel of fading
        // it out
        if (mShowInputControlsAnimator.isRunning()
                || mSelectorWheelState != SELECTOR_WHEEL_STATE_LARGE) {
            long drawTime = getDrawingTime();
            for (int i = 0, count = getChildCount(); i < count; i++) {
                View child = getChildAt(i);
                if (!child.isShown()) {
                    continue;
                }
                drawChild(canvas, getChildAt(i), drawTime);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSelectorWheelState == SELECTOR_WHEEL_STATE_NONE) {
            return;
        }

        float x = (mRight - mLeft) / 2;
        float y = mCurrentScrollOffset;

        final int restoreCount = canvas.save();

        if (mSelectorWheelState == SELECTOR_WHEEL_STATE_SMALL) {
            Rect clipBounds = canvas.getClipBounds();
            clipBounds.inset(0, mSelectorElementHeight);
            canvas.clipRect(clipBounds);
        }

        // draw the selector wheel
        int[] selectorIndices = mSelectorIndices;
        for (int i = 0; i < selectorIndices.length; i++) {
            int selectorIndex = selectorIndices[i];
            String scrollSelectorValue = mSelectorIndexToStringCache.get(selectorIndex);
            // Do not draw the middle item if input is visible since the input is shown only
            // if the wheel is static and it covers the middle item. Otherwise, if the user
            // starts editing the text via the IME he may see a dimmed version of the old
            // value intermixed with the new one.
            if (i != SELECTOR_MIDDLE_ITEM_INDEX || mInputText.getVisibility() != VISIBLE) {
                canvas.drawText(scrollSelectorValue, x, y, mSelectorWheelPaint);
            }
            y += mSelectorElementHeight;
        }

        // draw the selection dividers (only if scrolling and drawable specified)
        if (mSelectionDivider != null) {
            // draw the top divider
            int topOfTopDivider =
                (getHeight() - mSelectorElementHeight - mSelectionDividerHeight) / 2;
            int bottomOfTopDivider = topOfTopDivider + mSelectionDividerHeight;
            mSelectionDivider.setBounds(0, topOfTopDivider, mRight, bottomOfTopDivider);
            mSelectionDivider.draw(canvas);

            // draw the bottom divider
            int topOfBottomDivider =  topOfTopDivider + mSelectorElementHeight;
            int bottomOfBottomDivider = bottomOfTopDivider + mSelectorElementHeight;
            mSelectionDivider.setBounds(0, topOfBottomDivider, mRight, bottomOfBottomDivider);
            mSelectionDivider.draw(canvas);
        }

        canvas.restoreToCount(restoreCount);
    }

    @Override
    public void sendAccessibilityEvent(int eventType) {
        // Do not send accessibility events - we want the user to
        // perceive this widget as several controls rather as a whole.
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(NumberPicker.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(NumberPicker.class.getName());
    }

    /**
     * Makes a measure spec that tries greedily to use the max value.
     *
     * @param measureSpec The measure spec.
     * @param maxSize The max value for the size.
     * @return A measure spec greedily imposing the max size.
     */
    private int makeMeasureSpec(int measureSpec, int maxSize) {
        if (maxSize == SIZE_UNSPECIFIED) {
            return measureSpec;
        }
        final int size = MeasureSpec.getSize(measureSpec);
        final int mode = MeasureSpec.getMode(measureSpec);
        switch (mode) {
            case MeasureSpec.EXACTLY:
                return measureSpec;
            case MeasureSpec.AT_MOST:
                return MeasureSpec.makeMeasureSpec(Math.min(size, maxSize), MeasureSpec.EXACTLY);
            case MeasureSpec.UNSPECIFIED:
                return MeasureSpec.makeMeasureSpec(maxSize, MeasureSpec.EXACTLY);
            default:
                throw new IllegalArgumentException("Unknown measure mode: " + mode);
        }
    }

    /**
     * Utility to reconcile a desired size and state, with constraints imposed by
     * a MeasureSpec. Tries to respect the min size, unless a different size is
     * imposed by the constraints.
     *
     * @param minSize The minimal desired size.
     * @param measuredSize The currently measured size.
     * @param measureSpec The current measure spec.
     * @return The resolved size and state.
     */
    private int resolveSizeAndStateRespectingMinSize(int minSize, int measuredSize,
            int measureSpec) {
        if (minSize != SIZE_UNSPECIFIED) {
            final int desiredWidth = Math.max(minSize, measuredSize);
            return resolveSizeAndState(desiredWidth, measureSpec, 0);
        } else {
            return measuredSize;
        }
    }

    /**
     * Resets the selector indices and clear the cached
     * string representation of these indices.
     */
    private void initializeSelectorWheelIndices() {
        mSelectorIndexToStringCache.clear();
        int[] selectorIdices = mSelectorIndices;
        int current = getValue();
        for (int i = 0; i < mSelectorIndices.length; i++) {
            int selectorIndex = current + (i - SELECTOR_MIDDLE_ITEM_INDEX);
            if (mWrapSelectorWheel) {
                selectorIndex = getWrappedSelectorIndex(selectorIndex);
            }
            mSelectorIndices[i] = selectorIndex;
            ensureCachedScrollSelectorValue(mSelectorIndices[i]);
        }
    }

    /**
     * Sets the current value of this NumberPicker, and sets mPrevious to the
     * previous value. If current is greater than mEnd less than mStart, the
     * value of mCurrent is wrapped around. Subclasses can override this to
     * change the wrapping behavior
     *
     * @param current the new value of the NumberPicker
     */
    private void changeCurrent(int current) {
        if (mValue == current) {
            return;
        }
        // Wrap around the values if we go past the start or end
        if (mWrapSelectorWheel) {
            current = getWrappedSelectorIndex(current);
        }
        int previous = mValue;
        setValue(current);
        notifyChange(previous, current);
    }

    /**
     * Changes the current value by one which is increment or
     * decrement based on the passes argument.
     *
     * @param increment True to increment, false to decrement.
     */
    private void changeCurrentByOne(boolean increment) {
        if (mFlingable) {
            mDimSelectorWheelAnimator.cancel();
            mInputText.setVisibility(View.INVISIBLE);
            mSelectorWheelPaint.setAlpha(SELECTOR_WHEEL_BRIGHT_ALPHA);
            mPreviousScrollerY = 0;
            forceCompleteChangeCurrentByOneViaScroll();
            if (increment) {
                mFlingScroller.startScroll(0, 0, 0, -mSelectorElementHeight,
                        CHANGE_CURRENT_BY_ONE_SCROLL_DURATION);
            } else {
                mFlingScroller.startScroll(0, 0, 0, mSelectorElementHeight,
                        CHANGE_CURRENT_BY_ONE_SCROLL_DURATION);
            }
            invalidate();
        } else {
            if (increment) {
                changeCurrent(mValue + 1);
            } else {
                changeCurrent(mValue - 1);
            }
        }
    }

    /**
     * Ensures that if we are in the process of changing the current value
     * by one via scrolling the scroller gets to its final state and the
     * value is updated.
     */
    private void forceCompleteChangeCurrentByOneViaScroll() {
        Scroller scroller = mFlingScroller;
        if (!scroller.isFinished()) {
            final int yBeforeAbort = scroller.getCurrY();
            scroller.abortAnimation();
            final int yDelta = scroller.getCurrY() - yBeforeAbort;
            scrollBy(0, yDelta);
        }
    }

    /**
     * Sets the <code>alpha</code> of the {@link Paint} for drawing the selector
     * wheel.
     */
    @SuppressWarnings("unused")
    // Called via reflection
    private void setSelectorPaintAlpha(int alpha) {
        mSelectorWheelPaint.setAlpha(alpha);
        invalidate();
    }

    /**
     * @return If the <code>event</code> is in the visible <code>view</code>.
     */
    private boolean isEventInVisibleViewHitRect(MotionEvent event, View view) {
        if (view.getVisibility() == VISIBLE) {
            view.getHitRect(mTempRect);
            return mTempRect.contains((int) event.getX(), (int) event.getY());
        }
        return false;
    }

    /**
     * Sets the <code>selectorWheelState</code>.
     */
    private void setSelectorWheelState(int selectorWheelState) {
        mSelectorWheelState = selectorWheelState;
        if (selectorWheelState == SELECTOR_WHEEL_STATE_LARGE) {
            mSelectorWheelPaint.setAlpha(SELECTOR_WHEEL_BRIGHT_ALPHA);
        }

        if (mFlingable && selectorWheelState == SELECTOR_WHEEL_STATE_LARGE
                && AccessibilityManager.getInstance(mContext).isEnabled()) {
            AccessibilityManager.getInstance(mContext).interrupt();
            String text = mContext.getString(R.string.number_picker_increment_scroll_action);
            mInputText.setContentDescription(text);
            mInputText.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            mInputText.setContentDescription(null);
        }
    }

    private void initializeSelectorWheel() {
        initializeSelectorWheelIndices();
        int[] selectorIndices = mSelectorIndices;
        int totalTextHeight = selectorIndices.length * mTextSize;
        float totalTextGapHeight = (mBottom - mTop) - totalTextHeight;
        float textGapCount = selectorIndices.length - 1;
        mSelectorTextGapHeight = (int) (totalTextGapHeight / textGapCount + 0.5f);
        mSelectorElementHeight = mTextSize + mSelectorTextGapHeight;
        // Ensure that the middle item is positioned the same as the text in mInputText
        int editTextTextPosition = mInputText.getBaseline() + mInputText.getTop();
        mInitialScrollOffset = editTextTextPosition -
                (mSelectorElementHeight * SELECTOR_MIDDLE_ITEM_INDEX);
        mCurrentScrollOffset = mInitialScrollOffset;
        updateInputTextView();
    }

    private void initializeFadingEdges() {
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength((mBottom - mTop - mTextSize) / 2);
    }

    /**
     * Callback invoked upon completion of a given <code>scroller</code>.
     */
    private void onScrollerFinished(Scroller scroller) {
        if (scroller == mFlingScroller) {
            if (mSelectorWheelState == SELECTOR_WHEEL_STATE_LARGE) {
                postAdjustScrollerCommand(0);
                onScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
            } else {
                updateInputTextView();
                fadeSelectorWheel(mShowInputControlsAnimimationDuration);
            }
        } else {
            updateInputTextView();
            showInputControls(mShowInputControlsAnimimationDuration);
        }
    }

    /**
     * Handles transition to a given <code>scrollState</code>
     */
    private void onScrollStateChange(int scrollState) {
        if (mScrollState == scrollState) {
            return;
        }
        mScrollState = scrollState;
        if (mOnScrollListener != null) {
            mOnScrollListener.onScrollStateChange(this, scrollState);
        }
    }

    /**
     * Flings the selector with the given <code>velocityY</code>.
     */
    private void fling(int velocityY) {
        mPreviousScrollerY = 0;

        if (velocityY > 0) {
            mFlingScroller.fling(0, 0, 0, velocityY, 0, 0, 0, Integer.MAX_VALUE);
        } else {
            mFlingScroller.fling(0, Integer.MAX_VALUE, 0, velocityY, 0, 0, 0, Integer.MAX_VALUE);
        }

        invalidate();
    }

    /**
     * Hides the input controls which is the up/down arrows and the text field.
     */
    private void hideInputControls() {
        mShowInputControlsAnimator.cancel();
        mIncrementButton.setVisibility(INVISIBLE);
        mDecrementButton.setVisibility(INVISIBLE);
        mInputText.setVisibility(INVISIBLE);
    }

    /**
     * Show the input controls by making them visible and animating the alpha
     * property up/down arrows.
     *
     * @param animationDuration The duration of the animation.
     */
    private void showInputControls(long animationDuration) {
        updateIncrementAndDecrementButtonsVisibilityState();
        mInputText.setVisibility(VISIBLE);
        mShowInputControlsAnimator.setDuration(animationDuration);
        mShowInputControlsAnimator.start();
    }

    /**
     * Fade the selector wheel via an animation.
     *
     * @param animationDuration The duration of the animation.
     */
    private void fadeSelectorWheel(long animationDuration) {
        mInputText.setVisibility(VISIBLE);
        mDimSelectorWheelAnimator.setDuration(animationDuration);
        mDimSelectorWheelAnimator.start();
    }

    /**
     * Updates the visibility state of the increment and decrement buttons.
     */
    private void updateIncrementAndDecrementButtonsVisibilityState() {
        if (mWrapSelectorWheel || mValue < mMaxValue) {
            mIncrementButton.setVisibility(VISIBLE);
        } else {
            mIncrementButton.setVisibility(INVISIBLE);
        }
        if (mWrapSelectorWheel || mValue > mMinValue) {
            mDecrementButton.setVisibility(VISIBLE);
        } else {
            mDecrementButton.setVisibility(INVISIBLE);
        }
    }

    /**
     * @return The wrapped index <code>selectorIndex</code> value.
     */
    private int getWrappedSelectorIndex(int selectorIndex) {
        if (selectorIndex > mMaxValue) {
            return mMinValue + (selectorIndex - mMaxValue) % (mMaxValue - mMinValue) - 1;
        } else if (selectorIndex < mMinValue) {
            return mMaxValue - (mMinValue - selectorIndex) % (mMaxValue - mMinValue) + 1;
        }
        return selectorIndex;
    }

    /**
     * Increments the <code>selectorIndices</code> whose string representations
     * will be displayed in the selector.
     */
    private void incrementSelectorIndices(int[] selectorIndices) {
        for (int i = 0; i < selectorIndices.length - 1; i++) {
            selectorIndices[i] = selectorIndices[i + 1];
        }
        int nextScrollSelectorIndex = selectorIndices[selectorIndices.length - 2] + 1;
        if (mWrapSelectorWheel && nextScrollSelectorIndex > mMaxValue) {
            nextScrollSelectorIndex = mMinValue;
        }
        selectorIndices[selectorIndices.length - 1] = nextScrollSelectorIndex;
        ensureCachedScrollSelectorValue(nextScrollSelectorIndex);
    }

    /**
     * Decrements the <code>selectorIndices</code> whose string representations
     * will be displayed in the selector.
     */
    private void decrementSelectorIndices(int[] selectorIndices) {
        for (int i = selectorIndices.length - 1; i > 0; i--) {
            selectorIndices[i] = selectorIndices[i - 1];
        }
        int nextScrollSelectorIndex = selectorIndices[1] - 1;
        if (mWrapSelectorWheel && nextScrollSelectorIndex < mMinValue) {
            nextScrollSelectorIndex = mMaxValue;
        }
        selectorIndices[0] = nextScrollSelectorIndex;
        ensureCachedScrollSelectorValue(nextScrollSelectorIndex);
    }

    /**
     * Ensures we have a cached string representation of the given <code>
     * selectorIndex</code>
     * to avoid multiple instantiations of the same string.
     */
    private void ensureCachedScrollSelectorValue(int selectorIndex) {
        SparseArray<String> cache = mSelectorIndexToStringCache;
        String scrollSelectorValue = cache.get(selectorIndex);
        if (scrollSelectorValue != null) {
            return;
        }
        if (selectorIndex < mMinValue || selectorIndex > mMaxValue) {
            scrollSelectorValue = "";
        } else {
            if (mDisplayedValues != null) {
                int displayedValueIndex = selectorIndex - mMinValue;
                scrollSelectorValue = mDisplayedValues[displayedValueIndex];
            } else {
                scrollSelectorValue = formatNumber(selectorIndex);
            }
        }
        cache.put(selectorIndex, scrollSelectorValue);
    }

    private String formatNumber(int value) {
        return (mFormatter != null) ? mFormatter.format(value) : String.valueOf(value);
    }

    private void validateInputTextView(View v) {
        String str = String.valueOf(((TextView) v).getText());
        if (TextUtils.isEmpty(str)) {
            // Restore to the old value as we don't allow empty values
            updateInputTextView();
        } else {
            // Check the new value and ensure it's in range
            int current = getSelectedPos(str.toString());
            changeCurrent(current);
        }
    }

    /**
     * Updates the view of this NumberPicker. If displayValues were specified in
     * the string corresponding to the index specified by the current value will
     * be returned. Otherwise, the formatter specified in {@link #setFormatter}
     * will be used to format the number.
     */
    private void updateInputTextView() {
        /*
         * If we don't have displayed values then use the current number else
         * find the correct value in the displayed values for the current
         * number.
         */
        if (mDisplayedValues == null) {
            mInputText.setText(formatNumber(mValue));
        } else {
            mInputText.setText(mDisplayedValues[mValue - mMinValue]);
        }
        mInputText.setSelection(mInputText.getText().length());

        if (mFlingable && AccessibilityManager.getInstance(mContext).isEnabled()) {
            String text = mContext.getString(R.string.number_picker_increment_scroll_mode,
                    mInputText.getText());
            mInputText.setContentDescription(text);
        }
    }

    /**
     * Notifies the listener, if registered, of a change of the value of this
     * NumberPicker.
     */
    private void notifyChange(int previous, int current) {
        if (mOnValueChangeListener != null) {
            mOnValueChangeListener.onValueChange(this, previous, mValue);
        }
    }

    /**
     * Posts a command for changing the current value by one.
     *
     * @param increment Whether to increment or decrement the value.
     */
    private void postChangeCurrentByOneFromLongPress(boolean increment) {
        mInputText.clearFocus();
        removeAllCallbacks();
        if (mChangeCurrentByOneFromLongPressCommand == null) {
            mChangeCurrentByOneFromLongPressCommand = new ChangeCurrentByOneFromLongPressCommand();
        }
        mChangeCurrentByOneFromLongPressCommand.setIncrement(increment);
        post(mChangeCurrentByOneFromLongPressCommand);
    }

    /**
     * Removes all pending callback from the message queue.
     */
    private void removeAllCallbacks() {
        if (mChangeCurrentByOneFromLongPressCommand != null) {
            removeCallbacks(mChangeCurrentByOneFromLongPressCommand);
        }
        if (mAdjustScrollerCommand != null) {
            removeCallbacks(mAdjustScrollerCommand);
        }
        if (mSetSelectionCommand != null) {
            removeCallbacks(mSetSelectionCommand);
        }
    }

    /**
     * @return The selected index given its displayed <code>value</code>.
     */
    private int getSelectedPos(String value) {
        if (mDisplayedValues == null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // Ignore as if it's not a number we don't care
            }
        } else {
            for (int i = 0; i < mDisplayedValues.length; i++) {
                // Don't force the user to type in jan when ja will do
                value = value.toLowerCase();
                if (mDisplayedValues[i].toLowerCase().startsWith(value)) {
                    return mMinValue + i;
                }
            }

            /*
             * The user might have typed in a number into the month field i.e.
             * 10 instead of OCT so support that too.
             */
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {

                // Ignore as if it's not a number we don't care
            }
        }
        return mMinValue;
    }

    /**
     * Posts an {@link SetSelectionCommand} from the given <code>selectionStart
     * </code> to
     * <code>selectionEnd</code>.
     */
    private void postSetSelectionCommand(int selectionStart, int selectionEnd) {
        if (mSetSelectionCommand == null) {
            mSetSelectionCommand = new SetSelectionCommand();
        } else {
            removeCallbacks(mSetSelectionCommand);
        }
        mSetSelectionCommand.mSelectionStart = selectionStart;
        mSetSelectionCommand.mSelectionEnd = selectionEnd;
        post(mSetSelectionCommand);
    }

    /**
     * Posts an {@link AdjustScrollerCommand} within the given <code>
     * delayMillis</code>
     * .
     */
    private void postAdjustScrollerCommand(int delayMillis) {
        if (mAdjustScrollerCommand == null) {
            mAdjustScrollerCommand = new AdjustScrollerCommand();
        } else {
            removeCallbacks(mAdjustScrollerCommand);
        }
        postDelayed(mAdjustScrollerCommand, delayMillis);
    }

    /**
     * Filter for accepting only valid indices or prefixes of the string
     * representation of valid indices.
     */
    class InputTextFilter extends NumberKeyListener {

        // XXX This doesn't allow for range limits when controlled by a
        // soft input method!
        public int getInputType() {
            return InputType.TYPE_CLASS_TEXT;
        }

        @Override
        protected char[] getAcceptedChars() {
            return DIGIT_CHARACTERS;
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
                int dstart, int dend) {
            if (mDisplayedValues == null) {
                CharSequence filtered = super.filter(source, start, end, dest, dstart, dend);
                if (filtered == null) {
                    filtered = source.subSequence(start, end);
                }

                String result = String.valueOf(dest.subSequence(0, dstart)) + filtered
                        + dest.subSequence(dend, dest.length());

                if ("".equals(result)) {
                    return result;
                }
                int val = getSelectedPos(result);

                /*
                 * Ensure the user can't type in a value greater than the max
                 * allowed. We have to allow less than min as the user might
                 * want to delete some numbers and then type a new number.
                 */
                if (val > mMaxValue) {
                    return "";
                } else {
                    return filtered;
                }
            } else {
                CharSequence filtered = String.valueOf(source.subSequence(start, end));
                if (TextUtils.isEmpty(filtered)) {
                    return "";
                }
                String result = String.valueOf(dest.subSequence(0, dstart)) + filtered
                        + dest.subSequence(dend, dest.length());
                String str = String.valueOf(result).toLowerCase();
                for (String val : mDisplayedValues) {
                    String valLowerCase = val.toLowerCase();
                    if (valLowerCase.startsWith(str)) {
                        postSetSelectionCommand(result.length(), val.length());
                        return val.subSequence(dstart, val.length());
                    }
                }
                return "";
            }
        }
    }

    /**
     * Command for setting the input text selection.
     */
    class SetSelectionCommand implements Runnable {
        private int mSelectionStart;

        private int mSelectionEnd;

        public void run() {
            mInputText.setSelection(mSelectionStart, mSelectionEnd);
        }
    }

    /**
     * Command for adjusting the scroller to show in its center the closest of
     * the displayed items.
     */
    class AdjustScrollerCommand implements Runnable {
        public void run() {
            mPreviousScrollerY = 0;
            if (mInitialScrollOffset == mCurrentScrollOffset) {
                updateInputTextView();
                showInputControls(mShowInputControlsAnimimationDuration);
                return;
            }
            // adjust to the closest value
            int deltaY = mInitialScrollOffset - mCurrentScrollOffset;
            if (Math.abs(deltaY) > mSelectorElementHeight / 2) {
                deltaY += (deltaY > 0) ? -mSelectorElementHeight : mSelectorElementHeight;
            }
            mAdjustScroller.startScroll(0, 0, 0, deltaY, SELECTOR_ADJUSTMENT_DURATION_MILLIS);
            invalidate();
        }
    }

    /**
     * Command for changing the current value from a long press by one.
     */
    class ChangeCurrentByOneFromLongPressCommand implements Runnable {
        private boolean mIncrement;

        private void setIncrement(boolean increment) {
            mIncrement = increment;
        }

        public void run() {
            changeCurrentByOne(mIncrement);
            postDelayed(this, mLongPressUpdateInterval);
        }
    }
}
