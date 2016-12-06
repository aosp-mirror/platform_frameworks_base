/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.accessibility;

import android.content.Context;
import android.os.PowerManager;
import android.util.Pools.SimplePool;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputFilter;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy;
import android.view.accessibility.AccessibilityEvent;

/**
 * This class is an input filter for implementing accessibility features such
 * as display magnification and explore by touch.
 *
 * NOTE: This class has to be created and poked only from the main thread.
 */
class AccessibilityInputFilter extends InputFilter implements EventStreamTransformation {

    private static final String TAG = AccessibilityInputFilter.class.getSimpleName();

    private static final boolean DEBUG = false;

    /**
     * Flag for enabling the screen magnification feature.
     *
     * @see #setUserAndEnabledFeatures(int, int)
     */
    static final int FLAG_FEATURE_SCREEN_MAGNIFIER = 0x00000001;

    /**
     * Flag for enabling the touch exploration feature.
     *
     * @see #setUserAndEnabledFeatures(int, int)
     */
    static final int FLAG_FEATURE_TOUCH_EXPLORATION = 0x00000002;

    /**
     * Flag for enabling the filtering key events feature.
     *
     * @see #setUserAndEnabledFeatures(int, int)
     */
    static final int FLAG_FEATURE_FILTER_KEY_EVENTS = 0x00000004;

    /**
     * Flag for enabling "Automatically click on mouse stop" feature.
     *
     * @see #setUserAndEnabledFeatures(int, int)
     */
    static final int FLAG_FEATURE_AUTOCLICK = 0x00000008;

    /**
     * Flag for enabling motion event injection.
     *
     * @see #setUserAndEnabledFeatures(int, int)
     */
    static final int FLAG_FEATURE_INJECT_MOTION_EVENTS = 0x00000010;

    static final int FEATURES_AFFECTING_MOTION_EVENTS = FLAG_FEATURE_INJECT_MOTION_EVENTS
            | FLAG_FEATURE_AUTOCLICK | FLAG_FEATURE_TOUCH_EXPLORATION
            | FLAG_FEATURE_SCREEN_MAGNIFIER;
    /**
     * Flag for enabling the feature to control the screen magnifier. If
     * {@link #FLAG_FEATURE_SCREEN_MAGNIFIER} is set this flag is ignored
     * as the screen magnifier feature performs a super set of the work
     * performed by this feature.
     *
     * @see #setUserAndEnabledFeatures(int, int)
     */
    static final int FLAG_FEATURE_CONTROL_SCREEN_MAGNIFIER = 0x00000020;

    private final Runnable mProcessBatchedEventsRunnable = new Runnable() {
        @Override
        public void run() {
            final long frameTimeNanos = mChoreographer.getFrameTimeNanos();
            if (DEBUG) {
                Slog.i(TAG, "Begin batch processing for frame: " + frameTimeNanos);
            }
            processBatchedEvents(frameTimeNanos);
            if (DEBUG) {
                Slog.i(TAG, "End batch processing.");
            }
            if (mEventQueue != null) {
                scheduleProcessBatchedEvents();
            }
        }
    };

    private final Context mContext;

    private final PowerManager mPm;

    private final AccessibilityManagerService mAms;

    private final Choreographer mChoreographer;

    private boolean mInstalled;

    private int mUserId;

    private int mEnabledFeatures;

    private TouchExplorer mTouchExplorer;

    private MagnificationGestureHandler mMagnificationGestureHandler;

    private MotionEventInjector mMotionEventInjector;

    private AutoclickController mAutoclickController;

    private KeyboardInterceptor mKeyboardInterceptor;

    private EventStreamTransformation mEventHandler;

    private MotionEventHolder mEventQueue;

    private EventStreamState mMouseStreamState;

    private EventStreamState mTouchScreenStreamState;

    private EventStreamState mKeyboardStreamState;

    AccessibilityInputFilter(Context context, AccessibilityManagerService service) {
        super(context.getMainLooper());
        mContext = context;
        mAms = service;
        mPm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mChoreographer = Choreographer.getInstance();
    }

    @Override
    public void onInstalled() {
        if (DEBUG) {
            Slog.d(TAG, "Accessibility input filter installed.");
        }
        mInstalled = true;
        disableFeatures();
        enableFeatures();
        super.onInstalled();
    }

    @Override
    public void onUninstalled() {
        if (DEBUG) {
            Slog.d(TAG, "Accessibility input filter uninstalled.");
        }
        mInstalled = false;
        disableFeatures();
        super.onUninstalled();
    }

    @Override
    public void onInputEvent(InputEvent event, int policyFlags) {
        if (DEBUG) {
            Slog.d(TAG, "Received event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
        }

        if (mEventHandler == null) {
            super.onInputEvent(event, policyFlags);
            return;
        }

        EventStreamState state = getEventStreamState(event);
        if (state == null) {
            super.onInputEvent(event, policyFlags);
            return;
        }

        int eventSource = event.getSource();
        if ((policyFlags & WindowManagerPolicy.FLAG_PASS_TO_USER) == 0) {
            state.reset();
            mEventHandler.clearEvents(eventSource);
            super.onInputEvent(event, policyFlags);
            return;
        }

        if (state.updateDeviceId(event.getDeviceId())) {
            mEventHandler.clearEvents(eventSource);
        }

        if (!state.deviceIdValid()) {
            super.onInputEvent(event, policyFlags);
            return;
        }

        if (event instanceof MotionEvent) {
            if ((mEnabledFeatures & FEATURES_AFFECTING_MOTION_EVENTS) != 0) {
                MotionEvent motionEvent = (MotionEvent) event;
                processMotionEvent(state, motionEvent, policyFlags);
                return;
            } else {
                super.onInputEvent(event, policyFlags);
            }
        } else if (event instanceof KeyEvent) {
            KeyEvent keyEvent = (KeyEvent) event;
            processKeyEvent(state, keyEvent, policyFlags);
        }
    }

    /**
     * Gets current event stream state associated with an input event.
     * @return The event stream state that should be used for the event. Null if the event should
     *     not be handled by #AccessibilityInputFilter.
     */
    private EventStreamState getEventStreamState(InputEvent event) {
        if (event instanceof MotionEvent) {
          if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
              if (mTouchScreenStreamState == null) {
                  mTouchScreenStreamState = new TouchScreenEventStreamState();
              }
              return mTouchScreenStreamState;
          }
          if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
              if (mMouseStreamState == null) {
                  mMouseStreamState = new MouseEventStreamState();
              }
              return mMouseStreamState;
          }
        } else if (event instanceof KeyEvent) {
          if (event.isFromSource(InputDevice.SOURCE_KEYBOARD)) {
              if (mKeyboardStreamState == null) {
                  mKeyboardStreamState = new KeyboardEventStreamState();
              }
              return mKeyboardStreamState;
          }
        }
        return null;
    }

    private void processMotionEvent(EventStreamState state, MotionEvent event, int policyFlags) {
        if (!state.shouldProcessScroll() && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            super.onInputEvent(event, policyFlags);
            return;
        }

        if (!state.shouldProcessMotionEvent(event)) {
            return;
        }

        batchMotionEvent(event, policyFlags);
    }

    private void processKeyEvent(EventStreamState state, KeyEvent event, int policyFlags) {
        if (!state.shouldProcessKeyEvent(event)) {
            return;
        }
        mEventHandler.onKeyEvent(event, policyFlags);
    }

    private void scheduleProcessBatchedEvents() {
        mChoreographer.postCallback(Choreographer.CALLBACK_INPUT,
                mProcessBatchedEventsRunnable, null);
    }

    private void batchMotionEvent(MotionEvent event, int policyFlags) {
        if (DEBUG) {
            Slog.i(TAG, "Batching event: " + event + ", policyFlags: " + policyFlags);
        }
        if (mEventQueue == null) {
            mEventQueue = MotionEventHolder.obtain(event, policyFlags);
            scheduleProcessBatchedEvents();
            return;
        }
        if (mEventQueue.event.addBatch(event)) {
            return;
        }
        MotionEventHolder holder = MotionEventHolder.obtain(event, policyFlags);
        holder.next = mEventQueue;
        mEventQueue.previous = holder;
        mEventQueue = holder;
    }

    private void processBatchedEvents(long frameNanos) {
        MotionEventHolder current = mEventQueue;
        if (current == null) {
            return;
        }
        while (current.next != null) {
            current = current.next;
        }
        while (true) {
            if (current == null) {
                mEventQueue = null;
                break;
            }
            if (current.event.getEventTimeNano() >= frameNanos) {
                // Finished with this choreographer frame. Do the rest on the next one.
                current.next = null;
                break;
            }
            handleMotionEvent(current.event, current.policyFlags);
            MotionEventHolder prior = current;
            current = current.previous;
            prior.recycle();
        }
    }

    private void handleMotionEvent(MotionEvent event, int policyFlags) {
        if (DEBUG) {
            Slog.i(TAG, "Handling batched event: " + event + ", policyFlags: " + policyFlags);
        }
        // Since we do batch processing it is possible that by the time the
        // next batch is processed the event handle had been set to null.
        if (mEventHandler != null) {
            mPm.userActivity(event.getEventTime(), false);
            MotionEvent transformedEvent = MotionEvent.obtain(event);
            mEventHandler.onMotionEvent(transformedEvent, event, policyFlags);
            transformedEvent.recycle();
        }
    }

    @Override
    public void onMotionEvent(MotionEvent transformedEvent, MotionEvent rawEvent,
            int policyFlags) {
        sendInputEvent(transformedEvent, policyFlags);
    }

    @Override
    public void onKeyEvent(KeyEvent event, int policyFlags) {
        sendInputEvent(event, policyFlags);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // TODO Implement this to inject the accessibility event
        //      into the accessibility manager service similarly
        //      to how this is done for input events.
    }

    @Override
    public void setNext(EventStreamTransformation sink) {
        /* do nothing */
    }

    @Override
    public void clearEvents(int inputSource) {
        /* do nothing */
    }

    void setUserAndEnabledFeatures(int userId, int enabledFeatures) {
        if (mEnabledFeatures == enabledFeatures && mUserId == userId) {
            return;
        }
        if (mInstalled) {
            disableFeatures();
        }
        mUserId = userId;
        mEnabledFeatures = enabledFeatures;
        if (mInstalled) {
            enableFeatures();
        }
    }

    void notifyAccessibilityEvent(AccessibilityEvent event) {
        if (mEventHandler != null) {
            mEventHandler.onAccessibilityEvent(event);
        }
    }

    private void enableFeatures() {
        resetStreamState();

        if ((mEnabledFeatures & FLAG_FEATURE_AUTOCLICK) != 0) {
            mAutoclickController = new AutoclickController(mContext, mUserId);
            addFirstEventHandler(mAutoclickController);
        }

        if ((mEnabledFeatures & FLAG_FEATURE_TOUCH_EXPLORATION) != 0) {
            mTouchExplorer = new TouchExplorer(mContext, mAms);
            addFirstEventHandler(mTouchExplorer);
        }

        if ((mEnabledFeatures & FLAG_FEATURE_CONTROL_SCREEN_MAGNIFIER) != 0
                || (mEnabledFeatures  & FLAG_FEATURE_SCREEN_MAGNIFIER) != 0) {
            final boolean detectControlGestures = (mEnabledFeatures
                    & FLAG_FEATURE_SCREEN_MAGNIFIER) != 0;
            mMagnificationGestureHandler = new MagnificationGestureHandler(
                    mContext, mAms, detectControlGestures);
            addFirstEventHandler(mMagnificationGestureHandler);
        }

        if ((mEnabledFeatures & FLAG_FEATURE_INJECT_MOTION_EVENTS) != 0) {
            mMotionEventInjector = new MotionEventInjector(mContext.getMainLooper());
            addFirstEventHandler(mMotionEventInjector);
            mAms.setMotionEventInjector(mMotionEventInjector);
        }

        if ((mEnabledFeatures & FLAG_FEATURE_FILTER_KEY_EVENTS) != 0) {
            mKeyboardInterceptor = new KeyboardInterceptor(mAms);
            addFirstEventHandler(mKeyboardInterceptor);
        }
    }

    /**
     * Adds an event handler to the event handler chain. The handler is added at the beginning of
     * the chain.
     *
     * @param handler The handler to be added to the event handlers list.
     */
    private void addFirstEventHandler(EventStreamTransformation handler) {
        if (mEventHandler != null) {
           handler.setNext(mEventHandler);
        } else {
            handler.setNext(this);
        }
        mEventHandler = handler;
    }

    private void disableFeatures() {
        // Give the features a chance to process any batched events so we'll keep a consistent
        // event stream
        processBatchedEvents(Long.MAX_VALUE);
        if (mMotionEventInjector != null) {
            mAms.setMotionEventInjector(null);
            mMotionEventInjector.onDestroy();
            mMotionEventInjector = null;
        }
        if (mAutoclickController != null) {
            mAutoclickController.onDestroy();
            mAutoclickController = null;
        }
        if (mTouchExplorer != null) {
            mTouchExplorer.onDestroy();
            mTouchExplorer = null;
        }
        if (mMagnificationGestureHandler != null) {
            mMagnificationGestureHandler.onDestroy();
            mMagnificationGestureHandler = null;
        }
        if (mKeyboardInterceptor != null) {
            mKeyboardInterceptor.onDestroy();
            mKeyboardInterceptor = null;
        }

        mEventHandler = null;
        resetStreamState();
    }

    void resetStreamState() {
        if (mTouchScreenStreamState != null) {
            mTouchScreenStreamState.reset();
        }
        if (mMouseStreamState != null) {
            mMouseStreamState.reset();
        }
        if (mKeyboardStreamState != null) {
            mKeyboardStreamState.reset();
        }
    }

    @Override
    public void onDestroy() {
        /* ignore */
    }

    private static class MotionEventHolder {
        private static final int MAX_POOL_SIZE = 32;
        private static final SimplePool<MotionEventHolder> sPool =
                new SimplePool<MotionEventHolder>(MAX_POOL_SIZE);

        public int policyFlags;
        public MotionEvent event;
        public MotionEventHolder next;
        public MotionEventHolder previous;

        public static MotionEventHolder obtain(MotionEvent event, int policyFlags) {
            MotionEventHolder holder = sPool.acquire();
            if (holder == null) {
                holder = new MotionEventHolder();
            }
            holder.event = MotionEvent.obtain(event);
            holder.policyFlags = policyFlags;
            return holder;
        }

        public void recycle() {
            event.recycle();
            event = null;
            policyFlags = 0;
            next = null;
            previous = null;
            sPool.release(this);
        }
    }

    /**
     * Keeps state of event streams observed for an input device with a certain source.
     * Provides information about whether motion and key events should be processed by accessibility
     * #EventStreamTransformations. Base implementation describes behaviour for event sources that
     * whose events should not be handled by a11y event stream transformations.
     */
    private static class EventStreamState {
        private int mDeviceId;

        EventStreamState() {
            mDeviceId = -1;
        }

        /**
         * Updates the ID of the device associated with the state. If the ID changes, resets
         * internal state.
         *
         * @param deviceId Updated input device ID.
         * @return Whether the device ID has changed.
         */
        public boolean updateDeviceId(int deviceId) {
            if (mDeviceId == deviceId) {
                return false;
            }
            // Reset clears internal state, so make sure it's called before |mDeviceId| is updated.
            reset();
            mDeviceId = deviceId;
            return true;
        }

        /**
         * @return Whether device ID is valid.
         */
        public boolean deviceIdValid() {
            return mDeviceId >= 0;
        }

        /**
         * Resets the event stream state.
         */
        public void reset() {
            mDeviceId = -1;
        }

        /**
         * @return Whether scroll events for device should be handled by event transformations.
         */
        public boolean shouldProcessScroll() {
            return false;
        }

        /**
         * @param event An observed motion event.
         * @return Whether the event should be handled by event transformations.
         */
        public boolean shouldProcessMotionEvent(MotionEvent event) {
            return false;
        }

        /**
         * @param event An observed key event.
         * @return Whether the event should be handled by event transformations.
         */
        public boolean shouldProcessKeyEvent(KeyEvent event) {
            return false;
        }
    }

    /**
     * Keeps state of stream of events from a mouse device.
     */
    private static class MouseEventStreamState extends EventStreamState {
        private boolean mMotionSequenceStarted;

        public MouseEventStreamState() {
            reset();
        }

        @Override
        final public void reset() {
            super.reset();
            mMotionSequenceStarted = false;
        }

        @Override
        final public boolean shouldProcessScroll() {
            return true;
        }

        @Override
        final public boolean shouldProcessMotionEvent(MotionEvent event) {
            if (mMotionSequenceStarted) {
                return true;
            }
            // Wait for down or move event to start processing mouse events.
            int action = event.getActionMasked();
            mMotionSequenceStarted =
                    action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_HOVER_MOVE;
            return mMotionSequenceStarted;
        }
    }

    /**
     * Keeps state of stream of events from a touch screen device.
     */
    private static class TouchScreenEventStreamState extends EventStreamState {
        private boolean mTouchSequenceStarted;
        private boolean mHoverSequenceStarted;

        public TouchScreenEventStreamState() {
            reset();
        }

        @Override
        final public void reset() {
            super.reset();
            mTouchSequenceStarted = false;
            mHoverSequenceStarted = false;
        }

        @Override
        final public boolean shouldProcessMotionEvent(MotionEvent event) {
            // Wait for a down touch event to start processing.
            if (event.isTouchEvent()) {
                if (mTouchSequenceStarted) {
                    return true;
                }
                mTouchSequenceStarted = event.getActionMasked() == MotionEvent.ACTION_DOWN;
                return mTouchSequenceStarted;
            }

            // Wait for an enter hover event to start processing.
            if (mHoverSequenceStarted) {
                return true;
            }
            mHoverSequenceStarted = event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER;
            return mHoverSequenceStarted;
        }
    }

    /**
     * Keeps state of streams of events from all keyboard devices.
     */
    private static class KeyboardEventStreamState extends EventStreamState {
        private SparseBooleanArray mEventSequenceStartedMap = new SparseBooleanArray();

        public KeyboardEventStreamState() {
            reset();
        }

        @Override
        final public void reset() {
            super.reset();
            mEventSequenceStartedMap.clear();
        }

        /*
         * Key events from different devices may be interleaved. For example, the volume up and
         * down keys can come from different device IDs.
         */
        @Override
        public boolean updateDeviceId(int deviceId) {
            return false;
        }

        // We manage all device ids simultaneously; there is no concept of validity.
        @Override
        public boolean deviceIdValid() {
            return true;
        }


        @Override
        final public boolean shouldProcessKeyEvent(KeyEvent event) {
            // For each keyboard device, wait for a down event from a device to start processing
            int deviceId = event.getDeviceId();
            if (mEventSequenceStartedMap.get(deviceId, false)) {
                return true;
            }
            boolean shouldProcess = event.getAction() == KeyEvent.ACTION_DOWN;
            mEventSequenceStartedMap.put(deviceId, shouldProcess);
            return shouldProcess;
        }
    }
}
