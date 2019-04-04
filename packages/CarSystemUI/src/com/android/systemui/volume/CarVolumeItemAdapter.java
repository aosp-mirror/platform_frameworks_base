/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;

import java.util.List;

/** The {@link RecyclerView.Adapter} to show the volume items in the sysUI volume dialog. */
public class CarVolumeItemAdapter extends
        RecyclerView.Adapter<CarVolumeItem.CarVolumeItemViewHolder> {

    private final Context mContext;
    private final List<CarVolumeItem> mItems;

    public CarVolumeItemAdapter(Context context, List<CarVolumeItem> items) {
        mContext = context;
        mItems = items;
    }

    @Override
    public CarVolumeItem.CarVolumeItemViewHolder onCreateViewHolder(ViewGroup parent,
            int viewType) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        View view = inflater.inflate(R.layout.car_volume_item, parent, false);
        return new CarVolumeItem.CarVolumeItemViewHolder(view);
    }

    @Override
    public void onBindViewHolder(CarVolumeItem.CarVolumeItemViewHolder holder, int position) {
        mItems.get(position).bind(holder);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }
}
