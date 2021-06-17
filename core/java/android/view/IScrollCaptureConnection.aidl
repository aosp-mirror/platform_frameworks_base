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
import android.os.ICancellationSignal;
import android.view.IScrollCaptureCallbacks;
import android.view.Surface;


 /**
   * A remote connection to a scroll capture target.
   *
   * {@hide}
   */
interface IScrollCaptureConnection {

    /**
     * Informs the target that it has been selected for scroll capture.
     *
     * @param surface used to shuttle image buffers between processes
     * @param callbacks a return channel for requests
     *
     * @return a cancallation signal which is used cancel the start request
     */
    ICancellationSignal startCapture(in Surface surface, IScrollCaptureCallbacks callbacks);

    /**
     * Request the target capture an image within the provided rectangle.
     *
     * @param surface a return channel for image buffers
     * @param signal a cancallation signal which can interrupt the request
     *
     * @return a cancallation signal which is used cancel the request
     */
    ICancellationSignal requestImage(in Rect captureArea);

    /**
     * Inform the target that capture has ended.
     *
     * @return a cancallation signal which is used cancel the request
     */
    ICancellationSignal endCapture();

    /**
     * Closes the connection.
     */
    oneway void close();
}
