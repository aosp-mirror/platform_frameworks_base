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
import static org.junit.Assert.assertNull;

import android.view.Display;
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
        createDefaultDisplay(testLayout, 123456L);
        createNonDefaultDisplay(testLayout, 78910L, /* enabled= */ false, /* group= */ null);
        createNonDefaultDisplay(testLayout, 98765L, /* enabled= */ true, /* group= */ "group1");
        createNonDefaultDisplay(testLayout, 786L, /* enabled= */ false, /* group= */ "group2");

        assertEquals(testLayout, configLayout);
    }

    @Test
    public void testSwitchedState() {
        Layout configLayout = mDeviceStateToLayoutMap.get(1);

        Layout testLayout = new Layout();
        createDefaultDisplay(testLayout, 78910L);
        createNonDefaultDisplay(testLayout, 123456L, /* enabled= */ false, /* group= */ null);

        assertEquals(testLayout, configLayout);
    }

    @Test
    public void testThermalBrightnessThrottlingMapId() {
        Layout configLayout = mDeviceStateToLayoutMap.get(2);

        assertEquals("concurrent1", configLayout.getAt(0).getThermalBrightnessThrottlingMapId());
        assertEquals("concurrent2", configLayout.getAt(1).getThermalBrightnessThrottlingMapId());
    }

    @Test
    public void testRearDisplayLayout() {
        Layout configLayout = mDeviceStateToLayoutMap.get(2);

        assertEquals(Layout.Display.POSITION_FRONT, configLayout.getAt(0).getPosition());
        assertEquals(Layout.Display.POSITION_REAR, configLayout.getAt(1).getPosition());
    }

    @Test
    public void testRefreshRateZoneId() {
        Layout configLayout = mDeviceStateToLayoutMap.get(3);

        assertEquals("test1", configLayout.getAt(0).getRefreshRateZoneId());
        assertNull(configLayout.getAt(1).getRefreshRateZoneId());
    }

    @Test
    public void testThermalRefreshRateThrottlingMapId() {
        Layout configLayout = mDeviceStateToLayoutMap.get(4);

        assertEquals("test2", configLayout.getAt(0).getRefreshRateThermalThrottlingMapId());
        assertNull(configLayout.getAt(1).getRefreshRateThermalThrottlingMapId());
    }

    @Test
    public void testWholeStateConfig() {
        Layout configLayout = mDeviceStateToLayoutMap.get(99);

        Layout testLayout = new Layout();
        testLayout.createDisplayLocked(DisplayAddress.fromPhysicalDisplayId(345L),
                /* isDefault= */ true, /* isEnabled= */ true, /* displayGroupName= */ null,
                mDisplayIdProducerMock,  Layout.Display.POSITION_FRONT, Display.DEFAULT_DISPLAY,
                /* brightnessThrottlingMapId= */ "brightness1",
                /* refreshRateZoneId= */ "zone1",
                /* refreshRateThermalThrottlingMapId= */ "rr1");
        testLayout.createDisplayLocked(DisplayAddress.fromPhysicalDisplayId(678L),
                /* isDefault= */ false, /* isEnabled= */ false, /* displayGroupName= */ "group1",
                mDisplayIdProducerMock, Layout.Display.POSITION_REAR, Display.DEFAULT_DISPLAY,
                /* brightnessThrottlingMapId= */ "brightness2",
                /* refreshRateZoneId= */ "zone2",
                /* refreshRateThermalThrottlingMapId= */ "rr2");

        assertEquals(testLayout, configLayout);
    }

    ////////////////////
    // Helper Methods //
    ////////////////////

    private void createDefaultDisplay(Layout layout, long id) {
        layout.createDefaultDisplayLocked(DisplayAddress.fromPhysicalDisplayId(id),
                mDisplayIdProducerMock);
    }

    private void createNonDefaultDisplay(Layout layout, long id, boolean enabled, String group) {
        layout.createDisplayLocked(DisplayAddress.fromPhysicalDisplayId(id), /* isDefault= */ false,
                enabled, group, mDisplayIdProducerMock, Layout.Display.POSITION_UNKNOWN,
                Display.DEFAULT_DISPLAY, /* brightnessThrottlingMapId= */ null,
                /* refreshRateZoneId= */ null,
                /* refreshRateThermalThrottlingMapId= */ null);
    }

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
                +      "<display enabled=\"true\" displayGroup=\"group1\">\n"
                +        "<address>98765</address>\n"
                +      "</display>\n"
                +      "<display enabled=\"false\" displayGroup=\"group2\">\n"
                +        "<address>786</address>\n"
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
                +        "<brightnessThrottlingMapId>concurrent1</brightnessThrottlingMapId>\n"
                +      "</display>\n"
                +      "<display enabled=\"true\">\n"
                +        "<address>678</address>\n"
                +        "<position>rear</position>\n"
                +        "<brightnessThrottlingMapId>concurrent2</brightnessThrottlingMapId>\n"
                +      "</display>\n"
                +    "</layout>\n"

                +    "<layout>\n"
                +      "<state>3</state> \n"
                +      "<display enabled=\"true\" defaultDisplay=\"true\" "
                +                                               "refreshRateZoneId=\"test1\">\n"
                +        "<address>345</address>\n"
                +      "</display>\n"
                +      "<display enabled=\"true\">\n"
                +        "<address>678</address>\n"
                +      "</display>\n"
                +    "</layout>\n"

                +    "<layout>\n"
                +      "<state>4</state> \n"
                +      "<display enabled=\"true\" defaultDisplay=\"true\" >\n"
                +        "<address>345</address>\n"
                +        "<refreshRateThermalThrottlingMapId>"
                +          "test2"
                +        "</refreshRateThermalThrottlingMapId>"
                +      "</display>\n"
                +      "<display enabled=\"true\">\n"
                +        "<address>678</address>\n"
                +      "</display>\n"
                +    "</layout>\n"
                +    "<layout>\n"
                +      "<state>99</state> \n"
                +      "<display enabled=\"true\" defaultDisplay=\"true\" "
                +                                          "refreshRateZoneId=\"zone1\">\n"
                +         "<address>345</address>\n"
                +         "<position>front</position>\n"
                +         "<brightnessThrottlingMapId>brightness1</brightnessThrottlingMapId>\n"
                +         "<refreshRateThermalThrottlingMapId>"
                +           "rr1"
                +         "</refreshRateThermalThrottlingMapId>"
                +       "</display>\n"
                +       "<display enabled=\"false\" displayGroup=\"group1\" "
                +                                           "refreshRateZoneId=\"zone2\">\n"
                +         "<address>678</address>\n"
                +         "<position>rear</position>\n"
                +         "<brightnessThrottlingMapId>brightness2</brightnessThrottlingMapId>\n"
                +         "<refreshRateThermalThrottlingMapId>"
                +           "rr2"
                +         "</refreshRateThermalThrottlingMapId>"
                +       "</display>\n"
                +     "</layout>\n"
                +   "</layouts>\n";
    }
}
