/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.test.soundtrigger;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.Editable;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.test.soundtrigger.SoundTriggerTestService.SoundTriggerTestBinder;

public class SoundTriggerTestActivity extends Activity implements SoundTriggerTestService.UserActivity {
    private static final String TAG = "SoundTriggerTest";
    private static final int AUDIO_PERMISSIONS_REQUEST = 1;

    private SoundTriggerTestService mService = null;

    private static UUID mSelectedModelUuid = null;

    private Map<RadioButton, UUID> mButtonModelUuidMap;
    private Map<UUID, RadioButton> mModelButtons;
    private Map<UUID, String> mModelNames;
    private List<RadioButton> mModelRadioButtons;

    private TextView mDebugView = null;
    private ScrollView mScrollView = null;
    private Button mPlayTriggerButton = null;
    private PowerManager.WakeLock mScreenWakelock;
    private Handler mHandler;
    private RadioGroup mRadioGroup;
    private CheckBox mCaptureAudioCheckBox;
    private Button mPlayCapturedAudioButton = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Make sure that this activity can punch through the lockscreen if needed.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                             WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mDebugView = findViewById(R.id.console);
        mScrollView = findViewById(R.id.scroller_id);
        mRadioGroup = findViewById(R.id.model_group_id);
        mPlayTriggerButton = findViewById(R.id.play_trigger_id);
        mDebugView.setText(mDebugView.getText(), TextView.BufferType.EDITABLE);
        mDebugView.setMovementMethod(new ScrollingMovementMethod());
        mCaptureAudioCheckBox = findViewById(R.id.caputre_check_box);
        mPlayCapturedAudioButton = findViewById(R.id.play_captured_id);
        mHandler = new Handler();
        mButtonModelUuidMap = new HashMap();
        mModelButtons = new HashMap();
        mModelNames = new HashMap();
        mModelRadioButtons = new LinkedList();

        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Make sure that the service is started, so even if our activity goes down, we'll still
        // have a request for it to run.
        startService(new Intent(getBaseContext(), SoundTriggerTestService.class));

        // Bind to SoundTriggerTestService.
        Intent intent = new Intent(this, SoundTriggerTestService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unbind from the service.
        if (mService != null) {
            mService.setUserActivity(null);
            unbindService(mConnection);
        }
    }

    @Override
    public void addModel(UUID modelUuid, String name) {
        // Create a new widget for this model, and insert everything we'd need into the map.
        RadioButton button = new RadioButton(this);
        mModelRadioButtons.add(button);
        button.setText(name);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onRadioButtonClicked(v);
            }
        });
        mButtonModelUuidMap.put(button, modelUuid);
        mModelButtons.put(modelUuid, button);
        mModelNames.put(modelUuid, name);

        // Sort all the radio buttons by name, then push them into the group in order.
        Collections.sort(mModelRadioButtons, new Comparator<RadioButton>(){
            @Override
            public int compare(RadioButton button0, RadioButton button1) {
                return button0.getText().toString().compareTo(button1.getText().toString());
            }
        });
        mRadioGroup.removeAllViews();
        for (View v : mModelRadioButtons) {
            mRadioGroup.addView(v);
        }

        // If we don't have something selected, select this first thing.
        if (mSelectedModelUuid == null || mSelectedModelUuid.equals(modelUuid)) {
            button.setChecked(true);
            onRadioButtonClicked(button);
        }
    }

    @Override
    public void setModelState(UUID modelUuid, String state) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String newButtonText = mModelNames.get(modelUuid);
                if (state != null) {
                    newButtonText += ": " + state;
                }
                mModelButtons.get(modelUuid).setText(newButtonText);
                updateSelectModelSpecificUiElements();
            }
        });
    }

    @Override
    public void showMessage(String msg, boolean showToast) {
        // Append the message to the text field, then show the toast if requested.
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((Editable) mDebugView.getText()).append(msg + "\n");
                mScrollView.post(new Runnable() {
                    public void run() {
                        mScrollView.smoothScrollTo(0, mDebugView.getBottom());
                    }
                });
                if (showToast) {
                    Toast.makeText(SoundTriggerTestActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public void handleDetection(UUID modelUuid) {
        screenWakeup();
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                screenRelease();
            }
        }, 1000L);
    }

    private void screenWakeup() {
        if (mScreenWakelock == null) {
            PowerManager pm = ((PowerManager)getSystemService(POWER_SERVICE));
            mScreenWakelock =  pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        }
        mScreenWakelock.acquire();
    }

    private void screenRelease() {
        mScreenWakelock.release();
    }

    public void onLoadButtonClicked(View v) {
        if (mService == null) {
            Log.e(TAG, "Could not load sound model: not bound to SoundTriggerTestService");
        } else {
            mService.loadModel(mSelectedModelUuid);
        }
    }

    public void onUnloadButtonClicked(View v) {
        if (mService == null) {
           Log.e(TAG, "Can't unload model: not bound to SoundTriggerTestService");
        } else {
            mService.unloadModel(mSelectedModelUuid);
        }
    }

    public void onReloadButtonClicked(View v) {
        if (mService == null) {
            Log.e(TAG, "Can't reload model: not bound to SoundTriggerTestService");
        } else {
            mService.reloadModel(mSelectedModelUuid);
        }
    }

    public void onStartRecognitionButtonClicked(View v) {
        if (mService == null) {
            Log.e(TAG, "Can't start recognition: not bound to SoundTriggerTestService");
        } else {
            mService.startRecognition(mSelectedModelUuid);
        }
    }

    public void onStopRecognitionButtonClicked(View v) {
        if (mService == null) {
            Log.e(TAG, "Can't stop recognition: not bound to SoundTriggerTestService");
        } else {
            mService.stopRecognition(mSelectedModelUuid);
        }
    }

    public synchronized void onPlayTriggerButtonClicked(View v) {
        if (mService == null) {
            Log.e(TAG, "Can't play trigger audio: not bound to SoundTriggerTestService");
        } else {
            mService.playTriggerAudio(mSelectedModelUuid);
        }
    }

    public synchronized void onCaptureAudioCheckboxClicked(View v) {
        // See if we have the right permissions
        if (!mService.hasMicrophonePermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    AUDIO_PERMISSIONS_REQUEST);
            return;
        } else {
            mService.setCaptureAudio(mSelectedModelUuid, mCaptureAudioCheckBox.isChecked());
        }
    }

    @Override
    public synchronized void onRequestPermissionsResult(int requestCode, String permissions[],
                                                        int[] grantResults) {
        if (requestCode == AUDIO_PERMISSIONS_REQUEST) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                // Make sure that the check box is set to false.
                mCaptureAudioCheckBox.setChecked(false);
            }
            mService.setCaptureAudio(mSelectedModelUuid, mCaptureAudioCheckBox.isChecked());
        }
    }

    public synchronized void onPlayCapturedAudioButtonClicked(View v) {
        if (mService == null) {
            Log.e(TAG, "Can't play captured audio: not bound to SoundTriggerTestService");
        } else {
            mService.playCapturedAudio(mSelectedModelUuid);
        }
    }

    public synchronized void onRadioButtonClicked(View view) {
        // Is the button now checked?
        boolean checked = ((RadioButton) view).isChecked();
        if (checked) {
            mSelectedModelUuid = mButtonModelUuidMap.get(view);
            showMessage("Selected " + mModelNames.get(mSelectedModelUuid), false);
            updateSelectModelSpecificUiElements();
        }
    }

    private synchronized void updateSelectModelSpecificUiElements() {
        // Set the play trigger button to be enabled only if we actually have some audio.
        mPlayTriggerButton.setEnabled(mService.modelHasTriggerAudio((mSelectedModelUuid)));
        // Similar logic for the captured audio.
        mCaptureAudioCheckBox.setChecked(
                mService.modelWillCaptureTriggerAudio(mSelectedModelUuid));
        mPlayCapturedAudioButton.setEnabled(mService.modelHasCapturedAudio((mSelectedModelUuid)));
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            synchronized (SoundTriggerTestActivity.this) {
                // We've bound to LocalService, cast the IBinder and get LocalService instance
                SoundTriggerTestBinder binder = (SoundTriggerTestBinder) service;
                mService = binder.getService();
                mService.setUserActivity(SoundTriggerTestActivity.this);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            synchronized (SoundTriggerTestActivity.this) {
                mService.setUserActivity(null);
                mService = null;
            }
        }
    };
}
