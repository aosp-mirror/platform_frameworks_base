/*
 * Copyright (C) 2010 The Android Open Source Project
 * * Licensed under the Apache License, Version 2.0 (the "License");
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

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Mtp;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.util.Date;

/**
 * A view to display the properties of an object.
 */
public class ObjectViewer extends Activity {

    private static final String TAG = "ObjectViewer";

    private int mDeviceID;
    private int mStorageID;
    private int mObjectID;

    private static final String[] OBJECT_COLUMNS =
        new String[] {  Mtp.Object._ID,
                        Mtp.Object.NAME,
                        Mtp.Object.SIZE,
                        Mtp.Object.THUMB_WIDTH,
                        Mtp.Object.THUMB_HEIGHT,
                        Mtp.Object.THUMB_SIZE,
                        Mtp.Object.IMAGE_WIDTH,
                        Mtp.Object.IMAGE_HEIGHT,
                        Mtp.Object.IMAGE_DEPTH,
                        Mtp.Object.SEQUENCE_NUMBER,
                        Mtp.Object.DATE_CREATED,
                        Mtp.Object.DATE_MODIFIED,
                        Mtp.Object.KEYWORDS,
                        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.object_info);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mDeviceID = getIntent().getIntExtra("device", 0);
        mStorageID = getIntent().getIntExtra("storage", 0);
        mObjectID = getIntent().getIntExtra("object", 0);

        if (mDeviceID != 0 && mObjectID != 0) {
        Cursor c = getContentResolver().query(
                        Mtp.Object.getContentUri(mDeviceID, mObjectID),
                        OBJECT_COLUMNS, null, null, null);
            c.moveToFirst();
            TextView view = (TextView)findViewById(R.id.name);
            view.setText(c.getString(1));
            view = (TextView)findViewById(R.id.size);
            view.setText(Long.toString(c.getLong(2)));
            view = (TextView)findViewById(R.id.thumb_width);
            view.setText(Long.toString(c.getLong(3)));
            view = (TextView)findViewById(R.id.thumb_height);
            view.setText(Long.toString(c.getLong(4)));
            view = (TextView)findViewById(R.id.thumb_size);
            view.setText(Long.toString(c.getLong(5)));
            view = (TextView)findViewById(R.id.width);
            view.setText(Long.toString(c.getLong(6)));
            view = (TextView)findViewById(R.id.height);
            view.setText(Long.toString(c.getLong(7)));
            view = (TextView)findViewById(R.id.depth);
            view.setText(Long.toString(c.getLong(8)));
            view = (TextView)findViewById(R.id.sequence);
            view.setText(Long.toString(c.getLong(9)));
            view = (TextView)findViewById(R.id.created);
            Date date = new Date(c.getLong(10) * 1000);
            view.setText(date.toString());
            view = (TextView)findViewById(R.id.modified);
            date = new Date(c.getLong(11) * 1000);
            view.setText(date.toString());
            view = (TextView)findViewById(R.id.keywords);
            view.setText(c.getString(12));
        }
    }
}
