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
import android.view.Surface;


 /**
   * Interface implemented by a client of the Scroll Capture framework to receive requests
   * to start, capture images and end the session.
   *
   * {@hide}
   */
interface IScrollCaptureClient {

    /**
     * Informs the client that it has been selected for scroll capture and should prepare to
     * to begin handling capture requests.
     */
    oneway void startCapture(in Surface surface);

    /**
     * Request the client capture an image within the provided rectangle.
     *
     * @see android.view.ScrollCaptureCallback#onScrollCaptureRequest
     */
    oneway void requestImage(in Rect captureArea);

    /**
     * Inform the client that capture has ended. The client should shut down and release all
     * local resources in use and prepare for return to normal interactive usage.
     */
    oneway void endCapture();
}
