/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.audiofx.HapticGenerator;
import android.net.Uri;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Ringtone provides a quick method for playing a ringtone, notification, or
 * other similar types of sounds.
 * <p>
 * For ways of retrieving {@link Ringtone} objects or to show a ringtone
 * picker, see {@link RingtoneManager}.
 *
 * @see RingtoneManager
 */
public class Ringtone {
    private static final String TAG = "Ringtone";

    /**
     * The ringtone should only play sound. Any vibration is managed externally.
     * @hide
     */
    public static final int MEDIA_SOUND = 1;
    /**
     * The ringtone should only play vibration. Any sound is managed externally.
     * Requires the {@link android.Manifest.permission#VIBRATE} permission.
     * @hide
     */
    public static final int MEDIA_VIBRATION = 1 << 1;
    /**
     * The ringtone should play sound and vibration.
     * @hide
     */
    public static final int MEDIA_SOUND_AND_VIBRATION = MEDIA_SOUND | MEDIA_VIBRATION;

    // This is not a public value, because apps shouldn't enable "all" media - that wouldn't be
    // safe if new media types were added.
    static final int MEDIA_ALL = MEDIA_SOUND | MEDIA_VIBRATION;

    /**
     * Declares the types of media that this Ringtone is allowed to play.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "MEDIA_", value = {
            MEDIA_SOUND,
            MEDIA_VIBRATION,
            MEDIA_SOUND_AND_VIBRATION,
    })
    public @interface RingtoneMedia {}

    private static final String[] MEDIA_COLUMNS = new String[] {
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE
    };
    /** Selection that limits query results to just audio files */
    private static final String MEDIA_SELECTION = MediaColumns.MIME_TYPE + " LIKE 'audio/%' OR "
            + MediaColumns.MIME_TYPE + " IN ('application/ogg', 'application/x-flac')";

    // Flag-selected ringtone implementation to use.
    private final ApiInterface mApiImpl;

    /** {@hide} */
    @UnsupportedAppUsage
    public Ringtone(Context context, boolean allowRemote) {
        mApiImpl = new RingtoneV1(context, allowRemote);
    }

    /**
     * Constructor for legacy V1 initialization paths using non-public APIs on RingtoneV1.
     */
    private Ringtone(RingtoneV1 ringtoneV1) {
        mApiImpl = ringtoneV1;
    }

    private Ringtone(Builder builder, @Ringtone.RingtoneMedia int effectiveEnabledMedia,
            @NonNull AudioAttributes effectiveAudioAttributes,
            @Nullable VibrationEffect effectiveVibrationEffect,
            boolean effectiveHapticGeneratorEnabled) {
        mApiImpl = new RingtoneV2(builder.mContext, builder.mInjectables, builder.mAllowRemote,
                effectiveEnabledMedia, builder.mUri, effectiveAudioAttributes,
                builder.mUseExactAudioAttributes, builder.mVolumeShaperConfig,
                builder.mPreferBuiltinDevice, builder.mInitialSoundVolume, builder.mLooping,
                effectiveHapticGeneratorEnabled, effectiveVibrationEffect);
    }

    /**
     * Temporary V1 constructor for legacy V1 paths with audio attributes.
     * @hide
     */
    public static Ringtone createV1WithCustomAudioAttributes(
            Context context, AudioAttributes audioAttributes, Uri uri,
            VolumeShaper.Configuration volumeShaperConfig, boolean allowRemote) {
        RingtoneV1 ringtoneV1 = new RingtoneV1(context, allowRemote);
        ringtoneV1.setAudioAttributesField(audioAttributes);
        ringtoneV1.setUri(uri, volumeShaperConfig);
        ringtoneV1.reinitializeActivePlayer();
        return new Ringtone(ringtoneV1);
    }

    /**
     * Temporary V1 constructor for legacy V1 paths with stream type.
     * @hide
     */
    public static Ringtone createV1WithCustomStreamType(
            Context context, int streamType, Uri uri,
            VolumeShaper.Configuration volumeShaperConfig) {
        RingtoneV1 ringtoneV1 = new RingtoneV1(context, /* allowRemote= */ true);
        if (streamType >= 0) {
            ringtoneV1.setStreamType(streamType);
        }
        ringtoneV1.setUri(uri, volumeShaperConfig);
        if (!ringtoneV1.reinitializeActivePlayer()) {
            Log.e(TAG, "Failed to open ringtone " + uri);
            return null;
        }
        return new Ringtone(ringtoneV1);
    }

    /** @hide */
    @RingtoneMedia
    public int getEnabledMedia() {
        return mApiImpl.getEnabledMedia();
    }

    /**
     * Sets the stream type where this ringtone will be played.
     *
     * @param streamType The stream, see {@link AudioManager}.
     * @deprecated use {@link #setAudioAttributes(AudioAttributes)}
     */
    @Deprecated
    public void setStreamType(int streamType) {
        mApiImpl.setStreamType(streamType);
    }

    /**
     * Gets the stream type where this ringtone will be played.
     *
     * @return The stream type, see {@link AudioManager}.
     * @deprecated use of stream types is deprecated, see
     *     {@link #setAudioAttributes(AudioAttributes)}
     */
    @Deprecated
    public int getStreamType() {
        return mApiImpl.getStreamType();
    }

    /**
     * Sets the {@link AudioAttributes} for this ringtone.
     * @param attributes the non-null attributes characterizing this ringtone.
     */
    public void setAudioAttributes(AudioAttributes attributes)
            throws IllegalArgumentException {
        mApiImpl.setAudioAttributes(attributes);
    }

    /**
     * Returns the vibration effect that this ringtone was created with, if vibration is enabled.
     * Otherwise, returns null.
     * @hide
     */
    @Nullable
    public VibrationEffect getVibrationEffect() {
        return mApiImpl.getVibrationEffect();
    }

    /** @hide */
    @VisibleForTesting
    public boolean getPreferBuiltinDevice() {
        return mApiImpl.getPreferBuiltinDevice();
    }

    /** @hide */
    @VisibleForTesting
    public VolumeShaper.Configuration getVolumeShaperConfig() {
        return mApiImpl.getVolumeShaperConfig();
    }

    /**
     * Returns whether this player is local only, or can defer to the remote player. The
     * result may differ from the builder if there is no remote player available at all.
     * @hide
     */
    @VisibleForTesting
    public boolean isLocalOnly() {
        return mApiImpl.isLocalOnly();
    }

    /** @hide */
    @VisibleForTesting
    public boolean isUsingRemotePlayer() {
        return mApiImpl.isUsingRemotePlayer();
    }

    /**
     * Creates a local media player for the ringtone using currently set attributes.
     * @return true if media player creation succeeded or is deferred,
     * false if it did not succeed and can't be tried remotely.
     * @hide
     */
    public boolean reinitializeActivePlayer() {
        return mApiImpl.reinitializeActivePlayer();
    }

    /**
     * Same as AudioManager.hasHapticChannels except it assumes an already created ringtone.
     * @hide
     */
    public boolean hasHapticChannels() {
        return mApiImpl.hasHapticChannels();
    }

    /**
     * Returns the {@link AudioAttributes} used by this object.
     * @return the {@link AudioAttributes} that were set with
     *     {@link #setAudioAttributes(AudioAttributes)} or the default attributes if none were set.
     */
    public AudioAttributes getAudioAttributes() {
        return mApiImpl.getAudioAttributes();
    }

    /**
     * Sets the player to be looping or non-looping.
     * @param looping whether to loop or not.
     */
    public void setLooping(boolean looping) {
        mApiImpl.setLooping(looping);
    }

    /**
     * Returns whether the looping mode was enabled on this player.
     * @return true if this player loops when playing.
     */
    public boolean isLooping() {
        return mApiImpl.isLooping();
    }

    /**
     * Sets the volume on this player.
     * @param volume a raw scalar in range 0.0 to 1.0, where 0.0 mutes this player, and 1.0
     *   corresponds to no attenuation being applied.
     */
    public void setVolume(float volume) {
        mApiImpl.setVolume(volume);
    }

    /**
     * Returns the volume scalar set on this player.
     * @return a value between 0.0f and 1.0f.
     */
    public float getVolume() {
        return mApiImpl.getVolume();
    }

    /**
     * Enable or disable the {@link android.media.audiofx.HapticGenerator} effect. The effect can
     * only be enabled on devices that support the effect.
     *
     * @return true if the HapticGenerator effect is successfully enabled. Otherwise, return false.
     * @see android.media.audiofx.HapticGenerator#isAvailable()
     */
    public boolean setHapticGeneratorEnabled(boolean enabled) {
        return mApiImpl.setHapticGeneratorEnabled(enabled);
    }

    /**
     * Return whether the {@link android.media.audiofx.HapticGenerator} effect is enabled or not.
     * @return true if the HapticGenerator is enabled.
     */
    public boolean isHapticGeneratorEnabled() {
        return mApiImpl.isHapticGeneratorEnabled();
    }

    /**
     * Returns a human-presentable title for ringtone. Looks in media
     * content provider. If not in either, uses the filename
     *
     * @param context A context used for querying.
     */
    public String getTitle(Context context) {
        return mApiImpl.getTitle(context);
    }

    /**
     * @hide
     */
    public static String getTitle(
            Context context, Uri uri, boolean followSettingsUri, boolean allowRemote) {
        ContentResolver res = context.getContentResolver();

        String title = null;

        if (uri != null) {
            String authority = ContentProvider.getAuthorityWithoutUserId(uri.getAuthority());

            if (Settings.AUTHORITY.equals(authority)) {
                if (followSettingsUri) {
                    Uri actualUri = RingtoneManager.getActualDefaultRingtoneUri(context,
                            RingtoneManager.getDefaultType(uri));
                    String actualTitle = getTitle(
                            context, actualUri, false /*followSettingsUri*/, allowRemote);
                    title = context
                            .getString(com.android.internal.R.string.ringtone_default_with_actual,
                                    actualTitle);
                }
            } else {
                Cursor cursor = null;
                try {
                    if (MediaStore.AUTHORITY.equals(authority)) {
                        final String mediaSelection = allowRemote ? null : MEDIA_SELECTION;
                        cursor = res.query(uri, MEDIA_COLUMNS, mediaSelection, null, null);
                        if (cursor != null && cursor.getCount() == 1) {
                            cursor.moveToFirst();
                            return cursor.getString(1);
                        }
                        // missing cursor is handled below
                    }
                } catch (SecurityException e) {
                    IRingtonePlayer mRemotePlayer = null;
                    if (allowRemote) {
                        AudioManager audioManager =
                                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                        mRemotePlayer = audioManager.getRingtonePlayer();
                    }
                    if (mRemotePlayer != null) {
                        try {
                            title = mRemotePlayer.getTitle(uri);
                        } catch (RemoteException re) {
                        }
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                    cursor = null;
                }
                if (title == null) {
                    title = uri.getLastPathSegment();
                }
            }
        } else {
            title = context.getString(com.android.internal.R.string.ringtone_silent);
        }

        if (title == null) {
            title = context.getString(com.android.internal.R.string.ringtone_unknown);
            if (title == null) {
                title = "";
            }
        }

        return title;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public Uri getUri() {
        return mApiImpl.getUri();
    }

    /**
     * Plays the ringtone.
     */
    public void play() {
        mApiImpl.play();
    }

    /**
     * Stops a playing ringtone.
     */
    public void stop() {
        mApiImpl.stop();
    }

    /**
     * Whether this ringtone is currently playing.
     *
     * @return True if playing, false otherwise.
     */
    public boolean isPlaying() {
        return mApiImpl.isPlaying();
    }

    /**
     * Build a {@link Ringtone} to easily play sounds for ringtones, alarms and notifications.
     *
     * TODO: when un-hide, deprecate Ringtone: setAudioAttributes, setLooping,
     *       setHapticGeneratorEnabled (no-effect if MEDIA_VIBRATION),
     *       static RingtoneManager.getRingtone.
     * @hide
     */
    public static final class Builder {
        private final Context mContext;
        private final int mEnabledMedia;
        private Uri mUri;
        private final AudioAttributes mAudioAttributes;
        private boolean mUseExactAudioAttributes = false;
        // Not a static default since it doesn't really need to be in memory forever.
        private Injectables mInjectables = new Injectables();
        private VolumeShaper.Configuration mVolumeShaperConfig;
        private boolean mPreferBuiltinDevice = false;
        private boolean mAllowRemote = true;
        private boolean mHapticGeneratorEnabled = false;
        private float mInitialSoundVolume = 1.0f;
        private boolean mLooping = false;
        private VibrationEffect mVibrationEffect;

        /**
         * Constructs a builder to play the given media types from the mediaUri. If the mediaUri
         * is null (for example, an unset-setting), then fallback logic will dictate what plays.
         *
         * <p>When built, if the ringtone is already known to be a no-op, such as explicitly
         * silent, then the {@link #build} may return null.
         *
         * @param context The context for playing the ringtone.
         * @param enabledMedia Which media to play. Media not included is implicitly muted. Device
         *                     settings such as volume and vibrate-only may also affect which
         *                     media is played.
         * @param audioAttributes The attributes to use for playback, which affects the volumes and
         *                        settings that are applied.
         */
        public Builder(@NonNull Context context, @RingtoneMedia int enabledMedia,
                @NonNull AudioAttributes audioAttributes) {
            mContext = context;
            mEnabledMedia = enabledMedia;
            mAudioAttributes = audioAttributes;
        }

        /**
         * Inject test intercepters for static methods.
         * @hide
         */
        @NonNull
        public Builder setInjectables(Injectables injectables) {
            mInjectables = injectables;
            return this;
        }

        /**
         * The uri for the ringtone media to play. This is typically a user's preference for the
         * sound. If null, then it is treated as though the user's preference is unset and
         * fallback behavior, such as using the default ringtone setting, are used instead.
         *
         * When sound media is enabled, this is assumed to be a sound URI.
         */
        @NonNull
        public Builder setUri(@Nullable Uri uri) {
            mUri = uri;
            return this;
        }

        /**
         * Sets the VibrationEffect to use if vibration is enabled on this ringtone. The caller
         * should use {@link android.os.Vibrator#areVibrationFeaturesSupported} to ensure
         * that the effect is usable on this device, otherwise system defaults will be used.
         *
         * <p>Vibration will only happen if the Builder was created with media type
         * {@link Ringtone#MEDIA_VIBRATION} or {@link Ringtone#MEDIA_SOUND_AND_VIBRATION}, and
         * the application has the {@link android.Manifest.permission#VIBRATE} permission.
         *
         * <p>If the Ringtone is looping when it is played, then the VibrationEffect will be
         * modified to loop. Similarly, if the ringtone is not looping, a repeating
         * VibrationEffect will be modified to be non-repeating when the ringtone is played. Calls
         * to {@link Ringtone#setLooping} after the ringtone has started playing will stop a looping
         * vibration, but has no effect otherwise: specifically it will not restart vibration.
         */
        @NonNull
        public Builder setVibrationEffect(@NonNull VibrationEffect effect) {
            mVibrationEffect = effect;
            return this;
        }

        /**
         * Sets whether the resulting ringtone should loop until {@link Ringtone#stop()} is called,
         * or just play once.
         */
        @NonNull
        public Builder setLooping(boolean looping) {
            mLooping = looping;
            return this;
        }

        /**
         * Sets the VolumeShaper.Configuration to apply to the ringtone.
         * @hide
         */
        @NonNull
        public Builder setVolumeShaperConfig(
                @Nullable VolumeShaper.Configuration volumeShaperConfig) {
            mVolumeShaperConfig = volumeShaperConfig;
            return this;
        }

        /**
         * Whether to enable or disable the haptic generator.
         * @hide
         */
        @NonNull
        public Builder setEnableHapticGenerator(boolean enabled) {
            // Note that this property is mutable (but deprecated) on the Ringtone class itself.
            mHapticGeneratorEnabled = enabled;
            return this;
        }

        /**
         * Sets the initial sound volume for the ringtone.
         */
        @NonNull
        public Builder setInitialSoundVolume(float initialSoundVolume) {
            mInitialSoundVolume = initialSoundVolume;
            return this;
        }

        /**
         * Sets the preferred device of the ringtone playback to the built-in device. This is
         * only for use by the system server with known-good Uris.
         * @hide
         */
        @NonNull
        public Builder setPreferBuiltinDevice() {
            mPreferBuiltinDevice = true;
            mAllowRemote = false;  // Already in system.
            return this;
        }

        /**
         * Indicates that {@link AudioAttributes#areHapticChannelsMuted()} on the builder's
         * AudioAttributes should not be overridden. This is used to enable legacy behavior of
         * calling {@link Ringtone#setAudioAttributes} on an already-created ringtone, and can in
         * turn cause vibration during a "sound-only" session or can suppress audio-coupled
         * haptics that would usually take priority (therefore potentially falling back to
         * the VibrationEffect or system defaults).
         *
         * <p>Without this setting, the haptic channels will be automatically muted or not by the
         * Ringtone according to whether vibration is enabled or not.
         *
         * <p>This is for internal-use only. New applications should configure the vibration
         * behavior explicitly with the (TODO: future RingtoneSetting.setVibrationSource).
         * Handling haptic channels outside Ringtone leads to extra loads of the sound uri.
         * @hide
         */
        @NonNull
        public Builder setUseExactAudioAttributes(boolean useExactAttrs) {
            mUseExactAudioAttributes = useExactAttrs;
            return this;
        }

        /**
         * Prevent fallback to the remote service. This is primarily intended for use within the
         * remote IRingtonePlayer service itself, to avoid loops.
         * @hide
         */
        @NonNull
        public Builder setLocalOnly() {
            mAllowRemote = false;
            return this;
        }

        private boolean isVibrationEnabledAndAvailable() {
            if ((mEnabledMedia & MEDIA_VIBRATION) == 0) {
                return false;
            }
            Vibrator vibrator = mContext.getSystemService(Vibrator.class);
            if (!vibrator.hasVibrator()) {
                return false;
            }
            if (mContext.checkSelfPermission(Manifest.permission.VIBRATE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Ringtone requests vibration enabled, but no VIBRATE permission");
                return false;
            }
            return true;
        }

        /**
         * Returns the built Ringtone, or null if there was a problem loading the Uri and there
         * are no fallback options available.
         */
        @Nullable
        public Ringtone build() {
            @Ringtone.RingtoneMedia int effectiveEnabledMedia = mEnabledMedia;
            VibrationEffect effectiveVibrationEffect = mVibrationEffect;

            // Normalize media to that supported on this SDK level.
            if (effectiveEnabledMedia != (effectiveEnabledMedia & MEDIA_ALL)) {
                Log.e(TAG, "Unsupported media type: " + effectiveEnabledMedia);
                effectiveEnabledMedia = effectiveEnabledMedia & MEDIA_ALL;
            }
            final boolean effectiveHapticGenerator;
            final boolean hapticChannelsSupported;
            AudioAttributes effectiveAudioAttributes = mAudioAttributes;
            final boolean hapticChannelsMuted = mAudioAttributes.areHapticChannelsMuted();
            if (!isVibrationEnabledAndAvailable()) {
                // Vibration isn't active: turn off everything that might cause extra work.
                effectiveEnabledMedia &= ~MEDIA_VIBRATION;
                effectiveHapticGenerator = false;
                effectiveVibrationEffect = null;
                if (!mUseExactAudioAttributes && !hapticChannelsMuted) {
                    effectiveAudioAttributes = new AudioAttributes.Builder(effectiveAudioAttributes)
                            .setHapticChannelsMuted(true)
                            .build();
                }
            } else {
                // Vibration is active.
                effectiveHapticGenerator =
                        mHapticGeneratorEnabled && mInjectables.isHapticGeneratorAvailable();
                hapticChannelsSupported = mInjectables.isHapticPlaybackSupported();
                // Haptic channels are preferred if they are available, and not explicitly muted.
                // We won't know if haptic channels are available until loading the media player,
                // and since the media player needs to be reset to change audio attributes, then
                // we proactively enable the channels - it won't matter if they aren't present.
                if (!mUseExactAudioAttributes) {
                    boolean shouldBeMuted = effectiveHapticGenerator || !hapticChannelsSupported;
                    if (shouldBeMuted != hapticChannelsMuted) {
                        effectiveAudioAttributes =
                                new AudioAttributes.Builder(effectiveAudioAttributes)
                                .setHapticChannelsMuted(shouldBeMuted)
                                .build();
                    }
                }
                // If no contextual vibration, then try loading the default one for the URI.
                if (mVibrationEffect == null && mUri != null) {
                    effectiveVibrationEffect = VibrationEffect.get(mUri, mContext);
                }
            }
            try {
                Ringtone ringtone = new Ringtone(this, effectiveEnabledMedia,
                        effectiveAudioAttributes, effectiveVibrationEffect,
                        effectiveHapticGenerator);
                if (ringtone.reinitializeActivePlayer()) {
                    return ringtone;
                } else {
                    Log.e(TAG, "Failed to open ringtone " + mUri);
                    return null;
                }
            } catch (Exception ex) {
                // Catching Exception isn't great, but was done in the old
                // RingtoneManager.getRingtone and hides errors like DocumentsProvider throwing
                // IllegalArgumentException instead of FileNotFoundException, and also robolectric
                // failures when ShadowMediaPlayer wasn't pre-informed of the ringtone.
                Log.e(TAG, "Failed while opening ringtone " + mUri, ex);
                return null;
            }
        }
    }

    /**
     * Interface for intercepting static methods and constructors, for unit testing only.
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static class Injectables {
        /** Intercept {@code new MediaPlayer()}. */
        @NonNull
        public MediaPlayer newMediaPlayer() {
            return new MediaPlayer();
        }

        /** Intercept {@link HapticGenerator#isAvailable}. */
        public boolean isHapticGeneratorAvailable() {
            return HapticGenerator.isAvailable();
        }

        /**
         * Intercept {@link HapticGenerator#create} using
         * {@link MediaPlayer#getAudioSessionId()} from the given media player.
         */
        @Nullable
        public HapticGenerator createHapticGenerator(@NonNull MediaPlayer mediaPlayer) {
            return HapticGenerator.create(mediaPlayer.getAudioSessionId());
        }

        /** Returns the result of {@link AudioManager#isHapticPlaybackSupported()}. */
        public boolean isHapticPlaybackSupported() {
            return AudioManager.isHapticPlaybackSupported();
        }

        /**
         * Returns whether the MediaPlayer tracks have haptic channels. This is the same as
         * AudioManager.hasHapticChannels, except it uses an already prepared MediaPlayer to avoid
         * loading the metadata a second time.
         */
        public boolean hasHapticChannels(MediaPlayer mp) {
            try {
                Trace.beginSection("Ringtone.hasHapticChannels");
                for (MediaPlayer.TrackInfo trackInfo : mp.getTrackInfo()) {
                    if (trackInfo.hasHapticChannels()) {
                        return true;
                    }
                }
            } finally {
                Trace.endSection();
            }
            return false;
        }

    }

    /**
     * Interface for alternative Ringtone implementations. See the public Ringtone methods that
     * delegate to these for documentation.
     * @hide
     */
    interface ApiInterface {
        void setStreamType(int streamType);
        int getStreamType();
        void setAudioAttributes(AudioAttributes attributes);
        boolean getPreferBuiltinDevice();
        VolumeShaper.Configuration getVolumeShaperConfig();
        boolean isLocalOnly();
        boolean isUsingRemotePlayer();
        boolean reinitializeActivePlayer();
        boolean hasHapticChannels();
        AudioAttributes getAudioAttributes();
        void setLooping(boolean looping);
        boolean isLooping();
        void setVolume(float volume);
        float getVolume();
        boolean setHapticGeneratorEnabled(boolean enabled);
        boolean isHapticGeneratorEnabled();
        String getTitle(Context context);
        Uri getUri();
        void play();
        void stop();
        boolean isPlaying();
        // V2 future-public methods
        @RingtoneMedia int getEnabledMedia();
        VibrationEffect getVibrationEffect();
    }

    /**
     * Switch for using the new ringtone implementation (RingtoneV1 vs RingtoneV2). This may be
     * called from both system server and app-side sdk.
     *
     * @hide
     */
    public static boolean useRingtoneV2() {
        // TODO(b/293846645): chang eto new flagging infra
        return SystemProperties.getBoolean("persist.audio.ringtone.use_v2", false);
    }
}
