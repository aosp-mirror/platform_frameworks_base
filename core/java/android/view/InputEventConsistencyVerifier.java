/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.Build;
import android.util.Log;

/**
 * Checks whether a sequence of input events is self-consistent.
 * Logs a description of each problem detected.
 * <p>
 * When a problem is detected, the event is tainted.  This mechanism prevents the same
 * error from being reported multiple times.
 * </p>
 *
 * @hide
 */
public final class InputEventConsistencyVerifier {
    private static final boolean IS_ENG_BUILD = "eng".equals(Build.TYPE);

    private static final String EVENT_TYPE_KEY = "KeyEvent";
    private static final String EVENT_TYPE_TRACKBALL = "TrackballEvent";
    private static final String EVENT_TYPE_TOUCH = "TouchEvent";
    private static final String EVENT_TYPE_GENERIC_MOTION = "GenericMotionEvent";

    // The number of recent events to log when a problem is detected.
    // Can be set to 0 to disable logging recent events but the runtime overhead of
    // this feature is negligible on current hardware.
    private static final int RECENT_EVENTS_TO_LOG = 5;

    // The object to which the verifier is attached.
    private final Object mCaller;

    // Consistency verifier flags.
    private final int mFlags;

    // Tag for logging which a client can set to help distinguish the output
    // from different verifiers since several can be active at the same time.
    // If not provided defaults to the simple class name.
    private final String mLogTag;

    // The most recently checked event and the nesting level at which it was checked.
    // This is only set when the verifier is called from a nesting level greater than 0
    // so that the verifier can detect when it has been asked to verify the same event twice.
    // It does not make sense to examine the contents of the last event since it may have
    // been recycled.
    private int mLastEventSeq;
    private String mLastEventType;
    private int mLastNestingLevel;

    // Copy of the most recent events.
    private InputEvent[] mRecentEvents;
    private boolean[] mRecentEventsUnhandled;
    private int mMostRecentEventIndex;

    // Current event and its type.
    private InputEvent mCurrentEvent;
    private String mCurrentEventType;

    // Linked list of key state objects.
    private KeyState mKeyStateList;

    // Current state of the trackball.
    private boolean mTrackballDown;
    private boolean mTrackballUnhandled;

    // Bitfield of pointer ids that are currently down.
    // Assumes that the largest possible pointer id is 31, which is potentially subject to change.
    // (See MAX_POINTER_ID in frameworks/base/include/ui/Input.h)
    private int mTouchEventStreamPointers;

    // The device id and source of the current stream of touch events.
    private int mTouchEventStreamDeviceId = -1;
    private int mTouchEventStreamSource;

    // Set to true when we discover that the touch event stream is inconsistent.
    // Reset on down or cancel.
    private boolean mTouchEventStreamIsTainted;

    // Set to true if the touch event stream is partially unhandled.
    private boolean mTouchEventStreamUnhandled;

    // Set to true if we received hover enter.
    private boolean mHoverEntered;

    // The current violation message.
    private StringBuilder mViolationMessage;

    /**
     * Indicates that the verifier is intended to act on raw device input event streams.
     * Disables certain checks for invariants that are established by the input dispatcher
     * itself as it delivers input events, such as key repeating behavior.
     */
    public static final int FLAG_RAW_DEVICE_INPUT = 1 << 0;

    /**
     * Creates an input consistency verifier.
     * @param caller The object to which the verifier is attached.
     * @param flags Flags to the verifier, or 0 if none.
     */
    public InputEventConsistencyVerifier(Object caller, int flags) {
        this(caller, flags, InputEventConsistencyVerifier.class.getSimpleName());
    }

    /**
     * Creates an input consistency verifier.
     * @param caller The object to which the verifier is attached.
     * @param flags Flags to the verifier, or 0 if none.
     * @param logTag Tag for logging. If null defaults to the short class name.
     */
    public InputEventConsistencyVerifier(Object caller, int flags, String logTag) {
        this.mCaller = caller;
        this.mFlags = flags;
        this.mLogTag = (logTag != null) ? logTag : "InputEventConsistencyVerifier";
    }

    /**
     * Determines whether the instrumentation should be enabled.
     * @return True if it should be enabled.
     */
    public static boolean isInstrumentationEnabled() {
        return IS_ENG_BUILD;
    }

    /**
     * Resets the state of the input event consistency verifier.
     */
    public void reset() {
        mLastEventSeq = -1;
        mLastNestingLevel = 0;
        mTrackballDown = false;
        mTrackballUnhandled = false;
        mTouchEventStreamPointers = 0;
        mTouchEventStreamIsTainted = false;
        mTouchEventStreamUnhandled = false;
        mHoverEntered = false;

        while (mKeyStateList != null) {
            final KeyState state = mKeyStateList;
            mKeyStateList = state.next;
            state.recycle();
        }
    }

    /**
     * Checks an arbitrary input event.
     * @param event The event.
     * @param nestingLevel The nesting level: 0 if called from the base class,
     * or 1 from a subclass.  If the event was already checked by this consistency verifier
     * at a higher nesting level, it will not be checked again.  Used to handle the situation
     * where a subclass dispatching method delegates to its superclass's dispatching method
     * and both dispatching methods call into the consistency verifier.
     */
    public void onInputEvent(InputEvent event, int nestingLevel) {
        if (event instanceof KeyEvent) {
            final KeyEvent keyEvent = (KeyEvent)event;
            onKeyEvent(keyEvent, nestingLevel);
        } else {
            final MotionEvent motionEvent = (MotionEvent)event;
            if (motionEvent.isTouchEvent()) {
                onTouchEvent(motionEvent, nestingLevel);
            } else if ((motionEvent.getSource() & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                onTrackballEvent(motionEvent, nestingLevel);
            } else {
                onGenericMotionEvent(motionEvent, nestingLevel);
            }
        }
    }

    /**
     * Checks a key event.
     * @param event The event.
     * @param nestingLevel The nesting level: 0 if called from the base class,
     * or 1 from a subclass.  If the event was already checked by this consistency verifier
     * at a higher nesting level, it will not be checked again.  Used to handle the situation
     * where a subclass dispatching method delegates to its superclass's dispatching method
     * and both dispatching methods call into the consistency verifier.
     */
    public void onKeyEvent(KeyEvent event, int nestingLevel) {
        if (!startEvent(event, nestingLevel, EVENT_TYPE_KEY)) {
            return;
        }

        try {
            ensureMetaStateIsNormalized(event.getMetaState());

            final int action = event.getAction();
            final int deviceId = event.getDeviceId();
            final int source = event.getSource();
            final int keyCode = event.getKeyCode();
            switch (action) {
                case KeyEvent.ACTION_DOWN: {
                    KeyState state = findKeyState(deviceId, source, keyCode, /*remove*/ false);
                    if (state != null) {
                        // If the key is already down, ensure it is a repeat.
                        // We don't perform this check when processing raw device input
                        // because the input dispatcher itself is responsible for setting
                        // the key repeat count before it delivers input events.
                        if (state.unhandled) {
                            state.unhandled = false;
                        } else if ((mFlags & FLAG_RAW_DEVICE_INPUT) == 0
                                && event.getRepeatCount() == 0) {
                            problem("ACTION_DOWN but key is already down and this event "
                                    + "is not a key repeat.");
                        }
                    } else {
                        addKeyState(deviceId, source, keyCode);
                    }
                    break;
                }
                case KeyEvent.ACTION_UP: {
                    KeyState state = findKeyState(deviceId, source, keyCode, /*remove*/ true);
                    if (state == null) {
                        problem("ACTION_UP but key was not down.");
                    } else {
                        state.recycle();
                    }
                    break;
                }
                case KeyEvent.ACTION_MULTIPLE:
                    break;
                default:
                    problem("Invalid action " + KeyEvent.actionToString(action)
                            + " for key event.");
                    break;
            }
        } finally {
            finishEvent();
        }
    }

    /**
     * Checks a trackball event.
     * @param event The event.
     * @param nestingLevel The nesting level: 0 if called from the base class,
     * or 1 from a subclass.  If the event was already checked by this consistency verifier
     * at a higher nesting level, it will not be checked again.  Used to handle the situation
     * where a subclass dispatching method delegates to its superclass's dispatching method
     * and both dispatching methods call into the consistency verifier.
     */
    public void onTrackballEvent(MotionEvent event, int nestingLevel) {
        if (!startEvent(event, nestingLevel, EVENT_TYPE_TRACKBALL)) {
            return;
        }

        try {
            ensureMetaStateIsNormalized(event.getMetaState());

            final int action = event.getAction();
            final int source = event.getSource();
            if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        if (mTrackballDown && !mTrackballUnhandled) {
                            problem("ACTION_DOWN but trackball is already down.");
                        } else {
                            mTrackballDown = true;
                            mTrackballUnhandled = false;
                        }
                        ensureHistorySizeIsZeroForThisAction(event);
                        ensurePointerCountIsOneForThisAction(event);
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!mTrackballDown) {
                            problem("ACTION_UP but trackball is not down.");
                        } else {
                            mTrackballDown = false;
                            mTrackballUnhandled = false;
                        }
                        ensureHistorySizeIsZeroForThisAction(event);
                        ensurePointerCountIsOneForThisAction(event);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        ensurePointerCountIsOneForThisAction(event);
                        break;
                    default:
                        problem("Invalid action " + MotionEvent.actionToString(action)
                                + " for trackball event.");
                        break;
                }

                if (mTrackballDown && event.getPressure() <= 0) {
                    problem("Trackball is down but pressure is not greater than 0.");
                } else if (!mTrackballDown && event.getPressure() != 0) {
                    problem("Trackball is up but pressure is not equal to 0.");
                }
            } else {
                problem("Source was not SOURCE_CLASS_TRACKBALL.");
            }
        } finally {
            finishEvent();
        }
    }

    /**
     * Checks a touch event.
     * @param event The event.
     * @param nestingLevel The nesting level: 0 if called from the base class,
     * or 1 from a subclass.  If the event was already checked by this consistency verifier
     * at a higher nesting level, it will not be checked again.  Used to handle the situation
     * where a subclass dispatching method delegates to its superclass's dispatching method
     * and both dispatching methods call into the consistency verifier.
     */
    public void onTouchEvent(MotionEvent event, int nestingLevel) {
        if (!startEvent(event, nestingLevel, EVENT_TYPE_TOUCH)) {
            return;
        }

        final int action = event.getAction();
        final boolean newStream = action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE;
        if (newStream && (mTouchEventStreamIsTainted || mTouchEventStreamUnhandled)) {
            mTouchEventStreamIsTainted = false;
            mTouchEventStreamUnhandled = false;
            mTouchEventStreamPointers = 0;
        }
        if (mTouchEventStreamIsTainted) {
            event.setTainted(true);
        }

        try {
            ensureMetaStateIsNormalized(event.getMetaState());

            final int deviceId = event.getDeviceId();
            final int source = event.getSource();

            if (!newStream && mTouchEventStreamDeviceId != -1
                    && (mTouchEventStreamDeviceId != deviceId
                            || mTouchEventStreamSource != source)) {
                problem("Touch event stream contains events from multiple sources: "
                        + "previous device id " + mTouchEventStreamDeviceId
                        + ", previous source " + Integer.toHexString(mTouchEventStreamSource)
                        + ", new device id " + deviceId
                        + ", new source " + Integer.toHexString(source));
            }
            mTouchEventStreamDeviceId = deviceId;
            mTouchEventStreamSource = source;

            final int pointerCount = event.getPointerCount();
            if ((source & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        if (mTouchEventStreamPointers != 0) {
                            problem("ACTION_DOWN but pointers are already down.  "
                                    + "Probably missing ACTION_UP from previous gesture.");
                        }
                        ensureHistorySizeIsZeroForThisAction(event);
                        ensurePointerCountIsOneForThisAction(event);
                        mTouchEventStreamPointers = 1 << event.getPointerId(0);
                        break;
                    case MotionEvent.ACTION_UP:
                        ensureHistorySizeIsZeroForThisAction(event);
                        ensurePointerCountIsOneForThisAction(event);
                        mTouchEventStreamPointers = 0;
                        mTouchEventStreamIsTainted = false;
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        final int expectedPointerCount =
                                Integer.bitCount(mTouchEventStreamPointers);
                        if (pointerCount != expectedPointerCount) {
                            problem("ACTION_MOVE contained " + pointerCount
                                    + " pointers but there are currently "
                                    + expectedPointerCount + " pointers down.");
                            mTouchEventStreamIsTainted = true;
                        }
                        break;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        mTouchEventStreamPointers = 0;
                        mTouchEventStreamIsTainted = false;
                        break;
                    case MotionEvent.ACTION_OUTSIDE:
                        if (mTouchEventStreamPointers != 0) {
                            problem("ACTION_OUTSIDE but pointers are still down.");
                        }
                        ensureHistorySizeIsZeroForThisAction(event);
                        ensurePointerCountIsOneForThisAction(event);
                        mTouchEventStreamIsTainted = false;
                        break;
                    default: {
                        final int actionMasked = event.getActionMasked();
                        final int actionIndex = event.getActionIndex();
                        if (actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                            if (mTouchEventStreamPointers == 0) {
                                problem("ACTION_POINTER_DOWN but no other pointers were down.");
                                mTouchEventStreamIsTainted = true;
                            }
                            if (actionIndex < 0 || actionIndex >= pointerCount) {
                                problem("ACTION_POINTER_DOWN index is " + actionIndex
                                        + " but the pointer count is " + pointerCount + ".");
                                mTouchEventStreamIsTainted = true;
                            } else {
                                final int id = event.getPointerId(actionIndex);
                                final int idBit = 1 << id;
                                if ((mTouchEventStreamPointers & idBit) != 0) {
                                    problem("ACTION_POINTER_DOWN specified pointer id " + id
                                            + " which is already down.");
                                    mTouchEventStreamIsTainted = true;
                                } else {
                                    mTouchEventStreamPointers |= idBit;
                                }
                            }
                            ensureHistorySizeIsZeroForThisAction(event);
                        } else if (actionMasked == MotionEvent.ACTION_POINTER_UP) {
                            if (actionIndex < 0 || actionIndex >= pointerCount) {
                                problem("ACTION_POINTER_UP index is " + actionIndex
                                        + " but the pointer count is " + pointerCount + ".");
                                mTouchEventStreamIsTainted = true;
                            } else {
                                final int id = event.getPointerId(actionIndex);
                                final int idBit = 1 << id;
                                if ((mTouchEventStreamPointers & idBit) == 0) {
                                    problem("ACTION_POINTER_UP specified pointer id " + id
                                            + " which is not currently down.");
                                    mTouchEventStreamIsTainted = true;
                                } else {
                                    mTouchEventStreamPointers &= ~idBit;
                                }
                            }
                            ensureHistorySizeIsZeroForThisAction(event);
                        } else {
                            problem("Invalid action " + MotionEvent.actionToString(action)
                                    + " for touch event.");
                        }
                        break;
                    }
                }
            } else {
                problem("Source was not SOURCE_CLASS_POINTER.");
            }
        } finally {
            finishEvent();
        }
    }

    /**
     * Checks a generic motion event.
     * @param event The event.
     * @param nestingLevel The nesting level: 0 if called from the base class,
     * or 1 from a subclass.  If the event was already checked by this consistency verifier
     * at a higher nesting level, it will not be checked again.  Used to handle the situation
     * where a subclass dispatching method delegates to its superclass's dispatching method
     * and both dispatching methods call into the consistency verifier.
     */
    public void onGenericMotionEvent(MotionEvent event, int nestingLevel) {
        if (!startEvent(event, nestingLevel, EVENT_TYPE_GENERIC_MOTION)) {
            return;
        }

        try {
            ensureMetaStateIsNormalized(event.getMetaState());

            final int action = event.getAction();
            final int source = event.getSource();
            if ((source & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                switch (action) {
                    case MotionEvent.ACTION_HOVER_ENTER:
                        ensurePointerCountIsOneForThisAction(event);
                        mHoverEntered = true;
                        break;
                    case MotionEvent.ACTION_HOVER_MOVE:
                        ensurePointerCountIsOneForThisAction(event);
                        break;
                    case MotionEvent.ACTION_HOVER_EXIT:
                        ensurePointerCountIsOneForThisAction(event);
                        if (!mHoverEntered) {
                            problem("ACTION_HOVER_EXIT without prior ACTION_HOVER_ENTER");
                        }
                        mHoverEntered = false;
                        break;
                    case MotionEvent.ACTION_SCROLL:
                        ensureHistorySizeIsZeroForThisAction(event);
                        ensurePointerCountIsOneForThisAction(event);
                        break;
                    default:
                        problem("Invalid action for generic pointer event.");
                        break;
                }
            } else if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
                switch (action) {
                    case MotionEvent.ACTION_MOVE:
                        ensurePointerCountIsOneForThisAction(event);
                        break;
                    default:
                        problem("Invalid action for generic joystick event.");
                        break;
                }
            }
        } finally {
            finishEvent();
        }
    }

    /**
     * Notifies the verifier that a given event was unhandled and the rest of the
     * trace for the event should be ignored.
     * This method should only be called if the event was previously checked by
     * the consistency verifier using {@link #onInputEvent} and other methods.
     * @param event The event.
     * @param nestingLevel The nesting level: 0 if called from the base class,
     * or 1 from a subclass.  If the event was already checked by this consistency verifier
     * at a higher nesting level, it will not be checked again.  Used to handle the situation
     * where a subclass dispatching method delegates to its superclass's dispatching method
     * and both dispatching methods call into the consistency verifier.
     */
    public void onUnhandledEvent(InputEvent event, int nestingLevel) {
        if (nestingLevel != mLastNestingLevel) {
            return;
        }

        if (mRecentEventsUnhandled != null) {
            mRecentEventsUnhandled[mMostRecentEventIndex] = true;
        }

        if (event instanceof KeyEvent) {
            final KeyEvent keyEvent = (KeyEvent)event;
            final int deviceId = keyEvent.getDeviceId();
            final int source = keyEvent.getSource();
            final int keyCode = keyEvent.getKeyCode();
            final KeyState state = findKeyState(deviceId, source, keyCode, /*remove*/ false);
            if (state != null) {
                state.unhandled = true;
            }
        } else {
            final MotionEvent motionEvent = (MotionEvent)event;
            if (motionEvent.isTouchEvent()) {
                mTouchEventStreamUnhandled = true;
            } else if ((motionEvent.getSource() & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                if (mTrackballDown) {
                    mTrackballUnhandled = true;
                }
            }
        }
    }

    private void ensureMetaStateIsNormalized(int metaState) {
        final int normalizedMetaState = KeyEvent.normalizeMetaState(metaState);
        if (normalizedMetaState != metaState) {
            problem(String.format("Metastate not normalized.  Was 0x%08x but expected 0x%08x.",
                    metaState, normalizedMetaState));
        }
    }

    private void ensurePointerCountIsOneForThisAction(MotionEvent event) {
        final int pointerCount = event.getPointerCount();
        if (pointerCount != 1) {
            problem("Pointer count is " + pointerCount + " but it should always be 1 for "
                    + MotionEvent.actionToString(event.getAction()));
        }
    }

    private void ensureHistorySizeIsZeroForThisAction(MotionEvent event) {
        final int historySize = event.getHistorySize();
        if (historySize != 0) {
            problem("History size is " + historySize + " but it should always be 0 for "
                    + MotionEvent.actionToString(event.getAction()));
        }
    }

    private boolean startEvent(InputEvent event, int nestingLevel, String eventType) {
        // Ignore the event if we already checked it at a higher nesting level.
        final int seq = event.getSequenceNumber();
        if (seq == mLastEventSeq && nestingLevel < mLastNestingLevel
                && eventType == mLastEventType) {
            return false;
        }

        if (nestingLevel > 0) {
            mLastEventSeq = seq;
            mLastEventType = eventType;
            mLastNestingLevel = nestingLevel;
        } else {
            mLastEventSeq = -1;
            mLastEventType = null;
            mLastNestingLevel = 0;
        }

        mCurrentEvent = event;
        mCurrentEventType = eventType;
        return true;
    }

    private void finishEvent() {
        if (mViolationMessage != null && mViolationMessage.length() != 0) {
            if (!mCurrentEvent.isTainted()) {
                // Write a log message only if the event was not already tainted.
                mViolationMessage.append("\n  in ").append(mCaller);
                mViolationMessage.append("\n  ");
                appendEvent(mViolationMessage, 0, mCurrentEvent, false);

                if (RECENT_EVENTS_TO_LOG != 0 && mRecentEvents != null) {
                    mViolationMessage.append("\n  -- recent events --");
                    for (int i = 0; i < RECENT_EVENTS_TO_LOG; i++) {
                        final int index = (mMostRecentEventIndex + RECENT_EVENTS_TO_LOG - i)
                                % RECENT_EVENTS_TO_LOG;
                        final InputEvent event = mRecentEvents[index];
                        if (event == null) {
                            break;
                        }
                        mViolationMessage.append("\n  ");
                        appendEvent(mViolationMessage, i + 1, event, mRecentEventsUnhandled[index]);
                    }
                }

                Log.d(mLogTag, mViolationMessage.toString());

                // Taint the event so that we do not generate additional violations from it
                // further downstream.
                mCurrentEvent.setTainted(true);
            }
            mViolationMessage.setLength(0);
        }

        if (RECENT_EVENTS_TO_LOG != 0) {
            if (mRecentEvents == null) {
                mRecentEvents = new InputEvent[RECENT_EVENTS_TO_LOG];
                mRecentEventsUnhandled = new boolean[RECENT_EVENTS_TO_LOG];
            }
            final int index = (mMostRecentEventIndex + 1) % RECENT_EVENTS_TO_LOG;
            mMostRecentEventIndex = index;
            if (mRecentEvents[index] != null) {
                mRecentEvents[index].recycle();
            }
            mRecentEvents[index] = mCurrentEvent.copy();
            mRecentEventsUnhandled[index] = false;
        }

        mCurrentEvent = null;
        mCurrentEventType = null;
    }

    private static void appendEvent(StringBuilder message, int index,
            InputEvent event, boolean unhandled) {
        message.append(index).append(": sent at ").append(event.getEventTimeNano());
        message.append(", ");
        if (unhandled) {
            message.append("(unhandled) ");
        }
        message.append(event);
    }

    private void problem(String message) {
        if (mViolationMessage == null) {
            mViolationMessage = new StringBuilder();
        }
        if (mViolationMessage.length() == 0) {
            mViolationMessage.append(mCurrentEventType).append(": ");
        } else {
            mViolationMessage.append("\n  ");
        }
        mViolationMessage.append(message);
    }

    private KeyState findKeyState(int deviceId, int source, int keyCode, boolean remove) {
        KeyState last = null;
        KeyState state = mKeyStateList;
        while (state != null) {
            if (state.deviceId == deviceId && state.source == source
                    && state.keyCode == keyCode) {
                if (remove) {
                    if (last != null) {
                        last.next = state.next;
                    } else {
                        mKeyStateList = state.next;
                    }
                    state.next = null;
                }
                return state;
            }
            last = state;
            state = state.next;
        }
        return null;
    }

    private void addKeyState(int deviceId, int source, int keyCode) {
        KeyState state = KeyState.obtain(deviceId, source, keyCode);
        state.next = mKeyStateList;
        mKeyStateList = state;
    }

    private static final class KeyState {
        private static Object mRecycledListLock = new Object();
        private static KeyState mRecycledList;

        public KeyState next;
        public int deviceId;
        public int source;
        public int keyCode;
        public boolean unhandled;

        private KeyState() {
        }

        public static KeyState obtain(int deviceId, int source, int keyCode) {
            KeyState state;
            synchronized (mRecycledListLock) {
                state = mRecycledList;
                if (state != null) {
                    mRecycledList = state.next;
                } else {
                    state = new KeyState();
                }
            }
            state.deviceId = deviceId;
            state.source = source;
            state.keyCode = keyCode;
            state.unhandled = false;
            return state;
        }

        public void recycle() {
            synchronized (mRecycledListLock) {
                next = mRecycledList;
                mRecycledList = next;
            }
        }
    }
}
