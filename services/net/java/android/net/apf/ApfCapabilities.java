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

package android.net.apf;

/**
 * APF program support capabilities.
 *
 * @hide
 */
public class ApfCapabilities {
    /**
     * Version of APF instruction set supported for packet filtering. 0 indicates no support for
     * packet filtering using APF programs.
     */
    public final int apfVersionSupported;

    /**
     * Maximum size of APF program allowed.
     */
    public final int maximumApfProgramSize;

    /**
     * Format of packets passed to APF filter. Should be one of ARPHRD_*
     */
    public final int apfPacketFormat;

    public ApfCapabilities(int apfVersionSupported, int maximumApfProgramSize, int apfPacketFormat)
    {
        this.apfVersionSupported = apfVersionSupported;
        this.maximumApfProgramSize = maximumApfProgramSize;
        this.apfPacketFormat = apfPacketFormat;
    }

    public String toString() {
        return String.format("%s{version: %d, maxSize: %d format: %d}", getClass().getSimpleName(),
                apfVersionSupported, maximumApfProgramSize, apfPacketFormat);
    }
}
