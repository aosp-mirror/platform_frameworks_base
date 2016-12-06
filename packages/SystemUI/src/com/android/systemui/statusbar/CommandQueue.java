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

package com.android.systemui.statusbar;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Pair;
import android.view.KeyEvent;

import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.StatusBarIcon;

/**
 * This class takes the functions from IStatusBar that come in on
 * binder pool threads and posts messages to get them onto the main
 * thread, and calls onto Callbacks.  It also takes care of
 * coalescing these calls so they don't stack up.  For the calls
 * are coalesced, note that they are all idempotent.
 */
public class CommandQueue extends IStatusBar.Stub {
    private static final int INDEX_MASK = 0xffff;
    private static final int MSG_SHIFT  = 16;
    private static final int MSG_MASK   = 0xffff << MSG_SHIFT;

    private static final int OP_SET_ICON    = 1;
    private static final int OP_REMOVE_ICON = 2;

    private static final int MSG_ICON                          = 1 << MSG_SHIFT;
    private static final int MSG_DISABLE                       = 2 << MSG_SHIFT;
    private static final int MSG_EXPAND_NOTIFICATIONS          = 3 << MSG_SHIFT;
    private static final int MSG_COLLAPSE_PANELS               = 4 << MSG_SHIFT;
    private static final int MSG_EXPAND_SETTINGS               = 5 << MSG_SHIFT;
    private static final int MSG_SET_SYSTEMUI_VISIBILITY       = 6 << MSG_SHIFT;
    private static final int MSG_TOP_APP_WINDOW_CHANGED        = 7 << MSG_SHIFT;
    private static final int MSG_SHOW_IME_BUTTON               = 8 << MSG_SHIFT;
    private static final int MSG_TOGGLE_RECENT_APPS            = 9 << MSG_SHIFT;
    private static final int MSG_PRELOAD_RECENT_APPS           = 10 << MSG_SHIFT;
    private static final int MSG_CANCEL_PRELOAD_RECENT_APPS    = 11 << MSG_SHIFT;
    private static final int MSG_SET_WINDOW_STATE              = 12 << MSG_SHIFT;
    private static final int MSG_SHOW_RECENT_APPS              = 13 << MSG_SHIFT;
    private static final int MSG_HIDE_RECENT_APPS              = 14 << MSG_SHIFT;
    private static final int MSG_BUZZ_BEEP_BLINKED             = 15 << MSG_SHIFT;
    private static final int MSG_NOTIFICATION_LIGHT_OFF        = 16 << MSG_SHIFT;
    private static final int MSG_NOTIFICATION_LIGHT_PULSE      = 17 << MSG_SHIFT;
    private static final int MSG_SHOW_SCREEN_PIN_REQUEST       = 18 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_PENDING        = 19 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_CANCELLED      = 20 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_STARTING       = 21 << MSG_SHIFT;
    private static final int MSG_ASSIST_DISCLOSURE             = 22 << MSG_SHIFT;
    private static final int MSG_START_ASSIST                  = 23 << MSG_SHIFT;
    private static final int MSG_CAMERA_LAUNCH_GESTURE         = 24 << MSG_SHIFT;
    private static final int MSG_TOGGLE_KEYBOARD_SHORTCUTS     = 25 << MSG_SHIFT;
    private static final int MSG_SHOW_TV_PICTURE_IN_PICTURE_MENU = 26 << MSG_SHIFT;
    private static final int MSG_ADD_QS_TILE                   = 27 << MSG_SHIFT;
    private static final int MSG_REMOVE_QS_TILE                = 28 << MSG_SHIFT;
    private static final int MSG_CLICK_QS_TILE                 = 29 << MSG_SHIFT;
    private static final int MSG_TOGGLE_APP_SPLIT_SCREEN       = 30 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_FINISHED       = 31 << MSG_SHIFT;
    private static final int MSG_DISMISS_KEYBOARD_SHORTCUTS    = 32 << MSG_SHIFT;
    private static final int MSG_HANDLE_SYSNAV_KEY             = 33 << MSG_SHIFT;

    public static final int FLAG_EXCLUDE_NONE = 0;
    public static final int FLAG_EXCLUDE_SEARCH_PANEL = 1 << 0;
    public static final int FLAG_EXCLUDE_RECENTS_PANEL = 1 << 1;
    public static final int FLAG_EXCLUDE_NOTIFICATION_PANEL = 1 << 2;
    public static final int FLAG_EXCLUDE_INPUT_METHODS_PANEL = 1 << 3;
    public static final int FLAG_EXCLUDE_COMPAT_MODE_PANEL = 1 << 4;

    private static final String SHOW_IME_SWITCHER_KEY = "showImeSwitcherKey";

    private final Object mLock = new Object();
    private Callbacks mCallbacks;
    private Handler mHandler = new H();

    /**
     * These methods are called back on the main thread.
     */
    public interface Callbacks {
        void setIcon(String slot, StatusBarIcon icon);
        void removeIcon(String slot);
        void disable(int state1, int state2, boolean animate);
        void animateExpandNotificationsPanel();
        void animateCollapsePanels(int flags);
        void animateExpandSettingsPanel(String obj);
        void setSystemUiVisibility(int vis, int fullscreenStackVis,
                int dockedStackVis, int mask, Rect fullscreenStackBounds, Rect dockedStackBounds);
        void topAppWindowChanged(boolean visible);
        void setImeWindowStatus(IBinder token, int vis, int backDisposition,
                boolean showImeSwitcher);
        void showRecentApps(boolean triggeredFromAltTab, boolean fromHome);
        void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);
        void toggleRecentApps();
        void toggleSplitScreen();
        void preloadRecentApps();
        void dismissKeyboardShortcutsMenu();
        void toggleKeyboardShortcutsMenu(int deviceId);
        void cancelPreloadRecentApps();
        void setWindowState(int window, int state);
        void buzzBeepBlinked();
        void notificationLightOff();
        void notificationLightPulse(int argb, int onMillis, int offMillis);
        void showScreenPinningRequest(int taskId);
        void appTransitionPending();
        void appTransitionCancelled();
        void appTransitionStarting(long startTime, long duration);
        void appTransitionFinished();
        void showAssistDisclosure();
        void startAssist(Bundle args);
        void onCameraLaunchGestureDetected(int source);
        void showTvPictureInPictureMenu();

        void addQsTile(ComponentName tile);
        void remQsTile(ComponentName tile);
        void clickTile(ComponentName tile);

        void handleSystemNavigationKey(int arg1);
    }

    public CommandQueue(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    public void setIcon(String slot, StatusBarIcon icon) {
        synchronized (mLock) {
            // don't coalesce these
            mHandler.obtainMessage(MSG_ICON, OP_SET_ICON, 0,
                    new Pair<String, StatusBarIcon>(slot, icon)).sendToTarget();
        }
    }

    public void removeIcon(String slot) {
        synchronized (mLock) {
            // don't coalesce these
            mHandler.obtainMessage(MSG_ICON, OP_REMOVE_ICON, 0, slot).sendToTarget();
        }
    }

    public void disable(int state1, int state2) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_DISABLE);
            mHandler.obtainMessage(MSG_DISABLE, state1, state2, null).sendToTarget();
        }
    }

    public void animateExpandNotificationsPanel() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_EXPAND_NOTIFICATIONS);
            mHandler.sendEmptyMessage(MSG_EXPAND_NOTIFICATIONS);
        }
    }

    public void animateCollapsePanels() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_COLLAPSE_PANELS);
            mHandler.sendEmptyMessage(MSG_COLLAPSE_PANELS);
        }
    }

    public void animateExpandSettingsPanel(String subPanel) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_EXPAND_SETTINGS);
            mHandler.obtainMessage(MSG_EXPAND_SETTINGS, subPanel).sendToTarget();
        }
    }

    public void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis,
            int mask, Rect fullscreenStackBounds, Rect dockedStackBounds) {
        synchronized (mLock) {
            // Don't coalesce these, since it might have one time flags set such as
            // STATUS_BAR_UNHIDE which might get lost.
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = vis;
            args.argi2 = fullscreenStackVis;
            args.argi3 = dockedStackVis;
            args.argi4 = mask;
            args.arg1 = fullscreenStackBounds;
            args.arg2 = dockedStackBounds;
            mHandler.obtainMessage(MSG_SET_SYSTEMUI_VISIBILITY, args).sendToTarget();
        }
    }

    public void topAppWindowChanged(boolean menuVisible) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOP_APP_WINDOW_CHANGED);
            mHandler.obtainMessage(MSG_TOP_APP_WINDOW_CHANGED, menuVisible ? 1 : 0, 0,
                    null).sendToTarget();
        }
    }

    public void setImeWindowStatus(IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_IME_BUTTON);
            Message m = mHandler.obtainMessage(MSG_SHOW_IME_BUTTON, vis, backDisposition, token);
            m.getData().putBoolean(SHOW_IME_SWITCHER_KEY, showImeSwitcher);
            m.sendToTarget();
        }
    }

    public void showRecentApps(boolean triggeredFromAltTab, boolean fromHome) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_RECENT_APPS);
            mHandler.obtainMessage(MSG_SHOW_RECENT_APPS,
                    triggeredFromAltTab ? 1 : 0, fromHome ? 1 : 0, null).sendToTarget();
        }
    }

    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_HIDE_RECENT_APPS);
            mHandler.obtainMessage(MSG_HIDE_RECENT_APPS,
                    triggeredFromAltTab ? 1 : 0, triggeredFromHomeKey ? 1 : 0,
                    null).sendToTarget();
        }
    }

    public void toggleSplitScreen() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_APP_SPLIT_SCREEN);
            mHandler.obtainMessage(MSG_TOGGLE_APP_SPLIT_SCREEN, 0, 0, null).sendToTarget();
        }
    }

    public void toggleRecentApps() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_RECENT_APPS);
            mHandler.obtainMessage(MSG_TOGGLE_RECENT_APPS, 0, 0, null).sendToTarget();
        }
    }

    public void preloadRecentApps() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_PRELOAD_RECENT_APPS);
            mHandler.obtainMessage(MSG_PRELOAD_RECENT_APPS, 0, 0, null).sendToTarget();
        }
    }

    public void cancelPreloadRecentApps() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_CANCEL_PRELOAD_RECENT_APPS);
            mHandler.obtainMessage(MSG_CANCEL_PRELOAD_RECENT_APPS, 0, 0, null).sendToTarget();
        }
    }

    @Override
    public void dismissKeyboardShortcutsMenu() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_DISMISS_KEYBOARD_SHORTCUTS);
            mHandler.obtainMessage(MSG_DISMISS_KEYBOARD_SHORTCUTS).sendToTarget();
        }
    }

    @Override
    public void toggleKeyboardShortcutsMenu(int deviceId) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_KEYBOARD_SHORTCUTS);
            mHandler.obtainMessage(MSG_TOGGLE_KEYBOARD_SHORTCUTS, deviceId, 0).sendToTarget();
        }
    }

    @Override
    public void showTvPictureInPictureMenu() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_TV_PICTURE_IN_PICTURE_MENU);
            mHandler.obtainMessage(MSG_SHOW_TV_PICTURE_IN_PICTURE_MENU).sendToTarget();
        }
    }

    public void setWindowState(int window, int state) {
        synchronized (mLock) {
            // don't coalesce these
            mHandler.obtainMessage(MSG_SET_WINDOW_STATE, window, state, null).sendToTarget();
        }
    }

    public void buzzBeepBlinked() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_BUZZ_BEEP_BLINKED);
            mHandler.sendEmptyMessage(MSG_BUZZ_BEEP_BLINKED);
        }
    }

    public void notificationLightOff() {
        synchronized (mLock) {
            mHandler.sendEmptyMessage(MSG_NOTIFICATION_LIGHT_OFF);
        }
    }

    public void notificationLightPulse(int argb, int onMillis, int offMillis) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_NOTIFICATION_LIGHT_PULSE, onMillis, offMillis, argb)
                    .sendToTarget();
        }
    }

    public void showScreenPinningRequest(int taskId) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SHOW_SCREEN_PIN_REQUEST, taskId, 0, null)
                    .sendToTarget();
        }
    }

    public void appTransitionPending() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_APP_TRANSITION_PENDING);
            mHandler.sendEmptyMessage(MSG_APP_TRANSITION_PENDING);
        }
    }

    public void appTransitionCancelled() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_APP_TRANSITION_PENDING);
            mHandler.sendEmptyMessage(MSG_APP_TRANSITION_PENDING);
        }
    }

    public void appTransitionStarting(long startTime, long duration) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_APP_TRANSITION_STARTING);
            mHandler.obtainMessage(MSG_APP_TRANSITION_STARTING, Pair.create(startTime, duration))
                    .sendToTarget();
        }
    }

    @Override
    public void appTransitionFinished() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_APP_TRANSITION_FINISHED);
            mHandler.sendEmptyMessage(MSG_APP_TRANSITION_FINISHED);
        }
    }

    public void showAssistDisclosure() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_ASSIST_DISCLOSURE);
            mHandler.obtainMessage(MSG_ASSIST_DISCLOSURE).sendToTarget();
        }
    }

    public void startAssist(Bundle args) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_START_ASSIST);
            mHandler.obtainMessage(MSG_START_ASSIST, args).sendToTarget();
        }
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_CAMERA_LAUNCH_GESTURE);
            mHandler.obtainMessage(MSG_CAMERA_LAUNCH_GESTURE, source, 0).sendToTarget();
        }
    }

    @Override
    public void addQsTile(ComponentName tile) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_ADD_QS_TILE, tile).sendToTarget();
        }
    }

    @Override
    public void remQsTile(ComponentName tile) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_REMOVE_QS_TILE, tile).sendToTarget();
        }
    }

    @Override
    public void clickQsTile(ComponentName tile) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_CLICK_QS_TILE, tile).sendToTarget();
        }
    }

    @Override
    public void handleSystemNavigationKey(int key) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_HANDLE_SYSNAV_KEY, key, 0).sendToTarget();
        }
    }

    private final class H extends Handler {
        public void handleMessage(Message msg) {
            final int what = msg.what & MSG_MASK;
            switch (what) {
                case MSG_ICON: {
                    switch (msg.arg1) {
                        case OP_SET_ICON: {
                            Pair<String, StatusBarIcon> p = (Pair<String, StatusBarIcon>) msg.obj;
                            mCallbacks.setIcon(p.first, p.second);
                            break;
                        }
                        case OP_REMOVE_ICON:
                            mCallbacks.removeIcon((String) msg.obj);
                            break;
                    }
                    break;
                }
                case MSG_DISABLE:
                    mCallbacks.disable(msg.arg1, msg.arg2, true /* animate */);
                    break;
                case MSG_EXPAND_NOTIFICATIONS:
                    mCallbacks.animateExpandNotificationsPanel();
                    break;
                case MSG_COLLAPSE_PANELS:
                    mCallbacks.animateCollapsePanels(0);
                    break;
                case MSG_EXPAND_SETTINGS:
                    mCallbacks.animateExpandSettingsPanel((String) msg.obj);
                    break;
                case MSG_SET_SYSTEMUI_VISIBILITY:
                    SomeArgs args = (SomeArgs) msg.obj;
                    mCallbacks.setSystemUiVisibility(args.argi1, args.argi2, args.argi3,
                            args.argi4, (Rect) args.arg1, (Rect) args.arg2);
                    args.recycle();
                    break;
                case MSG_TOP_APP_WINDOW_CHANGED:
                    mCallbacks.topAppWindowChanged(msg.arg1 != 0);
                    break;
                case MSG_SHOW_IME_BUTTON:
                    mCallbacks.setImeWindowStatus((IBinder) msg.obj, msg.arg1, msg.arg2,
                            msg.getData().getBoolean(SHOW_IME_SWITCHER_KEY, false));
                    break;
                case MSG_SHOW_RECENT_APPS:
                    mCallbacks.showRecentApps(msg.arg1 != 0, msg.arg2 != 0);
                    break;
                case MSG_HIDE_RECENT_APPS:
                    mCallbacks.hideRecentApps(msg.arg1 != 0, msg.arg2 != 0);
                    break;
                case MSG_TOGGLE_RECENT_APPS:
                    mCallbacks.toggleRecentApps();
                    break;
                case MSG_PRELOAD_RECENT_APPS:
                    mCallbacks.preloadRecentApps();
                    break;
                case MSG_CANCEL_PRELOAD_RECENT_APPS:
                    mCallbacks.cancelPreloadRecentApps();
                    break;
                case MSG_DISMISS_KEYBOARD_SHORTCUTS:
                    mCallbacks.dismissKeyboardShortcutsMenu();
                    break;
                case MSG_TOGGLE_KEYBOARD_SHORTCUTS:
                    mCallbacks.toggleKeyboardShortcutsMenu(msg.arg1);
                    break;
                case MSG_SET_WINDOW_STATE:
                    mCallbacks.setWindowState(msg.arg1, msg.arg2);
                    break;
                case MSG_BUZZ_BEEP_BLINKED:
                    mCallbacks.buzzBeepBlinked();
                    break;
                case MSG_NOTIFICATION_LIGHT_OFF:
                    mCallbacks.notificationLightOff();
                    break;
                case MSG_NOTIFICATION_LIGHT_PULSE:
                    mCallbacks.notificationLightPulse((Integer) msg.obj, msg.arg1, msg.arg2);
                    break;
                case MSG_SHOW_SCREEN_PIN_REQUEST:
                    mCallbacks.showScreenPinningRequest(msg.arg1);
                    break;
                case MSG_APP_TRANSITION_PENDING:
                    mCallbacks.appTransitionPending();
                    break;
                case MSG_APP_TRANSITION_CANCELLED:
                    mCallbacks.appTransitionCancelled();
                    break;
                case MSG_APP_TRANSITION_STARTING:
                    Pair<Long, Long> data = (Pair<Long, Long>) msg.obj;
                    mCallbacks.appTransitionStarting(data.first, data.second);
                    break;
                case MSG_APP_TRANSITION_FINISHED:
                    mCallbacks.appTransitionFinished();
                    break;
                case MSG_ASSIST_DISCLOSURE:
                    mCallbacks.showAssistDisclosure();
                    break;
                case MSG_START_ASSIST:
                    mCallbacks.startAssist((Bundle) msg.obj);
                    break;
                case MSG_CAMERA_LAUNCH_GESTURE:
                    mCallbacks.onCameraLaunchGestureDetected(msg.arg1);
                    break;
                case MSG_SHOW_TV_PICTURE_IN_PICTURE_MENU:
                    mCallbacks.showTvPictureInPictureMenu();
                    break;
                case MSG_ADD_QS_TILE:
                    mCallbacks.addQsTile((ComponentName) msg.obj);
                    break;
                case MSG_REMOVE_QS_TILE:
                    mCallbacks.remQsTile((ComponentName) msg.obj);
                    break;
                case MSG_CLICK_QS_TILE:
                    mCallbacks.clickTile((ComponentName) msg.obj);
                    break;
                case MSG_TOGGLE_APP_SPLIT_SCREEN:
                    mCallbacks.toggleSplitScreen();
                    break;
                case MSG_HANDLE_SYSNAV_KEY:
                    mCallbacks.handleSystemNavigationKey(msg.arg1);
                    break;
            }
        }
    }
}

