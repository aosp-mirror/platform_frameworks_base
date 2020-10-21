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

import android.graphics.Point;
import android.graphics.Rect;
import android.view.Surface;

import android.view.IScrollCaptureConnection;

/**
 * Asynchronous callback channel for responses to scroll capture requests.
 *
 * {@hide}
 */
interface IScrollCaptureCallbacks {
    /**
     * Scroll capture is available, and a connection has been provided.
     *
     * @param connection a connection to a window process and scrollable content
     * @param scrollAreaInWindow the location of scrolling in global (window) coordinate space
     */
    oneway void onConnected(in IScrollCaptureConnection connection, in Rect scrollBounds,
            in Point positionInWindow);

    /**
     * The window does not support scroll capture.
     */
    oneway void onUnavailable();

    /**
     * Called when the remote end has confirmed the request and is ready to begin providing image
     * requests.
     */
    oneway void onCaptureStarted();

    /**
     * Received a response from a capture request.
     */
    oneway void onCaptureBufferSent(long frameNumber, in Rect capturedArea);

    /**
     * Signals that the capture session has completed and the target window may be returned to
     * normal interactive use. This may be due to normal shutdown, or after a timeout or other
     * unrecoverable state change such as activity lifecycle, window visibility or focus.
     */
    oneway void onConnectionClosed();
}
