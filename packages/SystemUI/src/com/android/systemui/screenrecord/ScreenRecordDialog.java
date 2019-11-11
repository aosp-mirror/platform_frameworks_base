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
import android.widget.Switch;
import android.widget.Toast;

import com.android.systemui.R;

/**
 * Activity to select screen recording options
 */
public class ScreenRecordDialog extends Activity {
    private static final String TAG = "ScreenRecord";
    
    private static final int REQUEST_CODE_PERMISSIONS = 201;
    private static final int REQUEST_CODE_PERMISSIONS_AUDIO = 202;

    private static final int REQUEST_CODE_VIDEO = 301;
    private static final int REQUEST_CODE_VIDEO_TAPS = 302;
    private static final int REQUEST_CODE_VIDEO_TAPS_DOT = 303;
    private static final int REQUEST_CODE_VIDEO_DOT = 304;    
    private static final int REQUEST_CODE_VIDEO_LOW = 305;   
    private static final int REQUEST_CODE_VIDEO_DOT_LOW = 306;
    private static final int REQUEST_CODE_VIDEO_TAPS_LOW = 307;
    private static final int REQUEST_CODE_VIDEO_TAPS_DOT_LOW = 308;    
    
    private static final int REQUEST_CODE_VIDEO_AUDIO = 401;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS = 402;
    private static final int REQUEST_CODE_VIDEO_AUDIO_DOT = 403;   
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT = 404;
    private static final int REQUEST_CODE_VIDEO_AUDIO_LOW = 405;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS_LOW = 406;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT_LOW = 407;
    private static final int REQUEST_CODE_VIDEO_AUDIO_DOT_LOW = 408;

    private boolean mUseAudio;
    private boolean mShowTaps;
    private boolean mShowDot;
    private boolean mLowQuality;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_record_dialog);

        final Switch micCheckBox = findViewById(R.id.checkbox_mic);
        final Switch tapsCheckBox = findViewById(R.id.checkbox_taps);
        final Switch dotCheckBox = findViewById(R.id.checkbox_stopdot);
        final Switch qualityCheckBox = findViewById(R.id.checkbox_low_quality);

        final Button recordButton = findViewById(R.id.record_button);
        recordButton.setOnClickListener(v -> {
            mUseAudio = micCheckBox.isChecked();
            mShowTaps = tapsCheckBox.isChecked();
            mShowDot = dotCheckBox.isChecked();
            mLowQuality = qualityCheckBox.isChecked();
            Log.d(TAG, "Record button clicked: audio " + mUseAudio + ", taps " + mShowTaps + ", dot " + mShowDot + ", quality " + mLowQuality);

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

        if (mLowQuality) {
            if (mUseAudio) {
                startActivityForResult(permissionIntent,
                        mShowTaps ? (mShowDot ? REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT_LOW : REQUEST_CODE_VIDEO_AUDIO_TAPS_LOW)
                        : (mShowDot ? REQUEST_CODE_VIDEO_AUDIO_DOT_LOW : REQUEST_CODE_VIDEO_AUDIO_LOW));
            } else {
                startActivityForResult(permissionIntent,
                        mShowTaps ? (mShowDot ? REQUEST_CODE_VIDEO_TAPS_DOT_LOW : REQUEST_CODE_VIDEO_TAPS_LOW)
                        : (mShowDot ? REQUEST_CODE_VIDEO_DOT_LOW : REQUEST_CODE_VIDEO_LOW));
            }
        } else {
            if (mUseAudio) {
                startActivityForResult(permissionIntent,
                        mShowTaps ? (mShowDot ? REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT : REQUEST_CODE_VIDEO_AUDIO_TAPS)
                        : (mShowDot ? REQUEST_CODE_VIDEO_AUDIO_DOT : REQUEST_CODE_VIDEO_AUDIO));
            } else {
                startActivityForResult(permissionIntent,
                        mShowTaps ? (mShowDot ? REQUEST_CODE_VIDEO_TAPS_DOT : REQUEST_CODE_VIDEO_TAPS)
                        : (mShowDot ? REQUEST_CODE_VIDEO_DOT : REQUEST_CODE_VIDEO));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mShowTaps = requestCode == REQUEST_CODE_VIDEO_TAPS
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS
                || requestCode == REQUEST_CODE_VIDEO_TAPS_DOT
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT
                || requestCode == REQUEST_CODE_VIDEO_TAPS_LOW
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_LOW
                || requestCode == REQUEST_CODE_VIDEO_TAPS_DOT_LOW
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT_LOW;
        mShowDot = requestCode == REQUEST_CODE_VIDEO_AUDIO_DOT
                || requestCode == REQUEST_CODE_VIDEO_DOT
                || requestCode == REQUEST_CODE_VIDEO_TAPS_DOT
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_DOT_LOW
                || requestCode == REQUEST_CODE_VIDEO_DOT_LOW
                || requestCode == REQUEST_CODE_VIDEO_TAPS_DOT_LOW
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_DOT_LOW;
        mLowQuality = ((requestCode > 304 && requestCode < 309)
                    || (requestCode > 404 && requestCode < 409));
        switch (requestCode) {
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
            default:
                if (resultCode == RESULT_OK) {
                    mUseAudio = requestCode > 400 && requestCode < 409;
                    startForegroundService(
                            RecordingService.getStartIntent(this, resultCode, data, mUseAudio,
                                    mShowTaps, mShowDot, mLowQuality));
                } else {
                    Toast.makeText(this,
                            getResources().getString(R.string.screenrecord_permission_error),
                            Toast.LENGTH_SHORT).show();
                }
                finish();
        }
    }
}
