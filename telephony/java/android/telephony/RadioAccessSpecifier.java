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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Describes a particular radio access network to be scanned.
 *
 * The scan can be performed on either bands or channels for a specific radio access network type.
 */
public final class RadioAccessSpecifier implements Parcelable {

    /**
     * The radio access network that needs to be scanned
     *
     * This parameter must be provided or else the scan will be rejected.
     *
     * See {@link AccessNetworkConstants.AccessNetworkType} for details.
     */
    private int mRadioAccessNetwork;

    /**
     * The frequency bands that need to be scanned
     *
     * When no specific bands are specified (empty array or null), all the frequency bands
     * supported by the modem will be scanned.
     *
     * See {@link AccessNetworkConstants} for details.
     */
    private int[] mBands;

    /**
     * The frequency channels that need to be scanned
     *
     * When any specific channels are provided for scan, the corresponding frequency bands that
     * contains those channels must also be provided, or else the channels will be ignored.
     *
     * When no specific channels are specified (empty array or null), all the frequency channels
     * supported by the modem will be scanned.
     *
     * See {@link AccessNetworkConstants} for details.
     */
    private int[] mChannels;

    /**
    * Creates a new RadioAccessSpecifier with radio network, bands and channels
    *
    * The user must specify the radio network type, and at least specify either of frequency
    * bands or channels.
    *
    * @param ran The type of the radio access network
    * @param bands the frequency bands to be scanned
    * @param channels the frequency bands to be scanned
    */
    public RadioAccessSpecifier(int ran, int[] bands, int[] channels) {
        this.mRadioAccessNetwork = ran;
        if (bands != null) {
            this.mBands = bands.clone();
        } else {
            this.mBands = null;
        }
        if (channels != null) {
            this.mChannels = channels.clone();
        } else {
            this.mChannels = null;
        }
    }

    /**
     * Returns the radio access network that needs to be scanned.
     *
     * The returned value is define in {@link AccessNetworkConstants.AccessNetworkType};
     */
    public int getRadioAccessNetwork() {
        return mRadioAccessNetwork;
    }

    /**
     * Returns the frequency bands that need to be scanned.
     *
     * The returned value is defined in either of {@link AccessNetworkConstants.GeranBand},
     * {@link AccessNetworkConstants.UtranBand}, {@link AccessNetworkConstants.EutranBand},
     * and {@link AccessNetworkConstants.NgranBands}, and it depends on
     * the returned value of {@link #getRadioAccessNetwork()}.
     */
    public int[] getBands() {
        return mBands == null ? null : mBands.clone();
    }

    /** Returns the frequency channels that need to be scanned. */
    public int[] getChannels() {
        return mChannels == null ? null : mChannels.clone();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<RadioAccessSpecifier> CREATOR =
            new Parcelable.Creator<RadioAccessSpecifier> (){
                @Override
                public RadioAccessSpecifier createFromParcel(Parcel in) {
                    return new RadioAccessSpecifier(in);
                }

                @Override
                public RadioAccessSpecifier[] newArray(int size) {
                    return new RadioAccessSpecifier[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRadioAccessNetwork);
        dest.writeIntArray(mBands);
        dest.writeIntArray(mChannels);
    }

    private RadioAccessSpecifier(Parcel in) {
        mRadioAccessNetwork = in.readInt();
        mBands = in.createIntArray();
        mChannels = in.createIntArray();
    }

    @Override
    public boolean equals (Object o) {
        RadioAccessSpecifier ras;

        try {
            ras = (RadioAccessSpecifier) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (mRadioAccessNetwork == ras.mRadioAccessNetwork
                && Arrays.equals(mBands, ras.mBands)
                && Arrays.equals(mChannels, ras.mChannels));
    }

    @Override
    public int hashCode () {
        return ((mRadioAccessNetwork * 31)
                + (Arrays.hashCode(mBands) * 37)
                + (Arrays.hashCode(mChannels)) * 39);
    }
}
