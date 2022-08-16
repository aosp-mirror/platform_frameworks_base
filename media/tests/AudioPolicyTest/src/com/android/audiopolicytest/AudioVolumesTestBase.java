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

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.test.ActivityInstrumentationTestCase2;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AudioVolumesTestBase extends ActivityInstrumentationTestCase2<AudioPolicyTest> {
    public AudioManager mAudioManager;
    Context mContext;
    private Map<Integer, Integer> mOriginalStreamVolumes = new HashMap<>();
    private Map<Integer, Integer> mOriginalVolumeGroupVolumes = new HashMap<>();

    // Default matches the invalid (empty) attributes from native.
    // The difference is the input source default which is not aligned between native and java
    public static final AudioAttributes sDefaultAttributes =
            AudioProductStrategy.getDefaultAttributes();

    public static final AudioAttributes sInvalidAttributes = new AudioAttributes.Builder().build();

    public AudioVolumesTestBase() {
        super("com.android.audiopolicytest", AudioPolicyTest.class);
    }

    /**
     * <p>Note: must be called with shell permission (MODIFY_AUDIO_ROUTING)
     */
    private void storeAllVolumes() {
        List<AudioVolumeGroup> audioVolumeGroups = mAudioManager.getAudioVolumeGroups();
        for (final AudioVolumeGroup avg : audioVolumeGroups) {
            if (avg.getAudioAttributes().isEmpty()) {
                // some volume group may not supports volume control per attributes
                // like rerouting/patch since these groups are internal to audio policy manager
                continue;
            }
            AudioAttributes avgAttributes = sDefaultAttributes;
            for (final AudioAttributes aa : avg.getAudioAttributes()) {
                if (!aa.equals(AudioProductStrategy.getDefaultAttributes())) {
                    avgAttributes = aa;
                    break;
                }
            }
            if (avgAttributes.equals(sDefaultAttributes)) {
                // This shall not happen, however, not purpose of this base class.
                // so bailing out.
                continue;
            }
            mOriginalVolumeGroupVolumes.put(
                    avg.getId(), mAudioManager.getVolumeIndexForAttributes(avgAttributes));
        }
    }

    /**
     * <p>Note: must be called with shell permission (MODIFY_AUDIO_ROUTING)
     */
    private void restoreAllVolumes() {
        List<AudioVolumeGroup> audioVolumeGroups = mAudioManager.getAudioVolumeGroups();
        for (Map.Entry<Integer, Integer> e : mOriginalVolumeGroupVolumes.entrySet()) {
            for (final AudioVolumeGroup avg : audioVolumeGroups) {
                if (avg.getId() == e.getKey()) {
                    assertTrue(!avg.getAudioAttributes().isEmpty());
                    AudioAttributes avgAttributes = sDefaultAttributes;
                    for (final AudioAttributes aa : avg.getAudioAttributes()) {
                        if (!aa.equals(AudioProductStrategy.getDefaultAttributes())) {
                            avgAttributes = aa;
                            break;
                        }
                    }
                    assertTrue(!avgAttributes.equals(sDefaultAttributes));
                    mAudioManager.setVolumeIndexForAttributes(
                            avgAttributes, e.getValue(), AudioManager.FLAG_ALLOW_RINGER_MODES);
                }
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getActivity();
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        assertEquals(PackageManager.PERMISSION_GRANTED,
                mContext.checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING));

        // Store the original volumes that that they can be recovered in tearDown().
        mOriginalStreamVolumes.clear();
        for (int streamType : AudioManager.getPublicStreamTypes()) {
            mOriginalStreamVolumes.put(streamType, mAudioManager.getStreamVolume(streamType));
        }
        // Store the original volume per attributes so that they can be recovered in tearDown()
        mOriginalVolumeGroupVolumes.clear();
        storeAllVolumes();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        // Recover the volume and the ringer mode that the test may have overwritten.
        for (Map.Entry<Integer, Integer> e : mOriginalStreamVolumes.entrySet()) {
            mAudioManager.setStreamVolume(e.getKey(), e.getValue(),
                                          AudioManager.FLAG_ALLOW_RINGER_MODES);
        }

        // Recover the original volume per attributes
        restoreAllVolumes();
    }

    public static int resetVolumeIndex(int indexMin, int indexMax) {
        return (indexMax + indexMin) / 2;
    }

    public static int incrementVolumeIndex(int index, int indexMin, int indexMax) {
        return (index + 1 > indexMax) ? resetVolumeIndex(indexMin, indexMax) : ++index;
    }
}
