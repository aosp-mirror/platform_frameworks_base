/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.effectstest;

import android.app.Activity;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.util.HashMap;

public class VisualizerTest extends Activity implements OnCheckedChangeListener {

    private final static String TAG = "Visualizer Test";

    private VisualizerInstance mVisualizer;
    ToggleButton mMultithreadedButton;
    ToggleButton mOnOffButton;
    ToggleButton mReleaseButton;
    boolean mUseMTInstance;
    boolean mEnabled;
    EditText mSessionText;
    static int sSession = 0;
    ToggleButton mCallbackButton;
    boolean mCallbackOn;
    private static HashMap<Integer, VisualizerInstance> sInstances =
            new HashMap<Integer, VisualizerInstance>(10);
    private Handler mUiHandler;

    public VisualizerTest() {
        Log.d(TAG, "contructor");
        mUiHandler = new UiHandler(Looper.getMainLooper());
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        TextView textView;

        setContentView(R.layout.visualizertest);

        mSessionText = findViewById(R.id.sessionEdit);
        mSessionText.setOnKeyListener(mSessionKeyListener);
        mSessionText.setText(Integer.toString(sSession));

        mMultithreadedButton = (ToggleButton) findViewById(R.id.visuMultithreadedOnOff);
        mReleaseButton = (ToggleButton) findViewById(R.id.visuReleaseButton);
        mOnOffButton = (ToggleButton) findViewById(R.id.visualizerOnOff);
        mCallbackButton = (ToggleButton) findViewById(R.id.visuCallbackOnOff);
        mCallbackOn = false;
        mCallbackButton.setChecked(mCallbackOn);

        final Button hammerReleaseTest = (Button) findViewById(R.id.hammer_on_release_bug);
        hammerReleaseTest.setEnabled(false);

        mMultithreadedButton.setOnCheckedChangeListener(this);
        if (getEffect(sSession) != null) {
            mReleaseButton.setOnCheckedChangeListener(this);
            mOnOffButton.setOnCheckedChangeListener(this);
            mCallbackButton.setOnCheckedChangeListener(this);

            hammerReleaseTest.setEnabled(true);
            hammerReleaseTest.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    runHammerReleaseTest(hammerReleaseTest);
                }
            });
        }
    }

    public static final int MSG_DISPLAY_WAVEFORM_VAL = 0;
    public static final int MSG_DISPLAY_FFT_VAL = 1;

    private class UiHandler extends Handler {
        UiHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISPLAY_WAVEFORM_VAL:
                case MSG_DISPLAY_FFT_VAL:
                    int[] minMaxCenter = (int[]) msg.obj;
                    boolean waveform = msg.what == MSG_DISPLAY_WAVEFORM_VAL;
                    displayVal(waveform ? R.id.waveformMin : R.id.fftMin, minMaxCenter[0]);
                    displayVal(waveform ? R.id.waveformMax : R.id.fftMax, minMaxCenter[1]);
                    displayVal(waveform ? R.id.waveformCenter : R.id.fftCenter, minMaxCenter[2]);
                    break;
                }
        }
    }

    private View.OnKeyListener mSessionKeyListener = new View.OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        try {
                            sSession = Integer.parseInt(mSessionText.getText().toString());
                            getEffect(sSession);
                        } catch (NumberFormatException e) {
                            Log.d(TAG, "Invalid session #: "+mSessionText.getText().toString());
                        }

                        return true;
                }
            }
            return false;
        }
    };

    // OnCheckedChangeListener
    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.getId() == R.id.visuMultithreadedOnOff) {
            mUseMTInstance = isChecked;
            Log.d(TAG, "Multi-threaded client: " + (isChecked ? "enabled" : "disabled"));
        }
        if (buttonView.getId() == R.id.visualizerOnOff) {
            if (mVisualizer != null) {
                mEnabled = isChecked;
                mCallbackButton.setEnabled(!mEnabled);
                if (mCallbackOn && mEnabled) {
                    mVisualizer.enableDataCaptureListener(true);
                }
                mVisualizer.setEnabled(mEnabled);
                if (mCallbackOn) {
                    if (!mEnabled) {
                        mVisualizer.enableDataCaptureListener(false);
                    }
                } else {
                    mVisualizer.startStopCapture(isChecked);
                }
            }
        }
        if (buttonView.getId() == R.id.visuReleaseButton) {
            if (isChecked) {
                if (mVisualizer == null) {
                    getEffect(sSession);
                }
            } else {
                if (mVisualizer != null) {
                    putEffect(sSession);
                }
            }
        }
        if (buttonView.getId() == R.id.visuCallbackOnOff) {
            mCallbackOn = isChecked;
        }
    }

    private void displayVal(int viewId, int val) {
        TextView textView = (TextView)findViewById(viewId);
        String text = Integer.toString(val);
        textView.setText(text);
    }


    private VisualizerInstance getEffect(int session) {
        synchronized (sInstances) {
            if (sInstances.containsKey(session)) {
                mVisualizer = sInstances.get(session);
            } else {
                try {
                    mVisualizer = mUseMTInstance
                            ? new VisualizerInstanceMT(session, mUiHandler, 0 /*extraThreadCount*/)
                            : new VisualizerInstanceSync(session, mUiHandler);
                } catch (RuntimeException e) {
                    throw e;
                }
                sInstances.put(session, mVisualizer);
            }
        }
        mReleaseButton.setEnabled(false);
        mOnOffButton.setEnabled(false);
        if (mVisualizer != null) {
            mReleaseButton.setChecked(true);
            mReleaseButton.setEnabled(true);

            mEnabled = mVisualizer.getEnabled();
            mOnOffButton.setChecked(mEnabled);
            mOnOffButton.setEnabled(true);

            mCallbackButton.setEnabled(!mEnabled);
        }
        return mVisualizer;
    }

    private void putEffect(int session) {
        mOnOffButton.setChecked(false);
        mOnOffButton.setEnabled(false);
        synchronized (sInstances) {
            if (mVisualizer != null) {
                mVisualizer.release();
                sInstances.remove(session);
                mVisualizer = null;
            }
        }
    }

    // Stress-tests releasing of AudioEffect by doing repeated creation
    // and subsequent releasing. Unlike a similar class in BassBoostTest,
    // this one doesn't sets a control status listener because Visualizer
    // doesn't inherit from AudioEffect and doesn't implement this method
    // by itself.
    class HammerReleaseTest extends Thread {
        private static final int NUM_EFFECTS = 10;
        private static final int NUM_ITERATIONS = 100;
        private final int mSession;
        private final Runnable mOnComplete;

        HammerReleaseTest(int session, Runnable onComplete) {
            mSession = session;
            mOnComplete = onComplete;
        }

        @Override
        public void run() {
            Log.w(TAG, "HammerReleaseTest started");
            Visualizer[] effects = new Visualizer[NUM_EFFECTS];
            for (int i = 0; i < NUM_ITERATIONS; i++) {
                for (int j = 0; j < NUM_EFFECTS; j++) {
                    effects[j] = new Visualizer(mSession);
                    yield();
                }
                for (int j = NUM_EFFECTS - 1; j >= 0; j--) {
                    Log.w(TAG, "HammerReleaseTest releasing effect " + (Object) effects[j]);
                    effects[j].release();
                    effects[j] = null;
                    yield();
                }
            }
            Log.w(TAG, "HammerReleaseTest ended");
            runOnUiThread(mOnComplete);
        }
    }

    private void runHammerReleaseTest(Button controlButton) {
        controlButton.setEnabled(false);
        HammerReleaseTest thread = new HammerReleaseTest(sSession,
                () -> {
                    controlButton.setEnabled(true);
                });
        thread.start();
    }

}
