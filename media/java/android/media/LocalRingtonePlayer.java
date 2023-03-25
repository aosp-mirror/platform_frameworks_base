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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.audiofx.HapticGenerator;
import android.net.Uri;
import android.os.Trace;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Plays a ringtone on the local process.
 * @hide
 */
public class LocalRingtonePlayer
        implements Ringtone.RingtonePlayer, MediaPlayer.OnCompletionListener {
    private static final String TAG = "LocalRingtonePlayer";

    // keep references on active Ringtones until stopped or completion listener called.
    private static final ArrayList<LocalRingtonePlayer> sActiveRingtones = new ArrayList<>();

    private final MediaPlayer mMediaPlayer;
    private final AudioAttributes mAudioAttributes;
    private final Ringtone.Injectables mInjectables;
    private final AudioManager mAudioManager;
    private final VolumeShaper mVolumeShaper;
    private HapticGenerator mHapticGenerator;

    private LocalRingtonePlayer(@NonNull MediaPlayer mediaPlayer,
            @NonNull AudioAttributes audioAttributes, @NonNull Ringtone.Injectables injectables,
            @NonNull AudioManager audioManager, @Nullable HapticGenerator hapticGenerator,
            @Nullable VolumeShaper volumeShaper) {
        Objects.requireNonNull(mediaPlayer);
        Objects.requireNonNull(audioAttributes);
        Objects.requireNonNull(injectables);
        Objects.requireNonNull(audioManager);
        mMediaPlayer = mediaPlayer;
        mAudioAttributes = audioAttributes;
        mInjectables = injectables;
        mAudioManager = audioManager;
        mVolumeShaper = volumeShaper;
        mHapticGenerator = hapticGenerator;
    }

    /**
     * Creates a {@link LocalRingtonePlayer} for a Uri, returning null if the Uri can't be
     * loaded in the local player.
     */
    @Nullable
    static LocalRingtonePlayer create(@NonNull Context context,
            @NonNull AudioManager audioManager, @NonNull Uri soundUri,
            @NonNull AudioAttributes audioAttributes,
            @NonNull Ringtone.Injectables injectables,
            @Nullable VolumeShaper.Configuration volumeShaperConfig,
            @Nullable AudioDeviceInfo preferredDevice, boolean initialHapticGeneratorEnabled,
            boolean initialLooping, float initialVolume) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(soundUri);
        Objects.requireNonNull(audioAttributes);
        Trace.beginSection("createLocalMediaPlayer");
        MediaPlayer mediaPlayer = injectables.newMediaPlayer();
        HapticGenerator hapticGenerator = null;
        try {
            mediaPlayer.setDataSource(context, soundUri);
            mediaPlayer.setAudioAttributes(audioAttributes);
            mediaPlayer.setPreferredDevice(preferredDevice);
            mediaPlayer.setLooping(initialLooping);
            mediaPlayer.setVolume(initialVolume);
            if (initialHapticGeneratorEnabled) {
                hapticGenerator = injectables.createHapticGenerator(mediaPlayer);
                hapticGenerator.setEnabled(true);
            }
            VolumeShaper volumeShaper = null;
            if (volumeShaperConfig != null) {
                volumeShaper = mediaPlayer.createVolumeShaper(volumeShaperConfig);
            }
            mediaPlayer.prepare();
            return new LocalRingtonePlayer(mediaPlayer, audioAttributes, injectables, audioManager,
                    hapticGenerator, volumeShaper);
        } catch (SecurityException | IOException e) {
            if (hapticGenerator != null) {
                hapticGenerator.release();
            }
            // volume shaper closes with media player
            mediaPlayer.release();
            return null;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Creates a {@link LocalRingtonePlayer} for an externally referenced file descriptor. This is
     * intended for loading a fallback from an internal resource, rather than via a Uri.
     */
    @Nullable
    static LocalRingtonePlayer createForFallback(
            @NonNull AudioManager audioManager, @NonNull AssetFileDescriptor afd,
            @NonNull AudioAttributes audioAttributes,
            @NonNull Ringtone.Injectables injectables,
            @Nullable VolumeShaper.Configuration volumeShaperConfig,
            @Nullable AudioDeviceInfo preferredDevice,
            boolean initialLooping, float initialVolume) {
        // Haptic generator not supported for fallback.
        Objects.requireNonNull(audioManager);
        Objects.requireNonNull(afd);
        Objects.requireNonNull(audioAttributes);
        Trace.beginSection("createFallbackLocalMediaPlayer");

        MediaPlayer mediaPlayer = injectables.newMediaPlayer();
        try {
            if (afd.getDeclaredLength() < 0) {
                mediaPlayer.setDataSource(afd.getFileDescriptor());
            } else {
                mediaPlayer.setDataSource(afd.getFileDescriptor(),
                        afd.getStartOffset(),
                        afd.getDeclaredLength());
            }
            mediaPlayer.setAudioAttributes(audioAttributes);
            mediaPlayer.setPreferredDevice(preferredDevice);
            mediaPlayer.setLooping(initialLooping);
            mediaPlayer.setVolume(initialVolume);
            VolumeShaper volumeShaper = null;
            if (volumeShaperConfig != null) {
                volumeShaper = mediaPlayer.createVolumeShaper(volumeShaperConfig);
            }
            mediaPlayer.prepare();
            return new LocalRingtonePlayer(mediaPlayer, audioAttributes, injectables, audioManager,
                    /* hapticGenerator= */ null, volumeShaper);
        } catch (SecurityException | IOException e) {
            Log.e(TAG, "Failed to open fallback ringtone");
            mediaPlayer.release();
            return null;
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public boolean play() {
        // Play ringtones if stream volume is over 0 or if it is a haptic-only ringtone
        // (typically because ringer mode is vibrate).
        if (mAudioManager.getStreamVolume(AudioAttributes.toLegacyStreamType(mAudioAttributes))
                == 0 && (mAudioAttributes.areHapticChannelsMuted() || !hasHapticChannels())) {
            return true;  // Successfully played while muted.
        }
        synchronized (sActiveRingtones) {
            sActiveRingtones.add(this);
        }

        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.start();
        if (mVolumeShaper != null) {
            mVolumeShaper.apply(VolumeShaper.Operation.PLAY);
        }
        return true;
    }

    @Override
    public boolean isPlaying() {
        return mMediaPlayer.isPlaying();
    }

    @Override
    public void stopAndRelease() {
        synchronized (sActiveRingtones) {
            sActiveRingtones.remove(this);
        }
        if (mHapticGenerator != null) {
            mHapticGenerator.release();
        }
        mMediaPlayer.setOnCompletionListener(null);
        mMediaPlayer.reset();
        mMediaPlayer.release();
    }

    @Override
    public void setPreferredDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
        mMediaPlayer.setPreferredDevice(audioDeviceInfo);
    }

    @Override
    public void setLooping(boolean looping) {
        mMediaPlayer.setLooping(looping);
    }

    @Override
    public void setHapticGeneratorEnabled(boolean enabled) {
        if (enabled && mHapticGenerator == null) {
            mHapticGenerator = mInjectables.createHapticGenerator(mMediaPlayer);
        }
        if (mHapticGenerator != null) {
            mHapticGenerator.setEnabled(enabled);
        }
    }

    @Override
    public void setVolume(float volume) {
        mMediaPlayer.setVolume(volume);
    }

    /**
     * Same as AudioManager.hasHapticChannels except it assumes an already created ringtone.
     * @hide
     */
    @Override
    public boolean hasHapticChannels() {
        // FIXME: support remote player, or internalize haptic channels support and remove entirely.
        try {
            Trace.beginSection("LocalRingtonePlayer.hasHapticChannels");
            for (MediaPlayer.TrackInfo trackInfo : mMediaPlayer.getTrackInfo()) {
                if (trackInfo.hasHapticChannels()) {
                    return true;
                }
            }
        } finally {
            Trace.endSection();
        }
        return false;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        synchronized (sActiveRingtones) {
            sActiveRingtones.remove(this);
        }
        mp.setOnCompletionListener(null); // Help the Java GC: break the refcount cycle.
    }
}
