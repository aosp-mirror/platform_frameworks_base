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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.media.audiofx.HapticGenerator;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;

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

    private static final String[] MEDIA_COLUMNS = new String[] {
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE
    };
    /** Selection that limits query results to just audio files */
    private static final String MEDIA_SELECTION = MediaColumns.MIME_TYPE + " LIKE 'audio/%' OR "
            + MediaColumns.MIME_TYPE + " IN ('application/ogg', 'application/x-flac')";

    private final Context mContext;
    private final AudioManager mAudioManager;
    private VolumeShaper.Configuration mVolumeShaperConfig;

    /**
     * Flag indicating if we're allowed to fall back to remote playback using
     * {@link #mRemoteRingtoneService}. Typically this is false when we're the remote
     * player and there is nobody else to delegate to.
     */
    private final boolean mAllowRemote;
    private final IRingtonePlayer mRemoteRingtoneService;

    @UnsupportedAppUsage
    private Uri mUri;
    private String mTitle;

    private AudioAttributes mAudioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    private boolean mPreferBuiltinDevice;
    private RingtonePlayer mActivePlayer;
    // playback properties, use synchronized with mPlaybackSettingsLock
    private boolean mIsLooping = false;
    private float mVolume = 1.0f;
    private boolean mHapticGeneratorEnabled = false;
    private final Object mPlaybackSettingsLock = new Object();

    /** {@hide} */
    @UnsupportedAppUsage
    public Ringtone(Context context, boolean allowRemote) {
        mContext = context;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mRemoteRingtoneService = allowRemote ? mAudioManager.getRingtonePlayer() : null;
        mAllowRemote = allowRemote && (mRemoteRingtoneService != null);
    }

    /**
     * Sets the stream type where this ringtone will be played.
     *
     * @param streamType The stream, see {@link AudioManager}.
     * @deprecated use {@link #setAudioAttributes(AudioAttributes)}
     */
    @Deprecated
    public void setStreamType(int streamType) {
        PlayerBase.deprecateStreamTypeForPlayback(streamType, "Ringtone", "setStreamType()");
        setAudioAttributes(new AudioAttributes.Builder()
                .setInternalLegacyStreamType(streamType)
                .build());
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
        setAudioAttributesField(attributes);
        // Setting the audio attributes requires re-initializing the player.
        if (mActivePlayer != null) {
            // The audio attributes have to be set before the media player is prepared.
            // Re-initialize it.
            reinitializeActivePlayer();
        }
    }

    /**
     * Same as {@link #setAudioAttributes(AudioAttributes)} except this one does not create
     * the media player.
     * @hide
     */
    public void setAudioAttributesField(@Nullable AudioAttributes attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("Invalid null AudioAttributes for Ringtone");
        }
        mAudioAttributes = attributes;
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
     * Sets the preferred device of the ringtone playback to the built-in device.
     *
     * @hide
     */
    public boolean preferBuiltinDevice(boolean enable) {
        mPreferBuiltinDevice = enable;
        if (mActivePlayer != null) {
            mActivePlayer.setPreferredDevice(enable ? getBuiltinDevice(mAudioManager) : null);
        }
        return true;  // FIXME: Unused, to clean up with builder.
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
        if (mActivePlayer != null) {
            stopAndReleaseActivePlayer();
        }

        if (mUri == null) {
            Log.e(TAG, "Could not create media player as no URI was provided.");
            return mAllowRemote;
        }

        AudioDeviceInfo preferredDevice =
                mPreferBuiltinDevice ? getBuiltinDevice(mAudioManager) : null;
        mActivePlayer = LocalRingtonePlayer.create(mContext, mAudioManager, mUri, mAudioAttributes,
                mVolumeShaperConfig, preferredDevice, mHapticGeneratorEnabled, mIsLooping, mVolume);
        if (mActivePlayer != null) {
            return true;
        }

        // Local player failed, setup a remote one if possible.
        if (!mAllowRemote) {
            return false;
        }

        mActivePlayer = new RemoteRingtonePlayer(mRemoteRingtoneService, mUri, mAudioAttributes,
                mVolumeShaperConfig, mHapticGeneratorEnabled, mIsLooping, mVolume);
        return true;
    }

    /**
     * Same as AudioManager.hasHapticChannels except it assumes an already created ringtone.
     * If the ringtone has not been created, it will load based on URI provided at {@link #setUri}
     * and if not URI has been set, it will assume no haptic channels are present.
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
        if (!HapticGenerator.isAvailable()) {
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
        return mTitle = getTitle(context, mUri, true /*followSettingsUri*/, mAllowRemote);
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

    /**
     * Set {@link Uri} to be used for ringtone playback.
     * {@link IRingtonePlayer}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void setUri(Uri uri) {
        setUri(uri, null);
    }

    /**
     * @hide
     */
    public void setVolumeShaperConfig(@Nullable VolumeShaper.Configuration volumeShaperConfig) {
        mVolumeShaperConfig = volumeShaperConfig;
    }

    /**
     * Set {@link Uri} to be used for ringtone playback. Attempts to open
     * locally, otherwise will delegate playback to remote
     * {@link IRingtonePlayer}. Add {@link VolumeShaper} if required.
     *
     * @hide
     */
    public void setUri(Uri uri, @Nullable VolumeShaper.Configuration volumeShaperConfig) {
        mVolumeShaperConfig = volumeShaperConfig;
        mUri = uri;
        if (mUri == null) {
            stopAndReleaseActivePlayer();
        }
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
        int ringtoneType = RingtoneManager.getDefaultType(mUri);
        if (ringtoneType != -1 &&
                RingtoneManager.getActualDefaultRingtoneUri(mContext, ringtoneType) == null) {
            Log.w(TAG, "not playing fallback for " + mUri);
            return false;
        }
        // Default ringtone, try fallback ringtone.
        AssetFileDescriptor afd;
        try {
            afd = mContext.getResources().openRawResourceFd(
                    com.android.internal.R.raw.fallbackring);
        } catch (NotFoundException nfe) {
            Log.e(TAG, "Fallback ringtone does not exist");
            return false;
        }
        if (afd == null) {
            Log.e(TAG, "Could not load fallback ringtone");
            return false;
        }

        try {
            AudioDeviceInfo preferredDevice =
                    mPreferBuiltinDevice ? getBuiltinDevice(mAudioManager) : null;
            mActivePlayer = LocalRingtonePlayer.createForFallback(mAudioManager, afd,
                    mAudioAttributes, mVolumeShaperConfig, preferredDevice, mIsLooping, mVolume);
            if (mActivePlayer == null) {
                return false;
            } else if (mActivePlayer.play()) {
                return true;
            } else {
                stopAndReleaseActivePlayer();
                return false;
            }
        } finally {
            try {
                afd.close();
            } catch (IOException e) {
                // As with the above messages, not including much information about the
                // failure so as not to expose details of the fallback ringtone resource.
                Log.e(TAG, "Exception closing fallback ringtone");
            }
        }
    }

    void setTitle(String title) {
        mTitle = title;
    }

    @Override
    protected void finalize() {
        if (mActivePlayer != null) {
            mActivePlayer.stopAndRelease();
        }
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
        private final VolumeShaper.Configuration mVolumeShaperConfig;
        private final AudioAttributes mAudioAttributes;
        private boolean mIsLooping;
        private float mVolume;
        private boolean mIsHapticGeneratorEnabled;

        RemoteRingtonePlayer(@NonNull IRingtonePlayer remoteRingtoneService,
                @NonNull Uri uri, @NonNull AudioAttributes audioAttributes,
                @Nullable VolumeShaper.Configuration volumeShaperConfig,
                boolean isHapticGeneratorEnabled, boolean initialIsLooping, float initialVolume) {
            mRemoteRingtoneService = remoteRingtoneService;
            mCanonicalUri = uri.getCanonicalUri();
            mAudioAttributes = audioAttributes;
            mVolumeShaperConfig = volumeShaperConfig;
            mIsHapticGeneratorEnabled = isHapticGeneratorEnabled;
            mIsLooping = initialIsLooping;
            mVolume = initialVolume;
        }

        @Override
        public boolean play() {
            try {
                mRemoteRingtoneService.playWithVolumeShaping(mRemoteToken, mCanonicalUri,
                        mAudioAttributes, mVolume, mIsLooping, mIsHapticGeneratorEnabled,
                        mVolumeShaperConfig);
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
            mIsHapticGeneratorEnabled = enabled;
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
