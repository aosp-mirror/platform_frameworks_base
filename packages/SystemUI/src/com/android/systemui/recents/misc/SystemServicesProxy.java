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

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
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
import com.android.systemui.R;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.RecentsImpl;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.tv.RecentsTvImpl;
import com.android.systemui.recents.model.ThumbnailData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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

    final static List<String> sRecentsBlacklist;
    static {
        sRecentsBlacklist = new ArrayList<>();
        sRecentsBlacklist.add("com.android.systemui.tv.pip.PipOnboardingActivity");
        sRecentsBlacklist.add("com.android.systemui.tv.pip.PipMenuActivity");
    }

    private static SystemServicesProxy sSystemServicesProxy;

    AccessibilityManager mAccm;
    ActivityManager mAm;
    IActivityManager mIam;
    PackageManager mPm;
    IPackageManager mIpm;
    AssistUtils mAssistUtils;
    WindowManager mWm;
    IWindowManager mIwm;
    UserManager mUm;
    Display mDisplay;
    String mRecentsPackage;
    ComponentName mAssistComponent;

    boolean mIsSafeMode;
    boolean mHasFreeformWorkspaceSupport;

    Bitmap mDummyIcon;
    int mDummyThumbnailWidth;
    int mDummyThumbnailHeight;
    Paint mBgProtectionPaint;
    Canvas mBgProtectionCanvas;

    private final Handler mHandler = new H();

    /**
     * An abstract class to track task stack changes.
     * Classes should implement this instead of {@link android.app.ITaskStackListener}
     * to reduce IPC calls from system services. These callbacks will be called on the main thread.
     */
    public abstract static class TaskStackListener {
        public void onTaskStackChanged() { }
        public void onActivityPinned() { }
        public void onPinnedActivityRestartAttempt() { }
        public void onPinnedStackAnimationEnded() { }
        public void onActivityForcedResizable(String packageName, int taskId) { }
        public void onActivityDismissingDockedStack() { }
    }

    /**
     * Implementation of {@link android.app.ITaskStackListener} to listen task stack changes from
     * ActivityManagerNative.
     * This simply passes callbacks to listeners through {@link H}.
     * */
    private ITaskStackListener.Stub mTaskStackListener = new ITaskStackListener.Stub() {
        @Override
        public void onTaskStackChanged() throws RemoteException {
            mHandler.removeMessages(H.ON_TASK_STACK_CHANGED);
            mHandler.sendEmptyMessage(H.ON_TASK_STACK_CHANGED);
        }

        @Override
        public void onActivityPinned() throws RemoteException {
            mHandler.removeMessages(H.ON_ACTIVITY_PINNED);
            mHandler.sendEmptyMessage(H.ON_ACTIVITY_PINNED);
        }

        @Override
        public void onPinnedActivityRestartAttempt() throws RemoteException{
            mHandler.removeMessages(H.ON_PINNED_ACTIVITY_RESTART_ATTEMPT);
            mHandler.sendEmptyMessage(H.ON_PINNED_ACTIVITY_RESTART_ATTEMPT);
        }

        @Override
        public void onPinnedStackAnimationEnded() throws RemoteException {
            mHandler.removeMessages(H.ON_PINNED_STACK_ANIMATION_ENDED);
            mHandler.sendEmptyMessage(H.ON_PINNED_STACK_ANIMATION_ENDED);
        }

        @Override
        public void onActivityForcedResizable(String packageName, int taskId)
                throws RemoteException {
            mHandler.obtainMessage(H.ON_ACTIVITY_FORCED_RESIZABLE, taskId, 0, packageName)
                    .sendToTarget();
        }

        @Override
        public void onActivityDismissingDockedStack() throws RemoteException {
            mHandler.sendEmptyMessage(H.ON_ACTIVITY_DISMISSING_DOCKED_STACK);
        }
    };

    /**
     * List of {@link TaskStackListener} registered from {@link #registerTaskStackListener}.
     */
    private List<TaskStackListener> mTaskStackListeners = new ArrayList<>();

    /** Private constructor */
    private SystemServicesProxy(Context context) {
        mAccm = AccessibilityManager.getInstance(context);
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mIam = ActivityManagerNative.getDefault();
        mPm = context.getPackageManager();
        mIpm = AppGlobals.getPackageManager();
        mAssistUtils = new AssistUtils(context);
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mIwm = WindowManagerGlobal.getWindowManagerService();
        mUm = UserManager.get(context);
        mDisplay = mWm.getDefaultDisplay();
        mRecentsPackage = context.getPackageName();
        mHasFreeformWorkspaceSupport =
                mPm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
                        Settings.Global.getInt(context.getContentResolver(),
                                DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0;
        mIsSafeMode = mPm.isSafeMode();

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

        // Resolve the assist intent
        mAssistComponent = mAssistUtils.getAssistComponentForUser(UserHandle.myUserId());

        if (RecentsDebugFlags.Static.EnableMockTasks) {
            // Create a dummy icon
            mDummyIcon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            mDummyIcon.eraseColor(0xFF999999);
        }

        UiModeManager uiModeManager = (UiModeManager) context.
                getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            Collections.addAll(sRecentsBlacklist,
                    res.getStringArray(R.array.recents_tv_blacklist_array));
        } else {
            Collections.addAll(sRecentsBlacklist,
                    res.getStringArray(R.array.recents_blacklist_array));
        }
    }

    /**
     * Returns the single instance of the {@link SystemServicesProxy}.
     * This should only be called on the main thread.
     */
    public static SystemServicesProxy getInstance(Context context) {
        if (!Looper.getMainLooper().isCurrentThread()) {
            throw new RuntimeException("Must be called on the UI thread");
        }
        if (sSystemServicesProxy == null) {
            sSystemServicesProxy = new SystemServicesProxy(context);
        }
        return sSystemServicesProxy;
    }

    /**
     * @return whether the provided {@param className} is blacklisted
     */
    public boolean isBlackListedActivity(String className) {
        return sRecentsBlacklist.contains(className);
    }

    /**
     * Returns a list of the recents tasks.
     *
     * @param includeFrontMostExcludedTask if set, will ensure that the front most excluded task
     *                                     will be visible, otherwise no excluded tasks will be
     *                                     visible.
     */
    public List<ActivityManager.RecentTaskInfo> getRecentTasks(int numLatestTasks, int userId,
            boolean includeFrontMostExcludedTask, ArraySet<Integer> quietProfileIds) {
        if (mAm == null) return null;

        // If we are mocking, then create some recent tasks
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            ArrayList<ActivityManager.RecentTaskInfo> tasks =
                    new ArrayList<ActivityManager.RecentTaskInfo>();
            int count = Math.min(numLatestTasks, RecentsDebugFlags.Static.MockTaskCount);
            for (int i = 0; i < count; i++) {
                // Create a dummy component name
                int packageIndex = i % RecentsDebugFlags.Static.MockTasksPackageCount;
                ComponentName cn = new ComponentName("com.android.test" + packageIndex,
                        "com.android.test" + i + ".Activity");
                String description = "" + i + " - " +
                        Long.toString(Math.abs(new Random().nextLong()), 36);
                // Create the recent task info
                ActivityManager.RecentTaskInfo rti = new ActivityManager.RecentTaskInfo();
                rti.id = rti.persistentId = rti.affiliatedTaskId = i;
                rti.baseIntent = new Intent();
                rti.baseIntent.setComponent(cn);
                rti.description = description;
                rti.firstActiveTime = rti.lastActiveTime = i;
                if (i % 2 == 0) {
                    rti.taskDescription = new ActivityManager.TaskDescription(description,
                        Bitmap.createBitmap(mDummyIcon), null,
                        0xFF000000 | (0xFFFFFF & new Random().nextInt()),
                        0xFF000000 | (0xFFFFFF & new Random().nextInt()));
                } else {
                    rti.taskDescription = new ActivityManager.TaskDescription();
                }
                tasks.add(rti);
            }
            return tasks;
        }

        // Remove home/recents/excluded tasks
        int minNumTasksToQuery = 10;
        int numTasksToQuery = Math.max(minNumTasksToQuery, numLatestTasks);
        int flags = ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                ActivityManager.RECENT_INGORE_DOCKED_STACK_TOP_TASK |
                ActivityManager.RECENT_INGORE_PINNED_STACK_TASKS |
                ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                ActivityManager.RECENT_INCLUDE_PROFILES;
        if (includeFrontMostExcludedTask) {
            flags |= ActivityManager.RECENT_WITH_EXCLUDED;
        }
        List<ActivityManager.RecentTaskInfo> tasks = null;
        try {
            tasks = mAm.getRecentTasksForUser(numTasksToQuery, flags, userId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get recent tasks", e);
        }

        // Break early if we can't get a valid set of tasks
        if (tasks == null) {
            return new ArrayList<>();
        }

        boolean isFirstValidTask = true;
        Iterator<ActivityManager.RecentTaskInfo> iter = tasks.iterator();
        while (iter.hasNext()) {
            ActivityManager.RecentTaskInfo t = iter.next();

            // NOTE: The order of these checks happens in the expected order of the traversal of the
            // tasks

            // Remove the task if it or it's package are blacklsited
            if (sRecentsBlacklist.contains(t.realActivity.getClassName()) ||
                    sRecentsBlacklist.contains(t.realActivity.getPackageName())) {
                iter.remove();
                continue;
            }

            // Remove the task if it is marked as excluded, unless it is the first most task and we
            // are requested to include it
            boolean isExcluded = (t.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    == Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            isExcluded |= quietProfileIds.contains(t.userId);
            if (isExcluded && (!isFirstValidTask || !includeFrontMostExcludedTask)) {
                iter.remove();
            }

            isFirstValidTask = false;
        }

        return tasks.subList(0, Math.min(tasks.size(), numLatestTasks));
    }

    /**
     * Returns the top running task.
     */
    public ActivityManager.RunningTaskInfo getRunningTask() {
        List<ActivityManager.RunningTaskInfo> tasks = mAm.getRunningTasks(1);
        if (tasks != null && !tasks.isEmpty()) {
            return tasks.get(0);
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
            ActivityManager.StackInfo stackInfo = mIam.getStackInfo(
                    ActivityManager.StackId.HOME_STACK_ID);
            ActivityManager.StackInfo fullscreenStackInfo = mIam.getStackInfo(
                    ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID);
            ComponentName topActivity = stackInfo.topActivity;
            boolean homeStackVisibleNotOccluded = stackInfo.visible;
            if (fullscreenStackInfo != null) {
                boolean isFullscreenStackOccludingHome = fullscreenStackInfo.visible &&
                        fullscreenStackInfo.position > stackInfo.position;
                homeStackVisibleNotOccluded &= !isFullscreenStackOccludingHome;
            }
            if (isHomeStackVisible != null) {
                isHomeStackVisible.value = homeStackVisibleNotOccluded;
            }
            return (homeStackVisibleNotOccluded && topActivity != null
                    && topActivity.getPackageName().equals(RecentsImpl.RECENTS_PACKAGE)
                    && (topActivity.getClassName().equals(RecentsImpl.RECENTS_ACTIVITY)
                        || topActivity.getClassName().equals(RecentsTvImpl.RECENTS_TV_ACTIVITY)));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Returns whether this device has freeform workspaces.
     */
    public boolean hasFreeformWorkspaceSupport() {
        return mHasFreeformWorkspaceSupport;
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
            options.setLaunchStackId(DOCKED_STACK_ID);
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
                    false /* animate */, initialBounds, true /* moveHomeStackFront */ );
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Returns whether the given stack id is the home stack id.
     */
    public static boolean isHomeStack(int stackId) {
        return stackId == HOME_STACK_ID;
    }

    /**
     * Returns whether the given stack id is the pinned stack id.
     */
    public static boolean isPinnedStack(int stackId){
        return stackId == PINNED_STACK_ID;
    }

    /**
     * Returns whether the given stack id is the docked stack id.
     */
    public static boolean isDockedStack(int stackId) {
        return stackId == DOCKED_STACK_ID;
    }

    /**
     * Returns whether the given stack id is the freeform workspace stack id.
     */
    public static boolean isFreeformStack(int stackId) {
        return stackId == FREEFORM_WORKSPACE_STACK_ID;
    }

    /**
     * @return whether there are any docked tasks for the current user.
     */
    public boolean hasDockedTask() {
        if (mIam == null) return false;

        ActivityManager.StackInfo stackInfo = null;
        try {
            stackInfo = mIam.getStackInfo(DOCKED_STACK_ID);
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
            return WindowManagerGlobal.getWindowManagerService().hasNavigationBar();
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
        if (mWm == null) return;

        try {
            WindowManagerGlobal.getWindowManagerService().cancelTaskWindowTransition(taskId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Cancels the current thumbnail transtion to/from Recents for the given task id.
     */
    public void cancelThumbnailTransition(int taskId) {
        if (mWm == null) return;

        try {
            WindowManagerGlobal.getWindowManagerService().cancelTaskThumbnailTransition(taskId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** Returns the top task thumbnail for the given task id */
    public ThumbnailData getTaskThumbnail(int taskId) {
        if (mAm == null) return null;
        ThumbnailData thumbnailData = new ThumbnailData();

        // If we are mocking, then just return a dummy thumbnail
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            thumbnailData.thumbnail = Bitmap.createBitmap(mDummyThumbnailWidth,
                    mDummyThumbnailHeight, Bitmap.Config.ARGB_8888);
            thumbnailData.thumbnail.eraseColor(0xff333333);
            return thumbnailData;
        }

        getThumbnail(taskId, thumbnailData);
        if (thumbnailData.thumbnail != null) {
            thumbnailData.thumbnail.setHasAlpha(false);
            // We use a dumb heuristic for now, if the thumbnail is purely transparent in the top
            // left pixel, then assume the whole thumbnail is transparent. Generally, proper
            // screenshots are always composed onto a bitmap that has no alpha.
            if (Color.alpha(thumbnailData.thumbnail.getPixel(0, 0)) == 0) {
                mBgProtectionCanvas.setBitmap(thumbnailData.thumbnail);
                mBgProtectionCanvas.drawRect(0, 0, thumbnailData.thumbnail.getWidth(),
                        thumbnailData.thumbnail.getHeight(), mBgProtectionPaint);
                mBgProtectionCanvas.setBitmap(null);
                Log.e(TAG, "Invalid screenshot detected from getTaskThumbnail()");
            }
        }
        return thumbnailData;
    }

    /**
     * Returns a task thumbnail from the activity manager
     */
    public void getThumbnail(int taskId, ThumbnailData thumbnailDataOut) {
        if (mAm == null) {
            return;
        }

        ActivityManager.TaskThumbnail taskThumbnail = mAm.getTaskThumbnail(taskId);
        if (taskThumbnail == null) {
            return;
        }

        Bitmap thumbnail = taskThumbnail.mainThumbnail;
        ParcelFileDescriptor descriptor = taskThumbnail.thumbnailFileDescriptor;
        if (thumbnail == null && descriptor != null) {
            thumbnail = BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(),
                    null, sBitmapOptions);
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException e) {
            }
        }
        thumbnailDataOut.thumbnail = thumbnail;
        thumbnailDataOut.thumbnailInfo = taskThumbnail.thumbnailInfo;
    }

    /**
     * Moves a task into another stack.
     */
    public void moveTaskToStack(int taskId, int stackId) {
        if (mIam == null) return;

        try {
            mIam.positionTaskInStack(taskId, stackId, 0);
        } catch (RemoteException | IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    /** Removes the task */
    public void removeTask(final int taskId) {
        if (mAm == null) return;
        if (RecentsDebugFlags.Static.EnableMockTasks) return;

        // Remove the task.
        BackgroundThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                mAm.removeTask(taskId);
            }
        });
    }

    /**
     * Sends a message to close other system windows.
     */
    public void sendCloseSystemWindows(String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                mIam.closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Returns the activity info for a given component name.
     *
     * @param cn The component name of the activity.
     * @param userId The userId of the user that this is for.
     */
    public ActivityInfo getActivityInfo(ComponentName cn, int userId) {
        if (mIpm == null) return null;
        if (RecentsDebugFlags.Static.EnableMockTasks) return new ActivityInfo();

        try {
            return mIpm.getActivityInfo(cn, PackageManager.GET_META_DATA, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns the activity info for a given component name.
     *
     * @param cn The component name of the activity.
     */
    public ActivityInfo getActivityInfo(ComponentName cn) {
        if (mPm == null) return null;
        if (RecentsDebugFlags.Static.EnableMockTasks) return new ActivityInfo();

        try {
            return mPm.getActivityInfo(cn, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns the activity label, badging if necessary.
     */
    public String getBadgedActivityLabel(ActivityInfo info, int userId) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            return "Recent Task: " + userId;
        }

        return getBadgedLabel(info.loadLabel(mPm).toString(), userId);
    }

    /**
     * Returns the application label, badging if necessary.
     */
    public String getBadgedApplicationLabel(ApplicationInfo appInfo, int userId) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            return "Recent Task App: " + userId;
        }

        return getBadgedLabel(appInfo.loadLabel(mPm).toString(), userId);
    }

    /**
     * Returns the content description for a given task, badging it if necessary.  The content
     * description joins the app and activity labels.
     */
    public String getBadgedContentDescription(ActivityInfo info, int userId, Resources res) {
        // If we are mocking, then return a mock label
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            return "Recent Task Content Description: " + userId;
        }

        String activityLabel = info.loadLabel(mPm).toString();
        String applicationLabel = info.applicationInfo.loadLabel(mPm).toString();
        String badgedApplicationLabel = getBadgedLabel(applicationLabel, userId);
        return applicationLabel.equals(activityLabel) ? badgedApplicationLabel
                : res.getString(R.string.accessibility_recents_task_header,
                        badgedApplicationLabel, activityLabel);
    }

    /**
     * Returns the activity icon for the ActivityInfo for a user, badging if
     * necessary.
     */
    public Drawable getBadgedActivityIcon(ActivityInfo info, int userId) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            return new ColorDrawable(0xFF666666);
        }

        Drawable icon = info.loadIcon(mPm);
        return getBadgedIcon(icon, userId);
    }

    /**
     * Returns the application icon for the ApplicationInfo for a user, badging if
     * necessary.
     */
    public Drawable getBadgedApplicationIcon(ApplicationInfo appInfo, int userId) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            return new ColorDrawable(0xFF666666);
        }

        Drawable icon = appInfo.loadIcon(mPm);
        return getBadgedIcon(icon, userId);
    }

    /**
     * Returns the task description icon, loading and badging it if it necessary.
     */
    public Drawable getBadgedTaskDescriptionIcon(ActivityManager.TaskDescription taskDescription,
            int userId, Resources res) {

        // If we are mocking, then return a mock label
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            return new ColorDrawable(0xFF666666);
        }

        Bitmap tdIcon = taskDescription.getInMemoryIcon();
        if (tdIcon == null) {
            tdIcon = ActivityManager.TaskDescription.loadTaskDescriptionIcon(
                    taskDescription.getIconFilename(), userId);
        }
        if (tdIcon != null) {
            return getBadgedIcon(new BitmapDrawable(res, tdIcon), userId);
        }
        return null;
    }

    /**
     * Returns the given icon for a user, badging if necessary.
     */
    private Drawable getBadgedIcon(Drawable icon, int userId) {
        if (userId != UserHandle.myUserId()) {
            icon = mPm.getUserBadgedIcon(icon, new UserHandle(userId));
        }
        return icon;
    }

    /**
     * Returns a banner used on TV for the specified Activity.
     */
    public Drawable getActivityBanner(ActivityInfo info) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock banner
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            return new ColorDrawable(0xFF666666);
        }

        Drawable banner = info.loadBanner(mPm);
        return banner;
    }

    /**
     * Returns a logo used on TV for the specified Activity.
     */
    public Drawable getActivityLogo(ActivityInfo info) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock logo
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            return new ColorDrawable(0xFF666666);
        }

        Drawable logo = info.loadLogo(mPm);
        return logo;
    }


    /**
     * Returns the given label for a user, badging if necessary.
     */
    private String getBadgedLabel(String label, int userId) {
        if (userId != UserHandle.myUserId()) {
            label = mPm.getUserBadgedLabel(label, new UserHandle(userId)).toString();
        }
        return label;
    }

    /** Returns the package name of the home activity. */
    public String getHomeActivityPackageName() {
        if (mPm == null) return null;
        if (RecentsDebugFlags.Static.EnableMockTasks) return null;

        ArrayList<ResolveInfo> homeActivities = new ArrayList<>();
        ComponentName defaultHomeActivity = mPm.getHomeActivities(homeActivities);
        if (defaultHomeActivity != null) {
            return defaultHomeActivity.getPackageName();
        } else if (homeActivities.size() == 1) {
            ResolveInfo info = homeActivities.get(0);
            if (info.activityInfo != null) {
                return info.activityInfo.packageName;
            }
        }
        return null;
    }

    /**
     * Returns whether the provided {@param userId} represents the system user.
     */
    public boolean isSystemUser(int userId) {
        return userId == UserHandle.USER_SYSTEM;
    }

    /**
     * Returns the current user id.
     */
    public int getCurrentUser() {
        if (mAm == null) return 0;

        return mAm.getCurrentUser();
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
     * Returns the window rect for the RecentsActivity, based on the dimensions of the home stack.
     */
    public Rect getWindowRect() {
        Rect windowRect = new Rect();
        if (mIam == null) return windowRect;

        try {
            // Use the home stack bounds
            ActivityManager.StackInfo stackInfo = mIam.getStackInfo(HOME_STACK_ID);
            if (stackInfo != null) {
                windowRect.set(stackInfo.bounds);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        } finally {
            return windowRect;
        }
    }

    /** Starts an activity from recents. */
    public boolean startActivityFromRecents(Context context, Task.TaskKey taskKey, String taskName,
            ActivityOptions options) {
        if (mIam != null) {
            try {
                if (taskKey.stackId == DOCKED_STACK_ID) {
                    // We show non-visible docked tasks in Recents, but we always want to launch
                    // them in the fullscreen stack.
                    if (options == null) {
                        options = ActivityOptions.makeBasic();
                    }
                    options.setLaunchStackId(FULLSCREEN_WORKSPACE_STACK_ID);
                }
                mIam.startActivityFromRecents(
                        taskKey.id, options == null ? null : options.toBundle());
                return true;
            } catch (Exception e) {
                Log.e(TAG, context.getString(R.string.recents_launch_error_message, taskName), e);
            }
        }
        return false;
    }

    /** Starts an in-place animation on the front most application windows. */
    public void startInPlaceAnimationOnFrontMostApplication(ActivityOptions opts) {
        if (mIam == null) return;

        try {
            mIam.startInPlaceAnimationOnFrontMostApplication(opts);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Registers a task stack listener with the system.
     * This should be called on the main thread.
     */
    public void registerTaskStackListener(TaskStackListener listener) {
        if (mIam == null) return;

        mTaskStackListeners.add(listener);
        if (mTaskStackListeners.size() == 1) {
            // Register mTaskStackListener to IActivityManager only once if needed.
            try {
                mIam.registerTaskStackListener(mTaskStackListener);
            } catch (Exception e) {
                Log.w(TAG, "Failed to call registerTaskStackListener", e);
            }
        }
    }

    public void endProlongedAnimations() {
        if (mWm == null) {
            return;
        }
        try {
            WindowManagerGlobal.getWindowManagerService().endProlongedAnimations();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void registerDockedStackListener(IDockedStackListener listener) {
        if (mWm == null) return;

        try {
            WindowManagerGlobal.getWindowManagerService().registerDockedStackListener(listener);
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
            WindowManagerGlobal.getWindowManagerService().getStableInsets(outStableInsets);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void overridePendingAppTransitionMultiThumbFuture(
            IAppTransitionAnimationSpecsFuture future, IRemoteCallback animStartedListener,
            boolean scaleUp) {
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .overridePendingAppTransitionMultiThumbFuture(future, animStartedListener,
                            scaleUp);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to override transition: " + e);
        }
    }

    /**
     * Updates the visibility of recents.
     */
    public void setRecentsVisibility(boolean visible) {
        try {
            mIwm.setRecentsVisibility(visible);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to reach window manager", e);
        }
    }

    /**
     * Updates the visibility of the picture-in-picture.
     */
    public void setTvPipVisibility(boolean visible) {
        try {
            mIwm.setTvPipVisibility(visible);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to reach window manager", e);
        }
    }

    private final class H extends Handler {
        private static final int ON_TASK_STACK_CHANGED = 1;
        private static final int ON_ACTIVITY_PINNED = 2;
        private static final int ON_PINNED_ACTIVITY_RESTART_ATTEMPT = 3;
        private static final int ON_PINNED_STACK_ANIMATION_ENDED = 4;
        private static final int ON_ACTIVITY_FORCED_RESIZABLE = 5;
        private static final int ON_ACTIVITY_DISMISSING_DOCKED_STACK = 6;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ON_TASK_STACK_CHANGED: {
                    for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                        mTaskStackListeners.get(i).onTaskStackChanged();
                    }
                    break;
                }
                case ON_ACTIVITY_PINNED: {
                    for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                        mTaskStackListeners.get(i).onActivityPinned();
                    }
                    break;
                }
                case ON_PINNED_ACTIVITY_RESTART_ATTEMPT: {
                    for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                        mTaskStackListeners.get(i).onPinnedActivityRestartAttempt();
                    }
                    break;
                }
                case ON_PINNED_STACK_ANIMATION_ENDED: {
                    for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                        mTaskStackListeners.get(i).onPinnedStackAnimationEnded();
                    }
                    break;
                }
                case ON_ACTIVITY_FORCED_RESIZABLE: {
                    for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                        mTaskStackListeners.get(i).onActivityForcedResizable(
                                (String) msg.obj, msg.arg1);
                    }
                    break;
                }
                case ON_ACTIVITY_DISMISSING_DOCKED_STACK: {
                    for (int i = mTaskStackListeners.size() - 1; i >= 0; i--) {
                        mTaskStackListeners.get(i).onActivityDismissingDockedStack();
                    }
                    break;
                }
            }
        }
    }
}
