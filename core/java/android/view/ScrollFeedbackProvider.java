/**
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.view.flags.Flags;

/**
 * Provides feedback to the user for scroll events on a {@link View}. The type of feedback provided
 * to the user may depend on the {@link InputDevice} that generated the scroll events.
 *
 * <p>An example of the type of feedback that this interface may provide is haptic feedback (that
 * is, tactile feedback that provide the user physical feedback for their scroll).
 *
 * <p>The interface provides methods for the client to report different scroll events. The client
 * should report all scroll events that they want to be considered for scroll feedback using the
 * respective methods. The interface will process these events and provide scroll feedback based on
 * its specific feedback implementation.
 *
 * <h3>Obtaining the correct arguments for methods in this interface</h3>
 *
 * <p>Methods in this interface rely on the provision of valid {@link InputDevice} ID and source, as
 * well as the {@link MotionEvent} axis that generated a specific scroll event. The
 * {@link InputDevice} represented by the provided ID must have a {@link InputDevice.MotionRange}
 * with the provided source and axis. See below for more details on obtaining the right arguments
 * for your method call.
 *
 * <ul>
 *
 * <li><p><b>inputDeviceId</b>: should always be the ID of the {@link InputDevice} that generated
 * the scroll event. If calling this method in response to a {@link MotionEvent}, use the device ID
 * that is reported by the event, which can be obtained using {@link MotionEvent#getDeviceId()}.
 * Otherwise, use a valid ID that is obtained from {@link InputDevice#getId()}, or from an
 * {@link android.hardware.input.InputManager} instance
 * ({@link android.hardware.input.InputManager#getInputDeviceIds()} gives all the valid input
 * device IDs).
 *
 * <li><p><b>source</b>: should always be the {@link InputDevice} source that generated the scroll
 * event. Use {@link MotionEvent#getSource()} if calling this method in response to a
 * {@link MotionEvent}. Otherwise, use a valid source for the {@link InputDevice}. You can use
 * {@link InputDevice#getMotionRanges()} to get all the {@link InputDevice.MotionRange}s for the
 * {@link InputDevice}, from which you can derive all the valid sources for the device.
 *
 * <li><p><b>axis</b>: should always be the axis whose axis value produced the scroll event.
 * A {@link MotionEvent} may report data for multiple axes, and each axis may have multiple data
 * points for different pointers. Use the axis whose movement produced the specific scroll event.
 * The motion value for an axis can be obtained using {@link MotionEvent#getAxisValue(int)}.
 * You can use {@link InputDevice#getMotionRanges()} to get all the {@link InputDevice.MotionRange}s
 * for the {@link InputDevice}, from which you can derive all the valid axes for the device.
 *
 * </ul>
 *
 * <b>Note</b> that not all valid input device source and motion axis inputs are necessarily
 * supported for scroll feedback; the implementation may choose to provide no feedback for some
 * valid input device source and motion axis arguments.
 */
@FlaggedApi(Flags.FLAG_SCROLL_FEEDBACK_API)
public interface ScrollFeedbackProvider {

    /**
     * Creates a {@link ScrollFeedbackProvider} implementation for this device.
     *
     * <p>Use a feedback provider created by this method, unless you intend to use your custom
     * scroll feedback providing logic. This allows your use cases to generate scroll feedback that
     * is consistent with the rest of the use cases on the device.
     *
     * @param view the {@link View} for which to provide scroll feedback.
     * @return the default {@link ScrollFeedbackProvider} implementation for the device.
     */
    @FlaggedApi(Flags.FLAG_SCROLL_FEEDBACK_API)
    @NonNull
    static ScrollFeedbackProvider createProvider(@NonNull View view) {
        return new HapticScrollFeedbackProvider(view);
    }

    /**
     * Call this when the view has snapped to an item.
     *
     * @param inputDeviceId the ID of the {@link InputDevice} that generated the motion triggering
     *          the snap.
     * @param source the input source of the motion causing the snap.
     * @param axis the axis of {@code event} that caused the item to snap.
     */
    @FlaggedApi(Flags.FLAG_SCROLL_FEEDBACK_API)
    void onSnapToItem(int inputDeviceId, int source, int axis);

    /**
     * Call this when the view has reached the scroll limit.
     *
     * <p>Note that a feedback may not be provided on every call to this method. This interface, for
     * instance, may provide feedback on every `N`th scroll limit event. For the interface to
     * properly provide feedback when needed, call this method for each scroll limit event that you
     * want to be accounted to scroll limit feedback.
     *
     * @param inputDeviceId the ID of the {@link InputDevice} that caused scrolling to hit limit.
     * @param source the input source of the motion that caused scrolling to hit the limit.
     * @param axis the axis of {@code event} that caused scrolling to hit the limit.
     * @param isStart {@code true} if scrolling hit limit at the start of the scrolling list, and
     *                {@code false} if the scrolling hit limit at the end of the scrolling list.
     *                <i>start</i> and <i>end<i> in this context are not geometrical references.
     *                Instead, they refer to the start and end of a scrolling experience. As such,
     *                "start" for some views may be at the bottom of a scrolling list, while it may
     *                be at the top of scrolling list for others.
     */
    @FlaggedApi(Flags.FLAG_SCROLL_FEEDBACK_API)
    void onScrollLimit(int inputDeviceId, int source, int axis, boolean isStart);

    /**
     * Call this when the view has scrolled.
     *
     * <p>Different axes have different ways to map their raw axis values to pixels for scrolling.
     * When calling this method, use the scroll values in pixels by which the view was scrolled; do
     * not use the raw axis values. That is, use whatever value is passed to one of View's scrolling
     * methods (example: {@link View#scrollBy(int, int)}). For example, for vertical scrolling on
     * {@link MotionEvent#AXIS_SCROLL}, convert the raw axis value to the equivalent pixels by using
     * {@link ViewConfiguration#getScaledVerticalScrollFactor()}, and use that value for this method
     * call.
     *
     * <p>Note that a feedback may not be provided on every call to this method. This interface, for
     * instance, may provide feedback for every `x` pixels scrolled. For the interface to properly
     * track scroll progress and provide feedback when needed, call this method for each scroll
     * event that you want to be accounted to scroll feedback.
     *
     * @param inputDeviceId the ID of the {@link InputDevice} that caused scroll progress.
     * @param source the input source of the motion that caused scroll progress.
     * @param axis the axis of {@code event} that caused scroll progress.
     * @param deltaInPixels the amount of scroll progress, in pixels.
     */
    @FlaggedApi(Flags.FLAG_SCROLL_FEEDBACK_API)
    void onScrollProgress(int inputDeviceId, int source, int axis, int deltaInPixels);
}
