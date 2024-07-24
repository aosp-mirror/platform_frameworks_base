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
import static org.junit.Assert.assertThrows;

import android.view.DisplayAddress;

import androidx.test.filters.SmallTest;

import com.android.server.display.feature.DisplayManagerFlags;
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
    @Mock DisplayManagerFlags mMockFlags;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);

        Mockito.when(mDisplayIdProducerMock.getId(false)).thenReturn(1);

        setupDeviceStateToLayoutMap(getContent());
    }

    //////////////////
    // Test Methods //
    //////////////////

    @Test
    public void testInitialState() {
        Layout configLayout = mDeviceStateToLayoutMap.get(0);

        Layout testLayout = new Layout();
        createDefaultDisplay(testLayout, 123456L);
        createNonDefaultDisplay(testLayout, 78910L, /* enabled= */ false, /* group= */ null,
                /* leadDisplayAddress= */ null);
        createNonDefaultDisplay(testLayout, 98765L, /* enabled= */ true, /* group= */ "group1",
                /* leadDisplayAddress= */ null);
        createNonDefaultDisplay(testLayout, 786L, /* enabled= */ false, /* group= */ "group2",
                /* leadDisplayAddress= */ null);
        createNonDefaultDisplay(testLayout, 1092L, /* enabled= */ true, /* group= */ null,
                DisplayAddress.fromPhysicalDisplayId(78910L));
        testLayout.postProcessLocked();

        assertEquals(testLayout, configLayout);
    }

    @Test
    public void testSwitchedState() {
        Layout configLayout = mDeviceStateToLayoutMap.get(1);

        Layout testLayout = new Layout();
        createDefaultDisplay(testLayout, 78910L);
        createNonDefaultDisplay(testLayout, 123456L, /* enabled= */ false, /* group= */ null,
                /* leadDisplayAddress= */ null);
        testLayout.postProcessLocked();

        assertEquals(testLayout, configLayout);
    }

    @Test
    public void testThermalBrightnessThrottlingMapId() {
        Layout configLayout = mDeviceStateToLayoutMap.get(2);

        assertEquals("concurrent1", configLayout.getAt(0).getThermalBrightnessThrottlingMapId());
        assertEquals("concurrent2", configLayout.getAt(1).getThermalBrightnessThrottlingMapId());
    }

    @Test
    public void testPowerThrottlingMapId() {
        Layout configLayout = mDeviceStateToLayoutMap.get(5);

        assertEquals("concurrent1", configLayout.getAt(0).getPowerThrottlingMapId());
        assertEquals("concurrent2", configLayout.getAt(1).getPowerThrottlingMapId());
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
                mDisplayIdProducerMock,  Layout.Display.POSITION_FRONT,
                /* leadDisplayAddress= */ null, /* brightnessThrottlingMapId= */ "brightness1",
                /* refreshRateZoneId= */ "zone1",
                /* refreshRateThermalThrottlingMapId= */ "rr1",
                /* powerThrottlingMapId= */ "power1");
        testLayout.createDisplayLocked(DisplayAddress.fromPhysicalDisplayId(678L),
                /* isDefault= */ false, /* isEnabled= */ false, /* displayGroupName= */ "group1",
                mDisplayIdProducerMock, Layout.Display.POSITION_REAR,
                /* leadDisplayAddress= */ null, /* brightnessThrottlingMapId= */ "brightness2",
                /* refreshRateZoneId= */ "zone2",
                /* refreshRateThermalThrottlingMapId= */ "rr2",
                /* powerThrottlingMapId= */ "power2");
        testLayout.postProcessLocked();

        assertEquals(testLayout, configLayout);
    }

    @Test
    public void testLeadDisplayAddress() {
        Layout layout = new Layout();
        createNonDefaultDisplay(layout, 111L, /* enabled= */ true, /* group= */ null,
                /* leadDisplayAddress= */ null);
        createNonDefaultDisplay(layout, 222L, /* enabled= */ true, /* group= */ null,
                DisplayAddress.fromPhysicalDisplayId(111L));

        layout.postProcessLocked();

        com.android.server.display.layout.Layout.Display display111 =
                layout.getByAddress(DisplayAddress.fromPhysicalDisplayId(111));
        com.android.server.display.layout.Layout.Display display222 =
                layout.getByAddress(DisplayAddress.fromPhysicalDisplayId(222));
        assertEquals(display111.getLeadDisplayId(), layout.NO_LEAD_DISPLAY);
        assertEquals(display222.getLeadDisplayId(), display111.getLogicalDisplayId());
    }

    @Test
    public void testLeadDisplayAddress_defaultDisplay() {
        Layout layout = new Layout();
        createDefaultDisplay(layout, 123456L);

        layout.postProcessLocked();

        com.android.server.display.layout.Layout.Display display =
                layout.getByAddress(DisplayAddress.fromPhysicalDisplayId(123456));
        assertEquals(display.getLeadDisplayId(), layout.NO_LEAD_DISPLAY);
    }

    @Test
    public void testLeadDisplayAddress_noLeadDisplay() {
        Layout layout = new Layout();
        createNonDefaultDisplay(layout, 222L, /* enabled= */ true, /* group= */ null,
                /* leadDisplayAddress= */ null);

        layout.postProcessLocked();

        com.android.server.display.layout.Layout.Display display =
                layout.getByAddress(DisplayAddress.fromPhysicalDisplayId(222));
        assertEquals(display.getLeadDisplayId(), layout.NO_LEAD_DISPLAY);
    }

    @Test
    public void testLeadDisplayAddress_selfLeadDisplayForNonDefaultDisplay() {
        Layout layout = new Layout();

        assertThrows("Expected Layout to throw IllegalArgumentException when the display points out"
                + " itself as a lead display",
                IllegalArgumentException.class,
                () -> layout.createDisplayLocked(DisplayAddress.fromPhysicalDisplayId(123L),
                    /* isDefault= */ true, /* isEnabled= */ true, /* displayGroupName= */ null,
                    mDisplayIdProducerMock,  Layout.Display.POSITION_FRONT,
                    DisplayAddress.fromPhysicalDisplayId(123L),
                    /* brightnessThrottlingMapId= */ null, /* refreshRateZoneId= */ null,
                    /* refreshRateThermalThrottlingMapId= */ null,
                    /* powerThrottlingMapId= */ null));
    }

    @Test
    public void testLeadDisplayAddress_wrongLeadDisplayForDefaultDisplay() {
        Layout layout = new Layout();

        assertThrows("Expected Layout to throw IllegalArgumentException when the default display "
                + "has a lead display",
                IllegalArgumentException.class,
                () -> layout.createDisplayLocked(DisplayAddress.fromPhysicalDisplayId(123L),
                    /* isDefault= */ true, /* isEnabled= */ true, /* displayGroupName= */ null,
                    mDisplayIdProducerMock,  Layout.Display.POSITION_FRONT,
                    DisplayAddress.fromPhysicalDisplayId(987L),
                    /* brightnessThrottlingMapId= */ null, /* refreshRateZoneId= */ null,
                    /* refreshRateThermalThrottlingMapId= */ null,
                    /* powerThrottlingMapId= */ null));
    }

    @Test
    public void testLeadDisplayAddress_notExistingLeadDisplayForNonDefaultDisplay() {
        Layout layout = new Layout();
        createNonDefaultDisplay(layout, 222L, /* enabled= */ true, /* group= */ null,
                DisplayAddress.fromPhysicalDisplayId(111L));

        assertThrows("Expected Layout to throw IllegalArgumentException when a lead display doesn't"
                + " exist", IllegalArgumentException.class, () -> layout.postProcessLocked());
    }

    @Test
    public void testLeadDisplayAddress_leadDisplayInDifferentDisplayGroup() {
        Layout layout = new Layout();
        createNonDefaultDisplay(layout, 111, /* enabled= */ true, /* group= */ "group1",
                /* leadDisplayAddress= */ null);
        createNonDefaultDisplay(layout, 222L, /* enabled= */ true, /* group= */ "group2",
                DisplayAddress.fromPhysicalDisplayId(111L));

        assertThrows("Expected Layout to throw IllegalArgumentException when pointing to a lead "
                + "display in the different group",
                IllegalArgumentException.class, () -> layout.postProcessLocked());
    }

    @Test
    public void testLeadDisplayAddress_cyclicLeadDisplay() {
        Layout layout = new Layout();
        createNonDefaultDisplay(layout, 111, /* enabled= */ true, /* group= */ null,
                DisplayAddress.fromPhysicalDisplayId(222L));
        createNonDefaultDisplay(layout, 222L, /* enabled= */ true, /* group= */ null,
                DisplayAddress.fromPhysicalDisplayId(333L));
        createNonDefaultDisplay(layout, 333L, /* enabled= */ true, /* group= */ null,
                DisplayAddress.fromPhysicalDisplayId(222L));

        assertThrows("Expected Layout to throw IllegalArgumentException when pointing to a lead "
                + "display in the different group",
                IllegalArgumentException.class, () -> layout.postProcessLocked());
    }

    @Test
    public void testPortInLayout_disabledFlag() {
        Mockito.when(mMockFlags.isPortInDisplayLayoutEnabled()).thenReturn(false);
        assertThrows("Expected IllegalArgumentException when using <port>",
                IllegalArgumentException.class,
                () -> setupDeviceStateToLayoutMap(getPortContent()));
    }

    @Test
    public void testPortInLayout_readLayout() throws Exception {
        Mockito.when(mMockFlags.isPortInDisplayLayoutEnabled()).thenReturn(true);
        setupDeviceStateToLayoutMap(getPortContent());

        Layout configLayout = mDeviceStateToLayoutMap.get(0);

        Layout testLayout = new Layout();
        testLayout.createDisplayLocked(DisplayAddress.fromPortAndModel(123, null),
                /* isDefault= */ true, /* isEnabled= */ true, /* displayGroupName= */ null,
                mDisplayIdProducerMock,  Layout.Display.POSITION_UNKNOWN,
                /* leadDisplayAddress= */ null, /* brightnessThrottlingMapId= */ null,
                /* refreshRateZoneId= */ null,
                /* refreshRateThermalThrottlingMapId= */ null,
                /* powerThrottlingMapId= */ null);
        testLayout.createDisplayLocked(DisplayAddress.fromPhysicalDisplayId(78910L),
                /* isDefault= */ false, /* isEnabled= */ false, /* displayGroupName= */ null,
                mDisplayIdProducerMock, Layout.Display.POSITION_UNKNOWN,
                /* leadDisplayAddress= */ null, /* brightnessThrottlingMapId= */ null,
                /* refreshRateZoneId= */ null,
                /* refreshRateThermalThrottlingMapId= */ null,
                /* powerThrottlingMapId= */ null);
        testLayout.postProcessLocked();

        assertEquals(testLayout, configLayout);
    }

    ////////////////////
    // Helper Methods //
    ////////////////////

    private void createDefaultDisplay(Layout layout, long id) {
        layout.createDefaultDisplayLocked(DisplayAddress.fromPhysicalDisplayId(id),
                mDisplayIdProducerMock);
    }

    private void createNonDefaultDisplay(Layout layout, long id, boolean enabled, String group,
            DisplayAddress leadDisplayAddress) {
        layout.createDisplayLocked(DisplayAddress.fromPhysicalDisplayId(id), /* isDefault= */ false,
                enabled, group, mDisplayIdProducerMock, Layout.Display.POSITION_UNKNOWN,
                leadDisplayAddress, /* brightnessThrottlingMapId= */ null,
                /* refreshRateZoneId= */ null,
                /* refreshRateThermalThrottlingMapId= */ null,
                /* powerThrottlingMapId= */ null);
    }

    private void setupDeviceStateToLayoutMap(String content) throws IOException {
        Path tempFile = Files.createTempFile("device_state_layout_map", ".tmp");
        Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
        mDeviceStateToLayoutMap = new DeviceStateToLayoutMap(mDisplayIdProducerMock, mMockFlags,
                tempFile.toFile());
    }

    private String getPortContent() {
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                +  "<layouts>\n"
                +    "<layout>\n"
                +      "<state>0</state> \n"
                +      "<display enabled=\"true\" defaultDisplay=\"true\">\n"
                +        "<port>123</port>\n"
                +      "</display>\n"
                +      "<display enabled=\"false\">\n"
                +        "<address>78910</address>\n"
                +      "</display>\n"
                +    "</layout>\n"
                +  "</layouts>\n";
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
                +      "<display enabled=\"true\">\n"
                +        "<address>1092</address>\n"
                +        "<leadDisplayAddress>78910</leadDisplayAddress>\n"
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
                +      "<state>5</state> \n"
                +      "<display enabled=\"true\" defaultDisplay=\"true\">\n"
                +        "<address>345</address>\n"
                +        "<position>front</position>\n"
                +        "<powerThrottlingMapId>concurrent1</powerThrottlingMapId>\n"
                +      "</display>\n"
                +      "<display enabled=\"true\">\n"
                +        "<address>678</address>\n"
                +        "<position>rear</position>\n"
                +        "<powerThrottlingMapId>concurrent2</powerThrottlingMapId>\n"
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
                +         "<powerThrottlingMapId>power1</powerThrottlingMapId>\n"
                +       "</display>\n"
                +       "<display enabled=\"false\" displayGroup=\"group1\" "
                +                                           "refreshRateZoneId=\"zone2\">\n"
                +         "<address>678</address>\n"
                +         "<position>rear</position>\n"
                +         "<brightnessThrottlingMapId>brightness2</brightnessThrottlingMapId>\n"
                +         "<refreshRateThermalThrottlingMapId>"
                +           "rr2"
                +         "</refreshRateThermalThrottlingMapId>"
                +         "<powerThrottlingMapId>power2</powerThrottlingMapId>\n"
                +       "</display>\n"
                +     "</layout>\n"
                +   "</layouts>\n";
    }
}
