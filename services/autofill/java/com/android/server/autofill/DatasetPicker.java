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
import android.service.autofill.Dataset;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RemoteViews;
import com.android.internal.R;
import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;

/**
 * This class manages the dataset selection UI.
 */
final class DatasetPicker extends FrameLayout implements OnItemClickListener {
    interface Listener {
        void onDatasetPicked(Dataset dataset);
        void onCanceled();
    }

    private final Listener mListener;

    private final ArrayAdapter<ViewItem> mAdapter;

    DatasetPicker(Context context, ArraySet<Dataset> datasets, AutoFillId filteredViewId,
            Listener listener) {
        super(context);
        mListener = listener;

        final List<ViewItem> items = new ArrayList<>(datasets.size());
        for (Dataset dataset : datasets) {
            final int index = dataset.getFieldIds().indexOf(filteredViewId);
            if (index >= 0) {
                AutoFillValue value = dataset.getFieldValues().get(index);
                items.add(new ViewItem(dataset, value.coerceToString()));
            }
        }

        mAdapter = new ArrayAdapter<ViewItem>(context, 0, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                RemoteViews presentation = getItem(position).getDataset().getPresentation();
                return presentation.apply(context, parent);
            }
        };

        LayoutInflater inflater = LayoutInflater.from(context);
        ListView content = (ListView) inflater.inflate(
                com.android.internal.R.layout.autofill_dataset_picker, this, true)
                .findViewById(com.android.internal.R.id.list);
        content.setAdapter(mAdapter);
        content.setOnItemClickListener(this);
    }

    public void update(String prefix) {
        mAdapter.getFilter().filter(prefix, (count) -> {
            if (count <= 0 && mListener != null) {
                mListener.onCanceled();
            }
        });
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int pos, long id) {
        if (mListener != null) {
            final ViewItem vi = (ViewItem) adapterView.getItemAtPosition(pos);
            mListener.onDatasetPicked(vi.getDataset());
        }
    }

    private static class ViewItem {
        private final String mValue;
        private final Dataset mDataset;

        ViewItem(Dataset dataset, String value) {
            mDataset = dataset;
            mValue = value;
        }

        public Dataset getDataset() {
            return mDataset;
        }

        @Override
        public String toString() {
            // used by ArrayAdapter
            return mValue;
        }
    }
}
