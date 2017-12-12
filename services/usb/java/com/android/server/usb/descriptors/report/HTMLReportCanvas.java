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
 * A concrete implementation of ReportCanvas class which generates HTML.
 */
public final class HTMLReportCanvas extends ReportCanvas {
    private static final String TAG = "HTMLReportCanvas";

    private final StringBuilder mStringBuilder;

    /**
     * Constructor. Connects HTML output to the provided StringBuilder.
     * @param connection    The USB connection object used to retrieve strings
     * from the USB device.
     * @param stringBuilder Generated output gets written into this object.
     */
    public HTMLReportCanvas(UsbDeviceConnection connection, StringBuilder stringBuilder) {
        super(connection);

        mStringBuilder = stringBuilder;
    }

    @Override
    public void write(String text) {
        mStringBuilder.append(text);
    }

    @Override
    public void openHeader(int level) {
        mStringBuilder.append("<h").append(level).append('>');
    }

    @Override
    public void closeHeader(int level) {
        mStringBuilder.append("</h").append(level).append('>');
    }

    // we can be cleverer (more clever?) with styles, but this will do for now.
    @Override
    public void openParagraph(boolean emphasis) {
        if (emphasis) {
            mStringBuilder.append("<p style=\"color:red\">");
        } else {
            mStringBuilder.append("<p>");
        }
    }

    @Override
    public void closeParagraph() {
        mStringBuilder.append("</p>");
    }

    @Override
    public void writeParagraph(String text, boolean inRed) {
        openParagraph(inRed);
        mStringBuilder.append(text);
        closeParagraph();
    }

    @Override
    public void openList() {
        mStringBuilder.append("<ul>");
    }

    @Override
    public void closeList() {
        mStringBuilder.append("</ul>");
    }

    @Override
    public void openListItem() {
        mStringBuilder.append("<li>");
    }

    @Override
    public void closeListItem() {
        mStringBuilder.append("</li>");
    }
}
