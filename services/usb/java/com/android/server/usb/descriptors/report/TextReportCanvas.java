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

import com.android.server.usb.descriptors.UsbDescriptorParser;

/**
 * @hide
 * A concrete implementation of ReportCanvas class which generates "Plain Text" output.
 */
public final class TextReportCanvas extends ReportCanvas {
    private static final String TAG = "TextReportCanvas";

    private final StringBuilder mStringBuilder;
    private int mListIndent;
    private static final int LIST_INDENT_AMNT = 2;

    /**
     * Constructor. Connects plain-text output to the provided StringBuilder.
     * @param connection    The USB connection object used to retrieve strings
     * from the USB device.
     * @param stringBuilder Generated output gets written into this object.
     */
    public TextReportCanvas(UsbDescriptorParser parser, StringBuilder stringBuilder) {
        super(parser);

        mStringBuilder = stringBuilder;
    }

    private void writeListIndent() {
        for (int space = 0; space < mListIndent; space++) {
            mStringBuilder.append(" ");
        }
    }

    @Override
    public void write(String text) {
        mStringBuilder.append(text);
    }

    @Override
    public void openHeader(int level) {
        writeListIndent();
        mStringBuilder.append("[");
    }

    @Override
    public void closeHeader(int level) {
        mStringBuilder.append("]\n");
    }

    @Override
    public void openParagraph(boolean emphasis) {
        writeListIndent();
    }

    @Override
    public void closeParagraph() {
        mStringBuilder.append("\n");
    }

    @Override
    public void writeParagraph(String text, boolean inRed) {
        openParagraph(inRed);
        if (inRed) {
            mStringBuilder.append("*" + text + "*");
        } else {
            mStringBuilder.append(text);
        }
        closeParagraph();
    }

    @Override
    public void openList() {
        mListIndent += LIST_INDENT_AMNT;
    }

    @Override
    public void closeList() {
        mListIndent -= LIST_INDENT_AMNT;
    }

    @Override
    public void openListItem() {
        writeListIndent();
        mStringBuilder.append("- ");
    }

    @Override
    public void closeListItem() {
        mStringBuilder.append("\n");
    }
}
