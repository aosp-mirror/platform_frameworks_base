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
import android.annotation.UiThread;
import android.graphics.Rect;
import android.os.CancellationSignal;

import java.util.function.Consumer;

/**
 * A ScrollCaptureCallback is responsible for providing rendered snapshots of scrolling content for
 * the scroll capture system. A single callback is responsible for providing support to a single
 * scrolling UI element. At request time, the system will select the best candidate from among all
 * callbacks registered within the window.
 * <p>
 * A callback is assigned to a View using {@link View#setScrollCaptureCallback}, or to the window as
 * {@link Window#registerScrollCaptureCallback}. The point where the callback is registered defines
 * the frame of reference for the bounds measurements used.
 * <p>
 * <b>Terminology</b>
 * <dl>
 * <dt>Containing View</dt>
 * <dd>The view on which this callback is attached, or the root view of the window if the callback
 * is assigned  directly to a window.</dd>
 *
 * <dt>Scroll Bounds</dt>
 * <dd>A rectangle which describes an area within the containing view where scrolling content
 * appears. This may be the entire view or any rectangle within. This defines a frame of reference
 * for requests as well as the width and maximum height of a single request.</dd>
 *
 * <dt>Scroll Delta</dt>
 * <dd>The distance the scroll position has moved since capture started. Implementations are
 * responsible for tracking changes in vertical scroll position during capture. This is required to
 * map the capture area to the correct location, given the current scroll position.
 *
 * <dt>Capture Area</dt>
 * <dd>A rectangle which describes the area to capture, relative to scroll bounds. The vertical
 * position remains relative to the starting scroll position and any movement since ("Scroll Delta")
 * should be subtracted to locate the correct local position, and scrolled into view as necessary.
 * </dd>
 * </dl>
 *
 * @see View#setScrollCaptureHint(int)
 * @see View#setScrollCaptureCallback(ScrollCaptureCallback)
 * @see Window#registerScrollCaptureCallback(ScrollCaptureCallback)
 */
@UiThread
public interface ScrollCaptureCallback {

    /**
     * The system is searching for the appropriate scrolling container to capture and would like to
     * know the size and position of scrolling content handled by this callback.
     * <p>
     * To determine scroll bounds, an implementation should inset the visible bounds of the
     * containing view to cover only the area where scrolling content may be positioned. This
     * should cover only the content which tracks with scrolling movement.
     * <p>
     * Return the updated rectangle to {@link Consumer#accept onReady.accept}. If for any reason the
     * scrolling content is not available to capture, a empty rectangle may be returned which will
     * exclude this view from consideration.
     * <p>
     * This request may be cancelled via the provided {@link CancellationSignal}. When this happens,
     * any future call to {@link Consumer#accept onReady.accept} will have no effect and this
     * content will be omitted from the search results.
     *
     * @param signal  signal to cancel the operation in progress
     * @param onReady consumer for the updated rectangle
     */
    void onScrollCaptureSearch(@NonNull CancellationSignal signal, @NonNull Consumer<Rect> onReady);

    /**
     * Scroll Capture has selected this callback to provide the scrolling image content.
     * <p>
     * {@link Runnable#run onReady.run} should be called when ready to begin handling image
     * requests.
     * <p>
     * This request may be cancelled via the provided {@link CancellationSignal}. When this happens,
     * any future call to {@link Runnable#run onReady.run} will have no effect and provided session
     * will not be activated.
     *
     * @param session the current session, resources provided by it are valid for use until the
     *                {@link #onScrollCaptureEnd(Runnable) session ends}
     * @param signal  signal to cancel the operation in progress
     * @param onReady signal used to report completion of the request
     */
    void onScrollCaptureStart(@NonNull ScrollCaptureSession session,
            @NonNull CancellationSignal signal, @NonNull Runnable onReady);

    /**
     * An image capture has been requested from the scrolling content.
     * <p>
     * The requested rectangle describes an area inside the target view, relative to
     * <code>scrollBounds</code>. The content may be offscreen, above or below the current visible
     * portion of the target view. To handle the request, render the available portion of this
     * rectangle to a buffer and return it via the Surface available from {@link
     * ScrollCaptureSession#getSurface()}.
     * <p>
     * Note: Implementations are only required to render the requested content, and may do so into
     * off-screen buffers without scrolling if they are able.
     * <p>
     * The resulting available portion of the request must be computed as a portion of {@code
     * captureArea}, and sent to signal the operation is complete, using  {@link Consumer#accept
     * onComplete.accept}. If the requested rectangle is  partially or fully out of bounds the
     * resulting portion should be returned. If no portion is available (outside of available
     * content), then skip sending any buffer and report an empty Rect as result.
     * <p>
     * This request may be cancelled via the provided {@link CancellationSignal}. When this happens,
     * any future call to {@link Consumer#accept onComplete.accept} will be ignored until the next
     * request.
     *
     * @param session the current session, resources provided by it are valid for use until the
     *                {@link #onScrollCaptureEnd(Runnable) session ends}
     * @param signal      signal to cancel the operation in progress
     * @param captureArea the area to capture, a rectangle within {@code scrollBounds}
     * @param onComplete  a consumer for the captured area
     */
    void onScrollCaptureImageRequest(@NonNull ScrollCaptureSession session,
            @NonNull CancellationSignal signal, @NonNull Rect captureArea,
            @NonNull Consumer<Rect> onComplete);

    /**
     * Signals that capture has ended. Implementations should release any temporary resources or
     * references to objects in use during the capture. Any resources obtained from the session are
     * now invalid and attempts to use them after this point may throw an exception.
     * <p>
     * The window should be returned to its original state when capture started. At a minimum, the
     * content should be scrolled to its original position.
     * <p>
     * {@link Runnable#run onReady.run} should be called as soon as possible after the window is
     * ready for normal interactive use. After the callback (or after a timeout, if not called) the
     * screenshot tool will be dismissed and the window may become visible to the user at any time.
     *
     * @param onReady a callback to inform the system that the application has completed any
     *                cleanup and is ready to become visible
     */
    void onScrollCaptureEnd(@NonNull Runnable onReady);
}

