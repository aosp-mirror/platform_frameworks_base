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

import static org.junit.Assert.assertNotEquals;

import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;

import java.util.List;

public class AudioVolumeGroupTest extends AudioVolumesTestBase {
    private static final String TAG = "AudioVolumeGroupTest";

    //-----------------------------------------------------------------
    // Test getAudioVolumeGroups and validate groud id
    //-----------------------------------------------------------------
    public void testGetVolumeGroupsFromNonServiceCaller() throws Exception {
        // The transaction behind getAudioVolumeGroups will fail. Check is done at binder level
        // with policy service. Error is not reported, the list is just empty.
        // Request must come from service components
        List<AudioVolumeGroup> audioVolumeGroup = AudioVolumeGroup.getAudioVolumeGroups();

        assertNotNull(audioVolumeGroup);
        assertEquals(audioVolumeGroup.size(), 0);
    }

    //-----------------------------------------------------------------
    // Test getAudioVolumeGroups and validate groud id
    //-----------------------------------------------------------------
    public void testGetVolumeGroups() throws Exception {
        // Through AudioManager, the transaction behind getAudioVolumeGroups will succeed
        final List<AudioVolumeGroup> audioVolumeGroup = mAudioManager.getAudioVolumeGroups();
        assertNotNull(audioVolumeGroup);
        assertTrue(audioVolumeGroup.size() > 0);

        final List<AudioProductStrategy> audioProductStrategies =
                mAudioManager.getAudioProductStrategies();
        assertTrue(audioProductStrategies.size() > 0);

        for (final AudioVolumeGroup avg : audioVolumeGroup) {
            int avgId = avg.getId();
            assertNotEquals(avgId, AudioVolumeGroup.DEFAULT_VOLUME_GROUP);

            List<AudioAttributes> avgAttributes = avg.getAudioAttributes();
            assertNotNull(avgAttributes);

            final int[] avgStreamTypes = avg.getLegacyStreamTypes();
            assertNotNull(avgStreamTypes);

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
                for (final AudioProductStrategy aps : audioProductStrategies) {
                    int groupId = aps.getVolumeGroupIdForAudioAttributes(aa);
                    if (groupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {
                        // Note that Audio Product Strategies are priority ordered, and the
                        // the first one matching the AudioAttributes will be used to identify
                        // the volume group associated to the request.
                        assertTrue(aps.supportsAudioAttributes(aa));
                        assertEquals("Volume Group ID (" + avg.toString()
                                + "), and Volume group ID associated to Strategy ("
                                + aps.toString() + ") both supporting attributes "
                                + aa.toString() + " are mismatching",
                                avgId, groupId);
                        isVolumeGroupAssociatedToStrategy = true;
                        break;
                    }
                }
                assertTrue("Volume Group (" + avg.toString()
                        + ") has no associated strategy for attributes " + aa.toString(),
                        isVolumeGroupAssociatedToStrategy);
            }

            // for each volume group stream type, find the matching product strategy and ensure
            // it is linked the considered volume group
            for (final int avgStreamType : avgStreamTypes) {
                if (avgStreamType == AudioSystem.STREAM_DEFAULT) {
                    // Some Volume Groups may not have corresponding stream types as they
                    // intends to address volume setting per attributes to avoid adding new
                    // stream type and going on deprecating the stream type even for volume
                    // so bailing out strategy retrieval from stream type
                    continue;
                }
                boolean isVolumeGroupAssociatedToStrategy = false;
                for (final AudioProductStrategy aps : audioProductStrategies) {
                    int groupId = aps.getVolumeGroupIdForLegacyStreamType(avgStreamType);
                    if (groupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {

                        assertEquals("Volume Group ID (" + avg.toString()
                                + "), and Volume group ID associated to Strategy ("
                                + aps.toString() + ") both supporting stream "
                                + AudioSystem.streamToString(avgStreamType) + "("
                                + avgStreamType + ") are mismatching",
                                avgId, groupId);

                        isVolumeGroupAssociatedToStrategy = true;
                        break;
                    }
                }
                assertTrue("Volume Group (" + avg.toString()
                        + ") has no associated strategy for stream "
                        + AudioSystem.streamToString(avgStreamType) + "(" + avgStreamType + ")",
                        isVolumeGroupAssociatedToStrategy);
            }
        }
    }
}
