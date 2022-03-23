package com.android.systemui.qs;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

public class QuickTileLayout extends LinearLayout {

    public QuickTileLayout(Context context) {
        this(context, null);
    }

    public QuickTileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setGravity(Gravity.CENTER);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        // Make everything square at the height of this view.
        params = new LayoutParams(params.height, params.height);
        ((LinearLayout.LayoutParams) params).weight = 1;
        super.addView(child, index, params);
    }
}
