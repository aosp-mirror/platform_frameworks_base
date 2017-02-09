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
 * limitations under the License.
 */
package com.android.server.autofill;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.autofill.Dataset;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Filter.FilterListener;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * View for dataset picker.
 *
 * <p>A fill session starts when a View is clicked and FillResponse is supplied.
 * <p>A fill session ends when 1) the user takes action in the UI, 2) another View is clicked, or
 * 3) the View is detached.
 */
final class DatasetPicker extends ListView implements OnItemClickListener {
    interface Listener {
        void onDatasetPicked(Dataset dataset);
    }

    private final Listener mListener;

    DatasetPicker(Context context, ArraySet<Dataset> datasets, Listener listener) {
        super(context);
        mListener = listener;

        final List<ViewItem> items = new ArrayList<>(datasets.size());
        for (Dataset dataset : datasets) {
            items.add(new ViewItem(dataset));
        }

        final ArrayAdapter<ViewItem> adapter = new ArrayAdapter<ViewItem>(
            context,
            android.R.layout.simple_list_item_1,
            android.R.id.text1,
            items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final TextView textView = (TextView) super.getView(position, convertView, parent);
                textView.setSingleLine();
                textView.setEllipsize(TextUtils.TruncateAt.END);
                textView.setMinHeight(
                        getDimen(com.android.internal.R.dimen.autofill_fill_item_height));
                return textView;
            }
        };
        setAdapter(adapter);
        setBackgroundColor(Color.WHITE);
        setDivider(null);
        setElevation(getDimen(com.android.internal.R.dimen.autofill_fill_elevation));
        setOnItemClickListener(this);
    }

    public void update(String prefix) {
        final ArrayAdapter<ViewItem> adapter = (ArrayAdapter) getAdapter();
        adapter.getFilter().filter(prefix, new FilterListener() {
            @Override
            public void onFilterComplete(int count) {
                setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            }
        });
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int pos, long id) {
        if (mListener != null) {
            final ViewItem vi = (ViewItem) adapterView.getItemAtPosition(pos);
            mListener.onDatasetPicked(vi.getData());
        }
    }

    private int getDimen(int resId) {
        return getContext().getResources().getDimensionPixelSize(resId);
    }

    private static class ViewItem {
        private final Dataset mData;

        ViewItem(Dataset data) {
            mData = data;
        }

        public Dataset getData() {
            return mData;
        }

        @Override
        public String toString() {
            // used by ArrayAdapter
            return mData.getName().toString();
        }
    }
}
