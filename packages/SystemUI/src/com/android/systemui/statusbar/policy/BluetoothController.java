/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import java.util.ArrayList;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;

public class BluetoothController extends BroadcastReceiver {
    private static final String TAG = "StatusBar.BluetoothController";

    private Context mContext;
    private ArrayList<ImageView> mIconViews = new ArrayList<ImageView>();

    private int mIconId = R.drawable.stat_sys_data_bluetooth;
    private boolean mEnabled;

    public BluetoothController(Context context) {
        mContext = context;

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        context.registerReceiver(this, filter);
    }

    public void addIconView(ImageView v) {
        mIconViews.add(v);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                BluetoothAdapter.STATE_DISCONNECTED);
        int contentDescriptionResId = 0;

        if (state == BluetoothAdapter.STATE_CONNECTED) {
            mIconId = R.drawable.stat_sys_data_bluetooth_connected;
            contentDescriptionResId = R.string.accessibility_bluetooth_connected;
        } else {
            mIconId = R.drawable.stat_sys_data_bluetooth;
            contentDescriptionResId = R.string.accessibility_bluetooth_disconnected;
        }

        int N = mIconViews.size();
        for (int i=0; i<N; i++) {
            ImageView v = mIconViews.get(i);
            v.setImageResource(mIconId);
            v.setVisibility(mEnabled ? View.VISIBLE : View.GONE);
            v.setContentDescription(mContext.getString(contentDescriptionResId));
        }
    }
}
