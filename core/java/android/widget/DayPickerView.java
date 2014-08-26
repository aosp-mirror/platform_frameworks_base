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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * This displays a list of months in a calendar format with selectable days.
 */
class DayPickerView extends ListView implements AbsListView.OnScrollListener,
        OnDateChangedListener {

    private static final String TAG = "DayPickerView";

    // How long the GoTo fling animation should last
    private static final int GOTO_SCROLL_DURATION = 250;

    // How long to wait after receiving an onScrollStateChanged notification before acting on it
    private static final int SCROLL_CHANGE_DELAY = 40;

    private static int LIST_TOP_OFFSET = -1; // so that the top line will be under the separator

    private SimpleDateFormat mYearFormat = new SimpleDateFormat("yyyy", Locale.getDefault());

    // These affect the scroll speed and feel
    private float mFriction = 1.0f;

    // highlighted time
    private Calendar mSelectedDay = Calendar.getInstance();
    private SimpleMonthAdapter mAdapter;

    private Calendar mTempDay = Calendar.getInstance();

    // which month should be displayed/highlighted [0-11]
    private int mCurrentMonthDisplayed;
    // used for tracking what state listview is in
    private int mPreviousScrollState = OnScrollListener.SCROLL_STATE_IDLE;
    // used for tracking what state listview is in
    private int mCurrentScrollState = OnScrollListener.SCROLL_STATE_IDLE;

    private DatePickerController mController;
    private boolean mPerformingScroll;

    private ScrollStateRunnable mScrollStateChangedRunnable = new ScrollStateRunnable(this);

    public DayPickerView(Context context, DatePickerController controller) {
        super(context);
        init();
        setController(controller);
    }

    public void setController(DatePickerController controller) {
        if (mController != null) {
            mController.unregisterOnDateChangedListener(this);
        }
        mController = controller;
        mController.registerOnDateChangedListener(this);
        setUpAdapter();
        setAdapter(mAdapter);
        onDateChanged();
    }

    public void init() {
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setDrawSelectorOnTop(false);

        setUpListView();
    }

    public void onChange() {
        setUpAdapter();
        setAdapter(mAdapter);
    }

    /**
     * Creates a new adapter if necessary and sets up its parameters. Override
     * this method to provide a custom adapter.
     */
    protected void setUpAdapter() {
        if (mAdapter == null) {
            mAdapter = new SimpleMonthAdapter(getContext(), mController);
        } else {
            mAdapter.setSelectedDay(mSelectedDay);
            mAdapter.notifyDataSetChanged();
        }
        // refresh the view with the new parameters
        mAdapter.notifyDataSetChanged();
    }

    /*
     * Sets all the required fields for the list view. Override this method to
     * set a different list view behavior.
     */
    protected void setUpListView() {
        // Transparent background on scroll
        setCacheColorHint(0);
        // No dividers
        setDivider(null);
        // Items are clickable
        setItemsCanFocus(true);
        // The thumb gets in the way, so disable it
        setFastScrollEnabled(false);
        setVerticalScrollBarEnabled(false);
        setOnScrollListener(this);
        setFadingEdgeLength(0);
        // Make the scrolling behavior nicer
        setFriction(ViewConfiguration.getScrollFriction() * mFriction);
    }

    private int getDiffMonths(Calendar start, Calendar end){
        final int diffYears = end.get(Calendar.YEAR) - start.get(Calendar.YEAR);
        final int diffMonths = end.get(Calendar.MONTH) - start.get(Calendar.MONTH) + 12 * diffYears;
        return diffMonths;
    }

    private int getPositionFromDay(Calendar day) {
        final int diffMonthMax = getDiffMonths(mController.getMinDate(), mController.getMaxDate());
        int diffMonth = getDiffMonths(mController.getMinDate(), day);

        if (diffMonth < 0 ) {
            diffMonth = 0;
        } else if (diffMonth > diffMonthMax) {
            diffMonth = diffMonthMax;
        }

        return diffMonth;
    }

    /**
     * This moves to the specified time in the view. If the time is not already
     * in range it will move the list so that the first of the month containing
     * the time is at the top of the view. If the new time is already in view
     * the list will not be scrolled unless forceScroll is true. This time may
     * optionally be highlighted as selected as well.
     *
     * @param day The day to move to
     * @param animate Whether to scroll to the given time or just redraw at the
     *            new location
     * @param setSelected Whether to set the given time as selected
     * @param forceScroll Whether to recenter even if the time is already
     *            visible
     * @return Whether or not the view animated to the new location
     */
    public boolean goTo(Calendar day, boolean animate, boolean setSelected,
                        boolean forceScroll) {

        // Set the selected day
        if (setSelected) {
            mSelectedDay.setTimeInMillis(day.getTimeInMillis());
        }

        mTempDay.setTimeInMillis(day.getTimeInMillis());
        final int position = getPositionFromDay(day);

        View child;
        int i = 0;
        int top = 0;
        // Find a child that's completely in the view
        do {
            child = getChildAt(i++);
            if (child == null) {
                break;
            }
            top = child.getTop();
        } while (top < 0);

        // Compute the first and last position visible
        int selectedPosition;
        if (child != null) {
            selectedPosition = getPositionForView(child);
        } else {
            selectedPosition = 0;
        }

        if (setSelected) {
            mAdapter.setSelectedDay(mSelectedDay);
        }

        // Check if the selected day is now outside of our visible range
        // and if so scroll to the month that contains it
        if (position != selectedPosition || forceScroll) {
            setMonthDisplayed(mTempDay);
            mPreviousScrollState = OnScrollListener.SCROLL_STATE_FLING;
            if (animate) {
                smoothScrollToPositionFromTop(
                        position, LIST_TOP_OFFSET, GOTO_SCROLL_DURATION);
                return true;
            } else {
                postSetSelection(position);
            }
        } else if (setSelected) {
            setMonthDisplayed(mSelectedDay);
        }
        return false;
    }

    public void postSetSelection(final int position) {
        clearFocus();
        post(new Runnable() {

            @Override
            public void run() {
                setSelection(position);
            }
        });
        onScrollStateChanged(this, OnScrollListener.SCROLL_STATE_IDLE);
    }

    /**
     * Updates the title and selected month if the view has moved to a new
     * month.
     */
    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                         int totalItemCount) {
        SimpleMonthView child = (SimpleMonthView) view.getChildAt(0);
        if (child == null) {
            return;
        }

        mPreviousScrollState = mCurrentScrollState;
    }

    /**
     * Sets the month displayed at the top of this view based on time. Override
     * to add custom events when the title is changed.
     */
    protected void setMonthDisplayed(Calendar date) {
        if (mCurrentMonthDisplayed != date.get(Calendar.MONTH)) {
            mCurrentMonthDisplayed = date.get(Calendar.MONTH);
            invalidateViews();
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        // use a post to prevent re-entering onScrollStateChanged before it
        // exits
        mScrollStateChangedRunnable.doScrollStateChange(view, scrollState);
    }

    void setCalendarTextColor(ColorStateList colors) {
        mAdapter.setCalendarTextColor(colors);
    }

    protected class ScrollStateRunnable implements Runnable {
        private int mNewState;
        private View mParent;

        ScrollStateRunnable(View view) {
            mParent = view;
        }

        /**
         * Sets up the runnable with a short delay in case the scroll state
         * immediately changes again.
         *
         * @param view The list view that changed state
         * @param scrollState The new state it changed to
         */
        public void doScrollStateChange(AbsListView view, int scrollState) {
            mParent.removeCallbacks(this);
            mNewState = scrollState;
            mParent.postDelayed(this, SCROLL_CHANGE_DELAY);
        }

        @Override
        public void run() {
            mCurrentScrollState = mNewState;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG,
                        "new scroll state: " + mNewState + " old state: " + mPreviousScrollState);
            }
            // Fix the position after a scroll or a fling ends
            if (mNewState == OnScrollListener.SCROLL_STATE_IDLE
                    && mPreviousScrollState != OnScrollListener.SCROLL_STATE_IDLE
                    && mPreviousScrollState != OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                mPreviousScrollState = mNewState;
                int i = 0;
                View child = getChildAt(i);
                while (child != null && child.getBottom() <= 0) {
                    child = getChildAt(++i);
                }
                if (child == null) {
                    // The view is no longer visible, just return
                    return;
                }
                int firstPosition = getFirstVisiblePosition();
                int lastPosition = getLastVisiblePosition();
                boolean scroll = firstPosition != 0 && lastPosition != getCount() - 1;
                final int top = child.getTop();
                final int bottom = child.getBottom();
                final int midpoint = getHeight() / 2;
                if (scroll && top < LIST_TOP_OFFSET) {
                    if (bottom > midpoint) {
                        smoothScrollBy(top, GOTO_SCROLL_DURATION);
                    } else {
                        smoothScrollBy(bottom, GOTO_SCROLL_DURATION);
                    }
                }
            } else {
                mPreviousScrollState = mNewState;
            }
        }
    }

    /**
     * Gets the position of the view that is most prominently displayed within the list view.
     */
    public int getMostVisiblePosition() {
        final int firstPosition = getFirstVisiblePosition();
        final int height = getHeight();

        int maxDisplayedHeight = 0;
        int mostVisibleIndex = 0;
        int i=0;
        int bottom = 0;
        while (bottom < height) {
            View child = getChildAt(i);
            if (child == null) {
                break;
            }
            bottom = child.getBottom();
            int displayedHeight = Math.min(bottom, height) - Math.max(0, child.getTop());
            if (displayedHeight > maxDisplayedHeight) {
                mostVisibleIndex = i;
                maxDisplayedHeight = displayedHeight;
            }
            i++;
        }
        return firstPosition + mostVisibleIndex;
    }

    @Override
    public void onDateChanged() {
        goTo(mController.getSelectedDay(), false, true, true);
    }

    /**
     * Attempts to return the date that has accessibility focus.
     *
     * @return The date that has accessibility focus, or {@code null} if no date
     *         has focus.
     */
    private Calendar findAccessibilityFocus() {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child instanceof SimpleMonthView) {
                final Calendar focus = ((SimpleMonthView) child).getAccessibilityFocus();
                if (focus != null) {
                    return focus;
                }
            }
        }

        return null;
    }

    /**
     * Attempts to restore accessibility focus to a given date. No-op if
     * {@code day} is {@code null}.
     *
     * @param day The date that should receive accessibility focus
     * @return {@code true} if focus was restored
     */
    private boolean restoreAccessibilityFocus(Calendar day) {
        if (day == null) {
            return false;
        }

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child instanceof SimpleMonthView) {
                if (((SimpleMonthView) child).restoreAccessibilityFocus(day)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    protected void layoutChildren() {
        final Calendar focusedDay = findAccessibilityFocus();
        super.layoutChildren();
        if (mPerformingScroll) {
            mPerformingScroll = false;
        } else {
            restoreAccessibilityFocus(focusedDay);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        mYearFormat = new SimpleDateFormat("yyyy", Locale.getDefault());
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setItemCount(-1);
    }

    private String getMonthAndYearString(Calendar day) {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append(day.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()));
        sbuf.append(" ");
        sbuf.append(mYearFormat.format(day.getTime()));
        return sbuf.toString();
    }

    /**
     * Necessary for accessibility, to ensure we support "scrolling" forward and backward
     * in the month list.
     */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    /**
     * When scroll forward/backward events are received, announce the newly scrolled-to month.
     */
    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD &&
                action != AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            return super.performAccessibilityAction(action, arguments);
        }

        // Figure out what month is showing.
        int firstVisiblePosition = getFirstVisiblePosition();
        int month = firstVisiblePosition % 12;
        int year = firstVisiblePosition / 12 + mController.getMinYear();
        Calendar day = Calendar.getInstance();
        day.set(year, month, 1);

        // Scroll either forward or backward one month.
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            day.add(Calendar.MONTH, 1);
            if (day.get(Calendar.MONTH) == 12) {
                day.set(Calendar.MONTH, 0);
                day.add(Calendar.YEAR, 1);
            }
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            View firstVisibleView = getChildAt(0);
            // If the view is fully visible, jump one month back. Otherwise, we'll just jump
            // to the first day of first visible month.
            if (firstVisibleView != null && firstVisibleView.getTop() >= -1) {
                // There's an off-by-one somewhere, so the top of the first visible item will
                // actually be -1 when it's at the exact top.
                day.add(Calendar.MONTH, -1);
                if (day.get(Calendar.MONTH) == -1) {
                    day.set(Calendar.MONTH, 11);
                    day.add(Calendar.YEAR, -1);
                }
            }
        }

        // Go to that month.
        announceForAccessibility(getMonthAndYearString(day));
        goTo(day, true, false, true);
        mPerformingScroll = true;
        return true;
    }
}
