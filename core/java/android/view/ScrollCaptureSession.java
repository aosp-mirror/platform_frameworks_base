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

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;

/**
 * A session represents the scope of interaction between a {@link ScrollCaptureCallback} and the
 * system during an active scroll capture operation.
 */
public class ScrollCaptureSession {

    private final Surface mSurface;
    private final Rect mScrollBounds;
    private final Point mPositionInWindow;

    /**
     * Constructs a new session instance.
     *
     * @param surface the surface to consume generated images
     * @param scrollBounds the bounds of the capture area within the containing view
     * @param positionInWindow the offset of scrollBounds within the window
     */
    public ScrollCaptureSession(@NonNull Surface surface, @NonNull Rect scrollBounds,
            @NonNull Point positionInWindow) {
        mSurface = requireNonNull(surface);
        mScrollBounds = requireNonNull(scrollBounds);
        mPositionInWindow = requireNonNull(positionInWindow);
    }

    /**
     * Returns a
     * <a href="https://source.android.com/devices/graphics/arch-bq-gralloc">BufferQueue</a> in the
     * form of a {@link Surface} for transfer of image buffers.
     * <p>
     * The surface is guaranteed to remain {@link Surface#isValid() valid} until the session
     * {@link ScrollCaptureCallback#onScrollCaptureEnd(Runnable) ends}.
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
}
