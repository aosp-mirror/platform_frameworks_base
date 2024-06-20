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

import android.annotation.AnyThread;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.os.Handler;

import androidx.annotation.UiThread;

import com.android.internal.util.CallbackRegistry;
import com.android.internal.util.CallbackRegistry.NotifierCallback;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;

/**
 * Owns a series of partial screen captures (tiles).
 * <p>
 * To display on-screen, use {@link #getDrawable()}.
 */
@UiThread
class ImageTileSet {

    private static final String TAG = "ImageTileSet";

    private CallbackRegistry<OnContentChangedListener, ImageTileSet, Rect> mContentListeners;

    @Inject
    ImageTileSet(@UiThread Handler handler) {
        mHandler = handler;
    }

    interface OnContentChangedListener {
        /**
         * Mark as dirty and rebuild display list.
         */
        void onContentChanged();
    }

    private final List<ImageTile> mTiles = new ArrayList<>();
    private final Region mRegion = new Region();
    private final Handler mHandler;

    void addOnContentChangedListener(OnContentChangedListener listener) {
        if (mContentListeners == null) {
            mContentListeners = new CallbackRegistry<>(
                    new NotifierCallback<OnContentChangedListener, ImageTileSet, Rect>() {
                        @Override
                        public void onNotifyCallback(OnContentChangedListener callback,
                                ImageTileSet sender,
                                int arg, Rect newBounds) {
                            callback.onContentChanged();
                        }
                    });
        }
        mContentListeners.add(listener);
    }

    @AnyThread
    void addTile(ImageTile tile) {
        if (!mHandler.getLooper().isCurrentThread()) {
            mHandler.post(() -> addTile(tile));
            return;
        }
        mTiles.add(tile);
        mRegion.op(tile.getLocation(), mRegion, Region.Op.UNION);
        notifyContentChanged();
    }

    private void notifyContentChanged() {
        if (mContentListeners != null) {
            mContentListeners.notifyCallbacks(this, 0, null);
        }
    }

    /**
     * Returns a drawable to paint the combined contents of the tiles. Drawable dimensions are
     * zero-based and map directly to {@link #getLeft()}, {@link #getTop()}, {@link #getRight()},
     * and {@link #getBottom()} which are dimensions relative to the capture start position
     * (positive or negative).
     *
     * @return a drawable to display the image content
     */
    Drawable getDrawable() {
        return new TiledImageDrawable(this);
    }

    boolean  isEmpty() {
        return mTiles.isEmpty();
    }

    int size() {
        return mTiles.size();
    }

    /**
     * @return the bounding rect around any gaps in the tiles.
     */
    Rect getGaps() {
        Region difference = new Region();
        difference.op(mRegion.getBounds(), mRegion, Region.Op.DIFFERENCE);
        return difference.getBounds();
    }

    ImageTile get(int i) {
        return mTiles.get(i);
    }

    Bitmap toBitmap() {
        return toBitmap(new Rect(0, 0, getWidth(), getHeight()));
    }

    /**
     * @param bounds Selected portion of the tile set's bounds (equivalent to tile bounds coord
     *               space). For example, to get the whole doc, use Rect(0, 0, getWidth(),
     *               getHeight()).
     */
    Bitmap toBitmap(Rect bounds) {
        if (mTiles.isEmpty()) {
            return null;
        }
        final RenderNode output = new RenderNode("Bitmap Export");
        output.setPosition(0, 0, bounds.width(), bounds.height());
        RecordingCanvas canvas = output.beginRecording();
        Drawable drawable = getDrawable();
        drawable.setBounds(bounds);
        drawable.draw(canvas);
        output.endRecording();
        return HardwareRenderer.createHardwareBitmap(output, bounds.width(), bounds.height());
    }

    int getLeft() {
        return mRegion.getBounds().left;
    }

    int getTop() {
        return mRegion.getBounds().top;
    }

    int getRight() {
        return mRegion.getBounds().right;
    }

    int getBottom() {
        return mRegion.getBounds().bottom;
    }

    int getWidth() {
        return mRegion.getBounds().width();
    }

    int getHeight() {
        return mRegion.getBounds().height();
    }

    void clear() {
        if (mTiles.isEmpty()) {
            return;
        }
        mRegion.setEmpty();
        Iterator<ImageTile> i = mTiles.iterator();
        while (i.hasNext()) {
            ImageTile next = i.next();
            next.close();
            i.remove();
        }
        notifyContentChanged();
    }
}
