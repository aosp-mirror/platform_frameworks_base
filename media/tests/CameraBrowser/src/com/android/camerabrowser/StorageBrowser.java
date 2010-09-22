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
 * A list view displaying all storage units on a device.
 */
public class StorageBrowser extends ListActivity {

    private static final String TAG = "StorageBrowser";

    private ListAdapter mAdapter;
    private int mDeviceID;

    private static final String[] STORAGE_COLUMNS =
        new String[] { Mtp.Storage._ID, Mtp.Storage.DESCRIPTION };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mDeviceID = getIntent().getIntExtra("device", 0);
        if (mDeviceID != 0) {
            Cursor c = getContentResolver().query(Mtp.Storage.getContentUri(mDeviceID),
                    STORAGE_COLUMNS, null, null, null);
            Log.d(TAG, "query returned " + c);
            startManagingCursor(c);

            // Map Cursor columns to views defined in simple_list_item_1.xml
            mAdapter = new SimpleCursorAdapter(this,
                    android.R.layout.simple_list_item_1, c,
                            new String[] { Mtp.Storage.DESCRIPTION },
                            new int[] { android.R.id.text1, android.R.id.text2 });
            setListAdapter(mAdapter);
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = new Intent(this, ObjectBrowser.class);
        intent.putExtra("device", mDeviceID);
        intent.putExtra("storage", (int)mAdapter.getItemId(position));
        startActivity(intent);
    }
}
