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
 * A list view displaying all connected cameras.
 */
public class CameraBrowser extends ListActivity {

    private static final String TAG = "CameraBrowser";

    private ListAdapter mAdapter;

    private static final String[] DEVICE_COLUMNS =
         new String[] { Mtp.Device._ID, Mtp.Device.MANUFACTURER, Mtp.Device.MODEL };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Cursor c = getContentResolver().query(Mtp.Device.CONTENT_URI,
                DEVICE_COLUMNS, null, null, null);
        Log.d(TAG, "query returned " + c);
        startManagingCursor(c);

        // Map Cursor columns to views defined in simple_list_item_2.xml
        mAdapter = new SimpleCursorAdapter(this,
                android.R.layout.simple_list_item_2, c,
                        new String[] { Mtp.Device.MANUFACTURER, Mtp.Device.MODEL },
                        new int[] { android.R.id.text1, android.R.id.text2 });
        setListAdapter(mAdapter);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = new Intent(this, StorageBrowser.class);
        intent.putExtra("device", (int)mAdapter.getItemId(position));
        startActivity(intent);
    }
}
