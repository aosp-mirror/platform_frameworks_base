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

public interface EventSender {
    	public void mouseDown();
    	public void mouseUp();
        public void mouseClick();
        public void mouseMoveTo(int X, int Y);
        public void leapForward(int milliseconds);
        public void keyDown (String character, String[] withModifiers);
        public void keyDown (String character);
        public void enableDOMUIEventLogging(int DOMNode);
        public void fireKeyboardEventsToElement(int DOMNode);
        public void touchStart();
        public void touchMove();
        public void touchEnd();
        public void touchCancel();
        public void addTouchPoint(int x, int y);
        public void updateTouchPoint(int id, int x, int y);
        public void setTouchModifier(String modifier, boolean enabled);
        public void releaseTouchPoint(int id);
        public void clearTouchPoints();
        public void cancelTouchPoint(int id);
}
