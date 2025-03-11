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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.VibrationEffect;

import java.util.Objects;

/**
 * Provides information about the vibrator hardware capabilities and limitations regarding
 * waveform envelope effects. This includes:
 * <ul>
 * <li>Maximum number of control points supported.
 * <li>Minimum and maximum duration for individual segments.
 * <li>Maximum total duration for an envelope effect.
 * </ul>
 *
 * <p>This information can be used to help construct waveform envelope effects with
 * {@link VibrationEffect.WaveformEnvelopeBuilder}. When designing these effects, it is also
 * recommended to check the {@link VibratorFrequencyProfile} for information about the supported
 * frequency range and the vibrator's output response.
 *
 * @see VibrationEffect.WaveformEnvelopeBuilder
 * @see VibratorFrequencyProfile
 */
@FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
public final class VibratorEnvelopeEffectInfo implements Parcelable {
    private final int mMaxSize;
    private final long mMinControlPointDurationMillis;
    private final long mMaxControlPointDurationMillis;

    VibratorEnvelopeEffectInfo(Parcel in) {
        mMaxSize = in.readInt();
        mMinControlPointDurationMillis = in.readLong();
        mMaxControlPointDurationMillis = in.readLong();
    }

    /**
     * Default constructor.
     *
     * @param maxSize                       The maximum number of control points supported for an
     *                                      envelope effect.
     * @param minControlPointDurationMillis The minimum duration supported between two control
     *                                      points within an envelope effect.
     * @param maxControlPointDurationMillis The maximum duration supported between two control
     *                                      points within an envelope effect.
     * @hide
     */
    public VibratorEnvelopeEffectInfo(int maxSize,
            long minControlPointDurationMillis,
            long maxControlPointDurationMillis) {
        mMaxSize = maxSize;
        mMinControlPointDurationMillis = minControlPointDurationMillis;
        mMaxControlPointDurationMillis = maxControlPointDurationMillis;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMaxSize);
        dest.writeLong(mMinControlPointDurationMillis);
        dest.writeLong(mMaxControlPointDurationMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VibratorEnvelopeEffectInfo)) {
            return false;
        }
        VibratorEnvelopeEffectInfo other = (VibratorEnvelopeEffectInfo) o;
        return mMaxSize == other.mMaxSize
                && mMinControlPointDurationMillis == other.mMinControlPointDurationMillis
                && mMaxControlPointDurationMillis == other.mMaxControlPointDurationMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMaxSize,
                mMinControlPointDurationMillis,
                mMaxControlPointDurationMillis);
    }

    @Override
    public String toString() {
        return "VibratorEnvelopeEffectInfo{"
                + ", mMaxSize=" + mMaxSize
                + ", mMinControlPointDurationMillis=" + mMinControlPointDurationMillis
                + ", mMaxControlPointDurationMillis=" + mMaxControlPointDurationMillis
                + '}';
    }

    @NonNull
    public static final Creator<VibratorEnvelopeEffectInfo> CREATOR =
            new Creator<VibratorEnvelopeEffectInfo>() {
                @Override
                public VibratorEnvelopeEffectInfo createFromParcel(Parcel in) {
                    return new VibratorEnvelopeEffectInfo(in);
                }

                @Override
                public VibratorEnvelopeEffectInfo[] newArray(int size) {
                    return new VibratorEnvelopeEffectInfo[size];
                }
            };

    /**
     * Retrieves the maximum duration supported for an envelope effect, in milliseconds.
     *
     * <p>If the device supports envelope effects
     * (check {@link android.os.VibratorInfo#areEnvelopeEffectsSupported}), this value will be
     * positive. Devices with envelope effects capabilities guarantees a maximum duration
     * equivalent to the product of {@link #getMaxSize()} and
     * {@link #getMaxControlPointDurationMillis()}. If the device does not support
     * envelope effects, this method will return 0.
     *
     * @return The maximum duration (in milliseconds) allowed for an envelope effect, or 0 if
     * envelope effects are not supported.
     */
    public long getMaxDurationMillis() {
        return mMaxSize * mMaxControlPointDurationMillis;
    }

    /**
     * Retrieves the maximum number of control points supported for an envelope effect.
     *
     * <p>If the device supports envelope effects
     * (check {@link android.os.VibratorInfo#areEnvelopeEffectsSupported}), this value will be
     * positive. Devices with envelope effects capabilities guarantee support for a minimum of
     * 16 control points. If the device does not support envelope effects, this method will
     * return 0.
     *
     * @return the maximum number of control points allowed for an envelope effect, or 0 if
     * envelope effects are not supported.
     */
    public int getMaxSize() {
        return mMaxSize;
    }

    /**
     * Retrieves the minimum duration supported between two control points within an envelope
     * effect, in milliseconds.
     *
     * <p>If the device supports envelope effects
     * (check {@link android.os.VibratorInfo#areEnvelopeEffectsSupported}), this value will be
     * positive. Devices with envelope effects capabilities guarantee support for durations down
     * to at least 20 milliseconds. If the device does not support envelope effects,
     * this method will return 0.
     *
     * @return the minimum allowed duration between two control points in an envelope effect,
     * or 0 if envelope effects are not supported.
     */
    public long getMinControlPointDurationMillis() {
        return mMinControlPointDurationMillis;
    }

    /**
     * Retrieves the maximum duration supported between two control points within an envelope
     * effect, in milliseconds.
     *
     * <p>If the device supports envelope effects
     * (check {@link android.os.VibratorInfo#areEnvelopeEffectsSupported}), this value will be
     * positive. Devices with envelope effects capabilities guarantee support for durations up to
     * at least 1 second. If the device does not support envelope effects, this method
     * will return 0.
     *
     * @return the maximum allowed duration between two control points in an envelope effect,
     * or 0 if envelope effects are not supported.
     */
    public long getMaxControlPointDurationMillis() {
        return mMaxControlPointDurationMillis;
    }
}
