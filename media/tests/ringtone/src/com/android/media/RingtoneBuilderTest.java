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

package com.android.media;

import static android.media.Ringtone.MEDIA_SOUND;
import static android.media.Ringtone.MEDIA_SOUND_AND_VIBRATION;
import static android.media.Ringtone.MEDIA_VIBRATION;

import static com.android.media.testing.MediaPlayerTestHelper.verifyPlayerFallbackSetup;
import static com.android.media.testing.MediaPlayerTestHelper.verifyPlayerSetup;
import static com.android.media.testing.MediaPlayerTestHelper.verifyPlayerStarted;
import static com.android.media.testing.MediaPlayerTestHelper.verifyPlayerStopped;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.framework.base.media.ringtone.tests.R;
import com.android.media.testing.RingtoneInjectablesTrackingTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileNotFoundException;

/**
 * Test behavior of {@link Ringtone} when it's created via {@link Ringtone.Builder}.
 */
@RunWith(AndroidJUnit4.class)
public class RingtoneBuilderTest {

    private static final Uri SOUND_URI = Uri.parse("content://fake-sound-uri");

    private static final AudioAttributes RINGTONE_ATTRIBUTES =
            audioAttributes(AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
    private static final AudioAttributes RINGTONE_ATTRIBUTES_WITH_HC =
            new AudioAttributes.Builder(RINGTONE_ATTRIBUTES).setHapticChannelsMuted(false).build();
    private static final VibrationAttributes RINGTONE_VIB_ATTRIBUTES =
            new VibrationAttributes.Builder(RINGTONE_ATTRIBUTES).build();

    private static final VibrationEffect VIBRATION_EFFECT =
            VibrationEffect.createWaveform(new long[] { 0, 100, 50, 100}, -1);

    @Rule public final RingtoneInjectablesTrackingTestRule
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
        verifyPlayerSetup(mContext, mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES);
        verify(mockMediaPlayer).setVolume(1.0f);
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        verifyPlayerStarted(mockMediaPlayer);

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
        verifyPlayerStopped(mockMediaPlayer);

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
        verifyPlayerSetup(mContext, mockMediaPlayer, SOUND_URI, audioAttributes);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        verifyPlayerStarted(mockMediaPlayer);

        // Release
        ringtone.stop();
        verifyPlayerStopped(mockMediaPlayer);

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
        verifyPlayerSetup(mContext, mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
        verify(mockMediaPlayer).setVolume(1.0f);
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();

        verifyPlayerStarted(mockMediaPlayer);
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
        verifyPlayerStopped(mockMediaPlayer);
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
        verifyPlayerSetup(mContext, mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
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
        verifyPlayerSetup(mContext, mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
        verify(mockMediaPlayer).setVolume(0.0f);  // Vibration-only: sound muted.
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        // Vibrator.vibrate isn't called because the vibration comes from the sound.
        verifyPlayerStarted(mockMediaPlayer);

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
        verifyPlayerStopped(mockMediaPlayer);

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
        verifyPlayerSetup(mContext, mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        when(mockMediaPlayer.isPlaying()).thenReturn(true);
        verifyPlayerStarted(mockMediaPlayer);

        // Release
        ringtone.stop();
        verifyPlayerStopped(mockMediaPlayer);

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
        verifyPlayerSetup(mContext, mockMediaPlayer, SOUND_URI, RINGTONE_ATTRIBUTES_WITH_HC);
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
                mContext.getResources().openRawResourceFd(R.raw.test_sound_file);
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
        verifyPlayerFallbackSetup(mockMediaPlayer, testResourceFd, RINGTONE_ATTRIBUTES);
        verify(mockMediaPlayer).setVolume(1.0f);
        verify(mockMediaPlayer).setLooping(false);
        verify(mockMediaPlayer).prepare();

        // Play
        ringtone.play();
        verifyPlayerStarted(mockMediaPlayer);

        // Release
        ringtone.stop();
        verifyPlayerStopped(mockMediaPlayer);

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

    private Ringtone.Builder newBuilder(@Ringtone.RingtoneMedia int ringtoneMedia,
            AudioAttributes audioAttributes) {
        return new Ringtone.Builder(mContext, ringtoneMedia, audioAttributes)
                .setInjectables(mMediaPlayerRule.getRingtoneTestInjectables());
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
}
