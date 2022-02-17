/*
 * Copyright 2021 The Android Open Source Project
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
package android.media;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothProfile;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Contains information about Bluetooth profile connection state changed
 * {@hide}
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class BluetoothProfileConnectionInfo implements Parcelable {
    private final int mProfile;
    private final boolean mSupprNoisy;
    private final int mVolume;
    private final boolean mIsLeOutput;

    private BluetoothProfileConnectionInfo(int profile, boolean suppressNoisyIntent,
            int volume, boolean isLeOutput) {
        mProfile = profile;
        mSupprNoisy = suppressNoisyIntent;
        mVolume = volume;
        mIsLeOutput = isLeOutput;
    }

    /**
     * Constructor used by BtHelper when a profile is connected
     * {@hide}
     */
    public BluetoothProfileConnectionInfo(int profile) {
        this(profile, false, -1, false);
    }

    public static final @NonNull Parcelable.Creator<BluetoothProfileConnectionInfo> CREATOR =
            new Parcelable.Creator<BluetoothProfileConnectionInfo>() {
                @Override
                public BluetoothProfileConnectionInfo createFromParcel(Parcel source) {
                    return new BluetoothProfileConnectionInfo(source.readInt(),
                            source.readBoolean(), source.readInt(), source.readBoolean());
                }

                @Override
                public BluetoothProfileConnectionInfo[] newArray(int size) {
                    return new BluetoothProfileConnectionInfo[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel dest, @WriteFlags int flags) {
        dest.writeInt(mProfile);
        dest.writeBoolean(mSupprNoisy);
        dest.writeInt(mVolume);
        dest.writeBoolean(mIsLeOutput);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Constructor for A2dp info
     *
     * @param suppressNoisyIntent if true the {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY}
     * intent will not be sent.
     *
     * @param volume of device -1 to ignore value
     */
    public static @NonNull BluetoothProfileConnectionInfo createA2dpInfo(
            boolean suppressNoisyIntent, int volume) {
        return new BluetoothProfileConnectionInfo(BluetoothProfile.A2DP, suppressNoisyIntent,
            volume, false);
    }

    /**
     * Constructor for A2dp sink info
     * The {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY} intent will not be sent.
     *
     * @param volume of device -1 to ignore value
     */
    public static @NonNull BluetoothProfileConnectionInfo createA2dpSinkInfo(int volume) {
        return new BluetoothProfileConnectionInfo(BluetoothProfile.A2DP_SINK, true, volume, false);
    }

    /**
     * Constructor for hearing aid info
     *
     * @param suppressNoisyIntent if true the {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY}
     * intent will not be sent.
     */
    public static @NonNull BluetoothProfileConnectionInfo createHearingAidInfo(
            boolean suppressNoisyIntent) {
        return new BluetoothProfileConnectionInfo(BluetoothProfile.HEARING_AID, suppressNoisyIntent,
            -1, false);
    }

    /**
     * constructor for le audio info
     *
     * @param suppressNoisyIntent if true the {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY}
     * intent will not be sent.
     *
     * @param isLeOutput if true mean the device is an output device, if false it's an input device
     */
    public static @NonNull BluetoothProfileConnectionInfo createLeAudioInfo(
            boolean suppressNoisyIntent, boolean isLeOutput) {
        return new BluetoothProfileConnectionInfo(BluetoothProfile.LE_AUDIO, suppressNoisyIntent,
            -1, isLeOutput);
    }

    /**
     * @return The profile connection
     */
    public int getProfile() {
        return mProfile;
    }

    /**
     * @return {@code true} if {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY} intent will not be
     * sent
     */
    public boolean isSuppressNoisyIntent() {
        return mSupprNoisy;
    }

    /**
     * Only for {@link BluetoothProfile.A2DP} profile
     * @return the volume of the connection or -1 if the value is ignored
     */
    public int getVolume() {
        return mVolume;
    }

    /**
     * Only for {@link BluetoothProfile.LE_AUDIO} profile
     * @return {@code true} is the LE device is an output device, {@code false} if it's an input
     * device
     */
    public boolean isLeOutput() {
        return mIsLeOutput;
    }
}
