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

package com.android.dumprendertree2;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;

import java.util.LinkedList;
import java.util.List;

/**
 * An implementation of EventSender
 */
public class EventSenderImpl {
    private static final String LOG_TAG = "EventSenderImpl";

    private static final int MSG_ENABLE_DOM_UI_EVENT_LOGGING = 0;
    private static final int MSG_FIRE_KEYBOARD_EVENTS_TO_ELEMENT = 1;
    private static final int MSG_LEAP_FORWARD = 2;

    private static final int MSG_KEY_DOWN = 3;

    private static final int MSG_MOUSE_DOWN = 4;
    private static final int MSG_MOUSE_UP = 5;
    private static final int MSG_MOUSE_CLICK = 6;
    private static final int MSG_MOUSE_MOVE_TO = 7;

    private static final int MSG_ADD_TOUCH_POINT = 8;
    private static final int MSG_TOUCH_START = 9;
    private static final int MSG_UPDATE_TOUCH_POINT = 10;
    private static final int MSG_TOUCH_MOVE = 11;
    private static final int MSG_CLEAR_TOUCH_POINTS = 12;
    private static final int MSG_TOUCH_CANCEL = 13;
    private static final int MSG_RELEASE_TOUCH_POINT = 14;
    private static final int MSG_TOUCH_END = 15;
    private static final int MSG_SET_TOUCH_MODIFIER = 16;
    private static final int MSG_CANCEL_TOUCH_POINT = 17;

    public static class TouchPoint {
        WebView mWebView;
        private int mX;
        private int mY;
        private long mDownTime;
        private boolean mReleased = false;
        private boolean mMoved = false;
        private boolean mCancelled = false;

        public TouchPoint(WebView webView, int x, int y) {
            mWebView = webView;
            mX = scaleX(x);
            mY = scaleY(y);
        }

        public int getX() {
            return mX;
        }

        public int getY() {
            return mY;
        }

        public boolean hasMoved() {
            return mMoved;
        }

        public void move(int newX, int newY) {
            mX = scaleX(newX);
            mY = scaleY(newY);
            mMoved = true;
        }

        public void resetHasMoved() {
            mMoved = false;
        }

        public long getDownTime() {
            return mDownTime;
        }

        public void setDownTime(long downTime) {
            mDownTime = downTime;
        }

        public boolean isReleased() {
            return mReleased;
        }

        public void release() {
            mReleased = true;
        }

        public boolean isCancelled() {
            return mCancelled;
        }

        public void cancel() {
            mCancelled = true;
        }

        private int scaleX(int x) {
            return (int)(x * mWebView.getScale()) - mWebView.getScrollX();
        }

        private int scaleY(int y) {
            return (int)(y * mWebView.getScale()) - mWebView.getScrollY();
        }
    }

    private List<TouchPoint> mTouchPoints;
    private int mTouchMetaState;
    private int mMouseX;
    private int mMouseY;

    private WebView mWebView;

    private Handler mEventSenderHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            TouchPoint touchPoint;
            Bundle bundle;
            KeyEvent event;

            switch (msg.what) {
                case MSG_ENABLE_DOM_UI_EVENT_LOGGING:
                    /** TODO: implement */
                    break;

                case MSG_FIRE_KEYBOARD_EVENTS_TO_ELEMENT:
                    /** TODO: implement */
                    break;

                case MSG_LEAP_FORWARD:
                    /** TODO: implement */
                    break;

                case MSG_KEY_DOWN:
                    bundle = (Bundle)msg.obj;
                    String character = bundle.getString("character");
                    String[] withModifiers = bundle.getStringArray("withModifiers");

                    if (withModifiers != null && withModifiers.length > 0) {
                        for (int i = 0; i < withModifiers.length; i++) {
                            executeKeyEvent(KeyEvent.ACTION_DOWN,
                                    modifierToKeyCode(withModifiers[i]));
                        }
                    }
                    executeKeyEvent(KeyEvent.ACTION_DOWN,
                            charToKeyCode(character.toLowerCase().toCharArray()[0]));
                    break;

                /** MOUSE */

                case MSG_MOUSE_DOWN:
                    /** TODO: Implement */
                    break;

                case MSG_MOUSE_UP:
                    /** TODO: Implement */
                    break;

                case MSG_MOUSE_CLICK:
                    /** TODO: Implement */
                    break;

                case MSG_MOUSE_MOVE_TO:
                    int x = msg.arg1;
                    int y = msg.arg2;

                    event = null;
                    if (x > mMouseX) {
                        event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);
                    } else if (x < mMouseX) {
                        event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT);
                    }
                    if (event != null) {
                        mWebView.onKeyDown(event.getKeyCode(), event);
                        mWebView.onKeyUp(event.getKeyCode(), event);
                    }

                    event = null;
                    if (y > mMouseY) {
                        event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
                    } else if (y < mMouseY) {
                        event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP);
                    }
                    if (event != null) {
                        mWebView.onKeyDown(event.getKeyCode(), event);
                        mWebView.onKeyUp(event.getKeyCode(), event);
                    }

                    mMouseX = x;
                    mMouseY = y;
                    break;

                /** TOUCH */

                case MSG_ADD_TOUCH_POINT:
                    getTouchPoints().add(new TouchPoint(mWebView,
                            msg.arg1, msg.arg2));
                    if (getTouchPoints().size() > 1) {
                        Log.w(LOG_TAG + "::MSG_ADD_TOUCH_POINT", "Added more than one touch point");
                    }
                    break;

                case MSG_TOUCH_START:
                    /**
                     * FIXME: At the moment we don't support multi-touch. Hence, we only examine
                     * the first touch point. In future this method will need rewriting.
                     */
                    if (getTouchPoints().isEmpty()) {
                        return;
                    }
                    touchPoint = getTouchPoints().get(0);

                    touchPoint.setDownTime(SystemClock.uptimeMillis());
                    executeTouchEvent(touchPoint, MotionEvent.ACTION_DOWN);
                    break;

                case MSG_UPDATE_TOUCH_POINT:
                    bundle = (Bundle)msg.obj;

                    int id = bundle.getInt("id");
                    if (id >= getTouchPoints().size()) {
                        Log.w(LOG_TAG + "::MSG_UPDATE_TOUCH_POINT", "TouchPoint out of bounds: "
                                + id);
                        break;
                    }

                    getTouchPoints().get(id).move(bundle.getInt("x"), bundle.getInt("y"));
                    break;

                case MSG_TOUCH_MOVE:
                    /**
                     * FIXME: At the moment we don't support multi-touch. Hence, we only examine
                     * the first touch point. In future this method will need rewriting.
                     */
                    if (getTouchPoints().isEmpty()) {
                        return;
                    }
                    touchPoint = getTouchPoints().get(0);

                    if (!touchPoint.hasMoved()) {
                        return;
                    }
                    executeTouchEvent(touchPoint, MotionEvent.ACTION_MOVE);
                    touchPoint.resetHasMoved();
                    break;

                case MSG_CANCEL_TOUCH_POINT:
                    if (msg.arg1 >= getTouchPoints().size()) {
                        Log.w(LOG_TAG + "::MSG_RELEASE_TOUCH_POINT", "TouchPoint out of bounds: "
                                + msg.arg1);
                        break;
                    }

                    getTouchPoints().get(msg.arg1).cancel();
                    break;

                case MSG_TOUCH_CANCEL:
                    /**
                     * FIXME: At the moment we don't support multi-touch. Hence, we only examine
                     * the first touch point. In future this method will need rewriting.
                     */
                    if (getTouchPoints().isEmpty()) {
                        return;
                    }
                    touchPoint = getTouchPoints().get(0);

                    if (touchPoint.isCancelled()) {
                        executeTouchEvent(touchPoint, MotionEvent.ACTION_CANCEL);
                    }
                    break;

                case MSG_RELEASE_TOUCH_POINT:
                    if (msg.arg1 >= getTouchPoints().size()) {
                        Log.w(LOG_TAG + "::MSG_RELEASE_TOUCH_POINT", "TouchPoint out of bounds: "
                                + msg.arg1);
                        break;
                    }

                    getTouchPoints().get(msg.arg1).release();
                    break;

                case MSG_TOUCH_END:
                    /**
                     * FIXME: At the moment we don't support multi-touch. Hence, we only examine
                     * the first touch point. In future this method will need rewriting.
                     */
                    if (getTouchPoints().isEmpty()) {
                        return;
                    }
                    touchPoint = getTouchPoints().get(0);

                    executeTouchEvent(touchPoint, MotionEvent.ACTION_UP);
                    if (touchPoint.isReleased()) {
                        getTouchPoints().remove(0);
                        touchPoint = null;
                    }
                    break;

                case MSG_SET_TOUCH_MODIFIER:
                    bundle = (Bundle)msg.obj;
                    String modifier = bundle.getString("modifier");
                    boolean enabled = bundle.getBoolean("enabled");

                    int mask = 0;
                    if ("alt".equals(modifier.toLowerCase())) {
                        mask = KeyEvent.META_ALT_ON;
                    } else if ("shift".equals(modifier.toLowerCase())) {
                        mask = KeyEvent.META_SHIFT_ON;
                    } else if ("ctrl".equals(modifier.toLowerCase())) {
                        mask = KeyEvent.META_SYM_ON;
                    }

                    if (enabled) {
                        mTouchMetaState |= mask;
                    } else {
                        mTouchMetaState &= ~mask;
                    }

                    break;

                case MSG_CLEAR_TOUCH_POINTS:
                    getTouchPoints().clear();
                    break;

                default:
                    break;
            }
        }
    };

    public void reset(WebView webView) {
        mWebView = webView;
        mTouchPoints = null;
        mTouchMetaState = 0;
        mMouseX = 0;
        mMouseY = 0;
    }

    public void enableDOMUIEventLogging(int domNode) {
        Message msg = mEventSenderHandler.obtainMessage(MSG_ENABLE_DOM_UI_EVENT_LOGGING);
        msg.arg1 = domNode;
        msg.sendToTarget();
    }

    public void fireKeyboardEventsToElement(int domNode) {
        Message msg = mEventSenderHandler.obtainMessage(MSG_FIRE_KEYBOARD_EVENTS_TO_ELEMENT);
        msg.arg1 = domNode;
        msg.sendToTarget();
    }

    public void leapForward(int milliseconds) {
        Message msg = mEventSenderHandler.obtainMessage(MSG_LEAP_FORWARD);
        msg.arg1 = milliseconds;
        msg.sendToTarget();
    }

    public void keyDown(String character, String[] withModifiers) {
        Bundle bundle = new Bundle();
        bundle.putString("character", character);
        bundle.putStringArray("withModifiers", withModifiers);
        mEventSenderHandler.obtainMessage(MSG_KEY_DOWN, bundle).sendToTarget();
    }

    /** MOUSE */

    public void mouseDown() {
        mEventSenderHandler.sendEmptyMessage(MSG_MOUSE_DOWN);
    }

    public void mouseUp() {
        mEventSenderHandler.sendEmptyMessage(MSG_MOUSE_UP);
    }

    public void mouseClick() {
        mEventSenderHandler.sendEmptyMessage(MSG_MOUSE_CLICK);
    }

    public void mouseMoveTo(int x, int y) {
        mEventSenderHandler.obtainMessage(MSG_MOUSE_MOVE_TO, x, y).sendToTarget();
    }

    /** TOUCH */

    public void addTouchPoint(int x, int y) {
        mEventSenderHandler.obtainMessage(MSG_ADD_TOUCH_POINT, x, y).sendToTarget();
    }

    public void touchStart() {
        mEventSenderHandler.sendEmptyMessage(MSG_TOUCH_START);
    }

    public void updateTouchPoint(int id, int x, int y) {
        Bundle bundle = new Bundle();
        bundle.putInt("id", id);
        bundle.putInt("x", x);
        bundle.putInt("y", y);
        mEventSenderHandler.obtainMessage(MSG_UPDATE_TOUCH_POINT, bundle).sendToTarget();
    }

    public void touchMove() {
        mEventSenderHandler.sendEmptyMessage(MSG_TOUCH_MOVE);
    }

    public void cancelTouchPoint(int id) {
        Message msg = mEventSenderHandler.obtainMessage(MSG_CANCEL_TOUCH_POINT);
        msg.arg1 = id;
        msg.sendToTarget();
    }

    public void touchCancel() {
        mEventSenderHandler.sendEmptyMessage(MSG_TOUCH_CANCEL);
    }

    public void releaseTouchPoint(int id) {
        Message msg = mEventSenderHandler.obtainMessage(MSG_RELEASE_TOUCH_POINT);
        msg.arg1 = id;
        msg.sendToTarget();
    }

    public void touchEnd() {
        mEventSenderHandler.sendEmptyMessage(MSG_TOUCH_END);
    }

    public void setTouchModifier(String modifier, boolean enabled) {
        Bundle bundle = new Bundle();
        bundle.putString("modifier", modifier);
        bundle.putBoolean("enabled", enabled);
        mEventSenderHandler.obtainMessage(MSG_SET_TOUCH_MODIFIER, bundle).sendToTarget();
    }

    public void clearTouchPoints() {
        mEventSenderHandler.sendEmptyMessage(MSG_CLEAR_TOUCH_POINTS);
    }

    private List<TouchPoint> getTouchPoints() {
        if (mTouchPoints == null) {
            mTouchPoints = new LinkedList<TouchPoint>();
        }

        return mTouchPoints;
    }

    private void executeTouchEvent(TouchPoint touchPoint, int action) {
        MotionEvent event =
                MotionEvent.obtain(touchPoint.getDownTime(), SystemClock.uptimeMillis(),
                action, touchPoint.getX(), touchPoint.getY(), mTouchMetaState);
        mWebView.onTouchEvent(event);
    }

    private void executeKeyEvent(int action, int keyCode) {
        KeyEvent event = new KeyEvent(action, keyCode);
        mWebView.onKeyDown(event.getKeyCode(), event);
    }

    /**
     * Assumes lowercase chars, case needs to be handled by calling function.
     */
    private static int charToKeyCode(char c) {
        // handle numbers
        if (c >= '0' && c <= '9') {
            int offset = c - '0';
            return KeyEvent.KEYCODE_0 + offset;
        }

        // handle characters
        if (c >= 'a' && c <= 'z') {
            int offset = c - 'a';
            return KeyEvent.KEYCODE_A + offset;
        }

        // handle all others
        switch (c) {
            case '*':
                return KeyEvent.KEYCODE_STAR;

            case '#':
                return KeyEvent.KEYCODE_POUND;

            case ',':
                return KeyEvent.KEYCODE_COMMA;

            case '.':
                return KeyEvent.KEYCODE_PERIOD;

            case '\t':
                return KeyEvent.KEYCODE_TAB;

            case ' ':
                return KeyEvent.KEYCODE_SPACE;

            case '\n':
                return KeyEvent.KEYCODE_ENTER;

            case '\b':
            case 0x7F:
                return KeyEvent.KEYCODE_DEL;

            case '~':
                return KeyEvent.KEYCODE_GRAVE;

            case '-':
                return KeyEvent.KEYCODE_MINUS;

            case '=':
                return KeyEvent.KEYCODE_EQUALS;

            case '(':
                return KeyEvent.KEYCODE_LEFT_BRACKET;

            case ')':
                return KeyEvent.KEYCODE_RIGHT_BRACKET;

            case '\\':
                return KeyEvent.KEYCODE_BACKSLASH;

            case ';':
                return KeyEvent.KEYCODE_SEMICOLON;

            case '\'':
                return KeyEvent.KEYCODE_APOSTROPHE;

            case '/':
                return KeyEvent.KEYCODE_SLASH;

            default:
                return c;
        }
    }

    private static int modifierToKeyCode(String modifier) {
        if (modifier.equals("ctrlKey")) {
            return KeyEvent.KEYCODE_ALT_LEFT;
        } else if (modifier.equals("shiftKey")) {
            return KeyEvent.KEYCODE_SHIFT_LEFT;
        } else if (modifier.equals("altKey")) {
            return KeyEvent.KEYCODE_SYM;
        }

        return KeyEvent.KEYCODE_UNKNOWN;
    }
}