/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.hardware.usb;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.usb.flags.Flags;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.util.XmlUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.StringReader;

/**
 * Unit tests for {@link android.hardware.usb.DeviceFilter}.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceFilterTest {

    private static final int VID = 10;
    private static final int PID = 11;
    private static final int CLASS = 12;
    private static final int SUBCLASS = 13;
    private static final int PROTOCOL = 14;
    private static final String MANUFACTURER = "Google";
    private static final String PRODUCT = "Test";
    private static final String SERIAL_NO = "4AL23";
    private static final String INTERFACE_NAME = "MTP";

    private MockitoSession mStaticMockSession;

    @Before
    public void setUp() throws Exception {
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(Flags.class)
                .strictness(Strictness.WARN)
                .startMocking();

        when(Flags.enableInterfaceNameDeviceFilter()).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testConstructorFromValues_interfaceNameIsInitialized() {
        DeviceFilter deviceFilter = new DeviceFilter(
                VID, PID, CLASS, SUBCLASS, PROTOCOL, MANUFACTURER,
                PRODUCT, SERIAL_NO, INTERFACE_NAME
        );

        verifyDeviceFilterConfigurationExceptInterfaceName(deviceFilter);
        assertThat(deviceFilter.mInterfaceName).isEqualTo(INTERFACE_NAME);
    }

    @Test
    public void testConstructorFromUsbDevice_interfaceNameIsNull() {
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);
        when(usbDevice.getVendorId()).thenReturn(VID);
        when(usbDevice.getProductId()).thenReturn(PID);
        when(usbDevice.getDeviceClass()).thenReturn(CLASS);
        when(usbDevice.getDeviceSubclass()).thenReturn(SUBCLASS);
        when(usbDevice.getDeviceProtocol()).thenReturn(PROTOCOL);
        when(usbDevice.getManufacturerName()).thenReturn(MANUFACTURER);
        when(usbDevice.getProductName()).thenReturn(PRODUCT);
        when(usbDevice.getSerialNumber()).thenReturn(SERIAL_NO);

        DeviceFilter deviceFilter = new DeviceFilter(usbDevice);

        verifyDeviceFilterConfigurationExceptInterfaceName(deviceFilter);
        assertThat(deviceFilter.mInterfaceName).isEqualTo(null);
    }

    @Test
    public void testConstructorFromDeviceFilter_interfaceNameIsInitialized() {
        DeviceFilter originalDeviceFilter = new DeviceFilter(
                VID, PID, CLASS, SUBCLASS, PROTOCOL, MANUFACTURER,
                PRODUCT, SERIAL_NO, INTERFACE_NAME
        );

        DeviceFilter deviceFilter = new DeviceFilter(originalDeviceFilter);

        verifyDeviceFilterConfigurationExceptInterfaceName(deviceFilter);
        assertThat(deviceFilter.mInterfaceName).isEqualTo(INTERFACE_NAME);
    }


    @Test
    public void testReadFromXml_interfaceNamePresent_propertyIsInitialized() throws Exception {
        DeviceFilter deviceFilter = getDeviceFilterFromXml("<usb-device interface-name=\"MTP\"/>");

        assertThat(deviceFilter.mInterfaceName).isEqualTo("MTP");
    }

    @Test
    public void testReadFromXml_interfaceNameAbsent_propertyIsNull() throws Exception {
        DeviceFilter deviceFilter = getDeviceFilterFromXml("<usb-device vendor-id=\"1\" />");

        assertThat(deviceFilter.mInterfaceName).isEqualTo(null);
    }

    @Test
    public void testWrite_withInterfaceName() throws Exception {
        DeviceFilter deviceFilter = getDeviceFilterFromXml("<usb-device interface-name=\"MTP\"/>");
        XmlSerializer serializer = Mockito.mock(XmlSerializer.class);

        deviceFilter.write(serializer);

        verify(serializer).attribute(null, "interface-name", "MTP");
    }

    @Test
    public void testWrite_withoutInterfaceName() throws Exception {
        DeviceFilter deviceFilter = getDeviceFilterFromXml("<usb-device vendor-id=\"1\" />");
        XmlSerializer serializer = Mockito.mock(XmlSerializer.class);

        deviceFilter.write(serializer);

        verify(serializer, times(0)).attribute(eq(null), eq("interface-name"), any());
    }

    @Test
    public void testToString() {
        DeviceFilter deviceFilter = new DeviceFilter(
                VID, PID, CLASS, SUBCLASS, PROTOCOL, MANUFACTURER,
                PRODUCT, SERIAL_NO, INTERFACE_NAME
        );

        assertThat(deviceFilter.toString()).isEqualTo(
                "DeviceFilter[mVendorId=10,mProductId=11,mClass=12,mSubclass=13,mProtocol=14,"
                + "mManufacturerName=Google,mProductName=Test,mSerialNumber=4AL23,"
                + "mInterfaceName=MTP]");
    }

    @Test
    public void testMatch_interfaceNameMatches_returnTrue() throws Exception {
        DeviceFilter deviceFilter = getDeviceFilterFromXml(
                "<usb-device class=\"255\" subclass=\"255\" protocol=\"0\" "
                + "interface-name=\"MTP\"/>");
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);
        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(usbDevice.getInterface(0)).thenReturn(new UsbInterface(
            /* id= */ 0,
            /* alternateSetting= */ 0,
            /* name= */ "MTP",
            /* class= */ 255,
            /* subClass= */ 255,
            /* protocol= */ 0));

        assertTrue(deviceFilter.matches(usbDevice));
    }

    @Test
    public void testMatch_interfaceNameMismatch_returnFalse() throws Exception {
        DeviceFilter deviceFilter = getDeviceFilterFromXml(
                "<usb-device class=\"255\" subclass=\"255\" protocol=\"0\" "
                + "interface-name=\"MTP\"/>");
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);
        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(usbDevice.getInterface(0)).thenReturn(new UsbInterface(
            /* id= */ 0,
            /* alternateSetting= */ 0,
            /* name= */ "UVC",
            /* class= */ 255,
            /* subClass= */ 255,
            /* protocol= */ 0));

        assertFalse(deviceFilter.matches(usbDevice));
    }

    @Test
    public void testMatch_interfaceNameMismatchFlagDisabled_returnTrue() throws Exception {
        when(Flags.enableInterfaceNameDeviceFilter()).thenReturn(false);
        DeviceFilter deviceFilter = getDeviceFilterFromXml(
                "<usb-device class=\"255\" subclass=\"255\" protocol=\"0\" "
                + "interface-name=\"MTP\"/>");
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);
        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(usbDevice.getInterface(0)).thenReturn(new UsbInterface(
            /* id= */ 0,
            /* alternateSetting= */ 0,
            /* name= */ "UVC",
            /* class= */ 255,
            /* subClass= */ 255,
            /* protocol= */ 0));

        assertTrue(deviceFilter.matches(usbDevice));
    }

    private void verifyDeviceFilterConfigurationExceptInterfaceName(DeviceFilter deviceFilter) {
        assertThat(deviceFilter.mVendorId).isEqualTo(VID);
        assertThat(deviceFilter.mProductId).isEqualTo(PID);
        assertThat(deviceFilter.mClass).isEqualTo(CLASS);
        assertThat(deviceFilter.mSubclass).isEqualTo(SUBCLASS);
        assertThat(deviceFilter.mProtocol).isEqualTo(PROTOCOL);
        assertThat(deviceFilter.mManufacturerName).isEqualTo(MANUFACTURER);
        assertThat(deviceFilter.mProductName).isEqualTo(PRODUCT);
        assertThat(deviceFilter.mSerialNumber).isEqualTo(SERIAL_NO);
    }

    private DeviceFilter getDeviceFilterFromXml(String xml) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(xml));
        XmlUtils.nextElement(parser);

        return DeviceFilter.read(parser);
    }

}
