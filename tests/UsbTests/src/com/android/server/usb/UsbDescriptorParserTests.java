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

package com.android.server.usb;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.usb.descriptors.UsbDescriptorParser;
import com.android.server.usb.descriptors.UsbDeviceDescriptor;
import com.google.common.io.ByteStreams;

import java.io.InputStream;
import java.io.IOException;
import java.lang.Exception;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link com.android.server.usb.descriptors.UsbDescriptorParser}
 */
@RunWith(AndroidJUnit4.class)
public class UsbDescriptorParserTests {

    public UsbDescriptorParser loadParser(int resource) {
        Context c = InstrumentationRegistry.getContext();
        Resources res = c.getResources();
        InputStream is = null;
        try {
            is = res.openRawResource(resource);
        } catch (NotFoundException e) {
            fail("Failed to load resource.");
        }

        byte[] descriptors = null;
        try {
            descriptors = ByteStreams.toByteArray(is);
        } catch (IOException e) {
            fail("Failed to convert descriptor strema to bytearray.");
        }

        // Testing same codepath as UsbHostManager.java:usbDeviceAdded
        UsbDescriptorParser parser = new UsbDescriptorParser("test-usb-addr", descriptors);
        return parser;
    }

    /** A Headset has a microphone and a speaker and is a headset.
     * Descriptors for this example show up on lsusb -v with:
     *   bcdDevice           22.80
     * and a UAC1 audio device with the following control interface:
     *       bInterfaceClass         1 Audio
     * ...
     *       bDescriptorSubtype      2 (INPUT_TERMINAL)
     *       bTerminalID             1
     *       wTerminalType      0x0201 Microphone
     * ...
     *       bDescriptorSubtype      3 (OUTPUT_TERMINAL)
     *       bTerminalID            15
     *       wTerminalType      0x0302 Headphones
     */
    @Test
    @SmallTest
    public void testHeadsetDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_headset);
        assertTrue(parser.hasInput());
        assertTrue(parser.hasOutput());
        assertTrue(parser.isInputHeadset());
        assertTrue(parser.isOutputHeadset());

        assertTrue(parser.hasAudioInterface());
        assertTrue(parser.hasHIDInterface());
        assertFalse(parser.hasStorageInterface());

        assertEquals(parser.getDeviceDescriptor().getDeviceReleaseString(), "22.80");
    }

    /** Headphones have no microphones but are considered a headset.
     * Descriptors for this example show up on lsusb -v with:
     *   bcdDevice           22.80
     * and a UAC1 audio device with the following control interface:
     *       bInterfaceClass         1 Audio
     * ...
     *       bDescriptorSubtype      3 (OUTPUT_TERMINAL)
     *       bTerminalID            15
     *       wTerminalType      0x0302 Headphones
     */
    @Test
    @SmallTest
    public void testHeadphoneDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_headphones);
        assertFalse(parser.hasInput());
        assertTrue(parser.hasOutput());
        assertFalse(parser.isInputHeadset());
        assertTrue(parser.isOutputHeadset());

        assertTrue(parser.hasAudioInterface());
        assertTrue(parser.hasHIDInterface());
        assertFalse(parser.hasStorageInterface());

        assertEquals(parser.getDeviceDescriptor().getDeviceReleaseString(), "22.80");
    }

    /** Line out with no microphones aren't considered a headset.
     * Descriptors for this example show up on lsusb -v with:
     *     bcdDevice           22.80
     * and the following UAC1 audio control interface
     *  bInterfaceClass         1 Audio
     *  ...
     *   bDescriptorSubtype      3 (OUTPUT_TERMINAL)
     *   bTerminalID            15
     *   wTerminalType      0x0603 Line Connector
     */
    @Test
    @SmallTest
    public void testLineoutDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_lineout);
        assertFalse(parser.hasInput());
        assertTrue(parser.hasOutput());
        assertFalse(parser.isInputHeadset());
        assertFalse(parser.isOutputHeadset());

        assertTrue(parser.hasAudioInterface());
        assertTrue(parser.hasHIDInterface());
        assertFalse(parser.hasStorageInterface());

        assertEquals(parser.getDeviceDescriptor().getDeviceReleaseString(), "22.80");
    }

    /** An HID-only device shouldn't be considered anything at all.
    /* Descriptors show up on lsusb -v with:
     *   bcdDevice           22.80
     * and a single HID interface,
     *   bInterfaceClass         3 Human Interface Device
     */
    @Test
    @SmallTest
    public void testNothingDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_nothing);
        assertFalse(parser.hasInput());
        assertFalse(parser.hasOutput());
        assertFalse(parser.isInputHeadset());
        assertFalse(parser.isOutputHeadset());

        assertFalse(parser.hasAudioInterface());
        assertTrue(parser.hasHIDInterface());
        assertFalse(parser.hasStorageInterface());

        assertEquals(parser.getDeviceDescriptor().getDeviceReleaseString(), "22.80");
    }

    /** A USB mass-storage device.
     * Shows up on lsusb -v with:
     *    bcdDevice            2.08
     * and a single interface descriptor,
     *    bInterfaceClass         8 Mass Storage
     */
    @Test
    @SmallTest
    public void testMassStorageDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_massstorage);
        assertFalse(parser.hasInput());
        assertFalse(parser.hasOutput());
        assertFalse(parser.isInputHeadset());
        assertFalse(parser.isOutputHeadset());

        assertFalse(parser.hasAudioInterface());
        assertFalse(parser.hasHIDInterface());
        assertTrue(parser.hasStorageInterface());

        assertEquals(parser.getDeviceDescriptor().getDeviceReleaseString(), "2.08");
    }

}
