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
import android.os.Looper;
import android.os.Message;
import android.support.annotation.VisibleForTesting;
import android.util.Pair;

import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.SystemUI;

import java.util.ArrayList;

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
    private static final int MSG_SHOW_SCREEN_PIN_REQUEST       = 18 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_PENDING        = 19 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_CANCELLED      = 20 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_STARTING       = 21 << MSG_SHIFT;
    private static final int MSG_ASSIST_DISCLOSURE             = 22 << MSG_SHIFT;
    private static final int MSG_START_ASSIST                  = 23 << MSG_SHIFT;
    private static final int MSG_CAMERA_LAUNCH_GESTURE         = 24 << MSG_SHIFT;
    private static final int MSG_TOGGLE_KEYBOARD_SHORTCUTS     = 25 << MSG_SHIFT;
    private static final int MSG_SHOW_PICTURE_IN_PICTURE_MENU  = 26 << MSG_SHIFT;
    private static final int MSG_ADD_QS_TILE                   = 27 << MSG_SHIFT;
    private static final int MSG_REMOVE_QS_TILE                = 28 << MSG_SHIFT;
    private static final int MSG_CLICK_QS_TILE                 = 29 << MSG_SHIFT;
    private static final int MSG_TOGGLE_APP_SPLIT_SCREEN       = 30 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_FINISHED       = 31 << MSG_SHIFT;
    private static final int MSG_DISMISS_KEYBOARD_SHORTCUTS    = 32 << MSG_SHIFT;
    private static final int MSG_HANDLE_SYSTEM_KEY             = 33 << MSG_SHIFT;
    private static final int MSG_SHOW_GLOBAL_ACTIONS           = 34 << MSG_SHIFT;
    private static final int MSG_TOGGLE_PANEL                  = 35 << MSG_SHIFT;
    private static final int MSG_SHOW_SHUTDOWN_UI              = 36 << MSG_SHIFT;
    private static final int MSG_SET_TOP_APP_HIDES_STATUS_BAR  = 37 << MSG_SHIFT;

    public static final int FLAG_EXCLUDE_NONE = 0;
    public static final int FLAG_EXCLUDE_SEARCH_PANEL = 1 << 0;
    public static final int FLAG_EXCLUDE_RECENTS_PANEL = 1 << 1;
    public static final int FLAG_EXCLUDE_NOTIFICATION_PANEL = 1 << 2;
    public static final int FLAG_EXCLUDE_INPUT_METHODS_PANEL = 1 << 3;
    public static final int FLAG_EXCLUDE_COMPAT_MODE_PANEL = 1 << 4;

    private static final String SHOW_IME_SWITCHER_KEY = "showImeSwitcherKey";

    private final Object mLock = new Object();
    private ArrayList<Callbacks> mCallbacks = new ArrayList<>();
    private Handler mHandler = new H(Looper.getMainLooper());
    private int mDisable1;
    private int mDisable2;

    /**
     * These methods are called back on the main thread.
     */
    public interface Callbacks {
        default void setIcon(String slot, StatusBarIcon icon) { }
        default void removeIcon(String slot) { }
        default void disable(int state1, int state2, boolean animate) { }
        default void animateExpandNotificationsPanel() { }
        default void animateCollapsePanels(int flags) { }
        default void togglePanel() { }
        default void animateExpandSettingsPanel(String obj) { }
        default void setSystemUiVisibility(int vis, int fullscreenStackVis,
                int dockedStackVis, int mask, Rect fullscreenStackBounds, Rect dockedStackBounds) {
        }
        default void topAppWindowChanged(boolean visible) { }
        default void setImeWindowStatus(IBinder token, int vis, int backDisposition,
                boolean showImeSwitcher) { }
        default void showRecentApps(boolean triggeredFromAltTab, boolean fromHome) { }
        default void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) { }
        default void toggleRecentApps() { }
        default void toggleSplitScreen() { }
        default void preloadRecentApps() { }
        default void dismissKeyboardShortcutsMenu() { }
        default void toggleKeyboardShortcutsMenu(int deviceId) { }
        default void cancelPreloadRecentApps() { }
        default void setWindowState(int window, int state) { }
        default void showScreenPinningRequest(int taskId) { }
        default void appTransitionPending(boolean forced) { }
        default void appTransitionCancelled() { }
        default void appTransitionStarting(long startTime, long duration, boolean forced) { }
        default void appTransitionFinished() { }
        default void showAssistDisclosure() { }
        default void startAssist(Bundle args) { }
        default void onCameraLaunchGestureDetected(int source) { }
        default void showPictureInPictureMenu() { }
        default void setTopAppHidesStatusBar(boolean topAppHidesStatusBar) { }

        default void addQsTile(ComponentName tile) { }
        default void remQsTile(ComponentName tile) { }
        default void clickTile(ComponentName tile) { }

        default void handleSystemKey(int arg1) { }
        default void handleShowGlobalActionsMenu() { }
        default void handleShowShutdownUi(boolean isReboot, String reason) { }
    }

    @VisibleForTesting
    protected CommandQueue() {
    }

    public void addCallbacks(Callbacks callbacks) {
        mCallbacks.add(callbacks);
        callbacks.disable(mDisable1, mDisable2, false /* animate */);
    }

    public void removeCallbacks(Callbacks callbacks) {
        mCallbacks.remove(callbacks);
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

    public void disable(int state1, int state2, boolean animate) {
        synchronized (mLock) {
            mDisable1 = state1;
            mDisable2 = state2;
            mHandler.removeMessages(MSG_DISABLE);
            Message msg = mHandler.obtainMessage(MSG_DISABLE, state1, state2, animate);
            if (Looper.myLooper() == mHandler.getLooper()) {
                // If its the right looper execute immediately so hides can be handled quickly.
                mHandler.handleMessage(msg);
                msg.recycle();
            } else {
                msg.sendToTarget();
            }
        }
    }

    public void disable(int state1, int state2) {
        disable(state1, state2, true);
    }

    public void recomputeDisableFlags(boolean animate) {
        disable(mDisable1, mDisable2, animate);
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
            mHandler.obtainMessage(MSG_COLLAPSE_PANELS, 0, 0).sendToTarget();
        }
    }

    public void animateCollapsePanels(int flags) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_COLLAPSE_PANELS);
            mHandler.obtainMessage(MSG_COLLAPSE_PANELS, flags, 0).sendToTarget();
        }
    }

    public void togglePanel() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_PANEL);
            mHandler.obtainMessage(MSG_TOGGLE_PANEL, 0, 0).sendToTarget();
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
            Message msg = mHandler.obtainMessage(MSG_TOGGLE_RECENT_APPS, 0, 0, null);
            msg.setAsynchronous(true);
            msg.sendToTarget();
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
    public void showPictureInPictureMenu() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_PICTURE_IN_PICTURE_MENU);
            mHandler.obtainMessage(MSG_SHOW_PICTURE_IN_PICTURE_MENU).sendToTarget();
        }
    }

    public void setWindowState(int window, int state) {
        synchronized (mLock) {
            // don't coalesce these
            mHandler.obtainMessage(MSG_SET_WINDOW_STATE, window, state, null).sendToTarget();
        }
    }

    public void showScreenPinningRequest(int taskId) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SHOW_SCREEN_PIN_REQUEST, taskId, 0, null)
                    .sendToTarget();
        }
    }

    public void appTransitionPending() {
        appTransitionPending(false /* forced */);
    }

    public void appTransitionPending(boolean forced) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_APP_TRANSITION_PENDING, forced ? 1 : 0, 0).sendToTarget();
        }
    }

    public void appTransitionCancelled() {
        synchronized (mLock) {
            mHandler.sendEmptyMessage(MSG_APP_TRANSITION_CANCELLED);
        }
    }

    public void appTransitionStarting(long startTime, long duration) {
        appTransitionStarting(startTime, duration, false /* forced */);
    }

    public void appTransitionStarting(long startTime, long duration, boolean forced) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_APP_TRANSITION_STARTING, forced ? 1 : 0, 0,
                    Pair.create(startTime, duration)).sendToTarget();
        }
    }

    @Override
    public void appTransitionFinished() {
        synchronized (mLock) {
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
    public void handleSystemKey(int key) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_HANDLE_SYSTEM_KEY, key, 0).sendToTarget();
        }
    }

    @Override
    public void showGlobalActionsMenu() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_GLOBAL_ACTIONS);
            mHandler.obtainMessage(MSG_SHOW_GLOBAL_ACTIONS).sendToTarget();
        }
    }

    @Override
    public void setTopAppHidesStatusBar(boolean hidesStatusBar) {
        mHandler.removeMessages(MSG_SET_TOP_APP_HIDES_STATUS_BAR);
        mHandler.obtainMessage(MSG_SET_TOP_APP_HIDES_STATUS_BAR, hidesStatusBar ? 1 : 0, 0)
                .sendToTarget();
    }

    @Override
    public void showShutdownUi(boolean isReboot, String reason) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_SHUTDOWN_UI);
            mHandler.obtainMessage(MSG_SHOW_SHUTDOWN_UI, isReboot ? 1 : 0, 0, reason)
                    .sendToTarget();
        }
    }

    private final class H extends Handler {
        private H(Looper l) {
            super(l);
        }

        public void handleMessage(Message msg) {
            final int what = msg.what & MSG_MASK;
            switch (what) {
                case MSG_ICON: {
                    switch (msg.arg1) {
                        case OP_SET_ICON: {
                            Pair<String, StatusBarIcon> p = (Pair<String, StatusBarIcon>) msg.obj;
                            for (int i = 0; i < mCallbacks.size(); i++) {
                                mCallbacks.get(i).setIcon(p.first, p.second);
                            }
                            break;
                        }
                        case OP_REMOVE_ICON:
                            for (int i = 0; i < mCallbacks.size(); i++) {
                                mCallbacks.get(i).removeIcon((String) msg.obj);
                            }
                            break;
                    }
                    break;
                }
                case MSG_DISABLE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).disable(msg.arg1, msg.arg2, (Boolean) msg.obj);
                    }
                    break;
                case MSG_EXPAND_NOTIFICATIONS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).animateExpandNotificationsPanel();
                    }
                    break;
                case MSG_COLLAPSE_PANELS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).animateCollapsePanels(msg.arg1);
                    }
                    break;
                case MSG_TOGGLE_PANEL:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).togglePanel();
                    }
                    break;
                case MSG_EXPAND_SETTINGS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).animateExpandSettingsPanel((String) msg.obj);
                    }
                    break;
                case MSG_SET_SYSTEMUI_VISIBILITY:
                    SomeArgs args = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setSystemUiVisibility(args.argi1, args.argi2, args.argi3,
                                args.argi4, (Rect) args.arg1, (Rect) args.arg2);
                    }
                    args.recycle();
                    break;
                case MSG_TOP_APP_WINDOW_CHANGED:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).topAppWindowChanged(msg.arg1 != 0);
                    }
                    break;
                case MSG_SHOW_IME_BUTTON:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setImeWindowStatus((IBinder) msg.obj, msg.arg1, msg.arg2,
                                msg.getData().getBoolean(SHOW_IME_SWITCHER_KEY, false));
                    }
                    break;
                case MSG_SHOW_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showRecentApps(msg.arg1 != 0, msg.arg2 != 0);
                    }
                    break;
                case MSG_HIDE_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).hideRecentApps(msg.arg1 != 0, msg.arg2 != 0);
                    }
                    break;
                case MSG_TOGGLE_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).toggleRecentApps();
                    }
                    break;
                case MSG_PRELOAD_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).preloadRecentApps();
                    }
                    break;
                case MSG_CANCEL_PRELOAD_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).cancelPreloadRecentApps();
                    }
                    break;
                case MSG_DISMISS_KEYBOARD_SHORTCUTS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).dismissKeyboardShortcutsMenu();
                    }
                    break;
                case MSG_TOGGLE_KEYBOARD_SHORTCUTS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).toggleKeyboardShortcutsMenu(msg.arg1);
                    }
                    break;
                case MSG_SET_WINDOW_STATE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setWindowState(msg.arg1, msg.arg2);
                    }
                    break;
                case MSG_SHOW_SCREEN_PIN_REQUEST:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showScreenPinningRequest(msg.arg1);
                    }
                    break;
                case MSG_APP_TRANSITION_PENDING:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).appTransitionPending(msg.arg1 != 0);
                    }
                    break;
                case MSG_APP_TRANSITION_CANCELLED:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).appTransitionCancelled();
                    }
                    break;
                case MSG_APP_TRANSITION_STARTING:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        Pair<Long, Long> data = (Pair<Long, Long>) msg.obj;
                        mCallbacks.get(i).appTransitionStarting(data.first, data.second,
                                msg.arg1 != 0);
                    }
                    break;
                case MSG_APP_TRANSITION_FINISHED:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).appTransitionFinished();
                    }
                    break;
                case MSG_ASSIST_DISCLOSURE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showAssistDisclosure();
                    }
                    break;
                case MSG_START_ASSIST:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).startAssist((Bundle) msg.obj);
                    }
                    break;
                case MSG_CAMERA_LAUNCH_GESTURE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onCameraLaunchGestureDetected(msg.arg1);
                    }
                    break;
                case MSG_SHOW_PICTURE_IN_PICTURE_MENU:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showPictureInPictureMenu();
                    }
                    break;
                case MSG_ADD_QS_TILE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).addQsTile((ComponentName) msg.obj);
                    }
                    break;
                case MSG_REMOVE_QS_TILE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).remQsTile((ComponentName) msg.obj);
                    }
                    break;
                case MSG_CLICK_QS_TILE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).clickTile((ComponentName) msg.obj);
                    }
                    break;
                case MSG_TOGGLE_APP_SPLIT_SCREEN:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).toggleSplitScreen();
                    }
                    break;
                case MSG_HANDLE_SYSTEM_KEY:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).handleSystemKey(msg.arg1);
                    }
                    break;
                case MSG_SHOW_GLOBAL_ACTIONS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).handleShowGlobalActionsMenu();
                    }
                    break;
                case MSG_SHOW_SHUTDOWN_UI:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).handleShowShutdownUi(msg.arg1 != 0, (String) msg.obj);
                    }
                    break;
                case MSG_SET_TOP_APP_HIDES_STATUS_BAR:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setTopAppHidesStatusBar(msg.arg1 != 0);
                    }
                    break;
            }
        }
    }

    // Need this class since CommandQueue already extends IStatusBar.Stub, so CommandQueueStart
    // is needed so it can extend SystemUI.
    public static class CommandQueueStart extends SystemUI {
        @Override
        public void start() {
            putComponent(CommandQueue.class, new CommandQueue());
        }
    }
}

