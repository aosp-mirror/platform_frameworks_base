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
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Mtp;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Date;

/**
 * A view to display the properties of an object.
 */
public class ObjectViewer extends Activity implements View.OnClickListener {

    private static final String TAG = "ObjectViewer";

    private int mDeviceID;
    private long mStorageID;
    private long mObjectID;
    private String mFileName;
    private Button mImportButton;
    private Button mDeleteButton;
    private DeviceDisconnectedReceiver mDisconnectedReceiver;

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
                        Mtp.Object.THUMB,
                        Mtp.Object.FORMAT,
                        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.object_info);

        mImportButton = (Button)findViewById(R.id.import_button);
        mImportButton.setOnClickListener(this);
        mDeleteButton = (Button)findViewById(R.id.delete_button);
        mDeleteButton.setOnClickListener(this);

        mDeviceID = getIntent().getIntExtra("device", 0);
        mStorageID = getIntent().getLongExtra("storage", 0);
        mObjectID = getIntent().getLongExtra("object", 0);
        mDisconnectedReceiver = new DeviceDisconnectedReceiver(this, mDeviceID);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mDeviceID != 0 && mObjectID != 0) {
        Cursor c = getContentResolver().query(
                        Mtp.Object.getContentUri(mDeviceID, mObjectID),
                        OBJECT_COLUMNS, null, null, null);
            c.moveToFirst();
            TextView view = (TextView)findViewById(R.id.name);
            mFileName = c.getString(1);
            view.setText(mFileName);
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
            byte[] thumbnail = c.getBlob(13);
            if (thumbnail != null) {
                ImageView thumbView = (ImageView)findViewById(R.id.thumbnail);
                Bitmap bitmap = BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.length);
                if (bitmap != null) {
                    thumbView.setImageBitmap(bitmap);
                }
            }
            view = (TextView)findViewById(R.id.format);
            view.setText(Long.toHexString(c.getLong(14)).toUpperCase());
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mDisconnectedReceiver);
        super.onDestroy();
    }

    private void importObject() {
        // copy file to /mnt/sdcard/imported/<filename>
        File dest = Environment.getExternalStorageDirectory();
        dest = new File(dest, "imported");
        dest.mkdirs();
        dest = new File(dest, mFileName);

        Uri requestUri = Mtp.Object.getContentUriForImport(mDeviceID, mObjectID,
                dest.getAbsolutePath());
        Uri resultUri = getContentResolver().insert(requestUri, new ContentValues());
        Log.d(TAG, "save returned " + resultUri);

        if (resultUri != null) {
            Toast.makeText(this, R.string.object_saved_message, Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Intent.ACTION_VIEW, resultUri);
            startActivity(intent);
        } else {
            Toast.makeText(this, R.string.save_failed_message, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteObject() {
        Uri uri = Mtp.Object.getContentUri(mDeviceID, mObjectID);

        Log.d(TAG, "deleting " + uri);

        int result = getContentResolver().delete(uri, null, null);
        if (result > 0) {
            Toast.makeText(this, R.string.object_deleted_message, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, R.string.delete_failed_message, Toast.LENGTH_SHORT).show();
        }
    }

    public void onClick(View v) {
        if (v == mImportButton) {
            importObject();
        } else if (v == mDeleteButton) {
            deleteObject();
        }
    }
}
