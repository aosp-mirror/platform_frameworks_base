/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.RemoteAction;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Debug;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.pip.phone.PipMediaController.ActionListener;
import com.android.systemui.shared.system.InputConsumerController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the PiP menu activity which can show menu options or a scrim.
 *
 * The current media session provides actions whenever there are no valid actions provided by the
 * current PiP activity. Otherwise, those actions always take precedence.
 */
public class PipMenuActivityController {

    private static final String TAG = "PipMenuActController";
    private static final boolean DEBUG = false;

    public static final int MENU_STATE_NONE = 0;
    public static final int MENU_STATE_CLOSE = 1;
    public static final int MENU_STATE_FULL = 2;

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Called when the PIP menu visibility changes.
         *
         * @param menuState the current state of the menu
         * @param resize whether or not to resize the PiP with the state change
         */
        void onPipMenuStateChanged(int menuState, boolean resize, Runnable callback);

        /**
         * Called when the PIP requested to be expanded.
         */
        void onPipExpand();

        /**
         * Called when the PIP requested to be dismissed.
         */
        void onPipDismiss();

        /**
         * Called when the PIP requested to show the menu.
         */
        void onPipShowMenu();
    }

    private Context mContext;
    private PipTaskOrganizer mPipTaskOrganizer;
    private PipMediaController mMediaController;
    private InputConsumerController mInputConsumerController;

    private ArrayList<Listener> mListeners = new ArrayList<>();
    private ParceledListSlice<RemoteAction> mAppActions;
    private ParceledListSlice<RemoteAction> mMediaActions;
    private int mMenuState;

    private PipMenuView mPipMenuView;

    private ActionListener mMediaActionListener = new ActionListener() {
        @Override
        public void onMediaActionsChanged(List<RemoteAction> mediaActions) {
            mMediaActions = new ParceledListSlice<>(mediaActions);
            updateMenuActions();
        }
    };

    public PipMenuActivityController(Context context,
            PipMediaController mediaController, InputConsumerController inputConsumerController,
            PipTaskOrganizer pipTaskOrganizer
    ) {
        mContext = context;
        mMediaController = mediaController;
        mInputConsumerController = inputConsumerController;
        mPipTaskOrganizer = pipTaskOrganizer;
    }

    public boolean isMenuVisible() {
        return mPipMenuView != null && mMenuState != MENU_STATE_NONE;
    }

    public void onActivityPinned() {
        if (mPipMenuView == null) {
            WindowManager.LayoutParams lp =
                    getPipMenuLayoutParams(0, 0);
            mPipMenuView = new PipMenuView(mContext, this);
            mPipTaskOrganizer.attachPipMenuViewHost(mPipMenuView, lp);
        }
        mInputConsumerController.registerInputConsumer(true /* withSfVsync */);
    }

    public void onActivityUnpinned() {
        hideMenu();
        mInputConsumerController.unregisterInputConsumer();
        mPipTaskOrganizer.detachPipMenuViewHost();
        mPipMenuView = null;
    }

    public void onPinnedStackAnimationEnded() {
        if (isMenuVisible()) {
            mPipMenuView.onPipAnimationEnded();
        }
    }

    /**
     * Adds a new menu activity listener.
     */
    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * Updates the appearance of the menu and scrim on top of the PiP while dismissing.
     */
    public void setDismissFraction(float fraction) {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "setDismissFraction() isMenuVisible=" + isMenuVisible
                    + " fraction=" + fraction);
        }
        if (isMenuVisible) {
            mPipMenuView.updateDismissFraction(fraction);
        }
    }

    /**
     * Similar to {@link #showMenu(int, Rect, boolean, boolean, boolean)} but only show the menu
     * upon PiP window transition is finished.
     */
    public void showMenuWithDelay(int menuState, Rect stackBounds, boolean allowMenuTimeout,
            boolean willResizeMenu, boolean showResizeHandle) {
        // hide all visible controls including close button and etc. first, this is to ensure
        // menu is totally invisible during the transition to eliminate unpleasant artifacts
        fadeOutMenu();
        showMenuInternal(menuState, stackBounds, allowMenuTimeout, willResizeMenu,
                true /* withDelay */, showResizeHandle);
    }

    /**
     * Shows the menu activity immediately.
     */
    public void showMenu(int menuState, Rect stackBounds, boolean allowMenuTimeout,
            boolean willResizeMenu, boolean showResizeHandle) {
        showMenuInternal(menuState, stackBounds, allowMenuTimeout, willResizeMenu,
                false /* withDelay */, showResizeHandle);
    }

    private void showMenuInternal(int menuState, Rect stackBounds, boolean allowMenuTimeout,
            boolean willResizeMenu, boolean withDelay, boolean showResizeHandle) {
        if (DEBUG) {
            Log.d(TAG, "showMenu() state=" + menuState
                    + " isMenuVisible=" + isMenuVisible()
                    + " allowMenuTimeout=" + allowMenuTimeout
                    + " willResizeMenu=" + willResizeMenu
                    + " withDelay=" + withDelay
                    + " showResizeHandle=" + showResizeHandle
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }

        if (mPipMenuView == null) {
            Log.e(TAG, "PipMenu has not been attached yet.");
            return;
        }
        mPipMenuView.showMenu(menuState, stackBounds, allowMenuTimeout, willResizeMenu, withDelay,
                showResizeHandle);
    }

    /**
     * Pokes the menu, indicating that the user is interacting with it.
     */
    public void pokeMenu() {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "pokeMenu() isMenuVisible=" + isMenuVisible);
        }
        if (isMenuVisible) {
            mPipMenuView.pokeMenu();
        }
    }

    private void fadeOutMenu() {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "fadeOutMenu() isMenuVisible=" + isMenuVisible);
        }
        if (isMenuVisible) {
            mPipMenuView.fadeOutMenu();
        }
    }

    /**
     * Hides the menu activity.
     */
    public void hideMenu() {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "hideMenu() state=" + mMenuState
                    + " isMenuVisible=" + isMenuVisible
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (isMenuVisible) {
            mPipMenuView.hideMenu();
        }
    }

    /**
     * Hides the menu activity.
     */
    public void hideMenu(Runnable onStartCallback, Runnable onEndCallback) {
        if (isMenuVisible()) {
            // If the menu is visible in either the closed or full state, then hide the menu and
            // trigger the animation trigger afterwards
            onStartCallback.run();
            mPipMenuView.hideMenu(onEndCallback);
        }
    }

    /**
     * Preemptively mark the menu as invisible, used when we are directly manipulating the pinned
     * stack and don't want to trigger a resize which can animate the stack in a conflicting way
     * (ie. when manually expanding or dismissing).
     */
    public void hideMenuWithoutResize() {
        onMenuStateChanged(MENU_STATE_NONE, false /* resize */, null /* callback */);
    }

    /**
     * Sets the menu actions to the actions provided by the current PiP activity.
     */
    public void setAppActions(ParceledListSlice<RemoteAction> appActions) {
        mAppActions = appActions;
        updateMenuActions();
    }

    void onPipExpand() {
        mListeners.forEach(Listener::onPipExpand);
    }

    void onPipDismiss() {
        mListeners.forEach(Listener::onPipDismiss);
    }

    void onPipShowMenu() {
        mListeners.forEach(Listener::onPipShowMenu);
    }

    /**
     * @return the best set of actions to show in the PiP menu.
     */
    private ParceledListSlice<RemoteAction> resolveMenuActions() {
        if (isValidActions(mAppActions)) {
            return mAppActions;
        }
        return mMediaActions;
    }

    /**
     * Returns a default LayoutParams for the PIP Menu.
     * @param width the PIP stack width.
     * @param height the PIP stack height.
     */
    public static WindowManager.LayoutParams getPipMenuLayoutParams(int width, int height) {
        return new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSLUCENT);
    }

    /**
     * Updates the PiP menu with the best set of actions provided.
     */
    private void updateMenuActions() {
        if (isMenuVisible()) {
            // Fetch the pinned stack bounds
            Rect stackBounds = null;
            try {
                RootTaskInfo pinnedTaskInfo = ActivityTaskManager.getService().getRootTaskInfo(
                        WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
                if (pinnedTaskInfo != null) {
                    stackBounds = pinnedTaskInfo.bounds;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error showing PIP menu", e);
            }

            mPipMenuView.setActions(stackBounds, resolveMenuActions().getList());
        }
    }

    /**
     * Returns whether the set of actions are valid.
     */
    private static boolean isValidActions(ParceledListSlice<?> actions) {
        return actions != null && actions.getList().size() > 0;
    }

    /**
     * Handles changes in menu visibility.
     */
    void onMenuStateChanged(int menuState, boolean resize, Runnable callback) {
        if (DEBUG) {
            Log.d(TAG, "onMenuStateChanged() mMenuState=" + mMenuState
                    + " menuState=" + menuState + " resize=" + resize
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }

        if (menuState != mMenuState) {
            mListeners.forEach(l -> l.onPipMenuStateChanged(menuState, resize, callback));
            if (menuState == MENU_STATE_FULL) {
                // Once visible, start listening for media action changes. This call will trigger
                // the menu actions to be updated again.
                mMediaController.addListener(mMediaActionListener);
            } else {
                // Once hidden, stop listening for media action changes. This call will trigger
                // the menu actions to be updated again.
                mMediaController.removeListener(mMediaActionListener);
            }
        }
        mMenuState = menuState;
    }

    /**
     * Handles a pointer event sent from pip input consumer.
     */
    void handlePointerEvent(MotionEvent ev) {
        if (ev.isTouchEvent()) {
            mPipMenuView.dispatchTouchEvent(ev);
        } else {
            mPipMenuView.dispatchGenericMotionEvent(ev);
        }
    }

    /**
     * Tell the PIP Menu to recalculate its layout given its current position on the display.
     */
    public void updateMenuLayout(Rect bounds) {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "updateMenuLayout() state=" + mMenuState
                    + " isMenuVisible=" + isMenuVisible
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (isMenuVisible) {
            mPipMenuView.updateMenuLayout(bounds);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMenuState=" + mMenuState);
        pw.println(innerPrefix + "mPipMenuView=" + mPipMenuView);
        pw.println(innerPrefix + "mListeners=" + mListeners.size());
    }
}
