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

package android.location;

/**
 * Used for receiving notifications from the SensorManager when
 * sensor values have changed.
 *
 * @hide
 */
public interface GpsStatusListener {

    /**
     * Called when the GPS has started.
     */
    void onGpsStarted();

    /**
     * Called when the GPS has stopped.
     */
    void onGpsStopped();

    /**
     * Called when the GPS status has received its first fix since starting.
     *
     * @param ttff Time to first fix in milliseconds.
     */
    void onFirstFix(int ttff);

    /**
     * Called when the GPS SV status has changed.
     *
     * @param svCount The number of visible SVs
     * @param prns Array of SV prns.  Length of array is svCount.
     * @param snrs Array of signal to noise ratios for SVs, in 1/10 dB units.  Length of array is svCount.
     * @param elevations Array of SV elevations in degrees.  Length of array is svCount.
     * @param azimuths Array of SV azimuths in degrees.  Length of array is svCount.
     * @param ephemerisMask Bit mask indicating which SVs the GPS has ephemeris data for.
     * @param almanacMask Bit mask indicating which SVs the GPS has almanac data for.
     * @param usedInFixMask Bit mask indicating which SVs were used in the most recent GPS fix.
     */
    public void onSvStatusChanged(int svCount, int[] prns, float[] snrs, float[] elevations, 
            float[] azimuths, int ephemerisMask, int almanacMask, int usedInFixMask);
}
