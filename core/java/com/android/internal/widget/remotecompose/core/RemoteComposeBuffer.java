/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core;

import com.android.internal.widget.remotecompose.core.operations.BitmapData;
import com.android.internal.widget.remotecompose.core.operations.ClickArea;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmapInt;
import com.android.internal.widget.remotecompose.core.operations.Header;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.RootContentDescription;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.Theme;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Provides an abstract buffer to encode/decode RemoteCompose operations
 */
public class RemoteComposeBuffer {
    WireBuffer mBuffer = new WireBuffer();
    Platform mPlatform = null;
    RemoteComposeState mRemoteComposeState;

    /**
     * Provides an abstract buffer to encode/decode RemoteCompose operations
     *
     * @param remoteComposeState the state used while encoding on the buffer
     */
    public RemoteComposeBuffer(RemoteComposeState remoteComposeState) {
        this.mRemoteComposeState = remoteComposeState;
    }

    public void reset() {
        mBuffer.reset();
        mRemoteComposeState.reset();
    }

    public Platform getPlatform() {
        return mPlatform;
    }

    public void setPlatform(Platform platform) {
        this.mPlatform = platform;
    }

    public WireBuffer getBuffer() {
        return mBuffer;
    }

    public void setBuffer(WireBuffer buffer) {
        this.mBuffer = buffer;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Supported operations on the buffer
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Insert a header
     *
     * @param width        the width of the document in pixels
     * @param height       the height of the document in pixels
     * @param contentDescription content description of the document
     * @param capabilities bitmask indicating needed capabilities (unused for now)
     */
    public void header(int width, int height, String contentDescription, long capabilities) {
        Header.COMPANION.apply(mBuffer, width, height, capabilities);
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
            RootContentDescription.COMPANION.apply(mBuffer, contentDescriptionId);
        }
    }

    /**
     * Insert a header
     *
     * @param width  the width of the document in pixels
     * @param height the height of the document in pixels
     * @param contentDescription content description of the document
     */
    public void header(int width, int height, String contentDescription) {
        header(width, height, contentDescription, 0);
    }

    /**
     * Insert a bitmap
     *
     * @param image       an opaque image that we'll add to the buffer
     * @param imageWidth the width of the image
     * @param imageHeight the height of the image
     * @param srcLeft     left coordinate of the source area
     * @param srcTop      top coordinate of the source area
     * @param srcRight    right coordinate of the source area
     * @param srcBottom   bottom coordinate of the source area
     * @param dstLeft     left coordinate of the destination area
     * @param dstTop      top coordinate of the destination area
     * @param dstRight    right coordinate of the destination area
     * @param dstBottom   bottom coordinate of the destination area
     */
    public void drawBitmap(Object image,
                           int imageWidth, int imageHeight,
                           int srcLeft, int srcTop, int srcRight, int srcBottom,
                           int dstLeft, int dstTop, int dstRight, int dstBottom,
                           String contentDescription) {
        int imageId = mRemoteComposeState.dataGetId(image);
        if (imageId == -1) {
            imageId = mRemoteComposeState.cache(image);
            byte[] data = mPlatform.imageToByteArray(image);
            BitmapData.COMPANION.apply(mBuffer, imageId, imageWidth, imageHeight, data);
        }
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        DrawBitmapInt.COMPANION.apply(
                mBuffer, imageId, srcLeft, srcTop, srcRight, srcBottom,
                dstLeft, dstTop, dstRight, dstBottom, contentDescriptionId
        );
    }

    /**
     * Adds a text string data to the stream and returns its id
     * Will be used to insert string with bitmaps etc.
     *
     * @param text the string to inject in the buffer
     */
    int addText(String text) {
        int id = mRemoteComposeState.dataGetId(text);
        if (id == -1) {
            id = mRemoteComposeState.cache(text);
            TextData.COMPANION.apply(mBuffer, id, text);
        }
        return id;
    }

    /**
     * Add a click area to the document
     *
     * @param id       the id of the click area, reported in the click listener callback
     * @param contentDescription the content description of that click area (accessibility)
     * @param left     left coordinate of the area bounds
     * @param top      top coordinate of the area bounds
     * @param right    right coordinate of the area bounds
     * @param bottom   bottom coordinate of the area bounds
     * @param metadata associated metadata, user-provided
     */
    public void addClickArea(
            int id,
            String contentDescription,
            float left,
            float top,
            float right,
            float bottom,
            String metadata
    ) {
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        int metadataId = 0;
        if (metadata != null) {
            metadataId = addText(metadata);
        }
        ClickArea.COMPANION.apply(mBuffer, id, contentDescriptionId,
                left, top, right, bottom, metadataId);
    }

    /**
     * Sets the way the player handles the content
     *
     * @param scroll set the horizontal behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     * @param alignment set the alignment of the content (TOP|CENTER|BOTTOM|START|END)
     * @param sizing set the type of sizing for the content (NONE|SIZING_LAYOUT|SIZING_SCALE)
     * @param mode set the mode of sizing, either LAYOUT modes or SCALE modes
     *             the LAYOUT modes are:
     *             - LAYOUT_MATCH_PARENT
     *             - LAYOUT_WRAP_CONTENT
     *             or adding an horizontal mode and a vertical mode:
     *             - LAYOUT_HORIZONTAL_MATCH_PARENT
     *             - LAYOUT_HORIZONTAL_WRAP_CONTENT
     *             - LAYOUT_HORIZONTAL_FIXED
     *             - LAYOUT_VERTICAL_MATCH_PARENT
     *             - LAYOUT_VERTICAL_WRAP_CONTENT
     *             - LAYOUT_VERTICAL_FIXED
     *             The LAYOUT_*_FIXED modes will use the intrinsic document size
     */
    public void setRootContentBehavior(int scroll, int alignment, int sizing, int mode) {
        RootContentBehavior.COMPANION.apply(mBuffer, scroll, alignment, sizing, mode);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////

    public void inflateFromBuffer(ArrayList<Operation> operations) {
        mBuffer.setIndex(0);
        while (mBuffer.available()) {
            int opId = mBuffer.readByte();
            CompanionOperation operation = Operations.map.get(opId);
            if (operation == null) {
                throw new RuntimeException("Unknown operation encountered");
            }
            operation.read(mBuffer, operations);
        }
    }

    RemoteComposeBuffer copy() {
        ArrayList<Operation> operations = new ArrayList<>();
        inflateFromBuffer(operations);
        RemoteComposeBuffer buffer = new RemoteComposeBuffer(mRemoteComposeState);
        return copyFromOperations(operations, buffer);
    }

    public void setTheme(int theme) {
        Theme.COMPANION.apply(mBuffer, theme);
    }


    static String version() {
        return "v1.0";
    }

    public static RemoteComposeBuffer fromFile(String path,
                                               RemoteComposeState remoteComposeState)
            throws IOException {
        RemoteComposeBuffer buffer = new RemoteComposeBuffer(remoteComposeState);
        read(new File(path), buffer);
        return buffer;
    }

    public RemoteComposeBuffer fromFile(File file,
                                        RemoteComposeState remoteComposeState) throws IOException {
        RemoteComposeBuffer buffer = new RemoteComposeBuffer(remoteComposeState);
        read(file, buffer);
        return buffer;
    }

    public static RemoteComposeBuffer fromInputStream(InputStream inputStream,
                                               RemoteComposeState remoteComposeState) {
        RemoteComposeBuffer buffer = new RemoteComposeBuffer(remoteComposeState);
        read(inputStream, buffer);
        return buffer;
    }

    RemoteComposeBuffer copyFromOperations(ArrayList<Operation> operations,
                                           RemoteComposeBuffer buffer) {

        for (Operation operation : operations) {
            operation.write(buffer.mBuffer);
        }
        return buffer;
    }

    public void write(RemoteComposeBuffer buffer, File file) {
        try {
            FileOutputStream fd = new FileOutputStream(file);
            fd.write(buffer.mBuffer.getBuffer(), 0, buffer.mBuffer.getSize());
            fd.flush();
            fd.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    static void read(File file, RemoteComposeBuffer buffer) throws IOException {
        FileInputStream fd = new FileInputStream(file);
        read(fd, buffer);
    }

    public static void read(InputStream fd, RemoteComposeBuffer buffer) {
        try {
            byte[] bytes = readAllBytes(fd);
            buffer.reset();
            buffer.mBuffer.resize(bytes.length);
            System.arraycopy(bytes, 0, buffer.mBuffer.mBuffer, 0, bytes.length);
            buffer.mBuffer.mSize = bytes.length;
        } catch (Exception e) {
            e.printStackTrace();
            // todo decide how to handel this stuff
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        byte[] buff = new byte[32 * 1024]; // moderate size buff to start
        int red = 0;
        while (true) {
            int ret = is.read(buff, red, buff.length - red);
            if (ret == -1) {
                is.close();
                return Arrays.copyOf(buff, red);
            }
            red += ret;
            if (red == buff.length) {
                buff = Arrays.copyOf(buff, buff.length * 2);
            }
        }
    }

}

