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

package android.view;

import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;

/**
 * This class creates trackball events from touchpad events.
 * 
 * @see ViewRootImpl
 */
class SimulatedTrackball {

    // Maximum difference in milliseconds between the down and up of a touch
    // event for it to be considered a tap
    // TODO:Read this value from a configuration file
    private static final int MAX_TAP_TIME = 250;
    private static final int FLICK_MSG_ID = 313;

    // The position of the previous touchpad event
    private float mLastTouchpadXPosition;
    private float mLastTouchpadYPosition;
    // Where the touchpad was initially pressed
    private float mTouchpadEnterXPosition;
    private float mTouchpadEnterYPosition;
    // When the most recent ACTION_HOVER_ENTER occurred
    private long mLastTouchPadStartTimeMs = 0;
    // When the most recent direction key was sent
    private long mLastTouchPadKeySendTimeMs = 0;
    // When the most recent touch event of any type occurred
    private long mLastTouchPadEventTimeMs = 0;

    // How quickly keys were sent;
    private int mKeySendRateMs = 0;
    private int mLastKeySent;
    // Last movement in device screen pixels
    private float mLastMoveX = 0;
    private float mLastMoveY = 0;
    // Offset from the initial touch. Gets reset as direction keys are sent.
    private float mAccumulatedX;
    private float mAccumulatedY;

    // Change in position allowed during tap events
    private float mTouchSlop;
    private float mTouchSlopSquared;
    // Has the TouchSlop constraint been invalidated
    private boolean mAlwaysInTapRegion = true;

    // Most recent event. Used to determine what device sent the event.
    private MotionEvent mRecentEvent;

    // TODO: Currently using screen dimensions tuned to a Galaxy Nexus, need to
    // read this from a config file instead
    private int mDistancePerTick;
    private int mDistancePerTickSquared;
    // Highest rate that the flinged events can occur at before dying out
    private int mMaxRepeatDelay;
    // The square of the minimum distance needed for a flick to register
    private int mMinFlickDistanceSquared;
    // How quickly the repeated events die off
    private float mFlickDecay;

    public SimulatedTrackball() {
        mDistancePerTick = SystemProperties.getInt("persist.vr_dist_tick", 64);
        mDistancePerTickSquared = mDistancePerTick * mDistancePerTick;
        mMaxRepeatDelay = SystemProperties.getInt("persist.vr_repeat_delay", 300);
        mMinFlickDistanceSquared = SystemProperties.getInt("persist.vr_min_flick", 20);
        mMinFlickDistanceSquared *= mMinFlickDistanceSquared;
        mFlickDecay = Float.parseFloat(SystemProperties.get(
                "persist.sys.vr_flick_decay", "1.3"));
        mTouchSlop = ViewConfiguration.getTouchSlop();
        mTouchSlopSquared = mTouchSlop * mTouchSlop;
    }

    private final Handler mHandler = new Handler(new Callback() {
            @Override
        public boolean handleMessage(Message msg) {
            if (msg.what != FLICK_MSG_ID)
                return false;

            final long time = SystemClock.uptimeMillis();
            ViewRootImpl viewroot = (ViewRootImpl) msg.obj;
            // Send the key
            viewroot.enqueueInputEvent(new KeyEvent(time, time,
                    KeyEvent.ACTION_DOWN, msg.arg2, 0, mRecentEvent.getMetaState(),
                    mRecentEvent.getDeviceId(), 0,
                    KeyEvent.FLAG_FALLBACK, mRecentEvent.getSource()));
            viewroot.enqueueInputEvent(new KeyEvent(time, time,
                    KeyEvent.ACTION_UP, msg.arg2, 0, mRecentEvent.getMetaState(),
                    mRecentEvent.getDeviceId(), 0,
                    KeyEvent.FLAG_FALLBACK, mRecentEvent.getSource()));
            Message msgCopy = Message.obtain(msg);
            // Increase the delay by the decay factor
            msgCopy.arg1 = (int) Math.ceil(mFlickDecay * msgCopy.arg1);
            if (msgCopy.arg1 <= mMaxRepeatDelay) {
                // Send the key again in arg1 milliseconds
                mHandler.sendMessageDelayed(msgCopy, msgCopy.arg1);
            }
            return false;
        }
    });

    public void updateTrackballDirection(ViewRootImpl viewroot, MotionEvent event) {
        // Store what time the touchpad event occurred
        final long time = SystemClock.uptimeMillis();
        switch (event.getAction()) {
            case MotionEvent.ACTION_HOVER_ENTER:
                mLastTouchPadStartTimeMs = time;
                mAlwaysInTapRegion = true;
                mTouchpadEnterXPosition = event.getX();
                mTouchpadEnterYPosition = event.getY();
                mAccumulatedX = 0;
                mAccumulatedY = 0;
                mLastMoveX = 0;
                mLastMoveY = 0;
                // Clear any flings
                mHandler.removeMessages(0);
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                // Determine whether the move is slop or an intentional move
                float deltaX = event.getX() - mTouchpadEnterXPosition;
                float deltaY = event.getY() - mTouchpadEnterYPosition;
                if (mTouchSlopSquared < deltaX * deltaX + deltaY * deltaY) {
                    mAlwaysInTapRegion = false;
                }

                // Find the difference in position between the two most recent
                // touchpad events
                mLastMoveX = event.getX() - mLastTouchpadXPosition;
                mLastMoveY = event.getY() - mLastTouchpadYPosition;
                mAccumulatedX += mLastMoveX;
                mAccumulatedY += mLastMoveY;
                float mAccumulatedXSquared = mAccumulatedX * mAccumulatedX;
                float mAccumulatedYSquared = mAccumulatedY * mAccumulatedY;
                // Determine if we've moved far enough to send a key press
                if (mAccumulatedXSquared > mDistancePerTickSquared ||
                        mAccumulatedYSquared > mDistancePerTickSquared) {
                    float dominantAxis;
                    float sign;
                    boolean isXAxis;
                    int key;
                    int repeatCount = 0;
                    // Determine dominant axis
                    if (mAccumulatedXSquared > mAccumulatedYSquared) {
                        dominantAxis = mAccumulatedX;
                        isXAxis = true;
                    } else {
                        dominantAxis = mAccumulatedY;
                        isXAxis = false;
                    }
                    // Determine sign of axis
                    sign = (dominantAxis > 0) ? 1 : -1;
                    // Determine key to send
                    if (isXAxis) {
                        key = (sign == 1) ? KeyEvent.KEYCODE_DPAD_RIGHT :
                                KeyEvent.KEYCODE_DPAD_LEFT;
                    } else {
                        key = (sign == 1) ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
                    }
                    // Send key until maximum distance constraint is satisfied
                    while (dominantAxis * dominantAxis > mDistancePerTickSquared) {
                        repeatCount++;
                        dominantAxis -= sign * mDistancePerTick;
                        viewroot.enqueueInputEvent(new KeyEvent(time, time,
                                KeyEvent.ACTION_DOWN, key, 0, event.getMetaState(),
                                event.getDeviceId(), 0, KeyEvent.FLAG_FALLBACK, event.getSource()));
                        viewroot.enqueueInputEvent(new KeyEvent(time, time,
                                KeyEvent.ACTION_UP, key, 0, event.getMetaState(),
                                event.getDeviceId(), 0, KeyEvent.FLAG_FALLBACK, event.getSource()));
                    }
                    // Save new axis values
                    mAccumulatedX = isXAxis ? dominantAxis : 0;
                    mAccumulatedY = isXAxis ? 0 : dominantAxis;

                    mLastKeySent = key;
                    mKeySendRateMs = (int) ((time - mLastTouchPadKeySendTimeMs) / repeatCount);
                    mLastTouchPadKeySendTimeMs = time;
                }
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (time - mLastTouchPadStartTimeMs < MAX_TAP_TIME && mAlwaysInTapRegion) {
                    // Trackball Down
                    MotionEvent trackballEvent = MotionEvent.obtain(mLastTouchPadStartTimeMs, time,
                            MotionEvent.ACTION_DOWN, 0, 0, 0, 0, event.getMetaState(),
                            10f, 10f, event.getDeviceId(), 0);
                    trackballEvent.setSource(InputDevice.SOURCE_CLASS_TRACKBALL);
                    viewroot.enqueueInputEvent(trackballEvent);
                    // Trackball Release
                    trackballEvent = MotionEvent.obtain(mLastTouchPadStartTimeMs, time,
                            MotionEvent.ACTION_UP, 0, 0, 0, 0, event.getMetaState(),
                            10f, 10f, event.getDeviceId(), 0);
                    trackballEvent.setSource(InputDevice.SOURCE_CLASS_TRACKBALL);
                    viewroot.enqueueInputEvent(trackballEvent);
                } else {
                    float xMoveSquared = mLastMoveX * mLastMoveX;
                    float yMoveSquared = mLastMoveY * mLastMoveY;
                    // Determine whether the last gesture was a fling.
                    if (mMinFlickDistanceSquared <= xMoveSquared + yMoveSquared &&
                            time - mLastTouchPadEventTimeMs <= MAX_TAP_TIME &&
                            mKeySendRateMs <= mMaxRepeatDelay && mKeySendRateMs > 0) {
                        Message message = Message.obtain(mHandler, FLICK_MSG_ID,
                                mKeySendRateMs, mLastKeySent, viewroot);
                        mRecentEvent = event;
                        mHandler.sendMessageDelayed(message, mKeySendRateMs);
                    }
                }
                break;
        }
        // Store touch event position and time
        mLastTouchPadEventTimeMs = time;
        mLastTouchpadXPosition = event.getX();
        mLastTouchpadYPosition = event.getY();
    }
}
