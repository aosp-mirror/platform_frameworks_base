/* Copyright 2014, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package android.media.session;

import android.media.AudioAttributes;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Convenience class for passing information about the audio configuration of a
 * session. The public implementation is {@link MediaController.PlaybackInfo}.
 *
 * @hide
 */
public class ParcelableVolumeInfo implements Parcelable {
    public int volumeType;
    public AudioAttributes audioAttrs;
    public int controlType;
    public int maxVolume;
    public int currentVolume;

    public ParcelableVolumeInfo(int volumeType, AudioAttributes audioAttrs, int controlType,
            int maxVolume,
            int currentVolume) {
        this.volumeType = volumeType;
        this.audioAttrs = audioAttrs;
        this.controlType = controlType;
        this.maxVolume = maxVolume;
        this.currentVolume = currentVolume;
    }

    public ParcelableVolumeInfo(Parcel from) {
        volumeType = from.readInt();
        controlType = from.readInt();
        maxVolume = from.readInt();
        currentVolume = from.readInt();
        audioAttrs = AudioAttributes.CREATOR.createFromParcel(from);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(volumeType);
        dest.writeInt(controlType);
        dest.writeInt(maxVolume);
        dest.writeInt(currentVolume);
        audioAttrs.writeToParcel(dest, flags);
    }


    public static final Parcelable.Creator<ParcelableVolumeInfo> CREATOR
            = new Parcelable.Creator<ParcelableVolumeInfo>() {
        @Override
        public ParcelableVolumeInfo createFromParcel(Parcel in) {
            return new ParcelableVolumeInfo(in);
        }

        @Override
        public ParcelableVolumeInfo[] newArray(int size) {
            return new ParcelableVolumeInfo[size];
        }
    };
}
