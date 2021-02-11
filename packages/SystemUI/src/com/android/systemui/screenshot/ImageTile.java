/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.screenshot;

import static android.graphics.ColorSpace.Named.SRGB;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.media.Image;

/**
 * Holds a hardware image, coordinates and render node to draw the tile. The tile manages clipping
 * and dimensions. The tile must be drawn translated to the correct target position:
 * <pre>
 *     ImageTile tile = getTile();
 *     canvas.save();
 *     canvas.translate(tile.getLeft(), tile.getTop());
 *     canvas.drawRenderNode(tile.getDisplayList());
 *     canvas.restore();
 * </pre>
 */
class ImageTile implements AutoCloseable {
    private final Image mImage;
    private final Rect mLocation;
    private RenderNode mNode;

    private static final ColorSpace COLOR_SPACE = ColorSpace.get(SRGB);

    /**
     * Create an image tile from the given image.
     *
     * @param image an image containing a hardware buffer
     * @param location the captured area represented by image tile (virtual coordinates)
     */
    ImageTile(@NonNull Image image, @NonNull Rect location) {
        mImage = requireNonNull(image, "image");
        mLocation = requireNonNull(location);
        requireNonNull(mImage.getHardwareBuffer(), "image must be a hardware image");
    }

    synchronized RenderNode getDisplayList() {
        if (mNode == null) {
            mNode = new RenderNode("Tile{" + Integer.toHexString(mImage.hashCode()) + "}");
        }
        if (mNode.hasDisplayList()) {
            return mNode;
        }
        final int w = Math.min(mImage.getWidth(), mLocation.width());
        final int h = Math.min(mImage.getHeight(), mLocation.height());
        mNode.setPosition(0, 0, w, h);

        RecordingCanvas canvas = mNode.beginRecording(w, h);
        canvas.save();
        canvas.clipRect(0, 0, mLocation.width(), mLocation.height());
        canvas.drawBitmap(Bitmap.wrapHardwareBuffer(mImage.getHardwareBuffer(), COLOR_SPACE),
                0, 0, null);
        canvas.restore();
        mNode.endRecording();
        return mNode;
    }

    Rect getLocation() {
        return mLocation;
    }

    int getLeft() {
        return mLocation.left;
    }

    int getTop() {
        return mLocation.top;
    }

    int getRight() {
        return mLocation.right;
    }

    int getBottom() {
        return mLocation.bottom;
    }

    @Override
    public synchronized void close() {
        mImage.close();
        if (mNode != null) {
            mNode.discardDisplayList();
        }
    }

    @Override
    public String toString() {
        return "{location=" + mLocation + ", source=" + mImage
                + ", buffer=" + mImage.getHardwareBuffer() + "}";
    }
}
