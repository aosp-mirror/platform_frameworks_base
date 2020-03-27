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
import android.annotation.TestApi;
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.media.MediaRecorder;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 * A class to encapsulate a collection of attributes associated to a given product strategy
 * (and for legacy reason, keep the association with the stream type).
 */
@SystemApi
public final class AudioProductStrategy implements Parcelable {
    /**
     * group value to use when introspection API fails.
     * @hide
     */
    public static final int DEFAULT_GROUP = -1;


    private static final String TAG = "AudioProductStrategy";

    private final AudioAttributesGroup[] mAudioAttributesGroups;
    private final String mName;
    /**
     * Unique identifier of a product strategy.
     * This Id can be assimilated to Car Audio Usage and even more generally to usage.
     * For legacy platforms, the product strategy id is the routing_strategy, which was hidden to
     * upper layer but was transpiring in the {@link AudioAttributes#getUsage()}.
     */
    private int mId;

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static List<AudioProductStrategy> sAudioProductStrategies;

    /**
     * @hide
     * @return the list of AudioProductStrategy discovered from platform configuration file.
     */
    @NonNull
    public static List<AudioProductStrategy> getAudioProductStrategies() {
        if (sAudioProductStrategies == null) {
            synchronized (sLock) {
                if (sAudioProductStrategies == null) {
                    sAudioProductStrategies = initializeAudioProductStrategies();
                }
            }
        }
        return sAudioProductStrategies;
    }

    /**
     * @hide
     * Return the AudioProductStrategy object for the given strategy ID.
     * @param id the ID of the strategy to find
     * @return an AudioProductStrategy on which getId() would return id, null if no such strategy
     *     exists.
     */
    public static @Nullable AudioProductStrategy getAudioProductStrategyWithId(int id) {
        synchronized (sLock) {
            if (sAudioProductStrategies == null) {
                sAudioProductStrategies = initializeAudioProductStrategies();
            }
            for (AudioProductStrategy strategy : sAudioProductStrategies) {
                if (strategy.getId() == id) {
                    return strategy;
                }
            }
        }
        return null;
    }

    /**
     * @hide
     * Create an invalid AudioProductStrategy instance for testing
     * @param id the ID for the invalid strategy, always use a different one than in use
     * @return an invalid instance that cannot successfully be used for volume groups or routing
     */
    @TestApi
    @SystemApi
    public static @NonNull AudioProductStrategy createInvalidAudioProductStrategy(int id) {
        return new AudioProductStrategy("dummy strategy", id, new AudioAttributesGroup[0]);
    }

    /**
     * @hide
     * @param streamType to match against AudioProductStrategy
     * @return the AudioAttributes for the first strategy found with the associated stream type
     *          If no match is found, returns AudioAttributes with unknown content_type and usage
     */
    @NonNull
    public static AudioAttributes getAudioAttributesForStrategyWithLegacyStreamType(
            int streamType) {
        for (final AudioProductStrategy productStrategy :
                AudioProductStrategy.getAudioProductStrategies()) {
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
     * @param audioAttributes to identify AudioProductStrategy with
     * @return legacy stream type associated with matched AudioProductStrategy
     *              Defaults to STREAM_MUSIC if no match is found, or if matches is STREAM_DEFAULT
     */
    public static int getLegacyStreamTypeForStrategyWithAudioAttributes(
            @NonNull AudioAttributes audioAttributes) {
        Preconditions.checkNotNull(audioAttributes, "AudioAttributes must not be null");
        for (final AudioProductStrategy productStrategy :
                AudioProductStrategy.getAudioProductStrategies()) {
            if (productStrategy.supportsAudioAttributes(audioAttributes)) {
                int streamType = productStrategy.getLegacyStreamTypeForAudioAttributes(
                        audioAttributes);
                if (streamType == AudioSystem.STREAM_DEFAULT) {
                    Log.w(TAG, "Attributes " + audioAttributes.toString() + " ported by strategy "
                            + productStrategy.getId() + " has no stream type associated, "
                            + "DO NOT USE STREAM TO CONTROL THE VOLUME");
                    return AudioSystem.STREAM_MUSIC;
                }
                if (streamType < AudioSystem.getNumStreamTypes()) {
                    return streamType;
                }
            }
        }
        return AudioSystem.STREAM_MUSIC;
    }

    private static List<AudioProductStrategy> initializeAudioProductStrategies() {
        ArrayList<AudioProductStrategy> apsList = new ArrayList<AudioProductStrategy>();
        int status = native_list_audio_product_strategies(apsList);
        if (status != AudioSystem.SUCCESS) {
            Log.w(TAG, ": initializeAudioProductStrategies failed");
        }
        return apsList;
    }

    private static native int native_list_audio_product_strategies(
            ArrayList<AudioProductStrategy> strategies);

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioProductStrategy thatStrategy = (AudioProductStrategy) o;

        return mName == thatStrategy.mName && mId == thatStrategy.mId
                && mAudioAttributesGroups.equals(thatStrategy.mAudioAttributesGroups);
    }

    /**
     * @param name of the product strategy
     * @param id of the product strategy
     * @param aag {@link AudioAttributesGroup} associated to the given product strategy
     */
    private AudioProductStrategy(@NonNull String name, int id,
            @NonNull AudioAttributesGroup[] aag) {
        Preconditions.checkNotNull(name, "name must not be null");
        Preconditions.checkNotNull(aag, "AudioAttributesGroups must not be null");
        mName = name;
        mId = id;
        mAudioAttributesGroups = aag;
    }

    /**
     * @hide
     * @return the product strategy ID (which is the generalisation of Car Audio Usage / legacy
     *         routing_strategy linked to {@link AudioAttributes#getUsage()}).
     */
    @SystemApi
    public int getId() {
        return mId;
    }

    /**
     * @hide
     * @return first {@link AudioAttributes} associated to this product strategy.
     */
    @SystemApi
    public @NonNull AudioAttributes getAudioAttributes() {
        // We need a choice, so take the first one
        return mAudioAttributesGroups.length == 0 ? (new AudioAttributes.Builder().build())
                : mAudioAttributesGroups[0].getAudioAttributes();
    }

    /**
     * @hide
     * @param streamType legacy stream type used for volume operation only
     * @return the {@link AudioAttributes} relevant for the given streamType.
     *         If none is found, it builds the default attributes.
     */
    public @Nullable AudioAttributes getAudioAttributesForLegacyStreamType(int streamType) {
        for (final AudioAttributesGroup aag : mAudioAttributesGroups) {
            if (aag.supportsStreamType(streamType)) {
                return aag.getAudioAttributes();
            }
        }
        return null;
    }

    /**
     * @hide
     * @param aa the {@link AudioAttributes} to be considered
     * @return the legacy stream type relevant for the given {@link AudioAttributes}.
     *         If none is found, it return DEFAULT stream type.
     */
    public int getLegacyStreamTypeForAudioAttributes(@NonNull AudioAttributes aa) {
        Preconditions.checkNotNull(aa, "AudioAttributes must not be null");
        for (final AudioAttributesGroup aag : mAudioAttributesGroups) {
            if (aag.supportsAttributes(aa)) {
                return aag.getStreamType();
            }
        }
        return AudioSystem.STREAM_DEFAULT;
    }

    /**
     * @hide
     * @param aa the {@link AudioAttributes} to be considered
     * @return true if the {@link AudioProductStrategy} supports the given {@link AudioAttributes},
     *         false otherwise.
     */
    @SystemApi
    public boolean supportsAudioAttributes(@NonNull AudioAttributes aa) {
        Preconditions.checkNotNull(aa, "AudioAttributes must not be null");
        for (final AudioAttributesGroup aag : mAudioAttributesGroups) {
            if (aag.supportsAttributes(aa)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @hide
     * @param streamType legacy stream type used for volume operation only
     * @return the volume group id relevant for the given streamType.
     *         If none is found, {@link AudioVolumeGroup#DEFAULT_VOLUME_GROUP} is returned.
     */
    public int getVolumeGroupIdForLegacyStreamType(int streamType) {
        for (final AudioAttributesGroup aag : mAudioAttributesGroups) {
            if (aag.supportsStreamType(streamType)) {
                return aag.getVolumeGroupId();
            }
        }
        return AudioVolumeGroup.DEFAULT_VOLUME_GROUP;
    }

    /**
     * @hide
     * @param aa the {@link AudioAttributes} to be considered
     * @return the volume group id associated with the given audio attributes if found,
     *         {@link AudioVolumeGroup#DEFAULT_VOLUME_GROUP} otherwise.
     */
    public int getVolumeGroupIdForAudioAttributes(@NonNull AudioAttributes aa) {
        Preconditions.checkNotNull(aa, "AudioAttributes must not be null");
        for (final AudioAttributesGroup aag : mAudioAttributesGroups) {
            if (aag.supportsAttributes(aa)) {
                return aag.getVolumeGroupId();
            }
        }
        return AudioVolumeGroup.DEFAULT_VOLUME_GROUP;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mId);
        dest.writeInt(mAudioAttributesGroups.length);
        for (AudioAttributesGroup aag : mAudioAttributesGroups) {
            aag.writeToParcel(dest, flags);
        }
    }

    @NonNull
    public static final Parcelable.Creator<AudioProductStrategy> CREATOR =
            new Parcelable.Creator<AudioProductStrategy>() {
                @Override
                public AudioProductStrategy createFromParcel(@NonNull Parcel in) {
                    String name = in.readString();
                    int id = in.readInt();
                    int nbAttributesGroups = in.readInt();
                    AudioAttributesGroup[] aag = new AudioAttributesGroup[nbAttributesGroups];
                    for (int index = 0; index < nbAttributesGroups; index++) {
                        aag[index] = AudioAttributesGroup.CREATOR.createFromParcel(in);
                    }
                    return new AudioProductStrategy(name, id, aag);
                }

                @Override
                public @NonNull AudioProductStrategy[] newArray(int size) {
                    return new AudioProductStrategy[size];
                }
            };

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("\n Name: ");
        s.append(mName);
        s.append(" Id: ");
        s.append(Integer.toString(mId));
        for (AudioAttributesGroup aag : mAudioAttributesGroups) {
            s.append(aag.toString());
        }
        return s.toString();
    }

    /**
     * @hide
     * Default attributes, with default source to be aligned with native.
     */
    public static final @NonNull AudioAttributes sDefaultAttributes =
            new AudioAttributes.Builder().setCapturePreset(MediaRecorder.AudioSource.DEFAULT)
                                         .build();

    /**
     * To avoid duplicating the logic in java and native, we shall make use of
     * native API native_get_product_strategies_from_audio_attributes
     * Keep in sync with frameworks/av/media/libaudioclient/AudioProductStrategy::attributesMatches
     * @param refAttr {@link AudioAttributes} to be taken as the reference
     * @param attr {@link AudioAttributes} of the requester.
     */
    private static boolean attributesMatches(@NonNull AudioAttributes refAttr,
            @NonNull AudioAttributes attr) {
        Preconditions.checkNotNull(refAttr, "refAttr must not be null");
        Preconditions.checkNotNull(attr, "attr must not be null");
        String refFormattedTags = TextUtils.join(";", refAttr.getTags());
        String cliFormattedTags = TextUtils.join(";", attr.getTags());
        if (refAttr.equals(sDefaultAttributes)) {
            return false;
        }
        return ((refAttr.getSystemUsage() == AudioAttributes.USAGE_UNKNOWN)
                || (attr.getSystemUsage() == refAttr.getSystemUsage()))
            && ((refAttr.getContentType() == AudioAttributes.CONTENT_TYPE_UNKNOWN)
                || (attr.getContentType() == refAttr.getContentType()))
            && ((refAttr.getAllFlags() == 0)
                || (attr.getAllFlags() != 0
                && (attr.getAllFlags() & refAttr.getAllFlags()) == refAttr.getAllFlags()))
            && ((refFormattedTags.length() == 0) || refFormattedTags.equals(cliFormattedTags));
    }

    private static final class AudioAttributesGroup implements Parcelable {
        private int mVolumeGroupId;
        private int mLegacyStreamType;
        private final AudioAttributes[] mAudioAttributes;

        AudioAttributesGroup(int volumeGroupId, int streamType,
                @NonNull AudioAttributes[] audioAttributes) {
            mVolumeGroupId = volumeGroupId;
            mLegacyStreamType = streamType;
            mAudioAttributes = audioAttributes;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            AudioAttributesGroup thatAag = (AudioAttributesGroup) o;

            return mVolumeGroupId == thatAag.mVolumeGroupId
                    && mLegacyStreamType == thatAag.mLegacyStreamType
                    && mAudioAttributes.equals(thatAag.mAudioAttributes);
        }

        public int getStreamType() {
            return mLegacyStreamType;
        }

        public int getVolumeGroupId() {
            return mVolumeGroupId;
        }

        public @NonNull AudioAttributes getAudioAttributes() {
            // We need a choice, so take the first one
            return mAudioAttributes.length == 0 ? (new AudioAttributes.Builder().build())
                    : mAudioAttributes[0];
        }

        /**
         * Checks if a {@link AudioAttributes} is supported by this product strategy.
         * @param {@link AudioAttributes} to check upon support
         * @return true if the {@link AudioAttributes} follows this product strategy,
                   false otherwise.
         */
        public boolean supportsAttributes(@NonNull AudioAttributes attributes) {
            for (final AudioAttributes refAa : mAudioAttributes) {
                if (refAa.equals(attributes) || attributesMatches(refAa, attributes)) {
                    return true;
                }
            }
            return false;
        }

        public boolean supportsStreamType(int streamType) {
            return mLegacyStreamType == streamType;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mVolumeGroupId);
            dest.writeInt(mLegacyStreamType);
            dest.writeInt(mAudioAttributes.length);
            for (AudioAttributes attributes : mAudioAttributes) {
                attributes.writeToParcel(dest, flags | AudioAttributes.FLATTEN_TAGS/*flags*/);
            }
        }

        public static final @android.annotation.NonNull Parcelable.Creator<AudioAttributesGroup> CREATOR =
                new Parcelable.Creator<AudioAttributesGroup>() {
                    @Override
                    public AudioAttributesGroup createFromParcel(@NonNull Parcel in) {
                        int volumeGroupId = in.readInt();
                        int streamType = in.readInt();
                        int nbAttributes = in.readInt();
                        AudioAttributes[] aa = new AudioAttributes[nbAttributes];
                        for (int index = 0; index < nbAttributes; index++) {
                            aa[index] = AudioAttributes.CREATOR.createFromParcel(in);
                        }
                        return new AudioAttributesGroup(volumeGroupId, streamType, aa);
                    }

                    @Override
                    public @NonNull AudioAttributesGroup[] newArray(int size) {
                        return new AudioAttributesGroup[size];
                    }
                };


        @Override
        public @NonNull String toString() {
            StringBuilder s = new StringBuilder();
            s.append("\n    Legacy Stream Type: ");
            s.append(Integer.toString(mLegacyStreamType));
            s.append(" Volume Group Id: ");
            s.append(Integer.toString(mVolumeGroupId));

            for (AudioAttributes attribute : mAudioAttributes) {
                s.append("\n    -");
                s.append(attribute.toString());
            }
            return s.toString();
        }
    }
}
