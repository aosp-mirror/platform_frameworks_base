/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.volume;

import static android.app.slice.Slice.HINT_ERROR;
import static android.app.slice.SliceItem.FORMAT_SLICE;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.slice.Slice;
import androidx.slice.SliceItem;
import androidx.slice.widget.SliceView;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RecyclerView adapter for Slices in Settings Panels.
 */
public class VolumePanelSlicesAdapter extends
        RecyclerView.Adapter<VolumePanelSlicesAdapter.SliceRowViewHolder> {

    private final List<LiveData<Slice>> mSliceLiveData;
    private final LifecycleOwner mLifecycleOwner;
    private SliceView.OnSliceActionListener mOnSliceActionListener;

    public VolumePanelSlicesAdapter(LifecycleOwner lifecycleOwner,
            Map<Uri, LiveData<Slice>> sliceLiveData) {
        mLifecycleOwner = lifecycleOwner;
        mSliceLiveData = new ArrayList<>(sliceLiveData.values());
    }

    @NonNull
    @Override
    public SliceRowViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        final Context context = viewGroup.getContext();
        final LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.volume_panel_slice_slider_row, viewGroup, false);
        return new SliceRowViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SliceRowViewHolder sliceRowViewHolder, int position) {
        sliceRowViewHolder.onBind(mSliceLiveData.get(position), position);
    }

    @Override
    public int getItemCount() {
        return mSliceLiveData.size();
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    void setOnSliceActionListener(SliceView.OnSliceActionListener listener) {
        mOnSliceActionListener = listener;
    }

    void updateDataSet(ArrayList<LiveData<Slice>> list) {
        mSliceLiveData.clear();
        mSliceLiveData.addAll(list);
        notifyDataSetChanged();
    }

    /**
     * ViewHolder for binding Slices to SliceViews.
     */
    public class SliceRowViewHolder extends RecyclerView.ViewHolder {

        private final SliceView mSliceView;

        public SliceRowViewHolder(View view) {
            super(view);
            mSliceView = view.findViewById(R.id.slice_view);
            mSliceView.setMode(SliceView.MODE_LARGE);
            mSliceView.setShowTitleItems(true);
            mSliceView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            mSliceView.setOnSliceActionListener(mOnSliceActionListener);
        }

        /**
         * Called when the view is displayed.
         */
        public void onBind(LiveData<Slice> sliceLiveData, int position) {
            sliceLiveData.observe(mLifecycleOwner, mSliceView);

            // Do not show the divider above media devices switcher slice per request
            final Slice slice = sliceLiveData.getValue();

            // Hides slice which reports with error hint or not contain any slice sub-item.
            if (slice == null || !isValidSlice(slice)) {
                mSliceView.setVisibility(View.GONE);
            } else {
                mSliceView.setVisibility(View.VISIBLE);
            }
        }

        private boolean isValidSlice(Slice slice) {
            if (slice.getHints().contains(HINT_ERROR)) {
                return false;
            }
            for (SliceItem item : slice.getItems()) {
                if (item.getFormat().equals(FORMAT_SLICE)) {
                    return true;
                }
            }
            return false;
        }
    }
}
