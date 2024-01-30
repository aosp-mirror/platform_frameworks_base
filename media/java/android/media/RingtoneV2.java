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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources.NotFoundException;
import android.media.Ringtone.Injectables;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * New Ringtone implementation, supporting vibration as well as sound, and configuration via a
 * builder. During flagged transition, the original implementation is in RingtoneV1.java.
 *
 * Only modified methods are moved here.
 *
 * @hide
 */
class RingtoneV2 implements Ringtone.ApiInterface {
    private static final String TAG = "RingtoneV2";

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

    private final Context mContext;
    private final Vibrator mVibrator;
    private final AudioManager mAudioManager;
    private VolumeShaper.Configuration mVolumeShaperConfig;

    /**
     * Flag indicating if we're allowed to fall back to remote playback using
     * {@link #mRemoteRingtoneService}. Typically this is false when we're the remote
     * player and there is nobody else to delegate to.
     */
    private final boolean mAllowRemote;
    private final IRingtonePlayer mRemoteRingtoneService;
    private final Injectables mInjectables;

    private final int mEnabledMedia;

    private final Uri mUri;
    private String mTitle;

    private AudioAttributes mAudioAttributes;
    private boolean mUseExactAudioAttributes;
    private boolean mPreferBuiltinDevice;
    private RingtonePlayer mActivePlayer;
    // playback properties, use synchronized with mPlaybackSettingsLock
    private boolean mIsLooping;
    private float mVolume;
    private boolean mHapticGeneratorEnabled;
    private final Object mPlaybackSettingsLock = new Object();
    private final VibrationEffect mVibrationEffect;

    /** Only for use by Ringtone constructor */
    RingtoneV2(@NonNull Context context, @NonNull Injectables injectables,
                       boolean allowRemote, @Ringtone.RingtoneMedia int enabledMedia,
                       @Nullable Uri uri, @NonNull AudioAttributes audioAttributes,
                       boolean useExactAudioAttributes,
                       @Nullable VolumeShaper.Configuration volumeShaperConfig,
                       boolean preferBuiltinDevice, float soundVolume, boolean looping,
                       boolean hapticGeneratorEnabled, @Nullable VibrationEffect vibrationEffect) {
        // Context
        mContext = context;
        mInjectables = injectables;
        mVibrator = mContext.getSystemService(Vibrator.class);
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mRemoteRingtoneService = allowRemote ? mAudioManager.getRingtonePlayer() : null;
        mAllowRemote = (mRemoteRingtoneService != null);  // Only set if allowed, and present.

        // Properties potentially propagated to remote player.
        mEnabledMedia = enabledMedia;
        mUri = uri;
        mAudioAttributes = audioAttributes;
        mUseExactAudioAttributes = useExactAudioAttributes;
        mVolumeShaperConfig = volumeShaperConfig;
        mPreferBuiltinDevice = preferBuiltinDevice;  // system-only, not supported for remote play.
        mVolume = soundVolume;
        mIsLooping = looping;
        mHapticGeneratorEnabled = hapticGeneratorEnabled;
        mVibrationEffect = vibrationEffect;
    }

    /** @hide */
    @RingtoneMedia
    public int getEnabledMedia() {
        return mEnabledMedia;
    }

    /**
     * Sets the stream type where this ringtone will be played.
     *
     * @param streamType The stream, see {@link AudioManager}.
     * @deprecated use {@link #setAudioAttributes(AudioAttributes)}
     */
    @Deprecated
    public void setStreamType(int streamType) {
        setAudioAttributes(
                getAudioAttributesForLegacyStreamType(streamType, "setStreamType()"));
    }

    private AudioAttributes getAudioAttributesForLegacyStreamType(int streamType, String originOp) {
        PlayerBase.deprecateStreamTypeForPlayback(streamType, "Ringtone", originOp);
        return new AudioAttributes.Builder()
                .setInternalLegacyStreamType(streamType)
                .build();
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
        return AudioAttributes.toLegacyStreamType(mAudioAttributes);
    }

    /**
     * Sets the {@link AudioAttributes} for this ringtone.
     * @param attributes the non-null attributes characterizing this ringtone.
     */
    public void setAudioAttributes(AudioAttributes attributes)
            throws IllegalArgumentException {
        // TODO: deprecate this method - it will be done with a builder.
        if (attributes == null) {
            throw new IllegalArgumentException("Invalid null AudioAttributes for Ringtone");
        }
        mAudioAttributes = attributes;
        // Setting the audio attributes requires re-initializing the player.
        if (mActivePlayer != null) {
            // The audio attributes have to be set before the media player is prepared.
            // Re-initialize it.
            reinitializeActivePlayer();
        }
    }

    /**
     * Returns the vibration effect that this ringtone was created with, if vibration is enabled.
     * Otherwise, returns null.
     * @hide
     */
    @Nullable
    public VibrationEffect getVibrationEffect() {
        return mVibrationEffect;
    }

    /** @hide */
    @VisibleForTesting
    public boolean getPreferBuiltinDevice() {
        return mPreferBuiltinDevice;
    }

    /** @hide */
    @VisibleForTesting
    public VolumeShaper.Configuration getVolumeShaperConfig() {
        return mVolumeShaperConfig;
    }

    /**
     * Returns whether this player is local only, or can defer to the remote player. The
     * result may differ from the builder if there is no remote player available at all.
     * @hide
     */
    @VisibleForTesting
    public boolean isLocalOnly() {
        return !mAllowRemote;
    }

    /** @hide */
    @VisibleForTesting
    public boolean isUsingRemotePlayer() {
        return mActivePlayer instanceof RemoteRingtonePlayer;
    }

    /**
     * Finds the output device of type {@link AudioDeviceInfo#TYPE_BUILTIN_SPEAKER}. This device is
     * the one on which outgoing audio for SIM calls is played.
     *
     * @param audioManager the audio manage.
     * @return the {@link AudioDeviceInfo} corresponding to the builtin device, or {@code null} if
     *     none can be found.
     */
    private AudioDeviceInfo getBuiltinDevice(AudioManager audioManager) {
        AudioDeviceInfo[] deviceList = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : deviceList) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return device;
            }
        }
        return null;
    }

    /**
     * Creates a local media player for the ringtone using currently set attributes.
     * @return true if media player creation succeeded or is deferred,
     * false if it did not succeed and can't be tried remotely.
     * @hide
     */
    public boolean reinitializeActivePlayer() {
        // Try creating a local media player, or fallback to creating a remote one.
        Trace.beginSection("reinitializeActivePlayer");
        try {
            if (mActivePlayer != null) {
                // This would only happen if calling the deprecated setAudioAttributes after
                // building the Ringtone.
                stopAndReleaseActivePlayer();
            }

            boolean vibrationOnly = (mEnabledMedia & MEDIA_ALL) == MEDIA_VIBRATION;
            // Vibration can come from the audio file if using haptic generator or if haptic
            // channels are a possibility.
            boolean maybeAudioVibration = mUri != null && mInjectables.isHapticPlaybackSupported()
                    && (mHapticGeneratorEnabled || !mAudioAttributes.areHapticChannelsMuted());

            // VibrationEffect only, use the simplified player without checking for haptic channels.
            if (vibrationOnly && !maybeAudioVibration && mVibrationEffect != null) {
                mActivePlayer = new LocalRingtonePlayer.VibrationEffectPlayer(
                        mVibrationEffect, mAudioAttributes, mVibrator, mIsLooping);
                return true;
            }

            AudioDeviceInfo preferredDevice =
                    mPreferBuiltinDevice ? getBuiltinDevice(mAudioManager) : null;
            if (mUri != null) {
                mActivePlayer = LocalRingtonePlayer.create(mContext, mAudioManager, mVibrator, mUri,
                        mAudioAttributes, vibrationOnly, mVibrationEffect, mInjectables,
                        mVolumeShaperConfig, preferredDevice, mHapticGeneratorEnabled, mIsLooping,
                        mVolume);
            } else {
                // Using the remote player won't help play a null Uri. Revert straight to fallback.
                // The vibration-only case was already covered above.
                mActivePlayer = createFallbackRingtonePlayer();
                // Fall through to attempting remote fallback play if null.
            }

            if (mActivePlayer == null && mAllowRemote) {
                mActivePlayer = new RemoteRingtonePlayer(mRemoteRingtoneService, mUri,
                        mAudioAttributes, mUseExactAudioAttributes, mEnabledMedia, mVibrationEffect,
                        mVolumeShaperConfig, mHapticGeneratorEnabled, mIsLooping, mVolume);
            }

            return mActivePlayer != null;
        } finally {
            if (mActivePlayer != null) {
                Log.d(TAG, "Initialized ringtone player with " + mActivePlayer.getClass());
            } else {
                Log.d(TAG, "Failed to initialize ringtone player");
            }
            Trace.endSection();
        }
    }

    @Nullable
    private LocalRingtonePlayer createFallbackRingtonePlayer() {
        int ringtoneType = RingtoneManager.getDefaultType(mUri);
        if (ringtoneType != -1
                && RingtoneManager.getActualDefaultRingtoneUri(mContext, ringtoneType) == null) {
            Log.w(TAG, "not playing fallback for " + mUri);
            return null;
        }
        // Default ringtone, try fallback ringtone.
        try (AssetFileDescriptor afd = mContext.getResources().openRawResourceFd(
                    com.android.internal.R.raw.fallbackring)) {
            if (afd == null) {
                Log.e(TAG, "Could not load fallback ringtone");
                return null;
            }

            AudioDeviceInfo preferredDevice =
                    mPreferBuiltinDevice ? getBuiltinDevice(mAudioManager) : null;
            return LocalRingtonePlayer.createForFallback(mAudioManager, mVibrator, afd,
                    mAudioAttributes, mVibrationEffect, mInjectables, mVolumeShaperConfig,
                    preferredDevice, mIsLooping, mVolume);
        } catch (NotFoundException nfe) {
            Log.e(TAG, "Fallback ringtone does not exist");
            return null;
        } catch (IOException e) {
            // As with the above messages, not including much information about the
            // failure so as not to expose details of the fallback ringtone resource.
            Log.e(TAG, "Exception reading fallback ringtone");
            return null;
        }
    }

    /**
     * Same as AudioManager.hasHapticChannels except it assumes an already created ringtone.
     * @hide
     */
    public boolean hasHapticChannels() {
        return (mActivePlayer == null) ? false : mActivePlayer.hasHapticChannels();
    }

    /**
     * Returns the {@link AudioAttributes} used by this object.
     * @return the {@link AudioAttributes} that were set with
     *     {@link #setAudioAttributes(AudioAttributes)} or the default attributes if none were set.
     */
    public AudioAttributes getAudioAttributes() {
        return mAudioAttributes;
    }

    /**
     * Sets the player to be looping or non-looping.
     * @param looping whether to loop or not.
     */
    public void setLooping(boolean looping) {
        synchronized (mPlaybackSettingsLock) {
            mIsLooping = looping;
            if (mActivePlayer != null) {
                mActivePlayer.setLooping(looping);
            }
        }
    }

    /**
     * Returns whether the looping mode was enabled on this player.
     * @return true if this player loops when playing.
     */
    public boolean isLooping() {
        synchronized (mPlaybackSettingsLock) {
            return mIsLooping;
        }
    }

    /**
     * Sets the volume on this player.
     * @param volume a raw scalar in range 0.0 to 1.0, where 0.0 mutes this player, and 1.0
     *   corresponds to no attenuation being applied.
     */
    public void setVolume(float volume) {
        // Ignore if sound not enabled.
        if ((mEnabledMedia & MEDIA_SOUND) == 0) {
            return;
        }
        if (volume < 0.0f) {
            volume = 0.0f;
        } else if (volume > 1.0f) {
            volume = 1.0f;
        }

        synchronized (mPlaybackSettingsLock) {
            mVolume = volume;
            if (mActivePlayer != null) {
                mActivePlayer.setVolume(volume);
            }
        }
    }

    /**
     * Returns the volume scalar set on this player.
     * @return a value between 0.0f and 1.0f.
     */
    public float getVolume() {
        synchronized (mPlaybackSettingsLock) {
            return mVolume;
        }
    }

    /**
     * Enable or disable the {@link android.media.audiofx.HapticGenerator} effect. The effect can
     * only be enabled on devices that support the effect.
     *
     * @return true if the HapticGenerator effect is successfully enabled. Otherwise, return false.
     * @see android.media.audiofx.HapticGenerator#isAvailable()
     */
    public boolean setHapticGeneratorEnabled(boolean enabled) {
        if (!mInjectables.isHapticGeneratorAvailable()) {
            return false;
        }
        synchronized (mPlaybackSettingsLock) {
            mHapticGeneratorEnabled = enabled;
            if (mActivePlayer != null) {
                mActivePlayer.setHapticGeneratorEnabled(enabled);
            }
        }
        return true;
    }

    /**
     * Return whether the {@link android.media.audiofx.HapticGenerator} effect is enabled or not.
     * @return true if the HapticGenerator is enabled.
     */
    public boolean isHapticGeneratorEnabled() {
        synchronized (mPlaybackSettingsLock) {
            return mHapticGeneratorEnabled;
        }
    }

    /**
     * Returns a human-presentable title for ringtone. Looks in media
     * content provider. If not in either, uses the filename
     *
     * @param context A context used for querying.
     */
    public String getTitle(Context context) {
        if (mTitle != null) return mTitle;
        return mTitle = Ringtone.getTitle(context, mUri, true /*followSettingsUri*/, mAllowRemote);
    }


    /** {@hide} */
    @UnsupportedAppUsage
    public Uri getUri() {
        return mUri;
    }

    /**
     * Plays the ringtone.
     */
    public void play() {
        if (mActivePlayer != null) {
            Log.d(TAG, "Starting ringtone playback");
            if (mActivePlayer.play()) {
                return;
            } else {
                // Discard active player: play() is only meant to be called once.
                stopAndReleaseActivePlayer();
            }
        }
        if (!playFallbackRingtone()) {
            Log.w(TAG, "Neither local nor remote playback available");
        }
    }

    /**
     * Stops a playing ringtone.
     */
    public void stop() {
        stopAndReleaseActivePlayer();
    }

    private void stopAndReleaseActivePlayer() {
        if (mActivePlayer != null) {
            mActivePlayer.stopAndRelease();
            mActivePlayer = null;
        }
    }

    /**
     * Whether this ringtone is currently playing.
     *
     * @return True if playing, false otherwise.
     */
    public boolean isPlaying() {
        if (mActivePlayer != null) {
            return mActivePlayer.isPlaying();
        } else {
            Log.w(TAG, "No active ringtone player");
            return false;
        }
    }

    /**
     * Fallback during the play stage rather than initialization, typically due to an issue
     * communicating with the remote player.
     */
    private boolean playFallbackRingtone() {
        if (mActivePlayer != null) {
            Log.wtf(TAG, "Playing fallback ringtone with another active player");
            stopAndReleaseActivePlayer();
        }
        int streamType = AudioAttributes.toLegacyStreamType(mAudioAttributes);
        if (mAudioManager.getStreamVolume(streamType) == 0) {
            // TODO: Return true? If volume is off, this is a successful play.
            return false;
        }
        mActivePlayer = createFallbackRingtonePlayer();
        if (mActivePlayer == null) {
            return false;  // the create method logs if it returns null.
        } else if (mActivePlayer.play()) {
            return true;
        } else {
            stopAndReleaseActivePlayer();
            return false;
        }
    }

    void setTitle(String title) {
        mTitle = title;
    }

    /**
     * Play a specific ringtone. This interface is implemented by either local (this process) or
     * proxied-remote playback via AudioManager.getRingtonePlayer, so that the caller
     * (Ringtone class) can just use a single player after the initial creation.
     * @hide
     */
    interface RingtonePlayer {
        /**
         * Start playing the ringtone, returning false if there was a problem that
         * requires falling back to the fallback ringtone resource.
         */
        boolean play();
        boolean isPlaying();
        void stopAndRelease();

        // Mutating playback methods.
        void setPreferredDevice(@Nullable AudioDeviceInfo audioDeviceInfo);
        void setLooping(boolean looping);
        void setHapticGeneratorEnabled(boolean enabled);
        void setVolume(float volume);

        boolean hasHapticChannels();
    }

    /**
     * Remote RingtonePlayer. All operations are delegated via the IRingtonePlayer interface, which
     * should ultimately be backed by a RingtoneLocalPlayer within the system services.
     */
    static class RemoteRingtonePlayer implements RingtonePlayer {
        private final IBinder mRemoteToken = new Binder();
        private final IRingtonePlayer mRemoteRingtoneService;
        private final Uri mCanonicalUri;
        private final int mEnabledMedia;
        private final VibrationEffect mVibrationEffect;
        private final VolumeShaper.Configuration mVolumeShaperConfig;
        private final AudioAttributes mAudioAttributes;
        private final boolean mUseExactAudioAttributes;
        private boolean mIsLooping;
        private float mVolume;
        private boolean mHapticGeneratorEnabled;

        RemoteRingtonePlayer(@NonNull IRingtonePlayer remoteRingtoneService,
                @NonNull Uri uri, @NonNull AudioAttributes audioAttributes,
                boolean useExactAudioAttributes,
                @RingtoneMedia int enabledMedia, @Nullable VibrationEffect vibrationEffect,
                @Nullable VolumeShaper.Configuration volumeShaperConfig,
                boolean hapticGeneratorEnabled, boolean initialIsLooping, float initialVolume) {
            mRemoteRingtoneService = remoteRingtoneService;
            mCanonicalUri = (uri == null) ? null : uri.getCanonicalUri();
            mAudioAttributes = audioAttributes;
            mUseExactAudioAttributes = useExactAudioAttributes;
            mEnabledMedia = enabledMedia;
            mVibrationEffect = vibrationEffect;
            mVolumeShaperConfig = volumeShaperConfig;
            mHapticGeneratorEnabled = hapticGeneratorEnabled;
            mIsLooping = initialIsLooping;
            mVolume = initialVolume;
        }

        @Override
        public boolean play() {
            try {
                mRemoteRingtoneService.playRemoteRingtone(mRemoteToken, mCanonicalUri,
                        mAudioAttributes, mUseExactAudioAttributes, mEnabledMedia, mVibrationEffect,
                        mVolume, mIsLooping, mHapticGeneratorEnabled, mVolumeShaperConfig);
                return true;
            } catch (RemoteException e) {
                Log.w(TAG, "Problem playing ringtone: " + e);
                return false;
            }
        }

        @Override
        public boolean isPlaying() {
            try {
                return mRemoteRingtoneService.isPlaying(mRemoteToken);
            } catch (RemoteException e) {
                Log.w(TAG, "Problem checking ringtone isPlaying: " + e);
                return false;
            }
        }

        @Override
        public void stopAndRelease() {
            try {
                mRemoteRingtoneService.stop(mRemoteToken);
            } catch (RemoteException e) {
                Log.w(TAG, "Problem stopping ringtone: " + e);
            }
        }

        @Override
        public void setPreferredDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
            // un-implemented for remote (but not used outside system).
        }

        @Override
        public void setLooping(boolean looping) {
            mIsLooping = looping;
            try {
                mRemoteRingtoneService.setLooping(mRemoteToken, looping);
            } catch (RemoteException e) {
                Log.w(TAG, "Problem setting looping: " + e);
            }
        }

        @Override
        public void setHapticGeneratorEnabled(boolean enabled) {
            mHapticGeneratorEnabled = enabled;
            try {
                mRemoteRingtoneService.setHapticGeneratorEnabled(mRemoteToken, enabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Problem setting hapticGeneratorEnabled: " + e);
            }
        }

        @Override
        public void setVolume(float volume) {
            mVolume = volume;
            try {
                mRemoteRingtoneService.setVolume(mRemoteToken, volume);
            } catch (RemoteException e) {
                Log.w(TAG, "Problem setting volume: " + e);
            }
        }

        @Override
        public boolean hasHapticChannels() {
            // FIXME: support remote player, or internalize haptic channels support and remove
            // entirely.
            return false;
        }
    }

}
