/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media.audiopolicy;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.AudioAttributes;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.List;

/**
 * A class to create the association between different playback attributes
 * (e.g. media, mapping direction) to a single volume control.
 * @hide
 */
@SystemApi
public final class AudioVolumeGroup implements Parcelable {
    /**
     * Unique identifier of a volume group.
     */
    private int mId;
    /**
     * human-readable name of this volume group.
     */
    private final String mName;

    private final AudioAttributes[] mAudioAttributes;
    private int[] mLegacyStreamTypes;

    /**
     * @param name of the volume group
     * @param id of the volume group
     * @param followers {@link AudioProductStrategies} strategy following this volume group
     */
    AudioVolumeGroup(@NonNull String name, int id,
                     @NonNull AudioAttributes[] audioAttributes,
                     @NonNull int[] legacyStreamTypes) {
        Preconditions.checkNotNull(name, "name must not be null");
        Preconditions.checkNotNull(audioAttributes, "audioAttributes must not be null");
        Preconditions.checkNotNull(legacyStreamTypes, "legacyStreamTypes must not be null");
        mName = name;
        mId = id;
        mAudioAttributes = audioAttributes;
        mLegacyStreamTypes = legacyStreamTypes;
    }

    @Override
    public boolean equals(@NonNull Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioVolumeGroup thatAvg = (AudioVolumeGroup) o;

        return mName == thatAvg.mName && mId == thatAvg.mId
                && mAudioAttributes.equals(thatAvg.mAudioAttributes);
    }

    /**
     * @return List of {@link AudioAttributes} involved in this {@link AudioVolumeGroup}.
     */
    public @NonNull List<AudioAttributes> getAudioAttributes() {
        return Arrays.asList(mAudioAttributes);
    }

    /**
     * @return the stream types involved in this {@link AudioVolumeGroup}.
     */
    public @NonNull int[] getLegacyStreamTypes() {
        return mLegacyStreamTypes;
    }

    /**
     * @return human-readable name of this volume group.
     */
    public @NonNull String name() {
        return mName;
    }

    /**
     * @return the volume group unique identifier id.
     */
    public int getId() {
        return mId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mId);
        dest.writeInt(mAudioAttributes.length);
        for (AudioAttributes attributes : mAudioAttributes) {
            attributes.writeToParcel(dest, flags | AudioAttributes.FLATTEN_TAGS/*flags*/);
        }
        dest.writeInt(mLegacyStreamTypes.length);
        for (int streamType : mLegacyStreamTypes) {
            dest.writeInt(streamType);
        }
    }

    public static final Parcelable.Creator<AudioVolumeGroup> CREATOR =
            new Parcelable.Creator<AudioVolumeGroup>() {
                @Override
                public @NonNull AudioVolumeGroup createFromParcel(@NonNull Parcel in) {
                    Preconditions.checkNotNull(in, "in Parcel must not be null");
                    String name = in.readString();
                    int id = in.readInt();
                    int nbAttributes = in.readInt();
                    AudioAttributes[] audioAttributes = new AudioAttributes[nbAttributes];
                    for (int index = 0; index < nbAttributes; index++) {
                        audioAttributes[index] = AudioAttributes.CREATOR.createFromParcel(in);
                    }
                    int nbStreamTypes = in.readInt();
                    int[] streamTypes = new int[nbStreamTypes];
                    for (int index = 0; index < nbStreamTypes; index++) {
                        streamTypes[index] = in.readInt();
                    }
                    return new AudioVolumeGroup(name, id, audioAttributes, streamTypes);
                }

                @Override
                public @NonNull AudioVolumeGroup[] newArray(int size) {
                    return new AudioVolumeGroup[size];
                }
            };

    @Override
    public @NonNull String toString() {
        StringBuilder s = new StringBuilder();
        s.append("\n Name: ");
        s.append(mName);
        s.append(" Id: ");
        s.append(Integer.toString(mId));

        s.append("\n     Supported Audio Attributes:");
        for (AudioAttributes attribute : mAudioAttributes) {
            s.append("\n       -");
            s.append(attribute.toString());
        }
        s.append("\n     Supported Legacy Stream Types: { ");
        for (int legacyStreamType : mLegacyStreamTypes) {
            s.append(Integer.toString(legacyStreamType));
            s.append(" ");
        }
        s.append("}");
        return s.toString();
    }
}
