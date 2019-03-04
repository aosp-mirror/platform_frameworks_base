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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.media.AudioSystem;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * @hide
 * A class to encapsulate a collection of {@link AudioVolumeGroup}.
 */
@SystemApi
public final class AudioVolumeGroups implements Iterable<AudioVolumeGroup>, Parcelable {

    private final ArrayList<AudioVolumeGroup> mAudioVolumeGroupList;

    private static final String TAG = "AudioVolumeGroups";

    /**
     * Volume group value to use when introspection API fails.
     */
    public static final int DEFAULT_VOLUME_GROUP = -1;

    public AudioVolumeGroups() {
        ArrayList<AudioVolumeGroup> avgList = new ArrayList<AudioVolumeGroup>();
        int status = native_list_audio_volume_groups(avgList);
        if (status != AudioSystem.SUCCESS) {
            Log.w(TAG, ": listAudioVolumeGroups failed");
        }
        mAudioVolumeGroupList = avgList;
    }

    private AudioVolumeGroups(@NonNull ArrayList<AudioVolumeGroup> audioVolumeGroupList) {
        Preconditions.checkNotNull(audioVolumeGroupList, "audioVolumeGroupList must not be null");
        mAudioVolumeGroupList = audioVolumeGroupList;
    }

    /**
     * @return number of {@link AudioProductStrategy} objects
     */
    public int size() {
        return mAudioVolumeGroupList.size();
    }

    /**
     * Returns an {@link Iterator}
     */
    @Override
    public @NonNull Iterator<AudioVolumeGroup> iterator() {
        return mAudioVolumeGroupList.iterator();
    }

    @Override
    public boolean equals(@NonNull Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioVolumeGroups that = (AudioVolumeGroups) o;

        return mAudioVolumeGroupList.equals(that.mAudioVolumeGroupList);
    }

    /**
     * @return the matching {@link AudioVolumeGroup} objects with the given id,
     *         null object if not found.
     */
    public @Nullable AudioVolumeGroup getById(int volumeGroupId) {
        for (final AudioVolumeGroup avg : this) {
            if (avg.getId() == volumeGroupId) {
                return avg;
            }
        }
        Log.e(TAG, ": invalid volume group id: " + volumeGroupId + " requested");
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(size());
        for (final AudioVolumeGroup volumeGroup : this) {
            volumeGroup.writeToParcel(dest, flags);
        }
    }

    private static native int native_list_audio_volume_groups(
            ArrayList<AudioVolumeGroup> groups);

    public static final Parcelable.Creator<AudioVolumeGroups> CREATOR =
            new Parcelable.Creator<AudioVolumeGroups>() {
                @Override
                public @NonNull AudioVolumeGroups createFromParcel(@NonNull Parcel in) {
                    Preconditions.checkNotNull(in, "in Parcel must not be null");
                    ArrayList<AudioVolumeGroup> avgList = new ArrayList<AudioVolumeGroup>();
                    int size = in.readInt();
                    for (int index = 0; index < size; index++) {
                        avgList.add(AudioVolumeGroup.CREATOR.createFromParcel(in));
                    }
                    return new AudioVolumeGroups(avgList);
                }

                @Override
                public @NonNull AudioVolumeGroups[] newArray(int size) {
                    return new AudioVolumeGroups[size];
                }
            };
}
