/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os.vibrator;

import java.util.Objects;

/**
 * A {@link PwlePoint} represents a single point in an envelope vibration effect. Defined by its
 * amplitude, frequency and time to transition to this point from the previous one in the envelope.
 *
 * @hide
 */
public final class PwlePoint {
    private final float mAmplitude;
    private final float mFrequencyHz;
    private final int mTimeMillis;

    /** @hide */
    public PwlePoint(float amplitude, float frequencyHz, int timeMillis) {
        mAmplitude = amplitude;
        mFrequencyHz = frequencyHz;
        mTimeMillis = timeMillis;
    }

    public float getAmplitude() {
        return mAmplitude;
    }

    public float getFrequencyHz() {
        return mFrequencyHz;
    }

    public int getTimeMillis() {
        return mTimeMillis;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PwlePoint)) {
            return false;
        }
        PwlePoint other = (PwlePoint) obj;
        return Float.compare(mAmplitude, other.mAmplitude) == 0
                && Float.compare(mFrequencyHz, other.mFrequencyHz) == 0
                && mTimeMillis == other.mTimeMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAmplitude, mFrequencyHz, mTimeMillis);
    }

    @Override
    public String toString() {
        return "PwlePoint{amplitude=" + mAmplitude
                + ", frequency=" + mFrequencyHz
                + ", time=" + mTimeMillis
                + "}";
    }
}
