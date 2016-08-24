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
 */

package android.location;

/**
 * Used for receiving notifications when GNSS events happen.
 * @removed
 */
public abstract class GnssStatusCallback {
    /**
     * Called when GNSS system has started.
     */
    public void onStarted() {}

    /**
     * Called when GNSS system has stopped.
     */
    public void onStopped() {}

    /**
     * Called when the GNSS system has received its first fix since starting.
     * @param ttffMillis the time from start to first fix in milliseconds.
     */
    public void onFirstFix(int ttffMillis) {}

    /**
     * Called periodically to report GNSS satellite status.
     * @param status the current status of all satellites.
     */
    public void onSatelliteStatusChanged(GnssStatus status) {}
}
