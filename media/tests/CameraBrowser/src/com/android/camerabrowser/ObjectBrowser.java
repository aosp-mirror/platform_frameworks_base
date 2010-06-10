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
import android.net.Uri;
import android.os.Bundle;
import android.provider.Mtp;
import android.util.Log;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ResourceCursorAdapter;
import android.widget.TextView;

 /**
 * A list view displaying all objects within a container (folder or storage unit).
 */
public class ObjectBrowser extends ListActivity {

    private static final String TAG = "ObjectBrowser";

    private ListAdapter mAdapter;
    private int mDeviceID;
    private int mStorageID;
    private int mObjectID;

    private static final String[] OBJECT_COLUMNS =
        new String[] { Mtp.Object._ID, Mtp.Object.NAME, Mtp.Object.FORMAT, Mtp.Object.THUMB };

    static final int ID_COLUMN = 0;
    static final int NAME_COLUMN = 1;
    static final int FORMAT_COLUMN = 2;
    static final int THUMB_COLUMN = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mDeviceID = getIntent().getIntExtra("device", 0);
        mStorageID = getIntent().getIntExtra("storage", 0);
        mObjectID = getIntent().getIntExtra("object", 0);
        if (mDeviceID != 0 && mStorageID != 0) {
            Cursor c;
            if (mObjectID == 0) {
                c = getContentResolver().query(
                        Mtp.Object.getContentUriForStorageChildren(mDeviceID, mStorageID),
                        OBJECT_COLUMNS, null, null, null);
            } else {
                c = getContentResolver().query(
                        Mtp.Object.getContentUriForObjectChildren(mDeviceID, mObjectID),
                        OBJECT_COLUMNS, null, null, null);
            }
            Log.d(TAG, "query returned " + c);
            startManagingCursor(c);

            // Map Cursor columns to views defined in simple_list_item_1.xml
            mAdapter = new ObjectCursorAdapter(this, c);
            setListAdapter(mAdapter);
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        int rowID = (int)mAdapter.getItemId(position);
        Cursor c = getContentResolver().query(
                        Mtp.Object.getContentUri(mDeviceID, rowID),
                        OBJECT_COLUMNS, null, null, null);
        Log.d(TAG, "query returned " + c + " count: " + c.getCount());
        long format = 0;
        if (c != null && c.getCount() == 1) {
            c.moveToFirst();
            long rowId = c.getLong(ID_COLUMN);
            String name = c.getString(NAME_COLUMN);
            format = c.getLong(FORMAT_COLUMN);
            Log.d(TAG, "rowId: " + rowId + " name: " + name + " format: " + format);
        }
        if (format == Mtp.Object.FORMAT_JFIF) {
            Intent intent = new Intent(this, ObjectViewer.class);
            intent.putExtra("device", mDeviceID);
            intent.putExtra("storage", mStorageID);
            intent.putExtra("object",rowID);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, ObjectBrowser.class);
            intent.putExtra("device", mDeviceID);
            intent.putExtra("storage", mStorageID);
            intent.putExtra("object", rowID);
            startActivity(intent);
        }
    }

    private class ObjectCursorAdapter extends ResourceCursorAdapter {

        public ObjectCursorAdapter(Context context, Cursor c) {
            super(context, R.layout.object_list, c);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            ImageView thumbView = (ImageView)view.findViewById(R.id.thumbnail);
            TextView nameView = (TextView)view.findViewById(R.id.name);

            // get the thumbnail
            byte[] thumbnail = cursor.getBlob(THUMB_COLUMN);
            if (thumbnail != null) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.length);
                if (bitmap != null) {
                    thumbView.setImageBitmap(bitmap);
                }
            }

            // get the name
            String name = cursor.getString(NAME_COLUMN);
            if (name == null) {
                name = "";
            }
            nameView.setText(name);
        }
    }
}
