/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Widget;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import com.android.internal.R;

import java.util.Locale;

import libcore.icu.LocaleData;

/**
 * A widget for selecting the time of day, in either 24-hour or AM/PM mode.
 * <p>
 * For a dialog using this view, see {@link android.app.TimePickerDialog}. See
 * the <a href="{@docRoot}guide/topics/ui/controls/pickers.html">Pickers</a>
 * guide for more information.
 *
 * @attr ref android.R.styleable#TimePicker_timePickerMode
 */
@Widget
public class TimePicker extends FrameLayout {
    private static final int MODE_SPINNER = 1;
    private static final int MODE_CLOCK = 2;

    private final TimePickerDelegate mDelegate;

    /**
     * The callback interface used to indicate the time has been adjusted.
     */
    public interface OnTimeChangedListener {

        /**
         * @param view The view associated with this listener.
         * @param hourOfDay The current hour.
         * @param minute The current minute.
         */
        void onTimeChanged(TimePicker view, int hourOfDay, int minute);
    }

    public TimePicker(Context context) {
        this(context, null);
    }

    public TimePicker(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.timePickerStyle);
    }

    public TimePicker(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TimePicker(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.TimePicker, defStyleAttr, defStyleRes);
        final int mode = a.getInt(R.styleable.TimePicker_timePickerMode, MODE_SPINNER);
        a.recycle();

        switch (mode) {
            case MODE_CLOCK:
                mDelegate = new TimePickerClockDelegate(
                        this, context, attrs, defStyleAttr, defStyleRes);
                break;
            case MODE_SPINNER:
            default:
                mDelegate = new TimePickerSpinnerDelegate(
                        this, context, attrs, defStyleAttr, defStyleRes);
                break;
        }
    }

    /**
     * Sets the currently selected hour using 24-hour time.
     *
     * @param hour the hour to set, in the range (0-23)
     * @see #getHour()
     */
    public void setHour(int hour) {
        mDelegate.setHour(hour);
    }

    /**
     * Returns the currently selected hour using 24-hour time.
     *
     * @return the currently selected hour, in the range (0-23)
     * @see #setHour(int)
     */
    public int getHour() {
        return mDelegate.getHour();
    }

    /**
     * Sets the currently selected minute..
     *
     * @param minute the minute to set, in the range (0-59)
     * @see #getMinute()
     */
    public void setMinute(int minute) {
        mDelegate.setMinute(minute);
    }

    /**
     * Returns the currently selected minute.
     *
     * @return the currently selected minute, in the range (0-59)
     * @see #setMinute(int)
     */
    public int getMinute() {
        return mDelegate.getMinute();
    }

    /**
     * Sets the current hour.
     *
     * @deprecated Use {@link #setHour(int)}
     */
    @Deprecated
    public void setCurrentHour(@NonNull Integer currentHour) {
        setHour(currentHour);
    }

    /**
     * @return the current hour in the range (0-23)
     * @deprecated Use {@link #getHour()}
     */
    @NonNull
    @Deprecated
    public Integer getCurrentHour() {
        return mDelegate.getHour();
    }

    /**
     * Set the current minute (0-59).
     *
     * @deprecated Use {@link #setMinute(int)}
     */
    @Deprecated
    public void setCurrentMinute(@NonNull Integer currentMinute) {
        mDelegate.setMinute(currentMinute);
    }

    /**
     * @return the current minute
     * @deprecated Use {@link #getMinute()}
     */
    @NonNull
    @Deprecated
    public Integer getCurrentMinute() {
        return mDelegate.getMinute();
    }

    /**
     * Sets whether this widget displays time in 24-hour mode or 12-hour mode
     * with an AM/PM picker.
     *
     * @param is24HourView {@code true} to display in 24-hour mode,
     *                     {@code false} for 12-hour mode with AM/PM
     * @see #is24HourView()
     */
    public void setIs24HourView(@NonNull Boolean is24HourView) {
        if (is24HourView == null) {
            return;
        }

        mDelegate.setIs24Hour(is24HourView);
    }

    /**
     * @return {@code true} if this widget displays time in 24-hour mode,
     *         {@code false} otherwise}
     * @see #setIs24HourView(Boolean)
     */
    public boolean is24HourView() {
        return mDelegate.is24Hour();
    }

    /**
     * Set the callback that indicates the time has been adjusted by the user.
     *
     * @param onTimeChangedListener the callback, should not be null.
     */
    public void setOnTimeChangedListener(OnTimeChangedListener onTimeChangedListener) {
        mDelegate.setOnTimeChangedListener(onTimeChangedListener);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mDelegate.setEnabled(enabled);
    }

    @Override
    public boolean isEnabled() {
        return mDelegate.isEnabled();
    }

    @Override
    public int getBaseline() {
        return mDelegate.getBaseline();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        return mDelegate.onSaveInstanceState(superState);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        BaseSavedState ss = (BaseSavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mDelegate.onRestoreInstanceState(ss);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return TimePicker.class.getName();
    }

    /** @hide */
    @Override
    public boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        return mDelegate.dispatchPopulateAccessibilityEvent(event);
    }

    /**
     * A delegate interface that defined the public API of the TimePicker. Allows different
     * TimePicker implementations. This would need to be implemented by the TimePicker delegates
     * for the real behavior.
     */
    interface TimePickerDelegate {
        void setHour(int hour);
        int getHour();

        void setMinute(int minute);
        int getMinute();

        void setIs24Hour(boolean is24Hour);
        boolean is24Hour();

        void setOnTimeChangedListener(OnTimeChangedListener onTimeChangedListener);

        void setEnabled(boolean enabled);
        boolean isEnabled();

        int getBaseline();

        Parcelable onSaveInstanceState(Parcelable superState);
        void onRestoreInstanceState(Parcelable state);

        boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event);
        void onPopulateAccessibilityEvent(AccessibilityEvent event);
    }

    static String[] getAmPmStrings(Context context) {
        final Locale locale = context.getResources().getConfiguration().locale;
        final LocaleData d = LocaleData.get(locale);

        final String[] result = new String[2];
        result[0] = d.amPm[0].length() > 4 ? d.narrowAm : d.amPm[0];
        result[1] = d.amPm[1].length() > 4 ? d.narrowPm : d.amPm[1];
        return result;
    }

    /**
     * An abstract class which can be used as a start for TimePicker implementations
     */
    abstract static class AbstractTimePickerDelegate implements TimePickerDelegate {
        protected final TimePicker mDelegator;
        protected final Context mContext;
        protected final Locale mLocale;

        protected OnTimeChangedListener mOnTimeChangedListener;

        public AbstractTimePickerDelegate(@NonNull TimePicker delegator, @NonNull Context context) {
            mDelegator = delegator;
            mContext = context;
            mLocale = context.getResources().getConfiguration().locale;
        }

        protected static class SavedState extends View.BaseSavedState {
            private final int mHour;
            private final int mMinute;
            private final boolean mIs24HourMode;
            private final int mCurrentItemShowing;

            public SavedState(Parcelable superState, int hour, int minute, boolean is24HourMode) {
                this(superState, hour, minute, is24HourMode, 0);
            }

            public SavedState(Parcelable superState, int hour, int minute, boolean is24HourMode,
                    int currentItemShowing) {
                super(superState);
                mHour = hour;
                mMinute = minute;
                mIs24HourMode = is24HourMode;
                mCurrentItemShowing = currentItemShowing;
            }

            private SavedState(Parcel in) {
                super(in);
                mHour = in.readInt();
                mMinute = in.readInt();
                mIs24HourMode = (in.readInt() == 1);
                mCurrentItemShowing = in.readInt();
            }

            public int getHour() {
                return mHour;
            }

            public int getMinute() {
                return mMinute;
            }

            public boolean is24HourMode() {
                return mIs24HourMode;
            }

            public int getCurrentItemShowing() {
                return mCurrentItemShowing;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {
                super.writeToParcel(dest, flags);
                dest.writeInt(mHour);
                dest.writeInt(mMinute);
                dest.writeInt(mIs24HourMode ? 1 : 0);
                dest.writeInt(mCurrentItemShowing);
            }

            @SuppressWarnings({"unused", "hiding"})
            public static final Creator<SavedState> CREATOR = new Creator<SavedState>() {
                public SavedState createFromParcel(Parcel in) {
                    return new SavedState(in);
                }

                public SavedState[] newArray(int size) {
                    return new SavedState[size];
                }
            };
        }
    }
}
