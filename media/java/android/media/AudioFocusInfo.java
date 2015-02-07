/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * @hide
 * A class to encapsulate information about an audio focus owner or request.
 */
@SystemApi
public final class AudioFocusInfo implements Parcelable {

    private AudioAttributes mAttributes;
    private String mClientId;
    private String mPackageName;
    private int mGainRequest;
    private int mLossReceived;
    private int mFlags;


    /**
     * Class constructor
     * @param aa
     * @param clientId
     * @param packageName
     * @param gainRequest
     * @param lossReceived
     * @param flags
     * @hide
     */
    public AudioFocusInfo(AudioAttributes aa, String clientId, String packageName,
            int gainRequest, int lossReceived, int flags) {
        mAttributes = aa == null ? new AudioAttributes.Builder().build() : aa;
        mClientId = clientId == null ? "" : clientId;
        mPackageName = packageName == null ? "" : packageName;
        mGainRequest = gainRequest;
        mLossReceived = lossReceived;
        mFlags = flags;
    }


    /**
     * The audio attributes for the audio focus request.
     * @return non-null {@link AudioAttributes}.
     */
    @SystemApi
    public AudioAttributes getAttributes() { return mAttributes; }

    @SystemApi
    public String getClientId() { return mClientId; }

    @SystemApi
    public String getPackageName() { return mPackageName; }

    /**
     * The type of audio focus gain request.
     * @return one of {@link AudioManager#AUDIOFOCUS_GAIN},
     *     {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT},
     *     {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK},
     *     {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE}.
     */
    @SystemApi
    public int getGainRequest() { return mGainRequest; }

    /**
     * The type of audio focus loss that was received by the
     * {@link AudioManager.OnAudioFocusChangeListener} if one was set.
     * @return 0 if focus wasn't lost, or one of {@link AudioManager#AUDIOFOCUS_LOSS},
     *   {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT} or
     *   {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}.
     */
    @SystemApi
    public int getLossReceived() { return mLossReceived; }

    /** @hide */
    public void clearLossReceived() { mLossReceived = 0; }

    /**
     * The flags set in the audio focus request.
     * @return 0 or a combination of {link AudioManager#AUDIOFOCUS_FLAG_DELAY_OK},
     *     {@link AudioManager#AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS}, and
     *     {@link AudioManager#AUDIOFOCUS_FLAG_LOCK}.
     */
    @SystemApi
    public int getFlags() { return mFlags; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mAttributes.writeToParcel(dest, flags);
        dest.writeString(mClientId);
        dest.writeString(mPackageName);
        dest.writeInt(mGainRequest);
        dest.writeInt(mLossReceived);
        dest.writeInt(mFlags);
    }

    @SystemApi
    @Override
    public int hashCode() {
        return Objects.hash(mAttributes, mClientId, mPackageName, mGainRequest, mFlags);
    }

    @SystemApi
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AudioFocusInfo other = (AudioFocusInfo) obj;
        if (!mAttributes.equals(other.mAttributes)) {
            return false;
        }
        if (!mClientId.equals(other.mClientId)) {
            return false;
        }
        if (!mPackageName.equals(other.mPackageName)) {
            return false;
        }
        if (mGainRequest != other.mGainRequest) {
            return false;
        }
        if (mLossReceived != other.mLossReceived) {
            return false;
        }
        if (mFlags != other.mFlags) {
            return false;
        }
        return true;
    }

    public static final Parcelable.Creator<AudioFocusInfo> CREATOR
            = new Parcelable.Creator<AudioFocusInfo>() {

        public AudioFocusInfo createFromParcel(Parcel in) {
            return new AudioFocusInfo(
                    AudioAttributes.CREATOR.createFromParcel(in), //AudioAttributes aa
                    in.readString(), //String clientId
                    in.readString(), //String packageName
                    in.readInt(), //int gainRequest
                    in.readInt(), //int lossReceived
                    in.readInt() //int flags
                    );
        }

        public AudioFocusInfo[] newArray(int size) {
            return new AudioFocusInfo[size];
        }
    };
}
