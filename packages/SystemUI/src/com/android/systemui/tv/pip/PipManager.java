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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Debug;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.tv.TvStatusBar;

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

    /**
     * List of package and class name which are considered as Settings,
     * so PIP location should be adjusted to the left of the side panel.
     */
    private static final List<Pair<String, String>> sSettingsPackageAndClassNamePairList;
    static {
        sSettingsPackageAndClassNamePairList = new ArrayList<>();
        sSettingsPackageAndClassNamePairList.add(new Pair<String, String>(
                "com.android.tv.settings", null));
        sSettingsPackageAndClassNamePairList.add(new Pair<String, String>(
                "com.google.android.leanbacklauncher",
                "com.google.android.leanbacklauncher.settings.HomeScreenSettingsActivity"));
    }

    /**
     * State when there's no PIP.
     */
    public static final int STATE_NO_PIP = 0;
    /**
     * State when PIP is shown with an overlay message on top of it.
     * This is used as default PIP state.
     */
    public static final int STATE_PIP_OVERLAY = 1;
    /**
     * State when PIP menu dialog is shown.
     */
    public static final int STATE_PIP_MENU = 2;
    /**
     * State when PIP is shown in Recents.
     */
    public static final int STATE_PIP_RECENTS = 3;
    /**
     * State when PIP is shown in Recents and it's focused to allow an user to control.
     */
    public static final int STATE_PIP_RECENTS_FOCUSED = 4;

    private static final int TASK_ID_NO_PIP = -1;
    private static final int INVALID_RESOURCE_TYPE = -1;

    public static final int SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH = 0x1;
    public static final int SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_OVERLAY_ACTIVITY_FINISH = 0x2;

    /**
     * PIPed activity is playing a media and it can be paused.
     */
    static final int PLAYBACK_STATE_PLAYING = 0;
    /**
     * PIPed activity has a paused media and it can be played.
     */
    static final int PLAYBACK_STATE_PAUSED = 1;
    /**
     * Users are unable to control PIPed activity's media playback.
     */
    static final int PLAYBACK_STATE_UNAVAILABLE = 2;

    private static final int CLOSE_PIP_WHEN_MEDIA_SESSION_GONE_TIMEOUT_MS = 3000;

    private int mSuspendPipResizingReason;

    private Context mContext;
    private PipRecentsOverlayManager mPipRecentsOverlayManager;
    private IActivityManager mActivityManager;
    private MediaSessionManager mMediaSessionManager;
    private int mState = STATE_NO_PIP;
    private final Handler mHandler = new Handler();
    private List<Listener> mListeners = new ArrayList<>();
    private List<MediaListener> mMediaListeners = new ArrayList<>();
    private Rect mCurrentPipBounds;
    private Rect mPipBounds;
    private Rect mDefaultPipBounds;
    private Rect mSettingsPipBounds;
    private Rect mMenuModePipBounds;
    private Rect mRecentsPipBounds;
    private Rect mRecentsFocusedPipBounds;
    private int mRecentsFocusChangedAnimationDurationMs;
    private boolean mInitialized;
    private int mPipTaskId = TASK_ID_NO_PIP;
    private ComponentName mPipComponentName;
    private MediaController mPipMediaController;
    private boolean mOnboardingShown;
    private String[] mLastPackagesResourceGranted;

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
                if (packageNames != null && packageNames.length > 0
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

        mActivityManager = ActivityManagerNative.getDefault();
        SystemServicesProxy.getInstance(context).registerTaskStackListener(mTaskStackListener);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_MEDIA_RESOURCE_GRANTED);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
        mOnboardingShown = Prefs.getBoolean(
                mContext, TV_PICTURE_IN_PICTURE_ONBOARDING_SHOWN, false);

        loadConfigurationsAndApply();
        mPipRecentsOverlayManager = new PipRecentsOverlayManager(context);
        mMediaSessionManager =
                (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    private void loadConfigurationsAndApply() {
        Resources res = mContext.getResources();
        mDefaultPipBounds = Rect.unflattenFromString(res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureBounds));
        mSettingsPipBounds = Rect.unflattenFromString(res.getString(
                R.string.pip_settings_bounds));
        mMenuModePipBounds = Rect.unflattenFromString(res.getString(
                R.string.pip_menu_bounds));
        mRecentsPipBounds = Rect.unflattenFromString(res.getString(
                R.string.pip_recents_bounds));
        mRecentsFocusedPipBounds = Rect.unflattenFromString(res.getString(
                R.string.pip_recents_focused_bounds));
        mRecentsFocusChangedAnimationDurationMs = res.getInteger(
                R.integer.recents_tv_pip_focus_anim_duration);

        // Reset the PIP bounds and apply. PIP bounds can be changed by two reasons.
        //   1. Configuration changed due to the language change (RTL <-> RTL)
        //   2. SystemUI restarts after the crash
        mPipBounds = isSettingsShown() ? mSettingsPipBounds : mDefaultPipBounds;
        resizePinnedStack(getPinnedStackInfo() == null ? STATE_NO_PIP : STATE_PIP_OVERLAY);
    }

    /**
     * Updates the PIP per configuration changed.
     */
    void onConfigurationChanged() {
        loadConfigurationsAndApply();
        mPipRecentsOverlayManager.onConfigurationChanged(mContext);
    }

    /**
     * Shows the picture-in-picture menu if an activity is in picture-in-picture mode.
     */
    public void showTvPictureInPictureMenu() {
        if (mState == STATE_PIP_OVERLAY) {
            resizePinnedStack(STATE_PIP_MENU);
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
        updatePipVisibility(false);
    }

    /**
     * Moves the PIPed activity to the fullscreen and closes PIP system UI.
     */
    void movePipToFullscreen() {
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
    void resizePinnedStack(int state) {
        if (DEBUG) Log.d(TAG, "resizePinnedStack() state=" + state);
        boolean wasRecentsShown =
                (mState == STATE_PIP_RECENTS || mState == STATE_PIP_RECENTS_FOCUSED);
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
                mCurrentPipBounds = mPipBounds;
                break;
            case STATE_PIP_RECENTS:
                mCurrentPipBounds = mRecentsPipBounds;
                break;
            case STATE_PIP_RECENTS_FOCUSED:
                mCurrentPipBounds = mRecentsFocusedPipBounds;
                break;
            default:
                mCurrentPipBounds = mPipBounds;
                break;
        }
        try {
            int animationDurationMs = -1;
            if (wasRecentsShown
                    && (mState == STATE_PIP_RECENTS || mState == STATE_PIP_RECENTS_FOCUSED)) {
                animationDurationMs = mRecentsFocusChangedAnimationDurationMs;
            }
            mActivityManager.resizeStack(PINNED_STACK_ID, mCurrentPipBounds,
                    true, true, true, animationDurationMs);
        } catch (RemoteException e) {
            Log.e(TAG, "resizeStack failed", e);
        }
    }

    /**
     * Returns the default PIP bound.
     */
    public Rect getPipBounds() {
        return mPipBounds;
    }

    /**
     * Returns the focused PIP bound while Recents is shown.
     * This is used to place PIP controls in Recents.
     */
    public Rect getRecentsFocusedPipBounds() {
        return mRecentsFocusedPipBounds;
    }

    /**
     * Shows PIP menu UI by launching {@link PipMenuActivity}. It also locates the pinned
     * stack to the centered PIP bound {@link R.config_centeredPictureInPictureBounds}.
     */
    private void showPipMenu() {
        if (DEBUG) Log.d(TAG, "showPipMenu()");
        if (mPipRecentsOverlayManager.isRecentsShown()) {
            if (DEBUG) Log.d(TAG, "Ignore showing PIP menu");
            return;
        }
        mState = STATE_PIP_MENU;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onShowPipMenu();
        }
        Intent intent = new Intent(mContext, PipMenuActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    /**
     * Adds a {@link Listener} to PipManager.
     */
    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a {@link Listener} from PipManager.
     */
    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * Adds a {@link MediaListener} to PipManager.
     */
    public void addMediaListener(MediaListener listener) {
        mMediaListeners.add(listener);
    }

    /**
     * Removes a {@link MediaListener} from PipManager.
     */
    public void removeMediaListener(MediaListener listener) {
        mMediaListeners.remove(listener);
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
        return mState != STATE_NO_PIP;
    }

    private StackInfo getPinnedStackInfo() {
        StackInfo stackInfo = null;
        try {
            stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
        } catch (RemoteException e) {
            Log.e(TAG, "getStackInfo failed", e);
        }
        return stackInfo;
    }

    private void handleMediaResourceGranted(String[] packageNames) {
        if (mState == STATE_NO_PIP) {
            mLastPackagesResourceGranted = packageNames;
        } else {
            boolean requestedFromLastPackages = false;
            if (mLastPackagesResourceGranted != null) {
                for (String packageName : mLastPackagesResourceGranted) {
                    for (String newPackageName : packageNames) {
                        if (TextUtils.equals(newPackageName, packageName)) {
                            requestedFromLastPackages = true;
                            break;
                        }
                    }
                }
            }
            mLastPackagesResourceGranted = packageNames;
            if (!requestedFromLastPackages) {
                closePip();
            }
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
            for (int i = mMediaListeners.size() - 1; i >= 0; i--) {
                mMediaListeners.get(i).onMediaControllerChanged();
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

    /**
     * Returns the PIPed activity's playback state.
     * This returns one of {@link PLAYBACK_STATE_PLAYING}, {@link PLAYBACK_STATE_PAUSED},
     * or {@link PLAYBACK_STATE_UNAVAILABLE}.
     */
    int getPlaybackState() {
        if (mPipMediaController == null || mPipMediaController.getPlaybackState() == null) {
            return PLAYBACK_STATE_UNAVAILABLE;
        }
        int state = mPipMediaController.getPlaybackState().getState();
        boolean isPlaying = (state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT);
        long actions = mPipMediaController.getPlaybackState().getActions();
        if (!isPlaying && ((actions & PlaybackState.ACTION_PLAY) != 0)) {
            return PLAYBACK_STATE_PAUSED;
        } else if (isPlaying && ((actions & PlaybackState.ACTION_PAUSE) != 0)) {
            return PLAYBACK_STATE_PLAYING;
        }
        return PLAYBACK_STATE_UNAVAILABLE;
    }

    private boolean isSettingsShown() {
        List<RunningTaskInfo> runningTasks;
        try {
            runningTasks = mActivityManager.getTasks(1, 0);
            if (runningTasks == null || runningTasks.size() == 0) {
                return false;
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Failed to detect top activity", e);
            return false;
        }
        ComponentName topActivity = runningTasks.get(0).topActivity;
        for (Pair<String, String> componentName : sSettingsPackageAndClassNamePairList) {
            String packageName = componentName.first;
            if (topActivity.getPackageName().equals(packageName)) {
                String className = componentName.second;
                if (className == null || topActivity.getClassName().equals(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    private TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            if (mState != STATE_NO_PIP) {
                boolean hasPip = false;

                StackInfo stackInfo = getPinnedStackInfo();
                if (stackInfo == null || stackInfo.taskIds == null) {
                    Log.w(TAG, "There is nothing in pinned stack");
                    closePipInternal(false);
                    return;
                }
                for (int i = stackInfo.taskIds.length - 1; i >= 0; --i) {
                    if (stackInfo.taskIds[i] == mPipTaskId) {
                        // PIP task is still alive.
                        hasPip = true;
                        break;
                    }
                }
                if (!hasPip) {
                    // PIP task doesn't exist anymore in PINNED_STACK.
                    closePipInternal(true);
                    return;
                }
            }
            if (mState == STATE_PIP_OVERLAY) {
                Rect bounds = isSettingsShown() ? mSettingsPipBounds : mDefaultPipBounds;
                if (mPipBounds != bounds) {
                    mPipBounds = bounds;
                    resizePinnedStack(STATE_PIP_OVERLAY);
                }
            }
        }

        @Override
        public void onActivityPinned() {
            if (DEBUG) Log.d(TAG, "onActivityPinned()");
            StackInfo stackInfo = getPinnedStackInfo();
            if (stackInfo == null) {
                Log.w(TAG, "Cannot find pinned stack");
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
            if (mPipRecentsOverlayManager.isRecentsShown()) {
                // If an activity becomes PIPed again after the fullscreen, the Recents is shown
                // behind so we need to resize the pinned stack and show the correct overlay.
                resizePinnedStack(STATE_PIP_RECENTS);
            }
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.get(i).onPipEntered();
            }
            updatePipVisibility(true);
        }

        @Override
        public void onPinnedActivityRestartAttempt() {
            if (DEBUG) Log.d(TAG, "onPinnedActivityRestartAttempt()");
            // If PIPed activity is launched again by Launcher or intent, make it fullscreen.
            movePipToFullscreen();
        }

        @Override
        public void onPinnedStackAnimationEnded() {
            if (DEBUG) Log.d(TAG, "onPinnedStackAnimationEnded()");
            switch (mState) {
                case STATE_PIP_OVERLAY:
                    if (!mPipRecentsOverlayManager.isRecentsShown()) {
                        showPipOverlay();
                        break;
                    } else {
                        // This happens only if an activity is PIPed after the Recents is shown.
                        // See {@link PipRecentsOverlayManager.requestFocus} for more details.
                        resizePinnedStack(mState);
                        break;
                    }
                case STATE_PIP_RECENTS:
                case STATE_PIP_RECENTS_FOCUSED:
                    mPipRecentsOverlayManager.addPipRecentsOverlayView();
                    break;
                case STATE_PIP_MENU:
                    showPipMenu();
                    break;
            }
        }
    };

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Invoked when an activity is pinned and PIP manager is set corresponding information.
         * Classes must use this instead of {@link android.app.ITaskStackListener.onActivityPinned}
         * because there's no guarantee for the PIP manager be return relavent information
         * correctly. (e.g. {@link isPipShown}).
         */
        void onPipEntered();
        /** Invoked when a PIPed activity is closed. */
        void onPipActivityClosed();
        /** Invoked when the PIP menu gets shown. */
        void onShowPipMenu();
        /** Invoked when the PIPed activity is about to return back to the fullscreen. */
        void onMoveToFullscreen();
        /** Invoked when we are above to start resizing the Pip. */
        void onPipResizeAboutToStart();
    }

    /**
     * A listener interface to receive change in PIP's media controller
     */
    public interface MediaListener {
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

    /**
     * Gets an instance of {@link PipRecentsOverlayManager}.
     */
    public PipRecentsOverlayManager getPipRecentsOverlayManager() {
        return mPipRecentsOverlayManager;
    }

    private void updatePipVisibility(boolean visible) {
        TvStatusBar statusBar = ((SystemUIApplication) mContext).getComponent(TvStatusBar.class);
        if (statusBar != null) {
            statusBar.updatePipVisibility(visible);
        }
    }
}
