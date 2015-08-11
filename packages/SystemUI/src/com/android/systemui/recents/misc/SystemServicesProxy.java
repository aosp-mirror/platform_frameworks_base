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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.MutableBoolean;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.app.AssistUtils;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.Recents;

import java.io.IOException;
import java.util.ArrayList;
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
    final static HandlerThread sBgThread;

    static {
        sBgThread = new HandlerThread("Recents-SystemServicesProxy",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        sBgThread.start();
        sBitmapOptions = new BitmapFactory.Options();
        sBitmapOptions.inMutable = true;
    }

    AccessibilityManager mAccm;
    ActivityManager mAm;
    IActivityManager mIam;
    AppWidgetManager mAwm;
    PackageManager mPm;
    IPackageManager mIpm;
    AssistUtils mAssistUtils;
    WindowManager mWm;
    Display mDisplay;
    String mRecentsPackage;
    ComponentName mAssistComponent;

    Handler mBgThreadHandler;

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
        mDisplay = mWm.getDefaultDisplay();
        mRecentsPackage = context.getPackageName();
        mBgThreadHandler = new Handler(sBgThread.getLooper());

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

        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            // Create a dummy icon
            mDummyIcon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            mDummyIcon.eraseColor(0xFF999999);
        }
    }

    /** Returns a list of the recents tasks */
    public List<ActivityManager.RecentTaskInfo> getRecentTasks(int numLatestTasks, int userId,
            boolean isTopTaskHome) {
        if (mAm == null) return null;

        // If we are mocking, then create some recent tasks
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            ArrayList<ActivityManager.RecentTaskInfo> tasks =
                    new ArrayList<ActivityManager.RecentTaskInfo>();
            int count = Math.min(numLatestTasks, Constants.DebugFlags.App.SystemServicesProxyMockTaskCount);
            for (int i = 0; i < count; i++) {
                // Create a dummy component name
                int packageIndex = i % Constants.DebugFlags.App.SystemServicesProxyMockPackageCount;
                ComponentName cn = new ComponentName("com.android.test" + packageIndex,
                        "com.android.test" + i + ".Activity");
                String description = "" + i + " - " +
                        Long.toString(Math.abs(new Random().nextLong()), 36);
                // Create the recent task info
                ActivityManager.RecentTaskInfo rti = new ActivityManager.RecentTaskInfo();
                rti.id = rti.persistentId = i;
                rti.baseIntent = new Intent();
                rti.baseIntent.setComponent(cn);
                rti.description = description;
                rti.firstActiveTime = rti.lastActiveTime = i;
                if (i % 2 == 0) {
                    rti.taskDescription = new ActivityManager.TaskDescription(description,
                        Bitmap.createBitmap(mDummyIcon),
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
            // tasks if it is not the first active task.
            boolean isExcluded = (t.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    == Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            if (isExcluded && (isTopTaskHome || !isFirstValidTask)) {
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

    /** Returns whether the recents is currently running */
    public boolean isRecentsTopMost(ActivityManager.RunningTaskInfo topTask,
            MutableBoolean isHomeTopMost) {
        if (topTask != null) {
            ComponentName topActivity = topTask.topActivity;

            // Check if the front most activity is recents
            if (topActivity.getPackageName().equals(Recents.sRecentsPackage) &&
                    topActivity.getClassName().equals(Recents.sRecentsActivity)) {
                if (isHomeTopMost != null) {
                    isHomeTopMost.value = false;
                }
                return true;
            }

            if (isHomeTopMost != null) {
                isHomeTopMost.value = isInHomeStack(topTask.id);
            }
        }
        return false;
    }

    /** Get the bounds of a stack / task. */
    public Rect getTaskBounds(int stackId) {
        ActivityManager.StackInfo info = getAllStackInfos().get(stackId);
        if (info != null)
          return info.bounds;
        return new Rect();
    }

    /** Resize a given task. */
    public void resizeTask(int taskId, Rect bounds) {
        if (mIam == null) return;

        try {
            mIam.resizeTask(taskId, bounds);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** Returns the stack info for all stacks. */
    public SparseArray<ActivityManager.StackInfo> getAllStackInfos() {
        if (mIam == null) return new SparseArray<ActivityManager.StackInfo>();

        try {
            SparseArray<ActivityManager.StackInfo> stacks =
                    new SparseArray<ActivityManager.StackInfo>();
            List<ActivityManager.StackInfo> infos = mIam.getAllStackInfos();
            int stackCount = infos.size();
            for (int i = 0; i < stackCount; i++) {
                ActivityManager.StackInfo info = infos.get(i);
                stacks.put(info.stackId, info);
            }
            return stacks;
        } catch (RemoteException e) {
            e.printStackTrace();
            return new SparseArray<ActivityManager.StackInfo>();
        }
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

    /** Returns whether the specified task is in the home stack */
    public boolean isInHomeStack(int taskId) {
        if (mAm == null) return false;

        // If we are mocking, then just return false
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return false;
        }

        return mAm.isInHomeStack(taskId);
    }

    /** Returns the top task thumbnail for the given task id */
    public Bitmap getTaskThumbnail(int taskId) {
        if (mAm == null) return null;

        // If we are mocking, then just return a dummy thumbnail
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            Bitmap thumbnail = Bitmap.createBitmap(mDummyThumbnailWidth, mDummyThumbnailHeight,
                    Bitmap.Config.ARGB_8888);
            thumbnail.eraseColor(0xff333333);
            return thumbnail;
        }

        Bitmap thumbnail = SystemServicesProxy.getThumbnail(mAm, taskId);
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
    public static Bitmap getThumbnail(ActivityManager activityManager, int taskId) {
        ActivityManager.TaskThumbnail taskThumbnail = activityManager.getTaskThumbnail(taskId);
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

    /** Moves a task to the front with the specified activity options. */
    public void moveTaskToFront(int taskId, ActivityOptions opts) {
        if (mAm == null) return;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return;

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
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return;

        // Remove the task.
        mBgThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                mAm.removeTask(taskId);
            }
        });
    }

    /**
     * Returns the activity info for a given component name.
     *
     * @param cn The component name of the activity.
     * @param userId The userId of the user that this is for.
     */
    public ActivityInfo getActivityInfo(ComponentName cn, int userId) {
        if (mIpm == null) return null;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return new ActivityInfo();

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
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return new ActivityInfo();

        try {
            return mPm.getActivityInfo(cn, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Returns the activity label */
    public String getActivityLabel(ActivityInfo info) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return "Recent Task";
        }

        return info.loadLabel(mPm).toString();
    }

    /** Returns the application label */
    public String getApplicationLabel(Intent baseIntent, int userId) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return "Recent Task";
        }

        ResolveInfo ri = mPm.resolveActivityAsUser(baseIntent, 0, userId);
        CharSequence label = (ri != null) ? ri.loadLabel(mPm) : null;
        return (label != null) ? label.toString() : null;
    }

    /** Returns the content description for a given task */
    public String getContentDescription(Intent baseIntent, int userId, String activityLabel,
            Resources res) {
        String applicationLabel = getApplicationLabel(baseIntent, userId);
        if (applicationLabel == null) {
            return getBadgedLabel(activityLabel, userId);
        }
        String badgedApplicationLabel = getBadgedLabel(applicationLabel, userId);
        return applicationLabel.equals(activityLabel) ? badgedApplicationLabel
                : res.getString(R.string.accessibility_recents_task_header,
                        badgedApplicationLabel, activityLabel);
    }

    /**
     * Returns the activity icon for the ActivityInfo for a user, badging if
     * necessary.
     */
    public Drawable getActivityIcon(ActivityInfo info, int userId) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return new ColorDrawable(0xFF666666);
        }

        Drawable icon = info.loadIcon(mPm);
        return getBadgedIcon(icon, userId);
    }

    /**
     * Returns the given icon for a user, badging if necessary.
     */
    public Drawable getBadgedIcon(Drawable icon, int userId) {
        if (userId != UserHandle.myUserId()) {
            icon = mPm.getUserBadgedIcon(icon, new UserHandle(userId));
        }
        return icon;
    }

    /**
     * Returns the given label for a user, badging if necessary.
     */
    public String getBadgedLabel(String label, int userId) {
        if (userId != UserHandle.myUserId()) {
            label = mPm.getUserBadgedLabel(label, new UserHandle(userId)).toString();
        }
        return label;
    }

    /** Returns the package name of the home activity. */
    public String getHomeActivityPackageName() {
        if (mPm == null) return null;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return null;

        ArrayList<ResolveInfo> homeActivities = new ArrayList<ResolveInfo>();
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
     * Returns whether the foreground user is the owner.
     */
    public boolean isForegroundUserOwner() {
        if (mAm == null) return false;

        return mAm.getCurrentUser() == UserHandle.USER_OWNER;
    }

    /**
     * Returns the current search widget id.
     */
    public int getSearchAppWidgetId(Context context) {
        return Prefs.getInt(context, Prefs.Key.SEARCH_APP_WIDGET_ID, -1);
    }

    /**
     * Returns the current search widget info, binding a new one if necessary.
     */
    public AppWidgetProviderInfo getOrBindSearchAppWidget(Context context, AppWidgetHost host) {
        int searchWidgetId = Prefs.getInt(context, Prefs.Key.SEARCH_APP_WIDGET_ID, -1);
        AppWidgetProviderInfo searchWidgetInfo = mAwm.getAppWidgetInfo(searchWidgetId);
        AppWidgetProviderInfo resolvedSearchWidgetInfo = resolveSearchAppWidget();

        // Return the search widget info if it hasn't changed
        if (searchWidgetInfo != null && resolvedSearchWidgetInfo != null &&
                searchWidgetInfo.provider.equals(resolvedSearchWidgetInfo.provider)) {
            if (Prefs.getString(context, Prefs.Key.SEARCH_APP_WIDGET_PACKAGE, null) == null) {
                Prefs.putString(context, Prefs.Key.SEARCH_APP_WIDGET_PACKAGE,
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
                Prefs.putInt(context, Prefs.Key.SEARCH_APP_WIDGET_ID, widgetInfo.first);
                Prefs.putString(context, Prefs.Key.SEARCH_APP_WIDGET_PACKAGE,
                        widgetInfo.second.provider.getPackageName());
                return widgetInfo.second;
            }
        }

        // If we fall through here, then there is no resolved search widget, so clear the state
        Prefs.remove(context, Prefs.Key.SEARCH_APP_WIDGET_ID);
        Prefs.remove(context, Prefs.Key.SEARCH_APP_WIDGET_PACKAGE);
        return null;
    }

    /**
     * Returns the first Recents widget from the same package as the global assist activity.
     */
    private AppWidgetProviderInfo resolveSearchAppWidget() {
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
     * Returns the window rect.
     */
    public Rect getWindowRect() {
        Rect windowRect = new Rect();
        if (mWm == null) return windowRect;

        Point p = new Point();
        mWm.getDefaultDisplay().getRealSize(p);
        windowRect.set(0, 0, p.x, p.y);
        return windowRect;
    }

    /** Starts an activity from recents. */
    public boolean startActivityFromRecents(Context context, int taskId, String taskName,
            ActivityOptions options) {
        if (mIam != null) {
            try {
                mIam.startActivityFromRecents(taskId, options == null ? null : options.toBundle());
                return true;
            } catch (Exception e) {
                Console.logError(context,
                        context.getString(R.string.recents_launch_error_message, taskName));
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
}
