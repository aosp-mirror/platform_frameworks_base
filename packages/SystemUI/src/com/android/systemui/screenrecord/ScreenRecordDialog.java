/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Toast;

import com.android.systemui.R;

/**
 * Activity to select screen recording options
 */
public class ScreenRecordDialog extends Activity {
    private static final String TAG = "ScreenRecord";
    private static final int REQUEST_CODE_VIDEO_ONLY = 200;
    private static final int REQUEST_CODE_VIDEO_TAPS = 201;
    private static final int REQUEST_CODE_PERMISSIONS = 299;
    private static final int REQUEST_CODE_VIDEO_AUDIO = 300;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS = 301;
    private static final int REQUEST_CODE_PERMISSIONS_AUDIO = 399;
    
    private static final int REQUEST_CODE_VIDEO_ONLY_DOT = 202;
    private static final int REQUEST_CODE_VIDEO_TAPS_DOT = 203;
    private static final int REQUEST_CODE_VIDEO_AUDIO_DOT = 302;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT = 303;

    private boolean mUseAudio;
    private boolean mShowTaps;
    private boolean mShowDot;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_record_dialog);

        final CheckBox micCheckBox = findViewById(R.id.checkbox_mic);
        final CheckBox tapsCheckBox = findViewById(R.id.checkbox_taps);
        final CheckBox dotCheckBox = findViewById(R.id.checkbox_stopdot);

        final Button recordButton = findViewById(R.id.record_button);
        recordButton.setOnClickListener(v -> {
            mUseAudio = micCheckBox.isChecked();
            mShowTaps = tapsCheckBox.isChecked();
            mShowDot = dotCheckBox.isChecked();
            Log.d(TAG, "Record button clicked: audio " + mUseAudio + ", taps " + mShowTaps + ", dot " + mShowDot);

            if (mUseAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting permission for audio");
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_CODE_PERMISSIONS_AUDIO);
            } else {
                requestScreenCapture();
            }
        });
    }

    private void requestScreenCapture() {
        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(
                Context.MEDIA_PROJECTION_SERVICE);
        Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent();

        if (mUseAudio) {
            startActivityForResult(permissionIntent,
                    mShowTaps ? (mShowDot ? REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT : REQUEST_CODE_VIDEO_AUDIO_TAPS)
                    : (mShowDot ? REQUEST_CODE_VIDEO_AUDIO_DOT : REQUEST_CODE_VIDEO_AUDIO));
        } else {
            startActivityForResult(permissionIntent,
                    mShowTaps ? (mShowDot ? REQUEST_CODE_VIDEO_TAPS_DOT : REQUEST_CODE_VIDEO_TAPS)
                    : (mShowDot ? REQUEST_CODE_VIDEO_ONLY_DOT : REQUEST_CODE_VIDEO_ONLY));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mShowTaps = (requestCode == REQUEST_CODE_VIDEO_TAPS
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS
                || requestCode == REQUEST_CODE_VIDEO_TAPS_DOT
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT);
        mShowDot = (requestCode == REQUEST_CODE_VIDEO_AUDIO_DOT
                || requestCode == REQUEST_CODE_VIDEO_ONLY_DOT
                || requestCode == REQUEST_CODE_VIDEO_TAPS_DOT
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT);
        switch (requestCode) {
            case REQUEST_CODE_VIDEO_TAPS:
            case REQUEST_CODE_VIDEO_AUDIO_TAPS:
            case REQUEST_CODE_VIDEO_ONLY:
            case REQUEST_CODE_VIDEO_AUDIO:
            case REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT:
            case REQUEST_CODE_VIDEO_AUDIO_DOT:
            case REQUEST_CODE_VIDEO_TAPS_DOT:
            case REQUEST_CODE_VIDEO_ONLY_DOT:
                if (resultCode == RESULT_OK) {
                    mUseAudio = (requestCode == REQUEST_CODE_VIDEO_AUDIO
                            || requestCode == REQUEST_CODE_VIDEO_AUDIO_DOT
                            || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS
                            || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT);
                    startForegroundService(
                            RecordingService.getStartIntent(this, resultCode, data, mUseAudio,
                                    mShowTaps, mShowDot));
                } else {
                    Toast.makeText(this,
                            getResources().getString(R.string.screenrecord_permission_error),
                            Toast.LENGTH_SHORT).show();
                }
                finish();
                break;
            case REQUEST_CODE_PERMISSIONS:
                int permission = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (permission != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                            getResources().getString(R.string.screenrecord_permission_error),
                            Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    requestScreenCapture();
                }
                break;
            case REQUEST_CODE_PERMISSIONS_AUDIO:
                int videoPermission = checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE);
                int audioPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO);
                if (videoPermission != PackageManager.PERMISSION_GRANTED
                        || audioPermission != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                            getResources().getString(R.string.screenrecord_permission_error),
                            Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    requestScreenCapture();
                }
                break;
        }
    }
}
