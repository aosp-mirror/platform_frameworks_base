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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
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
    private static final ArrayList<LocalRingtonePlayer> sActiveMediaPlayers = new ArrayList<>();

    private final MediaPlayer mMediaPlayer;
    private final AudioAttributes mAudioAttributes;
    private final Ringtone.RingtonePlayer mVibrationPlayer;
    private final Ringtone.Injectables mInjectables;
    private final AudioManager mAudioManager;
    private final VolumeShaper mVolumeShaper;
    private HapticGenerator mHapticGenerator;

    private LocalRingtonePlayer(@NonNull MediaPlayer mediaPlayer,
            @NonNull AudioAttributes audioAttributes, @NonNull Ringtone.Injectables injectables,
            @NonNull AudioManager audioManager, @Nullable HapticGenerator hapticGenerator,
            @Nullable VolumeShaper volumeShaper,
            @Nullable Ringtone.RingtonePlayer vibrationPlayer) {
        Objects.requireNonNull(mediaPlayer);
        Objects.requireNonNull(audioAttributes);
        Objects.requireNonNull(injectables);
        Objects.requireNonNull(audioManager);
        mMediaPlayer = mediaPlayer;
        mAudioAttributes = audioAttributes;
        mInjectables = injectables;
        mAudioManager = audioManager;
        mVolumeShaper = volumeShaper;
        mVibrationPlayer = vibrationPlayer;
        mHapticGenerator = hapticGenerator;
    }

    /**
     * Creates a {@link LocalRingtonePlayer} for a Uri, returning null if the Uri can't be
     * loaded in the local player.
     */
    @Nullable
    static Ringtone.RingtonePlayer create(@NonNull Context context,
            @NonNull AudioManager audioManager, @NonNull Vibrator vibrator,
            @NonNull Uri soundUri,
            @NonNull AudioAttributes audioAttributes,
            boolean isVibrationOnly,
            @Nullable VibrationEffect vibrationEffect,
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
            mediaPlayer.setVolume(isVibrationOnly ? 0 : initialVolume);
            if (initialHapticGeneratorEnabled) {
                hapticGenerator = injectables.createHapticGenerator(mediaPlayer);
                if (hapticGenerator != null) {
                    // In practise, this should always be non-null because the initial value is
                    // not true unless it's available.
                    hapticGenerator.setEnabled(true);
                    vibrationEffect = null;  // Don't play the VibrationEffect.
                }
            }
            VolumeShaper volumeShaper = null;
            if (volumeShaperConfig != null) {
                volumeShaper = mediaPlayer.createVolumeShaper(volumeShaperConfig);
            }
            mediaPlayer.prepare();
            if (vibrationEffect != null && !audioAttributes.areHapticChannelsMuted()) {
                if (injectables.hasHapticChannels(mediaPlayer)) {
                    // Don't play the Vibration effect if the URI has haptic channels.
                    vibrationEffect = null;
                }
            }
            VibrationEffectPlayer vibrationEffectPlayer = (vibrationEffect == null) ? null :
                    new VibrationEffectPlayer(
                            vibrationEffect, audioAttributes, vibrator, initialLooping);
            if (isVibrationOnly && vibrationEffectPlayer != null) {
                // Abandon the media player now that it's confirmed to not have haptic channels.
                mediaPlayer.release();
                return vibrationEffectPlayer;
            }
            return new LocalRingtonePlayer(mediaPlayer, audioAttributes, injectables, audioManager,
                    hapticGenerator, volumeShaper, vibrationEffectPlayer);
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
            @NonNull AudioManager audioManager, @NonNull Vibrator vibrator,
            @NonNull AssetFileDescriptor afd,
            @NonNull AudioAttributes audioAttributes,
            @Nullable VibrationEffect vibrationEffect,
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
            if (vibrationEffect != null && !audioAttributes.areHapticChannelsMuted()) {
                if (injectables.hasHapticChannels(mediaPlayer)) {
                    // Don't play the Vibration effect if the URI has haptic channels.
                    vibrationEffect = null;
                }
            }
            VibrationEffectPlayer vibrationEffectPlayer = (vibrationEffect == null) ? null :
                    new VibrationEffectPlayer(
                            vibrationEffect, audioAttributes, vibrator, initialLooping);
            return new LocalRingtonePlayer(mediaPlayer, audioAttributes,  injectables, audioManager,
                    /* hapticGenerator= */ null, volumeShaper, vibrationEffectPlayer);
        } catch (SecurityException | IOException e) {
            Log.e(TAG, "Failed to open fallback ringtone");
            // TODO: vibration-effect-only / no-sound LocalRingtonePlayer.
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
            maybeStartVibration();
            return true;  // Successfully played while muted.
        }
        synchronized (sActiveMediaPlayers) {
            // We keep-alive when a mediaplayer is active, since its finalizer would stop the
            // ringtone. This isn't necessary for vibrations in the vibrator service
            // (i.e. maybeStartVibration in the muted case, above).
            sActiveMediaPlayers.add(this);
        }

        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.start();
        if (mVolumeShaper != null) {
            mVolumeShaper.apply(VolumeShaper.Operation.PLAY);
        }
        maybeStartVibration();
        return true;
    }

    private void maybeStartVibration() {
        if (mVibrationPlayer != null) {
            mVibrationPlayer.play();
        }
    }

    @Override
    public boolean isPlaying() {
        return mMediaPlayer.isPlaying();
    }

    @Override
    public void stopAndRelease() {
        synchronized (sActiveMediaPlayers) {
            sActiveMediaPlayers.remove(this);
        }
        try {
            mMediaPlayer.stop();
        } finally {
            if (mVibrationPlayer != null) {
                try {
                    mVibrationPlayer.stopAndRelease();
                } catch (Exception e) {
                    Log.e(TAG, "Exception stopping ringtone vibration", e);
                }
            }
            if (mHapticGenerator != null) {
                mHapticGenerator.release();
            }
            mMediaPlayer.setOnCompletionListener(null);
            mMediaPlayer.reset();
            mMediaPlayer.release();
        }
    }

    @Override
    public void setPreferredDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
        mMediaPlayer.setPreferredDevice(audioDeviceInfo);
    }

    @Override
    public void setLooping(boolean looping) {
        boolean wasLooping = mMediaPlayer.isLooping();
        if (wasLooping == looping) {
            return;
        }
        mMediaPlayer.setLooping(looping);
        if (mVibrationPlayer != null) {
            mVibrationPlayer.setLooping(looping);
        }
    }

    @Override
    public void setHapticGeneratorEnabled(boolean enabled) {
        if (mVibrationPlayer != null) {
            // Ignore haptic generator changes if a vibration player is present. The decision to
            // use one or the other happens before this object is constructed.
            return;
        }
        if (enabled && mHapticGenerator == null && !hasHapticChannels()) {
            mHapticGenerator = mInjectables.createHapticGenerator(mMediaPlayer);
        }
        if (mHapticGenerator != null) {
            mHapticGenerator.setEnabled(enabled);
        }
    }

    @Override
    public void setVolume(float volume) {
        mMediaPlayer.setVolume(volume);
        // no effect on vibration player
    }

    @Override
    public boolean hasHapticChannels() {
        return mInjectables.hasHapticChannels(mMediaPlayer);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        synchronized (sActiveMediaPlayers) {
            sActiveMediaPlayers.remove(this);
        }
        mp.setOnCompletionListener(null); // Help the Java GC: break the refcount cycle.
        // No effect on vibration: either it's looping and this callback only happens when stopped,
        // or it's not looping, in which case the vibration should play to its own completion.
    }

    /** A RingtonePlayer that only plays a VibrationEffect. */
    static class VibrationEffectPlayer implements Ringtone.RingtonePlayer {
        private static final int VIBRATION_LOOP_DELAY_MS = 200;
        private final VibrationEffect mVibrationEffect;
        private final VibrationAttributes mVibrationAttributes;
        private final Vibrator mVibrator;
        private boolean mIsLooping;
        private boolean mStartedVibration;

        VibrationEffectPlayer(@NonNull VibrationEffect vibrationEffect,
                @NonNull AudioAttributes audioAttributes,
                @NonNull Vibrator vibrator, boolean initialLooping) {
            mVibrationEffect = vibrationEffect;
            mVibrationAttributes = new VibrationAttributes.Builder(audioAttributes).build();
            mVibrator = vibrator;
            mIsLooping = initialLooping;
        }

        @Override
        public boolean play() {
            if (!mStartedVibration) {
                try {
                    // Adjust the vibration effect to loop.
                    VibrationEffect loopAdjustedEffect =
                            mVibrationEffect.applyRepeatingIndefinitely(
                                mIsLooping, VIBRATION_LOOP_DELAY_MS);
                    mVibrator.vibrate(loopAdjustedEffect, mVibrationAttributes);
                    mStartedVibration = true;
                } catch (Exception e) {
                    // Catch exceptions widely, because we don't want to "leak" looping sounds or
                    // vibrations if something goes wrong.
                    Log.e(TAG, "Problem starting " + (mIsLooping ? "looping " : "") + "vibration "
                            + "for ringtone: " + mVibrationEffect, e);
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isPlaying() {
            return mStartedVibration;
        }

        @Override
        public void stopAndRelease() {
            if (mStartedVibration) {
                try {
                    mVibrator.cancel(mVibrationAttributes.getUsage());
                    mStartedVibration = false;
                } catch (Exception e) {
                    // Catch exceptions widely, because we don't want to "leak" looping sounds or
                    // vibrations if something goes wrong.
                    Log.e(TAG, "Problem stopping vibration for ringtone", e);
                }
            }
        }

        @Override
        public void setPreferredDevice(AudioDeviceInfo audioDeviceInfo) {
            // no-op
        }

        @Override
        public void setLooping(boolean looping) {
            if (looping == mIsLooping) {
                return;
            }
            mIsLooping = looping;
            if (mStartedVibration) {
                if (!mIsLooping) {
                    // Was looping, stop looping
                    stopAndRelease();
                }
                // Else was not looping, but can't interfere with a running vibration without
                // restarting it, and don't know if it was finished. So do nothing: apps shouldn't
                // toggle looping after calling play anyway.
            }
        }

        @Override
        public void setHapticGeneratorEnabled(boolean enabled) {
            // n/a
        }

        @Override
        public void setVolume(float volume) {
            // n/a
        }

        @Override
        public boolean hasHapticChannels() {
            return false;
        }
    }
}
