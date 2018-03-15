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
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * @hide
 * A class to encapsulate a collection of {@link AudioProductStrategy}.
 * Provides helper functions to easily retrieve the {@link AudioAttributes} for a given product
 * strategy or legacy stream type.
 */
@SystemApi
public final class AudioProductStrategies implements Iterable<AudioProductStrategy>, Parcelable {

    private final ArrayList<AudioProductStrategy> mAudioProductStrategyList;

    private static final String TAG = "AudioProductStrategies";

    public AudioProductStrategies() {
        ArrayList<AudioProductStrategy> apsList = new ArrayList<AudioProductStrategy>();
        int status = native_list_audio_product_strategies(apsList);
        if (status != AudioSystem.SUCCESS) {
            Log.w(TAG, ": createAudioProductStrategies failed");
        }
        mAudioProductStrategyList = apsList;
    }

    private AudioProductStrategies(ArrayList<AudioProductStrategy> audioProductStrategy) {
        mAudioProductStrategyList = audioProductStrategy;
    }

    /**
     * @hide
     * @return number of {@link AudioProductStrategy} objects
     */
    @SystemApi
    public int size() {
        return mAudioProductStrategyList.size();
    }

    /**
     * @hide
     * @return the matching {@link AudioProductStrategy} objects with the given id,
     *         null object if not found.
     */
    @SystemApi
    public @Nullable AudioProductStrategy getById(int productStrategyId) {
        for (final AudioProductStrategy avg : this) {
            if (avg.getId() == productStrategyId) {
                return avg;
            }
        }
        Log.e(TAG, ": invalid product strategy id: " + productStrategyId + " requested");
        return null;
    }

    /**
     * Returns an {@link Iterator}
     */
    @Override
    public Iterator<AudioProductStrategy> iterator() {
        return mAudioProductStrategyList.iterator();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioProductStrategies that = (AudioProductStrategies) o;

        return mAudioProductStrategyList.equals(that.mAudioProductStrategyList);
    }

    /**
     * @hide
     * @param aps {@link AudioProductStrategy} (which is the generalisation of Car Audio Usage /
     *                        legacy routing_strategy linked to {@link AudioAttributes#getUsage()} )
     * @return the {@link AudioAttributes} relevant for the given product strategy.
     *         If none is found, it builds the default attributes.
     *         TODO: shall the helper collection be able to identify the platform default?
     */
    @SystemApi
    @NonNull
    public AudioAttributes getAudioAttributesForProductStrategy(@NonNull AudioProductStrategy aps) {
        Preconditions.checkNotNull(aps, "AudioProductStrategy must not be null");
        for (final AudioProductStrategy audioProductStrategy : this) {
            if (audioProductStrategy.equals(aps)) {
                return audioProductStrategy.getAudioAttributes();
            }
        }
        return new AudioAttributes.Builder()
                                  .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                                  .setUsage(AudioAttributes.USAGE_UNKNOWN).build();
    }

    /**
     * @hide
     * @param streamType legacy stream type used for volume operation only
     * @return the {@link AudioAttributes} relevant for the given streamType.
     *         If none is found, it builds the default attributes.
     */
    @SystemApi
    public @NonNull AudioAttributes getAudioAttributesForLegacyStreamType(int streamType) {
        for (final AudioProductStrategy productStrategy : this) {
            AudioAttributes aa = productStrategy.getAudioAttributesForLegacyStreamType(streamType);
            if (aa != null) {
                return aa;
            }
        }
        return new AudioAttributes.Builder()
                                  .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                                  .setUsage(AudioAttributes.USAGE_UNKNOWN).build();
    }

    /**
     * @hide
     * @param aa the {@link AudioAttributes} for which stream type is requested
     * @return the legacy stream type relevant for the given {@link AudioAttributes}.
     *         If the product strategy is not associated to any stream, it returns STREAM_MUSIC.
     *         If no product strategy supports the stream type, it returns STREAM_MUSIC.
     */
    @SystemApi
    public int getLegacyStreamTypeForAudioAttributes(@NonNull AudioAttributes aa) {
        Preconditions.checkNotNull(aa, "AudioAttributes must not be null");
        for (final AudioProductStrategy productStrategy : this) {
            if (productStrategy.supportsAudioAttributes(aa)) {
                int streamType = productStrategy.getLegacyStreamTypeForAudioAttributes(aa);
                if (streamType == AudioSystem.STREAM_DEFAULT) {
                    Log.w(TAG, "Attributes " + aa.toString() + " ported by strategy "
                            + productStrategy.name() + " has no stream type associated, "
                            + "DO NOT USE STREAM TO CONTROL THE VOLUME");
                    return AudioSystem.STREAM_MUSIC;
                }
                return streamType;
            }
        }
        return AudioSystem.STREAM_MUSIC;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(size());
        for (final AudioProductStrategy productStrategy : this) {
            productStrategy.writeToParcel(dest, flags);
        }
    }

    public static final Parcelable.Creator<AudioProductStrategies> CREATOR =
            new Parcelable.Creator<AudioProductStrategies>() {
                @Override
                public AudioProductStrategies createFromParcel(@NonNull Parcel in) {
                    ArrayList<AudioProductStrategy> apsList = new ArrayList<AudioProductStrategy>();
                    int size = in.readInt();
                    for (int index = 0; index < size; index++) {
                        apsList.add(AudioProductStrategy.CREATOR.createFromParcel(in));
                    }
                    return new AudioProductStrategies(apsList);
                }

                @Override
                public @NonNull AudioProductStrategies[] newArray(int size) {
                    return new AudioProductStrategies[size];
                }
            };

    private static native int native_list_audio_product_strategies(
            ArrayList<AudioProductStrategy> strategies);
}
