package com.android.server.status;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.util.Log;


public class ExpandedView extends LinearLayout {
    final Display mDisplay;
    StatusBarService mService;
    boolean mTracking;
    int mStartX, mStartY;
    int mMaxHeight = 0;
    int mPrevHeight = -1;

    public ExpandedView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    /** We want to shrink down to 0, and ignore the background. */
    @Override
    public int getSuggestedMinimumHeight() {
        return 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST));
    }

    @Override
     protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
         super.onLayout(changed, left, top, right, bottom);
         int height = bottom - top;
         if (height != mPrevHeight) {
             //Log.d(StatusBarService.TAG, "height changed old=" + mPrevHeight + " new=" + height);
             mPrevHeight = height;
             mService.updateExpandedViewPos(StatusBarService.EXPANDED_LEAVE_ALONE);
         }
     }

    void setMaxHeight(int h) {
        if (h != mMaxHeight) {
            mMaxHeight = h;
            requestLayout();
        }
    }
}
