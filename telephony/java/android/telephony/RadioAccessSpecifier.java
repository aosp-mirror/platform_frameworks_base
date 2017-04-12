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
 * @hide
 */
public final class RadioAccessSpecifier implements Parcelable {

    /**
     * The radio access network that needs to be scanned
     *
     * See {@link RadioNetworkConstants.RadioAccessNetworks} for details.
     */
    public int radioAccessNetwork;

    /**
     * The frequency bands that need to be scanned
     *
     * bands must be used together with radioAccessNetwork
     *
     * See {@link RadioNetworkConstants} for details.
     */
    public int[] bands;

    /**
     * The frequency channels that need to be scanned
     *
     * channels must be used together with radioAccessNetwork
     *
     * See {@link RadioNetworkConstants.RadioAccessNetworks} for details.
     */
    public int[] channels;

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
        this.radioAccessNetwork = ran;
        this.bands = bands;
        this.channels = channels;
    }

    public static final Parcelable.Creator<RadioAccessSpecifier> CREATOR =
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
        dest.writeInt(radioAccessNetwork);
        dest.writeIntArray(bands);
        dest.writeIntArray(channels);
    }

    private RadioAccessSpecifier(Parcel in) {
        radioAccessNetwork = in.readInt();
        bands = in.createIntArray();
        channels = in.createIntArray();
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

        return (radioAccessNetwork == ras.radioAccessNetwork
                && Arrays.equals(bands, ras.bands)
                && Arrays.equals(channels, ras.channels));
    }

    @Override
    public int hashCode () {
        return ((radioAccessNetwork * 31)
                + (Arrays.hashCode(bands) * 37)
                + (Arrays.hashCode(channels)) * 39);
    }
}
