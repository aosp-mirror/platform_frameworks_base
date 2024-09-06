/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.screenshot.scroll;

import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.util.Log;

/**
 * Draws a set of hardware image tiles from a display list. The tiles exist in virtual coordinate
 * space that may extend into positive or negative values. The origin is the upper-left-most corner
 * of bounding box, which is drawn at 0,0 to the drawable (output) bounds.
 */
public class TiledImageDrawable extends Drawable {

    private static final String TAG = "TiledImageDrawable";

    private final ImageTileSet mTiles;
    private RenderNode mNode;

    public TiledImageDrawable(ImageTileSet tiles) {
        mTiles = tiles;
        mTiles.addOnContentChangedListener(this::onContentChanged);
    }


    private void onContentChanged() {
        if (mNode != null && mNode.hasDisplayList()) {
            mNode.discardDisplayList();
        }
        invalidateSelf();
    }

    private void rebuildDisplayListIfNeeded() {
        if (mNode != null && mNode.hasDisplayList()) {
            return;
        }
        if (mNode == null) {
            mNode = new RenderNode("TiledImageDrawable");
        }
        mNode.setPosition(0, 0, mTiles.getWidth(), mTiles.getHeight());
        Canvas canvas = mNode.beginRecording();
        // Align content (virtual) top/left with 0,0, within the render node
        canvas.translate(-mTiles.getLeft(), -mTiles.getTop());
        for (int i = 0; i < mTiles.size(); i++) {
            ImageTile tile = mTiles.get(i);
            canvas.save();
            canvas.translate(tile.getLeft(), tile.getTop());
            canvas.drawRenderNode(tile.getDisplayList());
            canvas.restore();
        }
        mNode.endRecording();
    }

    /**
     * Draws the tiled image to the canvas, with the top/left (virtual) coordinate aligned to 0,0
     * placed at left/top of the drawable's bounds.
     */
    @Override
    public void draw(Canvas canvas) {
        rebuildDisplayListIfNeeded();
        if (canvas.isHardwareAccelerated()) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.clipRect(0, 0, bounds.width(), bounds.height());
            canvas.translate(-bounds.left, -bounds.top);
            canvas.drawRenderNode(mNode);
            canvas.restore();
        } else {
            Log.d(TAG, "Canvas is not hardware accelerated. Skipping draw!");
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return mTiles.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mTiles.getHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        if (mNode.setAlpha(alpha / 255f)) {
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
