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

import java.util.function.Consumer;

/**
 * A ScrollCaptureCallback is responsible for providing rendered snapshots of scrolling content for
 * the scroll capture system. A single callback is responsible for providing support to a single
 * scrolling UI element. At request time, the system will select the best candidate from among all
 * callbacks registered within the window.
 * <p>
 * A callback is assigned to a View using {@link View#setScrollCaptureCallback}, or to the window as
 * {@link Window#addScrollCaptureCallback}. The point where the callback is registered defines the
 * frame of reference for the bounds measurements used.
 * <p>
 * <b>Terminology</b>
 * <dl>
 * <dt>Containing View</dt>
 * <dd>The view on which this callback is attached, or the root view of the window if the callback
 * is assigned  directly to a window.</dd>
 *
 * <dt>Scroll Bounds</dt>
 * <dd>A rectangle which describes an area within the containing view where scrolling content may
 * be positioned. This may be the Containing View bounds itself, or any rectangle within.
 * Requested by {@link #onScrollCaptureSearch}.</dd>
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
 * @see Window#addScrollCaptureCallback(ScrollCaptureCallback)
 *
 * @hide
 */
@UiThread
public interface ScrollCaptureCallback {

    /**
     * The system is searching for the appropriate scrolling container to capture and would like to
     * know the size and position of scrolling content handled by this callback.
     * <p>
     * Implementations should inset {@code containingViewBounds} to cover only the area within the
     * containing view where scrolling content may be positioned. This should cover only the content
     * which tracks with scrolling movement.
     * <p>
     * Return the updated rectangle to {@code resultConsumer}. If for any reason the scrolling
     * content is not available to capture, a {@code null} rectangle may be returned, and this view
     * will be excluded as the target for this request.
     * <p>
     * Responses received after XXXms will be discarded.
     * <p>
     * TODO: finalize timeout
     *
     * @param onReady              consumer for the updated rectangle
     */
    void onScrollCaptureSearch(@NonNull Consumer<Rect> onReady);

    /**
     * Scroll Capture has selected this callback to provide the scrolling image content.
     * <p>
     * The onReady signal should be called when ready to begin handling image requests.
     */
    void onScrollCaptureStart(@NonNull ScrollCaptureSession session, @NonNull Runnable onReady);

    /**
     * An image capture has been requested from the scrolling content.
     * <p>
     * <code>captureArea</code> contains the bounds of the image requested, relative to the
     * rectangle provided by {@link ScrollCaptureCallback#onScrollCaptureSearch}, referred to as
     * {@code scrollBounds}.
     * here.
     * <p>
     * A series of requests will step by a constant vertical amount relative to {@code
     * scrollBounds}, moving through the scrolling range of content, above and below the current
     * visible area. The rectangle's vertical position will not account for any scrolling movement
     * since capture started. Implementations therefore must track any scroll position changes and
     * subtract this distance from requests.
     * <p>
     * To handle a request, the content should be scrolled to maximize the visible area of the
     * requested rectangle. Offset {@code captureArea} again to account for any further scrolling.
     * <p>
     * Finally, clip this rectangle against scrollBounds to determine what portion, if any is
     * visible content to capture. If the rectangle is completely clipped, set it to {@link
     * Rect#setEmpty() empty} and skip the next step.
     * <p>
     * Make a copy of {@code captureArea}, transform to window coordinates and draw the window,
     * clipped to this rectangle, into the {@link ScrollCaptureSession#getSurface() surface} at
     * offset (0,0).
     * <p>
     * Finally, return the resulting {@code captureArea} using
     * {@link ScrollCaptureSession#notifyBufferSent}.
     * <p>
     * If the response is not supplied within XXXms, the session will end with a call to {@link
     * #onScrollCaptureEnd}, after which {@code session} is invalid and should be discarded.
     * <p>
     * TODO: finalize timeout
     * <p>
     *
     * @param captureArea the area to capture, a rectangle within {@code scrollBounds}
     */
    void onScrollCaptureImageRequest(
            @NonNull ScrollCaptureSession session, @NonNull Rect captureArea);

    /**
     * Signals that capture has ended. Implementations should release any temporary resources or
     * references to objects in use during the capture. Any resources obtained from the session are
     * now invalid and attempts to use them after this point may throw an exception.
     * <p>
     * The window should be returned as much as possible to its original state when capture started.
     * At a minimum, the content should be scrolled to its original position.
     * <p>
     * <code>onReady</code> should be called when the window should be made visible and
     * interactive. The system will wait up to XXXms for this call before proceeding.
     * <p>
     * TODO: finalize timeout
     *
     * @param onReady a callback to inform the system that the application has completed any
     *                cleanup and is ready to become visible
     */
    void onScrollCaptureEnd(@NonNull Runnable onReady);
}

