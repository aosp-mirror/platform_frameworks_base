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

import static com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression.MUL;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.operations.BitmapData;
import com.android.internal.widget.remotecompose.core.operations.ClickArea;
import com.android.internal.widget.remotecompose.core.operations.ClipPath;
import com.android.internal.widget.remotecompose.core.operations.ClipRect;
import com.android.internal.widget.remotecompose.core.operations.ColorConstant;
import com.android.internal.widget.remotecompose.core.operations.ColorExpression;
import com.android.internal.widget.remotecompose.core.operations.ComponentValue;
import com.android.internal.widget.remotecompose.core.operations.DataListFloat;
import com.android.internal.widget.remotecompose.core.operations.DataListIds;
import com.android.internal.widget.remotecompose.core.operations.DataMapIds;
import com.android.internal.widget.remotecompose.core.operations.DataMapLookup;
import com.android.internal.widget.remotecompose.core.operations.DrawArc;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmap;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmapInt;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmapScaled;
import com.android.internal.widget.remotecompose.core.operations.DrawCircle;
import com.android.internal.widget.remotecompose.core.operations.DrawLine;
import com.android.internal.widget.remotecompose.core.operations.DrawOval;
import com.android.internal.widget.remotecompose.core.operations.DrawPath;
import com.android.internal.widget.remotecompose.core.operations.DrawRect;
import com.android.internal.widget.remotecompose.core.operations.DrawRoundRect;
import com.android.internal.widget.remotecompose.core.operations.DrawSector;
import com.android.internal.widget.remotecompose.core.operations.DrawText;
import com.android.internal.widget.remotecompose.core.operations.DrawTextAnchored;
import com.android.internal.widget.remotecompose.core.operations.DrawTextOnPath;
import com.android.internal.widget.remotecompose.core.operations.DrawTweenPath;
import com.android.internal.widget.remotecompose.core.operations.FloatConstant;
import com.android.internal.widget.remotecompose.core.operations.FloatExpression;
import com.android.internal.widget.remotecompose.core.operations.Header;
import com.android.internal.widget.remotecompose.core.operations.IntegerExpression;
import com.android.internal.widget.remotecompose.core.operations.MatrixRestore;
import com.android.internal.widget.remotecompose.core.operations.MatrixRotate;
import com.android.internal.widget.remotecompose.core.operations.MatrixSave;
import com.android.internal.widget.remotecompose.core.operations.MatrixScale;
import com.android.internal.widget.remotecompose.core.operations.MatrixSkew;
import com.android.internal.widget.remotecompose.core.operations.MatrixTranslate;
import com.android.internal.widget.remotecompose.core.operations.NamedVariable;
import com.android.internal.widget.remotecompose.core.operations.PaintData;
import com.android.internal.widget.remotecompose.core.operations.PathAppend;
import com.android.internal.widget.remotecompose.core.operations.PathCreate;
import com.android.internal.widget.remotecompose.core.operations.PathData;
import com.android.internal.widget.remotecompose.core.operations.PathTween;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.RootContentDescription;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.TextFromFloat;
import com.android.internal.widget.remotecompose.core.operations.TextLength;
import com.android.internal.widget.remotecompose.core.operations.TextLookup;
import com.android.internal.widget.remotecompose.core.operations.TextLookupInt;
import com.android.internal.widget.remotecompose.core.operations.TextMeasure;
import com.android.internal.widget.remotecompose.core.operations.TextMerge;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.core.operations.TouchExpression;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.layout.CanvasContent;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentStart;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponentContent;
import com.android.internal.widget.remotecompose.core.operations.layout.LoopEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.LoopOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.OperationsListEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.RootLayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.BoxLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.CanvasLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.ColumnLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.RowLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.StateLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.TextLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.BackgroundModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.BorderModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ClipRectModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.GraphicsLayerModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.MarqueeModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.OffsetModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.PaddingModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.RippleModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.RoundedClipRectModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ScrollModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ZIndexModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.utilities.NanMap;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.FloatAnimation;
import com.android.internal.widget.remotecompose.core.types.BooleanConstant;
import com.android.internal.widget.remotecompose.core.types.IntegerConstant;
import com.android.internal.widget.remotecompose.core.types.LongConstant;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

/** Provides an abstract buffer to encode/decode RemoteCompose operations */
public class RemoteComposeBuffer {
    public static final int EASING_CUBIC_STANDARD = FloatAnimation.CUBIC_STANDARD;
    public static final int EASING_CUBIC_ACCELERATE = FloatAnimation.CUBIC_ACCELERATE;
    public static final int EASING_CUBIC_DECELERATE = FloatAnimation.CUBIC_DECELERATE;
    public static final int EASING_CUBIC_LINEAR = FloatAnimation.CUBIC_LINEAR;
    public static final int EASING_CUBIC_ANTICIPATE = FloatAnimation.CUBIC_ANTICIPATE;
    public static final int EASING_CUBIC_OVERSHOOT = FloatAnimation.CUBIC_OVERSHOOT;
    public static final int EASING_CUBIC_CUSTOM = FloatAnimation.CUBIC_CUSTOM;
    public static final int EASING_SPLINE_CUSTOM = FloatAnimation.SPLINE_CUSTOM;
    public static final int EASING_EASE_OUT_BOUNCE = FloatAnimation.EASE_OUT_BOUNCE;
    public static final int EASING_EASE_OUT_ELASTIC = FloatAnimation.EASE_OUT_ELASTIC;
    private @NonNull WireBuffer mBuffer = new WireBuffer();
    @Nullable private Platform mPlatform = null;
    @NonNull private final RemoteComposeState mRemoteComposeState;
    private static final boolean DEBUG = false;

    private int mLastComponentId = 0;
    private int mGeneratedComponentId = -1;

    /**
     * Provides an abstract buffer to encode/decode RemoteCompose operations
     *
     * @param remoteComposeState the state used while encoding on the buffer
     */
    public RemoteComposeBuffer(@NonNull RemoteComposeState remoteComposeState) {
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
        mLastComponentId = 0;
        mGeneratedComponentId = -1;
    }

    public int getLastComponentId() {
        return mLastComponentId;
    }

    @Nullable
    public Platform getPlatform() {
        return mPlatform;
    }

    public void setPlatform(@NonNull Platform platform) {
        this.mPlatform = platform;
    }

    public @NonNull WireBuffer getBuffer() {
        return mBuffer;
    }

    public void setBuffer(@NonNull WireBuffer buffer) {
        this.mBuffer = buffer;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Supported operations on the buffer
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Insert a header
     *
     * @param width the width of the document in pixels
     * @param height the height of the document in pixels
     * @param contentDescription content description of the document
     * @param capabilities bitmask indicating needed capabilities (unused for now)
     */
    public void header(
            int width,
            int height,
            @Nullable String contentDescription,
            float density,
            long capabilities) {
        Header.apply(mBuffer, width, height, density, capabilities);
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
            RootContentDescription.apply(mBuffer, contentDescriptionId);
        }
    }

    /**
     * Insert a header
     *
     * @param width the width of the document in pixels
     * @param height the height of the document in pixels
     * @param contentDescription content description of the document
     */
    public void header(int width, int height, @Nullable String contentDescription) {
        header(width, height, contentDescription, 1f, 0);
    }

    /**
     * Insert a bitmap
     *
     * @param image an opaque image that we'll add to the buffer
     * @param imageWidth the width of the image
     * @param imageHeight the height of the image
     * @param srcLeft left coordinate of the source area
     * @param srcTop top coordinate of the source area
     * @param srcRight right coordinate of the source area
     * @param srcBottom bottom coordinate of the source area
     * @param dstLeft left coordinate of the destination area
     * @param dstTop top coordinate of the destination area
     * @param dstRight right coordinate of the destination area
     * @param dstBottom bottom coordinate of the destination area
     */
    public void drawBitmap(
            @NonNull Object image,
            int imageWidth,
            int imageHeight,
            int srcLeft,
            int srcTop,
            int srcRight,
            int srcBottom,
            int dstLeft,
            int dstTop,
            int dstRight,
            int dstBottom,
            @Nullable String contentDescription) {
        int imageId = mRemoteComposeState.dataGetId(image);
        if (imageId == -1) {
            imageId = mRemoteComposeState.cacheData(image);
            byte[] data = mPlatform.imageToByteArray(image); // todo: potential npe
            BitmapData.apply(
                    mBuffer, imageId, imageWidth, imageHeight, data); // todo: potential npe
        }
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        DrawBitmapInt.apply(
                mBuffer,
                imageId,
                srcLeft,
                srcTop,
                srcRight,
                srcBottom,
                dstLeft,
                dstTop,
                dstRight,
                dstBottom,
                contentDescriptionId);
    }

    /**
     * look up map and return the id of the object looked up
     *
     * @param mapId the map to access
     * @param strId the string to lookup
     * @return id containing the result of the lookup
     */
    public int mapLookup(int mapId, int strId) {
        int hash = mapId + strId * 33;
        int id = mRemoteComposeState.dataGetId(hash);
        if (id == -1) {
            id = mRemoteComposeState.cacheData(hash);
            DataMapLookup.apply(mBuffer, id, mapId, strId);
        }
        return id;
    }

    /**
     * Adds a text string data to the stream and returns its id Will be used to insert string with
     * bitmaps etc.
     *
     * @param text the string to inject in the buffer
     */
    public int addText(@NonNull String text) {
        int id = mRemoteComposeState.dataGetId(text);
        if (id == -1) {
            id = mRemoteComposeState.cacheData(text);
            TextData.apply(mBuffer, id, text);
        }
        return id;
    }

    /**
     * Add a click area to the document
     *
     * @param id the id of the click area, reported in the click listener callback
     * @param contentDescription the content description of that click area (accessibility)
     * @param left left coordinate of the area bounds
     * @param top top coordinate of the area bounds
     * @param right right coordinate of the area bounds
     * @param bottom bottom coordinate of the area bounds
     * @param metadata associated metadata, user-provided
     */
    public void addClickArea(
            int id,
            @Nullable String contentDescription,
            float left,
            float top,
            float right,
            float bottom,
            @Nullable String metadata) {
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        int metadataId = 0;
        if (metadata != null) {
            metadataId = addText(metadata);
        }
        ClickArea.apply(mBuffer, id, contentDescriptionId, left, top, right, bottom, metadataId);
    }

    /**
     * Sets the way the player handles the content
     *
     * @param scroll set the horizontal behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     * @param alignment set the alignment of the content (TOP|CENTER|BOTTOM|START|END)
     * @param sizing set the type of sizing for the content (NONE|SIZING_LAYOUT|SIZING_SCALE)
     * @param mode set the mode of sizing, either LAYOUT modes or SCALE modes the LAYOUT modes are:
     *     - LAYOUT_MATCH_PARENT - LAYOUT_WRAP_CONTENT or adding an horizontal mode and a vertical
     *     mode: - LAYOUT_HORIZONTAL_MATCH_PARENT - LAYOUT_HORIZONTAL_WRAP_CONTENT -
     *     LAYOUT_HORIZONTAL_FIXED - LAYOUT_VERTICAL_MATCH_PARENT - LAYOUT_VERTICAL_WRAP_CONTENT -
     *     LAYOUT_VERTICAL_FIXED The LAYOUT_*_FIXED modes will use the intrinsic document size
     */
    public void setRootContentBehavior(int scroll, int alignment, int sizing, int mode) {
        RootContentBehavior.apply(mBuffer, scroll, alignment, sizing, mode);
    }

    /**
     * add Drawing the specified arc, which will be scaled to fit inside the specified oval. <br>
     * If the start angle is negative or >= 360, the start angle is treated as start angle modulo
     * 360. <br>
     * If the sweep angle is >= 360, then the oval is drawn completely. Note that this differs
     * slightly from SkPath::arcTo, which treats the sweep angle modulo 360. If the sweep angle is
     * negative, the sweep angle is treated as sweep angle modulo 360 <br>
     * The arc is drawn clockwise. An angle of 0 degrees correspond to the geometric angle of 0
     * degrees (3 o'clock on a watch.) <br>
     *
     * @param left left coordinate of oval used to define the shape and size of the arc
     * @param top top coordinate of oval used to define the shape and size of the arc
     * @param right right coordinate of oval used to define the shape and size of the arc
     * @param bottom bottom coordinate of oval used to define the shape and size of the arc
     * @param startAngle Starting angle (in degrees) where the arc begins
     * @param sweepAngle Sweep angle (in degrees) measured clockwise
     */
    public void addDrawArc(
            float left, float top, float right, float bottom, float startAngle, float sweepAngle) {
        DrawArc.apply(mBuffer, left, top, right, bottom, startAngle, sweepAngle);
    }

    /**
     * add Drawing the specified sector, which will be scaled to fit inside the specified oval. <br>
     * If the start angle is negative or >= 360, the start angle is treated as start angle modulo
     * 360. <br>
     * If the sweep angle is >= 360, then the oval is drawn completely. Note that this differs
     * slightly from SkPath::arcTo, which treats the sweep angle modulo 360. If the sweep angle is
     * negative, the sweep angle is treated as sweep angle modulo 360 <br>
     * The arc is drawn clockwise. An angle of 0 degrees correspond to the geometric angle of 0
     * degrees (3 o'clock on a watch.) <br>
     *
     * @param left left coordinate of oval used to define the shape and size of the arc
     * @param top top coordinate of oval used to define the shape and size of the arc
     * @param right right coordinate of oval used to define the shape and size of the arc
     * @param bottom bottom coordinate of oval used to define the shape and size of the arc
     * @param startAngle Starting angle (in degrees) where the arc begins
     * @param sweepAngle Sweep angle (in degrees) measured clockwise
     */
    public void addDrawSector(
            float left, float top, float right, float bottom, float startAngle, float sweepAngle) {
        DrawSector.apply(mBuffer, left, top, right, bottom, startAngle, sweepAngle);
    }

    /**
     * @param image The bitmap to be drawn
     * @param left left coordinate of rectangle that the bitmap will be to fit into
     * @param top top coordinate of rectangle that the bitmap will be to fit into
     * @param right right coordinate of rectangle that the bitmap will be to fit into
     * @param bottom bottom coordinate of rectangle that the bitmap will be to fit into
     * @param contentDescription content description of the image
     */
    public void addDrawBitmap(
            @NonNull Object image,
            float left,
            float top,
            float right,
            float bottom,
            @Nullable String contentDescription) {
        int imageId = mRemoteComposeState.dataGetId(image);
        if (imageId == -1) {
            imageId = mRemoteComposeState.cacheData(image);
            byte[] data = mPlatform.imageToByteArray(image); // todo: potential npe
            int imageWidth = mPlatform.getImageWidth(image);
            int imageHeight = mPlatform.getImageHeight(image);

            BitmapData.apply(
                    mBuffer, imageId, imageWidth, imageHeight, data); // todo: potential npe
        }
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        DrawBitmap.apply(mBuffer, imageId, left, top, right, bottom, contentDescriptionId);
    }

    /**
     * @param imageId The Id bitmap to be drawn
     * @param left left coordinate of rectangle that the bitmap will be to fit into
     * @param top top coordinate of rectangle that the bitmap will be to fit into
     * @param right right coordinate of rectangle that the bitmap will be to fit into
     * @param bottom bottom coordinate of rectangle that the bitmap will be to fit into
     * @param contentDescription content description of the image
     */
    public void addDrawBitmap(
            int imageId,
            float left,
            float top,
            float right,
            float bottom,
            @Nullable String contentDescription) {
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        DrawBitmap.apply(mBuffer, imageId, left, top, right, bottom, contentDescriptionId);
    }

    /**
     * @param image The bitmap to be drawn
     * @param srcLeft left coordinate in the source bitmap will be to extracted
     * @param srcTop top coordinate in the source bitmap will be to extracted
     * @param srcRight right coordinate in the source bitmap will be to extracted
     * @param srcBottom bottom coordinate in the source bitmap will be to extracted
     * @param dstLeft left coordinate of rectangle that the bitmap will be to fit into
     * @param dstTop top coordinate of rectangle that the bitmap will be to fit into
     * @param dstRight right coordinate of rectangle that the bitmap will be to fit into
     * @param dstBottom bottom coordinate of rectangle that the bitmap will be to fit into
     * @param scaleType The type of scaling to allow the image to fit.
     * @param scaleFactor the scale factor when scale type is FIXED_SCALE (type = 7)
     * @param contentDescription associate a string with image for accessibility
     */
    public void drawScaledBitmap(
            @NonNull Object image,
            float srcLeft,
            float srcTop,
            float srcRight,
            float srcBottom,
            float dstLeft,
            float dstTop,
            float dstRight,
            float dstBottom,
            int scaleType,
            float scaleFactor,
            @Nullable String contentDescription) {
        int imageId = mRemoteComposeState.dataGetId(image);
        if (imageId == -1) {
            imageId = mRemoteComposeState.cacheData(image);
            byte[] data = mPlatform.imageToByteArray(image); // todo: potential npe
            int imageWidth = mPlatform.getImageWidth(image);
            int imageHeight = mPlatform.getImageHeight(image);

            BitmapData.apply(mBuffer, imageId, imageWidth, imageHeight, data); // todo: potential pe
        }
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        DrawBitmapScaled.apply(
                mBuffer,
                imageId,
                srcLeft,
                srcTop,
                srcRight,
                srcBottom,
                dstLeft,
                dstTop,
                dstRight,
                dstBottom,
                scaleType,
                scaleFactor,
                contentDescriptionId);
    }

    /**
     * Transmit bitmap so the you can use the id form. This is useful if
     *
     * @param image drawScaledBitmap
     * @return id of the image useful with
     */
    public int addBitmap(@NonNull Object image) {
        int imageId = mRemoteComposeState.dataGetId(image);
        if (imageId == -1) {
            imageId = mRemoteComposeState.cacheData(image);
            byte[] data = mPlatform.imageToByteArray(image); // tODO: potential npe
            int imageWidth = mPlatform.getImageWidth(image);
            int imageHeight = mPlatform.getImageHeight(image);

            BitmapData.apply(mBuffer, imageId, imageWidth, imageHeight, data);
        }
        return imageId;
    }

    /**
     * Transmit bitmap so the you can use the id form. This is useful if
     *
     * @param image drawScaledBitmap
     * @return id of the image useful with
     */
    public int addBitmap(@NonNull Object image, @NonNull String name) {
        int imageId = mRemoteComposeState.dataGetId(image);
        if (imageId == -1) {
            imageId = mRemoteComposeState.cacheData(image);
            byte[] data = mPlatform.imageToByteArray(image); // todo: potential npe
            int imageWidth = mPlatform.getImageWidth(image);
            int imageHeight = mPlatform.getImageHeight(image);

            BitmapData.apply(mBuffer, imageId, imageWidth, imageHeight, data);
            setBitmapName(imageId, name);
        }

        return imageId;
    }

    /**
     * This defines the name of the color given the id.
     *
     * @param id of the Bitmap
     * @param name Name of the color
     */
    public void setBitmapName(int id, @NonNull String name) {
        NamedVariable.apply(mBuffer, id, NamedVariable.IMAGE_TYPE, name);
    }

    /**
     * @param imageId The id of the bitmap to be drawn
     * @param srcLeft left coordinate in the source bitmap will be to extracted
     * @param srcTop top coordinate in the source bitmap will be to extracted
     * @param srcRight right coordinate in the source bitmap will be to extracted
     * @param srcBottom bottom coordinate in the source bitmap will be to extracted
     * @param dstLeft left coordinate of rectangle that the bitmap will be to fit into
     * @param dstTop top coordinate of rectangle that the bitmap will be to fit into
     * @param dstRight right coordinate of rectangle that the bitmap will be to fit into
     * @param dstBottom bottom coordinate of rectangle that the bitmap will be to fit into
     * @param scaleType The type of scaling to allow the image to fit.
     * @param scaleFactor the scale factor when scale type is FIXED_SCALE (type = 7)
     * @param contentDescription associate a string with image for accessibility
     */
    public void drawScaledBitmap(
            int imageId,
            float srcLeft,
            float srcTop,
            float srcRight,
            float srcBottom,
            float dstLeft,
            float dstTop,
            float dstRight,
            float dstBottom,
            int scaleType,
            float scaleFactor,
            @Nullable String contentDescription) {
        int contentDescriptionId = 0;
        if (contentDescription != null) {
            contentDescriptionId = addText(contentDescription);
        }
        DrawBitmapScaled.apply(
                mBuffer,
                imageId,
                srcLeft,
                srcTop,
                srcRight,
                srcBottom,
                dstLeft,
                dstTop,
                dstRight,
                dstBottom,
                scaleType,
                scaleFactor,
                contentDescriptionId);
    }

    /**
     * Draw the specified circle using the specified paint. If radius is <= 0, then nothing will be
     * drawn.
     *
     * @param centerX The x-coordinate of the center of the circle to be drawn
     * @param centerY The y-coordinate of the center of the circle to be drawn
     * @param radius The radius of the circle to be drawn
     */
    public void addDrawCircle(float centerX, float centerY, float radius) {
        DrawCircle.apply(mBuffer, centerX, centerY, radius);
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
        DrawLine.apply(mBuffer, x1, y1, x2, y2);
    }

    /**
     * Draw the specified oval using the specified paint.
     *
     * @param left left coordinate of oval
     * @param top top coordinate of oval
     * @param right right coordinate of oval
     * @param bottom bottom coordinate of oval
     */
    public void addDrawOval(float left, float top, float right, float bottom) {
        DrawOval.apply(mBuffer, left, top, right, bottom);
    }

    /**
     * Draw the specified path
     *
     * <p>Note: path objects are not immutable modifying them and calling this will not change the
     * drawing
     *
     * @param path The path to be drawn
     */
    public void addDrawPath(@NonNull Object path) {
        int id = mRemoteComposeState.dataGetId(path);
        if (id == -1) { // never been seen before
            id = addPathData(path);
        }
        addDrawPath(id);
    }

    /**
     * interpolate the two paths to produce a 3rd
     *
     * @param pid1 the first path
     * @param pid2 the second path
     * @param tween path is the path1+(pat2-path1)*tween
     * @return id of the tweened path
     */
    public int pathTween(int pid1, int pid2, float tween) {
        int out = mRemoteComposeState.nextId();
        PathTween.apply(mBuffer, out, pid1, pid2, tween);
        return out;
    }

    /**
     * Create a path with an initial moveTo
     *
     * @param x x coordinate of the moveto
     * @param y y coordinate of the moveto
     * @return id of the created path
     */
    public int pathCreate(float x, float y) {
        int out = mRemoteComposeState.nextId();
        PathCreate.apply(mBuffer, out, x, y);
        return out;
    }

    public void pathAppend(int id, float... path) {
        PathAppend.apply(mBuffer, id, path);
    }

    /**
     * Draw the specified path
     *
     * @param pathId
     */
    public void addDrawPath(int pathId) {
        DrawPath.apply(mBuffer, pathId);
    }

    /**
     * Draw the specified Rect
     *
     * @param left left coordinate of rectangle to be drawn
     * @param top top coordinate of rectangle to be drawn
     * @param right right coordinate of rectangle to be drawn
     * @param bottom bottom coordinate of rectangle to be drawn
     */
    public void addDrawRect(float left, float top, float right, float bottom) {
        DrawRect.apply(mBuffer, left, top, right, bottom);
    }

    /**
     * Draw the specified round-rect
     *
     * @param left left coordinate of rectangle to be drawn
     * @param top left coordinate of rectangle to be drawn
     * @param right left coordinate of rectangle to be drawn
     * @param bottom left coordinate of rectangle to be drawn
     * @param radiusX The x-radius of the oval used to round the corners
     * @param radiusY The y-radius of the oval used to round the corners
     */
    public void addDrawRoundRect(
            float left, float top, float right, float bottom, float radiusX, float radiusY) {
        DrawRoundRect.apply(mBuffer, left, top, right, bottom, radiusX, radiusY);
    }

    /**
     * Draw the text, with origin at (x,y) along the specified path.
     *
     * @param text The text to be drawn
     * @param path The path the text should follow for its baseline
     * @param hOffset The distance along the path to add to the text's starting position
     * @param vOffset The distance above(-) or below(+) the path to position the text
     */
    public void addDrawTextOnPath(
            @NonNull String text, @NonNull Object path, float hOffset, float vOffset) {
        int pathId = mRemoteComposeState.dataGetId(path);
        if (pathId == -1) { // never been seen before
            pathId = addPathData(path);
        }
        int textId = addText(text);
        DrawTextOnPath.apply(mBuffer, textId, pathId, hOffset, vOffset);
    }

    /**
     * Draw the text, with origin at (x,y). The origin is interpreted based on the Align setting in
     * the paint.
     *
     * @param text The text to be drawn
     * @param start The index of the first character in text to draw
     * @param end (end - 1) is the index of the last character in text to draw
     * @param contextStart
     * @param contextEnd
     * @param x The x-coordinate of the origin of the text being drawn
     * @param y The y-coordinate of the baseline of the text being drawn
     * @param rtl Draw RTTL
     */
    public void addDrawTextRun(
            @NonNull String text,
            int start,
            int end,
            int contextStart,
            int contextEnd,
            float x,
            float y,
            boolean rtl) {
        int textId = addText(text);
        DrawText.apply(mBuffer, textId, start, end, contextStart, contextEnd, x, y, rtl);
    }

    /**
     * Draw the text, with origin at (x,y). The origin is interpreted based on the Align setting in
     * the paint.
     *
     * @param textId The text to be drawn
     * @param start The index of the first character in text to draw
     * @param end (end - 1) is the index of the last character in text to draw
     * @param contextStart
     * @param contextEnd
     * @param x The x-coordinate of the origin of the text being drawn
     * @param y The y-coordinate of the baseline of the text being drawn
     * @param rtl Draw RTTL
     */
    public void addDrawTextRun(
            int textId,
            int start,
            int end,
            int contextStart,
            int contextEnd,
            float x,
            float y,
            boolean rtl) {
        DrawText.apply(mBuffer, textId, start, end, contextStart, contextEnd, x, y, rtl);
    }

    /**
     * Draw a text on canvas at relative to position (x, y), offset panX and panY. <br>
     * The panning factors (panX, panY) mapped to the resulting bounding box of the text, in such a
     * way that a panning factor of (0.0, 0.0) would center the text at (x, y)
     *
     * <ul>
     *   <li>Panning of -1.0, -1.0 - the text above & right of x,y.
     *   <li>Panning of 1.0, 1.0 - the text is below and to the left
     *   <li>Panning of 1.0, 0.0 - the test is centered & to the right of x,y
     * </ul>
     *
     * <p>Setting panY to NaN results in y being the baseline of the text.
     *
     * @param text text to draw
     * @param x Coordinate of the Anchor
     * @param y Coordinate of the Anchor
     * @param panX justifies text -1.0=right, 0.0=center, 1.0=left
     * @param panY position text -1.0=above, 0.0=center, 1.0=below, Nan=baseline
     * @param flags 1 = RTL
     */
    public void drawTextAnchored(
            @NonNull String text, float x, float y, float panX, float panY, int flags) {
        int textId = addText(text);
        DrawTextAnchored.apply(mBuffer, textId, x, y, panX, panY, flags);
    }

    /**
     * Add a text and id so that it can be used
     *
     * @param text
     * @return
     */
    public int createTextId(@NonNull String text) {
        return addText(text);
    }

    /**
     * Merge two text (from id's) output one id
     *
     * @param id1 left id
     * @param id2 right id
     * @return new id that merges the two text
     */
    public int textMerge(int id1, int id2) {
        int textId = addText(id1 + "+" + id2);
        TextMerge.apply(mBuffer, textId, id1, id2);
        return textId;
    }

    public static final int PAD_AFTER_SPACE = TextFromFloat.PAD_AFTER_SPACE;
    public static final int PAD_AFTER_NONE = TextFromFloat.PAD_AFTER_NONE;
    public static final int PAD_AFTER_ZERO = TextFromFloat.PAD_AFTER_ZERO;
    public static final int PAD_PRE_SPACE = TextFromFloat.PAD_PRE_SPACE;
    public static final int PAD_PRE_NONE = TextFromFloat.PAD_PRE_NONE;
    public static final int PAD_PRE_ZERO = TextFromFloat.PAD_PRE_ZERO;

    /**
     * Create a TextFromFloat command which creates text from a Float.
     *
     * @param value The value to convert
     * @param digitsBefore the digits before the decimal point
     * @param digitsAfter the digits after the decimal point
     * @param flags configure the behaviour using PAD_PRE_* and PAD_AFTER* flags
     * @return id of the string that can be passed to drawTextAnchored
     */
    public int createTextFromFloat(float value, short digitsBefore, short digitsAfter, int flags) {
        String placeHolder =
                Utils.floatToString(value)
                        + "("
                        + digitsBefore
                        + ","
                        + digitsAfter
                        + ","
                        + flags
                        + ")";
        int id = mRemoteComposeState.dataGetId(placeHolder);
        if (id == -1) {
            id = mRemoteComposeState.cacheData(placeHolder);
            //   TextData.apply(mBuffer, id, text);
        }
        TextFromFloat.apply(mBuffer, id, value, digitsBefore, digitsAfter, flags);
        return id;
    }

    /**
     * Draw a text on canvas at relative to position (x, y), offset panX and panY. <br>
     * The panning factors (panX, panY) mapped to the resulting bounding box of the text, in such a
     * way that a panning factor of (0.0, 0.0) would center the text at (x, y)
     *
     * <ul>
     *   <li>Panning of -1.0, -1.0 - the text above & right of x,y.
     *   <li>Panning of 1.0, 1.0 - the text is below and to the left
     *   <li>Panning of 1.0, 0.0 - the test is centered & to the right of x,y
     * </ul>
     *
     * <p>Setting panY to NaN results in y being the baseline of the text.
     *
     * @param textId text to draw
     * @param x Coordinate of the Anchor
     * @param y Coordinate of the Anchor
     * @param panX justifies text -1.0=right, 0.0=center, 1.0=left
     * @param panY position text -1.0=above, 0.0=center, 1.0=below, Nan=baseline
     * @param flags 1 = RTL
     */
    public void drawTextAnchored(int textId, float x, float y, float panX, float panY, int flags) {

        DrawTextAnchored.apply(mBuffer, textId, x, y, panX, panY, flags);
    }

    /**
     * draw an interpolation between two paths that have the same pattern
     *
     * <p>Warning paths objects are not immutable and this is not taken into consideration
     *
     * @param path1 The path1 to be drawn between
     * @param path2 The path2 to be drawn between
     * @param tween The ratio of path1 and path2 to 0 = all path 1, 1 = all path2
     * @param start The start of the subrange of paths to draw 0 = start form start 0.5 is half way
     * @param stop The end of the subrange of paths to draw 1 = end at the end 0.5 is end half way
     */
    public void addDrawTweenPath(
            @NonNull Object path1, @NonNull Object path2, float tween, float start, float stop) {
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
     * @param tween The ratio of path1 and path2 to 0 = all path 1, 1 = all path2
     * @param start The start of the subrange of paths to draw 0 = start form start .5 is 1/2 way
     * @param stop The end of the subrange of paths to draw 1 = end at the end .5 is end 1/2 way
     */
    public void addDrawTweenPath(int path1Id, int path2Id, float tween, float start, float stop) {
        DrawTweenPath.apply(mBuffer, path1Id, path2Id, tween, start, stop);
    }

    /**
     * Add a path object
     *
     * @param path
     * @return the id of the path on the wire
     */
    public int addPathData(@NonNull Object path) {
        float[] pathData = mPlatform.pathToFloatArray(path);
        int id = mRemoteComposeState.cacheData(path);
        PathData.apply(mBuffer, id, pathData);
        return id;
    }

    /**
     * Adds a paint Bundle to the doc
     *
     * @param paint
     */
    public void addPaint(@NonNull PaintBundle paint) {
        PaintData.apply(mBuffer, paint);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////

    public void inflateFromBuffer(@NonNull ArrayList<Operation> operations) {
        mBuffer.setIndex(0);
        while (mBuffer.available()) {
            int opId = mBuffer.readByte();
            if (DEBUG) {
                Utils.log(">> " + opId);
            }
            CompanionOperation operation = Operations.map.get(opId);
            if (operation == null) {
                throw new RuntimeException("Unknown operation encountered " + opId);
            }
            operation.read(mBuffer, operations);
        }
    }

    public static void readNextOperation(
            @NonNull WireBuffer buffer, @NonNull ArrayList<Operation> operations) {
        int opId = buffer.readByte();
        if (DEBUG) {
            Utils.log(">> " + opId);
        }
        CompanionOperation operation = Operations.map.get(opId);
        if (operation == null) {
            throw new RuntimeException("Unknown operation encountered " + opId);
        }
        operation.read(buffer, operations);
    }

    @NonNull
    RemoteComposeBuffer copy() {
        ArrayList<Operation> operations = new ArrayList<>();
        inflateFromBuffer(operations);
        RemoteComposeBuffer buffer = new RemoteComposeBuffer(mRemoteComposeState);
        return copyFromOperations(operations, buffer);
    }

    public void setTheme(int theme) {
        Theme.apply(mBuffer, theme);
    }

    @NonNull
    static String version() {
        return "v1.0";
    }

    @NonNull
    public static RemoteComposeBuffer fromFile(
            @NonNull String path, @NonNull RemoteComposeState remoteComposeState)
            throws IOException {
        RemoteComposeBuffer buffer = new RemoteComposeBuffer(remoteComposeState);
        read(new File(path), buffer);
        return buffer;
    }

    @NonNull
    public RemoteComposeBuffer fromFile(
            @NonNull File file, @NonNull RemoteComposeState remoteComposeState) throws IOException {
        RemoteComposeBuffer buffer = new RemoteComposeBuffer(remoteComposeState);
        read(file, buffer);
        return buffer;
    }

    @NonNull
    public static RemoteComposeBuffer fromInputStream(
            @NonNull InputStream inputStream, @NonNull RemoteComposeState remoteComposeState) {
        RemoteComposeBuffer buffer = new RemoteComposeBuffer(remoteComposeState);
        read(inputStream, buffer);
        return buffer;
    }

    @NonNull
    RemoteComposeBuffer copyFromOperations(
            @NonNull ArrayList<Operation> operations, @NonNull RemoteComposeBuffer buffer) {

        for (Operation operation : operations) {
            operation.write(buffer.mBuffer);
        }
        return buffer;
    }

    /**
     * Write the given RemoteComposeBuffer to the given file
     *
     * @param buffer a RemoteComposeBuffer
     * @param file a target file
     */
    public void write(@NonNull RemoteComposeBuffer buffer, @NonNull File file) {
        try {
            FileOutputStream fd = new FileOutputStream(file);
            fd.write(buffer.mBuffer.getBuffer(), 0, buffer.mBuffer.getSize());
            fd.flush();
            fd.close();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static void read(@NonNull File file, @NonNull RemoteComposeBuffer buffer) throws IOException {
        FileInputStream fd = new FileInputStream(file);
        read(fd, buffer);
    }

    public static void read(@NonNull InputStream fd, @NonNull RemoteComposeBuffer buffer) {
        try {
            byte[] bytes = readAllBytes(fd);
            buffer.reset(bytes.length);
            System.arraycopy(bytes, 0, buffer.mBuffer.mBuffer, 0, bytes.length);
            buffer.mBuffer.mSize = bytes.length;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] readAllBytes(@NonNull InputStream is) throws IOException {
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
        MatrixSkew.apply(mBuffer, skewX, skewY);
    }

    /**
     * This call balances a previous call to save(), and is used to remove all modifications to the
     * matrix/clip state since the last save call. Do not call restore() more times than save() was
     * called.
     */
    public void addMatrixRestore() {
        MatrixRestore.apply(mBuffer);
    }

    /**
     * Add a saves the current matrix and clip onto a private stack.
     *
     * <p>Subsequent calls to translate,scale,rotate,skew,concat or clipRect, clipPath will all
     * operate as usual, but when the balancing call to restore() is made, those calls will be
     * forgotten, and the settings that existed before the save() will be reinstated.
     */
    public void addMatrixSave() {
        MatrixSave.apply(mBuffer);
    }

    /**
     * add a pre-concat the current matrix with the specified rotation.
     *
     * @param angle The amount to rotate, in degrees
     * @param centerX The x-coord for the pivot point (unchanged by the rotation)
     * @param centerY The y-coord for the pivot point (unchanged by the rotation)
     */
    public void addMatrixRotate(float angle, float centerX, float centerY) {
        MatrixRotate.apply(mBuffer, angle, centerX, centerY);
    }

    /**
     * add a Pre-concat to the current matrix with the specified translation
     *
     * @param dx The distance to translate in X
     * @param dy The distance to translate in Y
     */
    public void addMatrixTranslate(float dx, float dy) {
        MatrixTranslate.apply(mBuffer, dx, dy);
    }

    /**
     * Add a pre-concat of the current matrix with the specified scale.
     *
     * @param scaleX The amount to scale in X
     * @param scaleY The amount to scale in Y
     */
    public void addMatrixScale(float scaleX, float scaleY) {
        MatrixScale.apply(mBuffer, scaleX, scaleY, Float.NaN, Float.NaN);
    }

    /**
     * Add a pre-concat of the current matrix with the specified scale.
     *
     * @param scaleX The amount to scale in X
     * @param scaleY The amount to scale in Y
     * @param centerX The x-coord for the pivot point (unchanged by the scale)
     * @param centerY The y-coord for the pivot point (unchanged by the scale)
     */
    public void addMatrixScale(float scaleX, float scaleY, float centerX, float centerY) {
        MatrixScale.apply(mBuffer, scaleX, scaleY, centerX, centerY);
    }

    /**
     * sets the clip based on clip id
     *
     * @param pathId 0 clears the clip
     */
    public void addClipPath(int pathId) {
        ClipPath.apply(mBuffer, pathId);
    }

    /**
     * Sets the clip based on clip rec
     *
     * @param left left coordinate of the clip rectangle
     * @param top top coordinate of the clip rectangle
     * @param right right coordinate of the clip rectangle
     * @param bottom bottom coordinate of the clip rectangle
     */
    public void addClipRect(float left, float top, float right, float bottom) {
        ClipRect.apply(mBuffer, left, top, right, bottom);
    }

    /**
     * Add a float return a NaN number pointing to that float
     *
     * @param value the value of the float
     * @return the nan id of float
     */
    public float addFloat(float value) {
        int id = mRemoteComposeState.cacheFloat(value);
        FloatConstant.apply(mBuffer, id, value);
        return Utils.asNan(id);
    }

    /**
     * Reserve a float and returns a NaN number pointing to that float
     *
     * @return the nan id of float
     */
    public float reserveFloatVariable() {
        int id = mRemoteComposeState.cacheFloat(0f);
        return Utils.asNan(id);
    }

    /**
     * Add a Integer return an id number pointing to that float.
     *
     * @param value adds an integer and assigns it an id
     * @return the id of the integer to be used
     */
    public int addInteger(int value) {
        int id = mRemoteComposeState.cacheInteger(value);
        IntegerConstant.apply(mBuffer, id, value);
        return id;
    }

    /**
     * Add a long constant return a id. They can be used as parameters to Custom Attributes.
     *
     * @param value the value of the long
     * @return the id of the command representing long
     */
    public int addLong(long value) {
        int id = mRemoteComposeState.cacheData(value);
        LongConstant.apply(mBuffer, id, value);
        return id;
    }

    /**
     * Add a boolean constant return a id. They can be used as parameters to Custom Attributes.
     *
     * @param value the value of the boolean
     * @return the id
     */
    public int addBoolean(boolean value) {
        int id = mRemoteComposeState.cacheData(value);
        BooleanConstant.apply(mBuffer, id, value);
        return id;
    }

    /**
     * Add a IntegerId as float ID.
     *
     * @param id id to be converted
     * @return the id wrapped in a NaN
     */
    public float asFloatId(int id) {
        return Utils.asNan(id);
    }

    /**
     * Add a float that is a computation based on variables
     *
     * @param value A RPN style float operation i.e. "4, 3, ADD" outputs 7
     * @return NaN id of the result of the calculation
     */
    public float addAnimatedFloat(@NonNull float... value) {
        int id = mRemoteComposeState.cacheData(value);
        FloatExpression.apply(mBuffer, id, value, null);
        return Utils.asNan(id);
    }

    /**
     * Add a touch handle system
     *
     * @param id the float NaN id used for the returned position
     * @param value the default value
     * @param min the minimum value
     * @param max the maximum value
     * @param velocityId the id for the velocity TODO support in v2
     * @param exp The Float Expression
     * @param touchMode the touch up handling behaviour
     * @param touchSpec the touch up handling parameters
     * @param easingSpec the easing parameter TODO support in v2
     */
    public void addTouchExpression(
            float id,
            float value,
            float min,
            float max,
            float velocityId,
            int touchEffects,
            float[] exp,
            int touchMode,
            float[] touchSpec,
            float[] easingSpec) {
        TouchExpression.apply(
                mBuffer,
                Utils.idFromNan(id),
                value,
                min,
                max,
                velocityId,
                touchEffects,
                exp,
                touchMode,
                touchSpec,
                easingSpec);
    }

    /**
     * Add a touch handle system
     *
     * @param value the default value
     * @param min the minimum value
     * @param max the maximum value
     * @param velocityId the id for the velocity TODO support in v2
     * @param exp The Float Expression
     * @param touchMode the touch up handling behaviour
     * @param touchSpec the touch up handling parameters
     * @param easingSpec the easing parameter TODO support in v2
     * @return id of the variable to be used controlled by touch handling
     */
    public float addTouchExpression(
            float value,
            float min,
            float max,
            float velocityId,
            int touchEffects,
            float[] exp,
            int touchMode,
            float[] touchSpec,
            float[] easingSpec) {
        float id = Utils.asNan(mRemoteComposeState.nextId());
        addTouchExpression(
                id,
                value,
                min,
                max,
                velocityId,
                touchEffects,
                exp,
                touchMode,
                touchSpec,
                easingSpec);
        return id;
    }

    /**
     * Add a float that is a computation based on variables. see packAnimation
     *
     * @param value A RPN style float operation i.e. "4, 3, ADD" outputs 7
     * @param animation Array of floats that represents animation
     * @return NaN id of the result of the calculation
     */
    public float addAnimatedFloat(@NonNull float[] value, @Nullable float[] animation) {
        int id = mRemoteComposeState.cacheData(value);
        FloatExpression.apply(mBuffer, id, value, animation);
        return Utils.asNan(id);
    }

    /**
     * measure the text and return a measure as a float
     *
     * @param textId id of the text
     * @param mode the mode 0 is the width
     * @return
     */
    public float textMeasure(int textId, int mode) {
        int id = mRemoteComposeState.cacheData(textId + mode * 31);
        TextMeasure.apply(mBuffer, id, textId, mode);
        return Utils.asNan(id);
    }

    /**
     * measure the text and return the length of the text as float
     *
     * @param textId id of the text
     * @return id of a float that is the length
     */
    public float textLength(int textId) {
        // The cache id is computed buy merging the two values together
        // to create a relatively unique value
        int id = mRemoteComposeState.cacheData(textId + (TextLength.id() << 16));
        TextLength.apply(mBuffer, id, textId);
        return Utils.asNan(id);
    }

    /**
     * add a float array
     *
     * @param values
     * @return the id of the array, encoded as a float NaN
     */
    public float addFloatArray(@NonNull float[] values) {
        int id = mRemoteComposeState.cacheData(values, NanMap.TYPE_ARRAY);
        DataListFloat.apply(mBuffer, id, values);
        return Utils.asNan(id);
    }

    /**
     * This creates a list of individual floats
     *
     * @param values array of floats to be individually stored
     * @return id of the list
     */
    public float addFloatList(@NonNull float[] values) {
        int[] listId = new int[values.length];
        for (int i = 0; i < listId.length; i++) {
            listId[i] = mRemoteComposeState.cacheFloat(values[i]);
            FloatConstant.apply(mBuffer, listId[i], values[i]);
        }
        return addList(listId);
    }

    /**
     * This creates a list of individual floats
     *
     * @param listId array id to be stored
     * @return id of the list
     */
    public float addList(@NonNull int[] listId) {
        int id = mRemoteComposeState.cacheData(listId, NanMap.TYPE_ARRAY);
        DataListIds.apply(mBuffer, id, listId);
        return Utils.asNan(id);
    }

    /**
     * add a float map
     *
     * @param keys
     * @param values
     * @return the id of the map, encoded as a float NaN
     */
    public float addFloatMap(@NonNull String[] keys, @NonNull float[] values) {
        int[] listId = new int[values.length];
        byte[] type = new byte[values.length];
        for (int i = 0; i < listId.length; i++) {
            listId[i] = mRemoteComposeState.cacheFloat(values[i]);
            FloatConstant.apply(mBuffer, listId[i], values[i]);
            type[i] = DataMapIds.TYPE_FLOAT;
        }
        return addMap(keys, type, listId);
    }

    /**
     * add an int map
     *
     * @param keys
     * @param listId
     * @return the id of the map, encoded as a float NaN
     */
    public int addMap(@NonNull String[] keys, @Nullable byte[] types, @NonNull int[] listId) {
        int id = mRemoteComposeState.cacheData(listId, NanMap.TYPE_ARRAY);
        DataMapIds.apply(mBuffer, id, keys, types, listId);
        return id;
    }

    /**
     * This provides access to text in RemoteList
     *
     * <p>TODO: do we want both a float and an int index version of this method? bbade@ TODO
     * for @hoford - add a unit test for this method
     *
     * @param dataSet
     * @param index index as a float variable
     * @return
     */
    public int textLookup(float dataSet, float index) {
        long hash =
                (((long) Float.floatToRawIntBits(dataSet)) << 32)
                        + Float.floatToRawIntBits(
                                index); // TODO: is this the correct ()s? -- bbade@
        int id = mRemoteComposeState.cacheData(hash);
        TextLookup.apply(mBuffer, id, Utils.idFromNan(dataSet), index);
        return id;
    }

    /**
     * This provides access to text in RemoteList
     *
     * <p>TODO for hoford - add a unit test for this method
     *
     * @param dataSet
     * @param index index as an int variable
     * @return
     */
    public int textLookup(float dataSet, int index) {
        long hash =
                (((long) Float.floatToRawIntBits(dataSet)) << 32)
                        + Float.floatToRawIntBits(index); // TODO: is this the correct ()s?
        int id = mRemoteComposeState.cacheData(hash);
        TextLookupInt.apply(mBuffer, id, Utils.idFromNan(dataSet), index);
        return id;
    }

    /**
     * Add and integer expression
     *
     * @param mask defines which elements are operators or variables
     * @param value array of values to calculate maximum 32
     * @return the id as an integer
     */
    public int addIntegerExpression(int mask, @NonNull int[] value) {
        int id = mRemoteComposeState.cacheData(value);
        IntegerExpression.apply(mBuffer, id, mask, value);
        return id;
    }

    /**
     * Add a simple color
     *
     * @param color the RGB color value
     * @return id that represents that color
     */
    public int addColor(int color) {
        ColorConstant c = new ColorConstant(0, color);
        short id = (short) mRemoteComposeState.cacheData(c);
        c.mColorId = id;
        c.write(mBuffer);
        return id;
    }

    /**
     * Add a color that represents the tween between two colors
     *
     * @param color1 the ARGB value of the first color
     * @param color2 the ARGB value of the second color
     * @param tween the interpolation bet
     * @return id of the color (color ids are short)
     */
    public short addColorExpression(int color1, int color2, float tween) {
        ColorExpression c = new ColorExpression(0, 0, color1, color2, tween);
        short id = (short) mRemoteComposeState.cacheData(c);
        c.mId = id;
        c.write(mBuffer);
        return id;
    }

    /**
     * Add a color that represents the tween between two colors where color1 is the id of a color
     *
     * @param color1 id of color
     * @param color2 rgb color value
     * @param tween the tween between color1 and color2 (1 = color2)
     * @return id of the color (color ids are short)
     */
    public short addColorExpression(short color1, int color2, float tween) {
        ColorExpression c = new ColorExpression(0, 1, color1, color2, tween);
        short id = (short) mRemoteComposeState.cacheData(c);
        c.mId = id;
        c.write(mBuffer);
        return id;
    }

    /**
     * Add a color that represents the tween between two colors where color2 is the id of a color
     *
     * @param color1 the ARGB value of the first color
     * @param color2 id of the second color
     * @param tween the tween between color1 and color2 (1 = color2)
     * @return id of the color (color ids are short)
     */
    public short addColorExpression(int color1, short color2, float tween) {
        ColorExpression c = new ColorExpression(0, 2, color1, color2, tween);
        short id = (short) mRemoteComposeState.cacheData(c);
        c.mId = id;
        c.write(mBuffer);
        return id;
    }

    /**
     * Add a color that represents the tween between two colors where color1 & color2 are the ids of
     * colors
     *
     * @param color1 id of the first color
     * @param color2 id of the second color
     * @param tween the tween between color1 and color2 (1 = color2)
     * @return id of the color (color ids are short)
     */
    public short addColorExpression(short color1, short color2, float tween) {
        ColorExpression c = new ColorExpression(0, 3, color1, color2, tween);
        short id = (short) mRemoteComposeState.cacheData(c);
        c.mId = id;
        c.write(mBuffer);
        return id;
    }

    /**
     * Color calculated by Hue saturation and value. (as floats they can be variables used to create
     * color transitions)
     *
     * @param hue the Hue
     * @param sat the saturation
     * @param value the value
     * @return id of the color (color ids are short)
     */
    public short addColorExpression(float hue, float sat, float value) {
        ColorExpression c = new ColorExpression(0, hue, sat, value);
        short id = (short) mRemoteComposeState.cacheData(c);
        c.mId = id;
        c.write(mBuffer);
        return id;
    }

    /**
     * Color calculated by Alpha, Hue saturation and value. (as floats they can be variables used to
     * create color transitions)
     *
     * @param alpha the Alpha
     * @param hue the hue
     * @param sat the saturation
     * @param value the value
     * @return id of the color (color ids are short)
     */
    public short addColorExpression(int alpha, float hue, float sat, float value) {
        ColorExpression c = new ColorExpression(0, alpha, hue, sat, value);
        short id = (short) mRemoteComposeState.cacheData(c);
        c.mId = id;
        c.write(mBuffer);
        return id;
    }

    /**
     * create and animation based on description and return as an array of floats. see
     * addAnimatedFloat
     *
     * @param duration the duration of the animation in seconds
     * @param type the type of animation
     * @param spec the parameters of the animation if any
     * @param initialValue the initial value if it animates to a start
     * @param wrap the wraps value so (e.g 360 so angles 355 would animate to 5)
     * @return
     */
    public static @NonNull float[] packAnimation(
            float duration, int type, @Nullable float[] spec, float initialValue, float wrap) {

        return FloatAnimation.packToFloatArray(duration, type, spec, initialValue, wrap);
    }

    /**
     * This defines the name of the color given the id.
     *
     * @param id of the color
     * @param name Name of the color
     */
    public void setColorName(int id, @NonNull String name) {
        NamedVariable.apply(mBuffer, id, NamedVariable.COLOR_TYPE, name);
    }

    /**
     * This defines the name of the string given the id
     *
     * @param id of the string
     * @param name name of the string
     */
    public void setStringName(int id, @NonNull String name) {
        NamedVariable.apply(mBuffer, id, NamedVariable.STRING_TYPE, name);
    }

    /**
     * Returns a usable component id -- either the one passed in parameter if not -1 or a generated
     * one.
     *
     * @param id the current component id (if -1, we'll generate a new one)
     * @return a usable component id
     */
    private int getComponentId(int id) {
        int resolvedId = 0;
        if (id != -1) {
            resolvedId = id;
        } else {
            mGeneratedComponentId--;
            resolvedId = mGeneratedComponentId;
        }
        return resolvedId;
    }

    /**
     * Add a component start tag
     *
     * @param type type of component
     * @param id component id
     */
    public void addComponentStart(int type, int id) {
        mLastComponentId = getComponentId(id);
        ComponentStart.apply(mBuffer, type, mLastComponentId, 0f, 0f);
    }

    /**
     * Add a component start tag
     *
     * @param type type of component
     */
    public void addComponentStart(int type) {
        addComponentStart(type, -1);
    }

    /** Add a component end tag */
    public void addComponentEnd() {
        ComponentEnd.apply(mBuffer);
    }

    /**
     * Add a scroll modifier
     *
     * @param direction HORIZONTAL(0) or VERTICAL(1)
     * @param positionId the position id as a NaN
     * @param notches
     */
    public void addModifierScroll(int direction, float positionId, int notches) {
        // TODO: add support for non-notch behaviors etc.
        float max = this.reserveFloatVariable();
        float notchMax = this.reserveFloatVariable();
        float touchExpressionDirection =
                direction != 0 ? RemoteContext.FLOAT_TOUCH_POS_X : RemoteContext.FLOAT_TOUCH_POS_Y;

        ScrollModifierOperation.apply(mBuffer, direction, positionId, max, notchMax);

        this.addTouchExpression(
                positionId,
                0f,
                0f,
                max,
                0f,
                3,
                new float[] {
                    touchExpressionDirection, -1, MUL,
                },
                TouchExpression.STOP_NOTCHES_EVEN,
                new float[] {notches, notchMax},
                null);

        OperationsListEnd.apply(mBuffer);
    }

    /**
     * Add a background modifier of provided color
     *
     * @param color the color of the background
     * @param shape the background shape -- SHAPE_RECTANGLE, SHAPE_CIRCLE
     */
    public void addModifierBackground(int color, int shape) {
        float r = (color >> 16 & 0xff) / 255.0f;
        float g = (color >> 8 & 0xff) / 255.0f;
        float b = (color & 0xff) / 255.0f;
        float a = (color >> 24 & 0xff) / 255.0f;
        BackgroundModifierOperation.apply(mBuffer, 0f, 0f, 0f, 0f, r, g, b, a, shape);
    }

    /**
     * Add a border modifier
     *
     * @param borderWidth the border width
     * @param borderRoundedCorner the rounded corner radius if the shape is ROUNDED_RECT
     * @param color the color of the border
     * @param shape the shape of the border
     */
    public void addModifierBorder(
            float borderWidth, float borderRoundedCorner, int color, int shape) {
        float r = (color >> 16 & 0xff) / 255.0f;
        float g = (color >> 8 & 0xff) / 255.0f;
        float b = (color & 0xff) / 255.0f;
        float a = (color >> 24 & 0xff) / 255.0f;
        BorderModifierOperation.apply(
                mBuffer, 0f, 0f, 0f, 0f, borderWidth, borderRoundedCorner, r, g, b, a, shape);
    }

    /**
     * Add a padding modifier
     *
     * @param left left padding
     * @param top top padding
     * @param right right padding
     * @param bottom bottom padding
     */
    public void addModifierPadding(float left, float top, float right, float bottom) {
        PaddingModifierOperation.apply(mBuffer, left, top, right, bottom);
    }

    /**
     * Add an offset modifier
     *
     * @param x x offset
     * @param y y offset
     */
    public void addModifierOffset(float x, float y) {
        OffsetModifierOperation.apply(mBuffer, x, y);
    }

    /**
     * Add a zIndex modifier
     *
     * @param value z-Index value
     */
    public void addModifierZIndex(float value) {
        ZIndexModifierOperation.apply(mBuffer, value);
    }

    /** Add a ripple effect on touch down as a modifier */
    public void addModifierRipple() {
        RippleModifierOperation.apply(mBuffer);
    }

    /**
     * Add a marquee modifier
     *
     * @param iterations
     * @param animationMode
     * @param repeatDelayMillis
     * @param initialDelayMillis
     * @param spacing
     * @param velocity
     */
    public void addModifierMarquee(
            int iterations,
            int animationMode,
            float repeatDelayMillis,
            float initialDelayMillis,
            float spacing,
            float velocity) {
        MarqueeModifierOperation.apply(
                mBuffer,
                iterations,
                animationMode,
                repeatDelayMillis,
                initialDelayMillis,
                spacing,
                velocity);
    }

    /**
     * Add a graphics layer
     *
     * @param scaleX
     * @param scaleY
     * @param rotationX
     * @param rotationY
     * @param rotationZ
     * @param shadowElevation
     * @param transformOriginX
     * @param transformOriginY
     */
    public void addModifierGraphicsLayer(
            float scaleX,
            float scaleY,
            float rotationX,
            float rotationY,
            float rotationZ,
            float shadowElevation,
            float transformOriginX,
            float transformOriginY,
            float alpha,
            float cameraDistance,
            int blendMode,
            int spotShadowColorId,
            int ambientShadowColorId,
            int colorFilterId,
            int renderEffectId) {
        GraphicsLayerModifierOperation.apply(
                mBuffer,
                scaleX,
                scaleY,
                rotationX,
                rotationY,
                rotationZ,
                shadowElevation,
                transformOriginX,
                transformOriginY,
                alpha,
                cameraDistance,
                blendMode,
                spotShadowColorId,
                ambientShadowColorId,
                colorFilterId,
                renderEffectId);
    }

    /**
     * Sets the clip based on rounded clip rect
     *
     * @param topStart
     * @param topEnd
     * @param bottomStart
     * @param bottomEnd
     */
    public void addRoundClipRectModifier(
            float topStart, float topEnd, float bottomStart, float bottomEnd) {
        RoundedClipRectModifierOperation.apply(mBuffer, topStart, topEnd, bottomStart, bottomEnd);
    }

    /** Add a clip rect modifier */
    public void addClipRectModifier() {
        ClipRectModifierOperation.apply(mBuffer);
    }

    public void addLoopStart(int indexId, float from, float step, float until) {
        LoopOperation.apply(mBuffer, indexId, from, step, until);
    }

    public void addLoopEnd() {
        LoopEnd.apply(mBuffer);
    }

    public void addStateLayout(
            int componentId, int animationId, int horizontal, int vertical, int indexId) {
        mLastComponentId = getComponentId(componentId);
        StateLayout.apply(mBuffer, mLastComponentId, animationId, horizontal, vertical, indexId);
    }

    /**
     * Add a box start tag
     *
     * @param componentId component id
     * @param animationId animation id
     * @param horizontal horizontal alignment
     * @param vertical vertical alignment
     */
    public void addBoxStart(int componentId, int animationId, int horizontal, int vertical) {
        mLastComponentId = getComponentId(componentId);
        BoxLayout.apply(mBuffer, mLastComponentId, animationId, horizontal, vertical);
    }

    /**
     * Add a row start tag
     *
     * @param componentId component id
     * @param animationId animation id
     * @param horizontal horizontal alignment
     * @param vertical vertical alignment
     * @param spacedBy spacing between items
     */
    public void addRowStart(
            int componentId, int animationId, int horizontal, int vertical, float spacedBy) {
        mLastComponentId = getComponentId(componentId);
        RowLayout.apply(mBuffer, mLastComponentId, animationId, horizontal, vertical, spacedBy);
    }

    /**
     * Add a column start tag
     *
     * @param componentId component id
     * @param animationId animation id
     * @param horizontal horizontal alignment
     * @param vertical vertical alignment
     * @param spacedBy spacing between items
     */
    public void addColumnStart(
            int componentId, int animationId, int horizontal, int vertical, float spacedBy) {
        mLastComponentId = getComponentId(componentId);
        ColumnLayout.apply(mBuffer, mLastComponentId, animationId, horizontal, vertical, spacedBy);
    }

    /**
     * Add a canvas start tag
     *
     * @param componentId component id
     * @param animationId animation id
     */
    public void addCanvasStart(int componentId, int animationId) {
        mLastComponentId = getComponentId(componentId);
        CanvasLayout.apply(mBuffer, mLastComponentId, animationId);
    }

    /**
     * Add a canvas content start tag
     *
     * @param componentId component id
     */
    public void addCanvasContentStart(int componentId) {
        mLastComponentId = getComponentId(componentId);
        CanvasContent.apply(mBuffer, mLastComponentId);
    }

    /** Add a root start tag */
    public void addRootStart() {
        mLastComponentId = getComponentId(-1);
        RootLayoutComponent.apply(mBuffer, mLastComponentId);
    }

    /** Add a content start tag */
    public void addContentStart() {
        mLastComponentId = getComponentId(-1);
        LayoutComponentContent.apply(mBuffer, mLastComponentId);
    }

    /**
     * Add a component width value
     *
     * @param id id of the value
     */
    public void addComponentWidthValue(int id) {
        ComponentValue.apply(mBuffer, ComponentValue.WIDTH, mLastComponentId, id);
    }

    /**
     * Add a component height value
     *
     * @param id id of the value
     */
    public void addComponentHeightValue(int id) {
        ComponentValue.apply(mBuffer, ComponentValue.HEIGHT, mLastComponentId, id);
    }

    /**
     * Add a text component start tag
     *
     * @param componentId component id
     * @param animationId animation id
     * @param textId id of the text
     * @param color color of the text
     * @param fontSize font size
     * @param fontStyle font style (0 : Normal, 1 : Italic)
     * @param fontWeight font weight (1 to 1000, normal is 400)
     * @param fontFamily font family or null
     */
    public void addTextComponentStart(
            int componentId,
            int animationId,
            int textId,
            int color,
            float fontSize,
            int fontStyle,
            float fontWeight,
            @Nullable String fontFamily,
            int textAlign) {
        mLastComponentId = getComponentId(componentId);
        int fontFamilyId = -1;
        if (fontFamily != null) {
            fontFamilyId = addText(fontFamily);
        }
        TextLayout.apply(
                mBuffer,
                mLastComponentId,
                animationId,
                textId,
                color,
                fontSize,
                fontStyle,
                fontWeight,
                fontFamilyId,
                textAlign);
    }

    public int createID(int type) {
        return mRemoteComposeState.nextId(type);
    }

    public int nextId() {
        return mRemoteComposeState.nextId();
    }
}
