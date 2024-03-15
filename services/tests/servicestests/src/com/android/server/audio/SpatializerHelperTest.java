/*
 * Copyright 2022 The Android Open Source Project
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

import com.android.server.audio.SpatializerHelper.SADeviceState;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class SpatializerHelperTest {

    private static final String TAG = "SpatializerHelperTest";

    // the actual class under test
    private SpatializerHelper mSpatHelper;

    @Mock private AudioService mMockAudioService;
    @Spy private AudioSystemAdapter mSpyAudioSystem;
    @Mock private AudioSystemAdapter mMockAudioSystem;

    @Before
    public void setUp() throws Exception {
        mMockAudioService = mock(AudioService.class);
    }

    /**
     * Initializes mSpatHelper, the SpatizerHelper instance under test, to use the mock or spy
     * AudioSystemAdapter
     * @param useSpyAudioSystem true to use the spy adapter, mSpyAudioSystem, or false to use
     *                          the mock adapter, mMockAudioSystem.
     */
    private void setUpSpatHelper(boolean useSpyAudioSystem) {
        final AudioSystemAdapter asAdapter;
        if (useSpyAudioSystem) {
            mSpyAudioSystem = spy(new NoOpAudioSystemAdapter());
            asAdapter = mSpyAudioSystem;
            mMockAudioSystem = null;
        } else {
            mSpyAudioSystem = null;
            mMockAudioSystem = mock(NoOpAudioSystemAdapter.class);
            asAdapter = mMockAudioSystem;
        }
        mSpatHelper = new SpatializerHelper(mMockAudioService, asAdapter,
                true /*binauralEnabledDefault*/,
                true /*transauralEnabledDefault*/,
                false /*headTrackingEnabledDefault*/);

    }

    /**
     * Test that constructing an SADeviceState instance requires a non-null address for a
     * wireless type, but can take null for a non-wireless type;
     * @throws Exception
     */
    @Test
    public void testSADeviceStateNullAddressCtor() throws Exception {
        setUpSpatHelper(true /*useSpyAudioSystem*/);
        try {
            SADeviceState devState = new SADeviceState(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null);
            devState = new SADeviceState(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null);
            Assert.fail();
        } catch (NullPointerException e) { }
    }

    @Test
    public void testSADeviceStateStringSerialization() throws Exception {
        Log.i(TAG, "starting testSADeviceStateStringSerialization");
        setUpSpatHelper(true /*useSpyAudioSystem*/);
        final SADeviceState devState = new SADeviceState(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "bla");
        devState.mHasHeadTracker = false;
        devState.mHeadTrackerEnabled = false;
        devState.mEnabled = true;
        final String persistString = devState.toPersistableString();
        final SADeviceState result = SADeviceState.fromPersistedString(persistString);
        Log.i(TAG, "original:" + devState);
        Log.i(TAG, "result  :" + result);
        Assert.assertEquals(devState, result);
    }

    @Test
    public void testSADeviceSettings() throws Exception {
        Log.i(TAG, "starting testSADeviceSettings");
        setUpSpatHelper(true /*useSpyAudioSystem*/);
        final AudioDeviceAttributes dev1 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_SPEAKER, "");
        final AudioDeviceAttributes dev2 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, "C3:PO:beep");
        final AudioDeviceAttributes dev3 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, "R2:D2:bloop");

        doNothing().when(mMockAudioService).persistSpatialAudioDeviceSettings();
        mSpatHelper.initForTest(true /*binaural*/, true /*transaural*/);

        // test with single device
        mSpatHelper.addCompatibleAudioDevice(dev1);
        checkAddSettings();

        // test with 2+ devices so separator character is used in list
        mSpatHelper.addCompatibleAudioDevice(dev2);
        Assert.assertTrue(mSpatHelper.isAvailableForDevice(dev2));
        checkAddSettings();
        Assert.assertTrue(mSpatHelper.isAvailableForDevice(dev2));
        mSpatHelper.addCompatibleAudioDevice(dev3);
        checkAddSettings();

        // test adding a device twice in the list
        mSpatHelper.addCompatibleAudioDevice(dev1);
        checkAddSettings();

        // test removing a device
        mSpatHelper.removeCompatibleAudioDevice(dev2);
        // spatializer could still be run for dev2 (is available) but spatial audio
        // is disabled for dev2 by removeCompatibleAudioDevice
        Assert.assertTrue(mSpatHelper.isAvailableForDevice(dev2));
        List<AudioDeviceAttributes> compatDevices = mSpatHelper.getCompatibleAudioDevices();
        Assert.assertFalse(compatDevices.stream().anyMatch(dev -> dev.equalTypeAddress(dev2)));
        checkAddSettings();
    }

    /**
     * Gets the string representing the current configuration of the devices, then clears it
     * and restores the configuration. Verify the new string from the restored settings matches
     * the original one.
     */
    private void checkAddSettings() throws Exception {
        String settings = mSpatHelper.getSADeviceSettings();
        Log.i(TAG, "device settings: " + settings);
        mSpatHelper.clearSADevices();
        mSpatHelper.setSADeviceSettings(settings);
        String settingsRestored = mSpatHelper.getSADeviceSettings();
        Log.i(TAG, "device settingsRestored: " + settingsRestored);
        Assert.assertEquals(settings, settingsRestored);
    }

    /**
     * Test that null devices for routing do not break canBeSpatialized
     * @throws Exception
     */
    @Test
    public void testNoRoutingCanBeSpatialized() throws Exception {
        Log.i(TAG, "Starting testNoRoutingCanBeSpatialized");
        setUpSpatHelper(false /*useSpyAudioSystem*/);
        mSpatHelper.forceStateForTest(SpatializerHelper.STATE_ENABLED_AVAILABLE);

        final ArrayList<AudioDeviceAttributes> emptyList = new ArrayList<>(0);
        final ArrayList<AudioDeviceAttributes> listWithNull = new ArrayList<>(1);
        listWithNull.add(null);
        final AudioAttributes media = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        final AudioFormat spatialFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build();

        when(mMockAudioSystem.getDevicesForAttributes(any(AudioAttributes.class), anyBoolean()))
                .thenReturn(emptyList);
        Assert.assertFalse("can be spatialized on empty routing",
                mSpatHelper.canBeSpatialized(media, spatialFormat));

        when(mMockAudioSystem.getDevicesForAttributes(any(AudioAttributes.class), anyBoolean()))
                .thenReturn(listWithNull);
        Assert.assertFalse("can be spatialized on null routing",
                mSpatHelper.canBeSpatialized(media, spatialFormat));
    }
}
