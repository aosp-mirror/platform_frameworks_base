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
 * limitations under the License
 */

package com.android.keyguard.analytics;

import com.google.protobuf.nano.CodedOutputByteBufferNano;
import com.google.protobuf.nano.MessageNano;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.util.Log;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Tracks sessions, touch and sensor events in Keyguard.
 *
 * A session starts when the user is presented with the Keyguard and ends when the Keyguard is no
 * longer visible to the user.
 */
public class KeyguardAnalytics implements SensorEventListener {

    private static final boolean DEBUG = false;
    private static final String TAG = "KeyguardAnalytics";
    private static final long TIMEOUT_MILLIS = 11000; // 11 seconds.

    private static final String ANALYTICS_FILE = "/sdcard/keyguard_analytics.bin";

    private static final int[] SENSORS = new int[] {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_ROTATION_VECTOR,
    };

    private Session mCurrentSession = null;
    // Err on the side of caution, so logging is not started after a crash even tough the screen
    // is off.
    private boolean mScreenOn = false;
    private boolean mHidden = false;

    private final SensorManager mSensorManager;
    private final SessionTypeAdapter mSessionTypeAdapter;
    private final File mAnalyticsFile;

    public KeyguardAnalytics(Context context, SessionTypeAdapter sessionTypeAdapter,
            File analyticsFile) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mSessionTypeAdapter = sessionTypeAdapter;
        mAnalyticsFile = analyticsFile;
    }

    public Callback getCallback() {
        return mCallback;
    }

    public interface Callback {
        public void onShow();
        public void onHide();
        public void onScreenOn();
        public void onScreenOff();
        public boolean onTouchEvent(MotionEvent ev, int width, int height);
        public void onSetHidden(boolean hidden);
    }

    public interface SessionTypeAdapter {
        public int getSessionType();
    }

    private void sessionEntrypoint() {
        if (mCurrentSession == null && mScreenOn && !mHidden) {
            onSessionStart();
        }
    }

    private void sessionExitpoint(int result) {
        if (mCurrentSession != null) {
            onSessionEnd(result);
        }
    }

    private void onSessionStart() {
        int type = mSessionTypeAdapter.getSessionType();
        mCurrentSession = new Session(System.currentTimeMillis(), System.nanoTime(), type);
        if (type == Session.TYPE_KEYGUARD_SECURE) {
            mCurrentSession.setRedactTouchEvents();
        }
        for (int sensorType : SENSORS) {
            Sensor s = mSensorManager.getDefaultSensor(sensorType);
            if (s != null) {
                mSensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            }
        }
        if (DEBUG) {
            Log.d(TAG, "onSessionStart()");
        }
    }

    private void onSessionEnd(int result) {
        if (DEBUG) {
            Log.d(TAG, String.format("onSessionEnd(success=%d)", result));
        }
        mSensorManager.unregisterListener(this);

        Session session = mCurrentSession;
        mCurrentSession = null;

        session.end(System.currentTimeMillis(), result);
        queueSession(session);
    }

    private void queueSession(final Session currentSession) {
        if (DEBUG) {
            Log.i(TAG, "Saving session.");
        }
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    byte[] b = writeDelimitedProto(currentSession.toProto());
                    OutputStream os = new FileOutputStream(mAnalyticsFile, true /* append */);
                    if (DEBUG) {
                        Log.d(TAG, String.format("Serialized size: %d kB.", b.length / 1024));
                    }
                    try {
                        os.write(b);
                        os.flush();
                    } finally {
                        try {
                            os.close();
                        } catch (IOException e) {
                            Log.e(TAG, "Exception while closing file", e);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Exception while writing file", e);
                }
                return null;
            }

            private byte[] writeDelimitedProto(MessageNano proto)
                    throws IOException {
                byte[] result = new byte[CodedOutputByteBufferNano.computeMessageSizeNoTag(proto)];
                CodedOutputByteBufferNano ob = CodedOutputByteBufferNano.newInstance(result);
                ob.writeMessageNoTag(proto);
                ob.checkNoSpaceLeft();
                return result;
            }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
    }

    @Override
    public synchronized void onSensorChanged(SensorEvent event) {
        if (false) {
            Log.v(TAG, String.format(
                    "onSensorChanged(name=%s, values[0]=%f)",
                    event.sensor.getName(), event.values[0]));
        }
        if (mCurrentSession != null) {
            mCurrentSession.addSensorEvent(event, System.nanoTime());
            enforceTimeout();
        }
    }

    private void enforceTimeout() {
        if (System.currentTimeMillis() - mCurrentSession.getStartTimestampMillis()
                > TIMEOUT_MILLIS) {
            onSessionEnd(Session.RESULT_UNKNOWN);
            if (DEBUG) {
                Log.i(TAG, "Analytics timed out.");
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private final Callback mCallback = new Callback() {
        @Override
        public void onShow() {
            if (DEBUG) {
                Log.d(TAG, "onShow()");
            }
            synchronized (KeyguardAnalytics.this) {
                sessionEntrypoint();
            }
        }

        @Override
        public void onHide() {
            if (DEBUG) {
                Log.d(TAG, "onHide()");
            }
            synchronized (KeyguardAnalytics.this) {
                sessionExitpoint(Session.RESULT_SUCCESS);
            }
        }

        @Override
        public void onScreenOn() {
            if (DEBUG) {
                Log.d(TAG, "onScreenOn()");
            }
            synchronized (KeyguardAnalytics.this) {
                mScreenOn = true;
                sessionEntrypoint();
            }
        }

        @Override
        public void onScreenOff() {
            if (DEBUG) {
                Log.d(TAG, "onScreenOff()");
            }
            synchronized (KeyguardAnalytics.this) {
                mScreenOn = false;
                sessionExitpoint(Session.RESULT_FAILURE);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev, int width, int height) {
            if (DEBUG) {
                Log.v(TAG, "onTouchEvent(ev.action="
                        + MotionEvent.actionToString(ev.getAction()) + ")");
            }
            synchronized (KeyguardAnalytics.this) {
                if (mCurrentSession != null) {
                    mCurrentSession.addMotionEvent(ev);
                    mCurrentSession.setTouchArea(width, height);
                    enforceTimeout();
                }
            }
            return true;
        }

        @Override
        public void onSetHidden(boolean hidden) {
            synchronized (KeyguardAnalytics.this) {
                if (hidden != mHidden) {
                    if (DEBUG) {
                        Log.d(TAG, "onSetHidden(" + hidden + ")");
                    }
                    mHidden = hidden;
                    if (hidden) {
                        // Could have gone to camera on purpose / by falsing or an app could have
                        // launched on top of the lockscreen.
                        sessionExitpoint(Session.RESULT_UNKNOWN);
                    } else {
                        sessionEntrypoint();
                    }
                }
            }
        }
    };

}
