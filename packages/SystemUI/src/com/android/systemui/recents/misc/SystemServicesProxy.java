/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.misc;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.Log;
import android.util.MutableBoolean;
import android.view.Display;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IDockedStackListener;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManager.KeyboardShortcutsReceiver;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.app.AssistUtils;
import com.android.internal.os.BackgroundThread;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsImpl;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.statusbar.policy.UserInfoController;

import java.util.List;

/**
 * Acts as a shim around the real system services that we need to access data from, and provides
 * a point of injection when testing UI.
 */
public class SystemServicesProxy {
    final static String TAG = "SystemServicesProxy";

    final static BitmapFactory.Options sBitmapOptions;
    static {
        sBitmapOptions = new BitmapFactory.Options();
        sBitmapOptions.inMutable = true;
        sBitmapOptions.inPreferredConfig = Bitmap.Config.RGB_565;
    }

    private static SystemServicesProxy sSystemServicesProxy;

    AccessibilityManager mAccm;
    ActivityManager mAm;
    IActivityManager mIam;
    PackageManager mPm;
    IPackageManager mIpm;
    private final IDreamManager mDreamManager;
    private final Context mContext;
    AssistUtils mAssistUtils;
    WindowManager mWm;
    IWindowManager mIwm;
    UserManager mUm;
    Display mDisplay;
    String mRecentsPackage;
    private TaskStackChangeListeners mTaskStackChangeListeners;
    private int mCurrentUserId;

    boolean mIsSafeMode;

    int mDummyThumbnailWidth;
    int mDummyThumbnailHeight;
    Paint mBgProtectionPaint;
    Canvas mBgProtectionCanvas;

    private final Handler mHandler = new Handler();
    private final Runnable mGcRunnable = new Runnable() {
        @Override
        public void run() {
            System.gc();
            System.runFinalization();
        }
    };

    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);

    private final UserInfoController.OnUserInfoChangedListener mOnUserInfoChangedListener =
            (String name, Drawable picture, String userAccount) ->
                    mCurrentUserId = mAm.getCurrentUser();

    /** Private constructor */
    private SystemServicesProxy(Context context) {
        mContext = context.getApplicationContext();
        mAccm = AccessibilityManager.getInstance(context);
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mIam = ActivityManager.getService();
        mPm = context.getPackageManager();
        mIpm = AppGlobals.getPackageManager();
        mAssistUtils = new AssistUtils(context);
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mIwm = WindowManagerGlobal.getWindowManagerService();
        mUm = UserManager.get(context);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
        mDisplay = mWm.getDefaultDisplay();
        mRecentsPackage = context.getPackageName();
        mIsSafeMode = mPm.isSafeMode();
        mCurrentUserId = mAm.getCurrentUser();
        mTaskStackChangeListeners = new TaskStackChangeListeners(Looper.getMainLooper());

        // Get the dummy thumbnail width/heights
        Resources res = context.getResources();
        int wId = com.android.internal.R.dimen.thumbnail_width;
        int hId = com.android.internal.R.dimen.thumbnail_height;
        mDummyThumbnailWidth = res.getDimensionPixelSize(wId);
        mDummyThumbnailHeight = res.getDimensionPixelSize(hId);

        // Create the protection paints
        mBgProtectionPaint = new Paint();
        mBgProtectionPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_ATOP));
        mBgProtectionPaint.setColor(0xFFffffff);
        mBgProtectionCanvas = new Canvas();

        // Since SystemServicesProxy can be accessed from a per-SysUI process component, create a
        // per-process listener to keep track of the current user id to reduce the number of binder
        // calls to fetch it.
        UserInfoController userInfoController = Dependency.get(UserInfoController.class);
        userInfoController.addCallback(mOnUserInfoChangedListener);
    }

    /**
     * Returns the single instance of the {@link SystemServicesProxy}.
     * This should only be called on the main thread.
     */
    public static synchronized SystemServicesProxy getInstance(Context context) {
        if (sSystemServicesProxy == null) {
            sSystemServicesProxy = new SystemServicesProxy(context);
        }
        return sSystemServicesProxy;
    }

    /**
     * Requests a gc() from the background thread.
     */
    public void gc() {
        BackgroundThread.getHandler().post(mGcRunnable);
    }

    /**
     * Returns the top running task.
     */
    public ActivityManager.RunningTaskInfo getRunningTask() {
        // Note: The set of running tasks from the system is ordered by recency
        List<ActivityManager.RunningTaskInfo> tasks = mAm.getRunningTasks(10);
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }

        // Find the first task in a valid stack, we ignore everything from the Recents and PiP
        // stacks
        for (int i = 0; i < tasks.size(); i++) {
            final ActivityManager.RunningTaskInfo task = tasks.get(i);
            final WindowConfiguration winConfig = task.configuration.windowConfiguration;
            if (winConfig.getActivityType() == ACTIVITY_TYPE_RECENTS) {
                continue;
            }
            if (winConfig.getWindowingMode() == WINDOWING_MODE_PINNED) {
                continue;
            }
            return task;
        }
        return null;
    }

    /**
     * Returns whether the recents activity is currently visible.
     */
    public boolean isRecentsActivityVisible() {
        return isRecentsActivityVisible(null);
    }

    /**
     * Returns whether the recents activity is currently visible.
     *
     * @param isHomeStackVisible if provided, will return whether the home stack is visible
     *                           regardless of the recents visibility
     */
    public boolean isRecentsActivityVisible(MutableBoolean isHomeStackVisible) {
        if (mIam == null) return false;

        try {
            List<StackInfo> stackInfos = mIam.getAllStackInfos();
            ActivityManager.StackInfo homeStackInfo = null;
            ActivityManager.StackInfo fullscreenStackInfo = null;
            ActivityManager.StackInfo recentsStackInfo = null;
            for (int i = 0; i < stackInfos.size(); i++) {
                final StackInfo stackInfo = stackInfos.get(i);
                final WindowConfiguration winConfig = stackInfo.configuration.windowConfiguration;
                final int activityType = winConfig.getActivityType();
                final int windowingMode = winConfig.getWindowingMode();
                if (activityType == ACTIVITY_TYPE_HOME) {
                    homeStackInfo = stackInfo;
                } else if (activityType == ACTIVITY_TYPE_STANDARD
                        && (windowingMode == WINDOWING_MODE_FULLSCREEN
                            || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)) {
                    fullscreenStackInfo = stackInfo;
                } else if (activityType == ACTIVITY_TYPE_RECENTS) {
                    recentsStackInfo = stackInfo;
                }
            }
            boolean homeStackVisibleNotOccluded = isStackNotOccluded(homeStackInfo,
                    fullscreenStackInfo);
            boolean recentsStackVisibleNotOccluded = isStackNotOccluded(recentsStackInfo,
                    fullscreenStackInfo);
            if (isHomeStackVisible != null) {
                isHomeStackVisible.value = homeStackVisibleNotOccluded;
            }
            ComponentName topActivity = recentsStackInfo != null ?
                    recentsStackInfo.topActivity : null;
            return (recentsStackVisibleNotOccluded && topActivity != null
                    && topActivity.getPackageName().equals(RecentsImpl.RECENTS_PACKAGE)
                    && Recents.RECENTS_ACTIVITIES.contains(topActivity.getClassName()));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isStackNotOccluded(ActivityManager.StackInfo stackInfo,
            ActivityManager.StackInfo fullscreenStackInfo) {
        boolean stackVisibleNotOccluded = stackInfo == null || stackInfo.visible;
        if (fullscreenStackInfo != null && stackInfo != null) {
            boolean isFullscreenStackOccludingg = fullscreenStackInfo.visible &&
                    fullscreenStackInfo.position > stackInfo.position;
            stackVisibleNotOccluded &= !isFullscreenStackOccludingg;
        }
        return stackVisibleNotOccluded;
    }

    /**
     * Returns whether this device is in the safe mode.
     */
    public boolean isInSafeMode() {
        return mIsSafeMode;
    }

    /** Docks a task to the side of the screen and starts it. */
    public boolean startTaskInDockedMode(int taskId, int createMode) {
        if (mIam == null) return false;

        try {
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setDockCreateMode(createMode);
            options.setLaunchWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
            mIam.startActivityFromRecents(taskId, options.toBundle());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to dock task: " + taskId + " with createMode: " + createMode, e);
        }
        return false;
    }

    /** Docks an already resumed task to the side of the screen. */
    public boolean moveTaskToDockedStack(int taskId, int createMode, Rect initialBounds) {
        if (mIam == null) {
            return false;
        }

        try {
            return mIam.moveTaskToDockedStack(taskId, createMode, true /* onTop */,
                    false /* animate */, initialBounds);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * @return whether there are any docked tasks for the current user.
     */
    public boolean hasDockedTask() {
        if (mIam == null) return false;

        ActivityManager.StackInfo stackInfo = null;
        try {
            stackInfo =
                    mIam.getStackInfo(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_UNDEFINED);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        if (stackInfo != null) {
            int userId = getCurrentUser();
            boolean hasUserTask = false;
            for (int i = stackInfo.taskUserIds.length - 1; i >= 0 && !hasUserTask; i--) {
                hasUserTask = (stackInfo.taskUserIds[i] == userId);
            }
            return hasUserTask;
        }
        return false;
    }

    /**
     * Returns whether there is a soft nav bar.
     */
    public boolean hasSoftNavigationBar() {
        try {
            return mIwm.hasNavigationBar();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Returns whether the device has a transposed nav bar (on the right of the screen) in the
     * current display orientation.
     */
    public boolean hasTransposedNavigationBar() {
        Rect insets = new Rect();
        getStableInsets(insets);
        return insets.right > 0;
    }

    /**
     * Cancels the current window transtion to/from Recents for the given task id.
     */
    public void cancelWindowTransition(int taskId) {
        if (mIam == null) return;

        try {
            mIam.cancelTaskWindowTransition(taskId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Cancels the current thumbnail transtion to/from Recents for the given task id.
     */
    public void cancelThumbnailTransition(int taskId) {
        if (mIam == null) return;

        try {
            mIam.cancelTaskThumbnailTransition(taskId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** Set the task's windowing mode. */
    public void setTaskWindowingMode(int taskId, int windowingMode) {
        if (mIam == null) return;

        try {
            mIam.setTaskWindowingMode(taskId, windowingMode, false /* onTop */);
        } catch (RemoteException | IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    /** Removes the task */
    public void removeTask(final int taskId) {
        if (mAm == null) return;

        // Remove the task.
        mUiOffloadThread.submit(() -> {
            try {
                mIam.removeTask(taskId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Sends a message to close other system windows.
     */
    public void sendCloseSystemWindows(String reason) {
        mUiOffloadThread.submit(() -> {
            try {
                mIam.closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        });
    }

    public ActivityManager.TaskDescription getTaskDescription(int taskId) {
        try {
            return mIam.getTaskDescription(taskId);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns whether the provided {@param userId} represents the system user.
     */
    public boolean isSystemUser(int userId) {
        return userId == UserHandle.USER_SYSTEM;
    }

    /**
     * Returns the current user id.  Used instead of KeyguardUpdateMonitor in SystemUI components
     * that run in the non-primary SystemUI process.
     */
    public int getCurrentUser() {
        return mCurrentUserId;
    }

    /**
     * Returns the processes user id.
     */
    public int getProcessUser() {
        if (mUm == null) return 0;
        return mUm.getUserHandle();
    }

    /**
     * Returns whether touch exploration is currently enabled.
     */
    public boolean isTouchExplorationEnabled() {
        if (mAccm == null) return false;

        return mAccm.isEnabled() && mAccm.isTouchExplorationEnabled();
    }

    /**
     * Returns whether the current task is in screen-pinning mode.
     */
    public boolean isScreenPinningActive() {
        if (mIam == null) return false;

        try {
            return mIam.isInLockTaskMode();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns a global setting.
     */
    public int getGlobalSetting(Context context, String setting) {
        ContentResolver cr = context.getContentResolver();
        return Settings.Global.getInt(cr, setting, 0);
    }

    /**
     * Returns a system setting.
     */
    public int getSystemSetting(Context context, String setting) {
        ContentResolver cr = context.getContentResolver();
        return Settings.System.getInt(cr, setting, 0);
    }

    /**
     * Returns a system property.
     */
    public String getSystemProperty(String key) {
        return SystemProperties.get(key);
    }

    /**
     * Returns the smallest width/height.
     */
    public int getDeviceSmallestWidth() {
        if (mDisplay == null) return 0;

        Point smallestSizeRange = new Point();
        Point largestSizeRange = new Point();
        mDisplay.getCurrentSizeRange(smallestSizeRange, largestSizeRange);
        return smallestSizeRange.x;
    }

    /**
     * Returns the current display rect in the current display orientation.
     */
    public Rect getDisplayRect() {
        Rect displayRect = new Rect();
        if (mDisplay == null) return displayRect;

        Point p = new Point();
        mDisplay.getRealSize(p);
        displayRect.set(0, 0, p.x, p.y);
        return displayRect;
    }

    /**
     * Returns the window rect for the RecentsActivity, based on the dimensions of the recents stack
     */
    public Rect getWindowRect() {
        Rect windowRect = new Rect();
        if (mIam == null) return windowRect;

        try {
            // Use the recents stack bounds, fallback to fullscreen stack if it is null
            ActivityManager.StackInfo stackInfo =
                    mIam.getStackInfo(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_RECENTS);
            if (stackInfo == null) {
                stackInfo = mIam.getStackInfo(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD);
            }
            if (stackInfo != null) {
                windowRect.set(stackInfo.bounds);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        } finally {
            return windowRect;
        }
    }

    public void startActivityAsUserAsync(Intent intent, ActivityOptions opts) {
        mUiOffloadThread.submit(() -> mContext.startActivityAsUser(intent,
                opts != null ? opts.toBundle() : null, UserHandle.CURRENT));
    }

    public void startActivityFromRecents(Context context, Task.TaskKey taskKey, String taskName,
            ActivityOptions options,
            @Nullable final StartActivityFromRecentsResultListener resultListener) {
        startActivityFromRecents(context, taskKey, taskName, options,
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_UNDEFINED, resultListener);
    }

    /** Starts an activity from recents. */
    public void startActivityFromRecents(Context context, Task.TaskKey taskKey, String taskName,
            ActivityOptions options, int windowingMode, int activityType,
            @Nullable final StartActivityFromRecentsResultListener resultListener) {
        if (mIam == null) {
            return;
        }
        if (taskKey.windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            // We show non-visible docked tasks in Recents, but we always want to launch
            // them in the fullscreen stack.
            if (options == null) {
                options = ActivityOptions.makeBasic();
            }
            options.setLaunchWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        } else if (windowingMode != WINDOWING_MODE_UNDEFINED
                || activityType != ACTIVITY_TYPE_UNDEFINED) {
            if (options == null) {
                options = ActivityOptions.makeBasic();
            }
            options.setLaunchWindowingMode(windowingMode);
            options.setLaunchActivityType(activityType);
        }
        final ActivityOptions finalOptions = options;

        // Execute this from another thread such that we can do other things (like caching the
        // bitmap for the thumbnail) while AM is busy starting our activity.
        mUiOffloadThread.submit(() -> {
            try {
                mIam.startActivityFromRecents(
                        taskKey.id, finalOptions == null ? null : finalOptions.toBundle());
                if (resultListener != null) {
                    mHandler.post(() -> resultListener.onStartActivityResult(true));
                }
            } catch (Exception e) {
                Log.e(TAG, context.getString(
                        R.string.recents_launch_error_message, taskName), e);
                if (resultListener != null) {
                    mHandler.post(() -> resultListener.onStartActivityResult(false));
                }
            }
        });
    }

    /** Starts an in-place animation on the front most application windows. */
    public void startInPlaceAnimationOnFrontMostApplication(ActivityOptions opts) {
        if (mIam == null) return;

        try {
            mIam.startInPlaceAnimationOnFrontMostApplication(
                    opts == null ? null : opts.toBundle());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Registers a task stack listener with the system.
     * This should be called on the main thread.
     */
    public void registerTaskStackListener(TaskStackChangeListener listener) {
        if (mIam == null) return;

        synchronized (mTaskStackChangeListeners) {
            mTaskStackChangeListeners.addListener(mIam, listener);
        }
    }

    public void endProlongedAnimations() {
        if (mWm == null) {
            return;
        }
        try {
            mIwm.endProlongedAnimations();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void registerDockedStackListener(IDockedStackListener listener) {
        if (mWm == null) return;

        try {
            mIwm.registerDockedStackListener(listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Calculates the size of the dock divider in the current orientation.
     */
    public int getDockedDividerSize(Context context) {
        Resources res = context.getResources();
        int dividerWindowWidth = res.getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        int dividerInsets = res.getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
        return dividerWindowWidth - 2 * dividerInsets;
    }

    public void requestKeyboardShortcuts(
            Context context, KeyboardShortcutsReceiver receiver, int deviceId) {
        mWm.requestAppKeyboardShortcuts(receiver, deviceId);
    }

    public void getStableInsets(Rect outStableInsets) {
        if (mWm == null) return;

        try {
            mIwm.getStableInsets(Display.DEFAULT_DISPLAY, outStableInsets);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void overridePendingAppTransitionMultiThumbFuture(
            IAppTransitionAnimationSpecsFuture future, IRemoteCallback animStartedListener,
            boolean scaleUp) {
        try {
            mIwm.overridePendingAppTransitionMultiThumbFuture(future, animStartedListener, scaleUp);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to override transition: " + e);
        }
    }

    /**
     * Updates the visibility of recents.
     */
    public void setRecentsVisibility(final boolean visible) {
        mUiOffloadThread.submit(() -> {
            try {
                mIwm.setRecentsVisibility(visible);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to reach window manager", e);
            }
        });
    }

    /**
     * Updates the visibility of the picture-in-picture.
     */
    public void setPipVisibility(final boolean visible) {
        mUiOffloadThread.submit(() -> {
            try {
                mIwm.setPipVisibility(visible);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to reach window manager", e);
            }
        });
    }

    public boolean isDreaming() {
        try {
            return mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query dream manager.", e);
        }
        return false;
    }

    public void awakenDreamsAsync() {
        mUiOffloadThread.submit(() -> {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });
    }

    public interface StartActivityFromRecentsResultListener {
        void onStartActivityResult(boolean succeeded);
    }
}
