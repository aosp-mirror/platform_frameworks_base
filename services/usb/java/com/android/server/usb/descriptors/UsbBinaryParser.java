/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.usb.descriptors;

import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.android.server.usb.descriptors.report.UsbStrings;

/**
 * @hide
 * A class that just walks the descriptors and does a hex dump of the contained values.
 * Usefull as a debugging tool.
 */
public final class UsbBinaryParser {
    private static final String TAG = "UsbBinaryParser";
    private static final boolean LOGGING = false;

    private void dumpDescriptor(ByteStream stream, int length, byte type, StringBuilder builder) {

        // Log
        if (LOGGING) {
            Log.i(TAG, "l: " + length + " t: " + Integer.toHexString(type) + " "
                    + UsbStrings.getDescriptorName(type));
            StringBuilder sb = new StringBuilder();
            for (int index = 2; index < length; index++) {
                sb.append("0x" + Integer.toHexString(stream.getByte() & 0xFF) + " ");
            }
            Log.i(TAG, sb.toString());
        } else {
            // Screen Dump
            builder.append("<p>");
            builder.append("<b> l: " + length
                    + " t:0x" + Integer.toHexString(type) + " "
                    + UsbStrings.getDescriptorName(type) + "</b><br>");
            for (int index = 2; index < length; index++) {
                builder.append("0x" + Integer.toHexString(stream.getByte() & 0xFF) + " ");
            }
            builder.append("</p>");
        }
    }

    /**
     * Walk through descriptor stream and generate an HTML text report of the contents.
     * TODO: This should be done in the model of UsbDescriptorsParser/Reporter model.
     */
    public void parseDescriptors(UsbDeviceConnection connection, byte[] descriptors,
                                 StringBuilder builder) {

        builder.append("<tt>");
        ByteStream stream = new ByteStream(descriptors);
        while (stream.available() > 0) {
            int length = (int) stream.getByte() & 0x000000FF;
            byte type = stream.getByte();
            dumpDescriptor(stream, length, type, builder);
        }
        builder.append("</tt>");
    }
}
