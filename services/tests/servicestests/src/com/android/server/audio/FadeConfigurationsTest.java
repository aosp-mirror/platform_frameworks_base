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

import static android.media.AudioAttributes.CONTENT_TYPE_SPEECH;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO;
import static android.media.AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL;
import static android.media.AudioPlaybackConfiguration.PLAYER_TYPE_JAM_AUDIOTRACK;
import static android.media.AudioPlaybackConfiguration.PLAYER_TYPE_UNKNOWN;
import static android.media.audiopolicy.Flags.FLAG_ENABLE_FADE_MANAGER_CONFIGURATION;

import android.media.AudioAttributes;
import android.media.FadeManagerConfiguration;
import android.media.VolumeShaper;
import android.platform.test.flag.junit.SetFlagsRule;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public final class FadeConfigurationsTest {
    private FadeConfigurations mFadeConfigurations;
    private static final long DEFAULT_FADE_OUT_DURATION_MS = 2_000;
    private static final long DEFAULT_FADE_IN_DURATION_MS = 1_000;
    private static final long DEFAULT_DELAY_FADE_IN_OFFENDERS_MS = 2000;
    private static final long DURATION_FOR_UNFADEABLE_MS = 0;
    private static final int TEST_UID_SYSTEM = 1000;
    private static final int TEST_UID_USER = 10100;
    private static final List<Integer> DEFAULT_UNFADEABLE_PLAYER_TYPES = List.of(
            PLAYER_TYPE_AAUDIO,
            PLAYER_TYPE_JAM_SOUNDPOOL
    );
    private static final List<Integer> DEFAULT_UNFADEABLE_CONTENT_TYPES = List.of(
            CONTENT_TYPE_SPEECH
    );
    private static final List<Integer> DEFAULT_FADEABLE_USAGES = List.of(
            USAGE_GAME,
            USAGE_MEDIA
    );
    private static final VolumeShaper.Configuration DEFAULT_FADEOUT_VSHAPE =
            new VolumeShaper.Configuration.Builder()
                    .setId(PlaybackActivityMonitor.VOLUME_SHAPER_SYSTEM_FADEOUT_ID)
                    .setCurve(/* times= */ new float[]{0.f, 0.25f, 1.0f},
                            /* volumes= */ new float[]{1.f, 0.65f, 0.0f})
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(DEFAULT_FADE_OUT_DURATION_MS)
                    .build();
    private static final VolumeShaper.Configuration DEFAULT_FADEIN_VSHAPE =
            new VolumeShaper.Configuration.Builder()
                    .setId(PlaybackActivityMonitor.VOLUME_SHAPER_SYSTEM_FADEOUT_ID)
                    .setCurve(/* times= */ new float[]{0.f, 0.50f, 1.0f},
                            /* volumes= */ new float[]{0.f, 0.30f, 1.0f})
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(DEFAULT_FADE_IN_DURATION_MS)
                    .build();

    private static final AudioAttributes TEST_MEDIA_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final AudioAttributes TEST_GAME_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_GAME).build();
    private static final AudioAttributes TEST_ASSISTANT_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_ASSISTANT).build();
    private static final AudioAttributes TEST_EMERGENCY_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setSystemUsage(USAGE_EMERGENCY).build();

    private static final AudioAttributes TEST_SPEECH_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setContentType(CONTENT_TYPE_SPEECH).build();

    @Rule
    public final Expect expect = Expect.create();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        mFadeConfigurations = new FadeConfigurations();
    }

    @Test
    public void testGetFadeableUsages_forDefaultConstr_equalsDefaultFadeableUsages() {
        expect.withMessage("Fadeable usages for default constructor")
                .that(mFadeConfigurations.getFadeableUsages()).isEqualTo(DEFAULT_FADEABLE_USAGES);
    }

    @Test
    public void testGetUnfadeableContentTypes_forDefaultConstr_equalsDefaultContentTypes() {
        expect.withMessage("Unfadeable content types for default constructor")
                .that(mFadeConfigurations.getUnfadeableContentTypes())
                .isEqualTo(DEFAULT_UNFADEABLE_CONTENT_TYPES);
    }

    @Test
    public void testGetUnfadeablePlayerTypes_forDefaultConstr_equalsDefaultPlayerTypes() {
        expect.withMessage("Unfadeable player types for default constructor")
                .that(mFadeConfigurations.getUnfadeablePlayerTypes())
                .isEqualTo(DEFAULT_UNFADEABLE_PLAYER_TYPES);
    }

    @Test
    public void testGetFadeOutVolumeShaperConfig_forDefaultConstr_equalsDefaultVolShaperConfig() {
        expect.withMessage("Fadeout VolumeShaper config for default constructor")
                .that(mFadeConfigurations.getFadeOutVolumeShaperConfig(TEST_MEDIA_AUDIO_ATTRIBUTE))
                .isEqualTo(DEFAULT_FADEOUT_VSHAPE);
    }

    @Test
    public void testGetFadeOutDuration_forFadeableAttrribute_equalsDefaultDuration() {
        expect.withMessage("Fade out duration for media attribute with default constructor")
                .that(mFadeConfigurations.getFadeOutDuration(TEST_MEDIA_AUDIO_ATTRIBUTE))
                .isEqualTo(DEFAULT_FADE_OUT_DURATION_MS);
        expect.withMessage("Fade out duration for media attribute with default constructor")
                .that(mFadeConfigurations.getFadeOutDuration(TEST_GAME_AUDIO_ATTRIBUTE))
                .isEqualTo(DEFAULT_FADE_OUT_DURATION_MS);
    }

    @Test
    public void testGetFadeOutDuration_forUnFadeableAttrribute_equalsZeroDuration() {
        expect.withMessage("Fade out duration for assistant attribute with default constructor")
                .that(mFadeConfigurations.getFadeOutDuration(TEST_ASSISTANT_AUDIO_ATTRIBUTE))
                .isEqualTo(DURATION_FOR_UNFADEABLE_MS);
        expect.withMessage("Fade out duration for speech attribute with default constructor")
                .that(mFadeConfigurations.getFadeOutDuration(TEST_SPEECH_AUDIO_ATTRIBUTE))
                .isEqualTo(DURATION_FOR_UNFADEABLE_MS);
    }

    @Test
    public void testGetDelayFadeInOffenders_equalsDefaultDelay() {
        expect.withMessage("Fade out duration for media attribute with default constructor")
                .that(mFadeConfigurations.getDelayFadeInOffenders(TEST_MEDIA_AUDIO_ATTRIBUTE))
                .isEqualTo(DEFAULT_DELAY_FADE_IN_OFFENDERS_MS);
    }

    @Test
    public void testIsFadeable_forDefaultConstr_forFadableAttributes_returnsTrue() {
        expect.withMessage("Is fadable for media audio attribute returns")
                .that(mFadeConfigurations.isFadeable(TEST_MEDIA_AUDIO_ATTRIBUTE, TEST_UID_SYSTEM,
                        PLAYER_TYPE_JAM_AUDIOTRACK)).isTrue();
        expect.withMessage("Is fadable for game audio attribute returns")
                .that(mFadeConfigurations.isFadeable(TEST_GAME_AUDIO_ATTRIBUTE, TEST_UID_USER,
                        PLAYER_TYPE_UNKNOWN)).isTrue();
    }

    @Test
    public void testIsFadeable_forDefaultConstr_forUnfadableAttributes_returnsFalse() {
        expect.withMessage("Is fadable for speech audio attribute returns")
                .that(mFadeConfigurations.isFadeable(TEST_SPEECH_AUDIO_ATTRIBUTE, TEST_UID_SYSTEM,
                        PLAYER_TYPE_JAM_AUDIOTRACK)).isFalse();
        expect.withMessage("Is fadable for AAudio player type returns")
                .that(mFadeConfigurations.isFadeable(TEST_GAME_AUDIO_ATTRIBUTE, TEST_UID_USER,
                        PLAYER_TYPE_AAUDIO)).isFalse();
    }

    @Test
    public void testGetFadeableUsages_withFadeManagerConfigurations_equals() {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        List<Integer> usageList = List.of(AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_EMERGENCY);
        FadeManagerConfiguration fmc = createFadeMgrConfig(/* fadeableUsages= */ usageList,
                /* unfadeableContentTypes= */ null, /* unfadeableUids= */ null,
                /* unfadeableAudioAttrs= */ null);
        FadeConfigurations fadeConfigs = new FadeConfigurations();

        fadeConfigs.setFadeManagerConfiguration(fmc);

        expect.withMessage("Fadeable usages with fade manager configuration")
                .that(fadeConfigs.getFadeableUsages()).isEqualTo(fmc.getFadeableUsages());
    }

    @Test
    public void testGetUnfadeableContentTypes_withFadeManagerConfigurations_equals() {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        List<Integer> contentTypesList = List.of(AudioAttributes.CONTENT_TYPE_MUSIC,
                AudioAttributes.CONTENT_TYPE_MOVIE);
        FadeManagerConfiguration fmc = createFadeMgrConfig(/* fadeableUsages= */ null,
                /* unfadeableContentTypes= */ contentTypesList, /* unfadeableUids= */ null,
                /* unfadeableAudioAttrs= */ null);
        FadeConfigurations fadeConfigs = new FadeConfigurations();

        fadeConfigs.setFadeManagerConfiguration(fmc);

        expect.withMessage("Unfadeable content types with fade manager configuration")
                .that(fadeConfigs.getUnfadeableContentTypes())
                .isEqualTo(fmc.getUnfadeableContentTypes());
    }

    @Test
    public void testGetUnfadeableAudioAttributes_withFadeManagerConfigurations_equals() {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        List<AudioAttributes> attrsList = List.of(TEST_ASSISTANT_AUDIO_ATTRIBUTE,
                TEST_EMERGENCY_AUDIO_ATTRIBUTE);
        FadeManagerConfiguration fmc = createFadeMgrConfig(/* fadeableUsages= */ null,
                /* unfadeableContentTypes= */ null, /* unfadeableUids= */ null,
                /* unfadeableAudioAttrs= */ attrsList);
        FadeConfigurations fadeConfigs = new FadeConfigurations();

        fadeConfigs.setFadeManagerConfiguration(fmc);

        expect.withMessage("Unfadeable audio attributes with fade manager configuration")
                .that(fadeConfigs.getUnfadeableAudioAttributes())
                .isEqualTo(fmc.getUnfadeableAudioAttributes());
    }

    @Test
    public void testGetUnfadeableUids_withFadeManagerConfigurations_equals() {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        List<Integer> uidsList = List.of(TEST_UID_SYSTEM, TEST_UID_USER);
        FadeManagerConfiguration fmc = createFadeMgrConfig(/* fadeableUsages= */ null,
                /* unfadeableContentTypes= */ null, /* unfadeableUids= */ uidsList,
                /* unfadeableAudioAttrs= */ null);
        FadeConfigurations fadeConfigs = new FadeConfigurations();

        fadeConfigs.setFadeManagerConfiguration(fmc);

        expect.withMessage("Unfadeable uids with fade manager configuration")
                .that(fadeConfigs.getUnfadeableUids()).isEqualTo(fmc.getUnfadeableUids());
    }

    private static FadeManagerConfiguration createFadeMgrConfig(List<Integer> fadeableUsages,
            List<Integer> unfadeableContentTypes, List<Integer> unfadeableUids,
            List<AudioAttributes> unfadeableAudioAttrs) {
        FadeManagerConfiguration.Builder builder = new FadeManagerConfiguration.Builder();
        if (fadeableUsages != null) {
            builder.setFadeableUsages(fadeableUsages);
        }
        if (unfadeableContentTypes != null) {
            builder.setUnfadeableContentTypes(unfadeableContentTypes);
        }
        if (unfadeableUids != null) {
            builder.setUnfadeableUids(unfadeableUids);
        }
        if (unfadeableAudioAttrs != null) {
            builder.setUnfadeableAudioAttributes(unfadeableAudioAttrs);
        }
        if (fadeableUsages != null) {
            for (int index = 0; index < fadeableUsages.size(); index++) {
                builder.setFadeOutVolumeShaperConfigForAudioAttributes(
                        createGenericAudioAttributesForUsage(fadeableUsages.get(index)),
                        DEFAULT_FADEOUT_VSHAPE);
            }
        }
        if (fadeableUsages != null) {
            for (int index = 0; index < fadeableUsages.size(); index++) {
                builder.setFadeInVolumeShaperConfigForAudioAttributes(
                        createGenericAudioAttributesForUsage(fadeableUsages.get(index)),
                        DEFAULT_FADEIN_VSHAPE);
            }
        }

        return builder.build();
    }

    private static AudioAttributes createGenericAudioAttributesForUsage(int usage) {
        if (AudioAttributes.isSystemUsage(usage)) {
            return new AudioAttributes.Builder().setSystemUsage(usage).build();
        }
        return new AudioAttributes.Builder().setUsage(usage).build();
    }
}
