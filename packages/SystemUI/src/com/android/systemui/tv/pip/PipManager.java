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

package com.android.systemui.tv.pip;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Debug;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import com.android.systemui.Prefs;

import java.util.ArrayList;
import java.util.List;

import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static com.android.systemui.Prefs.Key.TV_PICTURE_IN_PICTURE_ONBOARDING_SHOWN;

/**
 * Manages the picture-in-picture (PIP) UI and states.
 */
public class PipManager {
    private static final String TAG = "PipManager";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_FORCE_ONBOARDING =
            SystemProperties.getBoolean("debug.tv.pip_force_onboarding", false);

    private static PipManager sPipManager;

    private static final int MAX_RUNNING_TASKS_COUNT = 10;

    public static final int STATE_NO_PIP = 0;
    public static final int STATE_PIP_OVERLAY = 1;
    public static final int STATE_PIP_MENU = 2;

    private static final int TASK_ID_NO_PIP = -1;
    private static final int INVALID_RESOURCE_TYPE = -1;

    public static final int SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH = 0x1;
    public static final int SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_OVERLAY_ACTIVITY_FINISH = 0x2;

    private static final int CLOSE_PIP_WHEN_MEDIA_SESSION_GONE_TIMEOUT_MS = 3000;

    private int mSuspendPipResizingReason;

    private static final float SCALE_FACTOR = 1.1f;

    private Context mContext;
    private IActivityManager mActivityManager;
    private MediaSessionManager mMediaSessionManager;
    private int mState = STATE_NO_PIP;
    private final Handler mHandler = new Handler();
    private List<Listener> mListeners = new ArrayList<>();
    private Rect mCurrentPipBounds;
    private Rect mPipBounds;
    private Rect mMenuModePipBounds;
    private Rect mRecentsPipBounds;
    private Rect mRecentsFocusedPipBounds;
    private boolean mInitialized;
    private int mPipTaskId = TASK_ID_NO_PIP;
    private ComponentName mPipComponentName;
    private MediaController mPipMediaController;
    private boolean mOnboardingShown;

    private boolean mIsRecentsShown;
    private boolean mIsPipFocusedInRecent;

    private final Runnable mOnActivityPinnedRunnable = new Runnable() {
        @Override
        public void run() {
            StackInfo stackInfo = null;
            try {
                stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
                if (stackInfo == null) {
                    Log.w(TAG, "Cannot find pinned stack");
                    return;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "getStackInfo failed", e);
                return;
            }
            if (DEBUG) Log.d(TAG, "PINNED_STACK:" + stackInfo);
            mPipTaskId = stackInfo.taskIds[stackInfo.taskIds.length - 1];
            mPipComponentName = ComponentName.unflattenFromString(
                    stackInfo.taskNames[stackInfo.taskNames.length - 1]);
            // Set state to overlay so we show it when the pinned stack animation ends.
            mState = STATE_PIP_OVERLAY;
            mCurrentPipBounds = mPipBounds;
            launchPipOnboardingActivityIfNeeded();
            mMediaSessionManager.addOnActiveSessionsChangedListener(
                    mActiveMediaSessionListener, null);
            updateMediaController(mMediaSessionManager.getActiveSessions(null));
        }
    };
    private final Runnable mOnTaskStackChanged = new Runnable() {
        @Override
        public void run() {
            if (mState != STATE_NO_PIP) {
                StackInfo stackInfo = null;
                try {
                    stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
                    if (stackInfo == null) {
                        Log.w(TAG, "There is no pinned stack");
                        closePipInternal(false);
                        return;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "getStackInfo failed", e);
                    return;
                }
                for (int i = stackInfo.taskIds.length - 1; i >= 0; --i) {
                    if (stackInfo.taskIds[i] == mPipTaskId) {
                        // PIP task is still alive.
                        return;
                    }
                }
                // PIP task doesn't exist anymore in PINNED_STACK.
                closePipInternal(true);
            }
        }
    };
    private final Runnable mOnPinnedActivityRestartAttempt = new Runnable() {
        @Override
        public void run() {
            // If PIPed activity is launched again by Launcher or intent, make it fullscreen.
            movePipToFullscreen();
        }
    };
    private final Runnable mOnPinnedStackAnimationEnded = new Runnable() {
        @Override
        public void run() {
            switch (mState) {
                case STATE_PIP_OVERLAY:
                    showPipOverlay();
                    break;
                case STATE_PIP_MENU:
                    showPipMenu();
                    break;
            }
        }
    };

    private final Runnable mResizePinnedStackRunnable = new Runnable() {
        @Override
        public void run() {
            resizePinnedStack(mState);
        }
    };
    private final Runnable mClosePipRunnable = new Runnable() {
        @Override
        public void run() {
            closePip();
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_RESOURCE_GRANTED.equals(action)) {
                String[] packageNames = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                int resourceType = intent.getIntExtra(Intent.EXTRA_MEDIA_RESOURCE_TYPE,
                        INVALID_RESOURCE_TYPE);
                if (mState != STATE_NO_PIP && packageNames != null && packageNames.length > 0
                        && resourceType == Intent.EXTRA_MEDIA_RESOURCE_TYPE_VIDEO_CODEC) {
                    handleMediaResourceGranted(packageNames);
                }
            }

        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener mActiveMediaSessionListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    updateMediaController(controllers);
                }
            };

    private PipManager() { }

    /**
     * Initializes {@link PipManager}.
     */
    public void initialize(Context context) {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mContext = context;
        Resources res = context.getResources();
        mPipBounds = Rect.unflattenFromString(res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureBounds));
        mMenuModePipBounds = Rect.unflattenFromString(res.getString(
                com.android.internal.R.string.config_centeredPictureInPictureBounds));
        mRecentsPipBounds = Rect.unflattenFromString(res.getString(
                com.android.internal.R.string.config_pictureInPictureBoundsInRecents));
        float scaleBy = (SCALE_FACTOR - 1.0f) / 2;
        mRecentsFocusedPipBounds = new Rect(
                (int) (mRecentsPipBounds.left - scaleBy * mRecentsPipBounds.width()),
                (int) (mRecentsPipBounds.top - scaleBy * mRecentsPipBounds.height()),
                (int) (mRecentsPipBounds.right + scaleBy * mRecentsPipBounds.width()),
                (int) (mRecentsPipBounds.bottom + scaleBy * mRecentsPipBounds.height()));

        mActivityManager = ActivityManagerNative.getDefault();
        TaskStackListener taskStackListener = new TaskStackListener();
        IActivityManager iam = ActivityManagerNative.getDefault();
        try {
            iam.registerTaskStackListener(taskStackListener);
        } catch (RemoteException e) {
            Log.e(TAG, "registerTaskStackListener failed", e);
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_MEDIA_RESOURCE_GRANTED);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
        mOnboardingShown = Prefs.getBoolean(
                mContext, TV_PICTURE_IN_PICTURE_ONBOARDING_SHOWN, false);

        mMediaSessionManager =
                (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    /**
     * Request PIP.
     * It could either start PIP if there's none, and show PIP menu otherwise.
     */
    public void requestTvPictureInPicture() {
        if (DEBUG) Log.d(TAG, "requestTvPictureInPicture()");
        if (!isPipShown()) {
            startPip();
        } else if (mState == STATE_PIP_OVERLAY) {
            resizePinnedStack(STATE_PIP_MENU);
        }
    }

    private void startPip() {
        try {
            mActivityManager.moveTopActivityToPinnedStack(FULLSCREEN_WORKSPACE_STACK_ID, mPipBounds);
        } catch (RemoteException|IllegalArgumentException e) {
            Log.e(TAG, "moveTopActivityToPinnedStack failed", e);
        }
    }

    /**
     * Closes PIP (PIPed activity and PIP system UI).
     */
    public void closePip() {
        closePipInternal(true);
    }

    private void closePipInternal(boolean removePipStack) {
        mState = STATE_NO_PIP;
        mPipTaskId = TASK_ID_NO_PIP;
        mPipMediaController = null;
        mMediaSessionManager.removeOnActiveSessionsChangedListener(mActiveMediaSessionListener);
        if (removePipStack) {
            try {
                mActivityManager.removeStack(PINNED_STACK_ID);
            } catch (RemoteException e) {
                Log.e(TAG, "removeStack failed", e);
            }
        }
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onPipActivityClosed();
        }
        mHandler.removeCallbacks(mClosePipRunnable);
    }

    /**
     * Moves the PIPed activity to the fullscreen and closes PIP system UI.
     */
    public void movePipToFullscreen() {
        mState = STATE_NO_PIP;
        mPipTaskId = TASK_ID_NO_PIP;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onMoveToFullscreen();
        }
        resizePinnedStack(mState);
    }

    /**
     * Shows PIP overlay UI by launching {@link PipOverlayActivity}. It also locates the pinned
     * stack to the default PIP bound {@link com.android.internal.R.string
     * .config_defaultPictureInPictureBounds}.
     */
    private void showPipOverlay() {
        if (DEBUG) Log.d(TAG, "showPipOverlay()");
        mState = STATE_PIP_OVERLAY;
        PipOverlayActivity.showPipOverlay(mContext);
    }

    /**
     * Suspends resizing operation on the Pip until {@link #resumePipResizing} is called
     * @param reason The reason for suspending resizing operations on the Pip.
     */
    public void suspendPipResizing(int reason) {
        if (DEBUG) Log.d(TAG,
                "suspendPipResizing() reason=" + reason + " callers=" + Debug.getCallers(2));
        mSuspendPipResizingReason |= reason;
    }

    /**
     * Resumes resizing operation on the Pip that was previously suspended.
     * @param reason The reason resizing operations on the Pip was suspended.
     */
    public void resumePipResizing(int reason) {
        if ((mSuspendPipResizingReason & reason) == 0) {
            return;
        }
        if (DEBUG) Log.d(TAG,
                "resumePipResizing() reason=" + reason + " callers=" + Debug.getCallers(2));
        mSuspendPipResizingReason &= ~reason;
        mHandler.post(mResizePinnedStackRunnable);
    }

    /**
     * Resize the Pip to the appropriate size for the input state.
     * @param state In Pip state also used to determine the new size for the Pip.
     */
    public void resizePinnedStack(int state) {
        if (DEBUG) Log.d(TAG, "resizePinnedStack() state=" + state);
        mState = state;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onPipResizeAboutToStart();
        }
        if (mSuspendPipResizingReason != 0) {
            if (DEBUG) Log.d(TAG,
                    "resizePinnedStack() deferring mSuspendPipResizingReason=" +
                            mSuspendPipResizingReason);
            return;
        }
        switch (mState) {
            case STATE_NO_PIP:
                mCurrentPipBounds = null;
                break;
            case STATE_PIP_MENU:
                mCurrentPipBounds = mMenuModePipBounds;
                break;
            case STATE_PIP_OVERLAY:
                if (mIsRecentsShown) {
                    if (mIsPipFocusedInRecent) {
                        mCurrentPipBounds = mRecentsFocusedPipBounds;
                    } else {
                        mCurrentPipBounds = mRecentsPipBounds;
                    }
                } else {
                    mCurrentPipBounds = mPipBounds;
                }
                break;
            default:
                mCurrentPipBounds = mPipBounds;
                break;
        }
        try {
            mActivityManager.resizeStack(PINNED_STACK_ID, mCurrentPipBounds, true, true, true);
        } catch (RemoteException e) {
            Log.e(TAG, "showPipMenu failed", e);
        }
    }

    /**
     * Returns the current PIP bound for activities to sync their UI with PIP.
     */
    public Rect getPipBounds() {
        return mCurrentPipBounds;
    }

    /**
     * Called when Recents is started.
     * PIPed activity will be resized accordingly and overlay will show available buttons.
     */
    public void onRecentsStarted() {
        mIsRecentsShown = true;
        mIsPipFocusedInRecent = false;
        if (mState == STATE_NO_PIP) {
            return;
        }
        resizePinnedStack(STATE_PIP_OVERLAY);
    }

    /**
     * Called when Recents is stopped.
     * PIPed activity will be resized accordingly and overlay will hide available buttons.
     */
    public void onRecentsStopped() {
        mIsRecentsShown = false;
        mIsPipFocusedInRecent = false;
        if (mState == STATE_NO_PIP) {
            return;
        }
        resizePinnedStack(STATE_PIP_OVERLAY);
    }

    /**
     * Returns {@code true} if recents is shown.
     */
    boolean isRecentsShown() {
        return mIsRecentsShown;
    }

    /**
     * Called when the PIP view in {@link com.android.systemui.recents.tv.RecentsTvActivity}
     * is focused.
     * This only resizes pinned stack so it looks like it's in Recents.
     * This should be called only by {@link com.android.systemui.recents.tv.RecentsTvActivity}.
     */
    public void onPipViewFocusChangedInRecents(boolean hasFocus) {
        mIsPipFocusedInRecent = hasFocus;
        if (mState != STATE_PIP_OVERLAY) {
            Log.w(TAG, "There is no pinned stack to handle focus change.");
            return;
        }
        resizePinnedStack(STATE_PIP_OVERLAY);
    }

    /**
     * Shows PIP menu UI by launching {@link PipMenuActivity}. It also locates the pinned
     * stack to the centered PIP bound {@link com.android.internal.R.string
     * .config_centeredPictureInPictureBounds}.
     */
    private void showPipMenu() {
        if (DEBUG) Log.d(TAG, "showPipMenu()");
        mState = STATE_PIP_MENU;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onShowPipMenu();
        }
        Intent intent = new Intent(mContext, PipMenuActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void launchPipOnboardingActivityIfNeeded() {
        if (DEBUG_FORCE_ONBOARDING || !mOnboardingShown) {
            mOnboardingShown = true;
            Prefs.putBoolean(mContext, TV_PICTURE_IN_PICTURE_ONBOARDING_SHOWN, true);

            Intent intent = new Intent(mContext, PipOnboardingActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        }
    }

    /**
     * Returns {@code true} if PIP is shown.
     */
    public boolean isPipShown() {
        return hasPipTasks();
    }

    private boolean hasPipTasks() {
        try {
            StackInfo stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
            return stackInfo != null;
        } catch (RemoteException e) {
            Log.e(TAG, "getStackInfo failed", e);
            return false;
        }
    }

    private void handleMediaResourceGranted(String[] packageNames) {
        StackInfo fullscreenStack = null;
        try {
            fullscreenStack = mActivityManager.getStackInfo(FULLSCREEN_WORKSPACE_STACK_ID);
        } catch (RemoteException e) {
            Log.e(TAG, "getStackInfo failed", e);
        }
        if (fullscreenStack == null) {
            return;
        }
        int fullscreenTopTaskId = fullscreenStack.taskIds[fullscreenStack.taskIds.length - 1];
        List<RunningTaskInfo> tasks = null;
        try {
            tasks = mActivityManager.getTasks(MAX_RUNNING_TASKS_COUNT, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "getTasks failed", e);
        }
        if (tasks == null) {
            return;
        }
        boolean wasGrantedInFullscreen = false;
        boolean wasGrantedInPip = false;
        for (int i = tasks.size() - 1; i >= 0; --i) {
            RunningTaskInfo task = tasks.get(i);
            for (int j = packageNames.length - 1; j >= 0; --j) {
                if (task.topActivity.getPackageName().equals(packageNames[j])) {
                    if (task.id == fullscreenTopTaskId) {
                        wasGrantedInFullscreen = true;
                    } else if (task.id == mPipTaskId) {
                        wasGrantedInPip= true;
                    }
                }
            }
        }
        if (wasGrantedInFullscreen && !wasGrantedInPip) {
            closePip();
        }
    }

    private void updateMediaController(List<MediaController> controllers) {
        MediaController mediaController = null;
        if (controllers != null && mState != STATE_NO_PIP && mPipComponentName != null) {
            for (int i = controllers.size() - 1; i >= 0; i--) {
                MediaController controller = controllers.get(i);
                // We assumes that an app with PIPable activity
                // keeps the single instance of media controller especially when PIP is on.
                if (controller.getPackageName().equals(mPipComponentName.getPackageName())) {
                    mediaController = controller;
                    break;
                }
            }
        }
        if (mPipMediaController != mediaController) {
            mPipMediaController = mediaController;
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.get(i).onMediaControllerChanged();
            }
            if (mPipMediaController == null) {
                mHandler.postDelayed(mClosePipRunnable,
                        CLOSE_PIP_WHEN_MEDIA_SESSION_GONE_TIMEOUT_MS);
            } else {
                mHandler.removeCallbacks(mClosePipRunnable);
            }
        }
    }

    /**
     * Gets the {@link android.media.session.MediaController} for the PIPed activity.
     */
    MediaController getMediaController() {
        return mPipMediaController;
    }

    private class TaskStackListener extends ITaskStackListener.Stub {
        @Override
        public void onTaskStackChanged() throws RemoteException {
            // Post the message back to the UI thread.
            mHandler.post(mOnTaskStackChanged);
        }

        @Override
        public void onActivityPinned()  throws RemoteException {
            // Post the message back to the UI thread.
            if (DEBUG) Log.d(TAG, "onActivityPinned()");
            mHandler.post(mOnActivityPinnedRunnable);
        }

        @Override
        public void onPinnedActivityRestartAttempt() {
            // Post the message back to the UI thread.
            if (DEBUG) Log.d(TAG, "onPinnedActivityRestartAttempt()");
            mHandler.post(mOnPinnedActivityRestartAttempt);
        }

        @Override
        public void onPinnedStackAnimationEnded() {
            if (DEBUG) Log.d(TAG, "onPinnedStackAnimationEnded()");
            mHandler.post(mOnPinnedStackAnimationEnded);
        }
    }

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /** Invoked when a PIPed activity is closed. */
        void onPipActivityClosed();
        /** Invoked when the PIP menu gets shown. */
        void onShowPipMenu();
        /** Invoked when the PIPed activity is returned back to the fullscreen. */
        void onMoveToFullscreen();
        /** Invoked when we are above to start resizing the Pip. */
        void onPipResizeAboutToStart();
        /** Invoked when the MediaController on PIPed activity is changed. */
        void onMediaControllerChanged();
    }

    /**
     * Gets an instance of {@link PipManager}.
     */
    public static PipManager getInstance() {
        if (sPipManager == null) {
            sPipManager = new PipManager();
        }
        return sPipManager;
    }
}
