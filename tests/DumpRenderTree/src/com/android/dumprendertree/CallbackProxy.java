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

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.webkit.MockGeolocation;
import android.webkit.WebStorage;

import java.util.HashMap;

public class CallbackProxy extends Handler implements EventSender, LayoutTestController {

    private EventSender mEventSender;
    private LayoutTestController mLayoutTestController;

    private static final int EVENT_DOM_LOG = 1;
    private static final int EVENT_FIRE_KBD = 2;
    private static final int EVENT_KEY_DOWN_1 = 3;
    private static final int EVENT_KEY_DOWN_2 = 4;
    private static final int EVENT_LEAP = 5;
    private static final int EVENT_MOUSE_CLICK = 6;
    private static final int EVENT_MOUSE_DOWN = 7;
    private static final int EVENT_MOUSE_MOVE = 8;
    private static final int EVENT_MOUSE_UP = 9;
    private static final int EVENT_TOUCH_START = 10;
    private static final int EVENT_TOUCH_MOVE = 11;
    private static final int EVENT_TOUCH_END = 12;
    private static final int EVENT_TOUCH_CANCEL = 13;
    private static final int EVENT_ADD_TOUCH_POINT = 14;
    private static final int EVENT_UPDATE_TOUCH_POINT = 15;
    private static final int EVENT_RELEASE_TOUCH_POINT = 16;
    private static final int EVENT_CLEAR_TOUCH_POINTS = 17;
    private static final int EVENT_CANCEL_TOUCH_POINT = 18;
    private static final int EVENT_SET_TOUCH_MODIFIER = 19;
    
    private static final int LAYOUT_CLEAR_LIST = 20;
    private static final int LAYOUT_DISPLAY = 21;
    private static final int LAYOUT_DUMP_TEXT = 22;
    private static final int LAYOUT_DUMP_HISTORY = 23;
    private static final int LAYOUT_DUMP_CHILD_SCROLL = 24;
    private static final int LAYOUT_DUMP_EDIT_CB = 25;
    private static final int LAYOUT_DUMP_SEL_RECT = 26;
    private static final int LAYOUT_DUMP_TITLE_CHANGES = 27;
    private static final int LAYOUT_KEEP_WEB_HISTORY = 28;
    private static final int LAYOUT_NOTIFY_DONE = 29;
    private static final int LAYOUT_QUEUE_BACK_NAV = 30;
    private static final int LAYOUT_QUEUE_FWD_NAV = 31;
    private static final int LAYOUT_QUEUE_LOAD = 32;
    private static final int LAYOUT_QUEUE_RELOAD = 33;
    private static final int LAYOUT_QUEUE_SCRIPT = 34;
    private static final int LAYOUT_REPAINT_HORZ = 35;
    private static final int LAYOUT_SET_ACCEPT_EDIT = 36;
    private static final int LAYOUT_MAIN_FIRST_RESP = 37;
    private static final int LAYOUT_SET_WINDOW_KEY = 38;
    private static final int LAYOUT_TEST_REPAINT = 39;
    private static final int LAYOUT_WAIT_UNTIL_DONE = 40;
    private static final int LAYOUT_DUMP_DATABASE_CALLBACKS = 41;
    private static final int LAYOUT_SET_CAN_OPEN_WINDOWS = 42;
    private static final int SET_GEOLOCATION_PERMISSION = 43;
    private static final int OVERRIDE_PREFERENCE = 44;
    private static final int LAYOUT_DUMP_CHILD_FRAMES_TEXT = 45;
    private static final int SET_XSS_AUDITOR_ENABLED = 46;
    
    CallbackProxy(EventSender eventSender, 
            LayoutTestController layoutTestController) {
        mEventSender = eventSender;
        mLayoutTestController = layoutTestController;
    }
    
    public void handleMessage(Message msg) {
        switch (msg.what) {
        case EVENT_DOM_LOG:
            mEventSender.enableDOMUIEventLogging(msg.arg1);
            break;
        case EVENT_FIRE_KBD:
            mEventSender.fireKeyboardEventsToElement(msg.arg1);
            break;
        case EVENT_KEY_DOWN_1:
            HashMap map = (HashMap) msg.obj;
            mEventSender.keyDown((String) map.get("character"), 
                    (String[]) map.get("withModifiers"));
            break;

        case EVENT_KEY_DOWN_2:
            mEventSender.keyDown((String)msg.obj);
            break;

        case EVENT_LEAP:
            mEventSender.leapForward(msg.arg1);
            break;

        case EVENT_MOUSE_CLICK:
            mEventSender.mouseClick();
            break;

        case EVENT_MOUSE_DOWN:
            mEventSender.mouseDown();
            break;

        case EVENT_MOUSE_MOVE:
            mEventSender.mouseMoveTo(msg.arg1, msg.arg2);
            break;

        case EVENT_MOUSE_UP:
            mEventSender.mouseUp();
            break;

        case EVENT_TOUCH_START:
            mEventSender.touchStart();
            break;

        case EVENT_TOUCH_MOVE:
            mEventSender.touchMove();
            break;

        case EVENT_TOUCH_END:
            mEventSender.touchEnd();
            break;

        case EVENT_TOUCH_CANCEL:
            mEventSender.touchCancel();
            break;

        case EVENT_ADD_TOUCH_POINT:
            mEventSender.addTouchPoint(msg.arg1, msg.arg2);
            break;

        case EVENT_UPDATE_TOUCH_POINT:
            Bundle args = (Bundle) msg.obj;
            int x = args.getInt("x");
            int y = args.getInt("y");
            int id = args.getInt("id");
            mEventSender.updateTouchPoint(id, x, y);
            break;

        case EVENT_SET_TOUCH_MODIFIER:
            Bundle modifierArgs = (Bundle) msg.obj;
            String modifier = modifierArgs.getString("modifier");
            boolean enabled = modifierArgs.getBoolean("enabled");
            mEventSender.setTouchModifier(modifier, enabled);
            break;

        case EVENT_RELEASE_TOUCH_POINT:
            mEventSender.releaseTouchPoint(msg.arg1);
            break;

        case EVENT_CLEAR_TOUCH_POINTS:
            mEventSender.clearTouchPoints();
            break;

        case EVENT_CANCEL_TOUCH_POINT:
            mEventSender.cancelTouchPoint(msg.arg1);
            break;

        case LAYOUT_CLEAR_LIST:
            mLayoutTestController.clearBackForwardList();
            break;

        case LAYOUT_DISPLAY:
            mLayoutTestController.display();
            break;

        case LAYOUT_DUMP_TEXT:
            mLayoutTestController.dumpAsText(msg.arg1 == 1);
            break;

        case LAYOUT_DUMP_CHILD_FRAMES_TEXT:
            mLayoutTestController.dumpChildFramesAsText();
            break;

        case LAYOUT_DUMP_HISTORY:
            mLayoutTestController.dumpBackForwardList();
            break;

        case LAYOUT_DUMP_CHILD_SCROLL:
            mLayoutTestController.dumpChildFrameScrollPositions();
            break;

        case LAYOUT_DUMP_EDIT_CB:
            mLayoutTestController.dumpEditingCallbacks();
            break;

        case LAYOUT_DUMP_SEL_RECT:
            mLayoutTestController.dumpSelectionRect();
            break;

        case LAYOUT_DUMP_TITLE_CHANGES:
            mLayoutTestController.dumpTitleChanges();
            break;

        case LAYOUT_KEEP_WEB_HISTORY:
            mLayoutTestController.keepWebHistory();
            break;

        case LAYOUT_NOTIFY_DONE:
            mLayoutTestController.notifyDone();
            break;

        case LAYOUT_QUEUE_BACK_NAV:
            mLayoutTestController.queueBackNavigation(msg.arg1);
            break;

        case LAYOUT_QUEUE_FWD_NAV:
            mLayoutTestController.queueForwardNavigation(msg.arg1);
            break;

        case LAYOUT_QUEUE_LOAD:
            HashMap<String, String> loadMap = 
                (HashMap<String, String>) msg.obj;
            mLayoutTestController.queueLoad(loadMap.get("Url"), 
                    loadMap.get("frameTarget"));
            break;

        case LAYOUT_QUEUE_RELOAD:
            mLayoutTestController.queueReload();
            break;

        case LAYOUT_QUEUE_SCRIPT:
            mLayoutTestController.queueScript((String)msg.obj);
            break;

        case LAYOUT_REPAINT_HORZ:
            mLayoutTestController.repaintSweepHorizontally();
            break;

        case LAYOUT_SET_ACCEPT_EDIT:
            mLayoutTestController.setAcceptsEditing(
                    msg.arg1 == 1 ? true : false);
            break;
        case LAYOUT_MAIN_FIRST_RESP:
            mLayoutTestController.setMainFrameIsFirstResponder(
                    msg.arg1 == 1 ? true : false);
            break;

        case LAYOUT_SET_WINDOW_KEY:
            mLayoutTestController.setWindowIsKey(
                    msg.arg1 == 1 ? true : false);
            break;

        case LAYOUT_TEST_REPAINT:
            mLayoutTestController.testRepaint();
            break;

        case LAYOUT_WAIT_UNTIL_DONE:
            mLayoutTestController.waitUntilDone();
            break;

        case LAYOUT_DUMP_DATABASE_CALLBACKS:
            mLayoutTestController.dumpDatabaseCallbacks();
            break;

        case LAYOUT_SET_CAN_OPEN_WINDOWS:
            mLayoutTestController.setCanOpenWindows();
            break;

        case SET_GEOLOCATION_PERMISSION:
            mLayoutTestController.setGeolocationPermission(
                    msg.arg1 == 1 ? true : false);
            break;

        case OVERRIDE_PREFERENCE:
            String key = msg.getData().getString("key");
            boolean value = msg.getData().getBoolean("value");
            mLayoutTestController.overridePreference(key, value);
            break;

        case SET_XSS_AUDITOR_ENABLED:
            mLayoutTestController.setXSSAuditorEnabled(msg.arg1 == 1);
            break;
        }
    }

    // EventSender Methods
    
    public void enableDOMUIEventLogging(int DOMNode) {
        obtainMessage(EVENT_DOM_LOG, DOMNode, 0).sendToTarget();
    }

    public void fireKeyboardEventsToElement(int DOMNode) {
        obtainMessage(EVENT_FIRE_KBD, DOMNode, 0).sendToTarget();
    }

    public void keyDown(String character, String[] withModifiers) {
        // TODO Auto-generated method stub
        HashMap map = new HashMap();
        map.put("character", character);
        map.put("withModifiers", withModifiers);
        obtainMessage(EVENT_KEY_DOWN_1, map).sendToTarget();
    }

    public void keyDown(String character) {
        obtainMessage(EVENT_KEY_DOWN_2, character).sendToTarget();
    }

    public void leapForward(int milliseconds) {
        obtainMessage(EVENT_LEAP, milliseconds, 0).sendToTarget(); 
    }

    public void mouseClick() {
        obtainMessage(EVENT_MOUSE_CLICK).sendToTarget();
    }

    public void mouseDown() {
        obtainMessage(EVENT_MOUSE_DOWN).sendToTarget();
    }

    public void mouseMoveTo(int X, int Y) {
        obtainMessage(EVENT_MOUSE_MOVE, X, Y).sendToTarget();
    }

    public void mouseUp() {
        obtainMessage(EVENT_MOUSE_UP).sendToTarget();
    }

    public void touchStart() {
        obtainMessage(EVENT_TOUCH_START).sendToTarget();
    }

    public void addTouchPoint(int x, int y) {
        obtainMessage(EVENT_ADD_TOUCH_POINT, x, y).sendToTarget();
    }

    public void updateTouchPoint(int id, int x, int y) {
        Bundle map = new Bundle();
        map.putInt("x", x);
        map.putInt("y", y);
        map.putInt("id", id);
        obtainMessage(EVENT_UPDATE_TOUCH_POINT, map).sendToTarget();
    }

    public void setTouchModifier(String modifier, boolean enabled) {
        Bundle map = new Bundle();
        map.putString("modifier", modifier);
        map.putBoolean("enabled", enabled);
        obtainMessage(EVENT_SET_TOUCH_MODIFIER, map).sendToTarget();
    }

    public void touchMove() {
        obtainMessage(EVENT_TOUCH_MOVE).sendToTarget();
    }

    public void releaseTouchPoint(int id) {
        obtainMessage(EVENT_RELEASE_TOUCH_POINT, id, 0).sendToTarget();
    }

    public void touchEnd() {
        obtainMessage(EVENT_TOUCH_END).sendToTarget();
    }

    public void touchCancel() {
        obtainMessage(EVENT_TOUCH_CANCEL).sendToTarget();
    }


    public void clearTouchPoints() {
        obtainMessage(EVENT_CLEAR_TOUCH_POINTS).sendToTarget();
    }

    public void cancelTouchPoint(int id) {
        obtainMessage(EVENT_CANCEL_TOUCH_POINT, id, 0).sendToTarget();
    }
    
    // LayoutTestController Methods

    public void clearBackForwardList() {
        obtainMessage(LAYOUT_CLEAR_LIST).sendToTarget();
    }

    public void display() {
        obtainMessage(LAYOUT_DISPLAY).sendToTarget();
    }

    public void dumpAsText() {
        obtainMessage(LAYOUT_DUMP_TEXT, 0).sendToTarget();
    }

    public void dumpAsText(boolean enablePixelTests) {
        obtainMessage(LAYOUT_DUMP_TEXT, enablePixelTests ? 1 : 0).sendToTarget();
    }

    public void dumpChildFramesAsText() {
        obtainMessage(LAYOUT_DUMP_CHILD_FRAMES_TEXT).sendToTarget();
    }

    public void dumpBackForwardList() {
        obtainMessage(LAYOUT_DUMP_HISTORY).sendToTarget();
    }

    public void dumpChildFrameScrollPositions() {
        obtainMessage(LAYOUT_DUMP_CHILD_SCROLL).sendToTarget();
    }

    public void dumpEditingCallbacks() {
        obtainMessage(LAYOUT_DUMP_EDIT_CB).sendToTarget(); 
    }

    public void dumpSelectionRect() {
        obtainMessage(LAYOUT_DUMP_SEL_RECT).sendToTarget(); 
    }

    public void dumpTitleChanges() {
        obtainMessage(LAYOUT_DUMP_TITLE_CHANGES).sendToTarget();
    }

    public void keepWebHistory() {
        obtainMessage(LAYOUT_KEEP_WEB_HISTORY).sendToTarget();
    }

    public void notifyDone() {
        obtainMessage(LAYOUT_NOTIFY_DONE).sendToTarget();
    }

    public void queueBackNavigation(int howfar) {
        obtainMessage(LAYOUT_QUEUE_BACK_NAV, howfar, 0).sendToTarget();
    }

    public void queueForwardNavigation(int howfar) {
        obtainMessage(LAYOUT_QUEUE_FWD_NAV, howfar, 0).sendToTarget();
    }

    public void queueLoad(String Url, String frameTarget) {
        HashMap <String, String>map = new HashMap<String, String>();
        map.put("Url", Url);
        map.put("frameTarget", frameTarget);
        obtainMessage(LAYOUT_QUEUE_LOAD, map).sendToTarget();
    }

    public void queueReload() {
        obtainMessage(LAYOUT_QUEUE_RELOAD).sendToTarget();
    }

    public void queueScript(String scriptToRunInCurrentContext) {
        obtainMessage(LAYOUT_QUEUE_SCRIPT, 
                scriptToRunInCurrentContext).sendToTarget();
    }

    public void repaintSweepHorizontally() {
        obtainMessage(LAYOUT_REPAINT_HORZ).sendToTarget();
    }

    public void setAcceptsEditing(boolean b) {
        obtainMessage(LAYOUT_SET_ACCEPT_EDIT, b ? 1 : 0, 0).sendToTarget();
    }

    public void setMainFrameIsFirstResponder(boolean b) {
        obtainMessage(LAYOUT_MAIN_FIRST_RESP, b ? 1 : 0, 0).sendToTarget();
    }

    public void setWindowIsKey(boolean b) {
        obtainMessage(LAYOUT_SET_WINDOW_KEY, b ? 1 : 0, 0).sendToTarget();
    }

    public void testRepaint() {
        obtainMessage(LAYOUT_TEST_REPAINT).sendToTarget(); 
    }

    public void waitUntilDone() {
        obtainMessage(LAYOUT_WAIT_UNTIL_DONE).sendToTarget();
    }

    public void dumpDatabaseCallbacks() {
        obtainMessage(LAYOUT_DUMP_DATABASE_CALLBACKS).sendToTarget();
    }

    public void clearAllDatabases() {
        WebStorage.getInstance().deleteAllData();
    }

    public void setDatabaseQuota(long quota) {
        WebStorage.getInstance().setQuotaForOrigin("file://", quota);
    }

    public void setAppCacheMaximumSize(long size) {
        android.webkit.WebStorageClassic.getInstance().setAppCacheMaximumSize(size);
    }

    public void setCanOpenWindows() {
        obtainMessage(LAYOUT_SET_CAN_OPEN_WINDOWS).sendToTarget();
    }

    public void setMockGeolocationPosition(double latitude,
                                           double longitude,
                                           double accuracy) {
        MockGeolocation.getInstance().setPosition(latitude,
                                                  longitude,
                                                  accuracy);
    }

    public void setMockGeolocationError(int code, String message) {
        MockGeolocation.getInstance().setError(code, message);
    }

    public void setGeolocationPermission(boolean allow) {
        obtainMessage(SET_GEOLOCATION_PERMISSION, allow ? 1 : 0, 0).sendToTarget();
    }

    public void setMockDeviceOrientation(boolean canProvideAlpha, double alpha,
            boolean canProvideBeta, double beta, boolean canProvideGamma, double gamma) {
        // Configuration is in WebKit, so stay on WebCore thread, but go via the TestShellActivity
        // as we need access to the Webview.
        mLayoutTestController.setMockDeviceOrientation(canProvideAlpha, alpha, canProvideBeta, beta,
                canProvideGamma, gamma);
    }

    public void overridePreference(String key, boolean value) {
        Message message = obtainMessage(OVERRIDE_PREFERENCE);
        message.getData().putString("key", key);
        message.getData().putBoolean("value", value);
        message.sendToTarget();
    }

    public void setXSSAuditorEnabled(boolean flag) {
        obtainMessage(SET_XSS_AUDITOR_ENABLED, flag ? 1 : 0, 0).sendToTarget();
    }
}
