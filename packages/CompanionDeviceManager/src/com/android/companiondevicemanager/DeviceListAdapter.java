/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.companiondevicemanager;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.lifecycle.Observer;

import java.util.List;

/**
 * Adapter for the list of "found" devices.
 */
class DeviceListAdapter extends BaseAdapter implements Observer<List<DeviceFilterPair<?>>> {
    private final Context mContext;

    // List if pairs (display name, address)
    private List<DeviceFilterPair<?>> mDevices;

    DeviceListAdapter(Context context) {
        mContext = context;
    }

    @Override
    public int getCount() {
        return mDevices != null ? mDevices.size() : 0;
    }

    @Override
    public DeviceFilterPair<?> getItem(int position) {
        return mDevices.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        final View view = convertView != null
                ? convertView
                : LayoutInflater.from(mContext).inflate(R.layout.list_item_device, parent, false);

        final DeviceFilterPair<?> item = getItem(position);
        bindView(view, item);

        return view;
    }

    private void bindView(@NonNull View view, DeviceFilterPair<?> item) {
        final TextView textView = view.findViewById(android.R.id.text1);
        textView.setText(item.getDisplayName());

        final ImageView iconView = view.findViewById(android.R.id.icon);

        // TODO(b/211417476): Set either Bluetooth or WiFi icon.
        iconView.setVisibility(View.GONE);
        // final int iconRes = isBt ? android.R.drawable.stat_sys_data_bluetooth
        //        : com.android.internal.R.drawable.ic_wifi_signal_3;
        // final Drawable icon = getTintedIcon(mResources, iconRes);
        // iconView.setImageDrawable(icon);
    }

    @Override
    public void onChanged(List<DeviceFilterPair<?>> deviceFilterPairs) {
        mDevices = deviceFilterPairs;
        notifyDataSetChanged();
    }
}
