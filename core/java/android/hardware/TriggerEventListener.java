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

package android.hardware;

/**
 * This class is the listener used to handle Trigger Sensors.
 * Trigger Sensors are sensors that trigger an event and are automatically
 * disabled. {@link Sensor#TYPE_SIGNIFICANT_MOTION} is one such example.
 * <p>
 * {@link SensorManager} lets you access the device's {@link android.hardware.Sensor
 * sensors}. Get an instance of {@link SensorManager} by calling
 * {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with the argument
 * {@link android.content.Context#SENSOR_SERVICE}.
 * <p>Here's an example setup for a TriggerEventListener:
 *
 * <pre>
 * class TriggerListener extends TriggerEventListener {
 *     public void onTrigger(TriggerEvent event) {
 *          // Do Work.
 *
 *     // As it is a one shot sensor, it will be canceled automatically.
 *     // SensorManager.requestTriggerSensor(this, mSigMotion); needs to
 *     // be called again, if needed.
 *     }
 * }
 * public class SensorActivity extends Activity {
 *     private final SensorManager mSensorManager;
 *     private final Sensor mSigMotion;
 *     private final TriggerEventListener mListener = new TriggerEventListener();
 *
 *     public SensorActivity() {
 *         mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
 *         mSigMotion = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
 *     }
 *
 *     protected void onResume() {
 *         super.onResume();
 *         mSensorManager.requestTriggerSensor(mListener, mSigMotion);
 *     }
 *
 *     protected void onPause() {
 *         super.onPause();
 *         // Call disable to ensure that the trigger request has been canceled.
 *         mSensorManager.cancelTriggerSensor(mListener, mSigMotion);
 *     }
 *
 * }
 * </pre>
 *
 * @see TriggerEvent
 * @see Sensor
 */
public abstract class TriggerEventListener {
    /**
     * The method that will be called when the sensor
     * is triggered. Override this method in your implementation
     * of this class.
     *
     * @param event The details of the event.
     */
    public abstract void onTrigger(TriggerEvent event);
}
