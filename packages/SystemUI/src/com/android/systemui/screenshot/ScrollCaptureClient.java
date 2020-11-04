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
 * High level interface to scroll capture API.
 */
public class ScrollCaptureClient {

    @VisibleForTesting
    static final int MATCH_ANY_TASK = ActivityTaskManager.INVALID_TASK_ID;

    private static final String TAG = "ScrollCaptureClient";

    /** Whether to log method names and arguments for most calls */
    private static final boolean DEBUG_TRACE = false;

    /**
     * A connection to a remote window. Starts a capture session.
     */
    public interface Connection {
        /**
         * Session start should be deferred until UI is active because of resource allocation and
         * potential visible side effects in the target window.
         *
         * @param maxBuffers the maximum number of buffers (tiles) that may be in use at one
         *                   time, tiles are not cached anywhere so set this to a large enough
         *                   number to retain offscreen content until it is no longer needed
         * @param sessionConsumer listener to receive the session once active
         */
        void start(int maxBuffers, Consumer<Session> sessionConsumer);

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
    }

    /**
     * Represents the connection to a target window and provides a mechanism for requesting tiles.
     */
    interface Session {
        /**
         * Request the given horizontal strip. Values are y-coordinates in captured space, relative
         * to start position.
         *
         * @param contentRect the area to capture, in content rect space, relative to scroll-bounds
         * @param consumer listener to be informed of the result
         */
        void requestTile(Rect contentRect, Consumer<CaptureResult> consumer);

        /**
         * End the capture session, return the target app to original state. The returned
         * stage must be waited for to complete to allow the target app a chance to restore to
         * original state before becoming visible.
         *
         * @return a stage presenting the session shutdown
         */
        void end(Runnable listener);

        int getMaxTileHeight();

        int getMaxTileWidth();
    }

    private final IWindowManager mWindowManagerService;
    private IBinder mHostWindowToken;

    @Inject
    public ScrollCaptureClient(@UiContext Context context, IWindowManager windowManagerService) {
        requireNonNull(context.getDisplay(), "context must be associated with a Display!");
        mWindowManagerService = windowManagerService;
    }

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
            if (DEBUG_TRACE) {
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
        private Rect mRequestRect;
        private boolean mStarted;

        private ControllerCallbacks(Consumer<Connection> connectionConsumer) {
            mConnectionConsumer = connectionConsumer;
        }

        // IScrollCaptureCallbacks

        @Override
        public void onConnected(IScrollCaptureConnection connection, Rect scrollBounds,
                Point positionInWindow) throws RemoteException {
            if (DEBUG_TRACE) {
                Log.d(TAG, "onConnected(connection=" + connection + ", scrollBounds=" + scrollBounds
                        + ", positionInWindow=" + positionInWindow + ")");
            }
            mConnection = connection;
            mConnection.asBinder().linkToDeath(this, 0);
            mScrollBounds = scrollBounds;
            mConnectionConsumer.accept(this);
            mConnectionConsumer = null;
        }

        @Override
        public void onUnavailable() throws RemoteException {
            if (DEBUG_TRACE) {
                Log.d(TAG, "onUnavailable");
            }
            // The targeted app does not support scroll capture
            // or the window could not be found... etc etc.
        }

        @Override
        public void onCaptureStarted() {
            if (DEBUG_TRACE) {
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
            if (DEBUG_TRACE) {
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
            if (DEBUG_TRACE) {
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

        // -> Error handling: BiConsumer<Session, Throwable> ?
        @Override
        public void start(int maxBufferCount, Consumer<Session> sessionConsumer) {
            if (DEBUG_TRACE) {
                Log.d(TAG, "start(maxBufferCount=" + maxBufferCount
                        + ", sessionConsumer=" + sessionConsumer + ")");
            }
            mReader = ImageReader.newInstance(mScrollBounds.width(), mScrollBounds.height(),
                    PixelFormat.RGBA_8888, maxBufferCount, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            mSessionConsumer = sessionConsumer;
            try {
                mConnection.startCapture(mReader.getSurface());
                mStarted = true;
            } catch (RemoteException e) {
                Log.w(TAG, "should not be happening :-(");
                // ?
                //mSessionListener.onError(e);
                //mSessionListener = null;
            }
        }

        @Override
        public void close() {
            end(null);
        }

        // ScrollCaptureController.Session

        @Override
        public void end(Runnable listener) {
            if (DEBUG_TRACE) {
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
        public int getMaxTileHeight() {
            return mScrollBounds.height();
        }

        @Override
        public int getMaxTileWidth() {
            return mScrollBounds.width();
        }

        @Override
        public void requestTile(Rect contentRect, Consumer<CaptureResult> consumer) {
            if (DEBUG_TRACE) {
                Log.d(TAG, "requestTile(contentRect=" + contentRect + "consumer=" + consumer + ")");
            }
            mRequestRect = new Rect(contentRect);
            mResultConsumer = consumer;
            try {
                mConnection.requestImage(mRequestRect);
            } catch (RemoteException e) {
                Log.e(TAG, "Caught remote exception from requestImage", e);
                // ?
            }
        }

        /**
         * The process hosting the window went away abruptly!
         */
        @Override
        public void binderDied() {
            if (DEBUG_TRACE) {
                Log.d(TAG, "binderDied()");
            }
            disconnect();
        }
    }
}
