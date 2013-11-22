/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.documentsui;

import android.database.DataSetObserver;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;

import com.android.internal.util.Preconditions;

/**
 * Adapter that wraps an existing adapter, presenting its contents in multiple
 * equally-sized horizontal columns.
 */
public class ColumnAdapter extends BaseAdapter {
    private final ListAdapter mWrapped;
    private final OnItemClickListener mListener;

    private int mColumns = 1;

    public interface OnItemClickListener {
        public void onItemClick(ListAdapter adapter, int position);
    }

    public ColumnAdapter(ListAdapter wrapped, OnItemClickListener listener) {
        mWrapped = Preconditions.checkNotNull(wrapped);
        mListener = Preconditions.checkNotNull(listener);

        if (!wrapped.areAllItemsEnabled()) {
            throw new IllegalStateException("All items must be enabled");
        }
        if (wrapped.getViewTypeCount() > 1) {
            throw new IllegalStateException("All items must be identical");
        }
    }

    public static void prepare(ListView list) {
        list.setItemsCanFocus(true);
    }

    public void setColumns(int columns) {
        mColumns = columns;
        notifyDataSetChanged();
    }

    private View.OnClickListener mItemListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final int position = (Integer) v.getTag();
            mListener.onItemClick(mWrapped, position);
        }
    };

    @Override
    public int getCount() {
        return (mWrapped.getCount() + mColumns - 1) / mColumns;
    }

    @Override
    public Object getItem(int position) {
        return position;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = new LinearLayout(parent.getContext());
        }

        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.weight = 1f / mColumns;

        final LinearLayout row = (LinearLayout) convertView;
        final int first = position * mColumns;
        final int last = mWrapped.getCount() - 1;

        for (int i = 0; i < mColumns; i++) {
            View convertItem = null;
            if (i < row.getChildCount()) {
                convertItem = row.getChildAt(i);
            }

            final int pos = first + i;
            final int validPos = Math.min(pos, last);
            final View item = mWrapped.getView(validPos, convertItem, row);
            item.setTag(validPos);
            item.setOnClickListener(mItemListener);
            item.setFocusable(true);

            if (pos == validPos) {
                item.setVisibility(View.VISIBLE);
            } else {
                item.setVisibility(View.INVISIBLE);
            }

            if (convertItem == null) {
                row.addView(item, params);
            }
        }

        return convertView;
    }

    @Override
    public void registerDataSetObserver(DataSetObserver observer) {
        super.registerDataSetObserver(observer);
        mWrapped.registerDataSetObserver(observer);
    }

    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {
        super.unregisterDataSetObserver(observer);
        mWrapped.unregisterDataSetObserver(observer);
    }
}
