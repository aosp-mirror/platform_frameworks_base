/*
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

package android.net.lowpan;


/** Provides detailed information about a given channel. */
//@SystemApi
public class LowpanChannelInfo {

    public static final int UNKNOWN_POWER = Integer.MAX_VALUE;

    //////////////////////////////////////////////////////////////////////////
    // Instance Variables

    private String mName = null;
    private int mIndex = 0;
    private boolean mIsMaskedByRegulatoryDomain = false;
    private float mSpectrumCenterFrequency = 0.0f;
    private float mSpectrumBandwidth = 0.0f;
    private int mMaxTransmitPower = UNKNOWN_POWER;

    //////////////////////////////////////////////////////////////////////////
    // Public Getters and Setters

    public String getName() {
        return mName;
    }

    public int getIndex() {
        return mIndex;
    }

    public int getMaxTransmitPower() {
        return mMaxTransmitPower;
    }

    public boolean isMaskedByRegulatoryDomain() {
        return mIsMaskedByRegulatoryDomain;
    }

    public float getSpectrumCenterFrequency() {
        return mSpectrumCenterFrequency;
    }

    public float getSpectrumBandwidth() {
        return mSpectrumBandwidth;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("Channel ").append(mIndex);

        if (mName != null) {
            sb.append(" (").append(mName).append(")");
        }

        if (mSpectrumCenterFrequency > 0.0f) {
            sb.append(", SpectrumCenterFrequency: ").append(mSpectrumCenterFrequency).append("Hz");
        }

        if (mSpectrumBandwidth > 0.0f) {
            sb.append(", SpectrumBandwidth: ").append(mSpectrumBandwidth).append("Hz");
        }

        if (mMaxTransmitPower != UNKNOWN_POWER) {
            sb.append(", MaxTransmitPower: ").append(mMaxTransmitPower);
        }

        return sb.toString();
    }
}
