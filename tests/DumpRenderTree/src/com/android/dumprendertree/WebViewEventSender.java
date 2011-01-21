/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.dumprendertree;

import android.os.SystemClock;
import android.util.*;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;

import java.util.Arrays;
import java.util.Vector;

public class WebViewEventSender implements EventSender {

    private static final String LOGTAG = "WebViewEventSender";
	
    WebViewEventSender(WebView webView) {
        mWebView = webView;
        mWebView.getSettings().setBuiltInZoomControls(true);
        mTouchPoints = new Vector<TouchPoint>();
    }
	
	public void resetMouse() {
		mouseX = mouseY = 0;
	}

	public void enableDOMUIEventLogging(int DOMNode) {
		// TODO Auto-generated method stub

	}

	public void fireKeyboardEventsToElement(int DOMNode) {
		// TODO Auto-generated method stub

	}

	public void keyDown(String character, String[] withModifiers) {
        Log.e("EventSender", "KeyDown: " + character + "("
                + character.getBytes()[0] + ") Modifiers: "
                + Arrays.toString(withModifiers));
        KeyEvent modifier = null;
        if (withModifiers != null && withModifiers.length > 0) {
            for (int i = 0; i < withModifiers.length; i++) {
                int keyCode = modifierMapper(withModifiers[i]);
                modifier = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
                mWebView.onKeyDown(modifier.getKeyCode(), modifier);
            }
        }
        int keyCode = keyMapper(character.toLowerCase().toCharArray()[0]);
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        mWebView.onKeyDown(event.getKeyCode(), event);

    }
	
	public void keyDown(String character) {
        keyDown(character, null);
	}

	public void leapForward(int milliseconds) {
		// TODO Auto-generated method stub

	}

	public void mouseClick() {
		mouseDown();
		mouseUp();
	}

    public void mouseDown() {
        long ts = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(ts, ts, MotionEvent.ACTION_DOWN, mouseX, mouseY, 0);
        mWebView.onTouchEvent(event);
    }

    public void mouseMoveTo(int X, int Y) {
        mouseX= X;
        mouseY= Y;
    }

     public void mouseUp() {
        long ts = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(ts, ts, MotionEvent.ACTION_UP, mouseX, mouseY, 0);
        mWebView.onTouchEvent(event);
    }

	// Assumes lowercase chars, case needs to be
	// handled by calling function.
	static int keyMapper(char c) {
		// handle numbers
		if (c >= '0' && c<= '9') {
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
			break;
		}

		return c;
	}
	
	static int modifierMapper(String modifier) {
		if (modifier.equals("ctrlKey")) {
			return KeyEvent.KEYCODE_ALT_LEFT;
		} else if (modifier.equals("shiftKey")) {
			return KeyEvent.KEYCODE_SHIFT_LEFT;
		} else if (modifier.equals("altKey")) {
			return KeyEvent.KEYCODE_SYM;
		} else if (modifier.equals("metaKey")) {
			return KeyEvent.KEYCODE_UNKNOWN;
		}
		return KeyEvent.KEYCODE_UNKNOWN;
	}

    public void touchStart() {
        final int numPoints = mTouchPoints.size();
        if (numPoints == 0) {
            return;
        }

        int[] pointerIds = new int[numPoints];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[numPoints];
        long downTime = SystemClock.uptimeMillis();

        for (int i = 0; i < numPoints; ++i) {
            pointerIds[i] = mTouchPoints.get(i).getId();
            pointerCoords[i] = new MotionEvent.PointerCoords();
            pointerCoords[i].x = mTouchPoints.get(i).getX();
            pointerCoords[i].y = mTouchPoints.get(i).getY();
            mTouchPoints.get(i).setDownTime(downTime);
        }

        MotionEvent event = MotionEvent.obtain(downTime, downTime,
            MotionEvent.ACTION_DOWN, numPoints, pointerIds, pointerCoords,
            mTouchMetaState, 1.0f, 1.0f, 0, 0, 0, 0);

        mWebView.onTouchEvent(event);
    }

    public void touchMove() {
        final int numPoints = mTouchPoints.size();
        if (numPoints == 0) {
            return;
        }

        int[] pointerIds = new int[numPoints];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[numPoints];
        int numMovedPoints = 0;
        for (int i = 0; i < numPoints; ++i) {
            TouchPoint tp = mTouchPoints.get(i);
            if (tp.hasMoved()) {
                pointerIds[numMovedPoints] = mTouchPoints.get(i).getId();
                pointerCoords[i] = new MotionEvent.PointerCoords();
                pointerCoords[numMovedPoints].x = mTouchPoints.get(i).getX();
                pointerCoords[numMovedPoints].y = mTouchPoints.get(i).getY();
                ++numMovedPoints;
                tp.setMoved(false);
            }
        }

        if (numMovedPoints == 0) {
            return;
        }

        MotionEvent event = MotionEvent.obtain(mTouchPoints.get(0).downTime(),
                SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                numMovedPoints, pointerIds, pointerCoords,
                mTouchMetaState, 1.0f, 1.0f, 0, 0, 0, 0);
        mWebView.onTouchEvent(event);
    }

    public void touchEnd() {
        final int numPoints = mTouchPoints.size();
        if (numPoints == 0) {
            return;
        }

        int[] pointerIds = new int[numPoints];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[numPoints];

        for (int i = 0; i < numPoints; ++i) {
            pointerIds[i] = mTouchPoints.get(i).getId();
            pointerCoords[i] = new MotionEvent.PointerCoords();
            pointerCoords[i].x = mTouchPoints.get(i).getX();
            pointerCoords[i].y = mTouchPoints.get(i).getY();
        }

        MotionEvent event = MotionEvent.obtain(mTouchPoints.get(0).downTime(),
                SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                numPoints, pointerIds, pointerCoords,
                mTouchMetaState, 1.0f, 1.0f, 0, 0, 0, 0);
        mWebView.onTouchEvent(event);

        for (int i = numPoints - 1; i >= 0; --i) {  // remove released points.
            TouchPoint tp = mTouchPoints.get(i);
            if (tp.isReleased()) {
              mTouchPoints.remove(i);
            }
        }
    }

    public void touchCancel() {
        final int numPoints = mTouchPoints.size();
        if (numPoints == 0) {
            return;
        }

        int[] pointerIds = new int[numPoints];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[numPoints];
        long cancelTime = SystemClock.uptimeMillis();
        int numCanceledPoints = 0;

        for (int i = 0; i < numPoints; ++i) {
            TouchPoint tp = mTouchPoints.get(i);
            if (tp.cancelled()) {
                pointerIds[numCanceledPoints] = mTouchPoints.get(i).getId();
                pointerCoords[numCanceledPoints] = new MotionEvent.PointerCoords();
                pointerCoords[numCanceledPoints].x = mTouchPoints.get(i).getX();
                pointerCoords[numCanceledPoints].y = mTouchPoints.get(i).getY();
                ++numCanceledPoints;
            }
        }

        if (numCanceledPoints == 0) {
            return;
        }

        MotionEvent event = MotionEvent.obtain(mTouchPoints.get(0).downTime(),
            SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
            numCanceledPoints, pointerIds, pointerCoords,
            mTouchMetaState, 1.0f, 1.0f, 0, 0, 0, 0);

        mWebView.onTouchEvent(event);
    }

    public void cancelTouchPoint(int id) {
        TouchPoint tp = mTouchPoints.get(id);
        if (tp == null) {
            return;
        }

        tp.cancel();
    }

    public void addTouchPoint(int x, int y) {
        final int numPoints = mTouchPoints.size();
        int id;
        if (numPoints == 0) {
          id = 0;
        } else {
          id = mTouchPoints.get(numPoints - 1).getId() + 1;
        }

        mTouchPoints.add(new TouchPoint(id, contentsToWindowX(x), contentsToWindowY(y)));
    }

    public void updateTouchPoint(int i, int x, int y) {
        TouchPoint tp = mTouchPoints.get(i);
        if (tp == null) {
            return;
        }

        tp.update(contentsToWindowX(x), contentsToWindowY(y));
        tp.setMoved(true);
    }

    public void setTouchModifier(String modifier, boolean enabled) {
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
    }

    public void releaseTouchPoint(int id) {
        TouchPoint tp = mTouchPoints.get(id);
        if (tp == null) {
            return;
        }

        tp.release();
    }

    public void clearTouchPoints() {
        mTouchPoints.clear();
    }

    public void clearTouchMetaState() {
        mTouchMetaState = 0;
    }

    private int contentsToWindowX(int x) {
        return Math.round(x * mWebView.getScale()) - mWebView.getScrollX();
    }

    private int contentsToWindowY(int y) {
        return Math.round(y * mWebView.getScale()) - mWebView.getScrollY();
    }

    private WebView mWebView = null;
    private int mouseX;
    private int mouseY;

    private class TouchPoint {
        private int mId;
        private int mX;
        private int mY;
        private long mDownTime;
        private boolean mReleased;
        private boolean mMoved;
        private boolean mCancelled;

        public TouchPoint(int id, int x, int y) {
            mId = id;
            mX = x;
            mY = y;
            mReleased = false;
            mMoved = false;
            mCancelled = false;
        }

        public void setDownTime(long downTime) { mDownTime = downTime; }
        public long downTime() { return mDownTime; }
        public void cancel() { mCancelled = true; }

        public boolean cancelled() { return mCancelled; }

        public void release() { mReleased = true; }
        public boolean isReleased() { return mReleased; }

        public void setMoved(boolean moved) { mMoved = moved; }
        public boolean hasMoved() { return mMoved; }

        public int getId() { return mId; }
        public int getX() { return mX; }
        public int getY() { return mY; }

        public void update(int x, int y) {
            mX = x;
            mY = y;
        }
    }

    private Vector<TouchPoint> mTouchPoints;
    private int mTouchMetaState;
}
