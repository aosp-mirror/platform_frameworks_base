package com.android.systemui.pip.phone;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;

import android.app.ActivityManager.StackInfo;
import android.app.ActivityOptions;
import android.app.IActivityManager;
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

import java.util.ArrayList;

public class PipMenuActivityController {

    private static final String TAG = "PipMenuActivityController";

    public static final String EXTRA_CONTROLLER_MESSENGER = "messenger";
    public static final String EXTRA_ACTIONS = "actions";

    public static final int MESSAGE_MENU_VISIBILITY_CHANGED = 100;
    public static final int MESSAGE_EXPAND_PIP = 101;
    public static final int MESSAGE_MINIMIZE_PIP = 102;
    public static final int MESSAGE_DISMISS_PIP = 103;
    public static final int MESSAGE_UPDATE_ACTIVITY_CALLBACK = 104;

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Called when the PIP menu visibility changes.
         */
        void onPipMenuVisibilityChanged(boolean visible);

        /**
         * Called when the PIP requested to be expanded.
         */
        void onPipExpand();

        /**
         * Called when the PIP requested to be minimized.
         */
        void onPipMinimize();

        /**
         * Called when the PIP requested to be expanded.
         */
        void onPipDismiss();
    }

    private Context mContext;
    private IActivityManager mActivityManager;
    private IWindowManager mWindowManager;

    private ArrayList<Listener> mListeners = new ArrayList<>();
    private ParceledListSlice mActions;

    private Messenger mToActivityMessenger;
    private Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_MENU_VISIBILITY_CHANGED: {
                    boolean visible = msg.arg1 > 0;
                    mListeners.forEach(l -> l.onPipMenuVisibilityChanged(visible));
                    break;
                }
                case MESSAGE_EXPAND_PIP: {
                    mListeners.forEach(l -> l.onPipExpand());
                    // Preemptively mark the menu as invisible once we expand the PiP
                    mListeners.forEach(l -> l.onPipMenuVisibilityChanged(false));
                    break;
                }
                case MESSAGE_MINIMIZE_PIP: {
                    mListeners.forEach(l -> l.onPipMinimize());
                    break;
                }
                case MESSAGE_DISMISS_PIP: {
                    mListeners.forEach(l -> l.onPipDismiss());
                    // Preemptively mark the menu as invisible once we dismiss the PiP
                    mListeners.forEach(l -> l.onPipMenuVisibilityChanged(false));
                    break;
                }
                case MESSAGE_UPDATE_ACTIVITY_CALLBACK: {
                    mToActivityMessenger = msg.replyTo;
                    // Mark the menu as invisible once the activity finishes as well
                    if (mToActivityMessenger == null) {
                        mListeners.forEach(l -> l.onPipMenuVisibilityChanged(false));
                    }
                    break;
                }
            }
        }
    });

    public PipMenuActivityController(Context context, IActivityManager activityManager,
            IWindowManager windowManager) {
        mContext = context;
        mActivityManager = activityManager;
        mWindowManager = windowManager;
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
                    intent.putExtra(EXTRA_ACTIONS, mActions);
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
     * Sets the {@param actions} associated with the PiP.
     */
    public void setActions(ParceledListSlice actions) {
        mActions = actions;

        if (mToActivityMessenger != null) {
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_UPDATE_ACTIONS;
            m.obj = actions;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify menu activity to update actions", e);
            }
        }
    }
}
