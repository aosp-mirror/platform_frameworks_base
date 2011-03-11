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
import android.content.Context;
import android.content.Intent;
import android.mtp.MtpDevice;
import android.mtp.MtpStorageInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/**
 * A list view displaying all storage units on a device.
 */
public class StorageBrowser extends ListActivity {

    private static final String TAG = "StorageBrowser";

    private MtpClient mClient;
    private String mDeviceName;
    private List<MtpStorageInfo> mStorageList;
    private DeviceDisconnectedReceiver mDisconnectedReceiver;

    private class StorageAdapter extends BaseAdapter {
        private final Context mContext;
        private final LayoutInflater mInflater;

        public StorageAdapter(Context c) {
            mContext = c;
            mInflater = (LayoutInflater)c.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        public int getCount() {
            if (mStorageList == null) {
                return 0;
            } else {
                return mStorageList.size();
            }
        }

        public Object getItem(int position) {
            return mStorageList.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view;
            if (convertView == null) {
                view = (TextView)mInflater.inflate(
                        android.R.layout.simple_list_item_1, parent, false);
            } else {
                view = (TextView)convertView;
            }

            MtpStorageInfo info = mStorageList.get(position);
            if (info != null) {
                view.setText(info.getDescription());
            } else {
                view.setText("???");
            }
            return view;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mClient = ((CameraBrowserApplication)getApplication()).getMtpClient();
        mDeviceName = getIntent().getStringExtra("device");
        mDisconnectedReceiver = new DeviceDisconnectedReceiver(this, mDeviceName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mStorageList = mClient.getStorageList(mDeviceName);
        setListAdapter(new StorageAdapter(this));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mDisconnectedReceiver);
        super.onDestroy();
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = new Intent(this, ObjectBrowser.class);
        intent.putExtra("device", mDeviceName);
        intent.putExtra("storage", mStorageList.get(position).getStorageId());
        startActivity(intent);
    }
}
