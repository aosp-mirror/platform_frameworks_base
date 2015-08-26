package com.android.layoutlib.test.myapplication;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A widget to test obtaining arrays from resources.
 */
public class ArraysCheckWidget extends LinearLayout {
    public ArraysCheckWidget(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ArraysCheckWidget(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ArraysCheckWidget(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        Resources resources = context.getResources();
        for (CharSequence chars : resources.getTextArray(R.array.array)) {
            addTextView(context, chars);
        }
        for (int i : resources.getIntArray(R.array.int_array)) {
            addTextView(context, String.valueOf(i));
        }
        for (String string : resources.getStringArray(R.array.string_array)) {
            addTextView(context, string);
        }
    }

    private void addTextView(Context context, CharSequence string) {
        TextView textView = new TextView(context);
        textView.setText(string);
        textView.setTextSize(30);
        addView(textView);
    }
}
