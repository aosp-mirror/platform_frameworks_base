/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.io.InputStream;

import com.android.powermodel.component.CpuProfile;
import com.android.powermodel.component.AudioProfile;
import com.android.powermodel.component.BluetoothProfile;
import com.android.powermodel.component.CameraProfile;
import com.android.powermodel.component.FlashlightProfile;
import com.android.powermodel.component.GpsProfile;
import com.android.powermodel.component.ModemProfile;
import com.android.powermodel.component.ScreenProfile;
import com.android.powermodel.component.VideoProfile;
import com.android.powermodel.component.WifiProfile;
import org.junit.Assert;
import org.junit.Test;

/*
 * Additional tests needed:
 *   - CPU clusters with mismatching counts of speeds and coefficients
 *   - Extra fields
 *   - Name listed twice
 */

/**
 * Tests {@link PowerProfile}
 */
public class PowerProfileTest {
    private static final float EPSILON = 0.00001f;

    private static InputStream loadPowerProfileStream() {
        return PowerProfileTest.class.getResourceAsStream("/power_profile.xml");
    }

    @Test public void testReadGood() throws Exception {
        final InputStream is = loadPowerProfileStream();

        final PowerProfile profile = PowerProfile.parse(is);

        // Audio
        final AudioProfile audio = (AudioProfile)profile.getComponent(Component.AUDIO);
        Assert.assertEquals(12.0f, audio.onMa, EPSILON);

        // Bluetooth
        final BluetoothProfile bluetooth
                = (BluetoothProfile)profile.getComponent(Component.BLUETOOTH);
        Assert.assertEquals(0.02f, bluetooth.idleMa, EPSILON);
        Assert.assertEquals(3.0f, bluetooth.rxMa, EPSILON);
        Assert.assertEquals(5.0f, bluetooth.txMa, EPSILON);

        // Camera
        final CameraProfile camera = (CameraProfile)profile.getComponent(Component.CAMERA);
        Assert.assertEquals(941.0f, camera.onMa, EPSILON);

        // CPU
        final CpuProfile cpu = (CpuProfile)profile.getComponent(Component.CPU);
        Assert.assertEquals(1.3f, cpu.suspendMa, EPSILON);
        Assert.assertEquals(3.9f, cpu.idleMa, EPSILON);
        Assert.assertEquals(18.33f, cpu.activeMa, EPSILON);
        Assert.assertEquals(2, cpu.clusters.length);
        // Cluster 0
        Assert.assertEquals(4, cpu.clusters[0].coreCount);
        Assert.assertEquals(2.41f, cpu.clusters[0].onMa, EPSILON);
        Assert.assertEquals(9, cpu.clusters[0].frequencies.length, EPSILON);
        Assert.assertEquals(100000, cpu.clusters[0].frequencies[0].speedHz);
        Assert.assertEquals(0.29f, cpu.clusters[0].frequencies[0].onMa, EPSILON);
        Assert.assertEquals(303200, cpu.clusters[0].frequencies[1].speedHz);
        Assert.assertEquals(0.63f, cpu.clusters[0].frequencies[1].onMa, EPSILON);
        Assert.assertEquals(380000, cpu.clusters[0].frequencies[2].speedHz);
        Assert.assertEquals(1.23f, cpu.clusters[0].frequencies[2].onMa, EPSILON);
        Assert.assertEquals(476000, cpu.clusters[0].frequencies[3].speedHz);
        Assert.assertEquals(1.24f, cpu.clusters[0].frequencies[3].onMa, EPSILON);
        Assert.assertEquals(552800, cpu.clusters[0].frequencies[4].speedHz);
        Assert.assertEquals(2.47f, cpu.clusters[0].frequencies[4].onMa, EPSILON);
        Assert.assertEquals(648800, cpu.clusters[0].frequencies[5].speedHz);
        Assert.assertEquals(2.54f, cpu.clusters[0].frequencies[5].onMa, EPSILON);
        Assert.assertEquals(725600, cpu.clusters[0].frequencies[6].speedHz);
        Assert.assertEquals(3.60f, cpu.clusters[0].frequencies[6].onMa, EPSILON);
        Assert.assertEquals(802400, cpu.clusters[0].frequencies[7].speedHz);
        Assert.assertEquals(3.64f, cpu.clusters[0].frequencies[7].onMa, EPSILON);
        Assert.assertEquals(879200, cpu.clusters[0].frequencies[8].speedHz);
        Assert.assertEquals(4.42f, cpu.clusters[0].frequencies[8].onMa, EPSILON);
        // Cluster 1
        Assert.assertEquals(2, cpu.clusters[1].coreCount);
        Assert.assertEquals(5.29f, cpu.clusters[1].onMa, EPSILON);
        Assert.assertEquals(7, cpu.clusters[1].frequencies.length, EPSILON);
        Assert.assertEquals(825600, cpu.clusters[1].frequencies[0].speedHz);
        Assert.assertEquals(28.98f, cpu.clusters[1].frequencies[0].onMa, EPSILON);
        Assert.assertEquals(902400, cpu.clusters[1].frequencies[1].speedHz);
        Assert.assertEquals(31.40f, cpu.clusters[1].frequencies[1].onMa, EPSILON);
        Assert.assertEquals(979200, cpu.clusters[1].frequencies[2].speedHz);
        Assert.assertEquals(33.33f, cpu.clusters[1].frequencies[2].onMa, EPSILON);
        Assert.assertEquals(1056000, cpu.clusters[1].frequencies[3].speedHz);
        Assert.assertEquals(40.12f, cpu.clusters[1].frequencies[3].onMa, EPSILON);
        Assert.assertEquals(1209600, cpu.clusters[1].frequencies[4].speedHz);
        Assert.assertEquals(44.10f, cpu.clusters[1].frequencies[4].onMa, EPSILON);
        Assert.assertEquals(1286400, cpu.clusters[1].frequencies[5].speedHz);
        Assert.assertEquals(90.14f, cpu.clusters[1].frequencies[5].onMa, EPSILON);
        Assert.assertEquals(1363200, cpu.clusters[1].frequencies[6].speedHz);
        Assert.assertEquals(100f, cpu.clusters[1].frequencies[6].onMa, EPSILON);

        // Flashlight
        final FlashlightProfile flashlight
                = (FlashlightProfile)profile.getComponent(Component.FLASHLIGHT);
        Assert.assertEquals(1233.47f, flashlight.onMa, EPSILON);

        // GPS
        final GpsProfile gps = (GpsProfile)profile.getComponent(Component.GPS);
        Assert.assertEquals(1.0f, gps.onMa, EPSILON);
        Assert.assertEquals(2, gps.signalQualityMa.length);
        Assert.assertEquals(88.0f, gps.signalQualityMa[0], EPSILON);
        Assert.assertEquals(7.0f, gps.signalQualityMa[1], EPSILON);

        // Modem
        final ModemProfile modem = (ModemProfile)profile.getComponent(Component.MODEM);
        Assert.assertEquals(1.0f, modem.sleepMa, EPSILON);
        Assert.assertEquals(44.0f, modem.idleMa, EPSILON);
        Assert.assertEquals(12.0f, modem.scanningMa, EPSILON);
        Assert.assertEquals(11.0f, modem.rxMa, EPSILON);
        Assert.assertEquals(5, modem.txMa.length);
        Assert.assertEquals(16.0f, modem.txMa[0], EPSILON);
        Assert.assertEquals(19.0f, modem.txMa[1], EPSILON);
        Assert.assertEquals(22.0f, modem.txMa[2], EPSILON);
        Assert.assertEquals(73.0f, modem.txMa[3], EPSILON);
        Assert.assertEquals(132.0f, modem.txMa[4], EPSILON);

        // Screen
        final ScreenProfile screen = (ScreenProfile)profile.getComponent(Component.SCREEN);
        Assert.assertEquals(102.4f, screen.onMa, EPSILON);
        Assert.assertEquals(1234.0f, screen.fullMa, EPSILON);
        Assert.assertEquals(12.0f, screen.ambientMa, EPSILON);

        // Video
        final VideoProfile video = (VideoProfile)profile.getComponent(Component.VIDEO);
        Assert.assertEquals(123.0f, video.onMa, EPSILON);

        // Wifi
        final WifiProfile wifi = (WifiProfile)profile.getComponent(Component.WIFI);
        Assert.assertEquals(2.0f, wifi.idleMa, EPSILON);
        Assert.assertEquals(123.0f, wifi.rxMa, EPSILON);
        Assert.assertEquals(333.0f, wifi.txMa, EPSILON);
    }
}
