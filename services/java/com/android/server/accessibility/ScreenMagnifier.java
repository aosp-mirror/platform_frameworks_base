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

package com.android.server.accessibility;

import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Property;
import android.util.Slog;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.IMagnificationCallbacks;
import android.view.IWindowManager;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;

import com.android.internal.os.SomeArgs;

import java.util.Locale;

/**
 * This class handles the screen magnification when accessibility is enabled.
 * The behavior is as follows:
 *
 * 1. Triple tap toggles permanent screen magnification which is magnifying
 *    the area around the location of the triple tap. One can think of the
 *    location of the triple tap as the center of the magnified viewport.
 *    For example, a triple tap when not magnified would magnify the screen
 *    and leave it in a magnified state. A triple tapping when magnified would
 *    clear magnification and leave the screen in a not magnified state.
 *
 * 2. Triple tap and hold would magnify the screen if not magnified and enable
 *    viewport dragging mode until the finger goes up. One can think of this
 *    mode as a way to move the magnified viewport since the area around the
 *    moving finger will be magnified to fit the screen. For example, if the
 *    screen was not magnified and the user triple taps and holds the screen
 *    would magnify and the viewport will follow the user's finger. When the
 *    finger goes up the screen will zoom out. If the same user interaction
 *    is performed when the screen is magnified, the viewport movement will
 *    be the same but when the finger goes up the screen will stay magnified.
 *    In other words, the initial magnified state is sticky.
 *
 * 3. Pinching with any number of additional fingers when viewport dragging
 *    is enabled, i.e. the user triple tapped and holds, would adjust the
 *    magnification scale which will become the current default magnification
 *    scale. The next time the user magnifies the same magnification scale
 *    would be used.
 *
 * 4. When in a permanent magnified state the user can use two or more fingers
 *    to pan the viewport. Note that in this mode the content is panned as
 *    opposed to the viewport dragging mode in which the viewport is moved.
 *
 * 5. When in a permanent magnified state the user can use two or more
 *    fingers to change the magnification scale which will become the current
 *    default magnification scale. The next time the user magnifies the same
 *    magnification scale would be used.
 *
 * 6. The magnification scale will be persisted in settings and in the cloud.
 */
public final class ScreenMagnifier extends IMagnificationCallbacks.Stub
        implements EventStreamTransformation {

    private static final String LOG_TAG = ScreenMagnifier.class.getSimpleName();

    private static final boolean DEBUG_STATE_TRANSITIONS = false;
    private static final boolean DEBUG_DETECTING = false;
    private static final boolean DEBUG_SET_MAGNIFICATION_SPEC = false;
    private static final boolean DEBUG_PANNING = false;
    private static final boolean DEBUG_SCALING = false;
    private static final boolean DEBUG_MAGNIFICATION_CONTROLLER = false;

    private static final int STATE_DELEGATING = 1;
    private static final int STATE_DETECTING = 2;
    private static final int STATE_VIEWPORT_DRAGGING = 3;
    private static final int STATE_MAGNIFIED_INTERACTION = 4;

    private static final float DEFAULT_MAGNIFICATION_SCALE = 2.0f;
    private static final int MULTI_TAP_TIME_SLOP_ADJUSTMENT = 50;

    private static final int MESSAGE_ON_MAGNIFIED_BOUNDS_CHANGED = 1;
    private static final int MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED = 2;
    private static final int MESSAGE_ON_USER_CONTEXT_CHANGED = 3;
    private static final int MESSAGE_ON_ROTATION_CHANGED = 4;

    private static final int DEFAULT_SCREEN_MAGNIFICATION_AUTO_UPDATE = 1;

    private static final int MY_PID = android.os.Process.myPid();

    private final Rect mTempRect = new Rect();
    private final Rect mTempRect1 = new Rect();

    private final Context mContext;
    private final IWindowManager mWindowManager;
    private final MagnificationController mMagnificationController;
    private final ScreenStateObserver mScreenStateObserver;

    private final DetectingStateHandler mDetectingStateHandler;
    private final MagnifiedContentInteractonStateHandler mMagnifiedContentInteractonStateHandler;
    private final StateViewportDraggingHandler mStateViewportDraggingHandler;

    private final AccessibilityManagerService mAms;

    private final int mTapTimeSlop = ViewConfiguration.getTapTimeout();
    private final int mMultiTapTimeSlop =
            ViewConfiguration.getDoubleTapTimeout() - MULTI_TAP_TIME_SLOP_ADJUSTMENT;
    private final int mTapDistanceSlop;
    private final int mMultiTapDistanceSlop;

    private final long mLongAnimationDuration;

    private final Region mMagnifiedBounds = new Region();

    private EventStreamTransformation mNext;

    private int mCurrentState;
    private int mPreviousState;
    private boolean mTranslationEnabledBeforePan;

    private PointerCoords[] mTempPointerCoords;
    private PointerProperties[] mTempPointerProperties;

    private long mDelegatingStateDownTime;

    private boolean mUpdateMagnificationSpecOnNextBoundsChange;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MESSAGE_ON_MAGNIFIED_BOUNDS_CHANGED: {
                    Region bounds = (Region) message.obj;
                    handleOnMagnifiedBoundsChanged(bounds);
                    bounds.recycle();
                } break;
                case MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    final int left = args.argi1;
                    final int top = args.argi2;
                    final int right = args.argi3;
                    final int bottom = args.argi4;
                    handleOnRectangleOnScreenRequested(left, top, right, bottom);
                    args.recycle();
                } break;
                case MESSAGE_ON_USER_CONTEXT_CHANGED: {
                    handleOnUserContextChanged();
                } break;
                case MESSAGE_ON_ROTATION_CHANGED: {
                    final int rotation = message.arg1;
                    handleOnRotationChanged(rotation);
                } break;
            }
        }
    };

    public ScreenMagnifier(Context context, int displayId, AccessibilityManagerService service) {
        mContext = context;
        mWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService("window"));
        mAms = service;

        mLongAnimationDuration = context.getResources().getInteger(
                com.android.internal.R.integer.config_longAnimTime);
        mTapDistanceSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mMultiTapDistanceSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();

        mDetectingStateHandler = new DetectingStateHandler();
        mStateViewportDraggingHandler = new StateViewportDraggingHandler();
        mMagnifiedContentInteractonStateHandler = new MagnifiedContentInteractonStateHandler(
                context);

        mMagnificationController = new MagnificationController(mLongAnimationDuration);
        mScreenStateObserver = new ScreenStateObserver(context, mMagnificationController);

        try {
            mWindowManager.setMagnificationCallbacks(this);
        } catch (RemoteException re) {
            /* ignore */
        }

        transitionToState(STATE_DETECTING);
    }

    @Override
    public void onMagnifedBoundsChanged(Region bounds) {
        Region newBounds = Region.obtain(bounds);
        mHandler.obtainMessage(MESSAGE_ON_MAGNIFIED_BOUNDS_CHANGED, newBounds).sendToTarget();
        if (MY_PID != Binder.getCallingPid()) {
            bounds.recycle();
        }
    }

    private void handleOnMagnifiedBoundsChanged(Region bounds) {
        // If there was a rotation we have to update the center of the magnified
        // region since the old offset X/Y may be out of its acceptable range for
        // the new display width and height.
        if (mUpdateMagnificationSpecOnNextBoundsChange) {
            mUpdateMagnificationSpecOnNextBoundsChange = false;
            MagnificationSpec spec = mMagnificationController.getMagnificationSpec();
            Rect magnifiedFrame = mTempRect;
            mMagnifiedBounds.getBounds(magnifiedFrame);
            final float scale = spec.scale;
            final float centerX = (-spec.offsetX + magnifiedFrame.width() / 2) / scale;
            final float centerY = (-spec.offsetY + magnifiedFrame.height() / 2) / scale;
            mMagnificationController.setScaleAndMagnifiedRegionCenter(scale, centerX,
                    centerY, false);
        }
        mMagnifiedBounds.set(bounds);
        mAms.onMagnificationStateChanged();
    }

    @Override
    public void onRectangleOnScreenRequested(int left, int top, int right, int bottom) {
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = left;
        args.argi2 = top;
        args.argi3 = right;
        args.argi4 = bottom;
        mHandler.obtainMessage(MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED, args).sendToTarget();
    }

    private void handleOnRectangleOnScreenRequested(int left, int top, int right, int bottom) {
        Rect magnifiedFrame = mTempRect;
        mMagnifiedBounds.getBounds(magnifiedFrame);
        if (!magnifiedFrame.intersects(left, top, right, bottom)) {
            return;
        }
        Rect magnifFrameInScreenCoords = mTempRect1;
        getMagnifiedFrameInContentCoords(magnifFrameInScreenCoords);
        final float scrollX;
        final float scrollY;
        if (right - left > magnifFrameInScreenCoords.width()) {
            final int direction = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
            if (direction == View.LAYOUT_DIRECTION_LTR) {
                scrollX = left - magnifFrameInScreenCoords.left;
            } else {
                scrollX = right - magnifFrameInScreenCoords.right;
            }
        } else if (left < magnifFrameInScreenCoords.left) {
            scrollX = left - magnifFrameInScreenCoords.left;
        } else if (right > magnifFrameInScreenCoords.right) {
            scrollX = right - magnifFrameInScreenCoords.right;
        } else {
            scrollX = 0;
        }
        if (bottom - top > magnifFrameInScreenCoords.height()) {
            scrollY = top - magnifFrameInScreenCoords.top;
        } else if (top < magnifFrameInScreenCoords.top) {
            scrollY = top - magnifFrameInScreenCoords.top;
        } else if (bottom > magnifFrameInScreenCoords.bottom) {
            scrollY = bottom - magnifFrameInScreenCoords.bottom;
        } else {
            scrollY = 0;
        }
        final float scale = mMagnificationController.getScale();
        mMagnificationController.offsetMagnifiedRegionCenter(scrollX * scale, scrollY * scale);
    }

    @Override
    public void onRotationChanged(int rotation) {
        mHandler.obtainMessage(MESSAGE_ON_ROTATION_CHANGED, rotation, 0).sendToTarget();
    }

    private void handleOnRotationChanged(int rotation) {
        resetMagnificationIfNeeded();
        if (mMagnificationController.isMagnifying()) {
            mUpdateMagnificationSpecOnNextBoundsChange = true;
        }
    }

    @Override
    public void onUserContextChanged() {
        mHandler.sendEmptyMessage(MESSAGE_ON_USER_CONTEXT_CHANGED);
    }

    private void handleOnUserContextChanged() {
        resetMagnificationIfNeeded();
    }

    private void getMagnifiedFrameInContentCoords(Rect rect) {
        MagnificationSpec spec = mMagnificationController.getMagnificationSpec();
        mMagnifiedBounds.getBounds(rect);
        rect.offset((int) -spec.offsetX, (int) -spec.offsetY);
        rect.scale(1.0f / spec.scale);
    }

    private void resetMagnificationIfNeeded() {
        if (mMagnificationController.isMagnifying()
                && isScreenMagnificationAutoUpdateEnabled(mContext)) {
            mMagnificationController.reset(true);
        }
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        mMagnifiedContentInteractonStateHandler.onMotionEvent(event);
        switch (mCurrentState) {
            case STATE_DELEGATING: {
                handleMotionEventStateDelegating(event, rawEvent, policyFlags);
            } break;
            case STATE_DETECTING: {
                mDetectingStateHandler.onMotionEvent(event, rawEvent, policyFlags);
            } break;
            case STATE_VIEWPORT_DRAGGING: {
                mStateViewportDraggingHandler.onMotionEvent(event, policyFlags);
            } break;
            case STATE_MAGNIFIED_INTERACTION: {
                // mMagnifiedContentInteractonStateHandler handles events only
                // if this is the current state since it uses ScaleGestureDetecotr
                // and a GestureDetector which need well formed event stream.
            } break;
            default: {
                throw new IllegalStateException("Unknown state: " + mCurrentState);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (mNext != null) {
            mNext.onAccessibilityEvent(event);
        }
    }

    @Override
    public void setNext(EventStreamTransformation next) {
        mNext = next;
    }

    @Override
    public void clear() {
        mCurrentState = STATE_DETECTING;
        mDetectingStateHandler.clear();
        mStateViewportDraggingHandler.clear();
        mMagnifiedContentInteractonStateHandler.clear();
        if (mNext != null) {
            mNext.clear();
        }
    }

    @Override
    public void onDestroy() {
        mScreenStateObserver.destroy();
        try {
            mWindowManager.setMagnificationCallbacks(null);
        } catch (RemoteException re) {
            /* ignore */
        }
    }

    private void handleMotionEventStateDelegating(MotionEvent event,
            MotionEvent rawEvent, int policyFlags) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDelegatingStateDownTime = event.getDownTime();
            } break;
            case MotionEvent.ACTION_UP: {
                if (mDetectingStateHandler.mDelayedEventQueue == null) {
                    transitionToState(STATE_DETECTING);
                }
            } break;
        }
        if (mNext != null) {
            // If the event is within the magnified portion of the screen we have
            // to change its location to be where the user thinks he is poking the
            // UI which may have been magnified and panned.
            final float eventX = event.getX();
            final float eventY = event.getY();
            if (mMagnificationController.isMagnifying()
                    && mMagnifiedBounds.contains((int) eventX, (int) eventY)) {
                final float scale = mMagnificationController.getScale();
                final float scaledOffsetX = mMagnificationController.getOffsetX();
                final float scaledOffsetY = mMagnificationController.getOffsetY();
                final int pointerCount = event.getPointerCount();
                PointerCoords[] coords = getTempPointerCoordsWithMinSize(pointerCount);
                PointerProperties[] properties = getTempPointerPropertiesWithMinSize(pointerCount);
                for (int i = 0; i < pointerCount; i++) {
                    event.getPointerCoords(i, coords[i]);
                    coords[i].x = (coords[i].x - scaledOffsetX) / scale;
                    coords[i].y = (coords[i].y - scaledOffsetY) / scale;
                    event.getPointerProperties(i, properties[i]);
                }
                event = MotionEvent.obtain(event.getDownTime(),
                        event.getEventTime(), event.getAction(), pointerCount, properties,
                        coords, 0, 0, 1.0f, 1.0f, event.getDeviceId(), 0, event.getSource(),
                        event.getFlags());
            }
            // We cache some events to see if the user wants to trigger magnification.
            // If no magnification is triggered we inject these events with adjusted
            // time and down time to prevent subsequent transformations being confused
            // by stale events. After the cached events, which always have a down, are
            // injected we need to also update the down time of all subsequent non cached
            // events. All delegated events cached and non-cached are delivered here.
            event.setDownTime(mDelegatingStateDownTime);
            mNext.onMotionEvent(event, rawEvent, policyFlags);
        }
    }

    private PointerCoords[] getTempPointerCoordsWithMinSize(int size) {
        final int oldSize = (mTempPointerCoords != null) ? mTempPointerCoords.length : 0;
        if (oldSize < size) {
            PointerCoords[] oldTempPointerCoords = mTempPointerCoords;
            mTempPointerCoords = new PointerCoords[size];
            if (oldTempPointerCoords != null) {
                System.arraycopy(oldTempPointerCoords, 0, mTempPointerCoords, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerCoords[i] = new PointerCoords();
        }
        return mTempPointerCoords;
    }

    private PointerProperties[] getTempPointerPropertiesWithMinSize(int size) {
        final int oldSize = (mTempPointerProperties != null) ? mTempPointerProperties.length : 0;
        if (oldSize < size) {
            PointerProperties[] oldTempPointerProperties = mTempPointerProperties;
            mTempPointerProperties = new PointerProperties[size];
            if (oldTempPointerProperties != null) {
                System.arraycopy(oldTempPointerProperties, 0, mTempPointerProperties, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerProperties[i] = new PointerProperties();
        }
        return mTempPointerProperties;
    }
    
    private void transitionToState(int state) {
        if (DEBUG_STATE_TRANSITIONS) {
            switch (state) {
                case STATE_DELEGATING: {
                    Slog.i(LOG_TAG, "mCurrentState: STATE_DELEGATING");
                } break;
                case STATE_DETECTING: {
                    Slog.i(LOG_TAG, "mCurrentState: STATE_DETECTING");
                } break;
                case STATE_VIEWPORT_DRAGGING: {
                    Slog.i(LOG_TAG, "mCurrentState: STATE_VIEWPORT_DRAGGING");
                } break;
                case STATE_MAGNIFIED_INTERACTION: {
                    Slog.i(LOG_TAG, "mCurrentState: STATE_MAGNIFIED_INTERACTION");
                } break;
                default: {
                    throw new IllegalArgumentException("Unknown state: " + state);
                }
            }
        }
        mPreviousState = mCurrentState;
        mCurrentState = state;
    }

    private final class MagnifiedContentInteractonStateHandler
            extends SimpleOnGestureListener implements OnScaleGestureListener {
        private static final float MIN_SCALE = 1.3f;
        private static final float MAX_SCALE = 5.0f;

        private static final float SCALING_THRESHOLD = 0.3f;

        private final ScaleGestureDetector mScaleGestureDetector;
        private final GestureDetector mGestureDetector;

        private float mInitialScaleFactor = -1;
        private boolean mScaling;

        public MagnifiedContentInteractonStateHandler(Context context) {
            mScaleGestureDetector = new ScaleGestureDetector(context, this);
            mGestureDetector = new GestureDetector(context, this);
        }

        public void onMotionEvent(MotionEvent event) {
            mScaleGestureDetector.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);
            if (mCurrentState != STATE_MAGNIFIED_INTERACTION) {
                return;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                clear();
                final float scale = Math.min(Math.max(mMagnificationController.getScale(),
                        MIN_SCALE), MAX_SCALE);
                if (scale != getPersistedScale()) {
                    persistScale(scale);
                }
                if (mPreviousState == STATE_VIEWPORT_DRAGGING) {
                    transitionToState(STATE_VIEWPORT_DRAGGING);
                } else {
                    transitionToState(STATE_DETECTING);
                }
            }
        }

        @Override
        public boolean onScroll(MotionEvent first, MotionEvent second, float distanceX,
                float distanceY) {
            if (mCurrentState != STATE_MAGNIFIED_INTERACTION) {
                return true;
            }
            if (DEBUG_PANNING) {
                Slog.i(LOG_TAG, "Panned content by scrollX: " + distanceX
                        + " scrollY: " + distanceY);
            }
            mMagnificationController.offsetMagnifiedRegionCenter(distanceX, distanceY);
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (!mScaling) {
                if (mInitialScaleFactor < 0) {
                    mInitialScaleFactor = detector.getScaleFactor();
                } else {
                    final float deltaScale = detector.getScaleFactor() - mInitialScaleFactor;
                    if (Math.abs(deltaScale) > SCALING_THRESHOLD) {
                        mScaling = true;
                        return true;
                    }
                }
                return false;
            }
            final float newScale = mMagnificationController.getScale()
                    * detector.getScaleFactor();
            final float normalizedNewScale = Math.min(Math.max(newScale, MIN_SCALE), MAX_SCALE);
            if (DEBUG_SCALING) {
                Slog.i(LOG_TAG, "normalizedNewScale: " + normalizedNewScale);
            }
            mMagnificationController.setScale(normalizedNewScale, detector.getFocusX(),
                    detector.getFocusY(), false);
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return (mCurrentState == STATE_MAGNIFIED_INTERACTION);
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            clear();
        }

        private void clear() {
            mInitialScaleFactor = -1;
            mScaling = false;
        }
    }

    private final class StateViewportDraggingHandler {
        private boolean mLastMoveOutsideMagnifiedRegion;

        private void onMotionEvent(MotionEvent event, int policyFlags) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    throw new IllegalArgumentException("Unexpected event type: ACTION_DOWN");
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    clear();
                    transitionToState(STATE_MAGNIFIED_INTERACTION);
                } break;
                case MotionEvent.ACTION_MOVE: {
                    if (event.getPointerCount() != 1) {
                        throw new IllegalStateException("Should have one pointer down.");
                    }
                    final float eventX = event.getX();
                    final float eventY = event.getY();
                    if (mMagnifiedBounds.contains((int) eventX, (int) eventY)) {
                        if (mLastMoveOutsideMagnifiedRegion) {
                            mLastMoveOutsideMagnifiedRegion = false;
                            mMagnificationController.setMagnifiedRegionCenter(eventX,
                                    eventY, true);
                        } else {
                            mMagnificationController.setMagnifiedRegionCenter(eventX,
                                    eventY, false);
                        }
                    } else {
                        mLastMoveOutsideMagnifiedRegion = true;
                    }
                } break;
                case MotionEvent.ACTION_UP: {
                    if (!mTranslationEnabledBeforePan) {
                        mMagnificationController.reset(true);
                    }
                    clear();
                    transitionToState(STATE_DETECTING);
                } break;
                case MotionEvent.ACTION_POINTER_UP: {
                    throw new IllegalArgumentException("Unexpected event type: ACTION_POINTER_UP");
                }
            }
        }

        public void clear() {
            mLastMoveOutsideMagnifiedRegion = false;
        }
    }

    private final class DetectingStateHandler {

        private static final int MESSAGE_ON_ACTION_TAP_AND_HOLD = 1;

        private static final int MESSAGE_TRANSITION_TO_DELEGATING_STATE = 2;

        private static final int ACTION_TAP_COUNT = 3;

        private MotionEventInfo mDelayedEventQueue;

        private MotionEvent mLastDownEvent;
        private MotionEvent mLastTapUpEvent;
        private int mTapCount;

        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                final int type = message.what;
                switch (type) {
                    case MESSAGE_ON_ACTION_TAP_AND_HOLD: {
                        MotionEvent event = (MotionEvent) message.obj;
                        final int policyFlags = message.arg1;
                        onActionTapAndHold(event, policyFlags);
                    } break;
                    case MESSAGE_TRANSITION_TO_DELEGATING_STATE: {
                        transitionToState(STATE_DELEGATING);
                        sendDelayedMotionEvents();
                        clear();
                    } break;
                    default: {
                        throw new IllegalArgumentException("Unknown message type: " + type);
                    }
                }
            }
        };

        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            cacheDelayedMotionEvent(event, rawEvent, policyFlags);
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);
                    if (!mMagnifiedBounds.contains((int) event.getX(),
                            (int) event.getY())) {
                        transitionToDelegatingStateAndClear();
                        return;
                    }
                    if (mTapCount == ACTION_TAP_COUNT - 1 && mLastDownEvent != null
                            && GestureUtils.isMultiTap(mLastDownEvent, event,
                                    mMultiTapTimeSlop, mMultiTapDistanceSlop, 0)) {
                        Message message = mHandler.obtainMessage(MESSAGE_ON_ACTION_TAP_AND_HOLD,
                                policyFlags, 0, event);
                        mHandler.sendMessageDelayed(message,
                                ViewConfiguration.getLongPressTimeout());
                    } else if (mTapCount < ACTION_TAP_COUNT) {
                        Message message = mHandler.obtainMessage(
                                MESSAGE_TRANSITION_TO_DELEGATING_STATE);
                        mHandler.sendMessageDelayed(message, mMultiTapTimeSlop);
                    }
                    clearLastDownEvent();
                    mLastDownEvent = MotionEvent.obtain(event);
                } break;
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (mMagnificationController.isMagnifying()) {
                        transitionToState(STATE_MAGNIFIED_INTERACTION);
                        clear();
                    } else {
                        transitionToDelegatingStateAndClear();
                    }
                } break;
                case MotionEvent.ACTION_MOVE: {
                    if (mLastDownEvent != null && mTapCount < ACTION_TAP_COUNT - 1) {
                        final double distance = GestureUtils.computeDistance(mLastDownEvent,
                                event, 0);
                        if (Math.abs(distance) > mTapDistanceSlop) {
                            transitionToDelegatingStateAndClear();
                        }
                    }
                } break;
                case MotionEvent.ACTION_UP: {
                    if (mLastDownEvent == null) {
                        return;
                    }
                    mHandler.removeMessages(MESSAGE_ON_ACTION_TAP_AND_HOLD);
                    if (!mMagnifiedBounds.contains((int) event.getX(), (int) event.getY())) {
                         transitionToDelegatingStateAndClear();
                         return;
                    }
                    if (!GestureUtils.isTap(mLastDownEvent, event, mTapTimeSlop,
                            mTapDistanceSlop, 0)) {
                        transitionToDelegatingStateAndClear();
                        return;
                    }
                    if (mLastTapUpEvent != null && !GestureUtils.isMultiTap(mLastTapUpEvent,
                            event, mMultiTapTimeSlop, mMultiTapDistanceSlop, 0)) {
                        transitionToDelegatingStateAndClear();
                        return;
                    }
                    mTapCount++;
                    if (DEBUG_DETECTING) {
                        Slog.i(LOG_TAG, "Tap count:" + mTapCount);
                    }
                    if (mTapCount == ACTION_TAP_COUNT) {
                        clear();
                        onActionTap(event, policyFlags);
                        return;
                    }
                    clearLastTapUpEvent();
                    mLastTapUpEvent = MotionEvent.obtain(event);
                } break;
                case MotionEvent.ACTION_POINTER_UP: {
                    /* do nothing */
                } break;
            }
        }

        public void clear() {
            mHandler.removeMessages(MESSAGE_ON_ACTION_TAP_AND_HOLD);
            mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);
            clearTapDetectionState();
            clearDelayedMotionEvents();
        }

        private void clearTapDetectionState() {
            mTapCount = 0;
            clearLastTapUpEvent();
            clearLastDownEvent();
        }

        private void clearLastTapUpEvent() {
            if (mLastTapUpEvent != null) {
                mLastTapUpEvent.recycle();
                mLastTapUpEvent = null;
            }
        }

        private void clearLastDownEvent() {
            if (mLastDownEvent != null) {
                mLastDownEvent.recycle();
                mLastDownEvent = null;
            }
        }

        private void cacheDelayedMotionEvent(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            MotionEventInfo info = MotionEventInfo.obtain(event, rawEvent,
                    policyFlags);
            if (mDelayedEventQueue == null) {
                mDelayedEventQueue = info;
            } else {
                MotionEventInfo tail = mDelayedEventQueue;
                while (tail.mNext != null) {
                    tail = tail.mNext;
                }
                tail.mNext = info;
            }
        }

        private void sendDelayedMotionEvents() {
            while (mDelayedEventQueue != null) {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;
                final long offset = SystemClock.uptimeMillis() - info.mCachedTimeMillis;
                MotionEvent event = obtainEventWithOffsetTimeAndDownTime(info.mEvent, offset);
                MotionEvent rawEvent = obtainEventWithOffsetTimeAndDownTime(info.mRawEvent, offset);
                ScreenMagnifier.this.onMotionEvent(event, rawEvent, info.mPolicyFlags);
                event.recycle();
                rawEvent.recycle();
                info.recycle();
            }
        }

        private MotionEvent obtainEventWithOffsetTimeAndDownTime(MotionEvent event, long offset) {
            final int pointerCount = event.getPointerCount();
            PointerCoords[] coords = getTempPointerCoordsWithMinSize(pointerCount);
            PointerProperties[] properties = getTempPointerPropertiesWithMinSize(pointerCount);
            for (int i = 0; i < pointerCount; i++) {
                event.getPointerCoords(i, coords[i]);
                event.getPointerProperties(i, properties[i]);
            }
            final long downTime = event.getDownTime() + offset;
            final long eventTime = event.getEventTime() + offset;
            return MotionEvent.obtain(downTime, eventTime,
                    event.getAction(), pointerCount, properties, coords,
                    event.getMetaState(), event.getButtonState(),
                    1.0f, 1.0f, event.getDeviceId(), event.getEdgeFlags(),
                    event.getSource(), event.getFlags());
        }

        private void clearDelayedMotionEvents() {
            while (mDelayedEventQueue != null) {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;
                info.recycle();
            }
        }

        private void transitionToDelegatingStateAndClear() {
            transitionToState(STATE_DELEGATING);
            sendDelayedMotionEvents();
            clear();
        }

        private void onActionTap(MotionEvent up, int policyFlags) {
            if (DEBUG_DETECTING) {
                Slog.i(LOG_TAG, "onActionTap()");
            }
            if (!mMagnificationController.isMagnifying()) {
                mMagnificationController.setScaleAndMagnifiedRegionCenter(getPersistedScale(),
                        up.getX(), up.getY(), true);
            } else {
                mMagnificationController.reset(true);
            }
        }

        private void onActionTapAndHold(MotionEvent down, int policyFlags) {
            if (DEBUG_DETECTING) {
                Slog.i(LOG_TAG, "onActionTapAndHold()");
            }
            clear();
            mTranslationEnabledBeforePan = mMagnificationController.isMagnifying();
            mMagnificationController.setScaleAndMagnifiedRegionCenter(getPersistedScale(),
                    down.getX(), down.getY(), true);
            transitionToState(STATE_VIEWPORT_DRAGGING);
        }
    }

    private void persistScale(final float scale) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Settings.Secure.putFloat(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, scale);
                return null;
            }
        }.execute();
    }

    private float getPersistedScale() {
        return Settings.Secure.getFloat(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                DEFAULT_MAGNIFICATION_SCALE);
    }

    private static boolean isScreenMagnificationAutoUpdateEnabled(Context context) {
        return (Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_AUTO_UPDATE,
                DEFAULT_SCREEN_MAGNIFICATION_AUTO_UPDATE) == 1);
    }

    private static final class MotionEventInfo {

        private static final int MAX_POOL_SIZE = 10;

        private static final Object sLock = new Object();
        private static MotionEventInfo sPool;
        private static int sPoolSize;

        private MotionEventInfo mNext;
        private boolean mInPool;

        public MotionEvent mEvent;
        public MotionEvent mRawEvent;
        public int mPolicyFlags;
        public long mCachedTimeMillis;

        public static MotionEventInfo obtain(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            synchronized (sLock) {
                MotionEventInfo info;
                if (sPoolSize > 0) {
                    sPoolSize--;
                    info = sPool;
                    sPool = info.mNext;
                    info.mNext = null;
                    info.mInPool = false;
                } else {
                    info = new MotionEventInfo();
                }
                info.initialize(event, rawEvent, policyFlags);
                return info;
            }
        }

        private void initialize(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            mEvent = MotionEvent.obtain(event);
            mRawEvent = MotionEvent.obtain(rawEvent);
            mPolicyFlags = policyFlags;
            mCachedTimeMillis = SystemClock.uptimeMillis();
        }

        public void recycle() {
            synchronized (sLock) {
                if (mInPool) {
                    throw new IllegalStateException("Already recycled.");
                }
                clear();
                if (sPoolSize < MAX_POOL_SIZE) {
                    sPoolSize++;
                    mNext = sPool;
                    sPool = this;
                    mInPool = true;
                }
            }
        }

        private void clear() {
            mEvent.recycle();
            mEvent = null;
            mRawEvent.recycle();
            mRawEvent = null;
            mPolicyFlags = 0;
            mCachedTimeMillis = 0;
        }
    }

    private final class MagnificationController {

        private static final String PROPERTY_NAME_MAGNIFICATION_SPEC =
                "magnificationSpec";

        private final MagnificationSpec mSentMagnificationSpec = MagnificationSpec.obtain();

        private final MagnificationSpec mCurrentMagnificationSpec = MagnificationSpec.obtain();

        private final Rect mTempRect = new Rect();

        private final ValueAnimator mTransformationAnimator;

        public MagnificationController(long animationDuration) {
            Property<MagnificationController, MagnificationSpec> property =
                    Property.of(MagnificationController.class, MagnificationSpec.class,
                    PROPERTY_NAME_MAGNIFICATION_SPEC);
            TypeEvaluator<MagnificationSpec> evaluator = new TypeEvaluator<MagnificationSpec>() {
                private final MagnificationSpec mTempTransformationSpec =
                        MagnificationSpec.obtain();
                @Override
                public MagnificationSpec evaluate(float fraction, MagnificationSpec fromSpec,
                        MagnificationSpec toSpec) {
                    MagnificationSpec result = mTempTransformationSpec;
                    result.scale = fromSpec.scale
                            + (toSpec.scale - fromSpec.scale) * fraction;
                    result.offsetX = fromSpec.offsetX + (toSpec.offsetX - fromSpec.offsetX)
                            * fraction;
                    result.offsetY = fromSpec.offsetY + (toSpec.offsetY - fromSpec.offsetY)
                            * fraction;
                    return result;
                }
            };
            mTransformationAnimator = ObjectAnimator.ofObject(this, property,
                    evaluator, mSentMagnificationSpec, mCurrentMagnificationSpec);
            mTransformationAnimator.setDuration((long) (animationDuration));
            mTransformationAnimator.setInterpolator(new DecelerateInterpolator(2.5f));
        }

        public boolean isMagnifying() {
            return mCurrentMagnificationSpec.scale > 1.0f;
        }

        public void reset(boolean animate) {
            if (mTransformationAnimator.isRunning()) {
                mTransformationAnimator.cancel();
            }
            mCurrentMagnificationSpec.clear();
            if (animate) {
                animateMangificationSpec(mSentMagnificationSpec,
                        mCurrentMagnificationSpec);
            } else {
                setMagnificationSpec(mCurrentMagnificationSpec);
            }
            Rect bounds = mTempRect;
            bounds.setEmpty();
            mAms.onMagnificationStateChanged();
        }

        public float getScale() {
            return mCurrentMagnificationSpec.scale;
        }

        public float getOffsetX() {
            return mCurrentMagnificationSpec.offsetX;
        }

        public float getOffsetY() {
            return mCurrentMagnificationSpec.offsetY;
        }

        public void setScale(float scale, float pivotX, float pivotY, boolean animate) {
            Rect magnifiedFrame = mTempRect;
            mMagnifiedBounds.getBounds(magnifiedFrame);
            MagnificationSpec spec = mCurrentMagnificationSpec;
            final float oldScale = spec.scale;
            final float oldCenterX = (-spec.offsetX + magnifiedFrame.width() / 2) / oldScale;
            final float oldCenterY = (-spec.offsetY + magnifiedFrame.height() / 2) / oldScale;
            final float normPivotX = (-spec.offsetX + pivotX) / oldScale;
            final float normPivotY = (-spec.offsetY + pivotY) / oldScale;
            final float offsetX = (oldCenterX - normPivotX) * (oldScale / scale);
            final float offsetY = (oldCenterY - normPivotY) * (oldScale / scale);
            final float centerX = normPivotX + offsetX;
            final float centerY = normPivotY + offsetY;
            setScaleAndMagnifiedRegionCenter(scale, centerX, centerY, animate);
        }

        public void setMagnifiedRegionCenter(float centerX, float centerY, boolean animate) {
            setScaleAndMagnifiedRegionCenter(mCurrentMagnificationSpec.scale, centerX, centerY,
                    animate);
        }

        public void offsetMagnifiedRegionCenter(float offsetX, float offsetY) {
            final float nonNormOffsetX = mCurrentMagnificationSpec.offsetX - offsetX;
            mCurrentMagnificationSpec.offsetX = Math.min(Math.max(nonNormOffsetX,
                    getMinOffsetX()), 0);
            final float nonNormOffsetY = mCurrentMagnificationSpec.offsetY - offsetY;
            mCurrentMagnificationSpec.offsetY = Math.min(Math.max(nonNormOffsetY,
                    getMinOffsetY()), 0);
            setMagnificationSpec(mCurrentMagnificationSpec);
        }

        public void setScaleAndMagnifiedRegionCenter(float scale, float centerX, float centerY,
                boolean animate) {
            if (Float.compare(mCurrentMagnificationSpec.scale, scale) == 0
                    && Float.compare(mCurrentMagnificationSpec.offsetX,
                            centerX) == 0
                    && Float.compare(mCurrentMagnificationSpec.offsetY,
                            centerY) == 0) {
                return;
            }
            if (mTransformationAnimator.isRunning()) {
                mTransformationAnimator.cancel();
            }
            if (DEBUG_MAGNIFICATION_CONTROLLER) {
                Slog.i(LOG_TAG, "scale: " + scale + " offsetX: " + centerX
                        + " offsetY: " + centerY);
            }
            updateMagnificationSpec(scale, centerX, centerY);
            if (animate) {
                animateMangificationSpec(mSentMagnificationSpec,
                        mCurrentMagnificationSpec);
            } else {
                setMagnificationSpec(mCurrentMagnificationSpec);
            }
            mAms.onMagnificationStateChanged();
        }

        public void updateMagnificationSpec(float scale, float magnifiedCenterX,
                float magnifiedCenterY) {
            Rect magnifiedFrame = mTempRect;
            mMagnifiedBounds.getBounds(magnifiedFrame);
            mCurrentMagnificationSpec.scale = scale;
            final int viewportWidth = magnifiedFrame.width();
            final float nonNormOffsetX = viewportWidth / 2 - magnifiedCenterX * scale;
            mCurrentMagnificationSpec.offsetX = Math.min(Math.max(nonNormOffsetX,
                    getMinOffsetX()), 0);
            final int viewportHeight = magnifiedFrame.height();
            final float nonNormOffsetY = viewportHeight / 2 - magnifiedCenterY * scale;
            mCurrentMagnificationSpec.offsetY = Math.min(Math.max(nonNormOffsetY,
                    getMinOffsetY()), 0);
        }

        private float getMinOffsetX() {
            Rect magnifiedFrame = mTempRect;
            mMagnifiedBounds.getBounds(magnifiedFrame);
            final float viewportWidth = magnifiedFrame.width();
            return viewportWidth - viewportWidth * mCurrentMagnificationSpec.scale;
        }

        private float getMinOffsetY() {
            Rect magnifiedFrame = mTempRect;
            mMagnifiedBounds.getBounds(magnifiedFrame);
            final float viewportHeight = magnifiedFrame.height();
            return viewportHeight - viewportHeight * mCurrentMagnificationSpec.scale;
        }

        private void animateMangificationSpec(MagnificationSpec fromSpec,
                MagnificationSpec toSpec) {
            mTransformationAnimator.setObjectValues(fromSpec, toSpec);
            mTransformationAnimator.start();
        }

        public MagnificationSpec getMagnificationSpec() {
            return mSentMagnificationSpec;
        }

        public void setMagnificationSpec(MagnificationSpec spec) {
            if (DEBUG_SET_MAGNIFICATION_SPEC) {
                Slog.i(LOG_TAG, "Sending: " + spec);
            }
            try {
                mSentMagnificationSpec.scale = spec.scale;
                mSentMagnificationSpec.offsetX = spec.offsetX;
                mSentMagnificationSpec.offsetY = spec.offsetY;
                mWindowManager.setMagnificationSpec(
                        MagnificationSpec.obtain(spec));
            } catch (RemoteException re) {
                /* ignore */
            }
        }
    }

    private final class ScreenStateObserver extends BroadcastReceiver {
        private static final int MESSAGE_ON_SCREEN_STATE_CHANGE = 1;

        private final Context mContext;
        private final MagnificationController mMagnificationController;

        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                 switch (message.what) {
                    case MESSAGE_ON_SCREEN_STATE_CHANGE: {
                        String action = (String) message.obj;
                        handleOnScreenStateChange(action);
                    } break;
                }
            }
        };

        public ScreenStateObserver(Context context,
                MagnificationController magnificationController) {
            mContext = context;
            mMagnificationController = magnificationController;
            mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }

        public void destroy() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.obtainMessage(MESSAGE_ON_SCREEN_STATE_CHANGE,
                    intent.getAction()).sendToTarget();
        }

        private void handleOnScreenStateChange(String action) {
            if (mMagnificationController.isMagnifying()
                    && isScreenMagnificationAutoUpdateEnabled(mContext)) {
                mMagnificationController.reset(false);
            }
        }
    }
}
