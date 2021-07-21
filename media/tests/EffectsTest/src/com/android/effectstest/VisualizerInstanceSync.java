/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

// This class only has `final' members, thus any thread-safety concerns
// can only come from the Visualizer effect class.
class VisualizerInstanceSync implements VisualizerInstance {

    private static final String TAG = "VisualizerInstance";

    private final Handler mUiHandler;
    private final Visualizer mVisualizer;
    private final VisualizerTestHandler mVisualizerTestHandler;
    private final VisualizerListener mVisualizerListener;

    VisualizerInstanceSync(int session, Handler uiHandler) {
        mUiHandler = uiHandler;
        try {
            mVisualizer = new Visualizer(session);
        } catch (UnsupportedOperationException e) {
            Log.e(TAG, "Visualizer library not loaded");
            throw new RuntimeException("Cannot initialize effect");
        } catch (RuntimeException e) {
            throw e;
        }
        mVisualizerTestHandler = new VisualizerTestHandler();
        mVisualizerListener = new VisualizerListener();
    }

    // Not a "deep" copy, only copies the references.
    VisualizerInstanceSync(VisualizerInstanceSync other) {
        mUiHandler = other.mUiHandler;
        mVisualizer = other.mVisualizer;
        mVisualizerTestHandler = other.mVisualizerTestHandler;
        mVisualizerListener = other.mVisualizerListener;
    }

    @Override
    public void enableDataCaptureListener(boolean enable) {
        mVisualizer.setDataCaptureListener(enable ? mVisualizerListener : null,
                10000, enable, enable);
    }

    @Override
    public boolean getEnabled() {
        return mVisualizer.getEnabled();
    }

    @Override
    public void release() {
        mVisualizer.release();
        Log.d(TAG, "Visualizer released");
    }

    @Override
    public void setEnabled(boolean enabled) {
        mVisualizer.setEnabled(enabled);
    }

    @Override
    public void startStopCapture(boolean start) {
        mVisualizerTestHandler.sendMessage(mVisualizerTestHandler.obtainMessage(
                        start ? MSG_START_CAPTURE : MSG_STOP_CAPTURE));
    }

    private static final int MSG_START_CAPTURE = 0;
    private static final int MSG_STOP_CAPTURE = 1;
    private static final int MSG_NEW_CAPTURE = 2;
    private static final int CAPTURE_PERIOD_MS = 100;

    private static int[] dataToMinMaxCenter(byte[] data, int len) {
        int[] minMaxCenter = new int[3];
        minMaxCenter[0] = data[0];
        minMaxCenter[1] = data[len - 1];
        minMaxCenter[2] = data[len / 2];
        return minMaxCenter;
    }

    private class VisualizerTestHandler extends Handler {
        private final int mCaptureSize;
        private boolean mActive = false;

        VisualizerTestHandler() {
            mCaptureSize = mVisualizer.getCaptureSize();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_CAPTURE:
                    if (!mActive) {
                        Log.d(TAG, "Start capture");
                        mActive = true;
                        sendMessageDelayed(obtainMessage(MSG_NEW_CAPTURE), CAPTURE_PERIOD_MS);
                    }
                    break;
                case MSG_STOP_CAPTURE:
                    if (mActive) {
                        Log.d(TAG, "Stop capture");
                        mActive = false;
                    }
                    break;
                case MSG_NEW_CAPTURE:
                    if (mActive) {
                        if (mCaptureSize > 0) {
                            byte[] data = new byte[mCaptureSize];
                            if (mVisualizer.getWaveForm(data) == Visualizer.SUCCESS) {
                                int len = data.length < mCaptureSize ? data.length : mCaptureSize;
                                mUiHandler.sendMessage(
                                        mUiHandler.obtainMessage(
                                                VisualizerTest.MSG_DISPLAY_WAVEFORM_VAL,
                                                dataToMinMaxCenter(data, len)));
                            }
                            if (mVisualizer.getFft(data) == Visualizer.SUCCESS) {
                                int len = data.length < mCaptureSize ? data.length : mCaptureSize;
                                mUiHandler.sendMessage(
                                        mUiHandler.obtainMessage(VisualizerTest.MSG_DISPLAY_FFT_VAL,
                                                dataToMinMaxCenter(data, len)));
                            }
                        }
                        sendMessageDelayed(obtainMessage(MSG_NEW_CAPTURE), CAPTURE_PERIOD_MS);
                    }
                    break;
            }
        }
    }

    private class VisualizerListener implements Visualizer.OnDataCaptureListener {
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform,
                int samplingRate) {
            if (visualizer == mVisualizer && waveform.length > 0) {
                Log.d(TAG, "onWaveFormDataCapture(): " + waveform[0]
                        + " smp rate: " + samplingRate / 1000);
                mUiHandler.sendMessage(
                        mUiHandler.obtainMessage(VisualizerTest.MSG_DISPLAY_WAVEFORM_VAL,
                                dataToMinMaxCenter(waveform, waveform.length)));
            }
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
            if (visualizer == mVisualizer && fft.length > 0) {
                Log.d(TAG, "onFftDataCapture(): " + fft[0]);
                mUiHandler.sendMessage(
                        mUiHandler.obtainMessage(VisualizerTest.MSG_DISPLAY_FFT_VAL,
                                dataToMinMaxCenter(fft, fft.length)));
            }
        }
    }
}
