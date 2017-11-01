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
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.SeekBar;

import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class VisualizerTest extends Activity implements OnCheckedChangeListener {

    private final static String TAG = "Visualizer Test";

    private Visualizer mVisualizer;
    ToggleButton mOnOffButton;
    ToggleButton mReleaseButton;
    boolean mEnabled;
    EditText mSessionText;
    static int sSession = 0;
    int mCaptureSize;
    ToggleButton mCallbackButton;
    boolean mCallbackOn;
    VisualizerListener mVisualizerListener;
    private static HashMap<Integer, Visualizer> sInstances = new HashMap<Integer, Visualizer>(10);
    private VisualizerTestHandler mVisualizerTestHandler = null;

    public VisualizerTest() {
        Log.d(TAG, "contructor");
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        TextView textView;

        setContentView(R.layout.visualizertest);

        mSessionText = findViewById(R.id.sessionEdit);
        mSessionText.setOnKeyListener(mSessionKeyListener);
        mSessionText.setText(Integer.toString(sSession));

        mReleaseButton = (ToggleButton)findViewById(R.id.visuReleaseButton);
        mOnOffButton = (ToggleButton)findViewById(R.id.visualizerOnOff);
        mCallbackButton = (ToggleButton)findViewById(R.id.visuCallbackOnOff);
        mCallbackOn = false;
        mCallbackButton.setChecked(mCallbackOn);

        mVisualizerTestHandler = new VisualizerTestHandler();
        mVisualizerListener = new VisualizerListener();

        getEffect(sSession);

        if (mVisualizer != null) {
            mReleaseButton.setOnCheckedChangeListener(this);
            mOnOffButton.setOnCheckedChangeListener(this);
            mCallbackButton.setOnCheckedChangeListener(this);
        }
    }

    private static final int MSG_START_CAPTURE = 0;
    private static final int MSG_STOP_CAPTURE = 1;
    private static final int MSG_NEW_CAPTURE = 2;
    private static final int CAPTURE_PERIOD_MS = 100;

    private class VisualizerTestHandler extends Handler {
        boolean mActive = false;
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_START_CAPTURE:
                if (!mActive) {
                    Log.d(TAG, "Start capture");
                    mActive = true;
                    sendMessageDelayed(obtainMessage(MSG_NEW_CAPTURE, 0, 0, null), CAPTURE_PERIOD_MS);
                }
                break;
            case MSG_STOP_CAPTURE:
                if (mActive) {
                    Log.d(TAG, "Stop capture");
                    mActive = false;
                }
                break;
            case MSG_NEW_CAPTURE:
                if (mActive && mVisualizer != null) {
                    if (mCaptureSize > 0) {
                        byte[] data = new byte[mCaptureSize];
                        if (mVisualizer.getWaveForm(data) == Visualizer.SUCCESS) {
                            int len = data.length < mCaptureSize ? data.length : mCaptureSize;
                            displayVal(R.id.waveformMin, data[0]);
                            displayVal(R.id.waveformMax, data[len-1]);
                            displayVal(R.id.waveformCenter, data[len/2]);
                        };
                        if (mVisualizer.getFft(data) == Visualizer.SUCCESS) {
                            int len = data.length < mCaptureSize ? data.length : mCaptureSize;
                            displayVal(R.id.fftMin, data[0]);
                            displayVal(R.id.fftMax, data[len-1]);
                            displayVal(R.id.fftCenter, data[len/2]);
                        };
                    }
                    sendMessageDelayed(obtainMessage(MSG_NEW_CAPTURE, 0, 0, null), CAPTURE_PERIOD_MS);
                }
                break;
            }
        }
    }

    private class VisualizerListener implements Visualizer.OnDataCaptureListener {

        public VisualizerListener() {
        }
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
            if (visualizer == mVisualizer) {
                if (waveform.length > 0) {
                    Log.d(TAG, "onWaveFormDataCapture(): "+waveform[0]+" smp rate: "+samplingRate/1000);
                    displayVal(R.id.waveformMin, waveform[0]);
                    displayVal(R.id.waveformMax, waveform[waveform.length - 1]);
                    displayVal(R.id.waveformCenter, waveform[waveform.length/2]);
                }
            }
        }
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
            if (visualizer == mVisualizer) {
                if (fft.length > 0) {
                    Log.d(TAG, "onFftDataCapture(): "+fft[0]);
                    displayVal(R.id.fftMin, fft[0]);
                    displayVal(R.id.fftMax, fft[fft.length - 1]);
                    displayVal(R.id.fftCenter, fft[fft.length/2]);
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    private View.OnKeyListener mSessionKeyListener
    = new View.OnKeyListener() {
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
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.getId() == R.id.visualizerOnOff) {
            if (mVisualizer != null) {
                mEnabled = isChecked;
                mCallbackButton.setEnabled(!mEnabled);
                if (mCallbackOn && mEnabled) {
                    mVisualizer.setDataCaptureListener(mVisualizerListener,
                            10000,
                            true,
                            true);
                }
                mVisualizer.setEnabled(mEnabled);
                if (mCallbackOn) {
                    if (!mEnabled) {
                        mVisualizer.setDataCaptureListener(null,
                                10000,
                                false,
                                false);
                    }
                } else {
                    int msg = isChecked ? MSG_START_CAPTURE : MSG_STOP_CAPTURE;
                    mVisualizerTestHandler.sendMessage(
                            mVisualizerTestHandler.obtainMessage(msg, 0, 0, null));
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


    private void getEffect(int session) {
        synchronized (sInstances) {
            if (sInstances.containsKey(session)) {
                mVisualizer = sInstances.get(session);
            } else {
                try{
                    mVisualizer = new Visualizer(session);
                } catch (UnsupportedOperationException e) {
                    Log.e(TAG,"Visualizer library not loaded");
                    throw (new RuntimeException("Cannot initialize effect"));
                } catch (RuntimeException e) {
                    throw e;
                }
                sInstances.put(session, mVisualizer);
            }
        }
        mReleaseButton.setEnabled(false);
        mOnOffButton.setEnabled(false);
        if (mVisualizer != null) {
            mCaptureSize = mVisualizer.getCaptureSize();

            mReleaseButton.setChecked(true);
            mReleaseButton.setEnabled(true);

            mEnabled = mVisualizer.getEnabled();
            mOnOffButton.setChecked(mEnabled);
            mOnOffButton.setEnabled(true);

            mCallbackButton.setEnabled(!mEnabled);
        }
    }

    private void putEffect(int session) {
        mOnOffButton.setChecked(false);
        mOnOffButton.setEnabled(false);
        synchronized (sInstances) {
            if (mVisualizer != null) {
                mVisualizer.release();
                Log.d(TAG,"Visualizer released");
                mVisualizer = null;
                sInstances.remove(session);
            }
        }
    }

}
