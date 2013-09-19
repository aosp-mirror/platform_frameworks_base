/*
 * Copyright (C) 2013 The Android Open Source Project
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

// Make values from a motion sensor (e.g., accelerometer) available as filter outputs.

package androidx.media.filterfw.samples.simplecamera;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.FrameValues;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

public final class MotionSensorWTime extends Filter implements SensorEventListener {

    private SensorManager mSensorManager = null;
    private Sensor mSensor = null;

    private float[] mValues = new float[3];
    private float[][] mTemp = new float[3][3];
    private float[] mAvgValues = new float[3];
    private int mCounter = 0;

    public MotionSensorWTime(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addOutputPort("values", Signature.PORT_REQUIRED, FrameType.array(float.class))
            .addOutputPort("timestamp", Signature.PORT_OPTIONAL, FrameType.single(long.class))
            .disallowOtherPorts();
    }

    @Override
    protected void onPrepare() {
        mSensorManager = (SensorManager)getContext().getApplicationContext()
                            .getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        // TODO: currently, the type of sensor is hardcoded. Should be able to set the sensor
        //  type as filter input!
        mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onTearDown() {
        mSensorManager.unregisterListener(this);
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // (Do we need to do something when sensor accuracy changes?)
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        synchronized(mValues) {
            mValues[0] = event.values[0];
            mValues[1] = event.values[1];
            mValues[2] = event.values[2];
        }
    }

    @Override
    protected void onProcess() {
        OutputPort outPort = getConnectedOutputPort("values");
        FrameValues outFrame = outPort.fetchAvailableFrame(null).asFrameValues();
        synchronized(mValues) {
            if (mCounter < 3 && mCounter >= 0) {
                mTemp[0][mCounter] = mValues[0];
                mTemp[1][mCounter] = mValues[1];
                mTemp[2][mCounter] = mValues[2];
            }

            mCounter = (mCounter + 1) % 3;

            mAvgValues[0] = (mTemp[0][0] + mTemp[0][1] + mTemp[0][2]) / 3;
            mAvgValues[1] = (mTemp[1][0] + mTemp[1][1] + mTemp[1][2]) / 3;
            mAvgValues[2] = (mTemp[2][0] + mTemp[2][1] + mTemp[2][2]) / 3;
            outFrame.setValues(mAvgValues);
        }
        outFrame.setTimestamp(System.currentTimeMillis() * 1000000L);
        outPort.pushFrame(outFrame);

        OutputPort timeOutPort = getConnectedOutputPort("timestamp");
        if (timeOutPort != null) {
            long timestamp = System.nanoTime();
            Log.v("MotionSensor", "Timestamp is: " + timestamp);
            FrameValue timeStampFrame = timeOutPort.fetchAvailableFrame(null).asFrameValue();
            timeStampFrame.setValue(timestamp);
            timeOutPort.pushFrame(timeStampFrame);
        }
    }
}

