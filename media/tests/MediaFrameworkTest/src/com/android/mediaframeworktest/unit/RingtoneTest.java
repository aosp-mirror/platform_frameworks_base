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

package com.android.mediaframeworktest.unit;

import static android.media.Ringtone.MEDIA_SOUND;
import static android.media.Ringtone.MEDIA_SOUND_AND_VIBRATION;
import static android.media.Ringtone.MEDIA_VIBRATION;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.IRingtonePlayer;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.audiofx.HapticGenerator;
import android.net.Uri;
import android.os.IBinder;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.testing.TestableContext;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.mediaframeworktest.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.FileNotFoundException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;

@RunWith(AndroidJUnit4.class)
public class RingtoneTest {

    private static final Uri SOUND_URI = Uri.parse("content://fake-sound-uri");

    private static final AudioAttributes RINGTONE_ATTRIBUTES =
            audioAttributes(AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
    private static final AudioAttributes RINGTONE_ATTRIBUTES_WITH_HC =
            new AudioAttributes.Builder(RINGTONE_ATTRIBUTES).setHapticChannelsMuted(false).build();
    private static final VibrationAttributes RINGTONE_VIB_ATTRIBUTES =
            new VibrationAttributes.Builder(RINGTONE_ATTRIBUTES).build();

    private static final VibrationEffect VIBRATION_EFFECT =
            VibrationEffect.createWaveform(new long[] { 0, 100, 50, 100}, -1);
    private static final VibrationEffect VIBRATION_EFFECT_REPEATING =
            VibrationEffect.createWaveform(new long[] { 0, 100, 50, 100, 50}, 1);

    @Rule
    public final RingtoneInjectablesTrackingTestRule
            mMediaPlayerRule = new RingtoneInjectablesTrackingTestRule();

    @Captor private ArgumentCaptor<IBinder> mIBinderCaptor;
    @Mock private IRingtonePlayer mMockRemotePlayer;
    @Mock private Vibrator mMockVibrator;
    private AudioManager mSpyAudioManager;
    private TestableContext mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        TestableContext testContext =
                new TestableContext(InstrumentationRegistry.getTargetContext(), null);
        testContext.getTestablePermissions().setPermission(Manifest.permission.VIBRATE,
                PackageManager.PERMISSION_GRANTED);
        AudioManager realAudioManager = testContext.getSystemService(AudioManager.class);
        mSpyAudioManager = spy(realAudioManager);
        when(mSpyAudioManager.getRingtonePlayer()).thenReturn(mMockRemotePlayer);
        testContext.addMockSystemService(AudioManager.class, mSpyAudioManager);
        testContext.addMockSystemService(Vibrator.class, mMockVibrator);

        mContext = spy(testContext);
    }

    @Test
    public void testRingtone_fullLifecycleUsingLocalMediaPlayer() throws Exception {
        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        Ringtone ringtone =
                newBuilder(MEDIA_SOUND, RINGTONE_ATTRIBUTES).setUri(SOUND_URI).build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_SOUND);
        assertThat(ringtone.getUri()).isEqualTo(SOUND_URI);
        assertThat(ringtone.getAudioAttributes()).isEqualTo(RINGTONE_ATTRIBUTES);
        assertThat(ringtone.getVolume()).isEqualTo(1.0f);
        assertThat(ringtone.isLooping()).isEqualTo(false);
        assertThat(ringtone.isHapticGeneratorEnabled()).isEqualTo(false);
        assertThat(ringtone.getPreferBuiltinDevice()).isFalse();
        assertThat(ringtone.getVolumeShaperConfig()).isNull();
        assertThat(ringtone.isLocalOnly()).isFalse();

        // Prepare
        verifyLocalPlayerSetup(mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES);
        verify(mockMediaPlayer).setVolume(1.0f);
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        verifyLocalPlay(mockMediaPlayer);

        // Verify dynamic controls.
        ringtone.setVolume(0.8f);
        verify(mockMediaPlayer).setVolume(0.8f);
        when(mockMediaPlayer.isLooping()).thenReturn(false);
        ringtone.setLooping(true);
        verify(mockMediaPlayer).isLooping();
        verify(mockMediaPlayer).setLooping(true);
        HapticGenerator mockHapticGenerator =
                mMediaPlayerRule.expectHapticGenerator(mockMediaPlayer);
        ringtone.setHapticGeneratorEnabled(true);
        verify(mockHapticGenerator).setEnabled(true);

        // Release
        ringtone.stop();
        verifyLocalStop(mockMediaPlayer);

        // This test is intended to strictly verify all interactions with MediaPlayer in a local
        // playback case. This shouldn't be necessary in other tests that have the same basic
        // setup.
        verifyNoMoreInteractions(mockMediaPlayer);
        verify(mockHapticGenerator).release();
        verifyNoMoreInteractions(mockHapticGenerator);
        verifyZeroInteractions(mMockRemotePlayer);
        verifyZeroInteractions(mMockVibrator);
    }

    @Test
    public void testRingtone_localMediaPlayerWithAudioCoupledOverride() throws Exception {
        // Audio coupled playback is enabled in the incoming attributes, plus an instruction
        // to leave the attributes alone. This test verifies that the attributes reach the
        // media player without changing.
        final AudioAttributes audioAttributes = RINGTONE_ATTRIBUTES_WITH_HC;
        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        mMediaPlayerRule.setHasHapticChannels(mockMediaPlayer, true);
        Ringtone ringtone =
                newBuilder(MEDIA_SOUND, audioAttributes)
                        .setUri(SOUND_URI)
                        .setUseExactAudioAttributes(true)
                        .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_SOUND);
        assertThat(ringtone.getUri()).isEqualTo(SOUND_URI);
        assertThat(ringtone.getAudioAttributes()).isEqualTo(audioAttributes);

        // Prepare
        verifyLocalPlayerSetup(mockMediaPlayer, SOUND_URI, audioAttributes);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        verifyLocalPlay(mockMediaPlayer);

        // Release
        ringtone.stop();
        verifyLocalStop(mockMediaPlayer);

        verifyZeroInteractions(mMockRemotePlayer);
        verifyZeroInteractions(mMockVibrator);
    }

    @Test
    public void testRingtone_fullLifecycleUsingRemoteMediaPlayer() throws Exception {
        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        setupFileNotFound(mockMediaPlayer, SOUND_URI);
        Ringtone ringtone =
                newBuilder(MEDIA_SOUND, RINGTONE_ATTRIBUTES)
                .setUri(SOUND_URI)
                .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isTrue();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_SOUND);
        assertThat(ringtone.getUri()).isEqualTo(SOUND_URI);
        assertThat(ringtone.getAudioAttributes()).isEqualTo(RINGTONE_ATTRIBUTES);
        assertThat(ringtone.getVolume()).isEqualTo(1.0f);
        assertThat(ringtone.isLooping()).isEqualTo(false);
        assertThat(ringtone.isHapticGeneratorEnabled()).isEqualTo(false);
        assertThat(ringtone.getPreferBuiltinDevice()).isFalse();
        assertThat(ringtone.getVolumeShaperConfig()).isNull();
        assertThat(ringtone.isLocalOnly()).isFalse();

        // Initialization did try to create a local media player.
        verify(mockMediaPlayer).setDataSource(mContext, SOUND_URI);
        // setDataSource throws file not found, so nothing else will happen on the local player.
        verify(mockMediaPlayer).release();

        // Delegates to remote media player.
        ringtone.play();
        verify(mMockRemotePlayer).playRemoteRingtone(mIBinderCaptor.capture(), eq(SOUND_URI),
                eq(RINGTONE_ATTRIBUTES), eq(false), eq(MEDIA_SOUND), isNull(),
                eq(1.0f), eq(false), eq(false), isNull());
        IBinder remoteToken = mIBinderCaptor.getValue();

        // Verify dynamic controls.
        ringtone.setVolume(0.8f);
        verify(mMockRemotePlayer).setVolume(remoteToken, 0.8f);
        ringtone.setLooping(true);
        verify(mMockRemotePlayer).setLooping(remoteToken, true);
        ringtone.setHapticGeneratorEnabled(true);
        verify(mMockRemotePlayer).setHapticGeneratorEnabled(remoteToken, true);

        ringtone.stop();
        verify(mMockRemotePlayer).stop(remoteToken);
        verifyNoMoreInteractions(mMockRemotePlayer);
        verifyNoMoreInteractions(mockMediaPlayer);
        verifyZeroInteractions(mMockVibrator);
    }

    @Test
    public void testRingtone_localMediaWithVibration() throws Exception {
        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        when(mMockVibrator.hasVibrator()).thenReturn(true);
        Ringtone ringtone =
                newBuilder(MEDIA_SOUND_AND_VIBRATION, RINGTONE_ATTRIBUTES)
                        .setUri(SOUND_URI)
                        .setVibrationEffect(VIBRATION_EFFECT)
                        .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();
        verify(mMockVibrator).hasVibrator();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_SOUND_AND_VIBRATION);
        assertThat(ringtone.getUri()).isEqualTo(SOUND_URI);
        assertThat(ringtone.getVibrationEffect()).isEqualTo(VIBRATION_EFFECT);

        // Prepare
        // Uses attributes with haptic channels enabled, but will use the effect when there aren't
        // any present.
        verifyLocalPlayerSetup(mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
        verify(mockMediaPlayer).setVolume(1.0f);
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();

        verifyLocalPlay(mockMediaPlayer);
        verify(mMockVibrator).vibrate(VIBRATION_EFFECT, RINGTONE_VIB_ATTRIBUTES);

        // Verify dynamic controls.
        ringtone.setVolume(0.8f);
        verify(mockMediaPlayer).setVolume(0.8f);

        // Set looping doesn't affect an already-started vibration.
        when(mockMediaPlayer.isLooping()).thenReturn(false);  // Checks original
        ringtone.setLooping(true);
        verify(mockMediaPlayer).isLooping();
        verify(mockMediaPlayer).setLooping(true);

        // This is ignored because there's a vibration effect being used.
        ringtone.setHapticGeneratorEnabled(true);

        // Release
        ringtone.stop();
        verifyLocalStop(mockMediaPlayer);
        verify(mMockVibrator).cancel(VibrationAttributes.USAGE_RINGTONE);

        // This test is intended to strictly verify all interactions with MediaPlayer in a local
        // playback case. This shouldn't be necessary in other tests that have the same basic
        // setup.
        verifyNoMoreInteractions(mockMediaPlayer);
        verifyZeroInteractions(mMockRemotePlayer);
        verifyNoMoreInteractions(mMockVibrator);
    }

    @Test
    public void testRingtone_localMediaWithVibrationOnly() throws Exception {
        when(mMockVibrator.hasVibrator()).thenReturn(true);
        Ringtone ringtone =
                newBuilder(MEDIA_VIBRATION, RINGTONE_ATTRIBUTES)
                        // TODO: set sound uri too in diff test
                        .setVibrationEffect(VIBRATION_EFFECT)
                        .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();
        verify(mMockVibrator).hasVibrator();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_VIBRATION);
        assertThat(ringtone.getUri()).isNull();
        assertThat(ringtone.getVibrationEffect()).isEqualTo(VIBRATION_EFFECT);

        // Play
        ringtone.play();

        verify(mMockVibrator).vibrate(VIBRATION_EFFECT, RINGTONE_VIB_ATTRIBUTES);

        // Verify dynamic controls (no-op without sound)
        ringtone.setVolume(0.8f);

        // Set looping doesn't affect an already-started vibration.
        ringtone.setLooping(true);

        // This is ignored because there's a vibration effect being used and no sound.
        ringtone.setHapticGeneratorEnabled(true);

        // Release
        ringtone.stop();
        verify(mMockVibrator).cancel(VibrationAttributes.USAGE_RINGTONE);

        // This test is intended to strictly verify all interactions with MediaPlayer in a local
        // playback case. This shouldn't be necessary in other tests that have the same basic
        // setup.
        verifyZeroInteractions(mMockRemotePlayer);
        verifyNoMoreInteractions(mMockVibrator);
    }

    @Test
    public void testRingtone_localMediaWithVibrationOnlyAndSoundUriNoHapticChannels()
            throws Exception {
        // A media player will still be created for vibration-only because the vibration can come
        // from haptic channels on the sound file (although in this case it doesn't).
        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        mMediaPlayerRule.setHasHapticChannels(mockMediaPlayer, false);
        when(mMockVibrator.hasVibrator()).thenReturn(true);
        Ringtone ringtone =
                newBuilder(MEDIA_VIBRATION, RINGTONE_ATTRIBUTES)
                        .setUri(SOUND_URI)
                        .setVibrationEffect(VIBRATION_EFFECT)
                        .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();
        verify(mMockVibrator).hasVibrator();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_VIBRATION);
        assertThat(ringtone.getUri()).isEqualTo(SOUND_URI);
        assertThat(ringtone.getVibrationEffect()).isEqualTo(VIBRATION_EFFECT);

        // Prepare
        // Uses attributes with haptic channels enabled, but will abandon the MediaPlayer when it
        // knows there aren't any.
        verifyLocalPlayerSetup(mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
        verify(mockMediaPlayer).setVolume(0.0f);  // Vibration-only: sound muted.
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();
        verify(mockMediaPlayer).release();  // abandoned: no haptic channels.

        // Play
        ringtone.play();

        verify(mMockVibrator).vibrate(VIBRATION_EFFECT, RINGTONE_VIB_ATTRIBUTES);

        // Verify dynamic controls (no-op without sound)
        ringtone.setVolume(0.8f);

        // Set looping doesn't affect an already-started vibration.
        ringtone.setLooping(true);

        // This is ignored because there's a vibration effect being used and no sound.
        ringtone.setHapticGeneratorEnabled(true);

        // Release
        ringtone.stop();
        verify(mMockVibrator).cancel(VibrationAttributes.USAGE_RINGTONE);

        // This test is intended to strictly verify all interactions with MediaPlayer in a local
        // playback case. This shouldn't be necessary in other tests that have the same basic
        // setup.
        verifyZeroInteractions(mMockRemotePlayer);
        verifyNoMoreInteractions(mMockVibrator);
        verifyNoMoreInteractions(mockMediaPlayer);
    }

    @Test
    public void testRingtone_localMediaWithVibrationOnlyAndSoundUriWithHapticChannels()
            throws Exception {
        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        when(mMockVibrator.hasVibrator()).thenReturn(true);
        mMediaPlayerRule.setHasHapticChannels(mockMediaPlayer, true);
        Ringtone ringtone =
                newBuilder(MEDIA_VIBRATION, RINGTONE_ATTRIBUTES)
                        .setUri(SOUND_URI)
                        .setVibrationEffect(VIBRATION_EFFECT)
                        .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();
        verify(mMockVibrator).hasVibrator();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_VIBRATION);
        assertThat(ringtone.getUri()).isEqualTo(SOUND_URI);
        assertThat(ringtone.getVibrationEffect()).isEqualTo(VIBRATION_EFFECT);

        // Prepare
        // Uses attributes with haptic channels enabled, but will use the effect when there aren't
        // any present.
        verifyLocalPlayerSetup(mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
        verify(mockMediaPlayer).setVolume(0.0f);  // Vibration-only: sound muted.
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        // Vibrator.vibrate isn't called because the vibration comes from the sound.
        verifyLocalPlay(mockMediaPlayer);

        // Verify dynamic controls (no-op without sound)
        ringtone.setVolume(0.8f);

        when(mockMediaPlayer.isLooping()).thenReturn(false);  // Checks original
        ringtone.setLooping(true);
        verify(mockMediaPlayer).isLooping();
        verify(mockMediaPlayer).setLooping(true);

        // This is ignored because it's using haptic channels.
        ringtone.setHapticGeneratorEnabled(true);

        // Release
        ringtone.stop();
        verifyLocalStop(mockMediaPlayer);

        // This test is intended to strictly verify all interactions with MediaPlayer in a local
        // playback case. This shouldn't be necessary in other tests that have the same basic
        // setup.
        verifyZeroInteractions(mMockRemotePlayer);
        verifyZeroInteractions(mMockVibrator);
    }

    @Test
    public void testRingtone_localMediaWithVibrationPrefersHapticChannels() throws Exception {
        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        mMediaPlayerRule.setHasHapticChannels(mockMediaPlayer, true);
        when(mMockVibrator.hasVibrator()).thenReturn(true);
        Ringtone ringtone =
                newBuilder(MEDIA_SOUND_AND_VIBRATION, RINGTONE_ATTRIBUTES)
                        .setUri(SOUND_URI)
                        .setVibrationEffect(VIBRATION_EFFECT)
                        .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();
        verify(mMockVibrator).hasVibrator();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_SOUND_AND_VIBRATION);
        assertThat(ringtone.getUri()).isEqualTo(SOUND_URI);
        assertThat(ringtone.getVibrationEffect()).isEqualTo(VIBRATION_EFFECT);

        // Prepare
        // The attributes here have haptic channels enabled (unlike above)
        verifyLocalPlayerSetup(mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        when(mockMediaPlayer.isPlaying()).thenReturn(true);
        verifyLocalPlay(mockMediaPlayer);

        // Release
        ringtone.stop();
        verifyLocalStop(mockMediaPlayer);

        verifyZeroInteractions(mMockRemotePlayer);
        // Nothing after the initial hasVibrator - it uses audio-coupled.
        verifyNoMoreInteractions(mMockVibrator);
    }

    @Test
    public void testRingtone_localMediaWithVibrationButSoundMuted() throws Exception {
        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        mMediaPlayerRule.setHasHapticChannels(mockMediaPlayer, false);
        doReturn(0).when(mSpyAudioManager)
                .getStreamVolume(AudioAttributes.toLegacyStreamType(RINGTONE_ATTRIBUTES));
        when(mMockVibrator.hasVibrator()).thenReturn(true);
        Ringtone ringtone =
                newBuilder(MEDIA_SOUND_AND_VIBRATION, RINGTONE_ATTRIBUTES)
                        .setUri(SOUND_URI)
                        .setVibrationEffect(VIBRATION_EFFECT)
                        .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();
        verify(mMockVibrator).hasVibrator();

        // Verify all the properties.
        assertThat(ringtone.getEnabledMedia()).isEqualTo(MEDIA_SOUND_AND_VIBRATION);
        assertThat(ringtone.getUri()).isEqualTo(SOUND_URI);
        assertThat(ringtone.getVibrationEffect()).isEqualTo(VIBRATION_EFFECT);

        // Prepare
        // The attributes here have haptic channels enabled (unlike above)
        verifyLocalPlayerSetup(mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        // The media player is never played, because sound is muted.
        verify(mockMediaPlayer, never()).start();
        when(mockMediaPlayer.isPlaying()).thenReturn(true);
        verify(mMockVibrator).vibrate(VIBRATION_EFFECT, RINGTONE_VIB_ATTRIBUTES);

        // Release
        ringtone.stop();
        verify(mockMediaPlayer).release();
        verify(mMockVibrator).cancel(VibrationAttributes.USAGE_RINGTONE);

        verifyZeroInteractions(mMockRemotePlayer);
        // Nothing after the initial hasVibrator - it uses audio-coupled.
        verifyNoMoreInteractions(mMockVibrator);
    }

    @Test
    public void testRingtone_nullMediaOnBuilderUsesFallback() throws Exception {
        AssetFileDescriptor testResourceFd =
                mContext.getResources().openRawResourceFd(R.raw.shortmp3);
        // Ensure it will flow as expected.
        assertThat(testResourceFd).isNotNull();
        assertThat(testResourceFd.getDeclaredLength()).isAtLeast(0);
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.raw.fallbackring, testResourceFd);

        MediaPlayer mockMediaPlayer = mMediaPlayerRule.expectLocalMediaPlayer();
        Ringtone ringtone = newBuilder(MEDIA_SOUND, RINGTONE_ATTRIBUTES)
                .setUri(null)
                .build();
        assertThat(ringtone).isNotNull();
        assertThat(ringtone.isUsingRemotePlayer()).isFalse();

        // Delegates straight to fallback in local player.
        // Prepare
        verifyLocalPlayerFallbackSetup(mockMediaPlayer, testResourceFd, RINGTONE_ATTRIBUTES);
        verify(mockMediaPlayer).setVolume(1.0f);
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        verifyLocalPlay(mockMediaPlayer);

        // Release
        ringtone.stop();
        verifyLocalStop(mockMediaPlayer);

        verifyNoMoreInteractions(mockMediaPlayer);
        verifyNoMoreInteractions(mMockRemotePlayer);
    }

    @Test
    public void testRingtone_nullMediaOnBuilderUsesFallbackViaRemote() throws Exception {
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.raw.fallbackring, null);
        Ringtone ringtone = newBuilder(MEDIA_SOUND, RINGTONE_ATTRIBUTES)
                .setUri(null)
                .setLooping(true) // distinct from haptic generator, to match plumbing
                .build();
        assertThat(ringtone).isNotNull();
        // Local player fallback fails as the resource isn't found (no media player creation is
        // attempted), and then goes on to create the remote player.
        assertThat(ringtone.isUsingRemotePlayer()).isTrue();

        ringtone.play();
        verify(mMockRemotePlayer).playRemoteRingtone(mIBinderCaptor.capture(), isNull(),
                eq(RINGTONE_ATTRIBUTES), eq(false),
                eq(MEDIA_SOUND), isNull(),
                eq(1.0f), eq(true), eq(false), isNull());
        ringtone.stop();
        verify(mMockRemotePlayer).stop(mIBinderCaptor.getValue());
        verifyNoMoreInteractions(mMockRemotePlayer);
    }

    @Test
    public void testRingtone_noMediaSetOnBuilderFallbackFailsAndNoRemote() throws Exception {
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.raw.fallbackring, null);
        Ringtone ringtone = newBuilder(MEDIA_SOUND, RINGTONE_ATTRIBUTES)
                .setUri(null)
                .setLocalOnly()
                .build();
        // Local player fallback fails as the resource isn't found (no media player creation is
        // attempted), and since there is no local player, the ringtone ends up having nothing to
        // do.
        assertThat(ringtone).isNull();
    }

    private Ringtone.Builder newBuilder(@Ringtone.RingtoneMedia int ringtoneMedia,
            AudioAttributes audioAttributes) {
        return new Ringtone.Builder(mContext, ringtoneMedia, audioAttributes)
                .setInjectables(mMediaPlayerRule.injectables);
    }

    private static AudioAttributes audioAttributes(int audioUsage) {
        return new AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    /** Makes the mock get some sort of file access problem. */
    private void setupFileNotFound(MediaPlayer mockMediaPlayer, Uri uri) throws Exception {
        doThrow(new FileNotFoundException("Fake file not found"))
                .when(mockMediaPlayer).setDataSource(any(Context.class), eq(uri));
    }

    private void verifyLocalPlayerSetup(MediaPlayer mockPlayer, Uri expectedUri,
            AudioAttributes expectedAudioAttributes) throws Exception {
        verify(mockPlayer).setDataSource(mContext, expectedUri);
        verify(mockPlayer).setAudioAttributes(expectedAudioAttributes);
        verify(mockPlayer).setPreferredDevice(null);
        verify(mockPlayer).prepare();
    }

    private void verifyLocalPlayerFallbackSetup(MediaPlayer mockPlayer, AssetFileDescriptor afd,
            AudioAttributes expectedAudioAttributes) throws Exception {
        // This is very specific but it's a simple way to test that the test resource matches.
        if (afd.getDeclaredLength() < 0) {
            verify(mockPlayer).setDataSource(afd.getFileDescriptor());
        } else {
            verify(mockPlayer).setDataSource(afd.getFileDescriptor(),
                    afd.getStartOffset(),
                    afd.getDeclaredLength());
        }
        verify(mockPlayer).setAudioAttributes(expectedAudioAttributes);
        verify(mockPlayer).setPreferredDevice(null);
        verify(mockPlayer).prepare();
    }

    private void verifyLocalPlay(MediaPlayer mockMediaPlayer) {
        verify(mockMediaPlayer).setOnCompletionListener(any());
        verify(mockMediaPlayer).start();
    }

    private void verifyLocalStop(MediaPlayer mockMediaPlayer) {
        verify(mockMediaPlayer).stop();
        verify(mockMediaPlayer).setOnCompletionListener(isNull());
        verify(mockMediaPlayer).reset();
        verify(mockMediaPlayer).release();
    }

    /**
     * This rule ensures that all expected media player creations from the factory do actually
     * occur. The reason for this level of control is that creating a media player is fairly
     * expensive and blocking, so we do want unit tests of this class to "declare" interactions
     * of all created media players.
     *
     * This needs to be a TestRule so that the teardown assertions can be skipped if the test has
     * failed (and media player assertions may just be a distracting side effect). Otherwise, the
     * teardown failures hide the real test ones.
     */
    public static class RingtoneInjectablesTrackingTestRule implements TestRule {
        public Ringtone.Injectables injectables = new TestInjectables();
        public boolean hapticGeneratorAvailable = true;

        // Queue of (local) media players, in order of expected creation. Enqueue using
        // expectNewMediaPlayer(), dequeued by the media player factory passed to Ringtone.
        // This queue is asserted to be empty at the end of the test.
        private Queue<MediaPlayer> mMockMediaPlayerQueue = new ArrayDeque<>();

        // Similar to media players, but for haptic generator, which also needs releasing.
        private Map<MediaPlayer, HapticGenerator> mMockHapticGeneratorMap = new ArrayMap<>();

        // Media players with haptic channels.
        private ArraySet<MediaPlayer> mHapticChannels = new ArraySet<>();

        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    base.evaluate();
                    // Only assert if the test didn't fail (base.evaluate() would throw).
                    assertWithMessage("Test setup an expectLocalMediaPlayer but it wasn't consumed")
                            .that(mMockMediaPlayerQueue).isEmpty();
                    // Only assert if the test didn't fail (base.evaluate() would throw).
                    assertWithMessage(
                            "Test setup an expectLocalHapticGenerator but it wasn't consumed")
                            .that(mMockHapticGeneratorMap).isEmpty();
                }
            };
        }

        private TestMediaPlayer expectLocalMediaPlayer() {
            TestMediaPlayer mockMediaPlayer = Mockito.mock(TestMediaPlayer.class);
            // Delegate to simulated methods. This means they can be verified but also reflect
            // realistic transitions from the TestMediaPlayer.
            doCallRealMethod().when(mockMediaPlayer).start();
            doCallRealMethod().when(mockMediaPlayer).stop();
            doCallRealMethod().when(mockMediaPlayer).setLooping(anyBoolean());
            when(mockMediaPlayer.isLooping()).thenCallRealMethod();
            when(mockMediaPlayer.isLooping()).thenCallRealMethod();
            mMockMediaPlayerQueue.add(mockMediaPlayer);
            return mockMediaPlayer;
        }

        private HapticGenerator expectHapticGenerator(MediaPlayer mockMediaPlayer) {
            HapticGenerator mockHapticGenerator = Mockito.mock(HapticGenerator.class);
            // A test should never want this.
            assertWithMessage("Can't expect a second haptic generator created "
                    + "for one media player")
                    .that(mMockHapticGeneratorMap.put(mockMediaPlayer, mockHapticGenerator))
                    .isNull();
            return mockHapticGenerator;
        }

        private void setHasHapticChannels(MediaPlayer mp, boolean hasHapticChannels) {
            if (hasHapticChannels) {
                mHapticChannels.add(mp);
            } else {
                mHapticChannels.remove(mp);
            }
        }

        private class TestInjectables extends Ringtone.Injectables {
            @Override
            public MediaPlayer newMediaPlayer() {
                assertWithMessage(
                        "Unexpected MediaPlayer creation. Bug or need expectNewMediaPlayer")
                        .that(mMockMediaPlayerQueue)
                        .isNotEmpty();
                return mMockMediaPlayerQueue.remove();
            }

            @Override
            public boolean isHapticGeneratorAvailable() {
                return hapticGeneratorAvailable;
            }

            @Override
            public HapticGenerator createHapticGenerator(MediaPlayer mediaPlayer) {
                HapticGenerator mockHapticGenerator = mMockHapticGeneratorMap.remove(mediaPlayer);
                assertWithMessage("Unexpected HapticGenerator creation. "
                        + "Bug or need expectHapticGenerator")
                        .that(mockHapticGenerator)
                        .isNotNull();
                return mockHapticGenerator;
            }

            @Override
            public boolean isHapticPlaybackSupported() {
                return true;
            }

            @Override
            public boolean hasHapticChannels(MediaPlayer mp) {
                return mHapticChannels.contains(mp);
            }
        }
    }

    /**
     * MediaPlayer relies on a native backend and so its necessary to intercept calls from
     * fake usage hitting them.
     *
     * Mocks don't work directly on native calls, but if they're overridden then it does work.
     * Some basic state faking is also done to make the mocks more realistic.
     */
    private static class TestMediaPlayer extends MediaPlayer {
        private boolean mIsPlaying = false;
        private boolean mIsLooping = false;

        @Override
        public void start() {
            mIsPlaying = true;
        }

        @Override
        public void stop() {
            mIsPlaying = false;
        }

        @Override
        public void setLooping(boolean value) {
            mIsLooping = value;
        }

        @Override
        public boolean isLooping() {
            return mIsLooping;
        }

        @Override
        public boolean isPlaying() {
            return mIsPlaying;
        }

        void simulatePlayingFinished() {
            if (!mIsPlaying) {
                throw new IllegalStateException(
                        "Attempted to pretend playing finished when not playing");
            }
            mIsPlaying = false;
        }
    }
}
