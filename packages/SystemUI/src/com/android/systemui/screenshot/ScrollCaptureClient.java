/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.BinderThread;
import android.annotation.UiContext;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.util.Log;
import android.view.IScrollCaptureCallbacks;
import android.view.IScrollCaptureConnection;
import android.view.IScrollCaptureResponseListener;
import android.view.IWindowManager;
import android.view.ScrollCaptureResponse;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.concurrent.futures.CallbackToFutureAdapter.Completer;

import com.android.internal.annotations.VisibleForTesting;

import com.google.common.util.concurrent.ListenableFuture;

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
     * Represents the connection to a target window and provides a mechanism for requesting tiles.
     */
    interface Session {
        /**
         * Request an image tile at the given position, from top, to top + {@link #getTileHeight()},
         * and from left 0, to {@link #getPageWidth()}
         *
         * @param top the top (y) position of the tile to capture, in content rect space
         */
        ListenableFuture<CaptureResult> requestTile(int top);

        /**
         * Returns the maximum number of tiles which may be requested and retained without
         * being {@link Image#close() closed}.
         *
         * @return the maximum number of open tiles allowed
         */
        int getMaxTiles();

        /**
         * @return the maximum combined capture height for this session, in pixels.
         */
        default int getMaxHeight() {
            return getMaxTiles() * getTileHeight();
        }

        /**
         * @return the height of each image tile
         */
        int getTileHeight();


        /**
         * @return the height of scrollable content being captured
         */
        int getPageHeight();

        /**
         * @return the width of the scrollable page
         */
        int getPageWidth();

        /**
         * @return the bounds on screen of the window being captured.
         */
        Rect getWindowBounds();

        /**
         * End the capture session, return the target app to original state. The returned Future
         * will complete once the target app is ready to become visible and interactive.
         */
        ListenableFuture<Void> end();

        void release();
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

        CaptureResult(Image image, Rect request, Rect captured) {
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
     */
    public ListenableFuture<ScrollCaptureResponse> request(int displayId) {
        return request(displayId, MATCH_ANY_TASK);
    }

    /**
     * Check for scroll capture support.
     *
     * @param displayId id for the display containing the target window
     * @param taskId id for the task containing the target window or {@link #MATCH_ANY_TASK}.
     * @return a listenable future providing the response
     */
    public ListenableFuture<ScrollCaptureResponse> request(int displayId, int taskId) {
        return CallbackToFutureAdapter.getFuture((completer) -> {
            try {
                mWindowManagerService.requestScrollCapture(displayId, mHostWindowToken, taskId,
                        new IScrollCaptureResponseListener.Stub() {
                            @Override
                            public void onScrollCaptureResponse(ScrollCaptureResponse response) {
                                completer.set(response);
                            }
                        });

            } catch (RemoteException e) {
                completer.setException(e);
            }
            return "ScrollCaptureClient#request"
                    + "(displayId=" + displayId + ", taskId=" + taskId + ")";
        });
    }

    /**
     * Start a scroll capture session.
     *
     * @param response a response provided from a request containing a connection
     * @param maxPages the capture buffer size expressed as a multiple of the content height
     * @return a listenable future providing the session
     */
    public ListenableFuture<Session> start(ScrollCaptureResponse response, float maxPages) {
        IScrollCaptureConnection connection = response.getConnection();
        return CallbackToFutureAdapter.getFuture((completer) -> {
            if (connection == null || !connection.asBinder().isBinderAlive()) {
                completer.setException(new DeadObjectException("No active connection!"));
                return "";
            }
            SessionWrapper session = new SessionWrapper(connection, response.getWindowBounds(),
                    response.getBoundsInWindow(), maxPages);
            session.start(completer);
            return "IScrollCaptureCallbacks#onCaptureStarted";
        });
    }

    private static class SessionWrapper extends IScrollCaptureCallbacks.Stub implements Session,
            IBinder.DeathRecipient {

        private IScrollCaptureConnection mConnection;

        private ImageReader mReader;
        private final int mTileHeight;
        private final int mTileWidth;
        private Rect mRequestRect;
        private boolean mStarted;

        private ICancellationSignal mCancellationSignal;
        private final Rect mWindowBounds;
        private final Rect mBoundsInWindow;
        private final int mMaxTiles;

        private Completer<Session> mStartCompleter;
        private Completer<CaptureResult> mTileRequestCompleter;
        private Completer<Void> mEndCompleter;

        private SessionWrapper(IScrollCaptureConnection connection, Rect windowBounds,
                Rect boundsInWindow, float maxPages) throws RemoteException {
            mConnection = requireNonNull(connection);
            mConnection.asBinder().linkToDeath(SessionWrapper.this, 0);
            mWindowBounds = requireNonNull(windowBounds);
            mBoundsInWindow = requireNonNull(boundsInWindow);

            int pxPerPage = mBoundsInWindow.width() * mBoundsInWindow.height();
            int pxPerTile = min(TILE_SIZE_PX_MAX, (pxPerPage / TILES_PER_PAGE));

            mTileWidth = mBoundsInWindow.width();
            mTileHeight = pxPerTile / mBoundsInWindow.width();
            mMaxTiles = (int) Math.ceil(maxPages * TILES_PER_PAGE);

            if (DEBUG_SCROLL) {
                Log.d(TAG, "boundsInWindow: " + mBoundsInWindow);
                Log.d(TAG, "tile size: " + mTileWidth + "x" + mTileHeight);
            }
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "binderDied! The target process just crashed :-(");
            // Clean up
            mConnection = null;

            // Pass along the bad news.
            if (mStartCompleter != null) {
                mStartCompleter.setException(new DeadObjectException("The remote process died"));
            }
            if (mTileRequestCompleter != null) {
                mTileRequestCompleter.setException(
                        new DeadObjectException("The remote process died"));
            }
            if (mEndCompleter != null) {
                mEndCompleter.setException(new DeadObjectException("The remote process died"));
            }
        }

        private void start(Completer<Session> completer) {
            mReader = ImageReader.newInstance(mTileWidth, mTileHeight, PixelFormat.RGBA_8888,
                    mMaxTiles, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            mStartCompleter = completer;
            try {
                mCancellationSignal = mConnection.startCapture(mReader.getSurface(), this);
                completer.addCancellationListener(() -> {
                    try {
                        mCancellationSignal.cancel();
                    } catch (RemoteException e) {
                        // Ignore
                    }
                }, Runnable::run);
                mStarted = true;
            } catch (RemoteException e) {
                mReader.close();
                completer.setException(e);
            }
        }

        @BinderThread
        @Override
        public void onCaptureStarted() {
            Log.d(TAG, "onCaptureStarted");
            mStartCompleter.set(this);
        }

        @Override
        public ListenableFuture<CaptureResult> requestTile(int top) {
            mRequestRect = new Rect(0, top, mTileWidth, top + mTileHeight);
            return CallbackToFutureAdapter.getFuture((completer -> {
                if (mConnection == null || !mConnection.asBinder().isBinderAlive()) {
                    completer.setException(new DeadObjectException("Connection is closed!"));
                    return "";
                }
                try {
                    mTileRequestCompleter = completer;
                    mCancellationSignal = mConnection.requestImage(mRequestRect);
                    completer.addCancellationListener(() -> {
                        try {
                            mCancellationSignal.cancel();
                        } catch (RemoteException e) {
                            // Ignore
                        }
                    }, Runnable::run);
                } catch (RemoteException e) {
                    completer.setException(e);
                }
                return "IScrollCaptureCallbacks#onImageRequestCompleted";
            }));
        }

        @BinderThread
        @Override
        public void onImageRequestCompleted(int flags, Rect contentArea) {
            Image image = mReader.acquireLatestImage();
            mTileRequestCompleter.set(new CaptureResult(image, mRequestRect, contentArea));
        }

        @Override
        public ListenableFuture<Void> end() {
            Log.d(TAG, "end()");
            return CallbackToFutureAdapter.getFuture(completer -> {
                if (!mStarted) {
                    try {
                        mConnection.asBinder().unlinkToDeath(SessionWrapper.this, 0);
                        mConnection.close();
                    } catch (RemoteException e) {
                        /* ignore */
                    }
                    mConnection = null;
                    completer.set(null);
                    return "";
                }

                mEndCompleter = completer;
                try {
                    mConnection.endCapture();
                } catch (RemoteException e) {
                    completer.setException(e);
                }
                return "IScrollCaptureCallbacks#onCaptureEnded";
            });
        }

        public void release() {
            mReader.close();
        }

        @BinderThread
        @Override
        public void onCaptureEnded() {
            try {
                mConnection.close();
            } catch (RemoteException e) {
                /* ignore */
            }
            mConnection = null;
            mEndCompleter.set(null);
        }

        // Misc

        @Override
        public int getPageHeight() {
            return mBoundsInWindow.height();
        }

        @Override
        public int getPageWidth() {
            return mBoundsInWindow.width();
        }

        @Override
        public int getTileHeight() {
            return mTileHeight;
        }

        public Rect getWindowBounds() {
            return new Rect(mWindowBounds);
        }

        public Rect getBoundsInWindow() {
            return new Rect(mBoundsInWindow);
        }

        @Override
        public int getMaxTiles() {
            return mMaxTiles;
        }
    }
}
