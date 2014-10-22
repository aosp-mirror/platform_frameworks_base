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
 * limitations under the License.
 */

package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;

import java.util.Calendar;
import java.util.HashMap;

/**
 * An adapter for a list of {@link android.widget.SimpleMonthView} items.
 */
class SimpleMonthAdapter extends BaseAdapter implements SimpleMonthView.OnDayClickListener {
    private static final String TAG = "SimpleMonthAdapter";

    private final Context mContext;
    private final DatePickerController mController;
    private Calendar mSelectedDay;

    private ColorStateList mCalendarTextColors;

    public SimpleMonthAdapter(Context context, DatePickerController controller) {
        mContext = context;
        mController = controller;
        init();
        setSelectedDay(mController.getSelectedDay());
    }

    /**
     * Updates the selected day and related parameters.
     *
     * @param day The day to highlight
     */
    public void setSelectedDay(Calendar day) {
        if (mSelectedDay != day) {
            mSelectedDay = day;
            notifyDataSetChanged();
        }
    }

    void setCalendarTextColor(ColorStateList colors) {
        mCalendarTextColors = colors;
    }

    /**
     * Set up the gesture detector and selected time
     */
    protected void init() {
        mSelectedDay = Calendar.getInstance();
    }

    @Override
    public int getCount() {
        final int diffYear = mController.getMaxYear() - mController.getMinYear();
        final int diffMonth = 1 + mController.getMaxMonth() - mController.getMinMonth()
                + 12 * diffYear;
        return diffMonth;
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        SimpleMonthView v;
        HashMap<String, Integer> drawingParams = null;
        if (convertView != null) {
            v = (SimpleMonthView) convertView;
            // We store the drawing parameters in the view so it can be recycled
            drawingParams = (HashMap<String, Integer>) v.getTag();
        } else {
            v = new SimpleMonthView(mContext);
            // Set up the new view
            AbsListView.LayoutParams params = new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.MATCH_PARENT);
            v.setLayoutParams(params);
            v.setClickable(true);
            v.setOnDayClickListener(this);
            if (mCalendarTextColors != null) {
                v.setTextColor(mCalendarTextColors);
            }
        }
        if (drawingParams == null) {
            drawingParams = new HashMap<String, Integer>();
        } else {
            drawingParams.clear();
        }
        final int currentMonth = position + mController.getMinMonth();
        final int month = currentMonth % 12;
        final int year = currentMonth / 12 + mController.getMinYear();

        int selectedDay = -1;
        if (isSelectedDayInMonth(year, month)) {
            selectedDay = mSelectedDay.get(Calendar.DAY_OF_MONTH);
        }

        // Invokes requestLayout() to ensure that the recycled view is set with the appropriate
        // height/number of weeks before being displayed.
        v.reuse();

        final int enabledDayRangeStart;
        if (mController.getMinMonth() == month && mController.getMinYear() == year) {
            enabledDayRangeStart = mController.getMinDay();
        } else {
            enabledDayRangeStart = 1;
        }

        final int enabledDayRangeEnd;
        if (mController.getMaxMonth() == month && mController.getMaxYear() == year) {
            enabledDayRangeEnd = mController.getMaxDay();
        } else {
            enabledDayRangeEnd = 31;
        }

        v.setMonthParams(selectedDay, month, year, mController.getFirstDayOfWeek(),
                enabledDayRangeStart, enabledDayRangeEnd);
        v.invalidate();

        return v;
    }

    private boolean isSelectedDayInMonth(int year, int month) {
        return mSelectedDay.get(Calendar.YEAR) == year && mSelectedDay.get(Calendar.MONTH) == month;
    }

    @Override
    public void onDayClick(SimpleMonthView view, Calendar day) {
        if (day != null) {
            onDayTapped(day);
        }
    }

    /**
     * Maintains the same hour/min/sec but moves the day to the tapped day.
     *
     * @param day The day that was tapped
     */
    protected void onDayTapped(Calendar day) {
        mController.tryVibrate();
        mController.onDayOfMonthSelected(day.get(Calendar.YEAR), day.get(Calendar.MONTH),
                day.get(Calendar.DAY_OF_MONTH));
        setSelectedDay(day);
    }
}
