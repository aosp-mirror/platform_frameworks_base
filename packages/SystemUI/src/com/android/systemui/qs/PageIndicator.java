package com.android.systemui.qs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.R;

public class PageIndicator extends LinearLayout {

    private final int mPageIndicatorSize;

    public PageIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
        setGravity(Gravity.CENTER);
        mPageIndicatorSize =
                (int) mContext.getResources().getDimension(R.dimen.qs_page_indicator_size);
    }

    public void setNumPages(int numPages) {
        while (numPages < getChildCount()) {
            removeViewAt(getChildCount() - 1);
        }
        while (numPages > getChildCount()) {
            SinglePageIndicator v = new SinglePageIndicator(mContext);
            v.setAmount(0);
            addView(v, new LayoutParams(mPageIndicatorSize, mPageIndicatorSize));
        }
    }

    public void setLocation(float location) {
        int index = (int) location;
        location -= index;

        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
            float amount = 0;
            if (i == index) {
                amount = 1 - location;
            } else if (i == index + 1) {
                amount = location;
            }
            ((SinglePageIndicator) getChildAt(i)).setAmount(amount);
        }
    }

    // This could be done with a circle drawable and an ImageView, but this seems
    // easier for now.
    public static class SinglePageIndicator extends View {
        private static final int MIN_ALPHA = 0x4d;
        private static final int MAX_ALPHA = 0xff;

        private static final float MIN_SIZE = .55f;
        private static final float MAX_SIZE = .7f;

        private final Paint mPaint;
        private float mSize;

        public SinglePageIndicator(Context context) {
            super(context);
            mPaint = new Paint();
            mPaint.setColor(0xffffffff);
            mPaint.setAlpha(MAX_ALPHA);
        }

        public void setAmount(float amount) {
            mSize = amount * (MAX_SIZE - MIN_SIZE) + MIN_SIZE;
            int alpha = (int) (amount * (MAX_ALPHA - MIN_ALPHA)) + MIN_ALPHA;
            mPaint.setAlpha(alpha);
            postInvalidate();
        }

        @Override
        public void draw(Canvas canvas) {
            int minDimen = Math.min(getWidth(), getHeight()) / 2;
            float radius = mSize * minDimen;
            float x = getWidth() / 2f;
            float y = getHeight() / 2f;
            canvas.drawCircle(x, y, radius, mPaint);
        }
    }
}
