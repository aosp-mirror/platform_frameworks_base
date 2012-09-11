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

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Property;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IDisplayContentChangeListener;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.internal.R;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;

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
 *    finger goes up the screen will clear zoom out. If the same user interaction
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
 * 5. When in a permanent magnified state the user can use three or more
 *    fingers to change the magnification scale which will become the current
 *    default magnification scale. The next time the user magnifies the same
 *    magnification scale would be used.
 *
 * 6. The magnification scale will be persisted in settings and in the cloud.
 */
public final class ScreenMagnifier implements EventStreamTransformation {

    private static final boolean DEBUG_STATE_TRANSITIONS = false;
    private static final boolean DEBUG_DETECTING = false;
    private static final boolean DEBUG_TRANSFORMATION = false;
    private static final boolean DEBUG_PANNING = false;
    private static final boolean DEBUG_SCALING = false;
    private static final boolean DEBUG_VIEWPORT_WINDOW = false;
    private static final boolean DEBUG_WINDOW_TRANSITIONS = false;
    private static final boolean DEBUG_ROTATION = false;
    private static final boolean DEBUG_GESTURE_DETECTOR = false;
    private static final boolean DEBUG_MAGNIFICATION_CONTROLLER = false;

    private static final String LOG_TAG = ScreenMagnifier.class.getSimpleName();

    private static final int STATE_DELEGATING = 1;
    private static final int STATE_DETECTING = 2;
    private static final int STATE_VIEWPORT_DRAGGING = 3;
    private static final int STATE_MAGNIFIED_INTERACTION = 4;

    private static final float DEFAULT_MAGNIFICATION_SCALE = 2.0f;
    private static final int DEFAULT_SCREEN_MAGNIFICATION_AUTO_UPDATE = 1;
    private static final float DEFAULT_WINDOW_ANIMATION_SCALE = 1.0f;

    private static final int MULTI_TAP_TIME_SLOP_ADJUSTMENT = 50;

    private final IWindowManager mWindowManagerService = IWindowManager.Stub.asInterface(
            ServiceManager.getService("window"));
    private final WindowManager mWindowManager;
    private final DisplayProvider mDisplayProvider;

    private final DetectingStateHandler mDetectingStateHandler = new DetectingStateHandler();
    private final GestureDetector mGestureDetector;
    private final StateViewportDraggingHandler mStateViewportDraggingHandler =
            new StateViewportDraggingHandler();

    private final Interpolator mInterpolator = new DecelerateInterpolator(2.5f);

    private final MagnificationController mMagnificationController;
    private final DisplayContentObserver mDisplayContentObserver;
    private final ScreenStateObserver mScreenStateObserver;
    private final Viewport mViewport;

    private final int mTapTimeSlop = ViewConfiguration.getTapTimeout();
    private final int mMultiTapTimeSlop =
            ViewConfiguration.getDoubleTapTimeout() - MULTI_TAP_TIME_SLOP_ADJUSTMENT;
    private final int mTapDistanceSlop;
    private final int mMultiTapDistanceSlop;

    private final int mShortAnimationDuration;
    private final int mLongAnimationDuration;
    private final float mWindowAnimationScale;

    private final Context mContext;

    private EventStreamTransformation mNext;

    private int mCurrentState;
    private int mPreviousState;
    private boolean mTranslationEnabledBeforePan;

    private PointerCoords[] mTempPointerCoords;
    private PointerProperties[] mTempPointerProperties;

    public ScreenMagnifier(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        mShortAnimationDuration = context.getResources().getInteger(
                com.android.internal.R.integer.config_shortAnimTime);
        mLongAnimationDuration = context.getResources().getInteger(
                com.android.internal.R.integer.config_longAnimTime);
        mTapDistanceSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mMultiTapDistanceSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        mWindowAnimationScale = Settings.System.getFloat(context.getContentResolver(),
                Settings.System.WINDOW_ANIMATION_SCALE, DEFAULT_WINDOW_ANIMATION_SCALE);

        mMagnificationController = new MagnificationController(mShortAnimationDuration);
        mDisplayProvider = new DisplayProvider(context, mWindowManager);
        mViewport = new Viewport(mContext, mWindowManager, mWindowManagerService,
                mDisplayProvider, mInterpolator, mShortAnimationDuration);
        mDisplayContentObserver = new DisplayContentObserver(mContext, mViewport,
                mMagnificationController, mWindowManagerService, mDisplayProvider,
                mLongAnimationDuration, mWindowAnimationScale);
        mScreenStateObserver = new ScreenStateObserver(mContext, mViewport,
                mMagnificationController);

        mGestureDetector = new GestureDetector(context);

        transitionToState(STATE_DETECTING);
    }

    @Override
    public void onMotionEvent(MotionEvent event, int policyFlags) {
        mGestureDetector.onMotionEvent(event);
        switch (mCurrentState) {
            case STATE_DELEGATING: {
                handleMotionEventStateDelegating(event, policyFlags);
            } break;
            case STATE_DETECTING: {
                mDetectingStateHandler.onMotionEvent(event, policyFlags);
            } break;
            case STATE_VIEWPORT_DRAGGING: {
                mStateViewportDraggingHandler.onMotionEvent(event, policyFlags);
            } break;
            case STATE_MAGNIFIED_INTERACTION: {
                // Handled by the gesture detector. Since the detector
                // needs all touch events to work properly we cannot
                // call it only for this state.
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
        mGestureDetector.clear();
        if (mNext != null) {
            mNext.clear();
        }
    }

    @Override
    public void onDestroy() {
        mMagnificationController.setScaleAndMagnifiedRegionCenter(1.0f,
                0, 0, true);
        mViewport.setFrameShown(false, true);
        mDisplayProvider.destroy();
        mDisplayContentObserver.destroy();
        mScreenStateObserver.destroy();
    }

    private void handleMotionEventStateDelegating(MotionEvent event, int policyFlags) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (mDetectingStateHandler.mDelayedEventQueue == null) {
                transitionToState(STATE_DETECTING);
            }
        }
        if (mNext != null) {
            // If the event is within the magnified portion of the screen we have
            // to change its location to be where the user thinks he is poking the
            // UI which may have been magnified and panned.
            final float eventX = event.getX();
            final float eventY = event.getY();
            if (mMagnificationController.isMagnifying()
                    && mViewport.getBounds().contains((int) eventX, (int) eventY)) {
                final float scale = mMagnificationController.getScale();
                final float scaledOffsetX = mMagnificationController.getScaledOffsetX();
                final float scaledOffsetY = mMagnificationController.getScaledOffsetY();
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
            mNext.onMotionEvent(event, policyFlags);
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

    private final class GestureDetector implements OnScaleGestureListener {
        private static final float MIN_SCALE = 1.3f;
        private static final float MAX_SCALE = 5.0f;

        private static final float DETECT_SCALING_THRESHOLD = 0.30f;
        private static final int DETECT_PANNING_THRESHOLD_DIP = 30;

        private final float mScaledDetectPanningThreshold;

        private final ScaleGestureDetector mScaleGestureDetector;

        private final PointF mPrevFocus = new PointF(Float.NaN, Float.NaN);
        private final PointF mInitialFocus = new PointF(Float.NaN, Float.NaN);

        private float mCurrScale = Float.NaN;
        private float mCurrScaleFactor = 1.0f;
        private float mPrevScaleFactor = 1.0f;
        private float mCurrPan;
        private float mPrevPan;

        private float mScaleFocusX = Float.NaN;
        private float mScaleFocusY = Float.NaN;

        private boolean mScaling;
        private boolean mPanning;

        public GestureDetector(Context context) {
            final float density = context.getResources().getDisplayMetrics().density;
            mScaledDetectPanningThreshold = DETECT_PANNING_THRESHOLD_DIP * density;
            mScaleGestureDetector = new ScaleGestureDetector(this);
        }

        public void onMotionEvent(MotionEvent event) {
            mScaleGestureDetector.onTouchEvent(event);
            switch (mCurrentState) {
                case STATE_DETECTING:
                case STATE_DELEGATING:
                case STATE_VIEWPORT_DRAGGING: {
                    return;
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                clear();
                final float scale = mMagnificationController.getScale();
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
        public boolean onScale(ScaleGestureDetector detector) {
            switch (mCurrentState) {
                case STATE_DETECTING:
                case STATE_DELEGATING:
                case STATE_VIEWPORT_DRAGGING: {
                    return true;
                }
                case STATE_MAGNIFIED_INTERACTION: {
                    mCurrScaleFactor = mScaleGestureDetector.getScaleFactor();
                    final float scaleDelta = Math.abs(1.0f - mCurrScaleFactor * mPrevScaleFactor);
                    if (DEBUG_GESTURE_DETECTOR) {
                        Slog.i(LOG_TAG, "scaleDelta: " + scaleDelta);
                    }
                    if (!mScaling && scaleDelta > DETECT_SCALING_THRESHOLD) {
                        mScaling = true;
                        clearContextualState();
                        return true;
                    }
                    if (mScaling) {
                        performScale(detector);
                    }
                    mCurrPan = (float) MathUtils.dist(
                            mScaleGestureDetector.getFocusX(),
                            mScaleGestureDetector.getFocusY(),
                            mInitialFocus.x, mInitialFocus.y);
                    final float panDelta = mCurrPan + mPrevPan;
                    if (DEBUG_GESTURE_DETECTOR) {
                        Slog.i(LOG_TAG, "panDelta: " + panDelta);
                    }
                    if (!mPanning && panDelta > mScaledDetectPanningThreshold) {
                        mPanning = true;
                        clearContextualState();
                        return true;
                    }
                    if (mPanning) {
                        performPan(detector);
                    }
                } break;
            }
            return false;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mPrevScaleFactor *= mCurrScaleFactor;
            mCurrScale = Float.NaN;
            mPrevPan += mCurrPan;
            mPrevFocus.x = mInitialFocus.x = detector.getFocusX();
            mPrevFocus.y = mInitialFocus.y = detector.getFocusY();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            clearContextualState();
        }

        public void clear() {
            clearContextualState();
            mScaling = false;
            mPanning = false;
        }

        private void clearContextualState() {
            mCurrScaleFactor = 1.0f;
            mPrevScaleFactor = 1.0f;
            mPrevPan = 0;
            mCurrPan = 0;
            mInitialFocus.set(Float.NaN, Float.NaN);
            mPrevFocus.set(Float.NaN, Float.NaN);
            mCurrScale = Float.NaN;
            mScaleFocusX = Float.NaN;
            mScaleFocusY = Float.NaN;
        }

        private void performPan(ScaleGestureDetector detector) {
            if (Float.compare(mPrevFocus.x, Float.NaN) == 0
                    && Float.compare(mPrevFocus.y, Float.NaN) == 0) {
                mPrevFocus.set(detector.getFocusX(), detector.getFocusY());
                return;
            }
            final float scale = mMagnificationController.getScale();
            final float scrollX = (detector.getFocusX() - mPrevFocus.x) / scale;
            final float scrollY = (detector.getFocusY() - mPrevFocus.y) / scale;
            final float centerX = mMagnificationController.getMagnifiedRegionCenterX()
                    - scrollX;
            final float centerY = mMagnificationController.getMagnifiedRegionCenterY()
                    - scrollY;
            if (DEBUG_PANNING) {
                Slog.i(LOG_TAG, "Panned content by scrollX: " + scrollX
                        + " scrollY: " + scrollY);
            }
            mMagnificationController.setMagnifiedRegionCenter(centerX, centerY, false);
            mPrevFocus.set(detector.getFocusX(), detector.getFocusY());
        }

        private void performScale(ScaleGestureDetector detector) {
            if (Float.compare(mCurrScale, Float.NaN) == 0) {
                mCurrScale = mMagnificationController.getScale();
                return;
            }
            final float totalScaleFactor = mPrevScaleFactor * detector.getScaleFactor();
            final float newScale = mCurrScale * totalScaleFactor;
            final float normalizedNewScale = Math.min(Math.max(newScale, MIN_SCALE),
                    MAX_SCALE);
            if (DEBUG_SCALING) {
                Slog.i(LOG_TAG, "normalizedNewScale: " + normalizedNewScale);
            }
            if (Float.compare(mScaleFocusX, Float.NaN) == 0
                    && Float.compare(mScaleFocusY, Float.NaN) == 0) {
                mScaleFocusX = detector.getFocusX();
                mScaleFocusY = detector.getFocusY();
            }
            mMagnificationController.setScale(normalizedNewScale, mScaleFocusX,
                    mScaleFocusY, false);
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
                    if (mViewport.getBounds().contains((int) eventX, (int) eventY)) {
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
                        mViewport.setFrameShown(false, true);
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

        public void onMotionEvent(MotionEvent event, int policyFlags) {
            cacheDelayedMotionEvent(event, policyFlags);
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);
                    if (!mViewport.getBounds().contains((int) event.getX(),
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
                    if (!mViewport.getBounds().contains((int) event.getX(), (int) event.getY())) {
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

        private void cacheDelayedMotionEvent(MotionEvent event, int policyFlags) {
            MotionEventInfo info = MotionEventInfo.obtain(event, policyFlags);
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
                ScreenMagnifier.this.onMotionEvent(info.mEvent, info.mPolicyFlags);
                info.recycle();
            }
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
                mViewport.setFrameShown(true, true);
            } else {
                mMagnificationController.reset(true);
                mViewport.setFrameShown(false, true);
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
            mViewport.setFrameShown(true, true);
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
        public int mPolicyFlags;

        public static MotionEventInfo obtain(MotionEvent event, int policyFlags) {
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
                info.initialize(event, policyFlags);
                return info;
            }
        }

        private void initialize(MotionEvent event, int policyFlags) {
            mEvent = MotionEvent.obtain(event);
            mPolicyFlags = policyFlags;
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
            mPolicyFlags = 0;
        }
    }

    private static final class ScreenStateObserver extends BroadcastReceiver {

        private static final int MESSAGE_ON_SCREEN_STATE_CHANGE = 1;

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

        private final Context mContext;
        private final Viewport mViewport;
        private final MagnificationController mMagnificationController;

        public ScreenStateObserver(Context context, Viewport viewport,
                MagnificationController magnificationController) {
            mContext = context;
            mViewport = viewport;
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
            if (action.equals(Intent.ACTION_SCREEN_OFF)
                    && mMagnificationController.isMagnifying()
                    && isScreenMagnificationAutoUpdateEnabled(mContext)) {
                mMagnificationController.reset(false);
                mViewport.setFrameShown(false, false);
            }
        }
    }

    private static final class DisplayContentObserver {

        private static final int MESSAGE_SHOW_VIEWPORT_FRAME = 1;
        private static final int MESSAGE_RECOMPUTE_VIEWPORT_BOUNDS = 2;
        private static final int MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED = 3;
        private static final int MESSAGE_ON_WINDOW_TRANSITION = 4;
        private static final int MESSAGE_ON_ROTATION_CHANGED = 5;

        private final Handler mHandler = new MyHandler();

        private final Rect mTempRect = new Rect();

        private final IDisplayContentChangeListener mDisplayContentChangeListener;

        private final Context mContext;
        private final Viewport mViewport;
        private final MagnificationController mMagnificationController;
        private final IWindowManager mWindowManagerService;
        private final DisplayProvider mDisplayProvider;
        private final long mLongAnimationDuration;
        private final float mWindowAnimationScale;

        public DisplayContentObserver(Context context, Viewport viewport,
                MagnificationController magnificationController,
                IWindowManager windowManagerService, DisplayProvider displayProvider,
                long longAnimationDuration, float windowAnimationScale) {
            mContext = context;
            mViewport = viewport;
            mMagnificationController = magnificationController;
            mWindowManagerService = windowManagerService;
            mDisplayProvider = displayProvider;
            mLongAnimationDuration = longAnimationDuration;
            mWindowAnimationScale = windowAnimationScale;

            mDisplayContentChangeListener = new IDisplayContentChangeListener.Stub() {
                @Override
                public void onWindowTransition(int displayId, int transition, WindowInfo info) {
                    mHandler.obtainMessage(MESSAGE_ON_WINDOW_TRANSITION, transition, 0,
                            WindowInfo.obtain(info)).sendToTarget();
                }

                @Override
                public void onRectangleOnScreenRequested(int dsiplayId, Rect rectangle,
                        boolean immediate) {
                    SomeArgs args = SomeArgs.obtain();
                    args.argi1 = rectangle.left;
                    args.argi2 = rectangle.top;
                    args.argi3 = rectangle.right;
                    args.argi4 = rectangle.bottom;
                    mHandler.obtainMessage(MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED, 0,
                            immediate ? 1 : 0, args).sendToTarget();
                }

                @Override
                public void onRotationChanged(int rotation) throws RemoteException {
                    mHandler.obtainMessage(MESSAGE_ON_ROTATION_CHANGED, rotation, 0)
                            .sendToTarget();
                }
            };

            try {
                mWindowManagerService.addDisplayContentChangeListener(
                        mDisplayProvider.getDisplay().getDisplayId(),
                        mDisplayContentChangeListener);
            } catch (RemoteException re) {
                /* ignore */
            }
        }

        public void destroy() {
            try {
                mWindowManagerService.removeDisplayContentChangeListener(
                        mDisplayProvider.getDisplay().getDisplayId(),
                        mDisplayContentChangeListener);
            } catch (RemoteException re) {
                /* ignore*/
            }
        }

        private void handleOnRotationChanged(int rotation) {
            if (DEBUG_ROTATION) {
                Slog.i(LOG_TAG, "Rotation: " + rotationToString(rotation));
            }
            resetMagnificationIfNeeded();
            mViewport.setFrameShown(false, false);
            mViewport.rotationChanged();
            mViewport.recomputeBounds(false);
            if (mMagnificationController.isMagnifying()) {
                final long delay = (long) (2 * mLongAnimationDuration * mWindowAnimationScale);
                Message message = mHandler.obtainMessage(MESSAGE_SHOW_VIEWPORT_FRAME);
                mHandler.sendMessageDelayed(message, delay);
            }
        }

        private void handleOnWindowTransition(int transition, WindowInfo info) {
            if (DEBUG_WINDOW_TRANSITIONS) {
                Slog.i(LOG_TAG, "Window transitioning: "
                        + windowTransitionToString(transition));
            }
            try {
                final boolean magnifying = mMagnificationController.isMagnifying();
                if (magnifying) {
                    switch (transition) {
                        case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
                        case WindowManagerPolicy.TRANSIT_TASK_OPEN:
                        case WindowManagerPolicy.TRANSIT_TASK_TO_FRONT:
                        case WindowManagerPolicy.TRANSIT_WALLPAPER_OPEN:
                        case WindowManagerPolicy.TRANSIT_WALLPAPER_CLOSE:
                        case WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN: {
                            resetMagnificationIfNeeded();
                        }
                    }
                }
                if (info.type == WindowManager.LayoutParams.TYPE_NAVIGATION_BAR
                        || info.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD
                        || info.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG) {
                    switch (transition) {
                        case WindowManagerPolicy.TRANSIT_ENTER:
                        case WindowManagerPolicy.TRANSIT_SHOW:
                        case WindowManagerPolicy.TRANSIT_EXIT:
                        case WindowManagerPolicy.TRANSIT_HIDE: {
                            mViewport.recomputeBounds(mMagnificationController.isMagnifying());
                        } break;
                    }
                } else {
                    switch (transition) {
                        case WindowManagerPolicy.TRANSIT_ENTER:
                        case WindowManagerPolicy.TRANSIT_SHOW: {
                            if (!magnifying || !isScreenMagnificationAutoUpdateEnabled(mContext)) {
                                break;
                            }
                            final int type = info.type;
                            switch (type) {
                                // TODO: Are these all the windows we want to make
                                //       visible when they appear on the screen?
                                //       Do we need to take some of them out?
                                case WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:
                                case WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA:
                                case WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL:
                                case WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG:
                                case WindowManager.LayoutParams.TYPE_SEARCH_BAR:
                                case WindowManager.LayoutParams.TYPE_PHONE:
                                case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                                case WindowManager.LayoutParams.TYPE_TOAST:
                                case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY:
                                case WindowManager.LayoutParams.TYPE_PRIORITY_PHONE:
                                case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                                case WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG:
                                case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                                case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                                case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL: {
                                    Rect magnifiedRegionBounds = mMagnificationController
                                            .getMagnifiedRegionBounds();
                                    Rect touchableRegion = info.touchableRegion;
                                    if (!magnifiedRegionBounds.intersect(touchableRegion)) {
                                        ensureRectangleInMagnifiedRegionBounds(
                                                magnifiedRegionBounds, touchableRegion);
                                    }
                                } break;
                            } break;
                        }
                    }
                }
            } finally {
                if (info != null) {
                    info.recycle();
                }
            }
        }

        private void handleOnRectangleOnScreenRequested(Rect rectangle, boolean immediate) {
            if (!mMagnificationController.isMagnifying()) {
                return;
            }
            Rect magnifiedRegionBounds = mMagnificationController.getMagnifiedRegionBounds();
            if (magnifiedRegionBounds.contains(rectangle)) {
                return;
            }
            ensureRectangleInMagnifiedRegionBounds(magnifiedRegionBounds, rectangle);
        }

        private void ensureRectangleInMagnifiedRegionBounds(Rect magnifiedRegionBounds,
                Rect rectangle) {
            if (!Rect.intersects(rectangle, mViewport.getBounds())) {
                return;
            }
            final float scrollX;
            final float scrollY;
            if (rectangle.width() > magnifiedRegionBounds.width()) {
                scrollX = rectangle.left - magnifiedRegionBounds.left;
            } else if (rectangle.left < magnifiedRegionBounds.left) {
                scrollX = rectangle.left - magnifiedRegionBounds.left;
            } else if (rectangle.right > magnifiedRegionBounds.right) {
                scrollX = rectangle.right - magnifiedRegionBounds.right;
            } else {
                scrollX = 0;
            }
            if (rectangle.height() > magnifiedRegionBounds.height()) {
                scrollY = rectangle.top - magnifiedRegionBounds.top;
            } else if (rectangle.top < magnifiedRegionBounds.top) {
                scrollY = rectangle.top - magnifiedRegionBounds.top;
            } else if (rectangle.bottom > magnifiedRegionBounds.bottom) {
                scrollY = rectangle.bottom - magnifiedRegionBounds.bottom;
            } else {
                scrollY = 0;
            }
            final float viewportCenterX = mMagnificationController.getMagnifiedRegionCenterX()
                    + scrollX;
            final float viewportCenterY = mMagnificationController.getMagnifiedRegionCenterY()
                    + scrollY;
            mMagnificationController.setMagnifiedRegionCenter(viewportCenterX, viewportCenterY,
                    true);
        }

        private void resetMagnificationIfNeeded() {
            if (mMagnificationController.isMagnifying()
                    && isScreenMagnificationAutoUpdateEnabled(mContext)) {
                mMagnificationController.reset(true);
                mViewport.setFrameShown(false, true);
            }
        }

        private String windowTransitionToString(int transition) {
            switch (transition) {
                case WindowManagerPolicy.TRANSIT_UNSET: {
                    return "TRANSIT_UNSET";
                }
                case WindowManagerPolicy.TRANSIT_NONE: {
                    return "TRANSIT_NONE";
                }
                case WindowManagerPolicy.TRANSIT_ENTER: {
                    return "TRANSIT_ENTER";
                }
                case WindowManagerPolicy.TRANSIT_EXIT: {
                    return "TRANSIT_EXIT";
                }
                case WindowManagerPolicy.TRANSIT_SHOW: {
                    return "TRANSIT_SHOW";
                }
                case WindowManagerPolicy.TRANSIT_EXIT_MASK: {
                    return "TRANSIT_EXIT_MASK";
                }
                case WindowManagerPolicy.TRANSIT_PREVIEW_DONE: {
                    return "TRANSIT_PREVIEW_DONE";
                }
                case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN: {
                    return "TRANSIT_ACTIVITY_OPEN";
                }
                case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE: {
                    return "TRANSIT_ACTIVITY_CLOSE";
                }
                case WindowManagerPolicy.TRANSIT_TASK_OPEN: {
                    return "TRANSIT_TASK_OPEN";
                }
                case WindowManagerPolicy.TRANSIT_TASK_CLOSE: {
                    return "TRANSIT_TASK_CLOSE";
                }
                case WindowManagerPolicy.TRANSIT_TASK_TO_FRONT: {
                    return "TRANSIT_TASK_TO_FRONT";
                }
                case WindowManagerPolicy.TRANSIT_TASK_TO_BACK: {
                    return "TRANSIT_TASK_TO_BACK";
                }
                case WindowManagerPolicy.TRANSIT_WALLPAPER_CLOSE: {
                    return "TRANSIT_WALLPAPER_CLOSE";
                }
                case WindowManagerPolicy.TRANSIT_WALLPAPER_OPEN: {
                    return "TRANSIT_WALLPAPER_OPEN";
                }
                case WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN: {
                    return "TRANSIT_WALLPAPER_INTRA_OPEN";
                }
                case WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_CLOSE: {
                    return "TRANSIT_WALLPAPER_INTRA_CLOSE";
                }
                default: {
                    return "<UNKNOWN>";
                }
            }
        }

        private String rotationToString(int rotation) {
            switch (rotation) {
                case Surface.ROTATION_0: {
                    return "ROTATION_0";
                }
                case Surface.ROTATION_90: {
                    return "ROATATION_90";
                }
                case Surface.ROTATION_180: {
                    return "ROATATION_180";
                }
                case Surface.ROTATION_270: {
                    return "ROATATION_270";
                }
                default: {
                    throw new IllegalArgumentException("Invalid rotation: "
                        + rotation);
                }
            }
        }

        private final class MyHandler extends Handler {
            @Override
            public void handleMessage(Message message) {
                final int action = message.what;
                switch (action) {
                    case MESSAGE_SHOW_VIEWPORT_FRAME: {
                        mViewport.setFrameShown(true, true);
                    } break;
                    case MESSAGE_RECOMPUTE_VIEWPORT_BOUNDS: {
                        final boolean animate = message.arg1 == 1;
                        mViewport.recomputeBounds(animate);
                    } break;
                    case MESSAGE_ON_RECTANGLE_ON_SCREEN_REQUESTED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        try {
                            mTempRect.set(args.argi1, args.argi2, args.argi3, args.argi4);
                            final boolean immediate = (message.arg1 == 1);
                            handleOnRectangleOnScreenRequested(mTempRect, immediate);
                        } finally {
                            args.recycle();
                        }
                    } break;
                    case MESSAGE_ON_WINDOW_TRANSITION: {
                        final int transition = message.arg1;
                        WindowInfo info = (WindowInfo) message.obj;
                        handleOnWindowTransition(transition, info);
                    } break;
                    case MESSAGE_ON_ROTATION_CHANGED: {
                        final int rotation = message.arg1;
                        handleOnRotationChanged(rotation);
                    } break;
                    default: {
                        throw new IllegalArgumentException("Unknown message: " + action);
                    }
                }
            }
        }
    }

    private final class MagnificationController {

        private static final String PROPERTY_NAME_ACCESSIBILITY_TRANSFORMATION =
                "accessibilityTransformation";

        private final MagnificationSpec mSentMagnificationSpec = new MagnificationSpec();

        private final MagnificationSpec mCurrentMagnificationSpec = new MagnificationSpec();

        private final Rect mTempRect = new Rect();

        private final ValueAnimator mTransformationAnimator;

        public MagnificationController(int animationDuration) {
            Property<MagnificationController, MagnificationSpec> property =
                    Property.of(MagnificationController.class, MagnificationSpec.class,
                    PROPERTY_NAME_ACCESSIBILITY_TRANSFORMATION);
            TypeEvaluator<MagnificationSpec> evaluator = new TypeEvaluator<MagnificationSpec>() {
                private final MagnificationSpec mTempTransformationSpec = new MagnificationSpec();
                @Override
                public MagnificationSpec evaluate(float fraction, MagnificationSpec fromSpec,
                        MagnificationSpec toSpec) {
                    MagnificationSpec result = mTempTransformationSpec;
                    result.mScale = fromSpec.mScale
                            + (toSpec.mScale - fromSpec.mScale) * fraction;
                    result.mMagnifiedRegionCenterX = fromSpec.mMagnifiedRegionCenterX
                            + (toSpec.mMagnifiedRegionCenterX - fromSpec.mMagnifiedRegionCenterX)
                            * fraction;
                    result.mMagnifiedRegionCenterY = fromSpec.mMagnifiedRegionCenterY
                            + (toSpec.mMagnifiedRegionCenterY - fromSpec.mMagnifiedRegionCenterY)
                            * fraction;
                    result.mScaledOffsetX = fromSpec.mScaledOffsetX
                            + (toSpec.mScaledOffsetX - fromSpec.mScaledOffsetX)
                            * fraction;
                    result.mScaledOffsetY = fromSpec.mScaledOffsetY
                            + (toSpec.mScaledOffsetY - fromSpec.mScaledOffsetY)
                            * fraction;
                    return result;
                }
            };
            mTransformationAnimator = ObjectAnimator.ofObject(this, property,
                    evaluator, mSentMagnificationSpec, mCurrentMagnificationSpec);
            mTransformationAnimator.setDuration((long) (animationDuration));
            mTransformationAnimator.setInterpolator(mInterpolator);
        }

        public boolean isMagnifying() {
            return mCurrentMagnificationSpec.mScale > 1.0f;
        }

        public void reset(boolean animate) {
            if (mTransformationAnimator.isRunning()) {
                mTransformationAnimator.cancel();
            }
            mCurrentMagnificationSpec.reset();
            if (animate) {
                animateAccessibilityTranformation(mSentMagnificationSpec,
                        mCurrentMagnificationSpec);
            } else {
                setAccessibilityTransformation(mCurrentMagnificationSpec);
            }
        }

        public Rect getMagnifiedRegionBounds() {
            mTempRect.set(mViewport.getBounds());
            mTempRect.offset((int) -mCurrentMagnificationSpec.mScaledOffsetX,
                    (int) -mCurrentMagnificationSpec.mScaledOffsetY);
            mTempRect.scale(1.0f / mCurrentMagnificationSpec.mScale);
            return mTempRect;
        }

        public float getScale() {
            return mCurrentMagnificationSpec.mScale;
        }

        public float getMagnifiedRegionCenterX() {
            return mCurrentMagnificationSpec.mMagnifiedRegionCenterX;
        }

        public float getMagnifiedRegionCenterY() {
            return mCurrentMagnificationSpec.mMagnifiedRegionCenterY;
        }

        public float getScaledOffsetX() {
            return mCurrentMagnificationSpec.mScaledOffsetX;
        }

        public float getScaledOffsetY() {
            return mCurrentMagnificationSpec.mScaledOffsetY;
        }

        public void setScale(float scale, float pivotX, float pivotY, boolean animate) {
            MagnificationSpec spec = mCurrentMagnificationSpec;
            final float oldScale = spec.mScale;
            final float oldCenterX = spec.mMagnifiedRegionCenterX;
            final float oldCenterY = spec.mMagnifiedRegionCenterY;
            final float normPivotX = (-spec.mScaledOffsetX + pivotX) / oldScale;
            final float normPivotY = (-spec.mScaledOffsetY + pivotY) / oldScale;
            final float offsetX = (oldCenterX - normPivotX) * (oldScale / scale);
            final float offsetY = (oldCenterY - normPivotY) * (oldScale / scale);
            final float centerX = normPivotX + offsetX;
            final float centerY = normPivotY + offsetY;
            setScaleAndMagnifiedRegionCenter(scale, centerX, centerY, animate);
        }

        public void setMagnifiedRegionCenter(float centerX, float centerY, boolean animate) {
            setScaleAndMagnifiedRegionCenter(mCurrentMagnificationSpec.mScale, centerX, centerY,
                    animate);
        }

        public void setScaleAndMagnifiedRegionCenter(float scale, float centerX, float centerY,
                boolean animate) {
            if (Float.compare(mCurrentMagnificationSpec.mScale, scale) == 0
                    && Float.compare(mCurrentMagnificationSpec.mMagnifiedRegionCenterX,
                            centerX) == 0
                    && Float.compare(mCurrentMagnificationSpec.mMagnifiedRegionCenterY,
                            centerY) == 0) {
                return;
            }
            if (mTransformationAnimator.isRunning()) {
                mTransformationAnimator.cancel();
            }
            if (DEBUG_MAGNIFICATION_CONTROLLER) {
                Slog.i(LOG_TAG, "scale: " + scale + " centerX: " + centerX
                        + " centerY: " + centerY);
            }
            mCurrentMagnificationSpec.initialize(scale, centerX, centerY);
            if (animate) {
                animateAccessibilityTranformation(mSentMagnificationSpec,
                        mCurrentMagnificationSpec);
            } else {
                setAccessibilityTransformation(mCurrentMagnificationSpec);
            }
        }

        private void animateAccessibilityTranformation(MagnificationSpec fromSpec,
                MagnificationSpec toSpec) {
            mTransformationAnimator.setObjectValues(fromSpec, toSpec);
            mTransformationAnimator.start();
        }

        @SuppressWarnings("unused")
        // Called from an animator.
        public MagnificationSpec getAccessibilityTransformation() {
            return mSentMagnificationSpec;
        }

        public void setAccessibilityTransformation(MagnificationSpec transformation) {
            if (DEBUG_TRANSFORMATION) {
                Slog.i(LOG_TAG, "Transformation scale: " + transformation.mScale
                        + " offsetX: " + transformation.mScaledOffsetX
                        + " offsetY: " + transformation.mScaledOffsetY);
            }
            try {
                mSentMagnificationSpec.updateFrom(transformation);
                mWindowManagerService.magnifyDisplay(mDisplayProvider.getDisplay().getDisplayId(),
                        transformation.mScale, transformation.mScaledOffsetX,
                        transformation.mScaledOffsetY);
            } catch (RemoteException re) {
                /* ignore */
            }
        }

        private class MagnificationSpec {

            private static final float DEFAULT_SCALE = 1.0f;

            public float mScale = DEFAULT_SCALE;

            public float mMagnifiedRegionCenterX;

            public float mMagnifiedRegionCenterY;

            public float mScaledOffsetX;

            public float mScaledOffsetY;

            public void initialize(float scale, float magnifiedRegionCenterX,
                    float magnifiedRegionCenterY) {
                mScale = scale;

                final int viewportWidth = mViewport.getBounds().width();
                final int viewportHeight = mViewport.getBounds().height();
                final float minMagnifiedRegionCenterX = (viewportWidth / 2) / scale;
                final float minMagnifiedRegionCenterY = (viewportHeight / 2) / scale;
                final float maxMagnifiedRegionCenterX = viewportWidth - minMagnifiedRegionCenterX;
                final float maxMagnifiedRegionCenterY = viewportHeight - minMagnifiedRegionCenterY;

                mMagnifiedRegionCenterX = Math.min(Math.max(magnifiedRegionCenterX,
                        minMagnifiedRegionCenterX), maxMagnifiedRegionCenterX);
                mMagnifiedRegionCenterY = Math.min(Math.max(magnifiedRegionCenterY,
                        minMagnifiedRegionCenterY), maxMagnifiedRegionCenterY);

                mScaledOffsetX = -(mMagnifiedRegionCenterX * scale - viewportWidth / 2);
                mScaledOffsetY = -(mMagnifiedRegionCenterY * scale - viewportHeight / 2);
            }

            public void updateFrom(MagnificationSpec other) {
                mScale = other.mScale;
                mMagnifiedRegionCenterX = other.mMagnifiedRegionCenterX;
                mMagnifiedRegionCenterY = other.mMagnifiedRegionCenterY;
                mScaledOffsetX = other.mScaledOffsetX;
                mScaledOffsetY = other.mScaledOffsetY;
            }

            public void reset() {
                mScale = DEFAULT_SCALE;
                mMagnifiedRegionCenterX = 0;
                mMagnifiedRegionCenterY = 0;
                mScaledOffsetX = 0;
                mScaledOffsetY = 0;
            }
        }
    }

    private static final class Viewport {

        private static final String PROPERTY_NAME_ALPHA = "alpha";

        private static final String PROPERTY_NAME_BOUNDS = "bounds";

        private static final int MIN_ALPHA = 0;

        private static final int MAX_ALPHA = 255;

        private final ArrayList<WindowInfo> mTempWindowInfoList = new ArrayList<WindowInfo>();

        private final Rect mTempRect = new Rect();

        private final IWindowManager mWindowManagerService;
        private final DisplayProvider mDisplayProvider;

        private final ViewportWindow mViewportFrame;

        private final ValueAnimator mResizeFrameAnimator;

        private final ValueAnimator mShowHideFrameAnimator;

        public Viewport(Context context, WindowManager windowManager,
                IWindowManager windowManagerService, DisplayProvider displayInfoProvider,
                Interpolator animationInterpolator, long animationDuration) {
            mWindowManagerService = windowManagerService;
            mDisplayProvider = displayInfoProvider;
            mViewportFrame = new ViewportWindow(context, windowManager, displayInfoProvider);

            mShowHideFrameAnimator = ObjectAnimator.ofInt(mViewportFrame, PROPERTY_NAME_ALPHA,
                  MIN_ALPHA, MAX_ALPHA);
            mShowHideFrameAnimator.setInterpolator(animationInterpolator);
            mShowHideFrameAnimator.setDuration(animationDuration);
            mShowHideFrameAnimator.addListener(new AnimatorListener() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mShowHideFrameAnimator.getAnimatedValue().equals(MIN_ALPHA)) {
                        mViewportFrame.hide();
                    }
                }
                @Override
                public void onAnimationStart(Animator animation) {
                    /* do nothing - stub */
                }
                @Override
                public void onAnimationCancel(Animator animation) {
                    /* do nothing - stub */
                }
                @Override
                public void onAnimationRepeat(Animator animation) {
                    /* do nothing - stub */
                }
            });

            Property<ViewportWindow, Rect> property = Property.of(ViewportWindow.class,
                    Rect.class, PROPERTY_NAME_BOUNDS);
            TypeEvaluator<Rect> evaluator = new TypeEvaluator<Rect>() {
                private final Rect mReusableResultRect = new Rect();
                @Override
                public Rect evaluate(float fraction, Rect fromFrame, Rect toFrame) {
                    Rect result = mReusableResultRect;
                    result.left = (int) (fromFrame.left
                            + (toFrame.left - fromFrame.left) * fraction);
                    result.top = (int) (fromFrame.top
                            + (toFrame.top - fromFrame.top) * fraction);
                    result.right = (int) (fromFrame.right
                            + (toFrame.right - fromFrame.right) * fraction);
                    result.bottom = (int) (fromFrame.bottom
                            + (toFrame.bottom - fromFrame.bottom) * fraction);
                    return result;
                }
            };
            mResizeFrameAnimator = ObjectAnimator.ofObject(mViewportFrame, property,
                    evaluator, mViewportFrame.mBounds, mViewportFrame.mBounds);
            mResizeFrameAnimator.setDuration((long) (animationDuration));
            mResizeFrameAnimator.setInterpolator(animationInterpolator);

            recomputeBounds(false);
        }

        public void recomputeBounds(boolean animate) {
            Rect frame = mTempRect;
            frame.set(0, 0, mDisplayProvider.getDisplayInfo().logicalWidth,
                    mDisplayProvider.getDisplayInfo().logicalHeight);
            ArrayList<WindowInfo> infos = mTempWindowInfoList;
            infos.clear();
            try {
                mWindowManagerService.getVisibleWindowsForDisplay(
                        mDisplayProvider.getDisplay().getDisplayId(), infos);
                final int windowCount = infos.size();
                for (int i = 0; i < windowCount; i++) {
                    WindowInfo info = infos.get(i);
                    if (info.type == WindowManager.LayoutParams.TYPE_NAVIGATION_BAR
                            || info.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD
                            || info.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG) {
                        subtract(frame, info.touchableRegion);
                    }
                    info.recycle();
                }
            } catch (RemoteException re) {
                /* ignore */
            } finally {
                infos.clear();
            }
            resize(frame, animate);
        }

        public void rotationChanged() {
            mViewportFrame.rotationChanged();
        }

        public Rect getBounds() {
            return mViewportFrame.getBounds();
        }

        public void setFrameShown(boolean shown, boolean animate) {
            if (mViewportFrame.isShown() == shown) {
                return;
            }
            if (animate) {
                if (mShowHideFrameAnimator.isRunning()) {
                    mShowHideFrameAnimator.reverse();
                } else {
                    if (shown) {
                        mViewportFrame.show();
                        mShowHideFrameAnimator.start();
                    } else {
                        mShowHideFrameAnimator.reverse();
                    }
                }
            } else {
                mShowHideFrameAnimator.cancel();
                if (shown) {
                    mViewportFrame.show();
                } else {
                    mViewportFrame.hide();
                }
            }
        }

        private void resize(Rect bounds, boolean animate) {
            if (mViewportFrame.getBounds().equals(bounds)) {
                return;
            }
            if (animate) {
                if (mResizeFrameAnimator.isRunning()) {
                    mResizeFrameAnimator.cancel();
                }
                mResizeFrameAnimator.setObjectValues(mViewportFrame.mBounds, bounds);
                mResizeFrameAnimator.start();
            } else {
                mViewportFrame.setBounds(bounds);
            }
        }

        private boolean subtract(Rect lhs, Rect rhs) {
            if (lhs.right < rhs.left || lhs.left  > rhs.right
                    || lhs.bottom < rhs.top || lhs.top > rhs.bottom) {
                return false;
            }
            if (lhs.left < rhs.left) {
                lhs.right = rhs.left;
            }
            if (lhs.top < rhs.top) {
                lhs.bottom = rhs.top;
            }
            if (lhs.right > rhs.right) {
                lhs.left = rhs.right;
            }
            if (lhs.bottom > rhs.bottom) {
                lhs.top = rhs.bottom;
            }
            return true;
        }

        private static final class ViewportWindow {
            private static final String WINDOW_TITLE = "Magnification Overlay";

            private final WindowManager mWindowManager;
            private final DisplayProvider mDisplayProvider;

            private final ContentView mWindowContent;
            private final WindowManager.LayoutParams mWindowParams;

            private final Rect mBounds = new Rect();
            private boolean mShown;
            private int mAlpha;

            public ViewportWindow(Context context, WindowManager windowManager,
                    DisplayProvider displayProvider) {
                mWindowManager = windowManager;
                mDisplayProvider = displayProvider;

                ViewGroup.LayoutParams contentParams = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                mWindowContent = new ContentView(context);
                mWindowContent.setLayoutParams(contentParams);
                mWindowContent.setBackgroundColor(R.color.transparent);

                mWindowParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY);
                mWindowParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                mWindowParams.setTitle(WINDOW_TITLE);
                mWindowParams.gravity = Gravity.CENTER;
                mWindowParams.width = displayProvider.getDisplayInfo().logicalWidth;
                mWindowParams.height = displayProvider.getDisplayInfo().logicalHeight;
                mWindowParams.format = PixelFormat.TRANSLUCENT;
            }

            public boolean isShown() {
                return mShown;
            }

            public void show() {
                if (mShown) {
                    return;
                }
                mShown = true;
                mWindowManager.addView(mWindowContent, mWindowParams);
                if (DEBUG_VIEWPORT_WINDOW) {
                    Slog.i(LOG_TAG, "ViewportWindow shown.");
                }
            }

            public void hide() {
                if (!mShown) {
                    return;
                }
                mShown = false;
                mWindowManager.removeView(mWindowContent);
                if (DEBUG_VIEWPORT_WINDOW) {
                    Slog.i(LOG_TAG, "ViewportWindow hidden.");
                }
            }

            @SuppressWarnings("unused")
            // Called reflectively from an animator.
            public int getAlpha() {
                return mAlpha;
            }

            @SuppressWarnings("unused")
            // Called reflectively from an animator.
            public void setAlpha(int alpha) {
                if (mAlpha == alpha) {
                    return;
                }
                mAlpha = alpha;
                if (mShown) {
                    mWindowContent.invalidate();
                }
                if (DEBUG_VIEWPORT_WINDOW) {
                    Slog.i(LOG_TAG, "ViewportFrame set alpha: " + alpha);
                }
            }

            public Rect getBounds() {
                return mBounds;
            }

            public void rotationChanged() {
                mWindowParams.width = mDisplayProvider.getDisplayInfo().logicalWidth;
                mWindowParams.height = mDisplayProvider.getDisplayInfo().logicalHeight;
                if (mShown) {
                    mWindowManager.updateViewLayout(mWindowContent, mWindowParams);
                }
            }

            public void setBounds(Rect bounds) {
                if (mBounds.equals(bounds)) {
                    return;
                }
                mBounds.set(bounds);
                if (mShown) {
                    mWindowContent.invalidate();
                }
                if (DEBUG_VIEWPORT_WINDOW) {
                    Slog.i(LOG_TAG, "ViewportFrame set bounds: " + bounds);
                }
            }

            private final class ContentView extends View {
                private final Drawable mHighlightFrame;

                public ContentView(Context context) {
                    super(context);
                    mHighlightFrame = context.getResources().getDrawable(
                            R.drawable.magnified_region_frame);
                }

                @Override
                public void onDraw(Canvas canvas) {
                    canvas.drawColor(Color.TRANSPARENT, Mode.CLEAR);
                    mHighlightFrame.setBounds(mBounds);
                    mHighlightFrame.setAlpha(mAlpha);
                    mHighlightFrame.draw(canvas);
                }
            }
        }
    }

    private static class DisplayProvider implements DisplayListener {
        private final WindowManager mWindowManager;
        private final DisplayManager mDisplayManager;
        private final Display mDefaultDisplay;
        private final DisplayInfo mDefaultDisplayInfo = new DisplayInfo();

        public DisplayProvider(Context context, WindowManager windowManager) {
            mWindowManager = windowManager;
            mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            mDefaultDisplay = mWindowManager.getDefaultDisplay();
            mDisplayManager.registerDisplayListener(this, null);
            updateDisplayInfo();
        }

        public DisplayInfo getDisplayInfo() {
            return mDefaultDisplayInfo;
        }

        public Display getDisplay() {
            return mDefaultDisplay;
        }

        private void updateDisplayInfo() {
            if (!mDefaultDisplay.getDisplayInfo(mDefaultDisplayInfo)) {
                Slog.e(LOG_TAG, "Default display is not valid.");
            }
        }

        public void destroy() {
            mDisplayManager.unregisterDisplayListener(this);
        }

        @Override
        public void onDisplayAdded(int displayId) {
            /* do noting */
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            // Having no default display
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updateDisplayInfo();
        }
    }

    /**
     * The listener for receiving notifications when gestures occur.
     * If you want to listen for all the different gestures then implement
     * this interface. If you only want to listen for a subset it might
     * be easier to extend {@link SimpleOnScaleGestureListener}.
     *
     * An application will receive events in the following order:
     * <ul>
     *  <li>One {@link OnScaleGestureListener#onScaleBegin(ScaleGestureDetector)}
     *  <li>Zero or more {@link OnScaleGestureListener#onScale(ScaleGestureDetector)}
     *  <li>One {@link OnScaleGestureListener#onScaleEnd(ScaleGestureDetector)}
     * </ul>
     */
    interface OnScaleGestureListener {
        /**
         * Responds to scaling events for a gesture in progress.
         * Reported by pointer motion.
         *
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         * @return Whether or not the detector should consider this event
         *          as handled. If an event was not handled, the detector
         *          will continue to accumulate movement until an event is
         *          handled. This can be useful if an application, for example,
         *          only wants to update scaling factors if the change is
         *          greater than 0.01.
         */
        public boolean onScale(ScaleGestureDetector detector);

        /**
         * Responds to the beginning of a scaling gesture. Reported by
         * new pointers going down.
         *
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         * @return Whether or not the detector should continue recognizing
         *          this gesture. For example, if a gesture is beginning
         *          with a focal point outside of a region where it makes
         *          sense, onScaleBegin() may return false to ignore the
         *          rest of the gesture.
         */
        public boolean onScaleBegin(ScaleGestureDetector detector);

        /**
         * Responds to the end of a scale gesture. Reported by existing
         * pointers going up.
         *
         * Once a scale has ended, {@link ScaleGestureDetector#getFocusX()}
         * and {@link ScaleGestureDetector#getFocusY()} will return the location
         * of the pointer remaining on the screen.
         *
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         */
        public void onScaleEnd(ScaleGestureDetector detector);
    }

    class ScaleGestureDetector {

        private final MinCircleFinder mMinCircleFinder = new MinCircleFinder();

        private final OnScaleGestureListener mListener;

        private float mFocusX;
        private float mFocusY;

        private float mCurrSpan;
        private float mPrevSpan;
        private float mCurrSpanX;
        private float mCurrSpanY;
        private float mPrevSpanX;
        private float mPrevSpanY;
        private long mCurrTime;
        private long mPrevTime;
        private boolean mInProgress;

        public ScaleGestureDetector(OnScaleGestureListener listener) {
            mListener = listener;
        }

        /**
         * Accepts MotionEvents and dispatches events to a {@link OnScaleGestureListener}
         * when appropriate.
         *
         * <p>Applications should pass a complete and consistent event stream to this method.
         * A complete and consistent event stream involves all MotionEvents from the initial
         * ACTION_DOWN to the final ACTION_UP or ACTION_CANCEL.</p>
         *
         * @param event The event to process
         * @return true if the event was processed and the detector wants to receive the
         *         rest of the MotionEvents in this event stream.
         */
        public boolean onTouchEvent(MotionEvent event) {
            boolean streamEnded = false;
            boolean contextChanged = false;
            int excludedPtrIdx = -1;
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    contextChanged = true;
                } break;
                case MotionEvent.ACTION_POINTER_UP: {
                    contextChanged = true;
                    excludedPtrIdx = event.getActionIndex();
                } break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    streamEnded = true;
                } break;
            }

            if (mInProgress && (contextChanged || streamEnded)) {
                mListener.onScaleEnd(this);
                mInProgress = false;
                mPrevSpan = 0;
                mPrevSpanX = 0;
                mPrevSpanY = 0;
                return true;
            }

            final long currTime = mCurrTime;

            mFocusX = 0;
            mFocusY = 0;
            mCurrSpan = 0;
            mCurrSpanX = 0;
            mCurrSpanY = 0;
            mCurrTime = 0;
            mPrevTime = 0;

            if (!streamEnded) {
                MinCircleFinder.Circle circle =
                        mMinCircleFinder.computeMinCircleAroundPointers(event);
                mFocusX = circle.centerX;
                mFocusY = circle.centerY;

                double sumSlope = 0;
                final int pointerCount = event.getPointerCount();
                for (int i = 0; i < pointerCount; i++) {
                    if (i == excludedPtrIdx) {
                        continue;
                    }
                    float x = event.getX(i) - mFocusX;
                    float y = event.getY(i) - mFocusY;
                    if (x == 0) {
                        x += 0.1f;
                    }
                    sumSlope += y / x;
                }
                final double avgSlope = sumSlope
                        / ((excludedPtrIdx < 0) ? pointerCount : pointerCount - 1);

                double angle = Math.atan(avgSlope);
                mCurrSpan = 2 * circle.radius;
                mCurrSpanX = (float) Math.abs((Math.cos(angle) * mCurrSpan));
                mCurrSpanY = (float) Math.abs((Math.sin(angle) * mCurrSpan));
            }

            if (contextChanged || mPrevSpan == 0 || mPrevSpanX == 0 || mPrevSpanY == 0) {
                mPrevSpan = mCurrSpan;
                mPrevSpanX = mCurrSpanX;
                mPrevSpanY = mCurrSpanY;
            }

            if (!mInProgress && mCurrSpan != 0 && !streamEnded) {
                mInProgress = mListener.onScaleBegin(this);
            }

            if (mInProgress) {
                mPrevTime = (currTime != 0) ? currTime : event.getEventTime();
                mCurrTime = event.getEventTime();
                if (mCurrSpan == 0) {
                    mListener.onScaleEnd(this);
                    mInProgress = false;
                } else {
                    if (mListener.onScale(this)) {
                        mPrevSpanX = mCurrSpanX;
                        mPrevSpanY = mCurrSpanY;
                        mPrevSpan = mCurrSpan;
                    }
                }
            }

            return true;
        }

        /**
         * Returns {@code true} if a scale gesture is in progress.
         */
        public boolean isInProgress() {
            return mInProgress;
        }

        /**
         * Get the X coordinate of the current gesture's focal point.
         * If a gesture is in progress, the focal point is between
         * each of the pointers forming the gesture.
         *
         * If {@link #isInProgress()} would return false, the result of this
         * function is undefined.
         *
         * @return X coordinate of the focal point in pixels.
         */
        public float getFocusX() {
            return mFocusX;
        }

        /**
         * Get the Y coordinate of the current gesture's focal point.
         * If a gesture is in progress, the focal point is between
         * each of the pointers forming the gesture.
         *
         * If {@link #isInProgress()} would return false, the result of this
         * function is undefined.
         *
         * @return Y coordinate of the focal point in pixels.
         */
        public float getFocusY() {
            return mFocusY;
        }

        /**
         * Return the average distance between each of the pointers forming the
         * gesture in progress through the focal point.
         *
         * @return Distance between pointers in pixels.
         */
        public float getCurrentSpan() {
            return mCurrSpan;
        }

        /**
         * Return the average X distance between each of the pointers forming the
         * gesture in progress through the focal point.
         *
         * @return Distance between pointers in pixels.
         */
        public float getCurrentSpanX() {
            return mCurrSpanX;
        }

        /**
         * Return the average Y distance between each of the pointers forming the
         * gesture in progress through the focal point.
         *
         * @return Distance between pointers in pixels.
         */
        public float getCurrentSpanY() {
            return mCurrSpanY;
        }

        /**
         * Return the previous average distance between each of the pointers forming the
         * gesture in progress through the focal point.
         *
         * @return Previous distance between pointers in pixels.
         */
        public float getPreviousSpan() {
            return mPrevSpan;
        }

        /**
         * Return the previous average X distance between each of the pointers forming the
         * gesture in progress through the focal point.
         *
         * @return Previous distance between pointers in pixels.
         */
        public float getPreviousSpanX() {
            return mPrevSpanX;
        }

        /**
         * Return the previous average Y distance between each of the pointers forming the
         * gesture in progress through the focal point.
         *
         * @return Previous distance between pointers in pixels.
         */
        public float getPreviousSpanY() {
            return mPrevSpanY;
        }

        /**
         * Return the scaling factor from the previous scale event to the current
         * event. This value is defined as
         * ({@link #getCurrentSpan()} / {@link #getPreviousSpan()}).
         *
         * @return The current scaling factor.
         */
        public float getScaleFactor() {
            return mPrevSpan > 0 ? mCurrSpan / mPrevSpan : 1;
        }

        /**
         * Return the time difference in milliseconds between the previous
         * accepted scaling event and the current scaling event.
         *
         * @return Time difference since the last scaling event in milliseconds.
         */
        public long getTimeDelta() {
            return mCurrTime - mPrevTime;
        }

        /**
         * Return the event time of the current event being processed.
         *
         * @return Current event time in milliseconds.
         */
        public long getEventTime() {
            return mCurrTime;
        }
    }

    private static final class MinCircleFinder {
        private final ArrayList<PointHolder> mPoints = new ArrayList<PointHolder>();
        private final ArrayList<PointHolder> sBoundary = new ArrayList<PointHolder>();
        private final Circle mMinCircle = new Circle();

        /**
         * Finds the minimal circle that contains all pointers of a motion event.
         *
         * @param event A motion event.
         * @return The minimal circle.
         */
        public Circle computeMinCircleAroundPointers(MotionEvent event) {
            ArrayList<PointHolder> points = mPoints;
            points.clear();
            final int pointerCount = event.getPointerCount();
            for (int i = 0; i < pointerCount; i++) {
                PointHolder point = PointHolder.obtain(event.getX(i), event.getY(i));
                points.add(point);
            }
            ArrayList<PointHolder> boundary = sBoundary;
            boundary.clear();
            computeMinCircleAroundPointsRecursive(points, boundary, mMinCircle);
            for (int i = points.size() - 1; i >= 0; i--) {
                points.remove(i).recycle();
            }
            boundary.clear();
            return mMinCircle;
        }

        private static void computeMinCircleAroundPointsRecursive(ArrayList<PointHolder> points,
                ArrayList<PointHolder> boundary, Circle outCircle) {
            if (points.isEmpty()) {
                if (boundary.size() == 0) {
                    outCircle.initialize();
                } else if (boundary.size() == 1) {
                    outCircle.initialize(boundary.get(0).mData, boundary.get(0).mData);
                } else if (boundary.size() == 2) {
                    outCircle.initialize(boundary.get(0).mData, boundary.get(1).mData);
                } else if (boundary.size() == 3) {
                    outCircle.initialize(boundary.get(0).mData, boundary.get(1).mData,
                            boundary.get(2).mData);
                }
                return;
            }
            PointHolder point = points.remove(points.size() - 1);
            computeMinCircleAroundPointsRecursive(points, boundary, outCircle);
            if (!outCircle.contains(point.mData)) {
                boundary.add(point);
                computeMinCircleAroundPointsRecursive(points, boundary, outCircle);
                boundary.remove(point);
            }
            points.add(point);
        }

        private static final class PointHolder {
            private static final int MAX_POOL_SIZE = 20;
            private static PointHolder sPool;
            private static int sPoolSize;

            private PointHolder mNext;
            private boolean mIsInPool;

            private final PointF mData = new PointF();

            public static PointHolder obtain(float x, float y) {
                PointHolder holder;
                if (sPoolSize > 0) {
                    sPoolSize--;
                    holder = sPool;
                    sPool = sPool.mNext;
                    holder.mNext = null;
                    holder.mIsInPool = false;
                } else {
                    holder = new PointHolder();
                }
                holder.mData.set(x, y);
                return holder;
            }

            public void recycle() {
                if (mIsInPool) {
                    throw new IllegalStateException("Already recycled.");
                }
                clear();
                if (sPoolSize < MAX_POOL_SIZE) {
                    sPoolSize++;
                    mNext = sPool;
                    sPool = this;
                    mIsInPool = true;
                }
            }

            private void clear() {
                mData.set(0, 0);
            }
        }

        public static final class Circle {
            public float centerX;
            public float centerY;
            public float radius;

            private void initialize() {
                centerX = 0;
                centerY = 0;
                radius = 0;
            }

            private void initialize(PointF first, PointF second, PointF third) {
                if (!hasLineWithInfiniteSlope(first, second, third)) {
                    initializeInternal(first, second, third);
                } else if (!hasLineWithInfiniteSlope(first, third, second)) {
                    initializeInternal(first, third, second);
                } else if (!hasLineWithInfiniteSlope(second, first, third)) {
                    initializeInternal(second, first, third);
                } else if (!hasLineWithInfiniteSlope(second, third, first)) {
                    initializeInternal(second, third, first);
                } else if (!hasLineWithInfiniteSlope(third, first, second)) {
                    initializeInternal(third, first, second);
                } else if (!hasLineWithInfiniteSlope(third, second, first)) {
                    initializeInternal(third, second, first);
                } else {
                    initialize();
                }
            }

            private void initialize(PointF first, PointF second) {
                radius = (float) (Math.hypot(second.x - first.x, second.y - first.y) / 2);
                centerX = (float) (second.x + first.x) / 2;
                centerY = (float) (second.y + first.y) / 2;
            }

            public boolean contains(PointF point) {
                return (int) (Math.hypot(point.x - centerX, point.y - centerY)) <= radius;
            }

            private void initializeInternal(PointF first, PointF second, PointF third) {
                final float x1 = first.x;
                final float y1 = first.y;
                final float x2 = second.x;
                final float y2 = second.y;
                final float x3 = third.x;
                final float y3 = third.y;

                final float sl1 = (y2 - y1) / (x2 - x1);
                final float sl2 = (y3 - y2) / (x3 - x2);

                centerX = (int) ((sl1 * sl2 * (y1 - y3) + sl2 * (x1 + x2) - sl1 * (x2 + x3))
                        / (2 * (sl2 - sl1)));
                centerY = (int) (-1 / sl1 * (centerX - (x1 + x2) / 2) + (y1 + y2) / 2);
                radius = (int) Math.hypot(x1 - centerX, y1 - centerY);
            }

            private boolean hasLineWithInfiniteSlope(PointF first, PointF second, PointF third) {
                return (second.x - first.x == 0 || third.x - second.x == 0
                        || second.y - first.y == 0 || third.y - second.y == 0);
            }

            @Override
            public String toString() {
                return "cetner: [" + centerX + ", " + centerY + "] radius: " + radius;
            }
        }
    }
}
