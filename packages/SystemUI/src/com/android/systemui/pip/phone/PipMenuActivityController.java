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
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.IWindowManager;

import com.android.systemui.pip.phone.PipMediaController.ActionListener;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the PiP menu activity.
 *
 * The current media session provides actions whenever there are no valid actions provided by the
 * current PiP activity. Otherwise, those actions always take precedence.
 */
public class PipMenuActivityController {

    private static final String TAG = "PipMenuActController";

    public static final String EXTRA_CONTROLLER_MESSENGER = "messenger";
    public static final String EXTRA_ACTIONS = "actions";

    public static final int MESSAGE_MENU_VISIBILITY_CHANGED = 100;
    public static final int MESSAGE_EXPAND_PIP = 101;
    public static final int MESSAGE_MINIMIZE_PIP = 102;
    public static final int MESSAGE_DISMISS_PIP = 103;
    public static final int MESSAGE_UPDATE_ACTIVITY_CALLBACK = 104;
    public static final int MESSAGE_REGISTER_INPUT_CONSUMER = 105;

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Called when the PIP menu visibility changes.
         *
         * @param menuVisible whether or not the menu is visible
         * @param resize whether or not to resize the PiP with the visibility change
         */
        void onPipMenuVisibilityChanged(boolean menuVisible, boolean resize);

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
    }

    private Context mContext;
    private IActivityManager mActivityManager;
    private PipMediaController mMediaController;
    private InputConsumerController mInputConsumerController;

    private ArrayList<Listener> mListeners = new ArrayList<>();
    private ParceledListSlice mAppActions;
    private ParceledListSlice mMediaActions;
    private boolean mMenuVisible;

    private Messenger mToActivityMessenger;
    private Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_MENU_VISIBILITY_CHANGED: {
                    boolean visible = msg.arg1 > 0;
                    onMenuVisibilityChanged(visible, true /* resize */);
                    break;
                }
                case MESSAGE_EXPAND_PIP: {
                    mListeners.forEach(l -> l.onPipExpand());
                    // Preemptively mark the menu as invisible once we expand the PiP, but don't
                    // resize as we will be animating the stack
                    onMenuVisibilityChanged(false, false /* resize */);
                    break;
                }
                case MESSAGE_MINIMIZE_PIP: {
                    mListeners.forEach(l -> l.onPipMinimize());
                    break;
                }
                case MESSAGE_DISMISS_PIP: {
                    mListeners.forEach(l -> l.onPipDismiss());
                    // Preemptively mark the menu as invisible once we dismiss the PiP, but don't
                    // resize as we'll be removing the stack in place
                    onMenuVisibilityChanged(false, false /* resize */);
                    break;
                }
                case MESSAGE_REGISTER_INPUT_CONSUMER: {
                    mInputConsumerController.registerInputConsumer();
                    break;
                }
                case MESSAGE_UPDATE_ACTIVITY_CALLBACK: {
                    mToActivityMessenger = msg.replyTo;
                    // Mark the menu as invisible once the activity finishes as well
                    if (mToActivityMessenger == null) {
                        onMenuVisibilityChanged(false, true /* resize */);
                    }
                    break;
                }
            }
        }
    });

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
    }

    public void onActivityPinned() {
        if (!mMenuVisible) {
            // If the menu is not visible, then re-register the input consumer if it is not already
            // registered
            mInputConsumerController.registerInputConsumer();
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
     * Shows the menu activity.
     */
    public void showMenu() {
        if (mToActivityMessenger != null) {
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_SHOW_MENU;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify menu to show", e);
            }
        } else {
            // Start the menu activity on the top task of the pinned stack
            try {
                StackInfo pinnedStackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
                if (pinnedStackInfo != null && pinnedStackInfo.taskIds != null &&
                        pinnedStackInfo.taskIds.length > 0) {
                    Intent intent = new Intent(mContext, PipMenuActivity.class);
                    intent.putExtra(EXTRA_CONTROLLER_MESSENGER, mMessenger);
                    intent.putExtra(EXTRA_ACTIONS, resolveMenuActions());
                    ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
                    options.setLaunchTaskId(
                            pinnedStackInfo.taskIds[pinnedStackInfo.taskIds.length - 1]);
                    options.setTaskOverlay(true, true /* canResume */);
                    mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
                } else {
                    Log.e(TAG, "No PIP tasks found");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error showing PIP menu activity", e);
            }
        }
    }

    /**
     * Pokes the menu, indicating that the user is interacting with it.
     */
    public void pokeMenu() {
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
     * @return whether the menu is currently visible.
     */
    public boolean isMenuVisible() {
        return mMenuVisible;
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
     * Updates the PiP menu activity with the best set of actions provided.
     */
    private void updateMenuActions() {
        if (mToActivityMessenger != null) {
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_UPDATE_ACTIONS;
            m.obj = resolveMenuActions();
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
     * Handles changes in menu visibility.
     */
    private void onMenuVisibilityChanged(boolean visible, boolean resize) {
        if (visible) {
            mInputConsumerController.unregisterInputConsumer();
        } else {
            mInputConsumerController.registerInputConsumer();
        }
        if (visible != mMenuVisible) {
            mListeners.forEach(l -> l.onPipMenuVisibilityChanged(visible, resize));
            if (visible) {
                // Once visible, start listening for media action changes. This call will trigger
                // the menu actions to be updated again.
                mMediaController.addListener(mMediaActionListener);
            } else {
                // Once hidden, stop listening for media action changes. This call will trigger
                // the menu actions to be updated again.
                mMediaController.removeListener(mMediaActionListener);
            }
        }
        mMenuVisible = visible;
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMenuVisible=" + mMenuVisible);
        pw.println(innerPrefix + "mListeners=" + mListeners.size());
    }
}
