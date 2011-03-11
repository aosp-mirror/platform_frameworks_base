/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.camerabrowser;

import android.app.ListActivity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.mtp.MtpDevice;
import android.mtp.MtpDeviceInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TwoLineListItem;

import java.util.List;

 /**
 * A list view displaying all connected cameras.
 */
public class CameraBrowser extends ListActivity implements MtpClient.Listener {

    private static final String TAG = "CameraBrowser";

    private MtpClient mClient;
    private List<MtpDevice> mDeviceList;

    private static final int MODEL_COLUMN = 0;
    private static final int MANUFACTURER_COLUMN = 1;
    private static final int COLUMN_COUNT = 2;

    private class CameraAdapter extends BaseAdapter {
        private final Context mContext;
        private final LayoutInflater mInflater;

        public CameraAdapter(Context c) {
            mContext = c;
            mInflater = (LayoutInflater)c.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        public int getCount() {
            return mDeviceList.size();
        }

        public Object getItem(int position) {
            return mDeviceList.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            TwoLineListItem view;
            if (convertView == null) {
                view = (TwoLineListItem)mInflater.inflate(
                        android.R.layout.simple_list_item_2, parent, false);
            } else {
                view = (TwoLineListItem)convertView;
            }

            TextView textView1 = (TextView)view.findViewById(android.R.id.text1);
            TextView textView2 = (TextView)view.findViewById(android.R.id.text2);
            MtpDevice device = mDeviceList.get(position);
            MtpDeviceInfo info = device.getDeviceInfo();
            if (info != null) {
                textView1.setText(info.getManufacturer());
                textView2.setText(info.getModel());
            } else {
                textView1.setText("???");
                textView2.setText("???");
            }
            return view;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mClient = ((CameraBrowserApplication)getApplication()).getMtpClient();
        mClient.addListener(this);
        mDeviceList = mClient.getDeviceList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        mClient.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = new Intent(this, StorageBrowser.class);
        intent.putExtra("device", mDeviceList.get(position).getDeviceName());
        startActivity(intent);
    }

    private void reload() {
        setListAdapter(new CameraAdapter(this));
    }

    public void deviceAdded(MtpDevice device) {
        Log.d(TAG, "deviceAdded: " + device.getDeviceName());
        mDeviceList = mClient.getDeviceList();
        reload();
    }

    public void deviceRemoved(MtpDevice device) {
        Log.d(TAG, "deviceRemoved: " + device.getDeviceName());
        mDeviceList = mClient.getDeviceList();
        reload();
    }
}
