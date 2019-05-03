/*
 * Copyright (C) 2016 The CyanogenMod Project
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
package com.android.server.custom.display;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.LinkedList;

public class AmbientLuxObserver {

    private static final String TAG = "AmbientLuxObserver";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Sensor mLightSensor;
    private final SensorManager mSensorManager;

    private final float mThresholdLux;
    private final float mHysteresisLux;
    private final int mThresholdDuration;

    private boolean mLightSensorEnabled = false;
    private int mLightSensorRate;

    private float mAmbientLux = 0.0f;

    private static final int LOW = 0;
    private static final int HIGH = 1;

    private int mState = LOW;

    private final AmbientLuxHandler mLuxHandler;

    private TransitionListener mCallback;

    private final TimedMovingAverageRingBuffer mRingBuffer;

    public interface TransitionListener {
        public void onTransition(int state, float ambientLux);
    }

    public AmbientLuxObserver(Context context, Looper looper,
            float thresholdLux, float hysteresisLux, int thresholdDuration) {
        mLuxHandler = new AmbientLuxHandler(looper);
        mThresholdLux = thresholdLux;
        mHysteresisLux = hysteresisLux;
        mThresholdDuration = thresholdDuration;
        mRingBuffer = new TimedMovingAverageRingBuffer(thresholdDuration);

        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mLightSensorRate = context.getResources().getInteger(
                com.android.internal.R.integer.config_autoBrightnessLightSensorRate);
    }

    private class AmbientLuxHandler extends Handler {

        private static final int MSG_UPDATE_LUX = 0;
        private static final int MSG_TRANSITION = 1;

        AmbientLuxHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            int direction = 0;
            float lux = 0.0f;

            synchronized (AmbientLuxObserver.this) {
                switch (msg.what) {
                    case MSG_UPDATE_LUX:
                        lux = (Float) msg.obj;
                        mRingBuffer.add(lux);

                        // FALL THRU

                    case MSG_TRANSITION:
                        mAmbientLux = mRingBuffer.getAverage();

                        if (DEBUG) {
                            Log.d(TAG, "lux= " + lux + " mState=" + mState +
                                       " mAmbientLux=" + mAmbientLux);
                        }

                        final float threshold = mState == HIGH
                                ? mThresholdLux - mHysteresisLux : mThresholdLux;
                        direction = mAmbientLux >= threshold ? HIGH : LOW;
                        if (mState != direction) {
                            mState = direction;
                            if (mCallback != null) {
                                mCallback.onTransition(mState, mAmbientLux);
                            }
                        }

                        // check again in case we didn't get any
                        // more readings because the sensor settled
                        if (mRingBuffer.size() > 1) {
                            removeMessages(MSG_TRANSITION);
                            sendEmptyMessageDelayed(MSG_TRANSITION, mThresholdDuration / 2);
                        }
                        break;
                }
            }
        }

        void clear() {
            removeCallbacksAndMessages(null);
        }
    };

    private final SensorEventListener mListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mLightSensorEnabled) {
                Message.obtain(mLuxHandler, AmbientLuxHandler.MSG_UPDATE_LUX,
                               event.values[0]).sendToTarget();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    public synchronized int getState() {
        return mState;
    }

    public synchronized void setTransitionListener(TransitionListener callback) {
        mCallback = callback;
        enableLightSensor(callback != null);
    }

    private void enableLightSensor(boolean enable) {
        if (enable && !mLightSensorEnabled) {
            mLightSensorEnabled = true;
            mSensorManager.registerListener(mListener, mLightSensor,
                    mLightSensorRate * 1000, mLuxHandler);
        } else if (!enable && mLightSensorEnabled) {
            mSensorManager.unregisterListener(mListener);
            mLuxHandler.clear();
            mAmbientLux = 0.0f;
            mState = LOW;
            mLightSensorEnabled = false;
            mRingBuffer.clear();
        }
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("  AmbientLuxObserver State:");
        pw.println("    mLightSensorEnabled=" + mLightSensorEnabled);
        pw.println("    mState=" + mState);
        pw.println("    mAmbientLux=" + mAmbientLux);
        pw.println("    mRingBuffer=" + mRingBuffer.toString());
    }

    /**
     * Calculates a simple moving average based on a fixed
     * duration sliding window. This is useful for dampening
     * erratic sensors and rolling thru transitional periods
     * smoothly.
     */
    private static class TimedMovingAverageRingBuffer {

        private final LinkedList<Sample> mRing = new LinkedList<Sample>();

        private final int mPeriod;

        private float mTotal = 0.0f;

        private static class Sample {
            public final long mTimestamp;
            public final float mValue;
            public Sample (long timestamp, float value) {
                mTimestamp = timestamp;
                mValue = value;
            }

            @Override
            public String toString() {
                return "(" + mValue + ", " + mTimestamp + ")";
            }
        }

        public TimedMovingAverageRingBuffer(int period) {
            mPeriod = period;
        }

        public synchronized void add(float sample) {
            expire();
            if (sample == 0.0f && mRing.size() == 0) {
                return;
            }
            mRing.offer(new Sample(System.currentTimeMillis(), sample));
            mTotal += sample;
        }

        public synchronized int size() {
            return mRing.size();
        }

        public synchronized float getAverage() {
            expire();
            return mRing.size() == 0 ? 0.0f : (mTotal / mRing.size());
        }

        public synchronized void clear() {
            mRing.clear();
            mTotal = 0.0f;
        }

        private void expire() {
            long now = System.currentTimeMillis();
            while (mRing.size() > 1 &&
                    ((now - mRing.peek().mTimestamp) > mPeriod)) {
                mTotal -= mRing.pop().mValue;
            }
        }

        @Override
        public synchronized String toString() {
            expire();
            StringBuilder sb = new StringBuilder();
            for (Iterator<Sample> i = mRing.iterator(); i.hasNext();) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(i.next());
            }
            return "average=" + getAverage() + " length=" + mRing.size() +
                   " mRing=[" + sb.toString() + "]";
        }
    }
}
