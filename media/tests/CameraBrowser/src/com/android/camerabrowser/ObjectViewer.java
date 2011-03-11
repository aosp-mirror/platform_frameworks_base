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
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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

    private MtpClient mClient;
    private String mDeviceName;
    private int mStorageID;
    private int mObjectID;
    private String mFileName;
    private Button mImportButton;
    private Button mDeleteButton;
    private DeviceDisconnectedReceiver mDisconnectedReceiver;

    private final class ScannerClient implements MediaScannerConnectionClient {
        private final Context mContext;
        private String mPath;

        public ScannerClient(Context context) {
            mContext = context;
        }

        public void setScanPath(String path) {
            mPath = path;
        }

        @Override
        public void onMediaScannerConnected() {
            mScannerConnection.scanFile(mPath, null);
        }

        @Override
        public void onScanCompleted(String path, Uri uri) {
            mScannerConnection.disconnect();

            // try to start an activity to view the file
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            try {
                mContext.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(mContext, R.string.start_activity_failed_message,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private MediaScannerConnection mScannerConnection;
    private ScannerClient mScannerClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mClient = ((CameraBrowserApplication)getApplication()).getMtpClient();

        setContentView(R.layout.object_info);

        mImportButton = (Button)findViewById(R.id.import_button);
        mImportButton.setOnClickListener(this);
        mDeleteButton = (Button)findViewById(R.id.delete_button);
        mDeleteButton.setOnClickListener(this);

        mDeviceName = getIntent().getStringExtra("device");
        mStorageID = getIntent().getIntExtra("storage", 0);
        mObjectID = getIntent().getIntExtra("object", 0);
        mDisconnectedReceiver = new DeviceDisconnectedReceiver(this, mDeviceName);
        mScannerClient = new ScannerClient(this);
        mScannerConnection = new MediaScannerConnection(this, mScannerClient);
    }

    @Override
    protected void onResume() {
        super.onResume();

        MtpObjectInfo info = mClient.getObjectInfo(mDeviceName, mObjectID);
        if (info != null) {
            TextView view = (TextView)findViewById(R.id.name);
            mFileName = info.getName();
            view.setText(mFileName);
            view = (TextView)findViewById(R.id.format);
            view.setText(Integer.toHexString(info.getFormat()).toUpperCase());
            view = (TextView)findViewById(R.id.size);
            view.setText(Long.toString(info.getCompressedSize()));
            view = (TextView)findViewById(R.id.thumb_width);
            view.setText(Long.toString(info.getThumbPixWidth()));
            view = (TextView)findViewById(R.id.thumb_height);
            view.setText(Long.toString(info.getThumbPixHeight()));
            view = (TextView)findViewById(R.id.thumb_size);
            view.setText(Long.toString(info.getThumbCompressedSize()));
            view = (TextView)findViewById(R.id.width);
            view.setText(Long.toString(info.getImagePixWidth()));
            view = (TextView)findViewById(R.id.height);
            view.setText(Long.toString(info.getImagePixHeight()));
            view = (TextView)findViewById(R.id.depth);
            view.setText(Long.toString(info.getImagePixDepth()));
            view = (TextView)findViewById(R.id.sequence);
            view.setText(Long.toString(info.getSequenceNumber()));
            view = (TextView)findViewById(R.id.created);
            Date date = new Date(info.getDateCreated() * 1000);
            view.setText(date.toString());
            view = (TextView)findViewById(R.id.modified);
            date = new Date(info.getDateModified() * 1000);
            view.setText(date.toString());
            view = (TextView)findViewById(R.id.keywords);
            view.setText(info.getKeywords());
            int thumbFormat = info.getThumbFormat();
            if (thumbFormat == MtpConstants.FORMAT_EXIF_JPEG
                    || thumbFormat == MtpConstants.FORMAT_JFIF) {
                byte[] thumbnail = mClient.getThumbnail(mDeviceName, info.getObjectHandle());
                if (thumbnail != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.length);
                    if (bitmap != null) {
                        ImageView thumbView = (ImageView)findViewById(R.id.thumbnail);
                        thumbView.setImageBitmap(bitmap);
                    }
                }
            }
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

        if (mClient.importFile(mDeviceName, mObjectID, dest.getAbsolutePath())) {
            Toast.makeText(this, R.string.object_saved_message, Toast.LENGTH_SHORT).show();

            mScannerClient.setScanPath(dest.getAbsolutePath());
            mScannerConnection.connect();
        } else {
            Toast.makeText(this, R.string.save_failed_message, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteObject() {
        if (mClient.deleteObject(mDeviceName, mObjectID)) {
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
