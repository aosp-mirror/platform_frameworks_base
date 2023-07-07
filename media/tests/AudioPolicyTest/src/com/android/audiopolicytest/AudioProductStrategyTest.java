/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.audiopolicytest;

import static com.android.audiopolicytest.AudioVolumeTestUtil.INVALID_ATTRIBUTES;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.testing.EqualsTester;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class AudioProductStrategyTest {
    private static final String TAG = "AudioProductStrategyTest";

    @Rule
    public final AudioVolumesTestRule rule = new AudioVolumesTestRule();

    //-----------------------------------------------------------------
    // Test getAudioProductStrategies and validate strategies
    //-----------------------------------------------------------------
    @Test
    public void testGetProductStrategies() {
        List<AudioProductStrategy> audioProductStrategies =
                AudioProductStrategy.getAudioProductStrategies();

        assertNotNull(audioProductStrategies);
        assertTrue(audioProductStrategies.size() > 0);

        for (final AudioProductStrategy aps : audioProductStrategies) {
            assertTrue(aps.getId() >= 0);

            AudioAttributes aa = aps.getAudioAttributes();
            assertNotNull(aa);

            // Ensure API consistency
            assertTrue(aps.supportsAudioAttributes(aa));

            int streamType = aps.getLegacyStreamTypeForAudioAttributes(aa);
            if (streamType == AudioSystem.STREAM_DEFAULT) {
                // bailing out test for volume group APIs consistency
                continue;
            }
            final int volumeGroupFromStream = aps.getVolumeGroupIdForLegacyStreamType(streamType);
            final int volumeGroupFromAttributes = aps.getVolumeGroupIdForAudioAttributes(aa);
            assertNotEquals(volumeGroupFromStream, AudioVolumeGroup.DEFAULT_VOLUME_GROUP);
            assertEquals(volumeGroupFromStream, volumeGroupFromAttributes);
        }
    }

    //-----------------------------------------------------------------
    // Test stream to/from attributes conversion
    //-----------------------------------------------------------------
    @Test
    public void testAudioAttributesFromStreamTypes() throws Exception {
        List<AudioProductStrategy> audioProductStrategies =
                AudioProductStrategy.getAudioProductStrategies();

        assertNotNull(audioProductStrategies);
        assertTrue(audioProductStrategies.size() > 0);

        for (final int streamType : AudioManager.getPublicStreamTypes()) {
            AudioAttributes aaFromStreamType =
                    AudioProductStrategy.getAudioAttributesForStrategyWithLegacyStreamType(
                            streamType);

            // No strategy found for this stream type or no attributes defined for the strategy
            // hosting this stream type; Bailing out the test, just ensure that any request
            // for reciproque API with the unknown attributes would return default stream
            // for volume control, aka STREAM_MUSIC.
            if (aaFromStreamType.equals(INVALID_ATTRIBUTES)) {
                assertEquals(AudioSystem.STREAM_MUSIC,
                        AudioProductStrategy.getLegacyStreamTypeForStrategyWithAudioAttributes(
                            aaFromStreamType));
            } else {
                // Attributes are valid, i.e. a strategy was found supporting this stream type
                // with valid attributes. Ensure reciproque works fine
                int streamTypeFromAttributes =
                        AudioProductStrategy.getLegacyStreamTypeForStrategyWithAudioAttributes(
                                aaFromStreamType);
                assertEquals("stream " + AudioSystem.streamToString(streamType) + "("
                        + streamType + ") expected to match attributes "
                        + aaFromStreamType.toString() + " got instead stream "
                        + AudioSystem.streamToString(streamTypeFromAttributes) + "("
                        + streamTypeFromAttributes + ") expected to match attributes ",
                        streamType, streamTypeFromAttributes);
            }

            // Now identify the strategy supporting this stream type, ensure uniqueness
            boolean strategyFound = false;
            for (final AudioProductStrategy aps : audioProductStrategies) {
                AudioAttributes aaFromAps =
                        aps.getAudioAttributesForLegacyStreamType(streamType);

                if (aaFromAps == null) {
                    // not this one...
                    continue;
                }
                // Got it!
                assertFalse("Unique ProductStrategy shall match for a given stream type",
                        strategyFound);
                strategyFound = true;

                // Ensure getters aligned
                assertEquals(aaFromStreamType, aaFromAps);
                assertTrue(aps.supportsAudioAttributes(aaFromStreamType));

                // Ensure reciproque works fine
                assertEquals(streamType,
                        aps.getLegacyStreamTypeForAudioAttributes(aaFromStreamType));

                // Ensure consistency of volume group getter API
                final int volumeGroupFromStream =
                        aps.getVolumeGroupIdForLegacyStreamType(streamType);
                final int volumeGroupFromAttributes =
                        aps.getVolumeGroupIdForAudioAttributes(aaFromStreamType);
                assertNotEquals(volumeGroupFromStream, AudioVolumeGroup.DEFAULT_VOLUME_GROUP);
                assertEquals(volumeGroupFromStream, volumeGroupFromAttributes);
            }
            if (!strategyFound) {
                // No strategy found, ensure volume control is MUSIC
                assertEquals(AudioSystem.STREAM_MUSIC,
                        AudioProductStrategy.getLegacyStreamTypeForStrategyWithAudioAttributes(
                            aaFromStreamType));
            }
        }
    }

    @Test
    public void testAudioAttributesToStreamTypes() {
        List<AudioProductStrategy> audioProductStrategies =
                AudioProductStrategy.getAudioProductStrategies();

        assertNotNull(audioProductStrategies);
        assertTrue(audioProductStrategies.size() > 0);

        for (int usage : AudioAttributes.SDK_USAGES) {
            AudioAttributes aaForUsage = new AudioAttributes.Builder().setUsage(usage).build();

            int streamTypeFromUsage =
                    AudioProductStrategy.getLegacyStreamTypeForStrategyWithAudioAttributes(
                            aaForUsage);

            // Cannot be undefined, always shall fall back on a valid stream type
            // to be able to control the volume
            assertNotEquals(streamTypeFromUsage, AudioSystem.STREAM_DEFAULT);

            Log.w(TAG, "GUSTAVE aaForUsage=" + aaForUsage.toString());

            // Now identify the strategy hosting these Audio Attributes and ensure informations
            // matches.
            // Now identify the strategy supporting this stream type, ensure uniqueness
            boolean strategyFound = false;
            for (final AudioProductStrategy aps : audioProductStrategies) {
                if (!aps.supportsAudioAttributes(aaForUsage)) {
                    // Not this one
                    continue;
                }
                // Got it!
                String msg = "Unique ProductStrategy shall match for a given audio attributes "
                        + aaForUsage.toString() + " already associated also matches with"
                        + aps.toString();
                assertFalse(msg, strategyFound);
                strategyFound = true;

                // It may not return the expected stream type if the strategy does not have
                // associated stream type.
                // Behavior of member function getLegacyStreamTypeForAudioAttributes is
                // different than getLegacyStreamTypeForStrategyWithAudioAttributes since it
                // does not fallback on MUSIC stream type for volume operation
                int streamTypeFromAps = aps.getLegacyStreamTypeForAudioAttributes(aaForUsage);
                if (streamTypeFromAps == AudioSystem.STREAM_DEFAULT) {
                    // No stream type assigned to this strategy
                    // Expect static API to return default stream type for volume (aka MUSIC)
                    assertEquals("Strategy (" + aps.toString() + ") has no associated stream "
                            + ", must fallback on MUSIC stream as default",
                            streamTypeFromUsage, AudioSystem.STREAM_MUSIC);
                } else {
                    assertEquals("Attributes " + aaForUsage.toString() + " associated to stream "
                            + AudioSystem.streamToString(streamTypeFromUsage)
                            + " are supported by strategy (" + aps.toString() + ") which reports "
                            + " these attributes are associated to stream "
                            + AudioSystem.streamToString(streamTypeFromAps),
                            streamTypeFromUsage, streamTypeFromAps);

                    // Ensure consistency of volume group getter API
                    int volumeGroupFromStream =
                            aps.getVolumeGroupIdForLegacyStreamType(streamTypeFromAps);
                    int volumeGroupFromAttributes =
                            aps.getVolumeGroupIdForAudioAttributes(aaForUsage);
                    assertNotEquals(
                            volumeGroupFromStream, AudioVolumeGroup.DEFAULT_VOLUME_GROUP);
                    assertEquals(volumeGroupFromStream, volumeGroupFromAttributes);
                }
            }
            if (!strategyFound) {
                // No strategy found for the given attributes, the expected stream must be MUSIC
                assertEquals(streamTypeFromUsage, AudioSystem.STREAM_MUSIC);
            }
        }
    }

    @Test
    public void testEquals() {
        final EqualsTester equalsTester = new EqualsTester();

        AudioProductStrategy.getAudioProductStrategies().forEach(
                strategy -> equalsTester.addEqualityGroup(strategy,
                        writeToAndFromParcel(strategy)));

        equalsTester.testEquals();
    }

    private static AudioProductStrategy writeToAndFromParcel(
            AudioProductStrategy audioProductStrategy) {
        Parcel parcel = Parcel.obtain();
        audioProductStrategy.writeToParcel(parcel, /*flags=*/0);
        parcel.setDataPosition(0);
        AudioProductStrategy unmarshalledAudioProductStrategy =
                AudioProductStrategy.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return unmarshalledAudioProductStrategy;
    }
}
