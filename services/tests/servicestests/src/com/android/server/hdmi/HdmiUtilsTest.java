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

import android.util.Slog;

import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiUtils.CodecSad;
import com.android.server.hdmi.HdmiUtils.DeviceConfig;

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
}
