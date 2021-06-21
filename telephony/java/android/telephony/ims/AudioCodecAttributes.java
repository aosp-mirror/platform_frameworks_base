/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Range;

/**
 * Parcelable object to handle audio codec attributes.
 * It provides the audio codec bitrate, bandwidth and their upper/lower bound.
 *
 * @hide
 */
@SystemApi
public final class AudioCodecAttributes implements Parcelable {
    // The audio codec bitrate in kbps.
    private float mBitrateKbps;
    // The range of the audio codec bitrate in kbps.
    private Range<Float> mBitrateRangeKbps;
    // The audio codec bandwidth in kHz.
    private float mBandwidthKhz;
    // The range of the audio codec bandwidth in kHz.
    private Range<Float> mBandwidthRangeKhz;


    /**
     * Constructor.
     *
     * @param bitrateKbps        The audio codec bitrate in kbps.
     * @param bitrateRangeKbps  The range of the audio codec bitrate in kbps.
     * @param bandwidthKhz      The audio codec bandwidth in kHz.
     * @param bandwidthRangeKhz The range of the audio codec bandwidth in kHz.
     */

    public AudioCodecAttributes(float bitrateKbps, @NonNull Range<Float> bitrateRangeKbps,
            float bandwidthKhz, @NonNull Range<Float> bandwidthRangeKhz) {
        mBitrateKbps = bitrateKbps;
        mBitrateRangeKbps = bitrateRangeKbps;
        mBandwidthKhz = bandwidthKhz;
        mBandwidthRangeKhz = bandwidthRangeKhz;
    }

    private AudioCodecAttributes(Parcel in) {
        mBitrateKbps = in.readFloat();
        mBitrateRangeKbps = new Range<>(in.readFloat(), in.readFloat());
        mBandwidthKhz = in.readFloat();
        mBandwidthRangeKhz = new Range<>(in.readFloat(), in.readFloat());
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeFloat(mBitrateKbps);
        out.writeFloat(mBitrateRangeKbps.getLower());
        out.writeFloat(mBitrateRangeKbps.getUpper());
        out.writeFloat(mBandwidthKhz);
        out.writeFloat(mBandwidthRangeKhz.getLower());
        out.writeFloat(mBandwidthRangeKhz.getUpper());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<AudioCodecAttributes> CREATOR =
            new Creator<AudioCodecAttributes>() {
                @Override
                public AudioCodecAttributes createFromParcel(Parcel in) {
                    return new AudioCodecAttributes(in);
                }

                @Override
                public AudioCodecAttributes[] newArray(int size) {
                    return new AudioCodecAttributes[size];
                }
            };

    /**
     * @return the exact value of the audio codec bitrate in kbps.
     */
    public float getBitrateKbps() {
        return mBitrateKbps;
    }

    /**
     * @return the range of the audio codec bitrate in kbps
     */
    public @NonNull Range<Float> getBitrateRangeKbps() {
        return mBitrateRangeKbps;
    }

    /**
     * @return the exact value of the audio codec bandwidth in kHz.
     */
    public float getBandwidthKhz() {
        return mBandwidthKhz;
    }

    /**
     * @return the range of the audio codec bandwidth in kHz.
     */
    public @NonNull Range<Float> getBandwidthRangeKhz() {
        return mBandwidthRangeKhz;
    }

    @NonNull
    @Override
    public String toString() {
        return "{ bitrateKbps=" + mBitrateKbps
                + ", bitrateRangeKbps=" + mBitrateRangeKbps
                + ", bandwidthKhz=" + mBandwidthKhz
                + ", bandwidthRangeKhz=" + mBandwidthRangeKhz + " }";
    }
}
