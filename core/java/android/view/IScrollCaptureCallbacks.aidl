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

import android.graphics.Rect;
import android.view.ScrollCaptureResponse;
import android.view.Surface;

/**
 * Asynchronous callback channel for responses to scroll capture requests.
 *
 * {@hide}
 */
interface IScrollCaptureCallbacks {
    /**
     * Called in reply to IScrollCaptureConnection#startCapture, when the remote end has confirmed
     * the request and is ready to begin capturing images.
     */
    oneway void onCaptureStarted();

    /**
     * Received a response from a capture request. The provided rectangle indicates the portion
     * of the requested rectangle which was captured. An empty rectangle indicates that the request
     * could not be satisfied (most commonly due to the available scrolling range).
     *
     * @param flags flags describing additional status of the result
     * @param capturedArea the actual area of the image captured
     */
    oneway void onImageRequestCompleted(int flags, in Rect capturedArea);

    /**
     * Signals that the capture session has completed and the target window is ready for normal use.
     */
    oneway void onCaptureEnded();
}
