/**
 * Copyright (C) 2017 The Android Open Source Project
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

package android.hardware.radio;

import android.graphics.Bitmap;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;

/** {@hide} */
interface ITuner {
    void close();

    boolean isClosed();

    /**
     * @throws IllegalArgumentException if config is not valid or null
     */
    void setConfiguration(in RadioManager.BandConfig config);

    RadioManager.BandConfig getConfiguration();

    /**
     * @throws IllegalStateException if tuner was opened without audio
     */
    void setMuted(boolean mute);

    boolean isMuted();

    /**
     * @throws IllegalStateException if called out of sequence
     */
    void step(boolean directionDown, boolean skipSubChannel);

    /**
     * @throws IllegalStateException if called out of sequence
     */
    void scan(boolean directionDown, boolean skipSubChannel);

    /**
     * @throws IllegalArgumentException if invalid arguments are passed
     * @throws IllegalStateException if called out of sequence
     */
    void tune(in ProgramSelector selector);

    /**
     * @throws IllegalStateException if called out of sequence
     */
    void cancel();

    void cancelAnnouncement();

    Bitmap getImage(int id);

    /**
     * @return {@code true} if the scan was properly scheduled,
     *          {@code false} if the scan feature is unavailable
     */
    boolean startBackgroundScan();

    void startProgramListUpdates(in ProgramList.Filter filter);
    void stopProgramListUpdates();

    boolean isConfigFlagSupported(int flag);
    boolean isConfigFlagSet(int flag);
    void setConfigFlag(int flag, boolean value);

    /**
     * @param parameters Vendor-specific key-value pairs
     * @return Vendor-specific key-value pairs
     */
    Map<String, String> setParameters(in Map<String, String> parameters);

    /**
     * @param keys Parameter keys to fetch
     * @return Vendor-specific key-value pairs
     */
    Map<String, String> getParameters(in List<String> keys);
}
