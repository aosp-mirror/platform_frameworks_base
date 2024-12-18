/*
 * Copyright 2024 The Android Open Source Project
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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link MotionEvent} processor that forwards scrolls on the letterbox area to the app's view
 * hierarchy by translating the coordinates to app's inbound area.
 *
 * @hide
 */
@VisibleForTesting(visibility = PACKAGE)
public class LetterboxScrollProcessor {

    private enum LetterboxScrollState {
        AWAITING_GESTURE_START,
        GESTURE_STARTED_IN_APP,
        GESTURE_STARTED_OUTSIDE_APP,
        SCROLLING_STARTED_OUTSIDE_APP
    }

    @NonNull private LetterboxScrollState mState = LetterboxScrollState.AWAITING_GESTURE_START;
    @NonNull private final List<MotionEvent> mProcessedEvents = new ArrayList<>();

    @NonNull private final GestureDetector mScrollDetector;
    @NonNull private final Context mContext;

    /** IDs of events generated from this class */
    private final Set<Integer> mGeneratedEventIds = new HashSet<>();

    @VisibleForTesting(visibility = PACKAGE)
    public LetterboxScrollProcessor(@NonNull Context context, @Nullable Handler handler) {
        mContext = context;
        mScrollDetector = new GestureDetector(context, new ScrollListener(), handler);
    }

    /**
     * Processes the MotionEvent. If the gesture is started in the app's bounds, or moves over the
     * app then the motion events are not adjusted. Motion events from outside the app's
     * bounds that are detected as a scroll gesture are adjusted to be over the app's bounds.
     * Otherwise (if the events are outside the app's bounds and not part of a scroll gesture), the
     * motion events are ignored.
     *
     * @param motionEvent The MotionEvent to process.
     * @return The list of adjusted events, or null if no adjustments are needed. The list is empty
     * if the event should be ignored. Do not keep a reference to the output as the list is reused.
     */
    @Nullable
    @VisibleForTesting(visibility = PACKAGE)
    public List<MotionEvent> processMotionEvent(@NonNull MotionEvent motionEvent) {
        if (!motionEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            // This is a non-pointer event that doesn't correspond to any location on the screen.
            // Ignore it.
            return null;
        }
        mProcessedEvents.clear();
        final Rect appBounds = getAppBounds();

        // Set state at the start of the gesture (when ACTION_DOWN is received)
        if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
            if (isOutsideAppBounds(motionEvent, appBounds)) {
                mState = LetterboxScrollState.GESTURE_STARTED_OUTSIDE_APP;
            } else {
                mState = LetterboxScrollState.GESTURE_STARTED_IN_APP;
            }
        }

        boolean makeNoAdjustments = false;

        switch (mState) {
            case AWAITING_GESTURE_START:
            case GESTURE_STARTED_IN_APP:
                // Do not adjust events if gesture is started in or is over the app.
                makeNoAdjustments = true;
                break;

            case GESTURE_STARTED_OUTSIDE_APP:
                // Send offset events to the scroll-detector. These events are not added to
                // mProcessedEvents and are therefore ignored until detected as part of a scroll.
                applyOffset(motionEvent, appBounds);
                mScrollDetector.onTouchEvent(motionEvent);
                // If scroll-detector triggered, then the state is changed to
                // SCROLLING_STARTED_OUTSIDE_APP (scroll detector can only trigger after an
                // ACTION_MOVE event is received).
                if (mState == LetterboxScrollState.SCROLLING_STARTED_OUTSIDE_APP) {
                    // Also, include ACTION_MOVE motion event that triggered the scroll-detector.
                    mProcessedEvents.add(motionEvent);
                }
                break;

            // Once scroll-detector has detected scrolling, offset is applied to the gesture.
            case SCROLLING_STARTED_OUTSIDE_APP:
                if (isOutsideAppBounds(motionEvent, appBounds)) {
                    // Offset the event to be over the app if the event is out-of-bounds.
                    applyOffset(motionEvent, appBounds);
                } else {
                    // Otherwise, the gesture is already over the app so stop offsetting it.
                    mState = LetterboxScrollState.GESTURE_STARTED_IN_APP;
                }
                mProcessedEvents.add(motionEvent);
                break;
        }

        // Reset state at the end of the gesture
        if (motionEvent.getAction() == MotionEvent.ACTION_UP
                || motionEvent.getAction() == MotionEvent.ACTION_CANCEL) {
            mState = LetterboxScrollState.AWAITING_GESTURE_START;
        }

        return makeNoAdjustments ? null : mProcessedEvents;
    }

    /**
     * Processes the InputEvent for compatibility before it is finished by calling
     * InputEventReceiver#finishInputEvent().
     *
     * @param motionEvent The MotionEvent to process.
     * @return The motionEvent to finish, or null if it should not be finished.
     */
    @Nullable
    @VisibleForTesting(visibility = PACKAGE)
    public InputEvent processMotionEventBeforeFinish(@NonNull MotionEvent motionEvent) {
        return mGeneratedEventIds.remove(motionEvent.getId()) ? null : motionEvent;
    }

    @NonNull
    private Rect getAppBounds() {
        return mContext.getResources().getConfiguration().windowConfiguration.getBounds();
    }

    /** Checks whether the gesture is located on the letterbox area. */
    private boolean isOutsideAppBounds(@NonNull MotionEvent motionEvent, @NonNull Rect appBounds) {
        // The events are in the coordinate system of the ViewRootImpl (window). The window might
        // not have the same dimensions as the app bounds - for example in case of Dialogs - thus
        // `getRawX()` and `getRawY()` are used, with the absolute bounds (left, top, etc) instead
        // of width and height.
        // The event should be passed to the app if it has happened anywhere in the app area,
        // irrespective of the current window size, therefore the app bounds are used instead of the
        // current window.
        return motionEvent.getRawX() < appBounds.left
                || motionEvent.getRawX() >= appBounds.right
                || motionEvent.getRawY() < appBounds.top
                || motionEvent.getRawY() >= appBounds.bottom;
    }

    private void applyOffset(@NonNull MotionEvent event, @NonNull Rect appBounds) {
        float horizontalOffset = calculateOffset(event.getX(), appBounds.width());
        float verticalOffset = calculateOffset(event.getY(), appBounds.height());
        // Apply the offset to the motion event so it is over the app's view.
        event.offsetLocation(horizontalOffset, verticalOffset);
    }

    private float calculateOffset(float eventCoord, int appBoundary) {
        if (eventCoord < 0) {
            return -eventCoord;
        } else if (eventCoord >= appBoundary) {
            return -(eventCoord - appBoundary + 1);
        } else {
            return 0;
        }
    }

    private class ScrollListener extends GestureDetector.SimpleOnGestureListener {
        private ScrollListener() {}

        @Override
        public boolean onScroll(
                @Nullable MotionEvent actionDownEvent,
                @NonNull MotionEvent actionMoveEvent,
                float distanceX,
                float distanceY) {
            // Inject in-bounds ACTION_DOWN event before continuing gesture with offset.
            final MotionEvent newActionDownEvent = MotionEvent.obtain(
                    Objects.requireNonNull(actionDownEvent));
            Rect appBounds = getAppBounds();
            applyOffset(newActionDownEvent, appBounds);
            mGeneratedEventIds.add(newActionDownEvent.getId());
            mProcessedEvents.add(newActionDownEvent);

            // Change state when onScroll method is triggered - at this point, the passed event is
            // known to be 'part of' a scroll gesture.
            mState = LetterboxScrollState.SCROLLING_STARTED_OUTSIDE_APP;

            return super.onScroll(actionDownEvent, actionMoveEvent, distanceX, distanceY);
        }
    }
}
