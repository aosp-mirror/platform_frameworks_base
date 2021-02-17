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

import static com.android.systemui.screenshot.LogConfig.DEBUG_SCROLL;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import android.annotation.UiContext;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.IScrollCaptureCallbacks;
import android.view.IScrollCaptureConnection;
import android.view.IWindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.view.ScrollCaptureViewSupport;

import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * High(er) level interface to scroll capture API.
 */
public class ScrollCaptureClient {
    private static final int TILE_SIZE_PX_MAX = 4 * (1024 * 1024);
    private static final int TILES_PER_PAGE = 2; // increase once b/174571735 is addressed

    @VisibleForTesting
    static final int MATCH_ANY_TASK = ActivityTaskManager.INVALID_TASK_ID;

    private static final String TAG = LogConfig.logTag(ScrollCaptureClient.class);


    /**
     * A connection to a remote window. Starts a capture session.
     */
    public interface Connection {
        /**
         * Session start should be deferred until UI is active because of resource allocation and
         * potential visible side effects in the target window.
         *
         * @param sessionConsumer listener to receive the session once active
         * @param maxPages the capture buffer size expressed as a multiple of the content height
         */
        void start(Consumer<Session> sessionConsumer, float maxPages);

        /**
         * Close the connection.
         */
        void close();
    }

    static class CaptureResult {
        public final Image image;
        /**
         * The area requested, in content rect space, relative to scroll-bounds.
         */
        public final Rect requested;
        /**
         * The actual area captured, in content rect space, relative to scroll-bounds. This may be
         * cropped or empty depending on available content.
         */
        public final Rect captured;

        // Error?

        private CaptureResult(Image image, Rect request, Rect captured) {
            this.image =  image;
            this.requested = request;
            this.captured = captured;
        }

        @Override
        public String toString() {
            return "CaptureResult{"
                    + "requested=" + requested
                    + " (" + requested.width() + "x" + requested.height() + ")"
                    + ", captured=" + captured
                    + " (" + captured.width() + "x" + captured.height() + ")"
                    + ", image=" + image
                    + '}';
        }
    }

    /**
     * Represents the connection to a target window and provides a mechanism for requesting tiles.
     */
    interface Session {
        /**
         * Request an image tile at the given position, from top, to top + {@link #getTileHeight()},
         * and from left 0, to {@link #getPageWidth()}
         *
         * @param top the top (y) position of the tile to capture, in content rect space
         * @param consumer listener to be informed of the result
         */
        void requestTile(int top, Consumer<CaptureResult> consumer);

        /**
         * Returns the maximum number of tiles which may be requested and retained without
         * being {@link Image#close() closed}.
         *
         * @return the maximum number of open tiles allowed
         */
        int getMaxTiles();

        int getTileHeight();

        int getPageHeight();

        int getPageWidth();

        /**
         * End the capture session, return the target app to original state. The listener
         * will be called when the target app is ready to before visible and interactive.
         */
        void end(Runnable listener);
    }

    private final IWindowManager mWindowManagerService;
    private IBinder mHostWindowToken;

    @Inject
    public ScrollCaptureClient(@UiContext Context context, IWindowManager windowManagerService) {
        requireNonNull(context.getDisplay(), "context must be associated with a Display!");
        mWindowManagerService = windowManagerService;
    }

    /**
     * Set the window token for the screenshot window/ This is required to avoid targeting our
     * window or any above it.
     *
     * @param token the windowToken of the screenshot window
     */
    public void setHostWindowToken(IBinder token) {
        mHostWindowToken = token;
    }

    /**
     * Check for scroll capture support.
     *
     * @param displayId id for the display containing the target window
     * @param consumer receives a connection when available
     */
    public void request(int displayId, Consumer<Connection> consumer) {
        request(displayId, MATCH_ANY_TASK, consumer);
    }

    /**
     * Check for scroll capture support.
     *
     * @param displayId id for the display containing the target window
     * @param taskId id for the task containing the target window or {@link #MATCH_ANY_TASK}.
     * @param consumer receives a connection when available
     */
    public void request(int displayId, int taskId, Consumer<Connection> consumer) {
        try {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "requestScrollCapture(displayId=" + displayId + ", " + mHostWindowToken
                        + ", taskId=" + taskId + ", consumer=" + consumer + ")");
            }
            mWindowManagerService.requestScrollCapture(displayId, mHostWindowToken, taskId,
                    new ControllerCallbacks(consumer));
        } catch (RemoteException e) {
            Log.e(TAG, "Ignored remote exception", e);
        }
    }

    private static class ControllerCallbacks extends IScrollCaptureCallbacks.Stub implements
            Connection, Session, IBinder.DeathRecipient {

        private IScrollCaptureConnection mConnection;
        private Consumer<Connection> mConnectionConsumer;
        private Consumer<Session> mSessionConsumer;
        private Consumer<CaptureResult> mResultConsumer;
        private Runnable mShutdownListener;

        private ImageReader mReader;
        private Rect mScrollBounds;
        private int mTileHeight;
        private int mTileWidth;
        private Rect mRequestRect;
        private boolean mStarted;
        private int mMaxTiles;

        private ControllerCallbacks(Consumer<Connection> connectionConsumer) {
            mConnectionConsumer = connectionConsumer;
        }

        // IScrollCaptureCallbacks

        @Override
        public void onConnected(IScrollCaptureConnection connection, Rect scrollBounds,
                Point positionInWindow) throws RemoteException {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "onConnected(connection=" + connection + ", scrollBounds=" + scrollBounds
                        + ", positionInWindow=" + positionInWindow + ")");
            }
            mConnection = connection;
            mConnection.asBinder().linkToDeath(this, 0);
            mScrollBounds = scrollBounds;
            mConnectionConsumer.accept(this);
            mConnectionConsumer = null;

            int pxPerPage = mScrollBounds.width() * mScrollBounds.height();
            int pxPerTile = min(TILE_SIZE_PX_MAX, (pxPerPage / TILES_PER_PAGE));
            mTileWidth = mScrollBounds.width();
            mTileHeight = pxPerTile  / mScrollBounds.width();
            if (DEBUG_SCROLL) {
                Log.d(TAG, "scrollBounds: " + mScrollBounds);
                Log.d(TAG, "tile dimen: " + mTileWidth + "x" + mTileHeight);
            }
        }

        @Override
        public void onUnavailable() throws RemoteException {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "onUnavailable");
            }
            // The targeted app does not support scroll capture
            // or the window could not be found... etc etc.
        }

        @Override
        public void onCaptureStarted() {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "onCaptureStarted()");
            }
            mSessionConsumer.accept(this);
            mSessionConsumer = null;
        }

        @Override
        public void onCaptureBufferSent(long frameNumber, Rect contentArea) {
            Image image = null;
            if (frameNumber != ScrollCaptureViewSupport.NO_FRAME_PRODUCED) {
                image = mReader.acquireNextImage();
            }
            if (DEBUG_SCROLL) {
                Log.d(TAG, "onCaptureBufferSent(frameNumber=" + frameNumber
                        + ", contentArea=" + contentArea + ") image=" + image);
            }
            // Save and clear first, since the consumer will likely request the next
            // tile, otherwise the new consumer will be wiped out.
            Consumer<CaptureResult> consumer = mResultConsumer;
            mResultConsumer = null;
            consumer.accept(new CaptureResult(image, mRequestRect, contentArea));
        }

        @Override
        public void onConnectionClosed() {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "onConnectionClosed()");
            }
            disconnect();
            if (mShutdownListener != null) {
                mShutdownListener.run();
                mShutdownListener = null;
            }
        }

        // Misc

        private void disconnect() {
            if (mConnection != null) {
                mConnection.asBinder().unlinkToDeath(this, 0);
            }
            mConnection = null;
        }

        // ScrollCaptureController.Connection

        @Override
        public void start(Consumer<Session> sessionConsumer, float maxPages) {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "start(sessionConsumer=" + sessionConsumer + ","
                        + " maxPages=" + maxPages + ")"
                        + " [maxHeight: " + (mMaxTiles * mTileHeight) + "px]");
            }
            mMaxTiles = (int) Math.ceil(maxPages * TILES_PER_PAGE);
            mReader = ImageReader.newInstance(mTileWidth, mTileHeight, PixelFormat.RGBA_8888,
                    mMaxTiles, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            mSessionConsumer = sessionConsumer;
            try {
                mConnection.startCapture(mReader.getSurface());
                mStarted = true;
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to start", e);
            }
        }

        @Override
        public void close() {
            end(null);
        }

        // ScrollCaptureController.Session

        @Override
        public void end(Runnable listener) {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "end(listener=" + listener + ")");
            }
            if (mStarted) {
                mShutdownListener = listener;
                try {
                    // listener called from onConnectionClosed callback
                    mConnection.endCapture();
                } catch (RemoteException e) {
                    Log.d(TAG, "Ignored exception from endCapture()", e);
                    disconnect();
                    listener.run();
                }
            } else {
                disconnect();
                listener.run();
            }
        }

        @Override
        public int getPageHeight() {
            return mScrollBounds.height();
        }

        @Override
        public int getPageWidth() {
            return mScrollBounds.width();
        }

        @Override
        public int getTileHeight() {
            return mTileHeight;
        }

        @Override
        public int getMaxTiles() {
            return mMaxTiles;
        }

        @Override
        public void requestTile(int top, Consumer<CaptureResult> consumer) {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "requestTile(top=" + top + ", consumer=" + consumer + ")");
            }
            mRequestRect = new Rect(0, top, mTileWidth, top + mTileHeight);
            mResultConsumer = consumer;
            try {
                mConnection.requestImage(mRequestRect);
            } catch (RemoteException e) {
                Log.e(TAG, "Caught remote exception from requestImage", e);
            }
        }

        /**
         * The process hosting the window went away abruptly!
         */
        @Override
        public void binderDied() {
            if (DEBUG_SCROLL) {
                Log.d(TAG, "binderDied()");
            }
            disconnect();
        }
    }
}
