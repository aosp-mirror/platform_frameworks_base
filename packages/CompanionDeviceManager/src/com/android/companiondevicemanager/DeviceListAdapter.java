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

import static com.android.companiondevicemanager.Utils.getIcon;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
/**
 * Adapter for the list of "found" devices.
 */
class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {
    public int mSelectedPosition = RecyclerView.NO_POSITION;

    private final Context mContext;

    // List if pairs (display name, address)
    private List<DeviceFilterPair<?>> mDevices;

    private OnItemClickListener mListener;

    private static final int TYPE_WIFI = 0;
    private static final int TYPE_BT = 1;

    DeviceListAdapter(Context context, OnItemClickListener listener) {
        mContext = context;
        mListener = listener;
    }

    public DeviceFilterPair<?> getItem(int position) {
        return mDevices.get(position);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.list_item_device, parent, false);
        ViewHolder viewHolder = new ViewHolder(view);
        if (viewType == TYPE_WIFI) {
            viewHolder.mImageView.setImageDrawable(
                    getIcon(mContext, com.android.internal.R.drawable.ic_wifi_signal_3));
        } else {
            viewHolder.mImageView.setImageDrawable(
                    getIcon(mContext, android.R.drawable.stat_sys_data_bluetooth));
        }
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        holder.itemView.setSelected(mSelectedPosition == position);
        holder.mTextView.setText(mDevices.get(position).getDisplayName());
        holder.itemView.setOnClickListener(v -> mListener.onItemClick(position));
    }

    @Override
    public int getItemViewType(int position) {
        return isWifiDevice(position) ? TYPE_WIFI : TYPE_BT;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return mDevices != null ? mDevices.size() : 0;
    }

    public void setSelectedPosition(int position) {
        mSelectedPosition = position;
    }

    void setDevices(List<DeviceFilterPair<?>> devices) {
        mDevices = devices;
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private TextView mTextView;
        private ImageView mImageView;
        ViewHolder(View itemView) {
            super(itemView);
            mTextView = itemView.findViewById(android.R.id.text1);
            mImageView = itemView.findViewById(android.R.id.icon);
        }
    }

    private boolean isWifiDevice(int position) {
        return mDevices.get(position).getDevice() instanceof android.net.wifi.ScanResult;
    }

    public interface OnItemClickListener {
        void onItemClick(int position);
    }
}
