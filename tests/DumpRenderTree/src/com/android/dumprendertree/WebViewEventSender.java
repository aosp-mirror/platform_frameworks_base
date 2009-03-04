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

import android.webkit.WebView;
import android.view.KeyEvent;
import android.util.*;

import java.util.Arrays;

public class WebViewEventSender implements EventSender {
	
	WebViewEventSender(WebView webView) {
		mWebView = webView;
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
          /*  KeyEvent event = new KeyEvent(
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
            mWebView.onKeyDown(event.getKeyCode(), event); */
	}

	public void mouseMoveTo(int X, int Y) {
		if (X > mouseX) {
                    KeyEvent event = new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);
                    mWebView.onKeyDown(event.getKeyCode(), event);
                    mWebView.onKeyUp(event.getKeyCode(), event);
		} else if ( X < mouseX ) {
                    KeyEvent event = new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT);
                    mWebView.onKeyDown(event.getKeyCode(), event);
                    mWebView.onKeyUp(event.getKeyCode(), event);
		}
		if (Y > mouseY) {
                    KeyEvent event = new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
                    mWebView.onKeyDown(event.getKeyCode(), event);
                    mWebView.onKeyUp(event.getKeyCode(), event);
		} else if (Y < mouseY ) {
                    KeyEvent event = new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP);
                    mWebView.onKeyDown(event.getKeyCode(), event);
                    mWebView.onKeyUp(event.getKeyCode(), event);
		}
		mouseX= X;
		mouseY= Y;
	
	}

	public void mouseUp() {
        /*    KeyEvent event = new KeyEvent(
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);
            mWebView.onKeyDown(event.getKeyCode(), event);*/

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
	
    private WebView mWebView = null;
    private int mouseX;
    private int mouseY;

}
