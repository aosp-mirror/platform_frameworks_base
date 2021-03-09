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

import android.annotation.AnyThread;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.UiThread;

import com.android.internal.util.CallbackRegistry;
import com.android.internal.util.CallbackRegistry.NotifierCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns a series of partial screen captures (tiles).
 * <p>
 * To display on-screen, use {@link #getDrawable()}.
 */
@UiThread
class ImageTileSet {

    private static final String TAG = "ImageTileSet";

    private CallbackRegistry<OnBoundsChangedListener, ImageTileSet, Rect> mOnBoundsListeners;
    private CallbackRegistry<OnContentChangedListener, ImageTileSet, Rect> mContentListeners;

    ImageTileSet(@UiThread Handler handler) {
        mHandler = handler;
    }

    interface OnBoundsChangedListener {
        /**
         * Reports an update to the bounding box that contains all active tiles. These are virtual
         * (capture) coordinates which can be either negative or positive.
         */
        void onBoundsChanged(int left, int top, int right, int bottom);
    }

    interface OnContentChangedListener {
        /**
         * Mark as dirty and rebuild display list.
         */
        void onContentChanged();
    }

    private final List<ImageTile> mTiles = new ArrayList<>();
    private final Rect mBounds = new Rect();
    private final Handler mHandler;

    private OnContentChangedListener mOnContentChangedListener;
    private OnBoundsChangedListener mOnBoundsChangedListener;

    void addOnBoundsChangedListener(OnBoundsChangedListener listener) {
        if (mOnBoundsListeners == null) {
            mOnBoundsListeners = new CallbackRegistry<>(
                    new NotifierCallback<OnBoundsChangedListener, ImageTileSet, Rect>() {
                        @Override
                        public void onNotifyCallback(OnBoundsChangedListener callback,
                                ImageTileSet sender,
                                int arg, Rect newBounds) {
                            callback.onBoundsChanged(newBounds.left, newBounds.top, newBounds.right,
                                    newBounds.bottom);
                        }
                    });
        }
        mOnBoundsListeners.add(listener);
    }

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
        final Rect newBounds = new Rect(mBounds);
        final Rect newRect = tile.getLocation();
        mTiles.add(tile);
        newBounds.union(newRect);
        if (!newBounds.equals(mBounds)) {
            mBounds.set(newBounds);
            notifyBoundsChanged(mBounds);
        }
        notifyContentChanged();
    }

    private void notifyContentChanged() {
        if (mContentListeners != null) {
            mContentListeners.notifyCallbacks(this, 0, null);
        }
    }

    private void notifyBoundsChanged(Rect bounds) {
        if (mOnBoundsListeners != null) {
            mOnBoundsListeners.notifyCallbacks(this, 0, bounds);
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
        Log.d(TAG, "exporting with bounds: " + bounds);
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
        return mBounds.left;
    }

    int getTop() {
        return mBounds.top;
    }

    int getRight() {
        return mBounds.right;
    }

    int getBottom() {
        return mBounds.bottom;
    }

    int getWidth() {
        return mBounds.width();
    }

    int getHeight() {
        return mBounds.height();
    }

    @AnyThread
    void clear() {
        if (!mHandler.getLooper().isCurrentThread()) {
            mHandler.post(this::clear);
            return;
        }
        if (mTiles.isEmpty()) {
            return;
        }
        mBounds.setEmpty();
        mTiles.forEach(ImageTile::close);
        mTiles.clear();
        notifyBoundsChanged(mBounds);
        notifyContentChanged();
    }
}
