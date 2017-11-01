package com.example.android.nativemididemo;

import android.content.Context;
import android.view.MotionEvent;
import android.util.AttributeSet;
import android.widget.ScrollView;

public class TouchableScrollView extends ScrollView {
    public boolean isTouched;

    public TouchableScrollView(Context context) {
        super(context);
    }

    public TouchableScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isTouched = true;
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                isTouched = false;
                break;
        }
        return super.onTouchEvent(event);
    }
}
