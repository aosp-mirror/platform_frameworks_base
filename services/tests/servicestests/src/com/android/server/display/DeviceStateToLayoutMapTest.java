/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display;


import static org.junit.Assert.assertEquals;

import android.view.DisplayAddress;

import androidx.test.filters.SmallTest;

import com.android.server.display.layout.DisplayIdProducer;
import com.android.server.display.layout.Layout;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


@SmallTest
public class DeviceStateToLayoutMapTest {
    private DeviceStateToLayoutMap mDeviceStateToLayoutMap;

    @Mock DisplayIdProducer mDisplayIdProducerMock;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);

        Mockito.when(mDisplayIdProducerMock.getId(false)).thenReturn(1);

        setupDeviceStateToLayoutMap();
    }

    //////////////////
    // Test Methods //
    //////////////////

    @Test
    public void testInitialState() {
        Layout configLayout = mDeviceStateToLayoutMap.get(0);

        Layout testLayout = new Layout();
        testLayout.createDisplayLocked(
                DisplayAddress.fromPhysicalDisplayId(123456L), /* isDefault= */ true,
                /* isEnabled= */ true, mDisplayIdProducerMock,
                /* brightnessThrottlingMapId= */ null);
        testLayout.createDisplayLocked(
                DisplayAddress.fromPhysicalDisplayId(78910L), /* isDefault= */ false,
                /* isEnabled= */ false, mDisplayIdProducerMock,
                /* brightnessThrottlingMapId= */ null);
        assertEquals(testLayout, configLayout);
    }

    @Test
    public void testSwitchedState() {
        Layout configLayout = mDeviceStateToLayoutMap.get(1);

        Layout testLayout = new Layout();
        testLayout.createDisplayLocked(
                DisplayAddress.fromPhysicalDisplayId(78910L), /* isDefault= */ true,
                /* isEnabled= */ true, mDisplayIdProducerMock,
                /* brightnessThrottlingMapId= */ null);
        testLayout.createDisplayLocked(
                DisplayAddress.fromPhysicalDisplayId(123456L), /* isDefault= */ false,
                /* isEnabled= */ false, mDisplayIdProducerMock,
                /* brightnessThrottlingMapId= */ null);

        assertEquals(testLayout, configLayout);
    }

    @Test
    public void testConcurrentState() {
        Layout configLayout = mDeviceStateToLayoutMap.get(2);

        Layout testLayout = new Layout();

        Layout.Display display1 = testLayout.createDisplayLocked(
                DisplayAddress.fromPhysicalDisplayId(345L), /* isDefault= */ true,
                /* isEnabled= */ true, mDisplayIdProducerMock,
                /* brightnessThrottlingMapId= */ "concurrent");
        display1.setPosition(Layout.Display.POSITION_FRONT);

        Layout.Display display2 = testLayout.createDisplayLocked(
                DisplayAddress.fromPhysicalDisplayId(678L), /* isDefault= */ false,
                /* isEnabled= */ true, mDisplayIdProducerMock,
                /* brightnessThrottlingMapId= */ "concurrent");
        display2.setPosition(Layout.Display.POSITION_REAR);

        assertEquals(testLayout, configLayout);
    }

    @Test
    public void testRearDisplayLayout() {
        Layout configLayout = mDeviceStateToLayoutMap.get(2);

        assertEquals(Layout.Display.POSITION_FRONT, configLayout.getAt(0).getPosition());
        assertEquals(Layout.Display.POSITION_REAR, configLayout.getAt(1).getPosition());
    }

    ////////////////////
    // Helper Methods //
    ////////////////////

    private void setupDeviceStateToLayoutMap() throws IOException {
        Path tempFile = Files.createTempFile("device_state_layout_map", ".tmp");
        Files.write(tempFile, getContent().getBytes(StandardCharsets.UTF_8));
        mDeviceStateToLayoutMap = new DeviceStateToLayoutMap(mDisplayIdProducerMock,
                tempFile.toFile());
    }

    private String getContent() {
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                +  "<layouts>\n"
                +    "<layout>\n"
                +      "<state>0</state> \n"
                +      "<display enabled=\"true\" defaultDisplay=\"true\">\n"
                +        "<address>123456</address>\n"
                +      "</display>\n"
                +      "<display enabled=\"false\">\n"
                +        "<address>78910</address>\n"
                +      "</display>\n"
                +    "</layout>\n"

                +    "<layout>\n"
                +      "<state>1</state> \n"
                +      "<display enabled=\"true\" defaultDisplay=\"true\">\n"
                +        "<address>78910</address>\n"
                +      "</display>\n"
                +      "<display enabled=\"false\">\n"
                +        "<address>123456</address>\n"
                +      "</display>\n"
                +    "</layout>\n"

                +    "<layout>\n"
                +      "<state>2</state> \n"
                +      "<display enabled=\"true\" defaultDisplay=\"true\">\n"
                +        "<address>345</address>\n"
                +        "<position>front</position>\n"
                +        "<brightnessThrottlingMapId>concurrent</brightnessThrottlingMapId>\n"
                +      "</display>\n"
                +      "<display enabled=\"true\">\n"
                +        "<address>678</address>\n"
                +        "<position>rear</position>\n"
                +        "<brightnessThrottlingMapId>concurrent</brightnessThrottlingMapId>\n"
                +      "</display>\n"
                +    "</layout>\n"
                +  "</layouts>\n";
    }
}

