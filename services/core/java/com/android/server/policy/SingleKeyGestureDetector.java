/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.policy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Detect single key gesture: press, long press, very long press and multi press.
 *
 * Call {@link #reset} if current {@link KeyEvent} has been handled by another policy
 */

public final class SingleKeyGestureDetector {
    private static final String TAG = "SingleKeyGesture";
    private static final boolean DEBUG = PhoneWindowManager.DEBUG_INPUT;

    private static final int MSG_KEY_LONG_PRESS = 0;
    private static final int MSG_KEY_VERY_LONG_PRESS = 1;
    private static final int MSG_KEY_DELAYED_PRESS = 2;
    private static final int MSG_KEY_UP = 3;

    private int mKeyPressCounter;
    private boolean mBeganFromNonInteractive = false;
    private boolean mBeganFromDefaultDisplayOn = false;

    private final ArrayList<SingleKeyRule> mRules = new ArrayList();
    private SingleKeyRule mActiveRule = null;

    // Key code of current key down event, reset when key up.
    private int mDownKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private boolean mHandledByLongPress = false;
    private final Handler mHandler;
    private long mLastDownTime = 0;

    static final long MULTI_PRESS_TIMEOUT = ViewConfiguration.getMultiPressTimeout();
    static long sDefaultLongPressTimeout;
    static long sDefaultVeryLongPressTimeout;

    /**
     *  Rule definition for single keys gesture.
     *  E.g : define power key.
     *  <pre class="prettyprint">
     *  SingleKeyRule rule =
     *      new SingleKeyRule(KEYCODE_POWER, KEY_LONGPRESS|KEY_VERYLONGPRESS) {
     *           int getMaxMultiPressCount() { // maximum multi press count. }
     *           void onPress(long downTime, int displayId) { // short press behavior. }
     *           void onLongPress(long eventTime) { // long press behavior. }
     *           void onVeryLongPress(long eventTime) { // very long press behavior. }
     *           void onMultiPress(long downTime, int count, int displayId) {
     *               // multi press behavior.
     *           }
     *       };
     *  </pre>
     */
    abstract static class SingleKeyRule {
        private final int mKeyCode;

        SingleKeyRule(int keyCode) {
            mKeyCode = keyCode;
        }

        /**
         *  True if the rule could intercept the key.
         */
        private boolean shouldInterceptKey(int keyCode) {
            return keyCode == mKeyCode;
        }

        /**
         *  True if the rule support long press.
         */
        boolean supportLongPress() {
            return false;
        }

        /**
         *  True if the rule support very long press.
         */
        boolean supportVeryLongPress() {
            return false;
        }

        /**
         *  Maximum count of multi presses.
         *  Return 1 will trigger onPress immediately when {@link KeyEvent#ACTION_UP}.
         *  Otherwise trigger onMultiPress immediately when reach max count when
         *  {@link KeyEvent#ACTION_DOWN}.
         */
        int getMaxMultiPressCount() {
            return 1;
        }

        /**
         *  Called when short press has been detected.
         */
        abstract void onPress(long downTime, int displayId);
        /**
         *  Callback when multi press (>= 2) has been detected.
         */
        void onMultiPress(long downTime, int count, int displayId) {}
        /**
         *  Returns the timeout in milliseconds for a long press.
         *
         *  If multipress is also supported, this should always be greater than the multipress
         *  timeout. If very long press is supported, this should always be less than the very long
         *  press timeout.
         */
        long getLongPressTimeoutMs() {
            return sDefaultLongPressTimeout;
        }
        /**
         *  Callback when long press has been detected.
         */
        void onLongPress(long eventTime) {}
        /**
         *  Returns the timeout in milliseconds for a very long press.
         *
         *  If long press is supported, this should always be longer than the long press timeout.
         */
        long getVeryLongPressTimeoutMs() {
            return sDefaultVeryLongPressTimeout;
        }
        /**
         *  Callback when very long press has been detected.
         */
        void onVeryLongPress(long eventTime) {}
        /**
         * Callback executed upon each key up event that hasn't been processed by long press.
         *
         * @param eventTime  the timestamp of this event
         * @param pressCount the number of presses detected leading up to this key up event
         * @param displayId  the display ID of the event
         * @param deviceId   the ID of the input device that generated this event
         * @param metaState  the state of the modifiers when this gesture was detected
         */
        void onKeyUp(long eventTime, int pressCount, int displayId, int deviceId, int metaState) {}

        @Override
        public String toString() {
            return "KeyCode=" + KeyEvent.keyCodeToString(mKeyCode)
                    + ", LongPress=" + supportLongPress()
                    + ", VeryLongPress=" + supportVeryLongPress()
                    + ", MaxMultiPressCount=" + getMaxMultiPressCount();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof SingleKeyRule) {
                SingleKeyRule that = (SingleKeyRule) o;
                return mKeyCode == that.mKeyCode;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return mKeyCode;
        }
    }

    private record MessageObject(SingleKeyRule activeRule, int keyCode, int pressCount,
                                 int displayId, int metaState, int deviceId) {
        MessageObject(SingleKeyRule activeRule, int keyCode, int pressCount, KeyEvent event) {
            this(activeRule, keyCode, pressCount, event.getDisplayId(), event.getMetaState(),
                    event.getDeviceId());
        }
    }

    static SingleKeyGestureDetector get(Context context, Looper looper) {
        SingleKeyGestureDetector detector = new SingleKeyGestureDetector(looper);
        sDefaultLongPressTimeout = context.getResources().getInteger(
                com.android.internal.R.integer.config_globalActionsKeyTimeout);
        sDefaultVeryLongPressTimeout = context.getResources().getInteger(
                com.android.internal.R.integer.config_veryLongPressTimeout);
        return detector;
    }

    private SingleKeyGestureDetector(Looper looper) {
        mHandler = new KeyHandler(looper);
    }

    void addRule(SingleKeyRule rule) {
        if (mRules.contains(rule)) {
            throw new IllegalArgumentException("Rule : " + rule + " already exists.");
        }
        mRules.add(rule);
    }

    void removeRule(SingleKeyRule rule) {
        mRules.remove(rule);
    }

    void interceptKey(KeyEvent event, boolean interactive, boolean defaultDisplayOn) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Store the non interactive state and display on state when first down.
            if (mDownKeyCode == KeyEvent.KEYCODE_UNKNOWN || mDownKeyCode != event.getKeyCode()) {
                mBeganFromNonInteractive = !interactive;
                mBeganFromDefaultDisplayOn = defaultDisplayOn;
            }
            interceptKeyDown(event);
        } else {
            interceptKeyUp(event);
        }
    }

    private void interceptKeyDown(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        // same key down.
        if (mDownKeyCode == keyCode) {
            if (mActiveRule != null && (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0
                    && mActiveRule.supportLongPress() && !mHandledByLongPress) {
                if (DEBUG) {
                    Log.i(TAG, "Long press key " + KeyEvent.keyCodeToString(keyCode));
                }
                mHandledByLongPress = true;
                mHandler.removeMessages(MSG_KEY_LONG_PRESS);
                mHandler.removeMessages(MSG_KEY_VERY_LONG_PRESS);
                MessageObject object = new MessageObject(mActiveRule, keyCode, /* pressCount= */ 1,
                        event);
                final Message msg = mHandler.obtainMessage(MSG_KEY_LONG_PRESS, object);
                msg.setAsynchronous(true);
                mHandler.sendMessage(msg);
            }
            return;
        }

        // When a different key is pressed, stop processing gestures for the currently active key.
        if (mDownKeyCode != KeyEvent.KEYCODE_UNKNOWN
                || (mActiveRule != null && !mActiveRule.shouldInterceptKey(keyCode))) {
            if (DEBUG) {
                Log.i(TAG, "Press another key " + KeyEvent.keyCodeToString(keyCode));
            }
            reset();
        }
        mDownKeyCode = keyCode;

        // Picks a new rule, return if no rule picked.
        if (mActiveRule == null) {
            final int count = mRules.size();
            for (int index = 0; index < count; index++) {
                final SingleKeyRule rule = mRules.get(index);
                if (rule.shouldInterceptKey(keyCode)) {
                    if (DEBUG) {
                        Log.i(TAG, "Intercept key by rule " + rule);
                    }
                    mActiveRule = rule;
                    break;
                }
            }
            mLastDownTime = 0;
        }
        if (mActiveRule == null) {
            return;
        }

        final long keyDownInterval = event.getDownTime() - mLastDownTime;
        mLastDownTime = event.getDownTime();
        if (keyDownInterval >= MULTI_PRESS_TIMEOUT) {
            mKeyPressCounter = 1;
        } else {
            mKeyPressCounter++;
        }

        if (mKeyPressCounter == 1) {
            if (mActiveRule.supportLongPress()) {
                MessageObject object = new MessageObject(mActiveRule, keyCode, mKeyPressCounter,
                        event);
                final Message msg = mHandler.obtainMessage(MSG_KEY_LONG_PRESS, object);
                msg.setAsynchronous(true);
                mHandler.sendMessageDelayed(msg, mActiveRule.getLongPressTimeoutMs());
            }

            if (mActiveRule.supportVeryLongPress()) {
                MessageObject object = new MessageObject(mActiveRule, keyCode, mKeyPressCounter,
                        event);
                final Message msg = mHandler.obtainMessage(MSG_KEY_VERY_LONG_PRESS, object);
                msg.setAsynchronous(true);
                mHandler.sendMessageDelayed(msg, mActiveRule.getVeryLongPressTimeoutMs());
            }
        } else {
            mHandler.removeMessages(MSG_KEY_LONG_PRESS);
            mHandler.removeMessages(MSG_KEY_VERY_LONG_PRESS);
            mHandler.removeMessages(MSG_KEY_DELAYED_PRESS);

            // Trigger multi press immediately when reach max count.( > 1)
            if (mActiveRule.getMaxMultiPressCount() > 1
                    && mKeyPressCounter == mActiveRule.getMaxMultiPressCount()) {
                if (DEBUG) {
                    Log.i(TAG, "Trigger multi press " + mActiveRule.toString() + " for it"
                            + " reached the max count " + mKeyPressCounter);
                }
                MessageObject object = new MessageObject(mActiveRule, keyCode, mKeyPressCounter,
                        event);
                final Message msg = mHandler.obtainMessage(MSG_KEY_DELAYED_PRESS, object);
                msg.setAsynchronous(true);
                mHandler.sendMessage(msg);
            }
        }
    }

    private boolean interceptKeyUp(KeyEvent event) {
        mDownKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        if (mActiveRule == null) {
            return false;
        }

        if (!mHandledByLongPress) {
            final long eventTime = event.getEventTime();
            if (eventTime < mLastDownTime + mActiveRule.getLongPressTimeoutMs()) {
                mHandler.removeMessages(MSG_KEY_LONG_PRESS);
            } else {
                mHandledByLongPress = mActiveRule.supportLongPress();
            }

            if (eventTime < mLastDownTime + mActiveRule.getVeryLongPressTimeoutMs()) {
                mHandler.removeMessages(MSG_KEY_VERY_LONG_PRESS);
            } else {
                // If long press or very long press (~3.5s) had been handled, we should skip the
                // short press behavior.
                mHandledByLongPress |= mActiveRule.supportVeryLongPress();
            }
        }

        if (mHandledByLongPress) {
            mHandledByLongPress = false;
            mKeyPressCounter = 0;
            mActiveRule = null;
            return true;
        }

        if (event.getKeyCode() == mActiveRule.mKeyCode) {
            // key-up action should always be triggered if not processed by long press.
            MessageObject object = new MessageObject(mActiveRule, mActiveRule.mKeyCode,
                    mKeyPressCounter, event);
            Message msgKeyUp = mHandler.obtainMessage(MSG_KEY_UP, object);
            msgKeyUp.setAsynchronous(true);
            mHandler.sendMessage(msgKeyUp);

            // Directly trigger short press when max count is 1.
            if (mActiveRule.getMaxMultiPressCount() == 1) {
                if (DEBUG) {
                    Log.i(TAG, "press key " + KeyEvent.keyCodeToString(event.getKeyCode()));
                }
                object = new MessageObject(mActiveRule, mActiveRule.mKeyCode,
                        /* pressCount= */ 1, event);
                Message msg = mHandler.obtainMessage(MSG_KEY_DELAYED_PRESS, object);
                msg.setAsynchronous(true);
                mHandler.sendMessage(msg);
                mActiveRule = null;
                return true;
            }

            // This could be a multi-press.  Wait a little bit longer to confirm.
            if (mKeyPressCounter < mActiveRule.getMaxMultiPressCount()) {
                object = new MessageObject(mActiveRule, mActiveRule.mKeyCode,
                        mKeyPressCounter, event);
                Message msg = mHandler.obtainMessage(MSG_KEY_DELAYED_PRESS, object);
                msg.setAsynchronous(true);
                mHandler.sendMessageDelayed(msg, MULTI_PRESS_TIMEOUT);
            }
            return true;
        }
        reset();
        return false;
    }

    int getKeyPressCounter(int keyCode) {
        if (mActiveRule != null && mActiveRule.mKeyCode == keyCode) {
            return mKeyPressCounter;
        } else {
            return 0;
        }
    }

    void reset() {
        if (mActiveRule != null) {
            if (mDownKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                mHandler.removeMessages(MSG_KEY_LONG_PRESS);
                mHandler.removeMessages(MSG_KEY_VERY_LONG_PRESS);
            }

            if (mKeyPressCounter > 0) {
                mHandler.removeMessages(MSG_KEY_DELAYED_PRESS);
                mKeyPressCounter = 0;
            }
            mActiveRule = null;
        }

        mHandledByLongPress = false;
        mDownKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    }

    boolean isKeyIntercepted(int keyCode) {
        return mActiveRule != null && mActiveRule.shouldInterceptKey(keyCode);
    }

    boolean beganFromNonInteractive() {
        return mBeganFromNonInteractive;
    }

    boolean beganFromDefaultDisplayOn() {
        return mBeganFromDefaultDisplayOn;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "SingleKey rules:");
        for (SingleKeyRule rule : mRules) {
            pw.println(prefix + "  " + rule);
        }
    }

    private class KeyHandler extends Handler {
        KeyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final MessageObject object = (MessageObject) msg.obj;
            final SingleKeyRule rule = object.activeRule;
            if (rule == null) {
                Log.wtf(TAG, "No active rule.");
                return;
            }

            final int keyCode = object.keyCode;
            final int pressCount = object.pressCount;
            final int displayId = object.displayId;
            switch(msg.what) {
                case MSG_KEY_UP:
                    if (DEBUG) {
                        Log.i(TAG, "Detect key up " + KeyEvent.keyCodeToString(keyCode)
                                + " on display " + displayId);
                    }
                    rule.onKeyUp(mLastDownTime, pressCount, displayId, object.deviceId,
                            object.metaState);
                    break;
                case MSG_KEY_LONG_PRESS:
                    if (DEBUG) {
                        Log.i(TAG, "Detect long press " + KeyEvent.keyCodeToString(keyCode));
                    }
                    rule.onLongPress(mLastDownTime);
                    break;
                case MSG_KEY_VERY_LONG_PRESS:
                    if (DEBUG) {
                        Log.i(TAG, "Detect very long press "
                                + KeyEvent.keyCodeToString(keyCode));
                    }
                    rule.onVeryLongPress(mLastDownTime);
                    break;
                case MSG_KEY_DELAYED_PRESS:
                    if (DEBUG) {
                        Log.i(TAG, "Detect press " + KeyEvent.keyCodeToString(keyCode)
                                + " on display " + displayId + ", count " + pressCount);
                    }
                    if (pressCount == 1) {
                        rule.onPress(mLastDownTime, displayId);
                    } else {
                        rule.onMultiPress(mLastDownTime, pressCount, displayId);
                    }
                    break;
            }
        }
    }
}
