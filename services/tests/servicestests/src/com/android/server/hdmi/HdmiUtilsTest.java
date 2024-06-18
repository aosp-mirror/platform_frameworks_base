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
package com.android.server.hdmi;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.platform.test.annotations.Presubmit;
import android.util.Slog;

import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiUtils.CodecSad;
import com.android.server.hdmi.HdmiUtils.DeviceConfig;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiUtils} class. */
public class HdmiUtilsTest {

    private static final String TAG = "HdmiUtilsTest";

    private final String mExampleXML =
            "<!-- A sample Short Audio Descriptor configuration xml -->"
                    + "<config version=\"1.0\" xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
                    + "<device type=\"VX_AUDIO_DEVICE_IN_HDMI_ARC\">"
                    + "<supportedFormat format=\"AUDIO_FORMAT_LPCM\" descriptor=\"011a03\"/>"
                    + "<supportedFormat format=\"AUDIO_FORMAT_DD\" descriptor=\"0d0506\"/>"
                    + "</device>"
                    + "<device type=\"AUDIO_DEVICE_IN_SPDIF\">"
                    + "<supportedFormat format=\"AUDIO_FORMAT_LPCM\" descriptor=\"010203\"/>"
                    + "<supportedFormat format=\"AUDIO_FORMAT_DD\" descriptor=\"040506\"/>"
                    + "</device>"
                    + "</config>";

    @Test
    public void pathToPort_isMe() {
        int targetPhysicalAddress = 0x1000;
        int myPhysicalAddress = 0x1000;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                        HdmiUtils.TARGET_SAME_PHYSICAL_ADDRESS);
    }

    @Test
    public void pathToPort_isDirectlyBelow() {
        int targetPhysicalAddress = 0x1100;
        int myPhysicalAddress = 0x1000;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(1);
    }

    @Test
    public void pathToPort_isBelow() {
        int targetPhysicalAddress = 0x1110;
        int myPhysicalAddress = 0x1000;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(1);
    }

    @Test
    public void pathToPort_neitherMeNorBelow() {
        int targetPhysicalAddress = 0x3000;
        int myPhysicalAddress = 0x2000;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                        HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE);

        targetPhysicalAddress = 0x2200;
        myPhysicalAddress = 0x3300;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                        HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE);

        targetPhysicalAddress = 0x2213;
        myPhysicalAddress = 0x2212;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                        HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE);

        targetPhysicalAddress = 0x2340;
        myPhysicalAddress = 0x2310;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                        HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE);
    }

    @Test
    public void testEqualsCodecSad() {
        byte[] sad = {0x0a, 0x1b, 0x2c};
        String sadString = "0a1b2c";
        new EqualsTester()
                .addEqualityGroup(
                        new HdmiUtils.CodecSad(Constants.AUDIO_CODEC_LPCM, sad),
                        new HdmiUtils.CodecSad(Constants.AUDIO_CODEC_LPCM, sadString))
                .addEqualityGroup(
                        new HdmiUtils.CodecSad(Constants.AUDIO_CODEC_LPCM, sadString + "01"))
                .addEqualityGroup(new HdmiUtils.CodecSad(Constants.AUDIO_CODEC_DD, sadString))
                .addEqualityGroup(
                        new HdmiUtils.CodecSad(Constants.AUDIO_CODEC_DD, sadString + "01"))
                .testEquals();
    }

    @Test
    public void testEqualsDeviceConfig() {
        String name = "Name";

        CodecSad expectedCodec1 = new CodecSad(Constants.AUDIO_CODEC_LPCM, "011a03");
        CodecSad expectedCodec2 = new CodecSad(Constants.AUDIO_CODEC_DD, "0d0506");
        CodecSad expectedCodec3 = new CodecSad(Constants.AUDIO_CODEC_LPCM, "010203");
        CodecSad expectedCodec4 = new CodecSad(Constants.AUDIO_CODEC_DD, "040506");

        List<CodecSad> list1 = new ArrayList();
        list1.add(expectedCodec1);
        list1.add(expectedCodec2);
        list1.add(expectedCodec3);

        List<CodecSad> list1Duplicate = new ArrayList(list1);

        List<CodecSad> list2 = new ArrayList(list1);
        list2.add(expectedCodec4);

        new EqualsTester()
                .addEqualityGroup(
                        new HdmiUtils.DeviceConfig(name, list1),
                        new HdmiUtils.DeviceConfig(name, list1Duplicate))
                .addEqualityGroup(new HdmiUtils.DeviceConfig(name, list2))
                .addEqualityGroup(new HdmiUtils.DeviceConfig("my" + name, list1))
                .addEqualityGroup(new HdmiUtils.DeviceConfig("my" + name, list2))
                .testEquals();
    }

    @Test
    public void parseSampleXML() {
        List<DeviceConfig> config = new ArrayList<>();
        try {
            config = HdmiUtils.ShortAudioDescriptorXmlParser.parse(
                    new ByteArrayInputStream(mExampleXML.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            Slog.e(TAG, e.getMessage(), e);
        } catch (XmlPullParserException e) {
            Slog.e(TAG, e.getMessage(), e);
        }

        CodecSad expectedCodec1 = new CodecSad(Constants.AUDIO_CODEC_LPCM, "011a03");
        CodecSad expectedCodec2 = new CodecSad(Constants.AUDIO_CODEC_DD, "0d0506");
        CodecSad expectedCodec3 = new CodecSad(Constants.AUDIO_CODEC_LPCM, "010203");
        CodecSad expectedCodec4 = new CodecSad(Constants.AUDIO_CODEC_DD, "040506");

        List<CodecSad> expectedList1 = new ArrayList<>();
        expectedList1.add(expectedCodec1);
        expectedList1.add(expectedCodec2);

        List<CodecSad> expectedList2 = new ArrayList<>();
        expectedList2.add(expectedCodec3);
        expectedList2.add(expectedCodec4);

        DeviceConfig expectedDevice1 = new DeviceConfig(
                "VX_AUDIO_DEVICE_IN_HDMI_ARC", expectedList1);
        DeviceConfig expectedDevice2 = new DeviceConfig(
                "AUDIO_DEVICE_IN_SPDIF", expectedList2);

        List<DeviceConfig> expectedConfig = new ArrayList<>();
        expectedConfig.add(expectedDevice1);
        expectedConfig.add(expectedDevice2);

        assertThat(config).isEqualTo(expectedConfig);
    }

    @Test
    public void isAffectingActiveRoutingPath() {
        // New path alters the parent
        assertTrue(HdmiUtils.isAffectingActiveRoutingPath(0x1100, 0x2000));
        // New path is a sibling
        assertTrue(HdmiUtils.isAffectingActiveRoutingPath(0x1100, 0x1200));
        // New path is the descendant of a sibling
        assertFalse(HdmiUtils.isAffectingActiveRoutingPath(0x1100, 0x1210));
        // In a completely different path
        assertFalse(HdmiUtils.isAffectingActiveRoutingPath(0x1000, 0x3200));
    }

    @Test
    public void isInActiveRoutingPath() {
        // New path is a parent
        assertTrue(HdmiUtils.isInActiveRoutingPath(0x1100, 0x1000));
        // New path is a descendant
        assertTrue(HdmiUtils.isInActiveRoutingPath(0x1210, 0x1212));
        // New path is a sibling
        assertFalse(HdmiUtils.isInActiveRoutingPath(0x1100, 0x1200));
        // In a completely different path
        assertFalse(HdmiUtils.isInActiveRoutingPath(0x1000, 0x2000));
    }

    @Test
    public void pathRelationship_unknown() {
        assertThat(HdmiUtils.pathRelationship(0x1234, Constants.INVALID_PHYSICAL_ADDRESS))
                .isEqualTo(Constants.PATH_RELATIONSHIP_UNKNOWN);
        assertThat(HdmiUtils.pathRelationship(Constants.INVALID_PHYSICAL_ADDRESS, 0x1234))
                .isEqualTo(Constants.PATH_RELATIONSHIP_UNKNOWN);
        assertThat(HdmiUtils.pathRelationship(Constants.INVALID_PHYSICAL_ADDRESS,
                Constants.INVALID_PHYSICAL_ADDRESS))
                .isEqualTo(Constants.PATH_RELATIONSHIP_UNKNOWN);
    }

    @Test
    public void pathRelationship_differentBranch() {
        assertThat(HdmiUtils.pathRelationship(0x1200, 0x2000))
                .isEqualTo(Constants.PATH_RELATIONSHIP_DIFFERENT_BRANCH);
        assertThat(HdmiUtils.pathRelationship(0x1234, 0x1224))
                .isEqualTo(Constants.PATH_RELATIONSHIP_DIFFERENT_BRANCH);
        assertThat(HdmiUtils.pathRelationship(0x1234, 0x1134))
                .isEqualTo(Constants.PATH_RELATIONSHIP_DIFFERENT_BRANCH);
        assertThat(HdmiUtils.pathRelationship(0x1234, 0x2234))
                .isEqualTo(Constants.PATH_RELATIONSHIP_DIFFERENT_BRANCH);
    }

    @Test
    public void pathRelationship_ancestor() {
        assertThat(HdmiUtils.pathRelationship(0x0000, 0x1230))
                .isEqualTo(Constants.PATH_RELATIONSHIP_ANCESTOR);
        assertThat(HdmiUtils.pathRelationship(0x1000, 0x1230))
                .isEqualTo(Constants.PATH_RELATIONSHIP_ANCESTOR);
        assertThat(HdmiUtils.pathRelationship(0x1200, 0x1230))
                .isEqualTo(Constants.PATH_RELATIONSHIP_ANCESTOR);
    }

    @Test
    public void pathRelationship_descendant() {
        assertThat(HdmiUtils.pathRelationship(0x1230, 0x0000))
                .isEqualTo(Constants.PATH_RELATIONSHIP_DESCENDANT);
        assertThat(HdmiUtils.pathRelationship(0x1230, 0x1000))
                .isEqualTo(Constants.PATH_RELATIONSHIP_DESCENDANT);
        assertThat(HdmiUtils.pathRelationship(0x1230, 0x1200))
                .isEqualTo(Constants.PATH_RELATIONSHIP_DESCENDANT);
    }

    @Test
    public void pathRelationship_sibling() {
        assertThat(HdmiUtils.pathRelationship(0x1000, 0x2000))
                .isEqualTo(Constants.PATH_RELATIONSHIP_SIBLING);
        assertThat(HdmiUtils.pathRelationship(0x1200, 0x1100))
                .isEqualTo(Constants.PATH_RELATIONSHIP_SIBLING);
        assertThat(HdmiUtils.pathRelationship(0x1230, 0x1220))
                .isEqualTo(Constants.PATH_RELATIONSHIP_SIBLING);
        assertThat(HdmiUtils.pathRelationship(0x1234, 0x1233))
                .isEqualTo(Constants.PATH_RELATIONSHIP_SIBLING);
    }

    @Test
    public void pathRelationship_same() {
        assertThat(HdmiUtils.pathRelationship(0x0000, 0x0000))
                .isEqualTo(Constants.PATH_RELATIONSHIP_SAME);
        assertThat(HdmiUtils.pathRelationship(0x1234, 0x1234))
                .isEqualTo(Constants.PATH_RELATIONSHIP_SAME);
    }

    @Test
    public void getTypeFromAddress() {
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_TV)).containsExactly(
                HdmiDeviceInfo.DEVICE_TV);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_RECORDER_1)).containsExactly(
                HdmiDeviceInfo.DEVICE_RECORDER);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_RECORDER_2)).containsExactly(
                HdmiDeviceInfo.DEVICE_RECORDER);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_TUNER_1)).containsExactly(
                HdmiDeviceInfo.DEVICE_TUNER);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_PLAYBACK_1)).containsExactly(
                HdmiDeviceInfo.DEVICE_PLAYBACK);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_AUDIO_SYSTEM)).containsExactly(
                HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_TUNER_2)).containsExactly(
                HdmiDeviceInfo.DEVICE_TUNER);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_TUNER_3)).containsExactly(
                HdmiDeviceInfo.DEVICE_TUNER);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_PLAYBACK_2)).containsExactly(
                HdmiDeviceInfo.DEVICE_PLAYBACK);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_RECORDER_3)).containsExactly(
                HdmiDeviceInfo.DEVICE_RECORDER);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_TUNER_4)).containsExactly(
                HdmiDeviceInfo.DEVICE_TUNER);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_PLAYBACK_3)).containsExactly(
                HdmiDeviceInfo.DEVICE_PLAYBACK);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_BACKUP_1)).containsExactly(
                HdmiDeviceInfo.DEVICE_PLAYBACK, HdmiDeviceInfo.DEVICE_RECORDER,
                HdmiDeviceInfo.DEVICE_TUNER, HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_BACKUP_2)).containsExactly(
                HdmiDeviceInfo.DEVICE_PLAYBACK, HdmiDeviceInfo.DEVICE_RECORDER,
                HdmiDeviceInfo.DEVICE_TUNER, HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR);
        assertThat(HdmiUtils.getTypeFromAddress(Constants.ADDR_SPECIFIC_USE)).containsExactly(
                HdmiDeviceInfo.DEVICE_TV);
    }

    @Test
    public void isEligibleAddressForDevice() {
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_TV)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_TV)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_TV)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_TV)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_TV)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_TV)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_TV)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_TV)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_RECORDER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_RECORDER_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_RECORDER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_RECORDER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_RECORDER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_RECORDER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_RECORDER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_RECORDER_1)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_RECORDER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_RECORDER_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_RECORDER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_RECORDER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_RECORDER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_RECORDER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_RECORDER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_RECORDER_2)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_TUNER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_TUNER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_TUNER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_TUNER_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_TUNER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_TUNER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_TUNER_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_TUNER_1)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_PLAYBACK_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_PLAYBACK_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_PLAYBACK_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_PLAYBACK_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_PLAYBACK_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_PLAYBACK_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_PLAYBACK_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_PLAYBACK_1)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_AUDIO_SYSTEM)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_AUDIO_SYSTEM)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_AUDIO_SYSTEM)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_AUDIO_SYSTEM)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_AUDIO_SYSTEM)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_AUDIO_SYSTEM)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_AUDIO_SYSTEM)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_AUDIO_SYSTEM)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_TUNER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_TUNER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_TUNER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_TUNER_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_TUNER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_TUNER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_TUNER_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_TUNER_2)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_TUNER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_TUNER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_TUNER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_TUNER_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_TUNER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_TUNER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_TUNER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_TUNER_3)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_PLAYBACK_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_PLAYBACK_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_PLAYBACK_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_PLAYBACK_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_PLAYBACK_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_PLAYBACK_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_PLAYBACK_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_PLAYBACK_2)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_RECORDER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_RECORDER_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_RECORDER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_RECORDER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_RECORDER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_RECORDER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_RECORDER_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_RECORDER_3)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_TUNER_4)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_TUNER_4)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_TUNER_4)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_TUNER_4)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_TUNER_4)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_TUNER_4)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_TUNER_4)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_TUNER_4)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_PLAYBACK_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_PLAYBACK_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_PLAYBACK_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_PLAYBACK_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_PLAYBACK_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_PLAYBACK_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_PLAYBACK_3)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_PLAYBACK_3)).isFalse();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_BACKUP_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_BACKUP_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_BACKUP_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_BACKUP_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_BACKUP_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_BACKUP_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_BACKUP_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_BACKUP_1)).isTrue();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_BACKUP_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_BACKUP_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_BACKUP_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_BACKUP_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_BACKUP_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_BACKUP_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_BACKUP_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_BACKUP_2)).isTrue();

        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TV,
                Constants.ADDR_SPECIFIC_USE)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RECORDER,
                Constants.ADDR_SPECIFIC_USE)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_RESERVED,
                Constants.ADDR_SPECIFIC_USE)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_TUNER,
                Constants.ADDR_SPECIFIC_USE)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PLAYBACK,
                Constants.ADDR_SPECIFIC_USE)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                Constants.ADDR_SPECIFIC_USE)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
                Constants.ADDR_SPECIFIC_USE)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR,
                Constants.ADDR_SPECIFIC_USE)).isFalse();
    }

    @Test
    public void isEligibleAddressForCecVersion_1_4() {
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_TV)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_RECORDER_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_RECORDER_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_TUNER_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_PLAYBACK_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_AUDIO_SYSTEM)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_TUNER_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_TUNER_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_PLAYBACK_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_RECORDER_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_TUNER_4)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_PLAYBACK_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_BACKUP_1)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_BACKUP_2)).isFalse();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Constants.ADDR_SPECIFIC_USE)).isTrue();
    }

    @Test
    public void isEligibleAddressForCecVersion_2_0() {
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_TV)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_RECORDER_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_RECORDER_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_TUNER_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_PLAYBACK_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_AUDIO_SYSTEM)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_TUNER_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_TUNER_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_PLAYBACK_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_RECORDER_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_TUNER_4)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_PLAYBACK_3)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_BACKUP_1)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_BACKUP_2)).isTrue();
        assertThat(HdmiUtils.isEligibleAddressForCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Constants.ADDR_SPECIFIC_USE)).isTrue();
    }

    @Test
    public void testBuildMessage_validation() {
        assertThrows(IllegalArgumentException.class, () -> HdmiUtils.buildMessage("04"));
        assertThrows(IllegalArgumentException.class, () -> HdmiUtils.buildMessage("041"));
        assertThrows(IllegalArgumentException.class, () -> HdmiUtils.buildMessage("041:00"));
        assertThrows(IllegalArgumentException.class, () -> HdmiUtils.buildMessage("04:000"));
        assertThrows(IllegalArgumentException.class, () -> HdmiUtils.buildMessage("04:00:000"));
        assertThrows(IllegalArgumentException.class, () -> HdmiUtils.buildMessage("04:00:00:000"));

        assertThrows(NumberFormatException.class, () -> HdmiUtils.buildMessage("G0:00"));
        assertThrows(NumberFormatException.class, () -> HdmiUtils.buildMessage("0G:00"));
        assertThrows(NumberFormatException.class, () -> HdmiUtils.buildMessage("04:G0"));
        assertThrows(NumberFormatException.class, () -> HdmiUtils.buildMessage("04:00:G0"));
        assertThrows(NumberFormatException.class, () -> HdmiUtils.buildMessage("04:00:0G"));
    }

    @Test
    public void testBuildMessage_source() {
        assertThat(HdmiUtils.buildMessage("04:00").getSource()).isEqualTo(Constants.ADDR_TV);
        assertThat(HdmiUtils.buildMessage("40:00").getSource()).isEqualTo(
                Constants.ADDR_PLAYBACK_1);
    }

    @Test
    public void testBuildMessage_destination() {
        assertThat(HdmiUtils.buildMessage("04:00").getDestination()).isEqualTo(
                Constants.ADDR_PLAYBACK_1);
        assertThat(HdmiUtils.buildMessage("40:00").getDestination()).isEqualTo(Constants.ADDR_TV);
    }

    @Test
    public void testBuildMessage_opcode() {
        assertThat(HdmiUtils.buildMessage("04:00").getOpcode()).isEqualTo(
                Constants.MESSAGE_FEATURE_ABORT);
        assertThat(HdmiUtils.buildMessage("04:36").getOpcode()).isEqualTo(
                Constants.MESSAGE_STANDBY);
        assertThat(HdmiUtils.buildMessage("04:FF").getOpcode()).isEqualTo(
                Integer.parseInt("FF", 16));
    }

    @Test
    public void testBuildMessage_params() {
        assertThat(HdmiUtils.buildMessage("04:00:00").getParams()).isEqualTo(new byte[]{0x00});
        assertThat(HdmiUtils.buildMessage("40:32:65:6E:67").getParams()).isEqualTo(
                new byte[]{0x65, 0x6E, 0x67});
    }

    @Test
    public void testVerifyAddressType() {
        assertTrue(HdmiUtils.verifyAddressType(Constants.ADDR_TV,
                HdmiDeviceInfo.DEVICE_TV));
        assertTrue(HdmiUtils.verifyAddressType(Constants.ADDR_AUDIO_SYSTEM,
                HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM));
        assertTrue(HdmiUtils.verifyAddressType(Constants.ADDR_PLAYBACK_1,
                HdmiDeviceInfo.DEVICE_PLAYBACK));
        assertFalse(HdmiUtils.verifyAddressType(Constants.ADDR_SPECIFIC_USE,
                HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM));
        assertFalse(HdmiUtils.verifyAddressType(Constants.ADDR_PLAYBACK_2,
                HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR));
    }
}
