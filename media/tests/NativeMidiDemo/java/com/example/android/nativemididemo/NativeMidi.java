/*
 * Copyright (C) 2016 The Android Open Source Project
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
 *
 */

package com.example.android.nativemididemo;

import android.app.Activity;
import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.IOException;

public class NativeMidi extends Activity
{
    private TextView mCallbackStatusTextView;
    private TextView mJavaMidiStatusTextView;
    private TextView mMessagesTextView;
    private RadioGroup mMidiDevicesRadioGroup;
    private Handler mTimerHandler = new Handler();
    private boolean mAudioWorks;
    private final int mMinFramesPerBuffer = 32;   // See {min|max}PlaySamples in nativemidi-jni.cpp
    private final int mMaxFramesPerBuffer = 1000;
    private int mFramesPerBuffer;

    private TouchableScrollView mMessagesContainer;
    private MidiManager mMidiManager;
    private MidiOutputPortSelector mActivePortSelector;

    private Runnable mTimerRunnable = new Runnable() {
        private long mLastTime;
        private long mLastPlaybackCounter;
        private int mLastCallbackRate;
        private long mLastUntouchedTime;

        @Override
        public void run() {
            final long checkIntervalMs = 1000;
            long currentTime = System.currentTimeMillis();
            long currentPlaybackCounter = getPlaybackCounter();
            if (currentTime - mLastTime >= checkIntervalMs) {
                int callbackRate = Math.round(
                        (float)(currentPlaybackCounter - mLastPlaybackCounter) /
                        ((float)(currentTime - mLastTime) / (float)1000));
                if (mLastCallbackRate != callbackRate) {
                    mCallbackStatusTextView.setText(
                           "CB: " + callbackRate + " Hz");
                    mLastCallbackRate = callbackRate;
                }
                mLastTime = currentTime;
                mLastPlaybackCounter = currentPlaybackCounter;
            }

            String[] newMessages = getRecentMessages();
            if (newMessages != null) {
                for (String message : newMessages) {
                    mMessagesTextView.append(message);
                    mMessagesTextView.append("\n");
                }
                if (!mMessagesContainer.isTouched) {
                    if (mLastUntouchedTime == 0) mLastUntouchedTime = currentTime;
                    if (currentTime - mLastUntouchedTime > 3000) {
                        mMessagesContainer.fullScroll(View.FOCUS_DOWN);
                    }
                } else {
                    mLastUntouchedTime = 0;
                }
            }

            mTimerHandler.postDelayed(this, checkIntervalMs / 4);
        }
    };

    private void addMessage(final String message) {
        mTimerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mMessagesTextView.append(message);
            }
        }, 0);
    }

    private class MidiOutputPortSelector implements View.OnClickListener {
        private final MidiDeviceInfo mDeviceInfo;
        private final int mPortNumber;
        private MidiDevice mDevice;
        private MidiOutputPort mOutputPort;

        MidiOutputPortSelector() {
            mDeviceInfo = null;
            mPortNumber = -1;
        }

        MidiOutputPortSelector(MidiDeviceInfo info, int portNumber) {
            mDeviceInfo = info;
            mPortNumber = portNumber;
        }

        MidiDeviceInfo getDeviceInfo() { return mDeviceInfo; }

        @Override
        public void onClick(View v) {
            if (mActivePortSelector != null) {
                mActivePortSelector.close();
                mActivePortSelector = null;
            }
            if (mDeviceInfo == null) {
                mActivePortSelector = this;
                return;
            }
            mMidiManager.openDevice(mDeviceInfo, new MidiManager.OnDeviceOpenedListener() {
                @Override
                public void onDeviceOpened(MidiDevice device) {
                    if (device == null) {
                        addMessage("! Failed to open MIDI device !\n");
                    } else {
                        mDevice = device;
                        try {
                            mDevice.mirrorToNative();
                            startReadingMidi(mDevice.getInfo().getId(), mPortNumber);
                        } catch (IOException e) {
                            addMessage("! Failed to mirror to native !\n" + e.getMessage() + "\n");
                        }

                        mActivePortSelector = MidiOutputPortSelector.this;

                        mOutputPort = device.openOutputPort(mPortNumber);
                        mOutputPort.connect(mMidiReceiver);
                    }
                }
            }, null);
        }

        void closePortOnly() {
            stopReadingMidi();
        }

        void close() {
            closePortOnly();
            try {
                if (mOutputPort != null) {
                    mOutputPort.close();
                }
            } catch (IOException e) {
                mMessagesTextView.append("! Port close error: " + e + "\n");
            } finally {
                mOutputPort = null;
            }
            try {
                if (mDevice != null) {
                    mDevice.close();
                }
            } catch (IOException e) {
                mMessagesTextView.append("! Device close error: " + e + "\n");
            } finally {
                mDevice = null;
            }
        }
    }

    private MidiManager.DeviceCallback mMidiDeviceCallback = new MidiManager.DeviceCallback() {
        @Override
        public void onDeviceAdded(MidiDeviceInfo info) {
            Bundle deviceProps = info.getProperties();
            String deviceName = deviceProps.getString(MidiDeviceInfo.PROPERTY_NAME);
            if (deviceName == null) {
                deviceName = deviceProps.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER);
            }

            for (MidiDeviceInfo.PortInfo port : info.getPorts()) {
                if (port.getType() != MidiDeviceInfo.PortInfo.TYPE_OUTPUT) continue;
                String portName = port.getName();
                int portNumber = port.getPortNumber();
                if (portName.length() == 0) portName = "[" + portNumber + "]";
                portName += "@" + deviceName;
                RadioButton outputDevice = new RadioButton(NativeMidi.this);
                outputDevice.setText(portName);
                outputDevice.setTag(info);
                outputDevice.setOnClickListener(new MidiOutputPortSelector(info, portNumber));
                mMidiDevicesRadioGroup.addView(outputDevice);
            }

            NativeMidi.this.updateKeepScreenOn();
        }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo info) {
            if (mActivePortSelector != null && info.equals(mActivePortSelector.getDeviceInfo())) {
                mActivePortSelector.close();
                mActivePortSelector = null;
            }
            int removeButtonStart = -1, removeButtonCount = 0;
            final int buttonCount = mMidiDevicesRadioGroup.getChildCount();
            boolean checked = false;
            for (int i = 0; i < buttonCount; ++i) {
                RadioButton button = (RadioButton) mMidiDevicesRadioGroup.getChildAt(i);
                if (!info.equals(button.getTag())) continue;
                if (removeButtonStart == -1) removeButtonStart = i;
                ++removeButtonCount;
                if (button.isChecked()) checked = true;
            }
            if (removeButtonStart != -1) {
                mMidiDevicesRadioGroup.removeViews(removeButtonStart, removeButtonCount);
                if (checked) {
                    mMidiDevicesRadioGroup.check(R.id.device_none);
                }
            }

            NativeMidi.this.updateKeepScreenOn();
        }
    };

    private class JavaMidiReceiver extends MidiReceiver implements Runnable {
        @Override
        public void onSend(byte[] data, int offset,
                int count, long timestamp) throws IOException {
            mTimerHandler.removeCallbacks(this);
            mTimerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mJavaMidiStatusTextView.setText("Java: MSG");
                }
            }, 0);
            mTimerHandler.postDelayed(this, 100);
        }

        @Override
        public void run() {
            mJavaMidiStatusTextView.setText("Java: ---");
        }
    }

    private JavaMidiReceiver mMidiReceiver = new JavaMidiReceiver();

    private void updateKeepScreenOn() {
        if (mMidiDevicesRadioGroup.getChildCount() > 1) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mCallbackStatusTextView = findViewById(R.id.callback_status);
        mJavaMidiStatusTextView = findViewById(R.id.java_midi_status);
        mMessagesTextView = findViewById(R.id.messages);
        mMessagesContainer = findViewById(R.id.messages_scroll);
        mMidiDevicesRadioGroup = findViewById(R.id.devices);
        RadioButton deviceNone = findViewById(R.id.device_none);
        deviceNone.setOnClickListener(new MidiOutputPortSelector());

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        String sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        if (sampleRate == null) sampleRate = "48000";
        String framesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        if (framesPerBuffer == null) framesPerBuffer = Integer.toString(mMaxFramesPerBuffer);
        mFramesPerBuffer = Integer.parseInt(framesPerBuffer);
        String audioInitResult = initAudio(Integer.parseInt(sampleRate), mFramesPerBuffer);
        mMessagesTextView.append("Open SL ES init: " + audioInitResult + "\n");

        if (audioInitResult.startsWith("Success")) {
            mAudioWorks = true;
            mTimerHandler.postDelayed(mTimerRunnable, 0);
            mTimerHandler.postDelayed(mMidiReceiver, 0);
        }

        mMidiManager = (MidiManager) getSystemService(Context.MIDI_SERVICE);
        mMidiManager.registerDeviceCallback(mMidiDeviceCallback, new Handler());
        for (MidiDeviceInfo info : mMidiManager.getDevices()) {
            mMidiDeviceCallback.onDeviceAdded(info);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mAudioWorks) {
            mTimerHandler.removeCallbacks(mTimerRunnable);
            pauseAudio();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAudioWorks) {
            mTimerHandler.postDelayed(mTimerRunnable, 0);
            resumeAudio();
        }
    }

    @Override
    protected void onDestroy() {
        if (mActivePortSelector != null) {
            mActivePortSelector.close();
            mActivePortSelector = null;
        }
        shutdownAudio();
        super.onDestroy();
    }

    public void onClearMessages(View v) {
        mMessagesTextView.setText("");
    }

    public void onClosePort(View v) {
        if (mActivePortSelector != null) {
            mActivePortSelector.closePortOnly();
        }
    }

    private native String initAudio(int sampleRate, int playSamples);
    private native void pauseAudio();
    private native void resumeAudio();
    private native void shutdownAudio();

    private native long getPlaybackCounter();
    private native String[] getRecentMessages();

    private native void startReadingMidi(int deviceId, int portNumber);
    private native void stopReadingMidi();

    static {
        System.loadLibrary("nativemidi_jni");
    }
}
