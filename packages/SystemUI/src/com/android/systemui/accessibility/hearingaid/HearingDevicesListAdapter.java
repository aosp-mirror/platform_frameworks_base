/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.Utils;
import com.android.systemui.bluetooth.qsdialog.DeviceItem;
import com.android.systemui.res.R;

import kotlin.Pair;

import java.util.List;

/**
 * Adapter for showing hearing device item list {@link DeviceItem}.
 */
public class HearingDevicesListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final List<DeviceItem> mItemList;
    private final HearingDeviceItemCallback mCallback;

    public HearingDevicesListAdapter(List<DeviceItem> itemList,
            HearingDeviceItemCallback callback) {
        mItemList = itemList;
        mCallback = callback;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int position) {
        View view = LayoutInflater.from(viewGroup.getContext()).inflate(
                R.layout.bluetooth_device_item, viewGroup, false);
        return new DeviceItemViewHolder(view, viewGroup.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        DeviceItem item = mItemList.get(position);
        ((DeviceItemViewHolder) viewHolder).bindView(item, mCallback);
    }

    @Override
    public int getItemCount() {
        return mItemList.size();
    }

    /**
     * Updates items in the adapter.
     *
     * @param itemList bluetooth device item list
     */
    public void refreshDeviceItemList(List<DeviceItem> itemList) {
        mItemList.clear();
        mItemList.addAll(itemList);
        notifyDataSetChanged();
    }

    /**
     * Interface to provide callbacks when click on the device item in hearing device quick
     * settings tile.
     */
    public interface HearingDeviceItemCallback {
        /**
         * Called when gear view in device item is clicked.
         *
         * @param deviceItem bluetooth device item
         * @param view       the view that was clicked
         */
        void onDeviceItemGearClicked(@NonNull DeviceItem deviceItem, @NonNull View view);

        /**
         * Called when device item is clicked.
         *
         * @param deviceItem bluetooth device item
         * @param view       the view that was clicked
         */
        void onDeviceItemClicked(@NonNull DeviceItem deviceItem, @NonNull View view);
    }

    private static class DeviceItemViewHolder extends RecyclerView.ViewHolder {
        private final Context mContext;
        private final View mContainer;
        private final TextView mNameView;
        private final TextView mSummaryView;
        private final ImageView mIconView;
        private final ImageView mGearIcon;
        private final View mGearView;

        DeviceItemViewHolder(@NonNull View itemView, Context context) {
            super(itemView);
            mContext = context;
            mContainer = itemView.requireViewById(R.id.bluetooth_device_row);
            mNameView = itemView.requireViewById(R.id.bluetooth_device_name);
            mSummaryView = itemView.requireViewById(R.id.bluetooth_device_summary);
            mIconView = itemView.requireViewById(R.id.bluetooth_device_icon);
            mGearIcon = itemView.requireViewById(R.id.gear_icon_image);
            mGearView = itemView.requireViewById(R.id.gear_icon);
        }

        public void bindView(DeviceItem item, HearingDeviceItemCallback callback) {
            mContainer.setEnabled(item.isEnabled());
            mContainer.setOnClickListener(view -> callback.onDeviceItemClicked(item, view));
            Integer backgroundResId = item.getBackground();
            if (backgroundResId != null) {
                mContainer.setBackground(mContext.getDrawable(item.getBackground()));
            }

            // tint different color in different state for bad color contrast problem
            int tintColor = item.isActive() ? Utils.getColorAttr(mContext,
                    com.android.internal.R.attr.materialColorOnPrimaryContainer).getDefaultColor()
                    : Utils.getColorAttr(mContext,
                            com.android.internal.R.attr.materialColorOnSurface).getDefaultColor();

            Pair<Drawable, String> iconPair = item.getIconWithDescription();
            if (iconPair != null) {
                Drawable drawable = iconPair.getFirst().mutate();
                drawable.setTint(tintColor);
                mIconView.setImageDrawable(drawable);
                mIconView.setContentDescription(iconPair.getSecond());
            }

            mNameView.setTextAppearance(
                    item.isActive() ? R.style.BluetoothTileDialog_DeviceName_Active
                            : R.style.BluetoothTileDialog_DeviceName);
            mNameView.setText(item.getDeviceName());
            mSummaryView.setTextAppearance(
                    item.isActive() ? R.style.BluetoothTileDialog_DeviceSummary_Active
                            : R.style.BluetoothTileDialog_DeviceSummary);
            mSummaryView.setText(item.getConnectionSummary());

            mGearIcon.getDrawable().mutate().setTint(tintColor);
            mGearView.setOnClickListener(view -> callback.onDeviceItemGearClicked(item, view));
        }
    }
}
