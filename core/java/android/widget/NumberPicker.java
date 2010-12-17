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

import com.android.internal.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.Widget;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.NumberKeyListener;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.LayoutInflater.Filter;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;

/**
 * A widget that enables the user to select a number form a predefined range.
 * The widget presents an input filed and up and down buttons for selecting the
 * current value. Pressing/long pressing the up and down buttons increments and
 * decrements the current value respectively. Touching the input filed shows a
 * scroll wheel, tapping on which while shown and not moving allows direct edit
 * of the current value. Sliding motions up or down hide the buttons and the
 * input filed, show the scroll wheel, and rotate the latter. Flinging is
 * also supported. The widget enables mapping from positions to strings such
 * that instead the position index the corresponding string is displayed.
 * <p>
 * For an example of using this widget, see {@link android.widget.TimePicker}.
 * </p>
 */
@Widget
public class NumberPicker extends LinearLayout {

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
     * The the delay for showing the input controls after a single tap on the
     * input text.
     */
    private static final int SHOW_INPUT_CONTROLS_DELAY_MILLIS = ViewConfiguration
            .getDoubleTapTimeout();

    /**
     * The update step for incrementing the current value.
     */
    private static final int UPDATE_STEP_INCREMENT = 1;

    /**
     * The update step for decrementing the current value.
     */
    private static final int UPDATE_STEP_DECREMENT = -1;

    /**
     * The strength of fading in the top and bottom while drawing the selector.
     */
    private static final float TOP_AND_BOTTOM_FADING_EDGE_STRENGTH = 0.9f;

    /**
     * The numbers accepted by the input text's {@link Filter}
     */
    private static final char[] DIGIT_CHARACTERS = new char[] {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
    };

    /**
     * Use a custom NumberPicker formatting callback to use two-digit minutes
     * strings like "01". Keeping a static formatter etc. is the most efficient
     * way to do this; it avoids creating temporary objects on every call to
     * format().
     */
    public static final NumberPicker.Formatter TWO_DIGIT_FORMATTER = new NumberPicker.Formatter() {
        final StringBuilder mBuilder = new StringBuilder();

        final java.util.Formatter mFmt = new java.util.Formatter(mBuilder, java.util.Locale.US);

        final Object[] mArgs = new Object[1];

        public String toString(int value) {
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
     * The height of the text.
     */
    private final int mTextSize;

    /**
     * The values to be displayed instead the indices.
     */
    private String[] mDisplayedValues;

    /**
     * Lower value of the range of numbers allowed for the NumberPicker
     */
    private int mStart;

    /**
     * Upper value of the range of numbers allowed for the NumberPicker
     */
    private int mEnd;

    /**
     * Current value of this NumberPicker
     */
    private int mCurrent;

    /**
     * Listener to be notified upon current value change.
     */
    private OnChangeListener mOnChangeListener;

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
    private long mLongPressUpdateInterval = 300;

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
    private final Paint mSelectorPaint;

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
     * Handle to the reusable command for updating the current value from long
     * press.
     */
    private UpdateValueFromLongPressCommand mUpdateFromLongPressCommand;

    /**
     * {@link Animator} for showing the up/down arrows.
     */
    private final AnimatorSet mShowInputControlsAnimator;

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
     * Flag if to draw the selector wheel.
     */
    private boolean mDrawSelectorWheel;

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
    private boolean mWrapSelector;

    /**
     * The back ground color used to optimize scroller fading.
     */
    private final int mSolidColor;

    /**
     * Reusable {@link Rect} instance.
     */
    private final Rect mTempRect = new Rect();

    /**
     * The current scroll state of the number picker.
     */
    private int mScrollState = OnScrollListener.SCROLL_STATE_IDLE;

    /**
     * The callback interface used to indicate the number value has changed.
     */
    public interface OnChangeListener {
        /**
         * Called upon a change of the current value.
         *
         * @param picker The NumberPicker associated with this listener.
         * @param oldVal The previous value.
         * @param newVal The new value.
         */
        void onChange(NumberPicker picker, int oldVal, int newVal);
    }

    /**
     * Interface for listening to the picker scroll state.
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
         * Callback method to be invoked while the number picker is being scrolled.
         *
         * @param view The view whose scroll state is being reported
         * @param scrollState The current scroll state. One of {@link #SCROLL_STATE_IDLE},
         * {@link #SCROLL_STATE_TOUCH_SCROLL} or {@link #SCROLL_STATE_IDLE}.
         */
        public void onScrollStateChange(NumberPicker view, int scrollState);
    }

    /**
     * Interface used to format the number into a string for presentation.
     */
    public interface Formatter {

        /**
         * Formats a string representation of the current index.
         *
         * @param value The currently selected value.
         * @return A formatted string representation.
         */
        public String toString(int value);
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
        int orientation = attributesArray.getInt(R.styleable.NumberPicker_orientation, VERTICAL);
        setOrientation(orientation);
        mSolidColor = attributesArray.getColor(R.styleable.NumberPicker_solidColor, 0);
        attributesArray.recycle();

        // By default Linearlayout that we extend is not drawn. This is
        // its draw() method is not called but dispatchDraw() is called
        // directly (see ViewGroup.drawChild()). However, this class uses
        // the fading edge effect implemented by View and we need our
        // draw() method to be called. Therefore, we declare we will draw.
        setWillNotDraw(false);
        setDrawSelectorWheel(false);

        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.number_picker, this, true);

        OnClickListener onClickListener = new OnClickListener() {
            public void onClick(View v) {
                mInputText.clearFocus();
                if (v.getId() == R.id.increment) {
                    changeCurrent(mCurrent + 1);
                } else {
                    changeCurrent(mCurrent - 1);
                }
            }
        };

        OnLongClickListener onLongClickListener = new OnLongClickListener() {
            public boolean onLongClick(View v) {
                mInputText.clearFocus();
                if (v.getId() == R.id.increment) {
                    postUpdateValueFromLongPress(UPDATE_STEP_INCREMENT);
                } else {
                    postUpdateValueFromLongPress(UPDATE_STEP_DECREMENT);
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
        mInputText = (EditText) findViewById(R.id.timepicker_input);
        mInputText.setOnFocusChangeListener(new OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
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
        mSelectorPaint = paint;

        // create the animator for showing the input controls
        final ValueAnimator fadeScroller = ObjectAnimator.ofInt(this, "selectorPaintAlpha", 255, 0);
        final ObjectAnimator showIncrementButton = ObjectAnimator.ofFloat(mIncrementButton,
                "alpha", 0, 1);
        final ObjectAnimator showDecrementButton = ObjectAnimator.ofFloat(mDecrementButton,
                "alpha", 0, 1);
        mShowInputControlsAnimator = new AnimatorSet();
        mShowInputControlsAnimator.playTogether(fadeScroller, showIncrementButton,
                showDecrementButton);
        mShowInputControlsAnimator.setDuration(getResources().getInteger(
                R.integer.config_longAnimTime));
        mShowInputControlsAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled = false;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCanceled) {
                    // if canceled => we still want the wheel drawn
                    setDrawSelectorWheel(false);
                }
                mCanceled = false;
                mSelectorPaint.setAlpha(255);
                invalidate();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (mShowInputControlsAnimator.isRunning()) {
                    mCanceled = true;
                }
            }
        });

        // create the fling and adjust scrollers
        mFlingScroller = new Scroller(getContext());
        mAdjustScroller = new Scroller(getContext(), new OvershootInterpolator());

        updateInputTextView();
        updateIncrementAndDecrementButtonsVisibilityState();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            removeAllCallbacks();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastMotionEventY = mLastDownEventY = event.getY();
                removeAllCallbacks();
                mBeginEditOnUpEvent = false;
                mAdjustScrollerOnUpEvent = true;
                if (mDrawSelectorWheel) {
                    boolean scrollersFinished = mFlingScroller.isFinished()
                            && mAdjustScroller.isFinished();
                    if (!scrollersFinished) {
                        mFlingScroller.forceFinished(true);
                        mAdjustScroller.forceFinished(true);
                        tryNotifyScrollListener(OnScrollListener.SCROLL_STATE_IDLE);
                    }
                    mBeginEditOnUpEvent = scrollersFinished;
                    mAdjustScrollerOnUpEvent = true;
                    hideInputControls();
                    return true;
                }
                if (isEventInInputText(event)) {
                    mAdjustScrollerOnUpEvent = false;
                    setDrawSelectorWheel(true);
                    hideInputControls();
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                float currentMoveY = event.getY();
                int deltaDownY = (int) Math.abs(currentMoveY - mLastDownEventY);
                if (deltaDownY > mTouchSlop) {
                    mBeginEditOnUpEvent = false;
                    tryNotifyScrollListener(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                    setDrawSelectorWheel(true);
                    hideInputControls();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
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
                        tryNotifyScrollListener(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                    }
                }
                int deltaMoveY = (int) (currentMoveY - mLastMotionEventY);
                scrollBy(0, deltaMoveY);
                invalidate();
                mLastMotionEventY = currentMoveY;
                break;
            case MotionEvent.ACTION_UP:
                if (mBeginEditOnUpEvent) {
                    setDrawSelectorWheel(false);
                    showInputControls();
                    mInputText.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getContext().getSystemService(
                            Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(mInputText, 0);
                    mInputText.setSelection(0, mInputText.getText().length());
                    return true;
                }
                VelocityTracker velocityTracker = mVelocityTracker;
                velocityTracker.computeCurrentVelocity(1000, mMaximumFlingVelocity);
                int initialVelocity = (int) velocityTracker.getYVelocity();
                if (Math.abs(initialVelocity) > mMinimumFlingVelocity) {
                    fling(initialVelocity);
                    tryNotifyScrollListener(OnScrollListener.SCROLL_STATE_FLING);
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
        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP)
                && !isEventInInputText(event)) {
            removeAllCallbacks();
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
        if (!mDrawSelectorWheel) {
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

    /**
     * Set the enabled state of this view. The interpretation of the enabled
     * state varies by subclass.
     *
     * @param enabled True if this view is enabled, false otherwise.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mIncrementButton.setEnabled(enabled);
        mDecrementButton.setEnabled(enabled);
        mInputText.setEnabled(enabled);
    }

    /**
     * Scrolls the selector with the given <code>vertical offset</code>.
     */
    @Override
    public void scrollBy(int x, int y) {
        int[] selectorIndices = getSelectorIndices();
        if (mInitialScrollOffset == Integer.MIN_VALUE) {
            int totalTextHeight = selectorIndices.length * mTextSize;
            int totalTextGapHeight = (mBottom - mTop) - totalTextHeight;
            int textGapCount = selectorIndices.length - 1;
            int selectorTextGapHeight = totalTextGapHeight / textGapCount;
            // compensate for integer division loss of the components used to
            // calculate the text gap
            int integerDivisionLoss = (mTextSize + mBottom - mTop) % textGapCount;
            mInitialScrollOffset = mCurrentScrollOffset = mTextSize - integerDivisionLoss / 2;
            mSelectorElementHeight = mTextSize + selectorTextGapHeight;
        }

        if (!mWrapSelector && y > 0 && selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] <= mStart) {
            mCurrentScrollOffset = mInitialScrollOffset;
            return;
        }
        if (!mWrapSelector && y < 0 && selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] >= mEnd) {
            mCurrentScrollOffset = mInitialScrollOffset;
            return;
        }
        mCurrentScrollOffset += y;
        while (mCurrentScrollOffset - mInitialScrollOffset > mSelectorElementHeight) {
            mCurrentScrollOffset -= mSelectorElementHeight;
            decrementSelectorIndices(selectorIndices);
            changeCurrent(selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX]);
            if (selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] <= mStart) {
                mCurrentScrollOffset = mInitialScrollOffset;
            }
        }
        while (mCurrentScrollOffset - mInitialScrollOffset < -mSelectorElementHeight) {
            mCurrentScrollOffset += mSelectorElementHeight;
            incrementScrollSelectorIndices(selectorIndices);
            changeCurrent(selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX]);
            if (selectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] >= mEnd) {
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
     * @param onChangeListener The listener.
     */
    public void setOnChangeListener(OnChangeListener onChangeListener) {
        mOnChangeListener = onChangeListener;
    }

    /**
     * Set listener to be notified for scroll state changes.
     *
     * @param onScrollListener the callback, should not be null.
     */
    public void setOnScrollListener(OnScrollListener onScrollListener) {
        mOnScrollListener = onScrollListener;
    }

    /**
     * Set the formatter to be used for formatting the current value.
     * <p>
     * Note: If you have provided alternative values for the selected positons
     *       this formatter is never invoked.
     * </p>
     *
     * @param formatter the formatter object. If formatter is null,
     *            String.valueOf() will be used.
     *
     * @see #setRange(int, int, String[])
     */
    public void setFormatter(Formatter formatter) {
        mFormatter = formatter;
    }

    /**
     * Set the range of numbers allowed for the number picker. The current value
     * will be automatically set to the start.
     *
     * @param start the start of the range (inclusive)
     * @param end the end of the range (inclusive)
     */
    public void setRange(int start, int end) {
        setRange(start, end, null);
    }

    /**
     * Set the range of numbers allowed for the number picker. The current value
     * will be automatically set to the start. Also provide a mapping for values
     * used to display to the user instead of the numbers in the range.
     *
     * @param start The start of the range (inclusive).
     * @param end The end of the range (inclusive).
     * @param displayedValues The values displayed to the user.
     */
    public void setRange(int start, int end, String[] displayedValues) {
        boolean wrapSelector = (end - start) >= mSelectorIndices.length;
        setRange(start, end, displayedValues, wrapSelector);
    }

    /**
     * Set the range of numbers allowed for the number picker. The current value
     * will be automatically set to the start. Also provide a mapping for values
     * used to display to the user.
     * <p>
     * Note: The <code>wrapSelectorWheel</code> argument is ignored if the range
     * (difference between <code>start</code> and <code>end</code>) us less than
     * five since this is the number of values shown by the selector wheel.
     * </p>
     *
     * @param start the start of the range (inclusive)
     * @param end the end of the range (inclusive)
     * @param displayedValues the values displayed to the user.
     * @param wrapSelectorWheel Whether to wrap the selector wheel.
     *
     * @see #setWrapSelectorWheel(boolean)
     */
    public void setRange(int start, int end, String[] displayedValues, boolean wrapSelectorWheel) {
        if (start == mStart && end == mEnd) {
            return;
        }

        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("start and end must be > 0");
        }
        
        mDisplayedValues = displayedValues;
        mStart = start;
        mEnd = end;
        mCurrent = start;

        setWrapSelectorWheel(wrapSelectorWheel);
        updateInputTextView();

        if (displayedValues != null) {
            // Allow text entry rather than strictly numeric entry.
            mInputText.setRawInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            mInputText.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        }

        resetSelectorIndices();
    }

    /**
     * Set the current value for the number picker.
     *
     * @param current the current value the start of the range (inclusive)
     *
     * @throws IllegalArgumentException when current is not within the range of
     *             of the number picker.
     */
    public void setCurrent(int current) {
        if (mCurrent == current) {
            return;
        }
        if (current < mStart || current > mEnd) {
            throw new IllegalArgumentException("current should be >= start and <= end");
        }
        mCurrent = current;
        updateInputTextView();
        updateIncrementAndDecrementButtonsVisibilityState();
    }

    /**
     * Sets whether the selector wheel shown during flinging/scrolling should wrap
     * around the beginning and end values. By default if the range is more than
     * five (the number of items shown on the selector wheel) the selector wheel
     * wrapping is enabled.
     *
     * @param wrapSelector Whether to wrap.
     */
    public void setWrapSelectorWheel(boolean wrapSelector) {
        if (wrapSelector && (mEnd - mStart) < mSelectorIndices.length) {
            throw new IllegalStateException("Range less than selector items count.");
        }
        if (wrapSelector != mWrapSelector) {
            // force the selector indices array to be reinitialized
            mSelectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] = Integer.MAX_VALUE;
            mWrapSelector = wrapSelector;
        }
    }

    /**
     * Sets the speed at which the numbers be incremented and decremented when
     * the up and down buttons are long pressed respectively.
     *
     * @param intervalMillis The speed (in milliseconds) at which the numbers
     *            will be incremented and decremented (default 300ms).
     */
    public void setOnLongPressUpdateInterval(long intervalMillis) {
        mLongPressUpdateInterval = intervalMillis;
    }

    /**
     * Returns the current value of the NumberPicker.
     *
     * @return the current value.
     */
    public int getCurrent() {
        return mCurrent;
    }

    /**
     * Returns the range lower value of the NumberPicker.
     *
     * @return The lower number of the range.
     */
    public int getRangeStart() {
        return mStart;
    }

    /**
     * Returns the range end value of the NumberPicker.
     *
     * @return The upper number of the range.
     */
    public int getRangeEnd() {
        return mEnd;
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
        // the animation for simultaneously fading out the scroll wheel and
        // showing in the buttons. This class takes advantage of the View
        // implementation of fading edges effect to draw the selector wheel.
        // However, in View.draw(), the fading is applied after all the children
        // have been drawn and we do not want this fading to be applied to the
        // buttons which are currently showing in. Therefore, we draw our
        // children
        // after we have completed drawing ourselves.

        super.draw(canvas);

        // Draw our children if we are not showing the selector wheel of fading
        // it out
        if (mShowInputControlsAnimator.isRunning() || !mDrawSelectorWheel) {
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
        // we only draw the selector wheel
        if (!mDrawSelectorWheel) {
            return;
        }
        float x = (mRight - mLeft) / 2;
        float y = mCurrentScrollOffset;

        int[] selectorIndices = getSelectorIndices();
        for (int i = 0; i < selectorIndices.length; i++) {
            int selectorIndex = selectorIndices[i];
            String scrollSelectorValue = mSelectorIndexToStringCache.get(selectorIndex);
            canvas.drawText(scrollSelectorValue, x, y, mSelectorPaint);
            y += mSelectorElementHeight;
        }
    }

    /**
     * Resets the selector indices and clear the cached
     * string representation of these indices.
     */
    private void resetSelectorIndices() {
        mSelectorIndexToStringCache.clear();
        int[] selectorIdices = getSelectorIndices();
        for (int i = 0; i < selectorIdices.length; i++) {
            selectorIdices[i] = Integer.MIN_VALUE; 
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
        if (mCurrent == current) {
            return;
        }
        // Wrap around the values if we go past the start or end
        if (mWrapSelector) {
            current = getWrappedSelectorIndex(current);
        }
        int previous = mCurrent;
        setCurrent(current);
        notifyChange(previous, current);
    }

    /**
     * Sets the <code>alpha</code> of the {@link Paint} for drawing the selector
     * wheel.
     */
    @SuppressWarnings("unused")
    // Called by ShowInputControlsAnimator via reflection
    private void setSelectorPaintAlpha(int alpha) {
        mSelectorPaint.setAlpha(alpha);
        if (mDrawSelectorWheel) {
            invalidate();
        }
    }

    /**
     * @return If the <code>event</code> is in the input text.
     */
    private boolean isEventInInputText(MotionEvent event) {
        mInputText.getHitRect(mTempRect);
        return mTempRect.contains((int) event.getX(), (int) event.getY());
    }

    /**
     * Sets if to <code>drawSelectionWheel</code>.
     */
    private void setDrawSelectorWheel(boolean drawSelectorWheel) {
        mDrawSelectorWheel = drawSelectorWheel;
        // do not fade if the selector wheel not shown
        setVerticalFadingEdgeEnabled(drawSelectorWheel);
    }

    /**
     * Callback invoked upon completion of a given <code>scroller</code>.
     */
    private void onScrollerFinished(Scroller scroller) {
        if (scroller == mFlingScroller) {
            postAdjustScrollerCommand(0);
            tryNotifyScrollListener(OnScrollListener.SCROLL_STATE_IDLE);
        } else {
            showInputControls();
            updateInputTextView();
        }
    }

    /**
     * Notifies the scroll listener for the given <code>scrollState</code>
     * if the scroll state differs from the current scroll state.
     */
    private void tryNotifyScrollListener(int scrollState) {
        if (mOnScrollListener != null && mScrollState != scrollState) {
            mScrollState = scrollState;
            mOnScrollListener.onScrollStateChange(this, scrollState);
        }
    }

    /**
     * Flings the selector with the given <code>velocityY</code>.
     */
    private void fling(int velocityY) {
        mPreviousScrollerY = 0;
        Scroller flingScroller = mFlingScroller;

        if (mWrapSelector) {
            if (velocityY > 0) {
                flingScroller.fling(0, 0, 0, velocityY, 0, 0, 0, Integer.MAX_VALUE);
            } else {
                flingScroller.fling(0, Integer.MAX_VALUE, 0, velocityY, 0, 0, 0, Integer.MAX_VALUE);
            }
        } else {
            if (velocityY > 0) {
                int maxY = mTextSize * (mCurrent - mStart);
                flingScroller.fling(0, 0, 0, velocityY, 0, 0, 0, maxY);
            } else {
                int startY = mTextSize * (mEnd - mCurrent);
                int maxY = startY;
                flingScroller.fling(0, startY, 0, velocityY, 0, 0, 0, maxY);
            }
        }

        postAdjustScrollerCommand(flingScroller.getDuration());
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
     */
    private void showInputControls() {
        updateIncrementAndDecrementButtonsVisibilityState();
        mInputText.setVisibility(VISIBLE);
        mShowInputControlsAnimator.start();
    }

    /**
     * Updates the visibility state of the increment and decrement buttons.
     */
    private void updateIncrementAndDecrementButtonsVisibilityState() {
        if (mWrapSelector || mCurrent < mEnd) {
            mIncrementButton.setVisibility(VISIBLE);
        } else {
            mIncrementButton.setVisibility(INVISIBLE);
        }
        if (mWrapSelector || mCurrent > mStart) {
            mDecrementButton.setVisibility(VISIBLE);
        } else {
            mDecrementButton.setVisibility(INVISIBLE);
        }
    }

    /**
     * @return The selector indices array with proper values with the current as
     *         the middle one.
     */
    private int[] getSelectorIndices() {
        int current = getCurrent();
        if (mSelectorIndices[SELECTOR_MIDDLE_ITEM_INDEX] != current) {
            for (int i = 0; i < mSelectorIndices.length; i++) {
                int selectorIndex = current + (i - SELECTOR_MIDDLE_ITEM_INDEX);
                if (mWrapSelector) {
                    selectorIndex = getWrappedSelectorIndex(selectorIndex);
                }
                mSelectorIndices[i] = selectorIndex;
                ensureCachedScrollSelectorValue(mSelectorIndices[i]);
            }
        }
        return mSelectorIndices;
    }

    /**
     * @return The wrapped index <code>selectorIndex</code> value.
     */
    private int getWrappedSelectorIndex(int selectorIndex) {
        if (selectorIndex > mEnd) {
            return mStart + (selectorIndex - mEnd) % (mEnd - mStart);
        } else if (selectorIndex < mStart) {
            return mEnd - (mStart - selectorIndex) % (mEnd - mStart);
        }
        return selectorIndex;
    }

    /**
     * Increments the <code>selectorIndices</code> whose string representations
     * will be displayed in the selector.
     */
    private void incrementScrollSelectorIndices(int[] selectorIndices) {
        for (int i = 0; i < selectorIndices.length - 1; i++) {
            selectorIndices[i] = selectorIndices[i + 1];
        }
        int nextScrollSelectorIndex = selectorIndices[selectorIndices.length - 2] + 1;
        if (mWrapSelector && nextScrollSelectorIndex > mEnd) {
            nextScrollSelectorIndex = mStart;
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
        if (mWrapSelector && nextScrollSelectorIndex < mStart) {
            nextScrollSelectorIndex = mEnd;
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
        if (selectorIndex < mStart || selectorIndex > mEnd) {
            scrollSelectorValue = "";
        } else {
            if (mDisplayedValues != null) {
                int displayedValueIndex = selectorIndex - mStart;
                scrollSelectorValue = mDisplayedValues[displayedValueIndex];
            } else {
                scrollSelectorValue = formatNumber(selectorIndex);
            }
        }
        cache.put(selectorIndex, scrollSelectorValue);
    }

    private String formatNumber(int value) {
        return (mFormatter != null) ? mFormatter.toString(value) : String.valueOf(value);
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
     * {@link #setRange}, the string corresponding to the index specified by the
     * current value will be returned. Otherwise, the formatter specified in
     * {@link #setFormatter} will be used to format the number.
     */
    private void updateInputTextView() {
        /*
         * If we don't have displayed values then use the current number else
         * find the correct value in the displayed values for the current
         * number.
         */
        if (mDisplayedValues == null) {
            mInputText.setText(formatNumber(mCurrent));
        } else {
            mInputText.setText(mDisplayedValues[mCurrent - mStart]);
        }
        mInputText.setSelection(mInputText.getText().length());
    }

    /**
     * Notifies the listener, if registered, of a change of the value of this
     * NumberPicker.
     */
    private void notifyChange(int previous, int current) {
        if (mOnChangeListener != null) {
            mOnChangeListener.onChange(this, previous, mCurrent);
        }
    }

    /**
     * Posts a command for updating the current value every <code>updateMillis
     * </code>.
     */
    private void postUpdateValueFromLongPress(int updateMillis) {
        mInputText.clearFocus();
        removeAllCallbacks();
        if (mUpdateFromLongPressCommand == null) {
            mUpdateFromLongPressCommand = new UpdateValueFromLongPressCommand();
        }
        mUpdateFromLongPressCommand.setUpdateStep(updateMillis);
        post(mUpdateFromLongPressCommand);
    }

    /**
     * Removes all pending callback from the message queue.
     */
    private void removeAllCallbacks() {
        if (mUpdateFromLongPressCommand != null) {
            removeCallbacks(mUpdateFromLongPressCommand);
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
                    return mStart + i;
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
        return mStart;
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
                if (val > mEnd) {
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
            int deltaY = mInitialScrollOffset - mCurrentScrollOffset;
            float delayCoef = (float) Math.abs(deltaY) / (float) mTextSize;
            int duration = (int) (delayCoef * SELECTOR_ADJUSTMENT_DURATION_MILLIS);
            mAdjustScroller.startScroll(0, 0, 0, deltaY, duration);
            invalidate();
        }
    }

    /**
     * Command for updating the current value from a long press.
     */
    class UpdateValueFromLongPressCommand implements Runnable {
        private int mUpdateStep = 0;

        private void setUpdateStep(int updateStep) {
            mUpdateStep = updateStep;
        }

        public void run() {
            changeCurrent(mCurrent + mUpdateStep);
            postDelayed(this, mLongPressUpdateInterval);
        }
    }
}
