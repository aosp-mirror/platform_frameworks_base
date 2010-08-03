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

import android.webkit.WebView;

/**
 * A class that acts as a JS interface for webview to mock various touch events,
 * mouse actions and key presses.
 *
 * The methods here just call corresponding methods on EventSenderImpl
 * that contains the logic of how to execute the methods.
 */
public class EventSender {
    EventSenderImpl mEventSenderImpl = new EventSenderImpl();

    public void reset(WebView webView) {
        mEventSenderImpl.reset(webView);
    }

    public void enableDOMUIEventLogging(int domNode) {
        mEventSenderImpl.enableDOMUIEventLogging(domNode);
    }

    public void fireKeyboardEventsToElement(int domNode) {
        mEventSenderImpl.fireKeyboardEventsToElement(domNode);
    }

    public void keyDown(String character, String[] withModifiers) {
        mEventSenderImpl.keyDown(character, withModifiers);
    }

    public void keyDown(String character) {
        keyDown(character, null);
    }

    public void leapForward(int milliseconds) {
        mEventSenderImpl.leapForward(milliseconds);
    }

    public void mouseClick() {
        mEventSenderImpl.mouseClick();
    }

    public void mouseDown() {
        mEventSenderImpl.mouseDown();
    }

    public void mouseMoveTo(int x, int y) {
        mEventSenderImpl.mouseMoveTo(x, y);
    }

    public void mouseUp() {
        mEventSenderImpl.mouseUp();
    }

    public void touchStart() {
        mEventSenderImpl.touchStart();
    }

    public void addTouchPoint(int x, int y) {
        mEventSenderImpl.addTouchPoint(x, y);
    }

    public void updateTouchPoint(int id, int x, int y) {
        mEventSenderImpl.updateTouchPoint(id, x, y);
    }

    public void setTouchModifier(String modifier, boolean enabled) {
        mEventSenderImpl.setTouchModifier(modifier, enabled);
    }

    public void touchMove() {
        mEventSenderImpl.touchMove();
    }

    public void releaseTouchPoint(int id) {
        mEventSenderImpl.releaseTouchPoint(id);
    }

    public void touchEnd() {
        mEventSenderImpl.touchEnd();
    }

    public void touchCancel() {
        mEventSenderImpl.touchCancel();
    }

    public void clearTouchPoints() {
        mEventSenderImpl.clearTouchPoints();
    }

    public void cancelTouchPoint(int id) {
        mEventSenderImpl.cancelTouchPoint(id);
    }
}