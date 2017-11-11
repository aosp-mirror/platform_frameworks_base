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
package com.android.server.usb.descriptors.report;

import android.hardware.usb.UsbDeviceConnection;

/**
 * @hide
 * Defines a class for generating report data in a variety of potential formats.
 */
public abstract class ReportCanvas {
    private static final String TAG = "ReportCanvas";

    private final UsbDeviceConnection mConnection;

    /**
     * Constructor.
     * @param connection    The USB connection object used to retrieve strings
     * from the USB device.
     */
    public ReportCanvas(UsbDeviceConnection connection) {
        mConnection = connection;
    }

    /**
     * @returns the UsbDeviceConnection member (mConnection).
     */
    public UsbDeviceConnection getConnection() {
        return mConnection;
    }

    /**
     * Writes a plain string to the output.
     */
    public abstract void write(String text);

    /**
     * Opens a "header" formatted section in the output.
     * @param level Specifies the logical level of the header.
     */
    public abstract void openHeader(int level);

    /**
     * Closes a "header" formatted section in the output.
     * @param level Specifies the logical level of the header.
     */
    public abstract void closeHeader(int level);

    /**
     * Writes a "header" formatted string to the output.
     * @param level Specifies the logical level of the header.
     * @param text  Specifies the text to display in the header.
     */
    public void writeHeader(int level, String text) {
        openHeader(level);
        write(text);
        closeHeader(level);
    }

    /**
     * Opens a paragraph construct in the output.
     * @param emphasis Specifies whether the text in the paragraph should
     * be displayed with "emphasis" formatting.
     */
    public abstract void openParagraph(boolean emphasis);

    /**
     * Closes a paragraph construct in the output.
     */
    public abstract void closeParagraph();

    /**
     * Writes a paragraph construct to the output.
     * @param text  The text to display with "paragraph" formatting.
     * @param emphasis Specifies whether the text in the paragraph should
     * be displayed with "emphasis" formatting.
     */
    public abstract void writeParagraph(String text, boolean emphasis);

    /**
     * Opens a "list" formatted section in the output.
     */
    public abstract void openList();

    /**
     * Closes a "list" formatted section in the output.
     */
    public abstract void closeList();

    /**
     * Opens a "list item" formatted section in the output.
     */
    public abstract void openListItem();

    /**
     * Closes a "list item" formatted section in the output.
     */
    public abstract void closeListItem();

    /**
     * Writes a "list item" formatted section in the output.
     * @param text  Specifies the text of the list item.
     */
    public void writeListItem(String text) {
        openListItem();
        write(text);
        closeListItem();
    }

    /*
     * Data Formating Helpers
     */
    /**
     * Generates a hex representation of the specified byte value.
     * @param value The value to format.
     */
    //TODO Look into renaming the "getHexString()" functions to be more
    // representative of the types they handle.
    public static String getHexString(byte value) {
        return "0x" + Integer.toHexString(((int) value) & 0xFF).toUpperCase();
    }

    /**
     * Generates a string representing a USB Binary-Coded Decimal value.
     * @param valueBCD The value to format.
     */
    public static String getBCDString(int valueBCD) {
        int major = (valueBCD >> 8) & 0x0F;
        int minor = (valueBCD >> 4) & 0x0F;
        int subminor = valueBCD & 0x0F;

        return "" + major + "." + minor + subminor;
    }

    /**
     * Generates a hex representation of the specified 16-bit integer value.
     * @param value The value to format.
     */
    //TODO Look into renaming the "getHexString()" functions to be more
    // representative of the types they handle.
    public static String getHexString(int value) {
        int intValue = value & 0xFFFF;
        return "0x" + Integer.toHexString(intValue).toUpperCase();
    }

    /**
     * Writes out the specified byte array to the provided StringBuilder.
     * @param rawData   The byte values.
     * @param builder The StringBuilder to write text into.
     */
    public void dumpHexArray(byte[] rawData, StringBuilder builder) {
        if (rawData != null) {
            // Assume the type and Length and perhaps sub-type have been displayed
            openParagraph(false);
            for (int index = 0; index < rawData.length; index++) {
                builder.append(getHexString(rawData[index]) + " ");
            }
            closeParagraph();
        }
    }
}
