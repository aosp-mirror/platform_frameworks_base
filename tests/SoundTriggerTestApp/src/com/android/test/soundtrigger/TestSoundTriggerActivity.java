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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import android.app.Activity;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.soundtrigger.SoundTriggerDetector;
import android.media.soundtrigger.SoundTriggerManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserManager;
import android.text.Editable;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class TestSoundTriggerActivity extends Activity {
    private static final String TAG = "TestSoundTriggerActivity";
    private static final boolean DBG = false;

    private SoundTriggerUtil mSoundTriggerUtil;
    private Random mRandom;

    private Map<Integer, ModelInfo> mModelInfoMap;
    private Map<View, Integer> mModelIdMap;

    private TextView mDebugView = null;
    private int mSelectedModelId = -1;
    private ScrollView mScrollView = null;
    private Button mPlayTriggerButton = null;
    private PowerManager.WakeLock mScreenWakelock;
    private Handler mHandler;
    private RadioGroup mRadioGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (DBG) Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mDebugView = (TextView) findViewById(R.id.console);
        mScrollView = (ScrollView) findViewById(R.id.scroller_id);
        mRadioGroup = (RadioGroup) findViewById(R.id.model_group_id);
        mPlayTriggerButton = (Button) findViewById(R.id.play_trigger_id);
        mDebugView.setText(mDebugView.getText(), TextView.BufferType.EDITABLE);
        mDebugView.setMovementMethod(new ScrollingMovementMethod());
        mSoundTriggerUtil = new SoundTriggerUtil(this);
        mRandom = new Random();
        mHandler = new Handler();

        mModelInfoMap = new HashMap();
        mModelIdMap = new HashMap();

        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Load all the models in the data dir.
        for (File file : getFilesDir().listFiles()) {
            // Find meta-data in .properties files, ignore everything else.
            if (!file.getName().endsWith(".properties")) {
                continue;
            }
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(file));
                createModelInfoAndWidget(properties);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load properties file " + file.getName());
            }
        }

        // Create a few dummy models if we didn't load anything.
        if (mModelIdMap.isEmpty()) {
            Properties dummyModelProperties = new Properties();
            for (String name : new String[]{"One", "Two", "Three"}) {
                dummyModelProperties.setProperty("name", "Model " + name);
                createModelInfoAndWidget(dummyModelProperties);
            }
        }
    }

    private void createModelInfoAndWidget(Properties properties) {
        try {
            ModelInfo modelInfo = new ModelInfo();

            if (!properties.containsKey("name")) {
                throw new RuntimeException("must have a 'name' property");
            }
            modelInfo.name = properties.getProperty("name");

            if (properties.containsKey("modelUuid")) {
                modelInfo.modelUuid = UUID.fromString(properties.getProperty("modelUuid"));
            } else {
                modelInfo.modelUuid = UUID.randomUUID();
            }

            if (properties.containsKey("vendorUuid")) {
                modelInfo.vendorUuid = UUID.fromString(properties.getProperty("vendorUuid"));
            } else {
                modelInfo.vendorUuid = UUID.randomUUID();
            }

            if (properties.containsKey("triggerAudio")) {
                modelInfo.triggerAudioPlayer = MediaPlayer.create(this, Uri.parse(
                        getFilesDir().getPath() + "/" + properties.getProperty("triggerAudio")));
            }

            if (properties.containsKey("dataFile")) {
                File modelDataFile = new File(
                        getFilesDir().getPath() + "/" + properties.getProperty("dataFile"));
                modelInfo.modelData = new byte[(int) modelDataFile.length()];
                FileInputStream input = new FileInputStream(modelDataFile);
                input.read(modelInfo.modelData, 0, modelInfo.modelData.length);
            } else {
                modelInfo.modelData = new byte[1024];
                mRandom.nextBytes(modelInfo.modelData);
            }

            // TODO: Add property support for keyphrase models when they're exposed by the
            // service. Also things like how much audio they should record with the capture session
            // provided in the callback.

            // Add a widget into the radio group.
            RadioButton button = new RadioButton(this);
            mRadioGroup.addView(button);
            button.setText(modelInfo.name);
            button.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    onRadioButtonClicked(v);
                }
            });

            // Update our maps containing the button -> id and id -> modelInfo.
            int newModelId = mModelIdMap.size() + 1;
            mModelIdMap.put(button, newModelId);
            mModelInfoMap.put(newModelId, modelInfo);

            // If we don't have something selected, select this first thing.
            if (mSelectedModelId < 0) {
                button.setChecked(true);
                onRadioButtonClicked(button);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error parsing properties for " + properties.getProperty("name"), e);
        }
    }

    private void postMessage(String msg) {
        Log.i(TAG, "Posted: " + msg);
        ((Editable) mDebugView.getText()).append(msg + "\n");
        if ((mDebugView.getMeasuredHeight() - mScrollView.getScrollY()) <=
                (mScrollView.getHeight() + mDebugView.getLineHeight())) {
            scrollToBottom();
        }
    }

    private void scrollToBottom() {
        mScrollView.post(new Runnable() {
            public void run() {
                mScrollView.smoothScrollTo(0, mDebugView.getBottom());
            }
        });
    }

    private synchronized UUID getSelectedUuid() {
        return mModelInfoMap.get(mSelectedModelId).modelUuid;
    }

    private synchronized void setDetector(SoundTriggerDetector detector) {
        mModelInfoMap.get(mSelectedModelId).detector = detector;
    }

    private synchronized SoundTriggerDetector getDetector() {
        return mModelInfoMap.get(mSelectedModelId).detector;
    }

    private void screenWakeup() {
        PowerManager pm = ((PowerManager)getSystemService(POWER_SERVICE));
        if (mScreenWakelock == null) {
            mScreenWakelock =  pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "TAG");
        }
        mScreenWakelock.acquire();
    }

    private void screenRelease() {
        PowerManager pm = ((PowerManager)getSystemService(POWER_SERVICE));
        mScreenWakelock.release();
    }

    /** TODO: Should return the abstract sound model that can be then sent to the service. */
    private GenericSoundModel createNewSoundModel() {
        ModelInfo modelInfo = mModelInfoMap.get(mSelectedModelId);
        return new GenericSoundModel(modelInfo.modelUuid, modelInfo.vendorUuid,
                modelInfo.modelData);
    }

    /**
     * Called when the user clicks the enroll button.
     * Performs a fresh enrollment.
     */
    public void onEnrollButtonClicked(View v) {
        postMessage("Loading model: " + mSelectedModelId);

        GenericSoundModel model = createNewSoundModel();

        boolean status = mSoundTriggerUtil.addOrUpdateSoundModel(model);
        if (status) {
            Toast.makeText(
                    this, "Successfully created sound trigger model UUID=" + model.uuid,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to enroll!!!" + model.uuid, Toast.LENGTH_SHORT).show();
        }

        // Test the SoundManager API.
    }

    /**
     * Called when the user clicks the un-enroll button.
     * Clears the enrollment information for the user.
     */
    public void onUnEnrollButtonClicked(View v) {
        postMessage("Unloading model: " + mSelectedModelId);
        UUID modelUuid = getSelectedUuid();
        GenericSoundModel soundModel = mSoundTriggerUtil.getSoundModel(modelUuid);
        if (soundModel == null) {
            Toast.makeText(this, "Sound model not found!!!", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean status = mSoundTriggerUtil.deleteSoundModel(modelUuid);
        if (status) {
            Toast.makeText(this, "Successfully deleted model UUID=" + soundModel.uuid,
                    Toast.LENGTH_SHORT)
                    .show();
        } else {
            Toast.makeText(this, "Failed to delete sound model!!!", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Called when the user clicks the re-enroll button.
     * Uses the previously enrolled sound model and makes changes to it before pushing it back.
     */
    public void onReEnrollButtonClicked(View v) {
        postMessage("Re-loading model: " + mSelectedModelId);
        UUID modelUuid = getSelectedUuid();
        GenericSoundModel soundModel = mSoundTriggerUtil.getSoundModel(modelUuid);
        if (soundModel == null) {
            Toast.makeText(this, "Sound model not found!!!", Toast.LENGTH_SHORT).show();
            return;
        }
        GenericSoundModel updated = createNewSoundModel();
        boolean status = mSoundTriggerUtil.addOrUpdateSoundModel(updated);
        if (status) {
            Toast.makeText(this, "Successfully re-enrolled, model UUID=" + updated.uuid,
                    Toast.LENGTH_SHORT)
                    .show();
        } else {
            Toast.makeText(this, "Failed to re-enroll!!!", Toast.LENGTH_SHORT).show();
        }
    }

    public void onStartRecognitionButtonClicked(View v) {
        UUID modelUuid = getSelectedUuid();
        SoundTriggerDetector detector = getDetector();
        if (detector == null) {
            Log.i(TAG, "Created an instance of the SoundTriggerDetector for model #" +
                    mSelectedModelId);
            postMessage("Created an instance of the SoundTriggerDetector for model #" +
                    mSelectedModelId);
            detector = mSoundTriggerUtil.createSoundTriggerDetector(modelUuid,
                    new DetectorCallback());
            setDetector(detector);
        }
        postMessage("Triggering start recognition for model: " + mSelectedModelId);
        if (!detector.startRecognition(
                SoundTriggerDetector.RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS)) {
            Log.e(TAG, "Fast failure attempting to start recognition.");
            postMessage("Fast failure attempting to start recognition:" + mSelectedModelId);
        }
    }

    public void onStopRecognitionButtonClicked(View v) {
        SoundTriggerDetector detector = getDetector();
        if (detector == null) {
            Log.e(TAG, "Stop called on null detector.");
            postMessage("Error: Stop called on null detector.");
            return;
        }
        postMessage("Triggering stop recognition for model: " + mSelectedModelId);
        if (!detector.stopRecognition()) {
            Log.e(TAG, "Fast failure attempting to stop recognition.");
            postMessage("Fast failure attempting to stop recognition: " + mSelectedModelId);
        }
    }

    public synchronized void onRadioButtonClicked(View view) {
        // Is the button now checked?
        boolean checked = ((RadioButton) view).isChecked();
        if (checked) {
            mSelectedModelId = mModelIdMap.get(view);
            ModelInfo modelInfo = mModelInfoMap.get(mSelectedModelId);
            postMessage("Selected " + modelInfo.name);

            // Set the play trigger button to be enabled only if we actually have some audio.
            mPlayTriggerButton.setEnabled(modelInfo.triggerAudioPlayer != null);
        }
    }

    public synchronized void onPlayTriggerButtonClicked(View v) {
        ModelInfo modelInfo = mModelInfoMap.get(mSelectedModelId);
        modelInfo.triggerAudioPlayer.start();
        postMessage("Playing trigger audio for " + modelInfo.name);
    }

    // Helper struct for holding information about a model.
    private static class ModelInfo {
      public String name;
      public UUID modelUuid;
      public UUID vendorUuid;
      public MediaPlayer triggerAudioPlayer;
      public SoundTriggerDetector detector;
      public byte modelData[];
    };

    // Implementation of SoundTriggerDetector.Callback.
    public class DetectorCallback extends SoundTriggerDetector.Callback {
        public void onAvailabilityChanged(int status) {
            postMessage("Availability changed to: " + status);
        }

        public void onDetected(SoundTriggerDetector.EventPayload event) {
            postMessage("onDetected(): " + eventPayloadToString(event));
            screenWakeup();
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                   screenRelease();
                }
            }, 1000L);
        }

        public void onError() {
            postMessage("onError()");
        }

        public void onRecognitionPaused() {
            postMessage("onRecognitionPaused()");
        }

        public void onRecognitionResumed() {
            postMessage("onRecognitionResumed()");
        }
    }

    private String eventPayloadToString(SoundTriggerDetector.EventPayload event) {
        String result = "EventPayload(";
        AudioFormat format =  event.getCaptureAudioFormat();
        result = result + "AudioFormat: " + ((format == null) ? "null" : format.toString());
        byte[] triggerAudio = event.getTriggerAudio();
        result = result + "TriggerAudio: " + (triggerAudio == null ? "null" : triggerAudio.length);
        result = result + "CaptureSession: " + event.getCaptureSession();
        result += " )";
        return result;
    }
}
