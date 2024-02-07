/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.media.audiopolicy.Flags.FLAG_ENABLE_FADE_MANAGER_CONFIGURATION;

import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Class to encapsulate fade configurations.
 *
 * <p>Configurations are provided through:
 * <ul>
 *     <li>Fadeable list: a positive list of fadeable type - usage</li>
 *     <li>Unfadeable lists: negative list of unfadeable types - content type, uid, audio attributes
 *     </li>
 *     <li>Volume shaper configs: fade in and fade out configs per usage or audio attributes
 *     </li>
 * </ul>
 *
 * <p>Fade manager configuration can be created in one of the following ways:
 * <ul>
 *     <li>Disabled fades:
 *     <pre class="prettyprint">
 *         new FadeManagerConfiguration.Builder()
 *               .setFadeState(FADE_STATE_DISABLED).build()
 *               </pre>
 *     Can be used to disable fading</li>
 *     <li>Default configurations including default fade duration:
 *     <pre class="prettyprint">
 *         new FadeManagerConfiguration.Builder()
 *                .setFadeState(FADE_STATE_ENABLED_DEFAULT).build()
 *                </pre>
 *     Can be used to enable default fading configurations</li>
 *     <li>Default configurations with custom fade duration:
 *     <pre class="prettyprint">
 *         new FadeManagerConfiguration.Builder(fade out duration, fade in duration)
 *            .setFadeState(FADE_STATE_ENABLED_DEFAULT).build()
 *            </pre>
 *     Can be used to enable default fadeability lists with configurable fade in and out duration
 *     </li>
 *     <li>Custom configurations and fade volume shapers:
 *     <pre class="prettyprint">
 *         new FadeManagerConfiguration.Builder(fade out duration, fade in duration)
 *                .setFadeState(FADE_STATE_ENABLED_DEFAULT)
 *                .setFadeableUsages(list of usages)
 *                .setUnfadeableContentTypes(list of content types)
 *                .setUnfadeableUids(list of uids)
 *                .setUnfadeableAudioAttributes(list of audio attributes)
 *                .setFadeOutVolumeShaperConfigForAudioAttributes(attributes, volume shaper config)
 *                .setFadeInDurationForUsaeg(usage, duration)
 *                ....
 *                .build() </pre>
 *      Achieves full customization of fadeability lists and configurations</li>
 *      <li>Also provides a copy constructor from another instance of fade manager configuration
 *      <pre class="prettyprint">
 *          new FadeManagerConfiguration.Builder(fadeManagerConfiguration)
 *                 .addFadeableUsage(new usage)
 *                 ....
 *                 .build()</pre>
 *      Helps with recreating a new instance from another to simply change/add on top of the
 *      existing ones</li>
 * </ul>
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION)
public final class FadeManagerConfiguration implements Parcelable {

    public static final String TAG = "FadeManagerConfiguration";

    /**
     * Defines the disabled fade state. No player will be faded in this state.
     */
    public static final int FADE_STATE_DISABLED = 0;

    /**
     * Defines the enabled fade state with default configurations
     */
    public static final int FADE_STATE_ENABLED_DEFAULT = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "FADE_STATE", value = {
            FADE_STATE_DISABLED,
            FADE_STATE_ENABLED_DEFAULT,
    })
    public @interface FadeStateEnum {}

    /**
     * Defines ID to be used in volume shaper for fading
     */
    public static final int VOLUME_SHAPER_SYSTEM_FADE_ID = 2;

    /**
     * Used to reset duration or return duration when not set
     *
     * @see Builder#setFadeOutDurationForUsage(int, long)
     * @see Builder#setFadeInDurationForUsage(int, long)
     * @see Builder#setFadeOutDurationForAudioAttributes(AudioAttributes, long)
     * @see Builder#setFadeInDurationForAudioAttributes(AudioAttributes, long)
     * @see #getFadeOutDurationForUsage(int)
     * @see #getFadeInDurationForUsage(int)
     * @see #getFadeOutDurationForAudioAttributes(AudioAttributes)
     * @see #getFadeInDurationForAudioAttributes(AudioAttributes)
     */
    public static final @DurationMillisLong long DURATION_NOT_SET = 0;

    /** Defines the default fade out duration */
    private static final @DurationMillisLong long DEFAULT_FADE_OUT_DURATION_MS = 2_000;

    /** Defines the default fade in duration */
    private static final @DurationMillisLong long DEFAULT_FADE_IN_DURATION_MS = 1_000;

    /** Map of Usage to Fade volume shaper configs wrapper */
    private final SparseArray<FadeVolumeShaperConfigsWrapper> mUsageToFadeWrapperMap;
    /** Map of AudioAttributes to Fade volume shaper configs wrapper */
    private final ArrayMap<AudioAttributes, FadeVolumeShaperConfigsWrapper> mAttrToFadeWrapperMap;
    /** list of fadeable usages */
    private final @NonNull IntArray mFadeableUsages;
    /** list of unfadeable content types */
    private final @NonNull IntArray mUnfadeableContentTypes;
    /** list of unfadeable player types */
    private final @NonNull IntArray mUnfadeablePlayerTypes;
    /** list of unfadeable uid(s) */
    private final @NonNull IntArray mUnfadeableUids;
    /** list of unfadeable AudioAttributes */
    private final @NonNull List<AudioAttributes> mUnfadeableAudioAttributes;
    /** fade state */
    private final @FadeStateEnum int mFadeState;
    /** fade out duration from builder - used for creating default fade out volume shaper */
    private final @DurationMillisLong long mFadeOutDurationMillis;
    /** fade in duration from builder - used for creating default fade in volume shaper */
    private final @DurationMillisLong long mFadeInDurationMillis;
    /** delay after which the offending players are faded back in */
    private final @DurationMillisLong long mFadeInDelayForOffendersMillis;

    private FadeManagerConfiguration(int fadeState, @DurationMillisLong long fadeOutDurationMillis,
            @DurationMillisLong long fadeInDurationMillis,
            @DurationMillisLong long offendersFadeInDelayMillis,
            @NonNull SparseArray<FadeVolumeShaperConfigsWrapper> usageToFadeWrapperMap,
            @NonNull ArrayMap<AudioAttributes, FadeVolumeShaperConfigsWrapper> attrToFadeWrapperMap,
            @NonNull IntArray fadeableUsages, @NonNull IntArray unfadeableContentTypes,
            @NonNull IntArray unfadeablePlayerTypes, @NonNull IntArray unfadeableUids,
            @NonNull List<AudioAttributes> unfadeableAudioAttributes) {
        mFadeState = fadeState;
        mFadeOutDurationMillis = fadeOutDurationMillis;
        mFadeInDurationMillis = fadeInDurationMillis;
        mFadeInDelayForOffendersMillis = offendersFadeInDelayMillis;
        mUsageToFadeWrapperMap = Objects.requireNonNull(usageToFadeWrapperMap,
                "Usage to fade wrapper map cannot be null");
        mAttrToFadeWrapperMap = Objects.requireNonNull(attrToFadeWrapperMap,
                "Attribute to fade wrapper map cannot be null");
        mFadeableUsages = Objects.requireNonNull(fadeableUsages,
                "List of fadeable usages cannot be null");
        mUnfadeableContentTypes = Objects.requireNonNull(unfadeableContentTypes,
                "List of unfadeable content types cannot be null");
        mUnfadeablePlayerTypes = Objects.requireNonNull(unfadeablePlayerTypes,
                "List of unfadeable player types cannot be null");
        mUnfadeableUids = Objects.requireNonNull(unfadeableUids,
                "List of unfadeable uids cannot be null");
        mUnfadeableAudioAttributes = Objects.requireNonNull(unfadeableAudioAttributes,
                "List of unfadeable audio attributes cannot be null");
    }

    /**
     * Get the fade state
     */
    @FadeStateEnum
    public int getFadeState() {
        return mFadeState;
    }

    /**
     * Get the list of usages that can be faded
     *
     * @return list of {@link android.media.AudioAttributes usages} that shall be faded
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @NonNull
    public List<Integer> getFadeableUsages() {
        ensureFadingIsEnabled();
        return convertIntArrayToIntegerList(mFadeableUsages);
    }

    /**
     * Get the list of {@link android.media.AudioPlaybackConfiguration player types} that can be
     * faded
     *
     * @return list of {@link android.media.AudioPlaybackConfiguration player types}
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @NonNull
    public List<Integer> getUnfadeablePlayerTypes() {
        ensureFadingIsEnabled();
        return convertIntArrayToIntegerList(mUnfadeablePlayerTypes);
    }

    /**
     * Get the list of {@link android.media.AudioAttributes content types} that can be faded
     *
     * @return list of {@link android.media.AudioAttributes content types}
     * @throws IllegalStateExceptionif if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @NonNull
    public List<Integer> getUnfadeableContentTypes() {
        ensureFadingIsEnabled();
        return convertIntArrayToIntegerList(mUnfadeableContentTypes);
    }

    /**
     * Get the list of uids that cannot be faded
     *
     * @return list of uids that shall not be faded
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @NonNull
    public List<Integer> getUnfadeableUids() {
        ensureFadingIsEnabled();
        return convertIntArrayToIntegerList(mUnfadeableUids);
    }

    /**
     * Get the list of {@link android.media.AudioAttributes} that cannot be faded
     *
     * @return list of {@link android.media.AudioAttributes} that shall not be faded
     * @throws IllegalStateException if fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @NonNull
    public List<AudioAttributes> getUnfadeableAudioAttributes() {
        ensureFadingIsEnabled();
        return mUnfadeableAudioAttributes;
    }

    /**
     * Get the duration used to fade out players with {@link android.media.AudioAttributes usage}
     *
     * @param usage the {@link android.media.AudioAttributes usage}
     * @return duration in milliseconds if set for the usage or {@link #DURATION_NOT_SET} otherwise
     * @throws IllegalArgumentException if the usage is invalid
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @DurationMillisLong
    public long getFadeOutDurationForUsage(@AudioAttributes.AttributeUsage int usage) {
        ensureFadingIsEnabled();
        validateUsage(usage);
        return getDurationForVolumeShaperConfig(getVolumeShaperConfigFromWrapper(
                mUsageToFadeWrapperMap.get(usage), /* isFadeIn= */ false));
    }

    /**
     * Get the duration used to fade in players with {@link android.media.AudioAttributes usage}
     *
     * @param usage the {@link android.media.AudioAttributes usage}
     * @return duration in milliseconds if set for the usage or {@link #DURATION_NOT_SET} otherwise
     * @throws IllegalArgumentException if the usage is invalid
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @DurationMillisLong
    public long getFadeInDurationForUsage(@AudioAttributes.AttributeUsage int usage) {
        ensureFadingIsEnabled();
        validateUsage(usage);
        return getDurationForVolumeShaperConfig(getVolumeShaperConfigFromWrapper(
                mUsageToFadeWrapperMap.get(usage), /* isFadeIn= */ true));
    }

    /**
     * Get the {@link android.media.VolumeShaper.Configuration} used to fade out players with
     * {@link android.media.AudioAttributes usage}
     *
     * @param usage the {@link android.media.AudioAttributes usage}
     * @return {@link android.media.VolumeShaper.Configuration} if set for the usage or
     *     {@code null} otherwise
     * @throws IllegalArgumentException if the usage is invalid
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @Nullable
    public VolumeShaper.Configuration getFadeOutVolumeShaperConfigForUsage(
            @AudioAttributes.AttributeUsage int usage) {
        ensureFadingIsEnabled();
        validateUsage(usage);
        return getVolumeShaperConfigFromWrapper(mUsageToFadeWrapperMap.get(usage),
                /* isFadeIn= */ false);
    }

    /**
     * Get the {@link android.media.VolumeShaper.Configuration} used to fade in players with
     * {@link android.media.AudioAttributes usage}
     *
     * @param usage the {@link android.media.AudioAttributes usage}
     * @return {@link android.media.VolumeShaper.Configuration} if set for the usage or
     *     {@code null} otherwise
     * @throws IllegalArgumentException if the usage is invalid
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @Nullable
    public VolumeShaper.Configuration getFadeInVolumeShaperConfigForUsage(
            @AudioAttributes.AttributeUsage int usage) {
        ensureFadingIsEnabled();
        validateUsage(usage);
        return getVolumeShaperConfigFromWrapper(mUsageToFadeWrapperMap.get(usage),
                /* isFadeIn= */ true);
    }

    /**
     * Get the duration used to fade out players with {@link android.media.AudioAttributes}
     *
     * @param audioAttributes {@link android.media.AudioAttributes}
     * @return duration in milliseconds if set for the audio attributes or
     *     {@link #DURATION_NOT_SET} otherwise
     * @throws NullPointerException if the audio attributes is {@code null}
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @DurationMillisLong
    public long getFadeOutDurationForAudioAttributes(@NonNull AudioAttributes audioAttributes) {
        ensureFadingIsEnabled();
        return getDurationForVolumeShaperConfig(getVolumeShaperConfigFromWrapper(
                mAttrToFadeWrapperMap.get(audioAttributes), /* isFadeIn= */ false));
    }

    /**
     * Get the duration used to fade-in players with {@link android.media.AudioAttributes}
     *
     * @param audioAttributes {@link android.media.AudioAttributes}
     * @return duration in milliseconds if set for the audio attributes or
     *     {@link #DURATION_NOT_SET} otherwise
     * @throws NullPointerException if the audio attributes is {@code null}
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @DurationMillisLong
    public long getFadeInDurationForAudioAttributes(@NonNull AudioAttributes audioAttributes) {
        ensureFadingIsEnabled();
        return getDurationForVolumeShaperConfig(getVolumeShaperConfigFromWrapper(
                mAttrToFadeWrapperMap.get(audioAttributes), /* isFadeIn= */ true));
    }

    /**
     * Get the {@link android.media.VolumeShaper.Configuration} used to fade out players with
     * {@link android.media.AudioAttributes}
     *
     * @param audioAttributes {@link android.media.AudioAttributes}
     * @return {@link android.media.VolumeShaper.Configuration} if set for the audio attribute or
     *     {@code null} otherwise
     * @throws NullPointerException if the audio attributes is {@code null}
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @Nullable
    public VolumeShaper.Configuration getFadeOutVolumeShaperConfigForAudioAttributes(
            @NonNull AudioAttributes audioAttributes) {
        Objects.requireNonNull(audioAttributes, "Audio attributes cannot be null");
        ensureFadingIsEnabled();
        return getVolumeShaperConfigFromWrapper(mAttrToFadeWrapperMap.get(audioAttributes),
                /* isFadeIn= */ false);
    }

    /**
     * Get the {@link android.media.VolumeShaper.Configuration} used to fade out players with
     * {@link android.media.AudioAttributes}
     *
     * @param audioAttributes {@link android.media.AudioAttributes}
     * @return {@link android.media.VolumeShaper.Configuration} used for fading in if set for the
     *     audio attribute or {@code null} otherwise
     * @throws NullPointerException if the audio attributes is {@code null}
     * @throws IllegalStateException if the fade state is set to {@link #FADE_STATE_DISABLED}
     */
    @Nullable
    public VolumeShaper.Configuration getFadeInVolumeShaperConfigForAudioAttributes(
            @NonNull AudioAttributes audioAttributes) {
        Objects.requireNonNull(audioAttributes, "Audio attributes cannot be null");
        ensureFadingIsEnabled();
        return getVolumeShaperConfigFromWrapper(mAttrToFadeWrapperMap.get(audioAttributes),
                /* isFadeIn= */ true);
    }

    /**
     * Get the list of {@link android.media.AudioAttributes} for whome the volume shaper
     * configurations are defined
     *
     * @return list of {@link android.media.AudioAttributes} with valid volume shaper configs or
     *     empty list if none set.
     */
    @NonNull
    public List<AudioAttributes> getAudioAttributesWithVolumeShaperConfigs() {
        return getAudioAttributesInternal();
    }

    /**
     * Get the delay after which the offending players are faded back in
     *
     * Players are categorized as offending if they do not honor audio focus state changes. For
     * example - when an app loses audio focus, it is expected that the app stops any active
     * player in favor of the app(s) that gained audio focus. However, if the app do not stop the
     * audio playback, such players are termed as offenders.
     *
     * @return delay in milliseconds
     */
    @DurationMillisLong
    public long getFadeInDelayForOffenders() {
        return mFadeInDelayForOffendersMillis;
    }

    /**
     * Query if fade is enabled
     *
     * @return {@code true} if fading is enabled, {@code false} otherwise
     */
    public boolean isFadeEnabled() {
        return mFadeState != FADE_STATE_DISABLED;
    }

    /**
     * Query if the usage is fadeable
     *
     * @param usage the {@link android.media.AudioAttributes usage}
     * @return {@code true} if usage is fadeable, {@code false}  when the fade state is set to
     *     {@link #FADE_STATE_DISABLED} or if the usage is not fadeable.
     */
    public boolean isUsageFadeable(@AudioAttributes.AttributeUsage int usage) {
        if (!isFadeEnabled()) {
            return false;
        }
        return mFadeableUsages.contains(usage);
    }

    /**
     * Query if the content type is unfadeable
     *
     * @param contentType the {@link android.media.AudioAttributes content type}
     * @return {@code true} if content type is unfadeable or if fade state is set to
     *     {@link #FADE_STATE_DISABLED}, {@code false} otherwise
     */
    public boolean isContentTypeUnfadeable(@AudioAttributes.AttributeContentType int contentType) {
        if (!isFadeEnabled()) {
            return true;
        }
        return mUnfadeableContentTypes.contains(contentType);
    }

    /**
     * Query if the player type is unfadeable
     *
     * @param playerType the {@link android.media.AudioPlaybackConfiguration player type}
     * @return {@code true} if player type is unfadeable or if fade state is set to
     *     {@link #FADE_STATE_DISABLED}, {@code false} otherwise
     */
    public boolean isPlayerTypeUnfadeable(@AudioPlaybackConfiguration.PlayerType int playerType) {
        if (!isFadeEnabled()) {
            return true;
        }
        return mUnfadeablePlayerTypes.contains(playerType);
    }

    /**
     * Query if the audio attributes is unfadeable
     *
     * @param audioAttributes the {@link android.media.AudioAttributes}
     * @return {@code true} if audio attributes is unfadeable or if fade state is set to
     *     {@link #FADE_STATE_DISABLED}, {@code false} otherwise
     * @throws NullPointerException if the audio attributes is {@code null}
     */
    public boolean isAudioAttributesUnfadeable(@NonNull AudioAttributes audioAttributes) {
        Objects.requireNonNull(audioAttributes, "Audio attributes cannot be null");
        if (!isFadeEnabled()) {
            return true;
        }
        return mUnfadeableAudioAttributes.contains(audioAttributes);
    }

    /**
     * Query if the uid is unfadeable
     *
     * @param uid the uid of application
     * @return {@code true} if uid is unfadeable or if fade state is set to
     *     {@link #FADE_STATE_DISABLED}, {@code false} otherwise
     */
    public boolean isUidUnfadeable(int uid) {
        if (!isFadeEnabled()) {
            return true;
        }
        return mUnfadeableUids.contains(uid);
    }

    /**
     * Returns the default fade out duration (in milliseconds)
     */
    public static @DurationMillisLong long getDefaultFadeOutDurationMillis() {
        return DEFAULT_FADE_OUT_DURATION_MS;
    }

    /**
     * Returns the default fade in duration (in milliseconds)
     */
    public static @DurationMillisLong long getDefaultFadeInDurationMillis() {
        return DEFAULT_FADE_IN_DURATION_MS;
    }

    @Override
    public String toString() {
        return "FadeManagerConfiguration { fade state = " + fadeStateToString(mFadeState)
                + ", fade out duration = " + mFadeOutDurationMillis
                + ", fade in duration = " + mFadeInDurationMillis
                + ", offenders fade in delay = " + mFadeInDelayForOffendersMillis
                + ", fade volume shapers for audio attributes = " + mAttrToFadeWrapperMap
                + ", fadeable usages = " + mFadeableUsages.toString()
                + ", unfadeable content types = " + mUnfadeableContentTypes.toString()
                + ", unfadeable player types = " + mUnfadeablePlayerTypes.toString()
                + ", unfadeable uids = " + mUnfadeableUids.toString()
                + ", unfadeable audio attributes = " + mUnfadeableAudioAttributes + "}";
    }

    /**
     * Convert fade state into a human-readable string
     *
     * @param fadeState one of {@link #FADE_STATE_DISABLED} or {@link #FADE_STATE_ENABLED_DEFAULT}
     * @return human-readable string
     * @hide
     */
    @NonNull
    public static String fadeStateToString(@FadeStateEnum int fadeState) {
        switch (fadeState) {
            case FADE_STATE_DISABLED:
                return "FADE_STATE_DISABLED";
            case FADE_STATE_ENABLED_DEFAULT:
                return "FADE_STATE_ENABLED_DEFAULT";
            default:
                return "unknown fade state: " + fadeState;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof FadeManagerConfiguration)) {
            return false;
        }

        FadeManagerConfiguration rhs = (FadeManagerConfiguration) o;

        return mUsageToFadeWrapperMap.contentEquals(rhs.mUsageToFadeWrapperMap)
                && mAttrToFadeWrapperMap.equals(rhs.mAttrToFadeWrapperMap)
                && Arrays.equals(mFadeableUsages.toArray(), rhs.mFadeableUsages.toArray())
                && Arrays.equals(mUnfadeableContentTypes.toArray(),
                rhs.mUnfadeableContentTypes.toArray())
                && Arrays.equals(mUnfadeablePlayerTypes.toArray(),
                rhs.mUnfadeablePlayerTypes.toArray())
                && Arrays.equals(mUnfadeableUids.toArray(), rhs.mUnfadeableUids.toArray())
                && mUnfadeableAudioAttributes.equals(rhs.mUnfadeableAudioAttributes)
                && mFadeState == rhs.mFadeState
                && mFadeOutDurationMillis == rhs.mFadeOutDurationMillis
                && mFadeInDurationMillis == rhs.mFadeInDurationMillis
                && mFadeInDelayForOffendersMillis == rhs.mFadeInDelayForOffendersMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUsageToFadeWrapperMap, mAttrToFadeWrapperMap, mFadeableUsages,
                mUnfadeableContentTypes, mUnfadeablePlayerTypes, mUnfadeableAudioAttributes,
                mUnfadeableUids, mFadeState, mFadeOutDurationMillis, mFadeInDurationMillis,
                mFadeInDelayForOffendersMillis);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mFadeState);
        dest.writeLong(mFadeOutDurationMillis);
        dest.writeLong(mFadeInDurationMillis);
        dest.writeLong(mFadeInDelayForOffendersMillis);
        dest.writeTypedSparseArray(mUsageToFadeWrapperMap, flags);
        dest.writeMap(mAttrToFadeWrapperMap);
        dest.writeIntArray(mFadeableUsages.toArray());
        dest.writeIntArray(mUnfadeableContentTypes.toArray());
        dest.writeIntArray(mUnfadeablePlayerTypes.toArray());
        dest.writeIntArray(mUnfadeableUids.toArray());
        dest.writeTypedList(mUnfadeableAudioAttributes, flags);
    }

    /**
     * Creates fade manage configuration from parcel
     *
     * @hide
     */
    @VisibleForTesting()
    FadeManagerConfiguration(Parcel in) {
        int fadeState = in.readInt();
        long fadeOutDurationMillis = in.readLong();
        long fadeInDurationMillis = in.readLong();
        long fadeInDelayForOffenders = in.readLong();
        SparseArray<FadeVolumeShaperConfigsWrapper> usageToWrapperMap =
                in.createTypedSparseArray(FadeVolumeShaperConfigsWrapper.CREATOR);
        ArrayMap<AudioAttributes, FadeVolumeShaperConfigsWrapper> attrToFadeWrapperMap =
                new ArrayMap<>();
        in.readMap(attrToFadeWrapperMap, getClass().getClassLoader(), AudioAttributes.class,
                FadeVolumeShaperConfigsWrapper.class);
        int[] fadeableUsages = in.createIntArray();
        int[] unfadeableContentTypes = in.createIntArray();
        int[] unfadeablePlayerTypes = in.createIntArray();
        int[] unfadeableUids = in.createIntArray();
        List<AudioAttributes> unfadeableAudioAttributes = new ArrayList<>();
        in.readTypedList(unfadeableAudioAttributes, AudioAttributes.CREATOR);

        this.mFadeState = fadeState;
        this.mFadeOutDurationMillis = fadeOutDurationMillis;
        this.mFadeInDurationMillis = fadeInDurationMillis;
        this.mFadeInDelayForOffendersMillis = fadeInDelayForOffenders;
        this.mUsageToFadeWrapperMap = usageToWrapperMap;
        this.mAttrToFadeWrapperMap = attrToFadeWrapperMap;
        this.mFadeableUsages = IntArray.wrap(fadeableUsages);
        this.mUnfadeableContentTypes = IntArray.wrap(unfadeableContentTypes);
        this.mUnfadeablePlayerTypes = IntArray.wrap(unfadeablePlayerTypes);
        this.mUnfadeableUids = IntArray.wrap(unfadeableUids);
        this.mUnfadeableAudioAttributes = unfadeableAudioAttributes;
    }

    @NonNull
    public static final Creator<FadeManagerConfiguration> CREATOR = new Creator<>() {
        @Override
        @NonNull
        public FadeManagerConfiguration createFromParcel(@NonNull Parcel in) {
            return new FadeManagerConfiguration(in);
        }

        @Override
        @NonNull
        public FadeManagerConfiguration[] newArray(int size) {
            return new FadeManagerConfiguration[size];
        }
    };

    private long getDurationForVolumeShaperConfig(VolumeShaper.Configuration config) {
        return config != null ? config.getDuration() : DURATION_NOT_SET;
    }

    private VolumeShaper.Configuration getVolumeShaperConfigFromWrapper(
            FadeVolumeShaperConfigsWrapper wrapper, boolean isFadeIn) {
        // if no volume shaper config is available, return null
        if (wrapper == null) {
            return null;
        }
        if (isFadeIn) {
            return wrapper.getFadeInVolShaperConfig();
        }
        return wrapper.getFadeOutVolShaperConfig();
    }

    private List<AudioAttributes> getAudioAttributesInternal() {
        List<AudioAttributes> attrs = new ArrayList<>(mAttrToFadeWrapperMap.size());
        for (int index = 0; index < mAttrToFadeWrapperMap.size(); index++) {
            attrs.add(mAttrToFadeWrapperMap.keyAt(index));
        }
        return attrs;
    }

    private static boolean isUsageValid(int usage) {
        return AudioAttributes.isSdkUsage(usage) || AudioAttributes.isSystemUsage(usage);
    }

    private void ensureFadingIsEnabled() {
        if (!isFadeEnabled()) {
            throw new IllegalStateException("Method call not allowed when fade is disabled");
        }
    }

    private static void validateUsage(int usage) {
        Preconditions.checkArgument(isUsageValid(usage), "Invalid usage: %s", usage);
    }

    private static IntArray convertIntegerListToIntArray(List<Integer> integerList) {
        if (integerList == null) {
            return new IntArray();
        }

        IntArray intArray = new IntArray(integerList.size());
        for (int index = 0; index < integerList.size(); index++) {
            intArray.add(integerList.get(index));
        }
        return intArray;
    }

    private static List<Integer> convertIntArrayToIntegerList(IntArray intArray) {
        if (intArray == null) {
            return new ArrayList<>();
        }

        ArrayList<Integer> integerArrayList = new ArrayList<>(intArray.size());
        for (int index = 0; index < intArray.size(); index++) {
            integerArrayList.add(intArray.get(index));
        }
        return integerArrayList;
    }

    /**
     * Builder class for {@link FadeManagerConfiguration} objects.
     *
     * <p><b>Notes:</b>
     * <ul>
     *     <li>When fade state is set to {@link #FADE_STATE_ENABLED_DEFAULT}, the builder expects at
     *     least one valid usage to be set/added. Failure to do so will result in an exception
     *     during {@link #build()}</li>
     *     <li>Every usage added to the fadeable list should have corresponding volume shaper
     *     configs defined. This can be achieved by setting either the duration or volume shaper
     *     config through {@link #setFadeOutDurationForUsage(int, long)} or
     *     {@link #setFadeOutVolumeShaperConfigForUsage(int, VolumeShaper.Configuration)}</li>
     *     <li> It is recommended to set volume shaper configurations individually for fade out and
     *     fade in</li>
     *     <li>For any incomplete volume shaper configurations, a volume shaper configuration will
     *     be created using either the default fade durations or the ones provided as part of the
     *     {@link #Builder(long, long)}</li>
     *     <li>Additional volume shaper configs can also configured for a given usage
     *     with additional attributes like content-type in order to achieve finer fade controls.
     *     See:
     *     {@link #setFadeOutVolumeShaperConfigForAudioAttributes(AudioAttributes,
     *     VolumeShaper.Configuration)} and
     *     {@link #setFadeInVolumeShaperConfigForAudioAttributes(AudioAttributes,
     *     VolumeShaper.Configuration)} </li>
     *     </ul>
     *
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {
        private static final int INVALID_INDEX = -1;
        private static final long IS_BUILDER_USED_FIELD_SET = 1 << 0;
        private static final long IS_FADEABLE_USAGES_FIELD_SET = 1 << 1;
        private static final long IS_UNFADEABLE_CONTENT_TYPE_FIELD_SET = 1 << 2;

        /**
         * delay after which a faded out player will be faded back in. This will be heard by the
         * user only in the case of unmuting players that didn't respect audio focus and didn't
         * stop/pause when their app lost focus.
         * This is the amount of time between the app being notified of the focus loss
         * (when its muted by the fade out), and the time fade in (to unmute) starts
         */
        private static final long DEFAULT_DELAY_FADE_IN_OFFENDERS_MS = 2_000;


        private static final IntArray DEFAULT_UNFADEABLE_PLAYER_TYPES = IntArray.wrap(new int[]{
                AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO,
                AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL
        });

        private static final IntArray DEFAULT_UNFADEABLE_CONTENT_TYPES = IntArray.wrap(new int[]{
                AudioAttributes.CONTENT_TYPE_SPEECH
        });

        private static final IntArray DEFAULT_FADEABLE_USAGES = IntArray.wrap(new int[]{
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_MEDIA
        });

        private int mFadeState = FADE_STATE_ENABLED_DEFAULT;
        private @DurationMillisLong long mFadeInDelayForOffendersMillis =
                DEFAULT_DELAY_FADE_IN_OFFENDERS_MS;
        private @DurationMillisLong long mFadeOutDurationMillis;
        private @DurationMillisLong long mFadeInDurationMillis;
        private long mBuilderFieldsSet;
        private SparseArray<FadeVolumeShaperConfigsWrapper> mUsageToFadeWrapperMap =
                new SparseArray<>();
        private ArrayMap<AudioAttributes, FadeVolumeShaperConfigsWrapper> mAttrToFadeWrapperMap =
                new ArrayMap<>();
        private IntArray mFadeableUsages = new IntArray();
        private IntArray mUnfadeableContentTypes = new IntArray();
        // Player types are not yet configurable
        private IntArray mUnfadeablePlayerTypes = DEFAULT_UNFADEABLE_PLAYER_TYPES;
        private IntArray mUnfadeableUids = new IntArray();
        private List<AudioAttributes> mUnfadeableAudioAttributes = new ArrayList<>();

        /**
         * Constructs a new Builder with {@link #DEFAULT_FADE_OUT_DURATION_MS} and
         * {@link #DEFAULT_FADE_IN_DURATION_MS} durations.
         */
        public Builder() {
            mFadeOutDurationMillis = DEFAULT_FADE_OUT_DURATION_MS;
            mFadeInDurationMillis = DEFAULT_FADE_IN_DURATION_MS;
        }

        /**
         * Constructs a new Builder with the provided fade out and fade in durations
         *
         * @param fadeOutDurationMillis duration in milliseconds used for fading out
         * @param fadeInDurationMills duration in milliseconds used for fading in
         */
        public Builder(@DurationMillisLong long fadeOutDurationMillis,
                @DurationMillisLong long fadeInDurationMills) {
            mFadeOutDurationMillis = fadeOutDurationMillis;
            mFadeInDurationMillis = fadeInDurationMills;
        }

        /**
         * Constructs a new Builder from the given {@link FadeManagerConfiguration}
         *
         * @param fmc the {@link FadeManagerConfiguration} object whose data will be reused in the
         *            new builder
         */
        public Builder(@NonNull FadeManagerConfiguration fmc) {
            mFadeState = fmc.mFadeState;
            mUsageToFadeWrapperMap = fmc.mUsageToFadeWrapperMap.clone();
            mAttrToFadeWrapperMap = new ArrayMap<AudioAttributes, FadeVolumeShaperConfigsWrapper>(
                    fmc.mAttrToFadeWrapperMap);
            mFadeableUsages = fmc.mFadeableUsages.clone();
            setFlag(IS_FADEABLE_USAGES_FIELD_SET);
            mUnfadeableContentTypes = fmc.mUnfadeableContentTypes.clone();
            setFlag(IS_UNFADEABLE_CONTENT_TYPE_FIELD_SET);
            mUnfadeablePlayerTypes = fmc.mUnfadeablePlayerTypes.clone();
            mUnfadeableUids = fmc.mUnfadeableUids.clone();
            mUnfadeableAudioAttributes = new ArrayList<>(fmc.mUnfadeableAudioAttributes);
            mFadeOutDurationMillis = fmc.mFadeOutDurationMillis;
            mFadeInDurationMillis = fmc.mFadeInDurationMillis;
        }

        /**
         * Set the overall fade state
         *
         * @param state one of the {@link #FADE_STATE_DISABLED} or
         *     {@link #FADE_STATE_ENABLED_DEFAULT} states
         * @return the same Builder instance
         * @throws IllegalArgumentException if the fade state is invalid
         * @see #getFadeState()
         */
        @NonNull
        public Builder setFadeState(@FadeStateEnum int state) {
            validateFadeState(state);
            mFadeState = state;
            return this;
        }

        /**
         * Set the {@link android.media.VolumeShaper.Configuration} used to fade out players with
         * {@link android.media.AudioAttributes usage}
         * <p>
         * This method accepts {@code null} for volume shaper config to clear a previously set
         * configuration (example, if set through
         * {@link #Builder(android.media.FadeManagerConfiguration)})
         *
         * @param usage the {@link android.media.AudioAttributes usage} of target player
         * @param fadeOutVShaperConfig the {@link android.media.VolumeShaper.Configuration} used
         *     to fade out players with usage
         * @return the same Builder instance
         * @throws IllegalArgumentException if the usage is invalid
         * @see #getFadeOutVolumeShaperConfigForUsage(int)
         */
        @NonNull
        public Builder setFadeOutVolumeShaperConfigForUsage(
                @AudioAttributes.AttributeUsage int usage,
                @Nullable VolumeShaper.Configuration fadeOutVShaperConfig) {
            validateUsage(usage);
            getFadeVolShaperConfigWrapperForUsage(usage)
                    .setFadeOutVolShaperConfig(fadeOutVShaperConfig);
            cleanupInactiveWrapperEntries(usage);
            return this;
        }

        /**
         * Set the {@link android.media.VolumeShaper.Configuration} used to fade in players with
         * {@link android.media.AudioAttributes usage}
         * <p>
         * This method accepts {@code null} for volume shaper config to clear a previously set
         * configuration (example, if set through
         * {@link #Builder(android.media.FadeManagerConfiguration)})
         *
         * @param usage the {@link android.media.AudioAttributes usage}
         * @param fadeInVShaperConfig the {@link android.media.VolumeShaper.Configuration} used
         *     to fade in players with usage
         * @return the same Builder instance
         * @throws IllegalArgumentException if the usage is invalid
         * @see #getFadeInVolumeShaperConfigForUsage(int)
         */
        @NonNull
        public Builder setFadeInVolumeShaperConfigForUsage(
                @AudioAttributes.AttributeUsage int usage,
                @Nullable VolumeShaper.Configuration fadeInVShaperConfig) {
            validateUsage(usage);
            getFadeVolShaperConfigWrapperForUsage(usage)
                    .setFadeInVolShaperConfig(fadeInVShaperConfig);
            cleanupInactiveWrapperEntries(usage);
            return this;
        }

        /**
         * Set the duration used for fading out players with
         * {@link android.media.AudioAttributes usage}
         * <p>
         * A Volume shaper configuration is generated with the provided duration and default
         * volume curve definitions. This config is then used to fade out players with given usage.
         * <p>
         * In order to clear previously set duration (example, if set through
         * {@link #Builder(android.media.FadeManagerConfiguration)}), this method accepts
         * {@link #DURATION_NOT_SET} and sets the corresponding fade out volume shaper config to
         * {@code null}
         *
         * @param usage the {@link android.media.AudioAttributes usage} of target player
         * @param fadeOutDurationMillis positive duration in milliseconds or
         *     {@link #DURATION_NOT_SET}
         * @return the same Builder instance
         * @throws IllegalArgumentException if the fade out duration is non-positive with the
         *     exception of {@link #DURATION_NOT_SET}
         * @see #setFadeOutVolumeShaperConfigForUsage(int, VolumeShaper.Configuration)
         * @see #getFadeOutDurationForUsage(int)
         */
        @NonNull
        public Builder setFadeOutDurationForUsage(@AudioAttributes.AttributeUsage int usage,
                @DurationMillisLong long fadeOutDurationMillis) {
            validateUsage(usage);
            VolumeShaper.Configuration fadeOutVShaperConfig =
                    createVolShaperConfigForDuration(fadeOutDurationMillis, /* isFadeIn= */ false);
            setFadeOutVolumeShaperConfigForUsage(usage, fadeOutVShaperConfig);
            return this;
        }

        /**
         * Set the duration used for fading in players with
         * {@link android.media.AudioAttributes usage}
         * <p>
         * A Volume shaper configuration is generated with the provided duration and default
         * volume curve definitions. This config is then used to fade in players with given usage.
         * <p>
         * <b>Note: </b>In order to clear previously set duration (example, if set through
         * {@link #Builder(android.media.FadeManagerConfiguration)}), this method accepts
         * {@link #DURATION_NOT_SET} and sets the corresponding fade in volume shaper config to
         * {@code null}
         *
         * @param usage the {@link android.media.AudioAttributes usage} of target player
         * @param fadeInDurationMillis positive duration in milliseconds or
         *     {@link #DURATION_NOT_SET}
         * @return the same Builder instance
         * @throws IllegalArgumentException if the fade in duration is non-positive with the
         *     exception of {@link #DURATION_NOT_SET}
         * @see #setFadeInVolumeShaperConfigForUsage(int, VolumeShaper.Configuration)
         * @see #getFadeInDurationForUsage(int)
         */
        @NonNull
        public Builder setFadeInDurationForUsage(@AudioAttributes.AttributeUsage int usage,
                @DurationMillisLong long fadeInDurationMillis) {
            validateUsage(usage);
            VolumeShaper.Configuration fadeInVShaperConfig =
                    createVolShaperConfigForDuration(fadeInDurationMillis, /* isFadeIn= */ true);
            setFadeInVolumeShaperConfigForUsage(usage, fadeInVShaperConfig);
            return this;
        }

        /**
         * Set the {@link android.media.VolumeShaper.Configuration} used to fade out players with
         * {@link android.media.AudioAttributes}
         * <p>
         * This method accepts {@code null} for volume shaper config to clear a previously set
         * configuration (example, set through
         * {@link #Builder(android.media.FadeManagerConfiguration)})
         *
         * @param audioAttributes the {@link android.media.AudioAttributes}
         * @param fadeOutVShaperConfig the {@link android.media.VolumeShaper.Configuration} used to
         *     fade out players with audio attribute
         * @return the same Builder instance
         * @see #getFadeOutVolumeShaperConfigForAudioAttributes(AudioAttributes)
         */
        @NonNull
        public Builder setFadeOutVolumeShaperConfigForAudioAttributes(
                @NonNull AudioAttributes audioAttributes,
                @Nullable VolumeShaper.Configuration fadeOutVShaperConfig) {
            Objects.requireNonNull(audioAttributes, "Audio attribute cannot be null");
            getFadeVolShaperConfigWrapperForAttr(audioAttributes)
                    .setFadeOutVolShaperConfig(fadeOutVShaperConfig);
            cleanupInactiveWrapperEntries(audioAttributes);
            return this;
        }

        /**
         * Set the {@link android.media.VolumeShaper.Configuration} used to fade in players with
         * {@link android.media.AudioAttributes}
         *
         * <p>This method accepts {@code null} for volume shaper config to clear a previously set
         * configuration (example, set through
         * {@link #Builder(android.media.FadeManagerConfiguration)})
         *
         * @param audioAttributes the {@link android.media.AudioAttributes}
         * @param fadeInVShaperConfig the {@link android.media.VolumeShaper.Configuration} used to
         *     fade in players with audio attribute
         * @return the same Builder instance
         * @throws NullPointerException if the audio attributes is {@code null}
         * @see #getFadeInVolumeShaperConfigForAudioAttributes(AudioAttributes)
         */
        @NonNull
        public Builder setFadeInVolumeShaperConfigForAudioAttributes(
                @NonNull AudioAttributes audioAttributes,
                @Nullable VolumeShaper.Configuration fadeInVShaperConfig) {
            Objects.requireNonNull(audioAttributes, "Audio attribute cannot be null");
            getFadeVolShaperConfigWrapperForAttr(audioAttributes)
                    .setFadeInVolShaperConfig(fadeInVShaperConfig);
            cleanupInactiveWrapperEntries(audioAttributes);
            return this;
        }

        /**
         * Set the duration used for fading out players of type
         * {@link android.media.AudioAttributes}.
         * <p>
         * A Volume shaper configuration is generated with the provided duration and default
         * volume curve definitions. This config is then used to fade out players with given usage.
         * <p>
         * <b>Note: </b>In order to clear previously set duration (example, if set through
         * {@link #Builder(android.media.FadeManagerConfiguration)}), this method accepts
         * {@link #DURATION_NOT_SET} and sets the corresponding fade out volume shaper config to
         * {@code null}
         *
         * @param audioAttributes the {@link android.media.AudioAttributes} for which the fade out
         *     duration will be set/updated/reset
         * @param fadeOutDurationMillis positive duration in milliseconds or
         *     {@link #DURATION_NOT_SET}
         * @return the same Builder instance
         * @throws IllegalArgumentException if the fade out duration is non-positive with the
         *     exception of {@link #DURATION_NOT_SET}
         * @see #getFadeOutDurationForAudioAttributes(AudioAttributes)
         * @see #setFadeOutVolumeShaperConfigForAudioAttributes(AudioAttributes,
         * VolumeShaper.Configuration)
         */
        @NonNull
        public Builder setFadeOutDurationForAudioAttributes(
                @NonNull AudioAttributes audioAttributes,
                @DurationMillisLong long fadeOutDurationMillis) {
            Objects.requireNonNull(audioAttributes, "Audio attribute cannot be null");
            VolumeShaper.Configuration fadeOutVShaperConfig =
                    createVolShaperConfigForDuration(fadeOutDurationMillis, /* isFadeIn= */ false);
            setFadeOutVolumeShaperConfigForAudioAttributes(audioAttributes, fadeOutVShaperConfig);
            return this;
        }

        /**
         * Set the duration used for fading in players of type {@link android.media.AudioAttributes}
         * <p>
         * A Volume shaper configuration is generated with the provided duration and default
         * volume curve definitions. This config is then used to fade in players with given usage.
         * <p>
         * <b>Note: </b>In order to clear previously set duration (example, if set through
         * {@link #Builder(android.media.FadeManagerConfiguration)}), this method accepts
         * {@link #DURATION_NOT_SET} and sets the corresponding fade in volume shaper config to
         * {@code null}
         *
         * @param audioAttributes the {@link android.media.AudioAttributes} for which the fade in
         *     duration will be set/updated/reset
         * @param fadeInDurationMillis positive duration in milliseconds or
         *     {@link #DURATION_NOT_SET}
         * @return the same Builder instance
         * @throws IllegalArgumentException if the fade in duration is non-positive with the
         *     exception of {@link #DURATION_NOT_SET}
         * @see #getFadeInDurationForAudioAttributes(AudioAttributes)
         * @see #setFadeInVolumeShaperConfigForAudioAttributes(AudioAttributes,
         * VolumeShaper.Configuration)
         */
        @NonNull
        public Builder setFadeInDurationForAudioAttributes(@NonNull AudioAttributes audioAttributes,
                @DurationMillisLong long fadeInDurationMillis) {
            Objects.requireNonNull(audioAttributes, "Audio attribute cannot be null");
            VolumeShaper.Configuration fadeInVShaperConfig =
                    createVolShaperConfigForDuration(fadeInDurationMillis, /* isFadeIn= */ true);
            setFadeInVolumeShaperConfigForAudioAttributes(audioAttributes, fadeInVShaperConfig);
            return this;
        }

        /**
         * Set the list of {@link android.media.AudioAttributes usage} that can be faded
         *
         * <p>This is a positive list. Players with matching usage will be considered for fading.
         * Usages that are not part of this list will not be faded
         *
         * <p><b>Warning:</b> When fade state is set to enabled, the builder expects at least one
         * usage to be set/added. Failure to do so will result in an exception during
         * {@link #build()}
         *
         * @param usages List of the {@link android.media.AudioAttributes usages}
         * @return the same Builder instance
         * @throws IllegalArgumentException if the usages are invalid
         * @see #getFadeableUsages()
         */
        @NonNull
        public Builder setFadeableUsages(@NonNull List<Integer> usages) {
            Objects.requireNonNull(usages, "List of usages cannot be null");
            validateUsages(usages);
            setFlag(IS_FADEABLE_USAGES_FIELD_SET);
            mFadeableUsages.clear();
            mFadeableUsages.addAll(convertIntegerListToIntArray(usages));
            return this;
        }

        /**
         * Add the {@link android.media.AudioAttributes usage} to the fadeable list
         *
         * @param usage the {@link android.media.AudioAttributes usage}
         * @return the same Builder instance
         * @throws IllegalArgumentException if the usage is invalid
         * @see #getFadeableUsages()
         * @see #setFadeableUsages(List)
         */
        @NonNull
        public Builder addFadeableUsage(@AudioAttributes.AttributeUsage int usage) {
            validateUsage(usage);
            setFlag(IS_FADEABLE_USAGES_FIELD_SET);
            if (!mFadeableUsages.contains(usage)) {
                mFadeableUsages.add(usage);
            }
            return this;
        }

        /**
         * Clears the fadeable {@link android.media.AudioAttributes usage} list
         *
         * <p>This can be used to reset the list when using a copy constructor
         *
         * @return the same Builder instance
         * @see #getFadeableUsages()
         * @see #setFadeableUsages(List)
         */
        @NonNull
        public Builder clearFadeableUsages() {
            setFlag(IS_FADEABLE_USAGES_FIELD_SET);
            mFadeableUsages.clear();
            return this;
        }

        /**
         * Set the list of {@link android.media.AudioAttributes content type} that can not be faded
         *
         * <p>This is a negative list. Players with matching content type of this list will not be
         * faded. Content types that are not part of this list will be considered for fading.
         *
         * <p>Passing an empty list as input clears the existing list. This can be used to
         * reset the list when using a copy constructor
         *
         * @param contentTypes list of {@link android.media.AudioAttributes content types}
         * @return the same Builder instance
         * @throws IllegalArgumentException if the content types are invalid
         * @see #getUnfadeableContentTypes()
         */
        @NonNull
        public Builder setUnfadeableContentTypes(@NonNull List<Integer> contentTypes) {
            Objects.requireNonNull(contentTypes, "List of content types cannot be null");
            validateContentTypes(contentTypes);
            setFlag(IS_UNFADEABLE_CONTENT_TYPE_FIELD_SET);
            mUnfadeableContentTypes.clear();
            mUnfadeableContentTypes.addAll(convertIntegerListToIntArray(contentTypes));
            return this;
        }

        /**
         * Add the {@link android.media.AudioAttributes content type} to unfadeable list
         *
         * @param contentType the {@link android.media.AudioAttributes content type}
         * @return the same Builder instance
         * @throws IllegalArgumentException if the content type is invalid
         * @see #setUnfadeableContentTypes(List)
         * @see #getUnfadeableContentTypes()
         */
        @NonNull
        public Builder addUnfadeableContentType(
                @AudioAttributes.AttributeContentType int contentType) {
            validateContentType(contentType);
            setFlag(IS_UNFADEABLE_CONTENT_TYPE_FIELD_SET);
            if (!mUnfadeableContentTypes.contains(contentType)) {
                mUnfadeableContentTypes.add(contentType);
            }
            return this;
        }

        /**
         * Clears the unfadeable {@link android.media.AudioAttributes content type} list
         *
         * <p>This can be used to reset the list when using a copy constructor
         *
         * @return the same Builder instance
         * @see #setUnfadeableContentTypes(List)
         * @see #getUnfadeableContentTypes()
         */
        @NonNull
        public Builder clearUnfadeableContentTypes() {
            setFlag(IS_UNFADEABLE_CONTENT_TYPE_FIELD_SET);
            mUnfadeableContentTypes.clear();
            return this;
        }

        /**
         * Set the uids that cannot be faded
         *
         * <p>This is a negative list. Players with matching uid of this list will not be faded.
         * Uids that are not part of this list shall be considered for fading.
         *
         * @param uids list of uids
         * @return the same Builder instance
         * @see #getUnfadeableUids()
         */
        @NonNull
        public Builder setUnfadeableUids(@NonNull List<Integer> uids) {
            Objects.requireNonNull(uids, "List of uids cannot be null");
            mUnfadeableUids.clear();
            mUnfadeableUids.addAll(convertIntegerListToIntArray(uids));
            return this;
        }

        /**
         * Add uid to unfadeable list
         *
         * @param uid client uid
         * @return the same Builder instance
         * @see #setUnfadeableUids(List)
         * @see #getUnfadeableUids()
         */
        @NonNull
        public Builder addUnfadeableUid(int uid) {
            if (!mUnfadeableUids.contains(uid)) {
                mUnfadeableUids.add(uid);
            }
            return this;
        }

        /**
         * Clears the unfadeable uid list
         *
         * <p>This can be used to reset the list when using a copy constructor.
         *
         * @return the same Builder instance
         * @see #setUnfadeableUids(List)
         * @see #getUnfadeableUids()
         */
        @NonNull
        public Builder clearUnfadeableUids() {
            mUnfadeableUids.clear();
            return this;
        }

        /**
         * Set the list of {@link android.media.AudioAttributes} that can not be faded
         *
         * <p>This is a negative list. Players with matching audio attributes of this list will not
         * be faded. Audio attributes that are not part of this list shall be considered for fading.
         *
         * <p><b>Note:</b> Be cautious when adding generic audio attributes into this list as it can
         * negatively impact fadeability decision (if such an audio attribute and corresponding
         * usage fall into opposing lists).
         * For example:
         * <pre class=prettyprint>
         *    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() </pre>
         * is a generic audio attribute for {@link android.media.AudioAttributes.USAGE_MEDIA}.
         * It is an undefined behavior to have an {@link android.media.AudioAttributes usage} in the
         * fadeable usage list and the corresponding generic {@link android.media.AudioAttributes}
         * in the unfadeable list. Such cases will result in an exception during {@link #build()}.
         *
         * @param attrs list of {@link android.media.AudioAttributes}
         * @return the same Builder instance
         * @see #getUnfadeableAudioAttributes()
         */
        @NonNull
        public Builder setUnfadeableAudioAttributes(@NonNull List<AudioAttributes> attrs) {
            Objects.requireNonNull(attrs, "List of audio attributes cannot be null");
            mUnfadeableAudioAttributes.clear();
            mUnfadeableAudioAttributes.addAll(attrs);
            return this;
        }

        /**
         * Add the {@link android.media.AudioAttributes} to the unfadeable list
         *
         * @param audioAttributes the {@link android.media.AudioAttributes}
         * @return the same Builder instance
         * @see #setUnfadeableAudioAttributes(List)
         * @see #getUnfadeableAudioAttributes()
         */
        @NonNull
        public Builder addUnfadeableAudioAttributes(@NonNull AudioAttributes audioAttributes) {
            Objects.requireNonNull(audioAttributes, "Audio attributes cannot be null");
            if (!mUnfadeableAudioAttributes.contains(audioAttributes)) {
                mUnfadeableAudioAttributes.add(audioAttributes);
            }
            return this;
        }

        /**
         * Clears the unfadeable {@link android.media.AudioAttributes} list.
         *
         * <p>This can be used to reset the list when using a copy constructor.
         *
         * @return the same Builder instance
         * @see #getUnfadeableAudioAttributes()
         */
        @NonNull
        public Builder clearUnfadeableAudioAttributes() {
            mUnfadeableAudioAttributes.clear();
            return this;
        }

        /**
         * Set the delay after which the offending faded out player will be faded in.
         *
         * <p>This is the amount of time between the app being notified of the focus loss (when its
         * muted by the fade out), and the time fade in (to unmute) starts
         *
         * @param delayMillis delay in milliseconds
         * @return the same Builder instance
         * @throws IllegalArgumentException if the delay is negative
         * @see #getFadeInDelayForOffenders()
         */
        @NonNull
        public Builder setFadeInDelayForOffenders(@DurationMillisLong long delayMillis) {
            Preconditions.checkArgument(delayMillis >= 0, "Delay cannot be negative");
            mFadeInDelayForOffendersMillis = delayMillis;
            return this;
        }

        /**
         * Builds the {@link FadeManagerConfiguration} with all of the fade configurations that
         * have been set.
         *
         * @return a new {@link FadeManagerConfiguration} object
         */
        @NonNull
        public FadeManagerConfiguration build() {
            if (!checkNotSet(IS_BUILDER_USED_FIELD_SET)) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }

            setFlag(IS_BUILDER_USED_FIELD_SET);

            if (checkNotSet(IS_FADEABLE_USAGES_FIELD_SET)) {
                mFadeableUsages = DEFAULT_FADEABLE_USAGES;
                setVolShaperConfigsForUsages(mFadeableUsages);
            }

            if (checkNotSet(IS_UNFADEABLE_CONTENT_TYPE_FIELD_SET)) {
                mUnfadeableContentTypes = DEFAULT_UNFADEABLE_CONTENT_TYPES;
            }

            validateFadeConfigurations();

            return new FadeManagerConfiguration(mFadeState, mFadeOutDurationMillis,
                    mFadeInDurationMillis, mFadeInDelayForOffendersMillis, mUsageToFadeWrapperMap,
                    mAttrToFadeWrapperMap, mFadeableUsages, mUnfadeableContentTypes,
                    mUnfadeablePlayerTypes, mUnfadeableUids, mUnfadeableAudioAttributes);
        }

        private void setFlag(long flag) {
            mBuilderFieldsSet |= flag;
        }

        private boolean checkNotSet(long flag) {
            return (mBuilderFieldsSet & flag) == 0;
        }

        private FadeVolumeShaperConfigsWrapper getFadeVolShaperConfigWrapperForUsage(int usage) {
            if (!mUsageToFadeWrapperMap.contains(usage)) {
                mUsageToFadeWrapperMap.put(usage, new FadeVolumeShaperConfigsWrapper());
            }
            return mUsageToFadeWrapperMap.get(usage);
        }

        private FadeVolumeShaperConfigsWrapper getFadeVolShaperConfigWrapperForAttr(
                AudioAttributes attr) {
            // if no entry, create a new one for setting/clearing
            if (!mAttrToFadeWrapperMap.containsKey(attr)) {
                mAttrToFadeWrapperMap.put(attr, new FadeVolumeShaperConfigsWrapper());
            }
            return mAttrToFadeWrapperMap.get(attr);
        }

        private VolumeShaper.Configuration createVolShaperConfigForDuration(long duration,
                boolean isFadeIn) {
            // used to reset the volume shaper config setting
            if (duration == DURATION_NOT_SET) {
                return null;
            }

            VolumeShaper.Configuration.Builder builder = new VolumeShaper.Configuration.Builder()
                    .setId(VOLUME_SHAPER_SYSTEM_FADE_ID)
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(duration);

            if (isFadeIn) {
                builder.setCurve(/* times= */ new float[]{0.f, 0.50f, 1.0f},
                        /* volumes= */ new float[]{0.f, 0.30f, 1.0f});
            } else {
                builder.setCurve(/* times= */ new float[]{0.f, 0.25f, 1.0f},
                        /* volumes= */ new float[]{1.f, 0.65f, 0.0f});
            }

            return builder.build();
        }

        private void cleanupInactiveWrapperEntries(int usage) {
            FadeVolumeShaperConfigsWrapper fmcw = mUsageToFadeWrapperMap.get(usage);
            // cleanup map entry if FadeVolumeShaperConfigWrapper is inactive
            if (fmcw != null && fmcw.isInactive()) {
                mUsageToFadeWrapperMap.remove(usage);
            }
        }

        private void cleanupInactiveWrapperEntries(AudioAttributes attr) {
            FadeVolumeShaperConfigsWrapper fmcw = mAttrToFadeWrapperMap.get(attr);
            // cleanup map entry if FadeVolumeShaperConfigWrapper is inactive
            if (fmcw != null && fmcw.isInactive()) {
                mAttrToFadeWrapperMap.remove(attr);
            }
        }

        private void setVolShaperConfigsForUsages(IntArray usages) {
            // set default volume shaper configs for fadeable usages
            for (int index = 0; index < usages.size(); index++) {
                setMissingVolShaperConfigsForWrapper(
                        getFadeVolShaperConfigWrapperForUsage(usages.get(index)));
            }
        }

        private void setMissingVolShaperConfigsForWrapper(FadeVolumeShaperConfigsWrapper wrapper) {
            if (!wrapper.isFadeOutConfigActive()) {
                wrapper.setFadeOutVolShaperConfig(createVolShaperConfigForDuration(
                        mFadeOutDurationMillis, /* isFadeIn= */ false));
            }
            if (!wrapper.isFadeInConfigActive()) {
                wrapper.setFadeInVolShaperConfig(createVolShaperConfigForDuration(
                        mFadeInDurationMillis, /* isFadeIn= */ true));
            }
        }

        private void validateFadeState(int state) {
            switch(state) {
                case FADE_STATE_DISABLED:
                case FADE_STATE_ENABLED_DEFAULT:
                    break;
                default:
                    throw new IllegalArgumentException("Unknown fade state: " + state);
            }
        }

        private void validateUsages(List<Integer> usages) {
            for (int index = 0; index < usages.size(); index++) {
                validateUsage(usages.get(index));
            }
        }

        private void validateContentTypes(List<Integer> contentTypes) {
            for (int index = 0; index < contentTypes.size(); index++) {
                validateContentType(contentTypes.get(index));
            }
        }

        private void validateContentType(int contentType) {
            Preconditions.checkArgument(AudioAttributes.isSdkContentType(contentType),
                    "Invalid content type: ", contentType);
        }

        private void validateFadeConfigurations() {
            validateFadeableUsages();
            validateFadeVolumeShaperConfigsWrappers();
            validateUnfadeableAudioAttributes();
        }

        /** Ensure fadeable usage list meets config requirements */
        private void validateFadeableUsages() {
            // ensure at least one fadeable usage
            Preconditions.checkArgumentPositive(mFadeableUsages.size(),
                    "Fadeable usage list cannot be empty when state set to enabled");
            // ensure all fadeable usages have volume shaper configs - both fade in and out
            for (int index = 0; index < mFadeableUsages.size(); index++) {
                setMissingVolShaperConfigsForWrapper(
                        getFadeVolShaperConfigWrapperForUsage(mFadeableUsages.get(index)));
            }
        }

        /** Ensure Fade volume shaper config wrappers meet requirements */
        private void validateFadeVolumeShaperConfigsWrappers() {
            // ensure both fade in & out volume shaper configs are defined for all wrappers
            // for usages -
            for (int index = 0; index < mUsageToFadeWrapperMap.size(); index++) {
                setMissingVolShaperConfigsForWrapper(
                        getFadeVolShaperConfigWrapperForUsage(mUsageToFadeWrapperMap.keyAt(index)));
            }

            // for additional audio attributes -
            for (int index = 0; index < mAttrToFadeWrapperMap.size(); index++) {
                setMissingVolShaperConfigsForWrapper(
                        getFadeVolShaperConfigWrapperForAttr(mAttrToFadeWrapperMap.keyAt(index)));
            }
        }

        /** Ensure Unfadeable attributes meet configuration requirements */
        private void validateUnfadeableAudioAttributes() {
            // ensure no generic AudioAttributes in unfadeable list with matching usage in fadeable
            // list. failure results in an undefined behavior as the audio attributes
            // shall be both fadeable (because of the usage) and unfadeable at the same time.
            for (int index = 0; index < mUnfadeableAudioAttributes.size(); index++) {
                AudioAttributes targetAttr = mUnfadeableAudioAttributes.get(index);
                int usage = targetAttr.getSystemUsage();
                boolean isFadeableUsage = mFadeableUsages.contains(usage);
                // cannot have a generic audio attribute that also is a fadeable usage
                Preconditions.checkArgument(
                        !isFadeableUsage || (isFadeableUsage && !isGeneric(targetAttr)),
                        "Unfadeable audio attributes cannot be generic of the fadeable usage");
            }
        }

        private static boolean isGeneric(AudioAttributes attr) {
            return (attr.getContentType() == AudioAttributes.CONTENT_TYPE_UNKNOWN
                    && attr.getFlags() == 0x0
                    && attr.getBundle() == null
                    && attr.getTags().isEmpty());
        }
    }

    private static final class FadeVolumeShaperConfigsWrapper implements Parcelable {
        // null volume shaper config refers to either init state or if its cleared/reset
        private @Nullable VolumeShaper.Configuration mFadeOutVolShaperConfig;
        private @Nullable VolumeShaper.Configuration mFadeInVolShaperConfig;

        FadeVolumeShaperConfigsWrapper() {}

        public void setFadeOutVolShaperConfig(@Nullable VolumeShaper.Configuration fadeOutConfig) {
            mFadeOutVolShaperConfig = fadeOutConfig;
        }

        public void setFadeInVolShaperConfig(@Nullable VolumeShaper.Configuration fadeInConfig) {
            mFadeInVolShaperConfig = fadeInConfig;
        }

        /**
         * Query fade out volume shaper config
         *
         * @return configured fade out volume shaper config or {@code null} when initialized/reset
         */
        @Nullable
        public VolumeShaper.Configuration getFadeOutVolShaperConfig() {
            return mFadeOutVolShaperConfig;
        }

        /**
         * Query fade in volume shaper config
         *
         * @return configured fade in volume shaper config or {@code null} when initialized/reset
         */
        @Nullable
        public VolumeShaper.Configuration getFadeInVolShaperConfig() {
            return mFadeInVolShaperConfig;
        }

        /**
         * Wrapper is inactive if both fade out and in configs are cleared.
         *
         * @return {@code true} if configs are cleared. {@code false} if either of the configs is
         * set
         */
        public boolean isInactive() {
            return !isFadeOutConfigActive() && !isFadeInConfigActive();
        }

        boolean isFadeOutConfigActive() {
            return mFadeOutVolShaperConfig != null;
        }

        boolean isFadeInConfigActive() {
            return mFadeInVolShaperConfig != null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof FadeVolumeShaperConfigsWrapper)) {
                return false;
            }

            FadeVolumeShaperConfigsWrapper rhs = (FadeVolumeShaperConfigsWrapper) o;

            if (mFadeInVolShaperConfig == null && rhs.mFadeInVolShaperConfig == null
                    && mFadeOutVolShaperConfig == null && rhs.mFadeOutVolShaperConfig == null) {
                return true;
            }

            boolean isEqual;
            if (mFadeOutVolShaperConfig != null) {
                isEqual = mFadeOutVolShaperConfig.equals(rhs.mFadeOutVolShaperConfig);
            } else if (rhs.mFadeOutVolShaperConfig != null) {
                return false;
            } else {
                isEqual = true;
            }

            if (mFadeInVolShaperConfig != null) {
                isEqual = isEqual && mFadeInVolShaperConfig.equals(rhs.mFadeInVolShaperConfig);
            } else if (rhs.mFadeInVolShaperConfig != null) {
                return false;
            }

            return isEqual;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFadeOutVolShaperConfig, mFadeInVolShaperConfig);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            mFadeOutVolShaperConfig.writeToParcel(dest, flags);
            mFadeInVolShaperConfig.writeToParcel(dest, flags);
        }

        /**
         * Creates fade volume shaper config wrapper from parcel
         *
         * @hide
         */
        @VisibleForTesting()
        FadeVolumeShaperConfigsWrapper(Parcel in) {
            mFadeOutVolShaperConfig = VolumeShaper.Configuration.CREATOR.createFromParcel(in);
            mFadeInVolShaperConfig = VolumeShaper.Configuration.CREATOR.createFromParcel(in);
        }

        @NonNull
        public static final Creator<FadeVolumeShaperConfigsWrapper> CREATOR = new Creator<>() {
            @Override
            @NonNull
            public FadeVolumeShaperConfigsWrapper createFromParcel(@NonNull Parcel in) {
                return new FadeVolumeShaperConfigsWrapper(in);
            }

            @Override
            @NonNull
            public FadeVolumeShaperConfigsWrapper[] newArray(int size) {
                return new FadeVolumeShaperConfigsWrapper[size];
            }
        };
    }
}

