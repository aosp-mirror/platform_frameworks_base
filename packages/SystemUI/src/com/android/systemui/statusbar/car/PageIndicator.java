/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.car;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.android.systemui.R;

import java.lang.ref.WeakReference;

/**
 * Displays the dots underneath the ViewPager on the lock screen. This is really just a simplified
 * version of PagerTitleStrip. We don't inherit from there because it's impossible to bypass some
 * of the overriden logic in that class.
 */
public class PageIndicator extends View {
    private static final String TAG = "PageIndicator";
    // These can be made a styleable attribute in the future if necessary.
    private static final int SELECTED_COLOR = 0xFFF5F5F5;  // grey 100
    private static final int UNSELECTED_COLOR = 0xFFBDBDBD;  // grey 400
    private final PageListener mPageListener = new PageListener();

    private ViewPager mPager;
    private WeakReference<PagerAdapter> mWatchingAdapter;

    private int mPageCount;
    private int mCurrentPosition;
    private Paint mPaint;
    private int mRadius;
    private int mStep;

    public PageIndicator(Context context) {
        super(context);
        init();
    }

    public PageIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setStyle(Paint.Style.FILL);
        mRadius = getResources().getDimensionPixelSize(R.dimen.car_page_indicator_dot_diameter) / 2;
        mStep = mRadius * 3;
    }

    public void setupWithViewPager(ViewPager pager) {
        mPager = pager;

        final PagerAdapter adapter = (PagerAdapter) pager.getAdapter();
        pager.addOnPageChangeListener(mPageListener);
        pager.addOnAdapterChangeListener(mPageListener);
        updateAdapter(mWatchingAdapter != null ? mWatchingAdapter.get() : null, adapter);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mPager != null) {
            updateAdapter(mPager.getAdapter(), null);
            mPager.removeOnPageChangeListener(mPageListener);
            mPager.removeOnAdapterChangeListener(mPageListener);
            mPager = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Don't draw anything unless there's multiple pages to scroll through.  No need to clear
        // any previous dots, since onDraw provides a canvas that's already cleared.
        if (mPageCount <= 1)
            return;

        int x = canvas.getWidth() / 2 - (mPageCount / 2) * mStep;
        int y = canvas.getHeight() / 2;

        for (int i = 0; i < mPageCount; i++) {
            if (i == mCurrentPosition) {
                mPaint.setColor(SELECTED_COLOR);
            } else {
                mPaint.setColor(UNSELECTED_COLOR);
            }

            canvas.drawCircle(x, y, mRadius, mPaint);
            x += mStep;
        }
    }

    void updateAdapter(PagerAdapter oldAdapter, PagerAdapter newAdapter) {
        if (oldAdapter != null) {
            oldAdapter.unregisterDataSetObserver(mPageListener);
            mWatchingAdapter = null;
        }

        if (newAdapter != null) {
            newAdapter.registerDataSetObserver(mPageListener);
            mWatchingAdapter = new WeakReference<>(newAdapter);
        }

        updateDots();

        if (mPager != null) {
            requestLayout();
        }
    }

    private <T> T getRef(WeakReference<T> weakRef) {
        if (weakRef == null) {
            return null;
        }
        return weakRef.get();
    }

    private void updateDots() {
        PagerAdapter adapter = getRef(mWatchingAdapter);
        if (adapter == null) {
            return;
        }

        int count = adapter.getCount();
        if (mPageCount == count) {
            // Nothing to be done.
            return;
        }

        mPageCount = count;
        mCurrentPosition = 0;
        invalidate();
    }

    private class PageListener extends DataSetObserver implements ViewPager.OnPageChangeListener,
            ViewPager.OnAdapterChangeListener {

        @Override
        public void onPageScrolled(int unused1, float unused2, int unused3) { }

        @Override
        public void onPageSelected(int position) {
            if (mCurrentPosition == position) {
                return;
            }

            if (mPageCount <= position) {
                Log.e(TAG, "Position out of bounds, position=" + position + " size=" + mPageCount);
                return;
            }

            mCurrentPosition = position;
            invalidate();
        }

        @Override
        public void onPageScrollStateChanged(int state) { }

        @Override
        public void onAdapterChanged(ViewPager viewPager, PagerAdapter oldAdapter,
                PagerAdapter newAdapter) {
            updateAdapter(oldAdapter, newAdapter);
        }

        @Override
        public void onChanged() {
            updateDots();
        }
    }
}
