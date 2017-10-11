/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import com.android.systemui.R;

/**
 * Similar to a ListView, but it will show only as many items as fit on screen and
 * bind those instead of scrolling.
 */
public class AutoSizingList extends LinearLayout {

    private static final String TAG = "AutoSizingList";
    private final int mItemSize;
    private final Handler mHandler;

    private ListAdapter mAdapter;
    private int mCount;
    private boolean mEnableAutoSizing;

    public AutoSizingList(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        mHandler = new Handler();
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AutoSizingList);
        mItemSize = a.getDimensionPixelSize(R.styleable.AutoSizingList_itemHeight, 0);
        mEnableAutoSizing = a.getBoolean(R.styleable.AutoSizingList_enableAutoSizing, true);
        a.recycle();
    }

    public void setAdapter(ListAdapter adapter) {
        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(mDataObserver);
        }
        mAdapter = adapter;
        if (adapter != null) {
            adapter.registerDataSetObserver(mDataObserver);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int requestedHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (requestedHeight != 0) {
            int count = getItemCount(requestedHeight);
            if (mCount != count) {
                postRebindChildren();
                mCount = count;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private int getItemCount(int requestedHeight) {
        int desiredCount = getDesiredCount();
        return mEnableAutoSizing ? Math.min(requestedHeight / mItemSize, desiredCount)
                : desiredCount;
    }

    private int getDesiredCount() {
        return mAdapter != null ? mAdapter.getCount() : 0;
    }

    private void postRebindChildren() {
        mHandler.post(mBindChildren);
    }

    private void rebindChildren() {
        if (mAdapter == null) {
            return;
        }
        for (int i = 0; i < mCount; i++) {
            View v = i < getChildCount() ? getChildAt(i) : null;
            View newView = mAdapter.getView(i, v, this);
            if (newView != v) {
                if (v != null) {
                    removeView(v);
                }
                addView(newView, i);
            }
        }
        // Ditch extra views.
        while (getChildCount() > mCount) {
            removeViewAt(getChildCount() - 1);
        }
    }

    private final Runnable mBindChildren = new Runnable() {
        @Override
        public void run() {
            rebindChildren();
        }
    };

    private final DataSetObserver mDataObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            if (mCount > getDesiredCount()) {
                mCount = getDesiredCount();
            }
            postRebindChildren();
        }

        @Override
        public void onInvalidated() {
            postRebindChildren();
        }
    };
}
