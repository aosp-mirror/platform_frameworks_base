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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
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
    @Spy private AudioDeviceBroker mSpyDeviceBroker;

    @Before
    public void setUp() throws Exception {
        mMockAudioService = mock(AudioService.class);

        mSpyAudioSystem = spy(new NoOpAudioSystemAdapter());
        mSpyDeviceBroker = spy(
                new AudioDeviceBroker(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        mMockAudioService, mSpyAudioSystem));
        mSpatHelper = new SpatializerHelper(mMockAudioService, mSpyAudioSystem,
                mSpyDeviceBroker, /*binauralEnabledDefault=*/true, /*transauralEnabledDefault=*/
                true, /*headTrackingEnabledDefault*/false);
    }

    @Test
    public void testAdiDeviceStateSettings() throws Exception {
        Log.i(TAG, "starting testSADeviceSettings");
        final AudioDeviceAttributes dev1 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_SPEAKER, "");
        final AudioDeviceAttributes dev2 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, "C3:PO:beep");
        final AudioDeviceAttributes dev3 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, "R2:D2:bloop");

        doNothing().when(mSpyDeviceBroker).postPersistAudioDeviceSettings();
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
        String settings = mSpyDeviceBroker.getDeviceSettings();
        Log.i(TAG, "device settings: " + settings);
        mSpyDeviceBroker.clearDeviceInventory();
        mSpyDeviceBroker.setDeviceSettings(settings);
        String settingsRestored = mSpyDeviceBroker.getDeviceSettings();
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
        mSpatHelper.forceStateForTest(SpatializerHelper.STATE_ENABLED_AVAILABLE);

        final ArrayList<AudioDeviceAttributes> emptyList = new ArrayList<>(0);
        final ArrayList<AudioDeviceAttributes> listWithNull = new ArrayList<>(1);
        listWithNull.add(null);
        final AudioAttributes media = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        final AudioFormat spatialFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build();

        when(mSpyAudioSystem.getDevicesForAttributes(any(AudioAttributes.class), anyBoolean()))
                .thenReturn(emptyList);
        Assert.assertFalse("can be spatialized on empty routing",
                mSpatHelper.canBeSpatialized(media, spatialFormat));

        when(mSpyAudioSystem.getDevicesForAttributes(any(AudioAttributes.class), anyBoolean()))
                .thenReturn(listWithNull);
        Assert.assertFalse("can be spatialized on null routing",
                mSpatHelper.canBeSpatialized(media, spatialFormat));
    }
}
