
package com.android.server.status;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextSwitcher;


public class TickerView extends TextSwitcher
{
    Ticker mTicker;

    public TickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mTicker.reflowText();
    }
}

