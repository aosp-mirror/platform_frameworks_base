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

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;

import android.app.ActivityManager.StackInfo;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.IWindowManager;

import com.android.systemui.pip.phone.PipMediaController.ActionListener;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.component.HidePipMenuEvent;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;

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

    public static final String EXTRA_CONTROLLER_MESSENGER = "messenger";
    public static final String EXTRA_ACTIONS = "actions";
    public static final String EXTRA_STACK_BOUNDS = "stack_bounds";
    public static final String EXTRA_MOVEMENT_BOUNDS = "movement_bounds";
    public static final String EXTRA_ALLOW_TIMEOUT = "allow_timeout";
    public static final String EXTRA_WILL_RESIZE_MENU = "resize_menu_on_show";
    public static final String EXTRA_DISMISS_FRACTION = "dismiss_fraction";
    public static final String EXTRA_MENU_STATE = "menu_state";

    public static final int MESSAGE_MENU_STATE_CHANGED = 100;
    public static final int MESSAGE_EXPAND_PIP = 101;
    public static final int MESSAGE_MINIMIZE_PIP = 102;
    public static final int MESSAGE_DISMISS_PIP = 103;
    public static final int MESSAGE_UPDATE_ACTIVITY_CALLBACK = 104;
    public static final int MESSAGE_REGISTER_INPUT_CONSUMER = 105;
    public static final int MESSAGE_UNREGISTER_INPUT_CONSUMER = 106;
    public static final int MESSAGE_SHOW_MENU = 107;

    public static final int MENU_STATE_NONE = 0;
    public static final int MENU_STATE_CLOSE = 1;
    public static final int MENU_STATE_FULL = 2;

    // The duration to wait before we consider the start activity as having timed out
    private static final long START_ACTIVITY_REQUEST_TIMEOUT_MS = 300;

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
        void onPipMenuStateChanged(int menuState, boolean resize);

        /**
         * Called when the PIP requested to be expanded.
         */
        void onPipExpand();

        /**
         * Called when the PIP requested to be minimized.
         */
        void onPipMinimize();

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
    private IActivityManager mActivityManager;
    private PipMediaController mMediaController;
    private InputConsumerController mInputConsumerController;

    private ArrayList<Listener> mListeners = new ArrayList<>();
    private ParceledListSlice mAppActions;
    private ParceledListSlice mMediaActions;
    private int mMenuState;

    // The dismiss fraction update is sent frequently, so use a temporary bundle for the message
    private Bundle mTmpDismissFractionData = new Bundle();

    private ReferenceCountedTrigger mOnAttachDecrementTrigger;
    private boolean mStartActivityRequested;
    private long mStartActivityRequestedTime;
    private Messenger mToActivityMessenger;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_MENU_STATE_CHANGED: {
                    int menuState = msg.arg1;
                    onMenuStateChanged(menuState, true /* resize */);
                    break;
                }
                case MESSAGE_EXPAND_PIP: {
                    mListeners.forEach(l -> l.onPipExpand());
                    break;
                }
                case MESSAGE_MINIMIZE_PIP: {
                    mListeners.forEach(l -> l.onPipMinimize());
                    break;
                }
                case MESSAGE_DISMISS_PIP: {
                    mListeners.forEach(l -> l.onPipDismiss());
                    break;
                }
                case MESSAGE_SHOW_MENU: {
                    mListeners.forEach(l -> l.onPipShowMenu());
                    break;
                }
                case MESSAGE_REGISTER_INPUT_CONSUMER: {
                    mInputConsumerController.registerInputConsumer();
                    break;
                }
                case MESSAGE_UNREGISTER_INPUT_CONSUMER: {
                    mInputConsumerController.unregisterInputConsumer();
                    break;
                }
                case MESSAGE_UPDATE_ACTIVITY_CALLBACK: {
                    mToActivityMessenger = msg.replyTo;
                    setStartActivityRequested(false);
                    if (mOnAttachDecrementTrigger != null) {
                        mOnAttachDecrementTrigger.decrement();
                        mOnAttachDecrementTrigger = null;
                    }
                    // Mark the menu as invisible once the activity finishes as well
                    if (mToActivityMessenger == null) {
                        onMenuStateChanged(MENU_STATE_NONE, true /* resize */);
                    }
                    break;
                }
            }
        }
    };
    private Messenger mMessenger = new Messenger(mHandler);

    private Runnable mStartActivityRequestedTimeoutRunnable = () -> {
        setStartActivityRequested(false);
        if (mOnAttachDecrementTrigger != null) {
            mOnAttachDecrementTrigger.decrement();
            mOnAttachDecrementTrigger = null;
        }
        Log.e(TAG, "Expected start menu activity request timed out");
    };

    private ActionListener mMediaActionListener = new ActionListener() {
        @Override
        public void onMediaActionsChanged(List<RemoteAction> mediaActions) {
            mMediaActions = new ParceledListSlice<>(mediaActions);
            updateMenuActions();
        }
    };

    public PipMenuActivityController(Context context, IActivityManager activityManager,
            PipMediaController mediaController, InputConsumerController inputConsumerController) {
        mContext = context;
        mActivityManager = activityManager;
        mMediaController = mediaController;
        mInputConsumerController = inputConsumerController;

        EventBus.getDefault().register(this);
    }

    public boolean isMenuActivityVisible() {
        return mToActivityMessenger != null;
    }

    public void onActivityPinned() {
        if (mMenuState == MENU_STATE_NONE) {
            // If the menu is not visible, then re-register the input consumer if it is not already
            // registered
            mInputConsumerController.registerInputConsumer();
        }
    }

    public void onActivityUnpinned() {
        hideMenu();
        setStartActivityRequested(false);
    }

    public void onPinnedStackAnimationEnded() {
        // Note: Only active menu activities care about this event
        if (mToActivityMessenger != null) {
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_ANIMATION_ENDED;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify menu pinned animation ended", e);
            }
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
        if (DEBUG) {
            Log.d(TAG, "setDismissFraction() hasActivity=" + (mToActivityMessenger != null)
                    + " fraction=" + fraction);
        }
        if (mToActivityMessenger != null) {
            mTmpDismissFractionData.clear();
            mTmpDismissFractionData.putFloat(EXTRA_DISMISS_FRACTION, fraction);
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_UPDATE_DISMISS_FRACTION;
            m.obj = mTmpDismissFractionData;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify menu to update dismiss fraction", e);
            }
        } else if (!mStartActivityRequested || isStartActivityRequestedElapsed()) {
            // If we haven't requested the start activity, or if it previously took too long to
            // start, then start it
            startMenuActivity(MENU_STATE_NONE, null /* stackBounds */,
                    null /* movementBounds */, false /* allowMenuTimeout */,
                    false /* resizeMenuOnShow */);
        }
    }

    /**
     * Shows the menu activity.
     */
    public void showMenu(int menuState, Rect stackBounds, Rect movementBounds,
            boolean allowMenuTimeout, boolean willResizeMenu) {
        if (DEBUG) {
            Log.d(TAG, "showMenu() state=" + menuState
                    + " hasActivity=" + (mToActivityMessenger != null)
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }

        if (mToActivityMessenger != null) {
            Bundle data = new Bundle();
            data.putInt(EXTRA_MENU_STATE, menuState);
            data.putParcelable(EXTRA_STACK_BOUNDS, stackBounds);
            data.putParcelable(EXTRA_MOVEMENT_BOUNDS, movementBounds);
            data.putBoolean(EXTRA_ALLOW_TIMEOUT, allowMenuTimeout);
            data.putBoolean(EXTRA_WILL_RESIZE_MENU, willResizeMenu);
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_SHOW_MENU;
            m.obj = data;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify menu to show", e);
            }
        } else if (!mStartActivityRequested || isStartActivityRequestedElapsed()) {
            // If we haven't requested the start activity, or if it previously took too long to
            // start, then start it
            startMenuActivity(menuState, stackBounds, movementBounds, allowMenuTimeout,
                    willResizeMenu);
        }
    }

    /**
     * Pokes the menu, indicating that the user is interacting with it.
     */
    public void pokeMenu() {
        if (DEBUG) {
            Log.d(TAG, "pokeMenu() hasActivity=" + (mToActivityMessenger != null));
        }
        if (mToActivityMessenger != null) {
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_POKE_MENU;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify poke menu", e);
            }
        }
    }

    /**
     * Hides the menu activity.
     */
    public void hideMenu() {
        if (DEBUG) {
            Log.d(TAG, "hideMenu() state=" + mMenuState
                    + " hasActivity=" + (mToActivityMessenger != null)
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (mToActivityMessenger != null) {
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_HIDE_MENU;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify menu to hide", e);
            }
        }
    }

    /**
     * Preemptively mark the menu as invisible, used when we are directly manipulating the pinned
     * stack and don't want to trigger a resize which can animate the stack in a conflicting way
     * (ie. when manually expanding or dismissing).
     */
    public void hideMenuWithoutResize() {
        onMenuStateChanged(MENU_STATE_NONE, false /* resize */);
    }

    /**
     * Sets the menu actions to the actions provided by the current PiP activity.
     */
    public void setAppActions(ParceledListSlice appActions) {
        mAppActions = appActions;
        updateMenuActions();
    }

    /**
     * @return the best set of actions to show in the PiP menu.
     */
    private ParceledListSlice resolveMenuActions() {
        if (isValidActions(mAppActions)) {
            return mAppActions;
        }
        return mMediaActions;
    }

    /**
     * Starts the menu activity on the top task of the pinned stack.
     */
    private void startMenuActivity(int menuState, Rect stackBounds, Rect movementBounds,
            boolean allowMenuTimeout, boolean willResizeMenu) {
        try {
            StackInfo pinnedStackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
            if (pinnedStackInfo != null && pinnedStackInfo.taskIds != null &&
                    pinnedStackInfo.taskIds.length > 0) {
                Intent intent = new Intent(mContext, PipMenuActivity.class);
                intent.putExtra(EXTRA_CONTROLLER_MESSENGER, mMessenger);
                intent.putExtra(EXTRA_ACTIONS, resolveMenuActions());
                if (stackBounds != null) {
                    intent.putExtra(EXTRA_STACK_BOUNDS, stackBounds);
                }
                if (movementBounds != null) {
                    intent.putExtra(EXTRA_MOVEMENT_BOUNDS, movementBounds);
                }
                intent.putExtra(EXTRA_MENU_STATE, menuState);
                intent.putExtra(EXTRA_ALLOW_TIMEOUT, allowMenuTimeout);
                intent.putExtra(EXTRA_WILL_RESIZE_MENU, willResizeMenu);
                ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
                options.setLaunchTaskId(
                        pinnedStackInfo.taskIds[pinnedStackInfo.taskIds.length - 1]);
                options.setTaskOverlay(true, true /* canResume */);
                mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
                setStartActivityRequested(true);
            } else {
                Log.e(TAG, "No PIP tasks found");
            }
        } catch (RemoteException e) {
            setStartActivityRequested(false);
            Log.e(TAG, "Error showing PIP menu activity", e);
        }
    }

    /**
     * Updates the PiP menu activity with the best set of actions provided.
     */
    private void updateMenuActions() {
        if (mToActivityMessenger != null) {
            // Fetch the pinned stack bounds
            Rect stackBounds = null;
            try {
                StackInfo pinnedStackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
                if (pinnedStackInfo != null) {
                    stackBounds = pinnedStackInfo.bounds;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error showing PIP menu activity", e);
            }

            Bundle data = new Bundle();
            data.putParcelable(EXTRA_STACK_BOUNDS, stackBounds);
            data.putParcelable(EXTRA_ACTIONS, resolveMenuActions());
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_UPDATE_ACTIONS;
            m.obj = data;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify menu activity to update actions", e);
            }
        }
    }

    /**
     * Returns whether the set of actions are valid.
     */
    private boolean isValidActions(ParceledListSlice actions) {
        return actions != null && actions.getList().size() > 0;
    }

    /**
     * @return whether the time of the activity request has exceeded the timeout.
     */
    private boolean isStartActivityRequestedElapsed() {
        return (SystemClock.uptimeMillis() - mStartActivityRequestedTime)
                >= START_ACTIVITY_REQUEST_TIMEOUT_MS;
    }

    /**
     * Handles changes in menu visibility.
     */
    private void onMenuStateChanged(int menuState, boolean resize) {
        if (DEBUG) {
            Log.d(TAG, "onMenuStateChanged() mMenuState=" + mMenuState
                    + " menuState=" + menuState + " resize=" + resize);
        }
        if (menuState == MENU_STATE_NONE) {
            mInputConsumerController.registerInputConsumer();
        } else {
            mInputConsumerController.unregisterInputConsumer();
        }
        if (menuState != mMenuState) {
            mListeners.forEach(l -> l.onPipMenuStateChanged(menuState, resize));
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

    private void setStartActivityRequested(boolean requested) {
        mHandler.removeCallbacks(mStartActivityRequestedTimeoutRunnable);
        mStartActivityRequested = requested;
        mStartActivityRequestedTime = requested ? SystemClock.uptimeMillis() : 0;
    }

    public final void onBusEvent(HidePipMenuEvent event) {
        if (mStartActivityRequested) {
            // If the menu has been start-requested, but not actually started, then we defer the
            // trigger callback until the menu has started and called back to the controller.
            mOnAttachDecrementTrigger = event.getAnimationTrigger();
            mOnAttachDecrementTrigger.increment();

            // Fallback for b/63752800, we have started the PipMenuActivity but it has not made any
            // callbacks. Don't continue to wait for the menu to show past some timeout.
            mHandler.removeCallbacks(mStartActivityRequestedTimeoutRunnable);
            mHandler.postDelayed(mStartActivityRequestedTimeoutRunnable,
                    START_ACTIVITY_REQUEST_TIMEOUT_MS);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMenuState=" + mMenuState);
        pw.println(innerPrefix + "mToActivityMessenger=" + mToActivityMessenger);
        pw.println(innerPrefix + "mListeners=" + mListeners.size());
        pw.println(innerPrefix + "mStartActivityRequested=" + mStartActivityRequested);
        pw.println(innerPrefix + "mStartActivityRequestedTime=" + mStartActivityRequestedTime);
    }
}
