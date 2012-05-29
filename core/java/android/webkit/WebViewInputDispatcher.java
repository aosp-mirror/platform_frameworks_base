/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.webkit;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * Perform asynchronous dispatch of input events in a {@link WebView}.
 *
 * This dispatcher is shared by the UI thread ({@link WebViewClassic}) and web kit
 * thread ({@link WebViewCore}).  The UI thread enqueues events for
 * processing, waits for the web kit thread to handle them, and then performs
 * additional processing depending on the outcome.
 *
 * How it works:
 *
 * 1. The web view thread receives an input event from the input system on the UI
 * thread in its {@link WebViewClassic#onTouchEvent} handler.  It sends the input event
 * to the dispatcher, then immediately returns true to the input system to indicate that
 * it will handle the event.
 *
 * 2. The web kit thread is notified that an event has been enqueued.  Meanwhile additional
 * events may be enqueued from the UI thread.  In some cases, the dispatcher may decide to
 * coalesce motion events into larger batches or to cancel events that have been
 * sitting in the queue for too long.
 *
 * 3. The web kit thread wakes up and handles all input events that are waiting for it.
 * After processing each input event, it informs the dispatcher whether the web application
 * has decided to handle the event itself and to prevent default event handling.
 *
 * 4. If web kit indicates that it wants to prevent default event handling, then web kit
 * consumes the remainder of the gesture and web view receives a cancel event if
 * needed.  Otherwise, the web view handles the gesture on the UI thread normally.
 *
 * 5. If the web kit thread takes too long to handle an input event, then it loses the
 * right to handle it.  The dispatcher synthesizes a cancellation event for web kit and
 * then tells the web view on the UI thread to handle the event that timed out along
 * with the rest of the gesture.
 *
 * One thing to keep in mind about the dispatcher is that what goes into the dispatcher
 * is not necessarily what the web kit or UI thread will see.  As mentioned above, the
 * dispatcher may tweak the input event stream to improve responsiveness.  Both web view and
 * web kit are guaranteed to perceive a consistent stream of input events but
 * they might not always see the same events (especially if one decides
 * to prevent the other from handling a particular gesture).
 *
 * This implementation very deliberately does not refer to the {@link WebViewClassic}
 * or {@link WebViewCore} classes, preferring to communicate with them only via
 * interfaces to avoid unintentional coupling to their implementation details.
 *
 * Currently, the input dispatcher only handles pointer events (includes touch,
 * hover and scroll events).  In principle, it could be extended to handle trackball
 * and key events if needed.
 *
 * @hide
 */
final class WebViewInputDispatcher {
    private static final String TAG = "WebViewInputDispatcher";
    private static final boolean DEBUG = false;
    // This enables batching of MotionEvents. It will combine multiple MotionEvents
    // together into a single MotionEvent if more events come in while we are
    // still waiting on the processing of a previous event.
    // If this is set to false, we will instead opt to drop ACTION_MOVE
    // events we cannot keep up with.
    // TODO: If batching proves to be working well, remove this
    private static final boolean ENABLE_EVENT_BATCHING = true;

    private final Object mLock = new Object();

    // Pool of queued input events.  (guarded by mLock)
    private static final int MAX_DISPATCH_EVENT_POOL_SIZE = 10;
    private DispatchEvent mDispatchEventPool;
    private int mDispatchEventPoolSize;

    // Posted state, tracks events posted to the dispatcher.  (guarded by mLock)
    private final TouchStream mPostTouchStream = new TouchStream();
    private boolean mPostSendTouchEventsToWebKit;
    private boolean mPostDoNotSendTouchEventsToWebKitUntilNextGesture;
    private boolean mPostLongPressScheduled;
    private boolean mPostClickScheduled;
    private boolean mPostShowTapHighlightScheduled;
    private boolean mPostHideTapHighlightScheduled;
    private int mPostLastWebKitXOffset;
    private int mPostLastWebKitYOffset;
    private float mPostLastWebKitScale;

    // State for event tracking (click, longpress, double tap, etc..)
    private boolean mIsDoubleTapCandidate;
    private boolean mIsTapCandidate;
    private float mInitialDownX;
    private float mInitialDownY;
    private float mTouchSlopSquared;
    private float mDoubleTapSlopSquared;

    // Web kit state, tracks events observed by web kit.  (guarded by mLock)
    private final DispatchEventQueue mWebKitDispatchEventQueue = new DispatchEventQueue();
    private final TouchStream mWebKitTouchStream = new TouchStream();
    private final WebKitCallbacks mWebKitCallbacks;
    private final WebKitHandler mWebKitHandler;
    private boolean mWebKitDispatchScheduled;
    private boolean mWebKitTimeoutScheduled;
    private long mWebKitTimeoutTime;

    // UI state, tracks events observed by the UI.  (guarded by mLock)
    private final DispatchEventQueue mUiDispatchEventQueue = new DispatchEventQueue();
    private final TouchStream mUiTouchStream = new TouchStream();
    private final UiCallbacks mUiCallbacks;
    private final UiHandler mUiHandler;
    private boolean mUiDispatchScheduled;

    // Give up on web kit handling of input events when this timeout expires.
    private static final long WEBKIT_TIMEOUT_MILLIS = 200;
    private static final int TAP_TIMEOUT = ViewConfiguration.getTapTimeout();
    private static final int LONG_PRESS_TIMEOUT =
            ViewConfiguration.getLongPressTimeout() + TAP_TIMEOUT;
    private static final int DOUBLE_TAP_TIMEOUT = ViewConfiguration.getDoubleTapTimeout();
    private static final int PRESSED_STATE_DURATION = ViewConfiguration.getPressedStateDuration();

    /**
     * Event type: Indicates a touch event type.
     *
     * This event is delivered together with a {@link MotionEvent} with one of the
     * following actions: {@link MotionEvent#ACTION_DOWN}, {@link MotionEvent#ACTION_MOVE},
     * {@link MotionEvent#ACTION_UP}, {@link MotionEvent#ACTION_POINTER_DOWN},
     * {@link MotionEvent#ACTION_POINTER_UP}, {@link MotionEvent#ACTION_CANCEL}.
     */
    public static final int EVENT_TYPE_TOUCH = 0;

    /**
     * Event type: Indicates a hover event type.
     *
     * This event is delivered together with a {@link MotionEvent} with one of the
     * following actions: {@link MotionEvent#ACTION_HOVER_ENTER},
     * {@link MotionEvent#ACTION_HOVER_MOVE}, {@link MotionEvent#ACTION_HOVER_MOVE}.
     */
    public static final int EVENT_TYPE_HOVER = 1;

    /**
     * Event type: Indicates a scroll event type.
     *
     * This event is delivered together with a {@link MotionEvent} with action
     * {@link MotionEvent#ACTION_SCROLL}.
     */
    public static final int EVENT_TYPE_SCROLL = 2;

    /**
     * Event type: Indicates a long-press event type.
     *
     * This event is delivered in the middle of a sequence of {@link #EVENT_TYPE_TOUCH} events.
     * It includes a {@link MotionEvent} with action {@link MotionEvent#ACTION_MOVE}
     * that indicates the current touch coordinates of the long-press.
     *
     * This event is sent when the current touch gesture has been held longer than
     * the long-press interval.
     */
    public static final int EVENT_TYPE_LONG_PRESS = 3;

    /**
     * Event type: Indicates a click event type.
     *
     * This event is delivered after a sequence of {@link #EVENT_TYPE_TOUCH} events that
     * comprise a complete gesture ending with {@link MotionEvent#ACTION_UP}.
     * It includes a {@link MotionEvent} with action {@link MotionEvent#ACTION_UP}
     * that indicates the location of the click.
     *
     * This event is sent shortly after the end of a touch after the double-tap
     * interval has expired to indicate a click.
     */
    public static final int EVENT_TYPE_CLICK = 4;

    /**
     * Event type: Indicates a double-tap event type.
     *
     * This event is delivered after a sequence of {@link #EVENT_TYPE_TOUCH} events that
     * comprise a complete gesture ending with {@link MotionEvent#ACTION_UP}.
     * It includes a {@link MotionEvent} with action {@link MotionEvent#ACTION_UP}
     * that indicates the location of the double-tap.
     *
     * This event is sent immediately after a sequence of two touches separated
     * in time by no more than the double-tap interval and separated in space
     * by no more than the double-tap slop.
     */
    public static final int EVENT_TYPE_DOUBLE_TAP = 5;

    /**
     * Event type: Indicates that a hit test should be performed
     */
    public static final int EVENT_TYPE_HIT_TEST = 6;

    /**
     * Flag: This event is private to this queue.  Do not forward it.
     */
    public static final int FLAG_PRIVATE = 1 << 0;

    /**
     * Flag: This event is currently being processed by web kit.
     * If a timeout occurs, make a copy of it before forwarding the event to another queue.
     */
    public static final int FLAG_WEBKIT_IN_PROGRESS = 1 << 1;

    /**
     * Flag: A timeout occurred while waiting for web kit to process this input event.
     */
    public static final int FLAG_WEBKIT_TIMEOUT = 1 << 2;

    /**
     * Flag: Indicates that the event was transformed for delivery to web kit.
     * The event must be transformed back before being delivered to the UI.
     */
    public static final int FLAG_WEBKIT_TRANSFORMED_EVENT = 1 << 3;

    public WebViewInputDispatcher(UiCallbacks uiCallbacks, WebKitCallbacks webKitCallbacks) {
        this.mUiCallbacks = uiCallbacks;
        mUiHandler = new UiHandler(uiCallbacks.getUiLooper());

        this.mWebKitCallbacks = webKitCallbacks;
        mWebKitHandler = new WebKitHandler(webKitCallbacks.getWebKitLooper());

        ViewConfiguration config = ViewConfiguration.get(mUiCallbacks.getContext());
        mDoubleTapSlopSquared = config.getScaledDoubleTapSlop();
        mDoubleTapSlopSquared = (mDoubleTapSlopSquared * mDoubleTapSlopSquared);
        mTouchSlopSquared = config.getScaledTouchSlop();
        mTouchSlopSquared = (mTouchSlopSquared * mTouchSlopSquared);
    }

    /**
     * Sets whether web kit wants to receive touch events.
     *
     * @param enable True to enable dispatching of touch events to web kit, otherwise
     * web kit will be skipped.
     */
    public void setWebKitWantsTouchEvents(boolean enable) {
        if (DEBUG) {
            Log.d(TAG, "webkitWantsTouchEvents: " + enable);
        }
        synchronized (mLock) {
            if (mPostSendTouchEventsToWebKit != enable) {
                if (!enable) {
                    enqueueWebKitCancelTouchEventIfNeededLocked();
                }
                mPostSendTouchEventsToWebKit = enable;
            }
        }
    }

    /**
     * Posts a pointer event to the dispatch queue.
     *
     * @param event The event to post.
     * @param webKitXOffset X offset to apply to events before dispatching them to web kit.
     * @param webKitYOffset Y offset to apply to events before dispatching them to web kit.
     * @param webKitScale The scale factor to apply to translated events before dispatching
     * them to web kit.
     * @return True if the dispatcher will handle the event, false if the event is unsupported.
     */
    public boolean postPointerEvent(MotionEvent event,
            int webKitXOffset, int webKitYOffset, float webKitScale) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }

        if (DEBUG) {
            Log.d(TAG, "postPointerEvent: " + event);
        }

        final int action = event.getActionMasked();
        final int eventType;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                eventType = EVENT_TYPE_TOUCH;
                break;
            case MotionEvent.ACTION_SCROLL:
                eventType = EVENT_TYPE_SCROLL;
                break;
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                eventType = EVENT_TYPE_HOVER;
                break;
            default:
                return false; // currently unsupported event type
        }

        synchronized (mLock) {
            // Ensure that the event is consistent and should be delivered.
            MotionEvent eventToEnqueue = event;
            if (eventType == EVENT_TYPE_TOUCH) {
                eventToEnqueue = mPostTouchStream.update(event);
                if (eventToEnqueue == null) {
                    if (DEBUG) {
                        Log.d(TAG, "postPointerEvent: dropped event " + event);
                    }
                    unscheduleLongPressLocked();
                    unscheduleClickLocked();
                    hideTapCandidateLocked();
                    return false;
                }

                if (action == MotionEvent.ACTION_DOWN && mPostSendTouchEventsToWebKit) {
                    if (mUiCallbacks.shouldInterceptTouchEvent(eventToEnqueue)) {
                        mPostDoNotSendTouchEventsToWebKitUntilNextGesture = true;
                    } else if (mPostDoNotSendTouchEventsToWebKitUntilNextGesture) {
                        // Recover from a previous web kit timeout.
                        mPostDoNotSendTouchEventsToWebKitUntilNextGesture = false;
                    }
                }
            }

            // Copy the event because we need to retain ownership.
            if (eventToEnqueue == event) {
                eventToEnqueue = event.copy();
            }

            DispatchEvent d = obtainDispatchEventLocked(eventToEnqueue, eventType, 0,
                    webKitXOffset, webKitYOffset, webKitScale);
            updateStateTrackersLocked(d, event);
            enqueueEventLocked(d);
        }
        return true;
    }

    private void scheduleLongPressLocked() {
        unscheduleLongPressLocked();
        mPostLongPressScheduled = true;
        mUiHandler.sendEmptyMessageDelayed(UiHandler.MSG_LONG_PRESS,
                LONG_PRESS_TIMEOUT);
    }

    private void unscheduleLongPressLocked() {
        if (mPostLongPressScheduled) {
            mPostLongPressScheduled = false;
            mUiHandler.removeMessages(UiHandler.MSG_LONG_PRESS);
        }
    }

    private void postLongPress() {
        synchronized (mLock) {
            if (!mPostLongPressScheduled) {
                return;
            }
            mPostLongPressScheduled = false;

            MotionEvent event = mPostTouchStream.getLastEvent();
            if (event == null) {
                return;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    break;
                default:
                    return;
            }

            MotionEvent eventToEnqueue = MotionEvent.obtainNoHistory(event);
            eventToEnqueue.setAction(MotionEvent.ACTION_MOVE);
            DispatchEvent d = obtainDispatchEventLocked(eventToEnqueue, EVENT_TYPE_LONG_PRESS, 0,
                    mPostLastWebKitXOffset, mPostLastWebKitYOffset, mPostLastWebKitScale);
            enqueueEventLocked(d);
        }
    }

    private void hideTapCandidateLocked() {
        unscheduleHideTapHighlightLocked();
        unscheduleShowTapHighlightLocked();
        mUiCallbacks.showTapHighlight(false);
    }

    private void showTapCandidateLocked() {
        unscheduleHideTapHighlightLocked();
        unscheduleShowTapHighlightLocked();
        mUiCallbacks.showTapHighlight(true);
    }

    private void scheduleShowTapHighlightLocked() {
        unscheduleShowTapHighlightLocked();
        mPostShowTapHighlightScheduled = true;
        mUiHandler.sendEmptyMessageDelayed(UiHandler.MSG_SHOW_TAP_HIGHLIGHT,
                TAP_TIMEOUT);
    }

    private void unscheduleShowTapHighlightLocked() {
        if (mPostShowTapHighlightScheduled) {
            mPostShowTapHighlightScheduled = false;
            mUiHandler.removeMessages(UiHandler.MSG_SHOW_TAP_HIGHLIGHT);
        }
    }

    private void scheduleHideTapHighlightLocked() {
        unscheduleHideTapHighlightLocked();
        mPostHideTapHighlightScheduled = true;
        mUiHandler.sendEmptyMessageDelayed(UiHandler.MSG_HIDE_TAP_HIGHLIGHT,
                PRESSED_STATE_DURATION);
    }

    private void unscheduleHideTapHighlightLocked() {
        if (mPostHideTapHighlightScheduled) {
            mPostHideTapHighlightScheduled = false;
            mUiHandler.removeMessages(UiHandler.MSG_HIDE_TAP_HIGHLIGHT);
        }
    }

    private void postShowTapHighlight(boolean show) {
        synchronized (mLock) {
            if (show) {
                if (!mPostShowTapHighlightScheduled) {
                    return;
                }
                mPostShowTapHighlightScheduled = false;
            } else {
                if (!mPostHideTapHighlightScheduled) {
                    return;
                }
                mPostHideTapHighlightScheduled = false;
            }
            mUiCallbacks.showTapHighlight(show);
        }
    }

    private void scheduleClickLocked() {
        unscheduleClickLocked();
        mPostClickScheduled = true;
        mUiHandler.sendEmptyMessageDelayed(UiHandler.MSG_CLICK, DOUBLE_TAP_TIMEOUT);
    }

    private void unscheduleClickLocked() {
        if (mPostClickScheduled) {
            mPostClickScheduled = false;
            mUiHandler.removeMessages(UiHandler.MSG_CLICK);
        }
    }

    private void postClick() {
        synchronized (mLock) {
            if (!mPostClickScheduled) {
                return;
            }
            mPostClickScheduled = false;

            MotionEvent event = mPostTouchStream.getLastEvent();
            if (event == null || event.getAction() != MotionEvent.ACTION_UP) {
                return;
            }

            showTapCandidateLocked();
            MotionEvent eventToEnqueue = MotionEvent.obtainNoHistory(event);
            DispatchEvent d = obtainDispatchEventLocked(eventToEnqueue, EVENT_TYPE_CLICK, 0,
                    mPostLastWebKitXOffset, mPostLastWebKitYOffset, mPostLastWebKitScale);
            enqueueEventLocked(d);
        }
    }

    private void checkForDoubleTapOnDownLocked(MotionEvent event) {
        mIsDoubleTapCandidate = false;
        if (!mPostClickScheduled) {
            return;
        }
        int deltaX = (int) mInitialDownX - (int) event.getX();
        int deltaY = (int) mInitialDownY - (int) event.getY();
        if ((deltaX * deltaX + deltaY * deltaY) < mDoubleTapSlopSquared) {
            unscheduleClickLocked();
            mIsDoubleTapCandidate = true;
        }
    }

    private boolean isClickCandidateLocked(MotionEvent event) {
        if (event == null
                || event.getActionMasked() != MotionEvent.ACTION_UP
                || !mIsTapCandidate) {
            return false;
        }
        long downDuration = event.getEventTime() - event.getDownTime();
        return downDuration < LONG_PRESS_TIMEOUT;
    }

    private void enqueueDoubleTapLocked(MotionEvent event) {
        MotionEvent eventToEnqueue = MotionEvent.obtainNoHistory(event);
        DispatchEvent d = obtainDispatchEventLocked(eventToEnqueue, EVENT_TYPE_DOUBLE_TAP, 0,
                mPostLastWebKitXOffset, mPostLastWebKitYOffset, mPostLastWebKitScale);
        enqueueEventLocked(d);
    }

    private void enqueueHitTestLocked(MotionEvent event) {
        mUiCallbacks.clearPreviousHitTest();
        MotionEvent eventToEnqueue = MotionEvent.obtainNoHistory(event);
        DispatchEvent d = obtainDispatchEventLocked(eventToEnqueue, EVENT_TYPE_HIT_TEST, 0,
                mPostLastWebKitXOffset, mPostLastWebKitYOffset, mPostLastWebKitScale);
        enqueueEventLocked(d);
    }

    private void checkForSlopLocked(MotionEvent event) {
        if (!mIsTapCandidate) {
            return;
        }
        int deltaX = (int) mInitialDownX - (int) event.getX();
        int deltaY = (int) mInitialDownY - (int) event.getY();
        if ((deltaX * deltaX + deltaY * deltaY) > mTouchSlopSquared) {
            unscheduleLongPressLocked();
            mIsTapCandidate = false;
            hideTapCandidateLocked();
        }
    }

    private void updateStateTrackersLocked(DispatchEvent d, MotionEvent event) {
        mPostLastWebKitXOffset = d.mWebKitXOffset;
        mPostLastWebKitYOffset = d.mWebKitYOffset;
        mPostLastWebKitScale = d.mWebKitScale;
        int action = event != null ? event.getAction() : MotionEvent.ACTION_CANCEL;
        if (d.mEventType != EVENT_TYPE_TOUCH) {
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL
                || event.getPointerCount() > 1) {
            unscheduleLongPressLocked();
            unscheduleClickLocked();
            hideTapCandidateLocked();
            mIsDoubleTapCandidate = false;
            mIsTapCandidate = false;
            hideTapCandidateLocked();
        } else if (action == MotionEvent.ACTION_DOWN) {
            checkForDoubleTapOnDownLocked(event);
            scheduleLongPressLocked();
            mIsTapCandidate = true;
            mInitialDownX = event.getX();
            mInitialDownY = event.getY();
            enqueueHitTestLocked(event);
            if (mIsDoubleTapCandidate) {
                hideTapCandidateLocked();
            } else {
                scheduleShowTapHighlightLocked();
            }
        } else if (action == MotionEvent.ACTION_UP) {
            unscheduleLongPressLocked();
            if (isClickCandidateLocked(event)) {
                if (mIsDoubleTapCandidate) {
                    hideTapCandidateLocked();
                    enqueueDoubleTapLocked(event);
                } else {
                    scheduleClickLocked();
                }
            } else {
                hideTapCandidateLocked();
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            checkForSlopLocked(event);
        }
    }

    /**
     * Dispatches pending web kit events.
     * Must only be called from the web kit thread.
     *
     * This method may be used to flush the queue of pending input events
     * immediately.  This method may help to reduce input dispatch latency
     * if called before certain expensive operations such as drawing.
     */
    public void dispatchWebKitEvents() {
        dispatchWebKitEvents(false);
    }

    private void dispatchWebKitEvents(boolean calledFromHandler) {
        for (;;) {
            // Get the next event, but leave it in the queue so we can move it to the UI
            // queue if a timeout occurs.
            DispatchEvent d;
            MotionEvent event;
            final int eventType;
            int flags;
            synchronized (mLock) {
                if (!ENABLE_EVENT_BATCHING) {
                    drainStaleWebKitEventsLocked();
                }
                d = mWebKitDispatchEventQueue.mHead;
                if (d == null) {
                    if (mWebKitDispatchScheduled) {
                        mWebKitDispatchScheduled = false;
                        if (!calledFromHandler) {
                            mWebKitHandler.removeMessages(
                                    WebKitHandler.MSG_DISPATCH_WEBKIT_EVENTS);
                        }
                    }
                    return;
                }

                event = d.mEvent;
                if (event != null) {
                    event.offsetLocation(d.mWebKitXOffset, d.mWebKitYOffset);
                    event.scale(d.mWebKitScale);
                    d.mFlags |= FLAG_WEBKIT_TRANSFORMED_EVENT;
                }

                eventType = d.mEventType;
                if (eventType == EVENT_TYPE_TOUCH) {
                    event = mWebKitTouchStream.update(event);
                    if (DEBUG && event == null && d.mEvent != null) {
                        Log.d(TAG, "dispatchWebKitEvents: dropped event " + d.mEvent);
                    }
                }

                d.mFlags |= FLAG_WEBKIT_IN_PROGRESS;
                flags = d.mFlags;
            }

            // Handle the event.
            final boolean preventDefault;
            if (event == null) {
                preventDefault = false;
            } else {
                preventDefault = dispatchWebKitEvent(event, eventType, flags);
            }

            synchronized (mLock) {
                flags = d.mFlags;
                d.mFlags = flags & ~FLAG_WEBKIT_IN_PROGRESS;
                boolean recycleEvent = event != d.mEvent;

                if ((flags & FLAG_WEBKIT_TIMEOUT) != 0) {
                    // A timeout occurred!
                    recycleDispatchEventLocked(d);
                } else {
                    // Web kit finished in a timely manner.  Dequeue the event.
                    assert mWebKitDispatchEventQueue.mHead == d;
                    mWebKitDispatchEventQueue.dequeue();

                    updateWebKitTimeoutLocked();

                    if ((flags & FLAG_PRIVATE) != 0) {
                        // Event was intended for web kit only.  All done.
                        recycleDispatchEventLocked(d);
                    } else if (preventDefault) {
                        // Web kit has decided to consume the event!
                        if (d.mEventType == EVENT_TYPE_TOUCH) {
                            enqueueUiCancelTouchEventIfNeededLocked();
                            unscheduleLongPressLocked();
                        }
                    } else {
                        // Web kit is being friendly.  Pass the event to the UI.
                        enqueueUiEventUnbatchedLocked(d);
                    }
                }

                if (event != null && recycleEvent) {
                    event.recycle();
                }

                if (eventType == EVENT_TYPE_CLICK) {
                    scheduleHideTapHighlightLocked();
                }
            }
        }
    }

    // Runs on web kit thread.
    private boolean dispatchWebKitEvent(MotionEvent event, int eventType, int flags) {
        if (DEBUG) {
            Log.d(TAG, "dispatchWebKitEvent: event=" + event
                    + ", eventType=" + eventType + ", flags=" + flags);
        }
        boolean preventDefault = mWebKitCallbacks.dispatchWebKitEvent(
                this, event, eventType, flags);
        if (DEBUG) {
            Log.d(TAG, "dispatchWebKitEvent: preventDefault=" + preventDefault);
        }
        return preventDefault;
    }

    private boolean isMoveEventLocked(DispatchEvent d) {
        return d.mEvent != null
                && d.mEvent.getActionMasked() == MotionEvent.ACTION_MOVE;
    }

    private void drainStaleWebKitEventsLocked() {
        DispatchEvent d = mWebKitDispatchEventQueue.mHead;
        while (d != null && d.mNext != null
                && isMoveEventLocked(d)
                && isMoveEventLocked(d.mNext)) {
            DispatchEvent next = d.mNext;
            skipWebKitEventLocked(d);
            d = next;
        }
        mWebKitDispatchEventQueue.mHead = d;
    }

    // Called by WebKit when it doesn't care about the rest of the touch stream
    public void skipWebkitForRemainingTouchStream() {
        // Just treat this like a timeout
        handleWebKitTimeout();
    }

    // Runs on UI thread in response to the web kit thread appearing to be unresponsive.
    private void handleWebKitTimeout() {
        synchronized (mLock) {
            if (!mWebKitTimeoutScheduled) {
                return;
            }
            mWebKitTimeoutScheduled = false;

            if (DEBUG) {
                Log.d(TAG, "handleWebKitTimeout: timeout occurred!");
            }

            // Drain the web kit event queue.
            DispatchEvent d = mWebKitDispatchEventQueue.dequeueList();

            // If web kit was processing an event (must be at the head of the list because
            // it can only do one at a time), then clone it or ignore it.
            if ((d.mFlags & FLAG_WEBKIT_IN_PROGRESS) != 0) {
                d.mFlags |= FLAG_WEBKIT_TIMEOUT;
                if ((d.mFlags & FLAG_PRIVATE) != 0) {
                    d = d.mNext; // the event is private to web kit, ignore it
                } else {
                    d = copyDispatchEventLocked(d);
                    d.mFlags &= ~FLAG_WEBKIT_IN_PROGRESS;
                }
            }

            // Enqueue all non-private events for handling by the UI thread.
            while (d != null) {
                DispatchEvent next = d.mNext;
                skipWebKitEventLocked(d);
                d = next;
            }

            // Tell web kit to cancel all pending touches.
            // This also prevents us from sending web kit any more touches until the
            // next gesture begins.  (As required to ensure touch event stream consistency.)
            enqueueWebKitCancelTouchEventIfNeededLocked();
        }
    }

    private void skipWebKitEventLocked(DispatchEvent d) {
        d.mNext = null;
        if ((d.mFlags & FLAG_PRIVATE) != 0) {
            recycleDispatchEventLocked(d);
        } else {
            d.mFlags |= FLAG_WEBKIT_TIMEOUT;
            enqueueUiEventUnbatchedLocked(d);
        }
    }

    /**
     * Dispatches pending UI events.
     * Must only be called from the UI thread.
     *
     * This method may be used to flush the queue of pending input events
     * immediately.  This method may help to reduce input dispatch latency
     * if called before certain expensive operations such as drawing.
     */
    public void dispatchUiEvents() {
        dispatchUiEvents(false);
    }

    private void dispatchUiEvents(boolean calledFromHandler) {
        for (;;) {
            MotionEvent event;
            final int eventType;
            final int flags;
            synchronized (mLock) {
                DispatchEvent d = mUiDispatchEventQueue.dequeue();
                if (d == null) {
                    if (mUiDispatchScheduled) {
                        mUiDispatchScheduled = false;
                        if (!calledFromHandler) {
                            mUiHandler.removeMessages(UiHandler.MSG_DISPATCH_UI_EVENTS);
                        }
                    }
                    return;
                }

                event = d.mEvent;
                if (event != null && (d.mFlags & FLAG_WEBKIT_TRANSFORMED_EVENT) != 0) {
                    event.scale(1.0f / d.mWebKitScale);
                    event.offsetLocation(-d.mWebKitXOffset, -d.mWebKitYOffset);
                    d.mFlags &= ~FLAG_WEBKIT_TRANSFORMED_EVENT;
                }

                eventType = d.mEventType;
                if (eventType == EVENT_TYPE_TOUCH) {
                    event = mUiTouchStream.update(event);
                    if (DEBUG && event == null && d.mEvent != null) {
                        Log.d(TAG, "dispatchUiEvents: dropped event " + d.mEvent);
                    }
                }

                flags = d.mFlags;

                if (event == d.mEvent) {
                    d.mEvent = null; // retain ownership of event, don't recycle it yet
                }
                recycleDispatchEventLocked(d);

                if (eventType == EVENT_TYPE_CLICK) {
                    scheduleHideTapHighlightLocked();
                }
            }

            // Handle the event.
            if (event != null) {
                dispatchUiEvent(event, eventType, flags);
                event.recycle();
            }
        }
    }

    // Runs on UI thread.
    private void dispatchUiEvent(MotionEvent event, int eventType, int flags) {
        if (DEBUG) {
            Log.d(TAG, "dispatchUiEvent: event=" + event
                    + ", eventType=" + eventType + ", flags=" + flags);
        }
        mUiCallbacks.dispatchUiEvent(event, eventType, flags);
    }

    private void enqueueEventLocked(DispatchEvent d) {
        if (!shouldSkipWebKit(d)) {
            enqueueWebKitEventLocked(d);
        } else {
            enqueueUiEventLocked(d);
        }
    }

    private boolean shouldSkipWebKit(DispatchEvent d) {
        switch (d.mEventType) {
            case EVENT_TYPE_CLICK:
            case EVENT_TYPE_HOVER:
            case EVENT_TYPE_SCROLL:
            case EVENT_TYPE_HIT_TEST:
                return false;
            case EVENT_TYPE_TOUCH:
                // TODO: This should be cleaned up. We now have WebViewInputDispatcher
                // and WebViewClassic both checking for slop and doing their own
                // thing - they should be consolidated. And by consolidated, I mean
                // WebViewClassic's version should just be deleted.
                // The reason this is done is because webpages seem to expect
                // that they only get an ontouchmove if the slop has been exceeded.
                if (mIsTapCandidate && d.mEvent != null
                        && d.mEvent.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    return true;
                }
                return !mPostSendTouchEventsToWebKit
                        || mPostDoNotSendTouchEventsToWebKitUntilNextGesture;
        }
        return true;
    }

    private void enqueueWebKitCancelTouchEventIfNeededLocked() {
        // We want to cancel touch events that were delivered to web kit.
        // Enqueue a null event at the end of the queue if needed.
        if (mWebKitTouchStream.isCancelNeeded() || !mWebKitDispatchEventQueue.isEmpty()) {
            DispatchEvent d = obtainDispatchEventLocked(null, EVENT_TYPE_TOUCH, FLAG_PRIVATE,
                    0, 0, 1.0f);
            enqueueWebKitEventUnbatchedLocked(d);
            mPostDoNotSendTouchEventsToWebKitUntilNextGesture = true;
        }
    }

    private void enqueueWebKitEventLocked(DispatchEvent d) {
        if (batchEventLocked(d, mWebKitDispatchEventQueue.mTail)) {
            if (DEBUG) {
                Log.d(TAG, "enqueueWebKitEventLocked: batched event " + d.mEvent);
            }
            recycleDispatchEventLocked(d);
        } else {
            enqueueWebKitEventUnbatchedLocked(d);
        }
    }

    private void enqueueWebKitEventUnbatchedLocked(DispatchEvent d) {
        if (DEBUG) {
            Log.d(TAG, "enqueueWebKitEventUnbatchedLocked: enqueued event " + d.mEvent);
        }
        mWebKitDispatchEventQueue.enqueue(d);
        scheduleWebKitDispatchLocked();
        updateWebKitTimeoutLocked();
    }

    private void scheduleWebKitDispatchLocked() {
        if (!mWebKitDispatchScheduled) {
            mWebKitHandler.sendEmptyMessage(WebKitHandler.MSG_DISPATCH_WEBKIT_EVENTS);
            mWebKitDispatchScheduled = true;
        }
    }

    private void updateWebKitTimeoutLocked() {
        DispatchEvent d = mWebKitDispatchEventQueue.mHead;
        if (d != null && mWebKitTimeoutScheduled && mWebKitTimeoutTime == d.mTimeoutTime) {
            return;
        }
        if (mWebKitTimeoutScheduled) {
            mUiHandler.removeMessages(UiHandler.MSG_WEBKIT_TIMEOUT);
            mWebKitTimeoutScheduled = false;
        }
        if (d != null) {
            mUiHandler.sendEmptyMessageAtTime(UiHandler.MSG_WEBKIT_TIMEOUT, d.mTimeoutTime);
            mWebKitTimeoutScheduled = true;
            mWebKitTimeoutTime = d.mTimeoutTime;
        }
    }

    private void enqueueUiCancelTouchEventIfNeededLocked() {
        // We want to cancel touch events that were delivered to the UI.
        // Enqueue a null event at the end of the queue if needed.
        if (mUiTouchStream.isCancelNeeded() || !mUiDispatchEventQueue.isEmpty()) {
            DispatchEvent d = obtainDispatchEventLocked(null, EVENT_TYPE_TOUCH, FLAG_PRIVATE,
                    0, 0, 1.0f);
            enqueueUiEventUnbatchedLocked(d);
        }
    }

    private void enqueueUiEventLocked(DispatchEvent d) {
        if (batchEventLocked(d, mUiDispatchEventQueue.mTail)) {
            if (DEBUG) {
                Log.d(TAG, "enqueueUiEventLocked: batched event " + d.mEvent);
            }
            recycleDispatchEventLocked(d);
        } else {
            enqueueUiEventUnbatchedLocked(d);
        }
    }

    private void enqueueUiEventUnbatchedLocked(DispatchEvent d) {
        if (DEBUG) {
            Log.d(TAG, "enqueueUiEventUnbatchedLocked: enqueued event " + d.mEvent);
        }
        mUiDispatchEventQueue.enqueue(d);
        scheduleUiDispatchLocked();
    }

    private void scheduleUiDispatchLocked() {
        if (!mUiDispatchScheduled) {
            mUiHandler.sendEmptyMessage(UiHandler.MSG_DISPATCH_UI_EVENTS);
            mUiDispatchScheduled = true;
        }
    }

    private boolean batchEventLocked(DispatchEvent in, DispatchEvent tail) {
        if (!ENABLE_EVENT_BATCHING) {
            return false;
        }
        if (tail != null && tail.mEvent != null && in.mEvent != null
                && in.mEventType == tail.mEventType
                && in.mFlags == tail.mFlags
                && in.mWebKitXOffset == tail.mWebKitXOffset
                && in.mWebKitYOffset == tail.mWebKitYOffset
                && in.mWebKitScale == tail.mWebKitScale) {
            return tail.mEvent.addBatch(in.mEvent);
        }
        return false;
    }

    private DispatchEvent obtainDispatchEventLocked(MotionEvent event,
            int eventType, int flags, int webKitXOffset, int webKitYOffset, float webKitScale) {
        DispatchEvent d = obtainUninitializedDispatchEventLocked();
        d.mEvent = event;
        d.mEventType = eventType;
        d.mFlags = flags;
        d.mTimeoutTime = SystemClock.uptimeMillis() + WEBKIT_TIMEOUT_MILLIS;
        d.mWebKitXOffset = webKitXOffset;
        d.mWebKitYOffset = webKitYOffset;
        d.mWebKitScale = webKitScale;
        if (DEBUG) {
            Log.d(TAG, "Timeout time: " + (d.mTimeoutTime - SystemClock.uptimeMillis()));
        }
        return d;
    }

    private DispatchEvent copyDispatchEventLocked(DispatchEvent d) {
        DispatchEvent copy = obtainUninitializedDispatchEventLocked();
        if (d.mEvent != null) {
            copy.mEvent = d.mEvent.copy();
        }
        copy.mEventType = d.mEventType;
        copy.mFlags = d.mFlags;
        copy.mTimeoutTime = d.mTimeoutTime;
        copy.mWebKitXOffset = d.mWebKitXOffset;
        copy.mWebKitYOffset = d.mWebKitYOffset;
        copy.mWebKitScale = d.mWebKitScale;
        copy.mNext = d.mNext;
        return copy;
    }

    private DispatchEvent obtainUninitializedDispatchEventLocked() {
        DispatchEvent d = mDispatchEventPool;
        if (d != null) {
            mDispatchEventPoolSize -= 1;
            mDispatchEventPool = d.mNext;
            d.mNext = null;
        } else {
            d = new DispatchEvent();
        }
        return d;
    }

    private void recycleDispatchEventLocked(DispatchEvent d) {
        if (d.mEvent != null) {
            d.mEvent.recycle();
            d.mEvent = null;
        }

        if (mDispatchEventPoolSize < MAX_DISPATCH_EVENT_POOL_SIZE) {
            mDispatchEventPoolSize += 1;
            d.mNext = mDispatchEventPool;
            mDispatchEventPool = d;
        }
    }

    /* Implemented by {@link WebViewClassic} to perform operations on the UI thread. */
    public static interface UiCallbacks {
        /**
         * Gets the UI thread's looper.
         * @return The looper.
         */
        public Looper getUiLooper();

        /**
         * Gets the UI's context
         * @return The context
         */
        public Context getContext();

        /**
         * Dispatches an event to the UI.
         * @param event The event.
         * @param eventType The event type.
         * @param flags The event's dispatch flags.
         */
        public void dispatchUiEvent(MotionEvent event, int eventType, int flags);

        /**
         * Asks the UI thread whether this touch event stream should be
         * intercepted based on the touch down event.
         * @param event The touch down event.
         * @return true if the UI stream wants the touch stream without going
         * through webkit or false otherwise.
         */
        public boolean shouldInterceptTouchEvent(MotionEvent event);

        /**
         * Inform's the UI that it should show the tap highlight
         * @param show True if it should show the highlight, false if it should hide it
         */
        public void showTapHighlight(boolean show);

        /**
         * Called when we are sending a new EVENT_TYPE_HIT_TEST to WebKit, so
         * previous hit tests should be cleared as they are obsolete.
         */
        public void clearPreviousHitTest();
    }

    /* Implemented by {@link WebViewCore} to perform operations on the web kit thread. */
    public static interface WebKitCallbacks {
        /**
         * Gets the web kit thread's looper.
         * @return The looper.
         */
        public Looper getWebKitLooper();

        /**
         * Dispatches an event to web kit.
         * @param dispatcher The WebViewInputDispatcher sending the event
         * @param event The event.
         * @param eventType The event type.
         * @param flags The event's dispatch flags.
         * @return True if web kit wants to prevent default event handling.
         */
        public boolean dispatchWebKitEvent(WebViewInputDispatcher dispatcher,
                MotionEvent event, int eventType, int flags);
    }

    // Runs on UI thread.
    private final class UiHandler extends Handler {
        public static final int MSG_DISPATCH_UI_EVENTS = 1;
        public static final int MSG_WEBKIT_TIMEOUT = 2;
        public static final int MSG_LONG_PRESS = 3;
        public static final int MSG_CLICK = 4;
        public static final int MSG_SHOW_TAP_HIGHLIGHT = 5;
        public static final int MSG_HIDE_TAP_HIGHLIGHT = 6;

        public UiHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISPATCH_UI_EVENTS:
                    dispatchUiEvents(true);
                    break;
                case MSG_WEBKIT_TIMEOUT:
                    handleWebKitTimeout();
                    break;
                case MSG_LONG_PRESS:
                    postLongPress();
                    break;
                case MSG_CLICK:
                    postClick();
                    break;
                case MSG_SHOW_TAP_HIGHLIGHT:
                    postShowTapHighlight(true);
                    break;
                case MSG_HIDE_TAP_HIGHLIGHT:
                    postShowTapHighlight(false);
                    break;
                default:
                    throw new IllegalStateException("Unknown message type: " + msg.what);
            }
        }
    }

    // Runs on web kit thread.
    private final class WebKitHandler extends Handler {
        public static final int MSG_DISPATCH_WEBKIT_EVENTS = 1;

        public WebKitHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISPATCH_WEBKIT_EVENTS:
                    dispatchWebKitEvents(true);
                    break;
                default:
                    throw new IllegalStateException("Unknown message type: " + msg.what);
            }
        }
    }

    private static final class DispatchEvent {
        public DispatchEvent mNext;

        public MotionEvent mEvent;
        public int mEventType;
        public int mFlags;
        public long mTimeoutTime;
        public int mWebKitXOffset;
        public int mWebKitYOffset;
        public float mWebKitScale;
    }

    private static final class DispatchEventQueue {
        public DispatchEvent mHead;
        public DispatchEvent mTail;

        public boolean isEmpty() {
            return mHead != null;
        }

        public void enqueue(DispatchEvent d) {
            if (mHead == null) {
                mHead = d;
                mTail = d;
            } else {
                mTail.mNext = d;
                mTail = d;
            }
        }

        public DispatchEvent dequeue() {
            DispatchEvent d = mHead;
            if (d != null) {
                DispatchEvent next = d.mNext;
                if (next == null) {
                    mHead = null;
                    mTail = null;
                } else {
                    mHead = next;
                    d.mNext = null;
                }
            }
            return d;
        }

        public DispatchEvent dequeueList() {
            DispatchEvent d = mHead;
            if (d != null) {
                mHead = null;
                mTail = null;
            }
            return d;
        }
    }

    /**
     * Keeps track of a stream of touch events so that we can discard touch
     * events that would make the stream inconsistent.
     */
    private static final class TouchStream {
        private MotionEvent mLastEvent;

        /**
         * Gets the last touch event that was delivered.
         * @return The last touch event, or null if none.
         */
        public MotionEvent getLastEvent() {
            return mLastEvent;
        }

        /**
         * Updates the touch event stream.
         * @param event The event that we intend to send, or null to cancel the
         * touch event stream.
         * @return The event that we should actually send, or null if no event should
         * be sent because the proposed event would make the stream inconsistent.
         */
        public MotionEvent update(MotionEvent event) {
            if (event == null) {
                if (isCancelNeeded()) {
                    event = mLastEvent;
                    if (event != null) {
                        event.setAction(MotionEvent.ACTION_CANCEL);
                        mLastEvent = null;
                    }
                }
                return event;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    if (mLastEvent == null
                            || mLastEvent.getAction() == MotionEvent.ACTION_UP) {
                        return null;
                    }
                    updateLastEvent(event);
                    return event;

                case MotionEvent.ACTION_DOWN:
                    updateLastEvent(event);
                    return event;

                case MotionEvent.ACTION_CANCEL:
                    if (mLastEvent == null) {
                        return null;
                    }
                    updateLastEvent(null);
                    return event;

                default:
                    return null;
            }
        }

        /**
         * Returns true if there is a gesture in progress that may need to be canceled.
         * @return True if cancel is needed.
         */
        public boolean isCancelNeeded() {
            return mLastEvent != null && mLastEvent.getAction() != MotionEvent.ACTION_UP;
        }

        private void updateLastEvent(MotionEvent event) {
            if (mLastEvent != null) {
                mLastEvent.recycle();
            }
            mLastEvent = event != null ? MotionEvent.obtainNoHistory(event) : null;
        }
    }
}