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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.util.Log;

import com.google.common.primitives.Ints;

import java.util.List;

public class AudioManagerTest extends AudioVolumesTestBase {
    private static final String TAG = "AudioManagerTest";

    //-----------------------------------------------------------------
    // Test getAudioProductStrategies and validate strategies
    //-----------------------------------------------------------------
    public void testGetAndValidateProductStrategies() throws Exception {
        List<AudioProductStrategy> audioProductStrategies =
                mAudioManager.getAudioProductStrategies();
        assertTrue(audioProductStrategies.size() > 0);

        List<AudioVolumeGroup> audioVolumeGroups = mAudioManager.getAudioVolumeGroups();
        assertTrue(audioVolumeGroups.size() > 0);

        // Validate Audio Product Strategies
        for (final AudioProductStrategy audioProductStrategy : audioProductStrategies) {
            AudioAttributes attributes = audioProductStrategy.getAudioAttributes();
            int strategyStreamType =
                    audioProductStrategy.getLegacyStreamTypeForAudioAttributes(attributes);

            assertTrue("Strategy shall support the attributes retrieved from its getter API",
                    audioProductStrategy.supportsAudioAttributes(attributes));

            int volumeGroupId =
                    audioProductStrategy.getVolumeGroupIdForAudioAttributes(attributes);

            // A strategy must be associated to a volume group
            assertNotEquals("strategy not assigned to any volume group",
                    volumeGroupId, AudioVolumeGroup.DEFAULT_VOLUME_GROUP);

            // Valid Group ?
            AudioVolumeGroup audioVolumeGroup = null;
            for (final AudioVolumeGroup avg : audioVolumeGroups) {
                if (avg.getId() == volumeGroupId) {
                    audioVolumeGroup = avg;
                    break;
                }
            }
            assertNotNull("Volume Group not found", audioVolumeGroup);

            // Cross check: the group shall have at least one aa / stream types following the
            // considered strategy
            boolean strategyAttributesSupported = false;
            for (final AudioAttributes aa : audioVolumeGroup.getAudioAttributes()) {
                if (audioProductStrategy.supportsAudioAttributes(aa)) {
                    strategyAttributesSupported = true;
                    break;
                }
            }
            assertTrue("Volume Group and Strategy mismatching", strategyAttributesSupported);

            // Some Product strategy may not have corresponding stream types as they intends
            // to address volume setting per attributes to avoid adding new stream type
            // and going on deprecating the stream type even for volume
            if (strategyStreamType != AudioSystem.STREAM_DEFAULT) {
                boolean strategStreamTypeSupported = false;
                for (final int vgStreamType : audioVolumeGroup.getLegacyStreamTypes()) {
                    if (vgStreamType == strategyStreamType) {
                        strategStreamTypeSupported = true;
                        break;
                    }
                }
                assertTrue("Volume Group and Strategy mismatching", strategStreamTypeSupported);
            }
        }
    }

    //-----------------------------------------------------------------
    // Test getAudioVolumeGroups and validate volume groups
    //-----------------------------------------------------------------

    public void testGetAndValidateVolumeGroups() throws Exception {
        List<AudioVolumeGroup> audioVolumeGroups = mAudioManager.getAudioVolumeGroups();
        assertTrue(audioVolumeGroups.size() > 0);

        List<AudioProductStrategy> audioProductStrategies =
                mAudioManager.getAudioProductStrategies();
        assertTrue(audioProductStrategies.size() > 0);

        // Validate Audio Volume Groups, check all
        for (final AudioVolumeGroup audioVolumeGroup : audioVolumeGroups) {
            List<AudioAttributes> avgAttributes = audioVolumeGroup.getAudioAttributes();
            int[] avgStreamTypes = audioVolumeGroup.getLegacyStreamTypes();

            // for each volume group attributes, find the matching product strategy and ensure
            // it is linked the considered volume group
            for (final AudioAttributes aa : avgAttributes) {
                if (aa.equals(sDefaultAttributes)) {
                    // Some volume groups may not have valid attributes, used for internal
                    // volume management like patch/rerouting
                    // so bailing out strategy retrieval from attributes
                    continue;
                }
                boolean isVolumeGroupAssociatedToStrategy = false;
                for (final AudioProductStrategy strategy : audioProductStrategies) {
                    int groupId = strategy.getVolumeGroupIdForAudioAttributes(aa);
                    if (groupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {

                        assertEquals("Volume Group ID (" + audioVolumeGroup.toString()
                                + "), and Volume group ID associated to Strategy ("
                                + strategy.toString() + ") both supporting attributes "
                                + aa.toString() + " are mismatching",
                                audioVolumeGroup.getId(), groupId);
                        isVolumeGroupAssociatedToStrategy = true;
                        break;
                    }
                }
                assertTrue("Volume Group (" + audioVolumeGroup.toString()
                        + ") has no associated strategy for attributes " + aa.toString(),
                        isVolumeGroupAssociatedToStrategy);
            }

            // for each volume group stream type, find the matching product strategy and ensure
            // it is linked the considered volume group
            for (final int avgStreamType : avgStreamTypes) {
                if (avgStreamType == AudioSystem.STREAM_DEFAULT) {
                    // Some Volume Groups may not have corresponding stream types as they
                    // intends to address volume setting per attributes to avoid adding new
                    //  stream type and going on deprecating the stream type even for volume
                    // so bailing out strategy retrieval from stream type
                    continue;
                }
                boolean isVolumeGroupAssociatedToStrategy = false;
                for (final AudioProductStrategy strategy : audioProductStrategies) {
                    Log.i(TAG, "strategy:" + strategy.toString());
                    int groupId = strategy.getVolumeGroupIdForLegacyStreamType(avgStreamType);
                    if (groupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {

                        assertEquals("Volume Group ID (" + audioVolumeGroup.toString()
                                + "), and Volume group ID associated to Strategy ("
                                + strategy.toString() + ") both supporting stream "
                                + AudioSystem.streamToString(avgStreamType) + "("
                                + avgStreamType + ") are mismatching",
                                audioVolumeGroup.getId(), groupId);
                        isVolumeGroupAssociatedToStrategy = true;
                        break;
                    }
                }
                assertTrue("Volume Group (" + audioVolumeGroup.toString()
                        + ") has no associated strategy for stream "
                        + AudioSystem.streamToString(avgStreamType) + "(" + avgStreamType + ")",
                        isVolumeGroupAssociatedToStrategy);
            }
        }
    }

    //-----------------------------------------------------------------
    // Test Volume per Attributes setter/getters
    //-----------------------------------------------------------------
    public void testSetGetVolumePerAttributesWithInvalidAttributes() throws Exception {
        AudioAttributes nullAttributes = null;

        assertThrows(NullPointerException.class,
                () -> mAudioManager.getMaxVolumeIndexForAttributes(nullAttributes));

        assertThrows(NullPointerException.class,
                () -> mAudioManager.getMinVolumeIndexForAttributes(nullAttributes));

        assertThrows(NullPointerException.class,
                () -> mAudioManager.getVolumeIndexForAttributes(nullAttributes));

        assertThrows(NullPointerException.class,
                () -> mAudioManager.setVolumeIndexForAttributes(
                        nullAttributes, 0 /*index*/, 0/*flags*/));
    }

    public void testSetGetVolumePerAttributes() throws Exception {
        for (int usage : AudioAttributes.SDK_USAGES) {
            if (usage == AudioAttributes.USAGE_UNKNOWN) {
                continue;
            }
            AudioAttributes aaForUsage = new AudioAttributes.Builder().setUsage(usage).build();
            int indexMin = 0;
            int indexMax = 0;
            int index = 0;
            Exception ex = null;

            try {
                indexMax = mAudioManager.getMaxVolumeIndexForAttributes(aaForUsage);
            } catch (Exception e) {
                ex = e; // unexpected
            }
            assertNull("Exception was thrown for valid attributes", ex);
            ex = null;
            try {
                indexMin = mAudioManager.getMinVolumeIndexForAttributes(aaForUsage);
            } catch (Exception e) {
                ex = e; // unexpected
            }
            assertNull("Exception was thrown for valid attributes", ex);
            ex = null;
            try {
                index = mAudioManager.getVolumeIndexForAttributes(aaForUsage);
            } catch (Exception e) {
                ex = e; // unexpected
            }
            assertNull("Exception was thrown for valid attributes", ex);
            ex = null;
            try {
                mAudioManager.setVolumeIndexForAttributes(aaForUsage, indexMin, 0/*flags*/);
            } catch (Exception e) {
                ex = e; // unexpected
            }
            assertNull("Exception was thrown for valid attributes", ex);

            index = mAudioManager.getVolumeIndexForAttributes(aaForUsage);
            assertEquals(index, indexMin);

            mAudioManager.setVolumeIndexForAttributes(aaForUsage, indexMax, 0/*flags*/);
            index = mAudioManager.getVolumeIndexForAttributes(aaForUsage);
            assertEquals(index, indexMax);
        }
    }

    //-----------------------------------------------------------------
    // Test register/unregister VolumeGroupCallback
    //-----------------------------------------------------------------
    public void testVolumeGroupCallback() throws Exception {
        List<AudioVolumeGroup> audioVolumeGroups = mAudioManager.getAudioVolumeGroups();
        assertTrue(audioVolumeGroups.size() > 0);

        AudioVolumeGroupCallbackHelper vgCbReceiver = new AudioVolumeGroupCallbackHelper();
        mAudioManager.registerVolumeGroupCallback(mContext.getMainExecutor(), vgCbReceiver);

        final List<Integer> publicStreams = Ints.asList(PUBLIC_STREAM_TYPES);
        try {
            // Validate Audio Volume Groups callback reception
            for (final AudioVolumeGroup audioVolumeGroup : audioVolumeGroups) {
                int volumeGroupId = audioVolumeGroup.getId();

                // Set the receiver to filter only the current group callback
                vgCbReceiver.setExpectedVolumeGroup(volumeGroupId);

                List<AudioAttributes> avgAttributes = audioVolumeGroup.getAudioAttributes();
                int[] avgStreamTypes = audioVolumeGroup.getLegacyStreamTypes();

                int index = 0;
                int indexMax = 0;
                int indexMin = 0;

                // Set the volume per attributes (if valid) and wait the callback
                for (final AudioAttributes aa : avgAttributes) {
                    if (aa.equals(sDefaultAttributes)) {
                        // Some volume groups may not have valid attributes, used for internal
                        // volume management like patch/rerouting
                        // so bailing out strategy retrieval from attributes
                        continue;
                    }
                    index = mAudioManager.getVolumeIndexForAttributes(aa);
                    indexMax = mAudioManager.getMaxVolumeIndexForAttributes(aa);
                    indexMin = mAudioManager.getMinVolumeIndexForAttributes(aa);
                    index = incrementVolumeIndex(index, indexMin, indexMax);

                    vgCbReceiver.setExpectedVolumeGroup(volumeGroupId);
                    mAudioManager.setVolumeIndexForAttributes(aa, index, 0/*flags*/);
                    assertTrue(vgCbReceiver.waitForExpectedVolumeGroupChanged(
                            AudioVolumeGroupCallbackHelper.ASYNC_TIMEOUT_MS));

                    int readIndex = mAudioManager.getVolumeIndexForAttributes(aa);
                    assertEquals(readIndex, index);
                }
                // Set the volume per stream type (if valid) and wait the callback
                for (final int avgStreamType : avgStreamTypes) {
                    if (avgStreamType == AudioSystem.STREAM_DEFAULT) {
                        // Some Volume Groups may not have corresponding stream types as they
                        // intends to address volume setting per attributes to avoid adding new
                        // stream type and going on deprecating the stream type even for volume
                        // so bailing out strategy retrieval from stream type
                        continue;
                    }
                    if (!publicStreams.contains(avgStreamType)
                            || avgStreamType == AudioManager.STREAM_ACCESSIBILITY) {
                        // Limit scope of test to public stream that do not require any
                        // permission (e.g. Changing ACCESSIBILITY is subject to permission).
                        continue;
                    }
                    index = mAudioManager.getStreamVolume(avgStreamType);
                    indexMax = mAudioManager.getStreamMaxVolume(avgStreamType);
                    indexMin = mAudioManager.getStreamMinVolumeInt(avgStreamType);
                    index = incrementVolumeIndex(index, indexMin, indexMax);

                    vgCbReceiver.setExpectedVolumeGroup(volumeGroupId);
                    mAudioManager.setStreamVolume(avgStreamType, index, 0/*flags*/);
                    assertTrue(vgCbReceiver.waitForExpectedVolumeGroupChanged(
                            AudioVolumeGroupCallbackHelper.ASYNC_TIMEOUT_MS));

                    int readIndex = mAudioManager.getStreamVolume(avgStreamType);
                    assertEquals(index, readIndex);
                }
            }
        } finally {
            mAudioManager.unregisterVolumeGroupCallback(vgCbReceiver);
        }
    }
}
