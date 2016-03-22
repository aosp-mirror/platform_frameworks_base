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

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;
import android.util.MutableBoolean;
import android.util.Pair;
import android.view.Display;
import android.view.IDockedStackListener;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.KeyboardShortcutsReceiver;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.app.AssistUtils;
import com.android.internal.os.BackgroundThread;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.RecentsImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;

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
    }

    final static List<String> sRecentsBlacklist;
    static {
        sRecentsBlacklist = new ArrayList<>();
        sRecentsBlacklist.add("com.android.systemui.tv.pip.PipOnboardingActivity");
        sRecentsBlacklist.add("com.android.systemui.tv.pip.PipMenuActivity");
    }

    AccessibilityManager mAccm;
    ActivityManager mAm;
    IActivityManager mIam;
    AppWidgetManager mAwm;
    PackageManager mPm;
    IPackageManager mIpm;
    AssistUtils mAssistUtils;
    WindowManager mWm;
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

    /** Private constructor */
    public SystemServicesProxy(Context context) {
        mAccm = AccessibilityManager.getInstance(context);
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mIam = ActivityManagerNative.getDefault();
        mAwm = AppWidgetManager.getInstance(context);
        mPm = context.getPackageManager();
        mIpm = AppGlobals.getPackageManager();
        mAssistUtils = new AssistUtils(context);
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
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
    }

    /** Returns a list of the recents tasks */
    public List<ActivityManager.RecentTaskInfo> getRecentTasks(int numLatestTasks, int userId,
            boolean isTopTaskHome, ArraySet<Integer> quietProfileIds) {
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
        List<ActivityManager.RecentTaskInfo> tasks = mAm.getRecentTasksForUser(numTasksToQuery,
                ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                ActivityManager.RECENT_INGORE_DOCKED_STACK_TASKS |
                ActivityManager.RECENT_INGORE_PINNED_STACK_TASKS |
                ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                ActivityManager.RECENT_INCLUDE_PROFILES |
                ActivityManager.RECENT_WITH_EXCLUDED, userId);

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

            // Check the first non-recents task, include this task even if it is marked as excluded
            // from recents if we are currently in the app.  In other words, only remove excluded
            // tasks if it is not the first active task, and not in the blacklist.
            boolean isExcluded = (t.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    == Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            boolean isBlackListed = sRecentsBlacklist.contains(t.realActivity.getClassName());
            // Filter out recent tasks from managed profiles which are in quiet mode.
            isExcluded |= quietProfileIds.contains(t.userId);
            if (isBlackListed || (isExcluded && (isTopTaskHome || !isFirstValidTask))) {
                iter.remove();
                continue;
            }
            isFirstValidTask = false;
        }

        return tasks.subList(0, Math.min(tasks.size(), numLatestTasks));
    }

    /** Returns a list of the running tasks */
    private List<ActivityManager.RunningTaskInfo> getRunningTasks(int numTasks) {
        if (mAm == null) return null;
        return mAm.getRunningTasks(numTasks);
    }

    /** Returns the top task. */
    public ActivityManager.RunningTaskInfo getTopMostTask() {
        List<ActivityManager.RunningTaskInfo> tasks = getRunningTasks(1);
        if (tasks != null && !tasks.isEmpty()) {
            return tasks.get(0);
        }
        return null;
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

    /** Returns whether the recents is currently running */
    public boolean isRecentsTopMost(ActivityManager.RunningTaskInfo topTask,
            MutableBoolean isHomeTopMost) {
        if (topTask != null) {
            ComponentName topActivity = topTask.topActivity;

            // Check if the front most activity is recents
            if ((topActivity.getPackageName().equals(RecentsImpl.RECENTS_PACKAGE) &&
                    (topActivity.getClassName().equals(RecentsImpl.RECENTS_ACTIVITY) ||
                    topActivity.getClassName().equals(RecentsImpl.RECENTS_TV_ACTIVITY)))) {
                if (isHomeTopMost != null) {
                    isHomeTopMost.value = false;
                }
                return true;
            }

            // Note, this is only valid because we currently only allow the recents and home
            // activities in the home stack
            if (isHomeTopMost != null) {
                isHomeTopMost.value = SystemServicesProxy.isHomeStack(topTask.stackId);
            }
        }
        return false;
    }

    /** Get the bounds of a task. */
    public Rect getTaskBounds(int taskId) {
        if (mIam == null) return null;

        try {
            return mIam.getTaskBounds(taskId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Resizes the given task to the new bounds.
     */
    public void resizeTask(int taskId, Rect bounds) {
        if (mIam == null) return;

        try {
            mIam.resizeTask(taskId, bounds, ActivityManager.RESIZE_MODE_FORCED);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** Docks a task to the side of the screen and starts it. */
    public void startTaskInDockedMode(Context context, View view, int taskId, int createMode) {
        if (mIam == null) return;

        try {
            // TODO: Determine what animation we want for the incoming task
            final ActivityOptions options = ActivityOptions.makeThumbnailAspectScaleUpAnimation(
                    view, null, 0, 0, view.getWidth(), view.getHeight(), null, null);
            options.setDockCreateMode(createMode);
            options.setLaunchStackId(DOCKED_STACK_ID);
            mIam.startActivityFromRecents(taskId, options.toBundle());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** Docks an already resumed task to the side of the screen. */
    public boolean moveTaskToDockedStack(int taskId, int createMode, Rect initialBounds) {
        if (mIam == null) {
            return false;
        }

        try {
            return mIam.moveTaskToDockedStack(
                    taskId, createMode, true /* onTop */, false /* animate */, initialBounds);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /** Returns the focused stack id. */
    public int getFocusedStack() {
        if (mIam == null) return -1;

        try {
            return mIam.getFocusedStackId();
        } catch (RemoteException e) {
            e.printStackTrace();
            return -1;
        }
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
    public Bitmap getTaskThumbnail(int taskId) {
        if (mAm == null) return null;

        // If we are mocking, then just return a dummy thumbnail
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            Bitmap thumbnail = Bitmap.createBitmap(mDummyThumbnailWidth, mDummyThumbnailHeight,
                    Bitmap.Config.ARGB_8888);
            thumbnail.eraseColor(0xff333333);
            return thumbnail;
        }

        Bitmap thumbnail = getThumbnail(taskId);
        if (thumbnail != null) {
            thumbnail.setHasAlpha(false);
            // We use a dumb heuristic for now, if the thumbnail is purely transparent in the top
            // left pixel, then assume the whole thumbnail is transparent. Generally, proper
            // screenshots are always composed onto a bitmap that has no alpha.
            if (Color.alpha(thumbnail.getPixel(0, 0)) == 0) {
                mBgProtectionCanvas.setBitmap(thumbnail);
                mBgProtectionCanvas.drawRect(0, 0, thumbnail.getWidth(), thumbnail.getHeight(),
                        mBgProtectionPaint);
                mBgProtectionCanvas.setBitmap(null);
                Log.e(TAG, "Invalid screenshot detected from getTaskThumbnail()");
            }
        }
        return thumbnail;
    }

    /**
     * Returns a task thumbnail from the activity manager
     */
    public Bitmap getThumbnail(int taskId) {
        if (mAm == null) {
            return null;
        }

        ActivityManager.TaskThumbnail taskThumbnail = mAm.getTaskThumbnail(taskId);
        if (taskThumbnail == null) return null;

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
        return thumbnail;
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

    /** Moves a task to the front with the specified activity options. */
    public void moveTaskToFront(int taskId, ActivityOptions opts) {
        if (mAm == null) return;
        if (RecentsDebugFlags.Static.EnableMockTasks) return;

        if (opts != null) {
            mAm.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME,
                    opts.toBundle());
        } else {
            mAm.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME);
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
     * Returns the current search widget id.
     */
    public int getSearchAppWidgetId(Context context) {
        return Prefs.getInt(context, Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_ID, -1);
    }

    /**
     * Returns the current search widget info, binding a new one if necessary.
     */
    public AppWidgetProviderInfo getOrBindSearchAppWidget(Context context, AppWidgetHost host) {
        int searchWidgetId = Prefs.getInt(context, Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_ID, -1);
        AppWidgetProviderInfo searchWidgetInfo = mAwm.getAppWidgetInfo(searchWidgetId);
        AppWidgetProviderInfo resolvedSearchWidgetInfo = resolveSearchAppWidget();

        // Return the search widget info if it hasn't changed
        if (searchWidgetInfo != null && resolvedSearchWidgetInfo != null &&
                searchWidgetInfo.provider.equals(resolvedSearchWidgetInfo.provider)) {
            if (Prefs.getString(context, Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_PACKAGE, null) == null) {
                Prefs.putString(context, Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_PACKAGE,
                        searchWidgetInfo.provider.getPackageName());
            }
            return searchWidgetInfo;
        }

        // Delete the old widget
        if (searchWidgetId != -1) {
            host.deleteAppWidgetId(searchWidgetId);
        }

        // And rebind a new search widget
        if (resolvedSearchWidgetInfo != null) {
            Pair<Integer, AppWidgetProviderInfo> widgetInfo = bindSearchAppWidget(host,
                    resolvedSearchWidgetInfo);
            if (widgetInfo != null) {
                Prefs.putInt(context, Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_ID, widgetInfo.first);
                Prefs.putString(context, Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_PACKAGE,
                        widgetInfo.second.provider.getPackageName());
                return widgetInfo.second;
            }
        }

        // If we fall through here, then there is no resolved search widget, so clear the state
        Prefs.remove(context, Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_ID);
        Prefs.remove(context, Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_PACKAGE);
        return null;
    }

    /**
     * Returns the first Recents widget from the same package as the global assist activity.
     */
    public AppWidgetProviderInfo resolveSearchAppWidget() {
        if (mAssistComponent == null) return null;
        List<AppWidgetProviderInfo> widgets = mAwm.getInstalledProviders(
                AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX);
        for (AppWidgetProviderInfo info : widgets) {
            if (info.provider.getPackageName().equals(mAssistComponent.getPackageName())) {
                return info;
            }
        }
        return null;
    }

    /**
     * Resolves and binds the search app widget that is to appear in the recents.
     */
    private Pair<Integer, AppWidgetProviderInfo> bindSearchAppWidget(AppWidgetHost host,
            AppWidgetProviderInfo resolvedSearchWidgetInfo) {
        if (mAwm == null) return null;
        if (mAssistComponent == null) return null;

        // Allocate a new widget id and try and bind the app widget (if that fails, then just skip)
        int searchWidgetId = host.allocateAppWidgetId();
        Bundle opts = new Bundle();
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX);
        if (!mAwm.bindAppWidgetIdIfAllowed(searchWidgetId, resolvedSearchWidgetInfo.provider, opts)) {
            host.deleteAppWidgetId(searchWidgetId);
            return null;
        }
        return new Pair<>(searchWidgetId, resolvedSearchWidgetInfo);
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
        if (mWm == null) return 0;

        Point smallestSizeRange = new Point();
        Point largestSizeRange = new Point();
        mWm.getDefaultDisplay().getCurrentSizeRange(smallestSizeRange, largestSizeRange);
        return smallestSizeRange.x;
    }

    /**
     * Returns the display rect.
     */
    public Rect getDisplayRect() {
        Rect displayRect = new Rect();
        if (mWm == null) return displayRect;

        Point p = new Point();
        mWm.getDefaultDisplay().getRealSize(p);
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
    public boolean startActivityFromRecents(Context context, int taskId, String taskName,
            ActivityOptions options) {
        if (mIam != null) {
            try {
                mIam.startActivityFromRecents(taskId, options == null ? null : options.toBundle());
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

    /** Registers a task stack listener with the system. */
    public void registerTaskStackListener(ITaskStackListener listener) {
        if (mIam == null) return;

        try {
            mIam.registerTaskStackListener(listener);
        } catch (Exception e) {
            e.printStackTrace();
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

    public void requestKeyboardShortcuts(Context context, KeyboardShortcutsReceiver receiver) {
        mWm.requestAppKeyboardShortcuts(receiver);
    }

    public void getStableInsets(Rect outStableInsets) {
        if (mWm == null) return;

        try {
            WindowManagerGlobal.getWindowManagerService().getStableInsets(outStableInsets);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
