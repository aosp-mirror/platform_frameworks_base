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
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.usb.descriptors.UsbDescriptorParser;
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
        UsbDescriptorParser parser = new UsbDescriptorParser("test-usb-addr");
        if (!parser.parseDescriptors(descriptors)) {
            fail("failed to parse descriptors.");
        }
        return parser;
    }

    // A Headset has a microphone and a speaker and is a headset.
    @Test
    @SmallTest
    public void testHeadsetDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_headset);
        assertTrue(parser.hasInput());
        assertTrue(parser.hasOutput());
        assertTrue(parser.isInputHeadset());
        assertTrue(parser.isOutputHeadset());
    }

    // Headphones have no microphones but are considered a headset.
    @Test
    @SmallTest
    public void testHeadphoneDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_headphones);
        assertFalse(parser.hasInput());
        assertTrue(parser.hasOutput());
        assertFalse(parser.isInputHeadset());
        assertTrue(parser.isOutputHeadset());
    }

    // Line out has no microphones and aren't considered a headset.
    @Test
    @SmallTest
    public void testLineoutDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_lineout);
        assertFalse(parser.hasInput());
        assertTrue(parser.hasOutput());
        assertFalse(parser.isInputHeadset());
        assertFalse(parser.isOutputHeadset());
    }

    // An HID-only device shouldn't be considered anything at all.
    @Test
    @SmallTest
    public void testNothingDescriptorParser() {
        UsbDescriptorParser parser = loadParser(R.raw.usbdescriptors_nothing);
        assertFalse(parser.hasInput());
        assertFalse(parser.hasOutput());
        assertFalse(parser.isInputHeadset());
        assertFalse(parser.isOutputHeadset());
    }

}
