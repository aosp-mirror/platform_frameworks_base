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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;

/**
 * A session represents the scope of interaction between a {@link ScrollCaptureCallback} and the
 * system during an active scroll capture operation. During the scope of a session, a callback
 * will receive a series of requests for image data. Resources provided here are valid for use
 * until {@link ScrollCaptureCallback#onScrollCaptureEnd(Runnable)}.
 *
 * @hide
 */
public class ScrollCaptureSession {

    private final Surface mSurface;
    private final Rect mScrollBounds;
    private final Point mPositionInWindow;

    @Nullable
    private ScrollCaptureClient mClient;

    /** @hide */
    public ScrollCaptureSession(Surface surface, Rect scrollBounds, Point positionInWindow,
            ScrollCaptureClient client) {
        mSurface = surface;
        mScrollBounds = scrollBounds;
        mPositionInWindow = positionInWindow;
        mClient = client;
    }

    /**
     * Returns a
     * <a href="https://source.android.com/devices/graphics/arch-bq-gralloc">BufferQueue</a> in the
     * form of a {@link Surface} for transfer of image buffers.
     *
     * @return the surface for transferring image buffers
     * @throws IllegalStateException if the session has been closed
     */
    @NonNull
    public Surface getSurface() {
        return mSurface;
    }

    /**
     * Returns the {@code scroll bounds}, as provided by
     * {@link ScrollCaptureCallback#onScrollCaptureSearch}.
     *
     * @return the area of scrolling content within the containing view
     */
    @NonNull
    public Rect getScrollBounds() {
        return mScrollBounds;
    }

    /**
     * Returns the offset of {@code scroll bounds} within the window.
     *
     * @return the area of scrolling content within the containing view
     */
    @NonNull
    public Point getPositionInWindow() {
        return mPositionInWindow;
    }

    /**
     * Notify the system that an a buffer has been posted via the getSurface() channel.
     *
     * @param frameNumber  the frame number of the queued buffer
     * @param capturedArea the area captured, relative to scroll bounds
     */
    public void notifyBufferSent(long frameNumber, @NonNull Rect capturedArea) {
        if (mClient != null) {
            mClient.onRequestImageCompleted(frameNumber, capturedArea);
        }
    }

    /**
     * @hide
     */
    public void disconnect() {
        mClient = null;
        if (mSurface.isValid()) {
            mSurface.release();
        }
    }
}
