package com.android.layoutlib.test.myapplication;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.DatePicker;

public class CustomDate extends DatePicker {
    public CustomDate(Context context) {
        super(context);
        init();
    }

    public CustomDate(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CustomDate(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public CustomDate(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        init(2015, 0, 20, null);
    }
}
