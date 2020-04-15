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
import static org.testng.Assert.assertThrows;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.audiopolicy.AudioVolumeGroup;
import android.media.audiopolicy.AudioVolumeGroupChangeHandler;

import java.util.ArrayList;
import java.util.List;

public class AudioVolumeGroupChangeHandlerTest extends AudioVolumesTestBase {
    private static final String TAG = "AudioVolumeGroupChangeHandlerTest";

    public void testRegisterInvalidCallback() throws Exception {
        final AudioVolumeGroupChangeHandler audioAudioVolumeGroupChangedHandler =
                new AudioVolumeGroupChangeHandler();

        audioAudioVolumeGroupChangedHandler.init();

        assertThrows(NullPointerException.class, () -> {
            AudioManager.VolumeGroupCallback nullCb = null;
            audioAudioVolumeGroupChangedHandler.registerListener(nullCb);
        });
    }

    public void testUnregisterInvalidCallback() throws Exception {
        final AudioVolumeGroupChangeHandler audioAudioVolumeGroupChangedHandler =
                new AudioVolumeGroupChangeHandler();

        audioAudioVolumeGroupChangedHandler.init();

        final AudioVolumeGroupCallbackHelper cb = new AudioVolumeGroupCallbackHelper();
        audioAudioVolumeGroupChangedHandler.registerListener(cb);

        assertThrows(NullPointerException.class, () -> {
            AudioManager.VolumeGroupCallback nullCb = null;
            audioAudioVolumeGroupChangedHandler.unregisterListener(nullCb);
        });
        audioAudioVolumeGroupChangedHandler.unregisterListener(cb);
    }

    public void testRegisterUnregisterCallback() throws Exception {
        final AudioVolumeGroupChangeHandler audioAudioVolumeGroupChangedHandler =
                new AudioVolumeGroupChangeHandler();

        audioAudioVolumeGroupChangedHandler.init();
        final AudioVolumeGroupCallbackHelper validCb = new AudioVolumeGroupCallbackHelper();

        // Should not assert, otherwise test will fail
        audioAudioVolumeGroupChangedHandler.registerListener(validCb);

        // Should not assert, otherwise test will fail
        audioAudioVolumeGroupChangedHandler.unregisterListener(validCb);
    }

    public void testCallbackReceived() throws Exception {
        final AudioVolumeGroupChangeHandler audioAudioVolumeGroupChangedHandler =
                new AudioVolumeGroupChangeHandler();

        audioAudioVolumeGroupChangedHandler.init();

        final AudioVolumeGroupCallbackHelper validCb = new AudioVolumeGroupCallbackHelper();
        audioAudioVolumeGroupChangedHandler.registerListener(validCb);

        List<AudioVolumeGroup> audioVolumeGroups = mAudioManager.getAudioVolumeGroups();
        assertTrue(audioVolumeGroups.size() > 0);

        try {
            for (final AudioVolumeGroup audioVolumeGroup : audioVolumeGroups) {
                int volumeGroupId = audioVolumeGroup.getId();

                List<AudioAttributes> avgAttributes = audioVolumeGroup.getAudioAttributes();
                // Set the volume per attributes (if valid) and wait the callback
                if (avgAttributes.size() == 0 || avgAttributes.get(0).equals(sDefaultAttributes)) {
                    // Some volume groups may not have valid attributes, used for internal
                    // volume management like patch/rerouting
                    // so bailing out strategy retrieval from attributes
                    continue;
                }
                final AudioAttributes aa = avgAttributes.get(0);

                int index = mAudioManager.getVolumeIndexForAttributes(aa);
                int indexMax = mAudioManager.getMaxVolumeIndexForAttributes(aa);
                int indexMin = mAudioManager.getMinVolumeIndexForAttributes(aa);

                final int indexForAa = incrementVolumeIndex(index, indexMin, indexMax);

                // Set the receiver to filter only the current group callback
                validCb.setExpectedVolumeGroup(volumeGroupId);
                mAudioManager.setVolumeIndexForAttributes(aa, indexForAa, 0/*flags*/);
                assertTrue(validCb.waitForExpectedVolumeGroupChanged(
                        AudioVolumeGroupCallbackHelper.ASYNC_TIMEOUT_MS));

                final int readIndex = mAudioManager.getVolumeIndexForAttributes(aa);
                assertEquals(readIndex, indexForAa);
            }
        } finally {
            audioAudioVolumeGroupChangedHandler.unregisterListener(validCb);
        }
    }

    public void testMultipleCallbackReceived() throws Exception {

        final AudioVolumeGroupChangeHandler audioAudioVolumeGroupChangedHandler =
                new AudioVolumeGroupChangeHandler();

        audioAudioVolumeGroupChangedHandler.init();

        final int callbackCount = 10;
        final List<AudioVolumeGroupCallbackHelper> validCbs =
                new ArrayList<AudioVolumeGroupCallbackHelper>();
        for (int i = 0; i < callbackCount; i++) {
            validCbs.add(new AudioVolumeGroupCallbackHelper());
        }
        for (final AudioVolumeGroupCallbackHelper cb : validCbs) {
            audioAudioVolumeGroupChangedHandler.registerListener(cb);
        }

        List<AudioVolumeGroup> audioVolumeGroups = mAudioManager.getAudioVolumeGroups();
        assertTrue(audioVolumeGroups.size() > 0);

        try {
            for (final AudioVolumeGroup audioVolumeGroup : audioVolumeGroups) {
                int volumeGroupId = audioVolumeGroup.getId();

                List<AudioAttributes> avgAttributes = audioVolumeGroup.getAudioAttributes();
                // Set the volume per attributes (if valid) and wait the callback
                if (avgAttributes.size() == 0 || avgAttributes.get(0).equals(sDefaultAttributes)) {
                    // Some volume groups may not have valid attributes, used for internal
                    // volume management like patch/rerouting
                    // so bailing out strategy retrieval from attributes
                    continue;
                }
                AudioAttributes aa = avgAttributes.get(0);

                int index = mAudioManager.getVolumeIndexForAttributes(aa);
                int indexMax = mAudioManager.getMaxVolumeIndexForAttributes(aa);
                int indexMin = mAudioManager.getMinVolumeIndexForAttributes(aa);

                final int indexForAa = incrementVolumeIndex(index, indexMin, indexMax);

                // Set the receiver to filter only the current group callback
                for (final AudioVolumeGroupCallbackHelper cb : validCbs) {
                    cb.setExpectedVolumeGroup(volumeGroupId);
                }
                mAudioManager.setVolumeIndexForAttributes(aa, indexForAa, 0/*flags*/);

                for (final AudioVolumeGroupCallbackHelper cb : validCbs) {
                    assertTrue(cb.waitForExpectedVolumeGroupChanged(
                            AudioVolumeGroupCallbackHelper.ASYNC_TIMEOUT_MS));
                }
                int readIndex = mAudioManager.getVolumeIndexForAttributes(aa);
                assertEquals(readIndex, indexForAa);
            }
        } finally {
            for (final AudioVolumeGroupCallbackHelper cb : validCbs) {
                audioAudioVolumeGroupChangedHandler.unregisterListener(cb);
            }
        }
    }
}
