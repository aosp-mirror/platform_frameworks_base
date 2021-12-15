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

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * Adapter for the list of "found" devices.
 */
class DeviceListAdapter extends BaseAdapter implements Observer {
    private final Context mContext;
    private final Resources mResources;

    private final Drawable mBluetoothIcon;
    private final Drawable mWifiIcon;

    private final @ColorInt int mTextColor;

    // List if pairs (display name, address)
    private List<DeviceFilterPair<?>> mDevices;

    DeviceListAdapter(Context context) {
        mContext = context;
        mResources = context.getResources();
        mBluetoothIcon = getTintedIcon(mResources, android.R.drawable.stat_sys_data_bluetooth);
        mWifiIcon = getTintedIcon(mResources, com.android.internal.R.drawable.ic_wifi_signal_3);
        mTextColor = getColor(context, android.R.attr.colorForeground);
    }

    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        final TextView view = convertView != null ? (TextView) convertView : newView();
        bind(view, getItem(position));
        return view;
    }

    private void bind(TextView textView, DeviceFilterPair<?> item) {
        textView.setText(item.getDisplayName());
        textView.setBackgroundColor(Color.TRANSPARENT);
        /*
        textView.setCompoundDrawablesWithIntrinsicBounds(
                item.getDevice() instanceof android.net.wifi.ScanResult
                        ? mWifiIcon
                        : mBluetoothIcon,
                null, null, null);
        textView.getCompoundDrawables()[0].setTint(mTextColor);
         */
    }

    private TextView newView() {
        final TextView textView = new TextView(mContext);
        textView.setTextColor(mTextColor);
        final int padding = 24;
        textView.setPadding(padding, padding, padding, padding);
        //textView.setCompoundDrawablePadding(padding);
        return textView;
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
    public void update(Observable o, Object arg) {
        mDevices = CompanionDeviceDiscoveryService.getScanResults();
        notifyDataSetChanged();
    }

    private @ColorInt int getColor(Context context, int attr) {
        final TypedArray a = context.obtainStyledAttributes(new TypedValue().data,
                new int[] { attr });
        final int color = a.getColor(0, 0);
        a.recycle();
        return color;
    }

    private static Drawable getTintedIcon(Resources resources, int drawableRes) {
        Drawable icon = resources.getDrawable(drawableRes, null);
        icon.setTint(Color.DKGRAY);
        return icon;
    }
}
