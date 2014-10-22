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
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;

import java.util.Calendar;

import com.android.internal.R;

/**
 * Displays a selectable list of years.
 */
class YearPickerView extends ListView implements AdapterView.OnItemClickListener,
        OnDateChangedListener {
    private DatePickerController mController;
    private YearAdapter mAdapter;
    private int mViewSize;
    private int mChildSize;
    private int mSelectedPosition = -1;
    private int mYearSelectedCircleColor;

    public YearPickerView(Context context) {
        this(context, null);
    }

    public YearPickerView(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.listViewStyle);
    }

    public YearPickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public YearPickerView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final LayoutParams frame = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        setLayoutParams(frame);

        final Resources res = context.getResources();
        mViewSize = res.getDimensionPixelOffset(R.dimen.datepicker_view_animator_height);
        mChildSize = res.getDimensionPixelOffset(R.dimen.datepicker_year_label_height);

        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(mChildSize / 3);

        final int paddingTop = res.getDimensionPixelSize(
                R.dimen.datepicker_year_picker_padding_top);
        setPadding(0, paddingTop, 0, 0);

        setOnItemClickListener(this);
        setDividerHeight(0);
    }

    public void init(DatePickerController controller) {
        mController = controller;
        mController.registerOnDateChangedListener(this);

        mAdapter = new YearAdapter(getContext(), R.layout.year_label_text_view);
        updateAdapterData();
        setAdapter(mAdapter);

        onDateChanged();
    }

    public void setYearSelectedCircleColor(int color) {
        if (color != mYearSelectedCircleColor) {
            mYearSelectedCircleColor = color;
        }
        requestLayout();
    }

    public int getYearSelectedCircleColor()  {
        return mYearSelectedCircleColor;
    }

    private void updateAdapterData() {
        mAdapter.clear();
        final int maxYear = mController.getMaxYear();
        for (int year = mController.getMinYear(); year <= maxYear; year++) {
            mAdapter.add(year);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        mController.tryVibrate();
        if (position != mSelectedPosition) {
            mSelectedPosition = position;
            mAdapter.notifyDataSetChanged();
        }
        mController.onYearSelected(mAdapter.getItem(position));
    }

    void setItemTextAppearance(int resId) {
        mAdapter.setItemTextAppearance(resId);
    }

    private class YearAdapter extends ArrayAdapter<Integer> {
        int mItemTextAppearanceResId;

        public YearAdapter(Context context, int resource) {
            super(context, resource);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextViewWithCircularIndicator v = (TextViewWithCircularIndicator)
                    super.getView(position, convertView, parent);
            v.setTextAppearance(getContext(), mItemTextAppearanceResId);
            v.requestLayout();
            int year = getItem(position);
            boolean selected = mController.getSelectedDay().get(Calendar.YEAR) == year;
            v.setDrawIndicator(selected);
            if (selected) {
                v.setCircleColor(mYearSelectedCircleColor);
            }
            return v;
        }

        public void setItemTextAppearance(int resId) {
            mItemTextAppearanceResId = resId;
        }
    }

    public void postSetSelectionCentered(final int position) {
        postSetSelectionFromTop(position, mViewSize / 2 - mChildSize / 2);
    }

    public void postSetSelectionFromTop(final int position, final int offset) {
        post(new Runnable() {

            @Override
            public void run() {
                setSelectionFromTop(position, offset);
                requestLayout();
            }
        });
    }

    public int getFirstPositionOffset() {
        final View firstChild = getChildAt(0);
        if (firstChild == null) {
            return 0;
        }
        return firstChild.getTop();
    }

    @Override
    public void onDateChanged() {
        updateAdapterData();
        mAdapter.notifyDataSetChanged();
        postSetSelectionCentered(
                mController.getSelectedDay().get(Calendar.YEAR) - mController.getMinYear());
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            event.setFromIndex(0);
            event.setToIndex(0);
        }
    }
}