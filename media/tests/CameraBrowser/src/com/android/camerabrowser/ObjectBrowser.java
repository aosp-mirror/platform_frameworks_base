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
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Mtp;
import android.util.Log;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;

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
        new String[] { Mtp.Object._ID, Mtp.Object.NAME, Mtp.Object.FORMAT };

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
            mAdapter = new SimpleCursorAdapter(this,
                    android.R.layout.simple_list_item_1, c,
                            new String[] { Mtp.Object.NAME },
                            new int[] { android.R.id.text1, android.R.id.text2 });
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
            long rowId = c.getLong(0);
            String name = c.getString(1);
            format = c.getLong(2);
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
}
