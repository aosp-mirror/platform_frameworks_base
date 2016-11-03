package com.android.systemui.pip.phone;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.WindowManager.INPUT_CONSUMER_PIP;

import android.app.ActivityManager.StackInfo;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
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
    public static final int MESSAGE_ACTIVITY_VISIBILITY_CHANGED = 1;
    public static final int MESSAGE_EXPAND_PIP = 3;

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Called when the PIP menu visibility changes.
         */
        void onPipMenuVisibilityChanged(boolean visible);
    }

    private Context mContext;
    private IActivityManager mActivityManager;
    private IWindowManager mWindowManager;
    private ArrayList<Listener> mListeners = new ArrayList<>();

    private Messenger mToActivityMessenger;
    private Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_ACTIVITY_VISIBILITY_CHANGED: {
                    boolean visible = msg.arg1 > 0;
                    int listenerCount = mListeners.size();
                    for (int i = 0; i < listenerCount; i++) {
                        mListeners.get(i).onPipMenuVisibilityChanged(visible);
                    }
                    mToActivityMessenger = msg.replyTo;
                    break;
                }
                case MESSAGE_EXPAND_PIP: {
                    try {
                        mActivityManager.resizeStack(PINNED_STACK_ID, null, true, true, true, 225);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error showing PIP menu activity", e);
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
        // Start the menu activity on the top task of the pinned stack
        try {
            StackInfo pinnedStackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
            if (pinnedStackInfo != null && pinnedStackInfo.taskIds != null &&
                    pinnedStackInfo.taskIds.length > 0) {
                Intent intent = new Intent(mContext, PipMenuActivity.class);
                intent.putExtra(EXTRA_CONTROLLER_MESSENGER, mMessenger);
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchTaskId(
                        pinnedStackInfo.taskIds[pinnedStackInfo.taskIds.length - 1]);
                options.setTaskOverlay(true);
                mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
            } else {
                Log.e(TAG, "No PIP tasks found");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error showing PIP menu activity", e);
        }
    }

    /**
     * Hides the menu activity.
     */
    public void hideMenu() {
        if (mToActivityMessenger != null) {
            Message m = Message.obtain();
            m.what = PipMenuActivity.MESSAGE_FINISH_SELF;
            try {
                mToActivityMessenger.send(m);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not notify menu activity to finish", e);
            }
            mToActivityMessenger = null;
        }
    }
}
