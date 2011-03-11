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
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.mtp.MtpConstants;
import android.mtp.MtpDevice;
import android.mtp.MtpObjectInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

 /**
 * A list view displaying all objects within a container (folder or storage unit).
 */
public class ObjectBrowser extends ListActivity {

    private static final String TAG = "ObjectBrowser";

    private MtpClient mClient;
    private List<MtpObjectInfo> mObjectList;
    private String mDeviceName;
    private int mStorageID;
    private int mObjectID;
    private DeviceDisconnectedReceiver mDisconnectedReceiver;

    private class ObjectAdapter extends BaseAdapter {
        private final Context mContext;
        private final LayoutInflater mInflater;

        public ObjectAdapter(Context c) {
            mContext = c;
            mInflater = (LayoutInflater)c.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        public int getCount() {
            if (mObjectList == null) {
                return 0;
            } else {
                return mObjectList.size();
            }
        }

        public Object getItem(int position) {
            return mObjectList.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            if (convertView == null) {
                view = mInflater.inflate(R.layout.object_list, parent, false);
            } else {
                view = convertView;
            }

            TextView nameView = (TextView)view.findViewById(R.id.name);
            MtpObjectInfo info = mObjectList.get(position);
            nameView.setText(info.getName());

            int thumbFormat = info.getThumbFormat();
            if (thumbFormat == MtpConstants.FORMAT_EXIF_JPEG
                    || thumbFormat == MtpConstants.FORMAT_JFIF) {
                byte[] thumbnail = mClient.getThumbnail(mDeviceName, info.getObjectHandle());
                if (thumbnail != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.length);
                    if (bitmap != null) {
                        ImageView thumbView = (ImageView)view.findViewById(R.id.thumbnail);
                        thumbView.setImageBitmap(bitmap);
                    }
                }
            }
            return view;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mClient = ((CameraBrowserApplication)getApplication()).getMtpClient();
        mDeviceName = getIntent().getStringExtra("device");
        mStorageID = getIntent().getIntExtra("storage", 0);
        mObjectID = getIntent().getIntExtra("object", 0);
        mDisconnectedReceiver = new DeviceDisconnectedReceiver(this, mDeviceName);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mObjectList = mClient.getObjectList(mDeviceName, mStorageID, mObjectID);
        setListAdapter(new ObjectAdapter(this));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mDisconnectedReceiver);
        super.onDestroy();
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        MtpObjectInfo info = mObjectList.get(position);
        Intent intent;
        if (info.getFormat() == MtpConstants.FORMAT_ASSOCIATION) {
            intent = new Intent(this, ObjectBrowser.class);
        } else {
            intent = new Intent(this, ObjectViewer.class);
        }
        intent.putExtra("device", mDeviceName);
        intent.putExtra("storage", mStorageID);
        intent.putExtra("object", info.getObjectHandle());
        startActivity(intent);
    }
}
