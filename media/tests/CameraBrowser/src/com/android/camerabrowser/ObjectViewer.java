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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.Mtp;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Calendar;
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
                        Mtp.Object.THUMB,
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
            byte[] thumbnail = c.getBlob(13);
            if (thumbnail != null) {
                ImageView thumbView = (ImageView)findViewById(R.id.thumbnail);
                Bitmap bitmap = BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.length);
                if (bitmap != null) {
                    thumbView.setImageBitmap(bitmap);
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.object_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.save);
        item.setEnabled(true);
        item = menu.findItem(R.id.delete);
        item.setEnabled(true);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.save:
                save();
                return true;
            case R.id.delete:
                delete();
                return true;
        }
        return false;
    }

    private static String getTimestamp() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis());
        return String.format("%tY-%tm-%td-%tH-%tM-%tS", c, c, c, c, c, c);
    }

    private void save() {
        boolean success = false;
        Uri uri = Mtp.Object.getContentUri(mDeviceID, mObjectID);
        File destFile = null;
        ParcelFileDescriptor pfd = null;
        FileInputStream fis = null;
        FileOutputStream fos = null;

        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
            Log.d(TAG, "save got pfd " + pfd);
            if (pfd != null) {
                fis = new FileInputStream(pfd.getFileDescriptor());
                Log.d(TAG, "save got fis " + fis);
                File destDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DCIM);
                destDir.mkdirs();
                destFile = new File(destDir, "CameraBrowser-" + getTimestamp() + ".jpeg");


                Log.d(TAG, "save got destFile " + destFile);

                if (destFile.exists()) {
                    destFile.delete();
                }
                fos = new FileOutputStream(destFile);

                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) >= 0) {
                    Log.d(TAG, "copying the bytes numbering " + bytesRead);
                    fos.write(buffer, 0, bytesRead);
                }

                // temporary workaround until we straighten out permissions in /data/media
                // 1015 is AID_SDCARD_RW
                FileUtils.setPermissions(destDir.getPath(), 0775, Process.myUid(), 1015);
                FileUtils.setPermissions(destFile.getPath(), 0664, Process.myUid(), 1015);

                success = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception in ObjectView.save", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception e) {
                }
            }
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception e) {
                }
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception e) {
                }
            }
        }

        if (success) {
            Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            intent.setData(Uri.fromFile(destFile));
            sendBroadcast(intent);
            Toast.makeText(this, R.string.object_saved_message, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.save_failed_message, Toast.LENGTH_SHORT).show();
        }
    }

    private void delete() {
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
}
