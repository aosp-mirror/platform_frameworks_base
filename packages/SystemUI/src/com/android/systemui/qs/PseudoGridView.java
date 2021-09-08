/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.android.systemui.R;

import java.lang.ref.WeakReference;

/**
 * A view that arranges it's children in a grid with a fixed number of evenly spaced columns.
 *
 * {@see android.widget.GridView}
 */
public class PseudoGridView extends ViewGroup {

    private int mNumColumns = 3;
    private int mVerticalSpacing;
    private int mHorizontalSpacing;
    private int mFixedChildWidth = -1;

    public PseudoGridView(Context context, AttributeSet attrs) {
        super(context, attrs);

        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PseudoGridView);

        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            int attr = a.getIndex(i);
            if (attr == R.styleable.PseudoGridView_numColumns) {
                mNumColumns = a.getInt(attr, 3);
            } else if (attr == R.styleable.PseudoGridView_verticalSpacing) {
                mVerticalSpacing = a.getDimensionPixelSize(attr, 0);
            } else if (attr == R.styleable.PseudoGridView_horizontalSpacing) {
                mHorizontalSpacing = a.getDimensionPixelSize(attr, 0);
            } else if (attr == R.styleable.PseudoGridView_fixedChildWidth) {
                mFixedChildWidth = a.getDimensionPixelSize(attr, -1);
            }
        }

        a.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            throw new UnsupportedOperationException("Needs a maximum width");
        }
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int childWidth;
        int necessarySpaceForChildWidth =
                mFixedChildWidth * mNumColumns + mHorizontalSpacing * (mNumColumns - 1);
        if (mFixedChildWidth != -1 && necessarySpaceForChildWidth <= width) {
            childWidth = mFixedChildWidth;
            width = mFixedChildWidth * mNumColumns + mHorizontalSpacing * (mNumColumns - 1);
        } else {
            childWidth = (width - (mNumColumns - 1) * mHorizontalSpacing) / mNumColumns;
        }
        int childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.UNSPECIFIED;
        int totalHeight = 0;
        int children = getChildCount();
        int rows = (children + mNumColumns - 1) / mNumColumns;
        for (int row = 0; row < rows; row++) {
            int startOfRow = row * mNumColumns;
            int endOfRow = Math.min(startOfRow + mNumColumns, children);
            int maxHeight = 0;
            for (int i = startOfRow; i < endOfRow; i++) {
                View child = getChildAt(i);
                child.measure(childWidthSpec, childHeightSpec);
                maxHeight = Math.max(maxHeight, child.getMeasuredHeight());
            }
            int maxHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY);
            for (int i = startOfRow; i < endOfRow; i++) {
                View child = getChildAt(i);
                if (child.getMeasuredHeight() != maxHeight) {
                    child.measure(childWidthSpec, maxHeightSpec);
                }
            }
            totalHeight += maxHeight;
            if (row > 0) {
                totalHeight += mVerticalSpacing;
            }
        }

        setMeasuredDimension(width, resolveSizeAndState(totalHeight, heightMeasureSpec, 0));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean isRtl = isLayoutRtl();
        int children = getChildCount();
        int rows = (children + mNumColumns - 1) / mNumColumns;
        int y = 0;
        for (int row = 0; row < rows; row++) {
            int x = isRtl ? getWidth() : 0;
            int maxHeight = 0;
            int startOfRow = row * mNumColumns;
            int endOfRow = Math.min(startOfRow + mNumColumns, children);
            for (int i = startOfRow; i < endOfRow; i++) {
                View child = getChildAt(i);
                int width = child.getMeasuredWidth();
                int height = child.getMeasuredHeight();
                if (isRtl) {
                    x -= width;
                }
                child.layout(x, y, x + width, y + height);
                maxHeight = Math.max(maxHeight, height);
                if (isRtl) {
                    x -= mHorizontalSpacing;
                } else {
                    x += width + mHorizontalSpacing;
                }
            }
            y += maxHeight;
            if (row > 0) {
                y += mVerticalSpacing;
            }
        }
    }

    /**
     * Bridges between a ViewGroup and a BaseAdapter.
     * <p>
     * Usage: {@code ViewGroupAdapterBridge.link(viewGroup, adapter)}
     * <br />
     * After this call, the ViewGroup's children will be provided by the adapter.
     */
    public static class ViewGroupAdapterBridge extends DataSetObserver {

        private final WeakReference<ViewGroup> mViewGroup;
        private final BaseAdapter mAdapter;
        private boolean mReleased;

        public static void link(ViewGroup viewGroup, BaseAdapter adapter) {
            new ViewGroupAdapterBridge(viewGroup, adapter);
        }

        private ViewGroupAdapterBridge(ViewGroup viewGroup, BaseAdapter adapter) {
            mViewGroup = new WeakReference<>(viewGroup);
            mAdapter = adapter;
            mReleased = false;
            mAdapter.registerDataSetObserver(this);
            refresh();
        }

        private void refresh() {
            if (mReleased) {
                return;
            }
            ViewGroup viewGroup = mViewGroup.get();
            if (viewGroup == null) {
                release();
                return;
            }
            final int childCount = viewGroup.getChildCount();
            final int adapterCount = mAdapter.getCount();
            final int N = Math.max(childCount, adapterCount);
            for (int i = 0; i < N; i++) {
                if (i < adapterCount) {
                    View oldView = null;
                    if (i < childCount) {
                        oldView = viewGroup.getChildAt(i);
                    }
                    View newView = mAdapter.getView(i, oldView, viewGroup);
                    if (oldView == null) {
                        // We ran out of existing views. Add it at the end.
                        viewGroup.addView(newView);
                    } else if (oldView != newView) {
                        // We couldn't rebind the view. Replace it.
                        viewGroup.removeViewAt(i);
                        viewGroup.addView(newView, i);
                    }
                } else {
                    int lastIndex = viewGroup.getChildCount() - 1;
                    viewGroup.removeViewAt(lastIndex);
                }
            }
        }

        @Override
        public void onChanged() {
            refresh();
        }

        @Override
        public void onInvalidated() {
            release();
        }

        private void release() {
            if (!mReleased) {
                mReleased = true;
                mAdapter.unregisterDataSetObserver(this);
            }
        }
    }
}
