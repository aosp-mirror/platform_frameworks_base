package com.android.layoutlib.test.myapplication;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.CalendarView;

public class CustomCalendar extends CalendarView {
    public CustomCalendar(Context context) {
        super(context);
        init();
    }

    public CustomCalendar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CustomCalendar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public CustomCalendar(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        setDate(871703200000L, false, true);
    }
}
