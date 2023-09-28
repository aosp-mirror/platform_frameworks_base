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

package com.android.server.audio;

import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.CONTENT_TYPE_SPEECH;
import static android.media.AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO;
import static android.media.AudioPlaybackConfiguration.PLAYER_TYPE_JAM_AUDIOTRACK;
import static android.media.AudioPlaybackConfiguration.PLAYER_TYPE_UNKNOWN;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.PlayerBase;
import android.os.Parcel;

import androidx.test.core.app.ApplicationProvider;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;

@RunWith(JUnit4.class)
public final class FadeOutManagerTest {
    private static final int TEST_UID_SYSTEM = 1000;
    private static final int TEST_UID_USER = 10100;
    private static final int TEST_SESSION_ID = 10;
    private static final int TEST_PIID_1 = 101;
    private static final int TEST_PIID_2 = 102;
    private static final int TEST_PIID_3 = 103;
    private static final int TEST_PID = 10101;

    private static final AudioAttributes TEST_MEDIA_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final AudioAttributes TEST_GAME_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_GAME).build();
    private static final AudioAttributes TEST_SPEECH_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setContentType(CONTENT_TYPE_SPEECH).build();
    private FadeOutManager mFadeOutManager;
    private Context mContext;

    @Mock
    PlayerBase.PlayerIdCard mMockPlayerIdCard;
    @Mock
    AudioPlaybackConfiguration mMockPlaybackConfiguration;

    @Rule
    public final Expect expect = Expect.create();

    @Before
    public void setUp() {
        mFadeOutManager = new FadeOutManager();
        mContext = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void testCanCauseFadeOut_forFaders_returnsTrue() {
        FocusRequester winner = createFocusRequester(TEST_MEDIA_AUDIO_ATTRIBUTE, "winning-client",
                "unit-test", TEST_UID_USER,
                AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS);
        FocusRequester loser = createFocusRequester(TEST_SPEECH_AUDIO_ATTRIBUTE, "losing-client",
                "unit-test", TEST_UID_USER, AudioManager.AUDIOFOCUS_FLAG_TEST);

        expect.withMessage("Can cause fade out").that(mFadeOutManager.canCauseFadeOut(
                winner, loser)).isTrue();
    }

    @Test
    public void testCanCauseFadeOut_forUnfaderSpeechAttribute_returnsFalse() {
        FocusRequester winner = createFocusRequester(TEST_SPEECH_AUDIO_ATTRIBUTE, "winning-client",
                "unit-test", TEST_UID_USER, AudioManager.AUDIOFOCUS_FLAG_TEST);
        FocusRequester loser = createFocusRequester(TEST_SPEECH_AUDIO_ATTRIBUTE, "losing-client",
                "unit-test", TEST_UID_USER, AudioManager.AUDIOFOCUS_FLAG_TEST);

        expect.withMessage("Can cause fade out for speech attribute")
                .that(mFadeOutManager.canCauseFadeOut(winner, loser)).isFalse();
    }

    @Test
    public void testCanCauseFadeOut_forUnfaderFlag_returnsFalse() {
        FocusRequester winner = createFocusRequester(TEST_SPEECH_AUDIO_ATTRIBUTE, "winning-client",
                "unit-test", TEST_UID_USER, AudioManager.AUDIOFOCUS_FLAG_TEST);
        FocusRequester loser = createFocusRequester(TEST_MEDIA_AUDIO_ATTRIBUTE, "losing-client",
                "unit-test", TEST_UID_USER,
                AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS);

        expect.withMessage("Can cause fade out for flag pause on duckable loss")
                .that(mFadeOutManager.canCauseFadeOut(winner, loser)).isFalse();
    }

    @Test
    public void testCanBeFadedOut_forFadableConfig_returnsTrue() {
        AudioPlaybackConfiguration apcMedia = createAudioPlaybackConfiguration(
                TEST_MEDIA_AUDIO_ATTRIBUTE, TEST_UID_USER, TEST_PIID_1, TEST_PID,
                PLAYER_TYPE_JAM_AUDIOTRACK);
        AudioPlaybackConfiguration apcGame = createAudioPlaybackConfiguration(
                TEST_GAME_AUDIO_ATTRIBUTE, TEST_UID_USER, TEST_PIID_2, TEST_PID,
                PLAYER_TYPE_UNKNOWN);

        expect.withMessage("Fadability for media audio attribute")
                .that(mFadeOutManager.canBeFadedOut(apcMedia)).isTrue();
        expect.withMessage("Fadability for game audio attribute")
                .that(mFadeOutManager.canBeFadedOut(apcGame)).isTrue();
    }

    @Test
    public void testCanBeFadedOut_forUnFadableConfig_returnsFalse() {
        AudioPlaybackConfiguration apcSpeech = createAudioPlaybackConfiguration(
                TEST_SPEECH_AUDIO_ATTRIBUTE, TEST_UID_USER, TEST_PIID_1, TEST_PID,
                PLAYER_TYPE_JAM_AUDIOTRACK);
        AudioPlaybackConfiguration apcAAudio = createAudioPlaybackConfiguration(
                TEST_GAME_AUDIO_ATTRIBUTE, TEST_UID_USER, TEST_PIID_2, TEST_PID,
                PLAYER_TYPE_AAUDIO);

        expect.withMessage("Fadability for speech audio attribute")
                .that(mFadeOutManager.canBeFadedOut(apcSpeech)).isFalse();
        expect.withMessage("Fadability for AAudio player type")
                .that(mFadeOutManager.canBeFadedOut(apcAAudio)).isFalse();
    }

    @Test
    public void testFadeOutUid_getsFadedOut_isFadedReturnsTrue() {
        final ArrayList<AudioPlaybackConfiguration> apcsToFadeOut =
                new ArrayList<AudioPlaybackConfiguration>();
        apcsToFadeOut.add(createAudioPlaybackConfiguration(
                TEST_MEDIA_AUDIO_ATTRIBUTE, TEST_UID_USER, TEST_PIID_1, TEST_PID,
                PLAYER_TYPE_JAM_AUDIOTRACK));

        mFadeOutManager.fadeOutUid(TEST_UID_USER, apcsToFadeOut);

        expect.withMessage("Fade out state for uid")
                .that(mFadeOutManager.isUidFadedOut(TEST_UID_USER)).isTrue();
    }

    @Test
    public void testUnfadeOutUid_getsUnfaded_isFadedReturnsFalse() {
        final ArrayList<AudioPlaybackConfiguration> apcsToUnfade =
                new ArrayList<AudioPlaybackConfiguration>();
        final ArrayList<AudioPlaybackConfiguration> apcsToKeepfaded =
                new ArrayList<AudioPlaybackConfiguration>();
        AudioPlaybackConfiguration apcMediaPiid1 = createAudioPlaybackConfiguration(
                TEST_MEDIA_AUDIO_ATTRIBUTE, TEST_UID_USER, TEST_PIID_1, TEST_PID,
                PLAYER_TYPE_JAM_AUDIOTRACK);
        AudioPlaybackConfiguration apcGamePiid2 = createAudioPlaybackConfiguration(
                TEST_GAME_AUDIO_ATTRIBUTE, TEST_UID_USER, TEST_PIID_2, TEST_PID,
                PLAYER_TYPE_JAM_AUDIOTRACK);
        AudioPlaybackConfiguration apcMediaPiid3 = createAudioPlaybackConfiguration(
                TEST_MEDIA_AUDIO_ATTRIBUTE, TEST_UID_SYSTEM, TEST_PIID_3, TEST_PID,
                PLAYER_TYPE_JAM_AUDIOTRACK);
        HashMap<Integer, AudioPlaybackConfiguration> mapPiidToApcs = new HashMap<>();
        apcsToUnfade.add(apcMediaPiid1);
        apcsToUnfade.add(apcGamePiid2);
        apcsToKeepfaded.add(apcMediaPiid3);
        mFadeOutManager.fadeOutUid(TEST_UID_USER, apcsToUnfade);
        mFadeOutManager.fadeOutUid(TEST_UID_SYSTEM, apcsToKeepfaded);
        mapPiidToApcs.put(TEST_PIID_1, apcMediaPiid1);
        mapPiidToApcs.put(TEST_PIID_2, apcGamePiid2);

        mFadeOutManager.unfadeOutUid(TEST_UID_USER, mapPiidToApcs);

        expect.withMessage("Fade out state after unfading for uid")
                .that(mFadeOutManager.isUidFadedOut(TEST_UID_USER)).isFalse();
        expect.withMessage("Fade out state for uid")
                .that(mFadeOutManager.isUidFadedOut(TEST_UID_SYSTEM)).isTrue();

    }

    private FocusRequester createFocusRequester(AudioAttributes aa, String clientId,
            String packageName, int uid, int flags) {
        MediaFocusControl mfc = new MediaFocusControl(mContext, null);
        return new FocusRequester(aa, AudioManager.AUDIOFOCUS_GAIN, flags, null, null, clientId,
                null, packageName, uid, mfc, 1);
    }

    private PlayerBase.PlayerIdCard createPlayerIdCard(AudioAttributes aa, int playerType) {

        Parcel p = Parcel.obtain();
        p.writeInt(playerType);
        aa.writeToParcel(p, 0);
        p.writeStrongInterface(null);
        p.writeInt(TEST_SESSION_ID);
        p.setDataPosition(0);
        return PlayerBase.PlayerIdCard.CREATOR.createFromParcel(p);
    }

    private AudioPlaybackConfiguration createAudioPlaybackConfiguration(AudioAttributes aa, int uid,
            int piid, int pid, int playerType) {
        return new AudioPlaybackConfiguration(createPlayerIdCard(aa, playerType), piid, uid, pid);
    }
}
