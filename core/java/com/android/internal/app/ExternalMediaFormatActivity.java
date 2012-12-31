/*
 * Copyright (C) 2007 Google Inc.
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

package com.android.internal.app;

import com.android.internal.os.storage.ExternalStorageFormatter;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

/**
 * This activity is shown to the user to confirm formatting of external media.
 * It uses the alert dialog style. It will be launched from a notification, or from settings
 */
public class ExternalMediaFormatActivity extends AlertActivity implements DialogInterface.OnClickListener {

    private static final int POSITIVE_BUTTON = AlertDialog.BUTTON_POSITIVE;
    public static final String FORMAT_PATH = "format_path";

    private StorageManager mStorageManager;
    private StorageVolume mStorageVolume = null;

    /** Used to detect when the media state changes, in case we need to call finish() */
    private BroadcastReceiver mStorageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d("ExternalMediaFormatActivity", "got action " + action);

            if (action == Intent.ACTION_MEDIA_REMOVED ||
                action == Intent.ACTION_MEDIA_CHECKING ||
                action == Intent.ACTION_MEDIA_MOUNTED ||
                action == Intent.ACTION_MEDIA_SHARED) {
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // This is necessary because this class's caller,
        // packages/SystemUI/src/com/android/systemui/usb/StorageNotification.java,
        // supplies the path to be erased/formatted as a String, instead of a
        // StorageVolume. This for-loop gets the correct StorageVolume from the
        // given path.
        mStorageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        String path = getIntent().getStringExtra(FORMAT_PATH);
        StorageVolume[] volumes = mStorageManager.getVolumeList();

        for (StorageVolume sv : volumes) {
            if (path.equals(sv.getPath())) {
                mStorageVolume = sv;
                break;
            }
        }

        Log.d("ExternalMediaFormatActivity", "onCreate!");
        Log.d("ExternalMediaFormatActivity", "The storage volume to be formatted is : "
                + mStorageVolume.getPath());

        // Set up the "dialog"
        final AlertController.AlertParams p = mAlertParams;
        p.mIconId = com.android.internal.R.drawable.stat_sys_warning;
        p.mTitle = getString(com.android.internal.R.string.extmedia_format_title);
        p.mMessage = String.format(
                getString(com.android.internal.R.string.extmedia_format_message),
                mStorageVolume.getPath());
        p.mPositiveButtonText = getString(com.android.internal.R.string.extmedia_format_button_format);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonText = getString(com.android.internal.R.string.cancel);
        p.mNegativeButtonListener = this;
        setupAlert();
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_CHECKING);
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_SHARED);
        registerReceiver(mStorageReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();

        unregisterReceiver(mStorageReceiver);
    }

    /**
     * {@inheritDoc}
     */
    public void onClick(DialogInterface dialog, int which) {

        if (which == POSITIVE_BUTTON) {
            Intent intent = new Intent(ExternalStorageFormatter.FORMAT_ONLY);
            intent.setComponent(ExternalStorageFormatter.COMPONENT_NAME);
            intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, mStorageVolume);
            startService(intent);
        }

        // No matter what, finish the activity
        finish();
    }
}