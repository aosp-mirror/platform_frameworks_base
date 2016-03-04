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

import java.util.Random;
import java.util.UUID;

import android.app.Activity;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.media.AudioFormat;
import android.media.soundtrigger.SoundTriggerDetector;
import android.media.soundtrigger.SoundTriggerManager;
import android.text.Editable;
import android.text.method.ScrollingMovementMethod;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class TestSoundTriggerActivity extends Activity {
    private static final String TAG = "TestSoundTriggerActivity";
    private static final boolean DBG = false;

    private SoundTriggerUtil mSoundTriggerUtil;
    private Random mRandom;
    private UUID mModelUuid1 = UUID.randomUUID();
    private UUID mModelUuid2 = UUID.randomUUID();
    private UUID mModelUuid3 = UUID.randomUUID();
    private UUID mVendorUuid = UUID.randomUUID();

    private SoundTriggerDetector mDetector1 = null;
    private SoundTriggerDetector mDetector2 = null;
    private SoundTriggerDetector mDetector3 = null;

    private TextView mDebugView = null;
    private int mSelectedModelId = 1;
    private ScrollView mScrollView = null;
    private PowerManager.WakeLock mScreenWakelock;
    private Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (DBG) Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mDebugView = (TextView) findViewById(R.id.console);
        mScrollView = (ScrollView) findViewById(R.id.scroller_id);
        mDebugView.setText(mDebugView.getText(), TextView.BufferType.EDITABLE);
        mDebugView.setMovementMethod(new ScrollingMovementMethod());
        mSoundTriggerUtil = new SoundTriggerUtil(this);
        mRandom = new Random();
        mHandler = new Handler();
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
        if (mSelectedModelId == 2) return mModelUuid2;
        if (mSelectedModelId == 3) return mModelUuid3;
        return mModelUuid1;  // Default.
    }

    private synchronized void setDetector(SoundTriggerDetector detector) {
        if (mSelectedModelId == 2) {
            mDetector2 = detector;
            return;
        }
        if (mSelectedModelId == 3) {
            mDetector3 = detector;
            return;
        }
        mDetector1 = detector;
    }

    private synchronized SoundTriggerDetector getDetector() {
        if (mSelectedModelId == 2) return mDetector2;
        if (mSelectedModelId == 3) return mDetector3;
        return mDetector1;
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

    /**
     * Called when the user clicks the enroll button.
     * Performs a fresh enrollment.
     */
    public void onEnrollButtonClicked(View v) {
        postMessage("Loading model: " + mSelectedModelId);
        // Generate a fake model to push.
        byte[] data = new byte[1024];
        mRandom.nextBytes(data);
        UUID modelUuid = getSelectedUuid();
        GenericSoundModel model = new GenericSoundModel(modelUuid, mVendorUuid, data);

        boolean status = mSoundTriggerUtil.addOrUpdateSoundModel(model);
        if (status) {
            Toast.makeText(
                    this, "Successfully created sound trigger model UUID=" + modelUuid,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to enroll!!!" + modelUuid, Toast.LENGTH_SHORT).show();
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
        // Generate a fake model to push.
        byte[] data = new byte[2048];
        mRandom.nextBytes(data);
        GenericSoundModel updated = new GenericSoundModel(soundModel.uuid,
                soundModel.vendorUuid, data);
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
        // Check which radio button was clicked
        switch(view.getId()) {
            case R.id.model_one:
                if (checked) {
                    mSelectedModelId = 1;
                    postMessage("Selected model one.");
                }
                break;
            case R.id.model_two:
                if (checked) {
                    mSelectedModelId = 2;
                    postMessage("Selected model two.");
                }
                break;
            case R.id.model_three:
                if (checked) {
                    mSelectedModelId = 3;
                    postMessage("Selected model three.");
                }
                break;
        }
    }

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
