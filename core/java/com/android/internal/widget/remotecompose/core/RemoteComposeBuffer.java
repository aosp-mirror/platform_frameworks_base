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
import com.android.internal.widget.remotecompose.core.operations.ClipPath;
import com.android.internal.widget.remotecompose.core.operations.ClipRect;
import com.android.internal.widget.remotecompose.core.operations.DrawArc;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmap;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmapInt;
import com.android.internal.widget.remotecompose.core.operations.DrawCircle;
import com.android.internal.widget.remotecompose.core.operations.DrawLine;
import com.android.internal.widget.remotecompose.core.operations.DrawOval;
import com.android.internal.widget.remotecompose.core.operations.DrawPath;
import com.android.internal.widget.remotecompose.core.operations.DrawRect;
import com.android.internal.widget.remotecompose.core.operations.DrawRoundRect;
import com.android.internal.widget.remotecompose.core.operations.DrawTextOnPath;
import com.android.internal.widget.remotecompose.core.operations.DrawTextRun;
import com.android.internal.widget.remotecompose.core.operations.DrawTweenPath;
import com.android.internal.widget.remotecompose.core.operations.Header;
import com.android.internal.widget.remotecompose.core.operations.MatrixRestore;
import com.android.internal.widget.remotecompose.core.operations.MatrixRotate;
import com.android.internal.widget.remotecompose.core.operations.MatrixSave;
import com.android.internal.widget.remotecompose.core.operations.MatrixScale;
import com.android.internal.widget.remotecompose.core.operations.MatrixSkew;
import com.android.internal.widget.remotecompose.core.operations.MatrixTranslate;
import com.android.internal.widget.remotecompose.core.operations.PaintData;
import com.android.internal.widget.remotecompose.core.operations.PathData;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.RootContentDescription;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;

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

    /**
     * Reset the internal buffers
     *
     * @param expectedSize provided hint for the main buffer size
     */
    public void reset(int expectedSize) {
        mBuffer.reset(expectedSize);
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
     * @param width              the width of the document in pixels
     * @param height             the height of the document in pixels
     * @param contentDescription content description of the document
     * @param capabilities       bitmask indicating needed capabilities (unused for now)
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
     * @param width              the width of the document in pixels
     * @param height             the height of the document in pixels
     * @param contentDescription content description of the document
     */
    public void header(int width, int height, String contentDescription) {
        header(width, height, contentDescription, 0);
    }

    /**
     * Insert a bitmap
     *
     * @param image       an opaque image that we'll add to the buffer
     * @param imageWidth  the width of the image
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
     * @param id                 the id of the click area, reported in the click listener callback
     * @param contentDescription the content description of that click area (accessibility)
     * @param left               left coordinate of the area bounds
     * @param top                top coordinate of the area bounds
     * @param right              right coordinate of the area bounds
     * @param bottom             bottom coordinate of the area bounds
     * @param metadata           associated metadata, user-provided
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
     * @param scroll    set the horizontal behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     * @param alignment set the alignment of the content (TOP|CENTER|BOTTOM|START|END)
     * @param sizing    set the type of sizing for the content (NONE|SIZING_LAYOUT|SIZING_SCALE)
     * @param mode      set the mode of sizing, either LAYOUT modes or SCALE modes
     *                  the LAYOUT modes are:
     *                  - LAYOUT_MATCH_PARENT
     *                  - LAYOUT_WRAP_CONTENT
     *                  or adding an horizontal mode and a vertical mode:
     *                  - LAYOUT_HORIZONTAL_MATCH_PARENT
     *                  - LAYOUT_HORIZONTAL_WRAP_CONTENT
     *                  - LAYOUT_HORIZONTAL_FIXED
     *                  - LAYOUT_VERTICAL_MATCH_PARENT
     *                  - LAYOUT_VERTICAL_WRAP_CONTENT
     *                  - LAYOUT_VERTICAL_FIXED
     *                  The LAYOUT_*_FIXED modes will use the intrinsic document size
     */
    public void setRootContentBehavior(int scroll, int alignment, int sizing, int mode) {
        RootContentBehavior.COMPANION.apply(mBuffer, scroll, alignment, sizing, mode);
    }

    /**
     * add Drawing the specified arc, which will be scaled to fit inside the specified oval.
     * <br>
     * If the start angle is negative or >= 360, the start angle is treated as start angle modulo
     * 360.
     * <br>
     * If the sweep angle is >= 360, then the oval is drawn completely. Note that this differs
     * slightly from SkPath::arcTo, which treats the sweep angle modulo 360. If the sweep angle is
     * negative, the sweep angle is treated as sweep angle modulo 360
     * <br>
     * The arc is drawn clockwise. An angle of 0 degrees correspond to the geometric angle of 0
     * degrees (3 o'clock on a watch.)
     * <br>
     *
     * @param left       left coordinate of oval used to define the shape and size of the arc
     * @param top        top coordinate of oval used to define the shape and size of the arc
     * @param right      right coordinate of oval used to define the shape and size of the arc
     * @param bottom     bottom coordinate of oval used to define the shape and size of the arc
     * @param startAngle Starting angle (in degrees) where the arc begins
     * @param sweepAngle Sweep angle (in degrees) measured clockwise
     */
    public void addDrawArc(float left,
                           float top,
                           float right,
                           float bottom,
                           float startAngle,
                           float sweepAngle) {
        DrawArc.COMPANION.apply(mBuffer, left, top, right, bottom, startAngle, sweepAngle);
    }

    /**
     * @param image              The bitmap to be drawn
     * @param left               left coordinate of rectangle that the bitmap will be to fit into
     * @param top                top coordinate of rectangle that the bitmap will be to fit into
     * @param right              right coordinate of rectangle that the bitmap will be to fit into
     * @param bottom             bottom coordinate of rectangle that the bitmap will be to fit into
     * @param contentDescription content description of the image
     */
    public void addDrawBitmap(Object image,
                              float left,
                              float top,
                              float right,
                              float bottom,
                              String contentDescription) {
        int imageId = mRemoteComposeState.dataGetId(image);
        if (imageId == -1) {
            imageId = mRemoteComposeState.cache(image);
            byte[] data = mPlatform.imageToByteArray(image);
            int imageWidth = mPlatform.getImageWidth(image);
            int imageHeight = mPlatform.getImageHeight(image);

            BitmapData.COMPANION.apply(mBuffer, imageId, imageWidth, imageHeight, data);
        }
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        DrawBitmap.COMPANION.apply(
                mBuffer, imageId, left, top, right, bottom, contentDescriptionId
        );
    }

    /**
     * Draw the specified circle using the specified paint. If radius is <= 0, then nothing will be
     * drawn.
     *
     * @param centerX The x-coordinate of the center of the circle to be drawn
     * @param centerY The y-coordinate of the center of the circle to be drawn
     * @param radius  The radius of the circle to be drawn
     */
    public void addDrawCircle(float centerX, float centerY, float radius) {
        DrawCircle.COMPANION.apply(mBuffer, centerX, centerY, radius);
    }

    /**
     * Draw a line segment with the specified start and stop x,y coordinates, using the specified
     * paint.
     *
     * @param x1 The x-coordinate of the start point of the line
     * @param y1 The y-coordinate of the start point of the line
     * @param x2 The x-coordinate of the end point of the line
     * @param y2 The y-coordinate of the end point of the line
     */
    public void addDrawLine(float x1, float y1, float x2, float y2) {
        DrawLine.COMPANION.apply(mBuffer, x1, y1, x2, y2);
    }

    /**
     * Draw the specified oval using the specified paint.
     *
     * @param left   left coordinate of oval
     * @param top    top coordinate of oval
     * @param right  right coordinate of oval
     * @param bottom bottom coordinate of oval
     */
    public void addDrawOval(float left, float top, float right, float bottom) {
        DrawOval.COMPANION.apply(mBuffer, left, top, right, bottom);
    }

    /**
     * Draw the specified path
     * <p>
     * Note: path objects are not immutable
     * modifying them and calling this will not change the drawing
     *
     * @param path The path to be drawn
     */
    public void addDrawPath(Object path) {
        int id = mRemoteComposeState.dataGetId(path);
        if (id == -1) { // never been seen before
            id = addPathData(path);
        }
        addDrawPath(id);
    }


    /**
     * Draw the specified path
     *
     * @param pathId
     */
    public void addDrawPath(int pathId) {
        DrawPath.COMPANION.apply(mBuffer, pathId);
    }

    /**
     * Draw the specified Rect
     *
     * @param left   left coordinate of rectangle to be drawn
     * @param top    top coordinate of rectangle to be drawn
     * @param right  right coordinate of rectangle to be drawn
     * @param bottom bottom coordinate of rectangle to be drawn
     */
    public void addDrawRect(float left, float top, float right, float bottom) {
        DrawRect.COMPANION.apply(mBuffer, left, top, right, bottom);
    }

    /**
     * Draw the specified round-rect
     *
     * @param left    left coordinate of rectangle to be drawn
     * @param top     left coordinate of rectangle to be drawn
     * @param right   left coordinate of rectangle to be drawn
     * @param bottom  left coordinate of rectangle to be drawn
     * @param radiusX The x-radius of the oval used to round the corners
     * @param radiusY The y-radius of the oval used to round the corners
     */
    public void addDrawRoundRect(float left, float top, float right, float bottom,
                                 float radiusX, float radiusY) {
        DrawRoundRect.COMPANION.apply(mBuffer, left, top, right, bottom, radiusX, radiusY);
    }

    /**
     * Draw the text, with origin at (x,y) along the specified path.
     *
     * @param text    The text to be drawn
     * @param path    The path the text should follow for its baseline
     * @param hOffset The distance along the path to add to the text's starting position
     * @param vOffset The distance above(-) or below(+) the path to position the text
     */
    public void addDrawTextOnPath(String text, Object path, float hOffset, float vOffset) {
        int pathId = mRemoteComposeState.dataGetId(path);
        if (pathId == -1) { // never been seen before
            pathId = addPathData(path);
        }
        int textId = addText(text);
        DrawTextOnPath.COMPANION.apply(mBuffer, textId, pathId, hOffset, vOffset);
    }

    /**
     * Draw the text, with origin at (x,y). The origin is interpreted
     * based on the Align setting in the paint.
     *
     * @param text         The text to be drawn
     * @param start        The index of the first character in text to draw
     * @param end          (end - 1) is the index of the last character in text to draw
     * @param contextStart
     * @param contextEnd
     * @param x            The x-coordinate of the origin of the text being drawn
     * @param y            The y-coordinate of the baseline of the text being drawn
     * @param rtl          Draw RTTL
     */
    public void addDrawTextRun(String text,
                               int start,
                               int end,
                               int contextStart,
                               int contextEnd,
                               float x,
                               float y,
                               boolean rtl) {
        int textId = addText(text);
        DrawTextRun.COMPANION.apply(
                mBuffer, textId, start, end,
                contextStart, contextEnd, x, y, rtl);
    }

    /**
     * draw an interpolation between two paths that have the same pattern
     * <p>
     * Warning paths objects are not immutable and this is not taken into consideration
     *
     * @param path1 The path1 to be drawn between
     * @param path2 The path2 to be drawn between
     * @param tween The ratio of path1 and path2 to 0 = all path 1, 1 = all path2
     * @param start The start of the subrange of paths to draw 0 = start form start 0.5 is half way
     * @param stop  The end of the subrange of paths to draw 1 = end at the end 0.5 is end half way
     */
    public void addDrawTweenPath(Object path1,
                                 Object path2,
                                 float tween,
                                 float start,
                                 float stop) {
        int path1Id = mRemoteComposeState.dataGetId(path1);
        if (path1Id == -1) { // never been seen before
            path1Id = addPathData(path1);
        }
        int path2Id = mRemoteComposeState.dataGetId(path2);
        if (path2Id == -1) { // never been seen before
            path2Id = addPathData(path2);
        }
        addDrawTweenPath(path1Id, path2Id, tween, start, stop);
    }

    /**
     * draw an interpolation between two paths that have the same pattern
     *
     * @param path1Id The path1 to be drawn between
     * @param path2Id The path2 to be drawn between
     * @param tween   The ratio of path1 and path2 to 0 = all path 1, 1 = all path2
     * @param start   The start of the subrange of paths to draw 0 = start form start .5 is 1/2 way
     * @param stop    The end of the subrange of paths to draw 1 = end at the end .5 is end 1/2 way
     */
    public void addDrawTweenPath(int path1Id,
                                 int path2Id,
                                 float tween,
                                 float start,
                                 float stop) {
        DrawTweenPath.COMPANION.apply(
                mBuffer, path1Id, path2Id,
                tween, start, stop);
    }

    /**
     * Add a path object
     *
     * @param path
     * @return the id of the path on the wire
     */
    public int addPathData(Object path) {
        float[] pathData = mPlatform.pathToFloatArray(path);
        int id = mRemoteComposeState.cache(path);
        PathData.COMPANION.apply(mBuffer, id, pathData);
        return id;
    }

    public void addPaint(PaintBundle paint) {
        PaintData.COMPANION.apply(mBuffer, paint);
    }
    ///////////////////////////////////////////////////////////////////////////////////////////////

    public void inflateFromBuffer(ArrayList<Operation> operations) {
        mBuffer.setIndex(0);
        while (mBuffer.available()) {
            int opId = mBuffer.readByte();
            System.out.println(">>> " + opId);
            CompanionOperation operation = Operations.map.get(opId);
            if (operation == null) {
                throw new RuntimeException("Unknown operation encountered " + opId);
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
            buffer.reset(bytes.length);
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

    /**
     * add a Pre-concat the current matrix with the specified skew.
     *
     * @param skewX The amount to skew in X
     * @param skewY The amount to skew in Y
     */
    public void addMatrixSkew(float skewX, float skewY) {
        MatrixSkew.COMPANION.apply(mBuffer, skewX, skewY);
    }

    /**
     * This call balances a previous call to save(), and is used to remove all
     * modifications to the matrix/clip state since the last save call.
     * Do not call restore() more times than save() was called.
     */
    public void addMatrixRestore() {
        MatrixRestore.COMPANION.apply(mBuffer);
    }

    /**
     * Add a saves the current matrix and clip onto a private stack.
     * <p>
     * Subsequent calls to translate,scale,rotate,skew,concat or clipRect,
     * clipPath will all operate as usual, but when the balancing call to
     * restore() is made, those calls will be forgotten, and the settings that
     * existed before the save() will be reinstated.
     */
    public void addMatrixSave() {
        MatrixSave.COMPANION.apply(mBuffer);
    }

    /**
     * add a pre-concat the current matrix with the specified rotation.
     *
     * @param angle   The amount to rotate, in degrees
     * @param centerX The x-coord for the pivot point (unchanged by the rotation)
     * @param centerY The y-coord for the pivot point (unchanged by the rotation)
     */
    public void addMatrixRotate(float angle, float centerX, float centerY) {
        MatrixRotate.COMPANION.apply(mBuffer, angle, centerX, centerY);
    }

    /**
     * add a Pre-concat to the current matrix with the specified translation
     *
     * @param dx The distance to translate in X
     * @param dy The distance to translate in Y
     */
    public void addMatrixTranslate(float dx, float dy) {
        MatrixTranslate.COMPANION.apply(mBuffer, dx, dy);
    }

    /**
     * Add a pre-concat of the current matrix with the specified scale.
     *
     * @param scaleX  The amount to scale in X
     * @param scaleY  The amount to scale in Y
     */
    public void addMatrixScale(float scaleX, float scaleY) {
        MatrixScale.COMPANION.apply(mBuffer, scaleX, scaleY, Float.NaN, Float.NaN);
    }

    /**
     * Add a pre-concat of the current matrix with the specified scale.
     *
     * @param scaleX  The amount to scale in X
     * @param scaleY  The amount to scale in Y
     * @param centerX The x-coord for the pivot point (unchanged by the scale)
     * @param centerY The y-coord for the pivot point (unchanged by the scale)
     */
    public void addMatrixScale(float scaleX, float scaleY, float centerX, float centerY) {
        MatrixScale.COMPANION.apply(mBuffer, scaleX, scaleY, centerX, centerY);
    }

    public void addClipPath(int pathId) {
        ClipPath.COMPANION.apply(mBuffer, pathId);
    }

    public void addClipRect(float left, float top, float right, float bottom) {
        ClipRect.COMPANION.apply(mBuffer, left, top, right, bottom);
    }
}

