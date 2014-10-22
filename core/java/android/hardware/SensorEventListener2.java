/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * Used for receiving a notification when a flush() has been successfully completed.
 */
public interface SensorEventListener2 extends SensorEventListener {
    /**
     * Called after flush() is completed. All the events in the batch at the point when
     * the flush was called have been delivered to the applications registered for those
     * sensor events. Flush Complete Events are sent ONLY to the application that has
     * explicitly called flush(). If the hardware FIFO is flushed due to some other
     * application calling flush(), flush complete event is not delivered to this application.
     * <p>
     *
     * @param sensor The {@link android.hardware.Sensor Sensor} on which flush was called.
     *
     * @see android.hardware.SensorManager#flush(SensorEventListener)
     */
    public void onFlushCompleted(Sensor sensor);
}
