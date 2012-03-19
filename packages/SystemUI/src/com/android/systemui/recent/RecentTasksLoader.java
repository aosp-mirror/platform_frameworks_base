/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.recent;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.tablet.TabletStatusBar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class RecentTasksLoader {
    static final String TAG = "RecentTasksLoader";
    static final boolean DEBUG = TabletStatusBar.DEBUG || PhoneStatusBar.DEBUG || false;

    private static final int DISPLAY_TASKS = 20;
    private static final int MAX_TASKS = DISPLAY_TASKS + 1; // allow extra for non-apps

    private Context mContext;
    private RecentsPanelView mRecentsPanel;

    private AsyncTask<Void, ArrayList<TaskDescription>, Void> mTaskLoader;
    private AsyncTask<Void, TaskDescription, Void> mThumbnailLoader;
    private final Handler mHandler;

    private int mIconDpi;
    private Bitmap mDefaultThumbnailBackground;
    private Bitmap mDefaultIconBackground;
    private int mNumTasksInFirstScreenful;

    public RecentTasksLoader(Context context) {
        mContext = context;

        final Resources res = context.getResources();

        // get the icon size we want -- on tablets, we use bigger icons
        boolean isTablet = res.getBoolean(R.bool.config_recents_interface_for_tablets);
        if (isTablet) {
            ActivityManager activityManager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            mIconDpi = activityManager.getLauncherLargeIconDensity();
        } else {
            mIconDpi = res.getDisplayMetrics().densityDpi;
        }

        // Render default icon (just a blank image)
        int defaultIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.app_icon_size);
        int iconSize = (int) (defaultIconSize * mIconDpi / res.getDisplayMetrics().densityDpi);
        mDefaultIconBackground = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);

        // Render the default thumbnail background
        int thumbnailWidth =
                (int) res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_width);
        int thumbnailHeight =
                (int) res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_height);
        int color = res.getColor(R.drawable.status_bar_recents_app_thumbnail_background);

        mDefaultThumbnailBackground =
                Bitmap.createBitmap(thumbnailWidth, thumbnailHeight, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(mDefaultThumbnailBackground);
        c.drawColor(color);

        // If we're using the cache, begin listening to the activity manager for
        // updated thumbnails
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);

        mHandler = new Handler();
    }

    public void setRecentsPanel(RecentsPanelView recentsPanel) {
        mRecentsPanel = recentsPanel;
        mNumTasksInFirstScreenful = mRecentsPanel.numItemsInOneScreenful();
    }

    public Bitmap getDefaultThumbnail() {
        return mDefaultThumbnailBackground;
    }

    public Bitmap getDefaultIcon() {
        return mDefaultIconBackground;
    }

    // Create an TaskDescription, returning null if the title or icon is null, or if it's the
    // home activity
    TaskDescription createTaskDescription(int taskId, int persistentTaskId, Intent baseIntent,
            ComponentName origActivity, CharSequence description, ActivityInfo homeInfo) {
        Intent intent = new Intent(baseIntent);
        if (origActivity != null) {
            intent.setComponent(origActivity);
        }
        final PackageManager pm = mContext.getPackageManager();
        if (homeInfo == null) {
            homeInfo = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .resolveActivityInfo(pm, 0);
        }
        // Don't load the current home activity.
        if (homeInfo != null
            && homeInfo.packageName.equals(intent.getComponent().getPackageName())
            && homeInfo.name.equals(intent.getComponent().getClassName())) {
            return null;
        }

        intent.setFlags((intent.getFlags()&~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
        if (resolveInfo != null) {
            final ActivityInfo info = resolveInfo.activityInfo;
            final String title = info.loadLabel(pm).toString();

            if (title != null && title.length() > 0) {
                if (DEBUG) Log.v(TAG, "creating activity desc for id="
                        + persistentTaskId + ", label=" + title);

                TaskDescription item = new TaskDescription(taskId,
                        persistentTaskId, resolveInfo, baseIntent, info.packageName,
                        description);
                item.setLabel(title);

                return item;
            } else {
                if (DEBUG) Log.v(TAG, "SKIPPING item " + persistentTaskId);
            }
        }
        return null;
    }

    void loadThumbnailAndIcon(TaskDescription td) {
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        final PackageManager pm = mContext.getPackageManager();
        ActivityManager.TaskThumbnails thumbs = am.getTaskThumbnails(td.persistentTaskId);
        Drawable icon = getFullResIcon(td.resolveInfo, pm);

        if (DEBUG) Log.v(TAG, "Loaded bitmap for task "
                + td + ": " + thumbs.mainThumbnail);
        synchronized (td) {
            if (thumbs != null && thumbs.mainThumbnail != null) {
                td.setThumbnail(thumbs.mainThumbnail);
            } else {
                td.setThumbnail(mDefaultThumbnailBackground);
            }
            if (icon != null) {
                td.setIcon(icon);
            }
            td.setLoaded(true);
        }
    }

    Drawable getFullResDefaultActivityIcon() {
        return getFullResIcon(Resources.getSystem(),
                com.android.internal.R.mipmap.sym_def_app_icon);
    }

    Drawable getFullResIcon(Resources resources, int iconId) {
        try {
            return resources.getDrawableForDensity(iconId, mIconDpi);
        } catch (Resources.NotFoundException e) {
            return getFullResDefaultActivityIcon();
        }
    }

    private Drawable getFullResIcon(ResolveInfo info, PackageManager packageManager) {
        Resources resources;
        try {
            resources = packageManager.getResourcesForApplication(
                    info.activityInfo.applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            resources = null;
        }
        if (resources != null) {
            int iconId = info.activityInfo.getIconResource();
            if (iconId != 0) {
                return getFullResIcon(resources, iconId);
            }
        }
        return getFullResDefaultActivityIcon();
    }

    public void cancelLoadingThumbnailsAndIcons() {
        if (mTaskLoader != null) {
            mTaskLoader.cancel(false);
            mTaskLoader = null;
        }
        if (mThumbnailLoader != null) {
            mThumbnailLoader.cancel(false);
            mThumbnailLoader = null;
        }
    }

    public void loadTasksInBackground() {
        // cancel all previous loading of tasks and thumbnails
        cancelLoadingThumbnailsAndIcons();
        final LinkedBlockingQueue<TaskDescription> tasksWaitingForThumbnails =
                new LinkedBlockingQueue<TaskDescription>();
        final ArrayList<TaskDescription> taskDescriptionsWaitingToLoad =
                new ArrayList<TaskDescription>();
        mTaskLoader = new AsyncTask<Void, ArrayList<TaskDescription>, Void>() {
            @Override
            protected void onProgressUpdate(ArrayList<TaskDescription>... values) {
                if (!isCancelled()) {
                    ArrayList<TaskDescription> newTasks = values[0];
                    // do a callback to RecentsPanelView to let it know we have more values
                    // how do we let it know we're all done? just always call back twice
                    mRecentsPanel.onTasksLoaded(newTasks);
                }
            }
            @Override
            protected Void doInBackground(Void... params) {
                // We load in two stages: first, we update progress with just the first screenful
                // of items. Then, we update with the rest of the items
                final int origPri = Process.getThreadPriority(Process.myTid());
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                final PackageManager pm = mContext.getPackageManager();
                final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);

                final List<ActivityManager.RecentTaskInfo> recentTasks =
                        am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
                int numTasks = recentTasks.size();
                ActivityInfo homeInfo = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME).resolveActivityInfo(pm, 0);

                boolean firstScreenful = true;
                ArrayList<TaskDescription> tasks = new ArrayList<TaskDescription>();

                // skip the first task - assume it's either the home screen or the current activity.
                final int first = 1;
                for (int i = first, index = 0; i < numTasks && (index < MAX_TASKS); ++i) {
                    if (isCancelled()) {
                        break;
                    }
                    final ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);
                    TaskDescription item = createTaskDescription(recentInfo.id,
                            recentInfo.persistentId, recentInfo.baseIntent,
                            recentInfo.origActivity, recentInfo.description, homeInfo);

                    if (item != null) {
                        while (true) {
                            try {
                                tasksWaitingForThumbnails.put(item);
                                break;
                            } catch (InterruptedException e) {
                            }
                        }
                        tasks.add(item);
                        if (firstScreenful && tasks.size() == mNumTasksInFirstScreenful) {
                            publishProgress(tasks);
                            tasks = new ArrayList<TaskDescription>();
                            firstScreenful = false;
                            //break;
                        }
                        ++index;
                    }
                }

                if (!isCancelled()) {
                    publishProgress(tasks);
                    if (firstScreenful) {
                        // always should publish two updates
                        publishProgress(new ArrayList<TaskDescription>());
                    }
                }

                while (true) {
                    try {
                        tasksWaitingForThumbnails.put(new TaskDescription());
                        break;
                    } catch (InterruptedException e) {
                    }
                }

                Process.setThreadPriority(origPri);
                return null;
            }
        };
        mTaskLoader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        loadThumbnailsAndIconsInBackground(tasksWaitingForThumbnails);
    }

    private void loadThumbnailsAndIconsInBackground(
            final BlockingQueue<TaskDescription> tasksWaitingForThumbnails) {
        // continually read items from tasksWaitingForThumbnails and load
        // thumbnails and icons for them. finish thread when cancelled or there
        // is a null item in tasksWaitingForThumbnails
        mThumbnailLoader = new AsyncTask<Void, TaskDescription, Void>() {
            @Override
            protected void onProgressUpdate(TaskDescription... values) {
                if (!isCancelled()) {
                    TaskDescription td = values[0];
                    mRecentsPanel.onTaskThumbnailLoaded(td);
                }
            }
            @Override
            protected Void doInBackground(Void... params) {
                final int origPri = Process.getThreadPriority(Process.myTid());
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

                while (true) {
                    if (isCancelled()) {
                        break;
                    }
                    TaskDescription td = null;
                    while (td == null) {
                        try {
                            td = tasksWaitingForThumbnails.take();
                        } catch (InterruptedException e) {
                        }
                    }
                    if (td.isNull()) {
                        break;
                    }
                    loadThumbnailAndIcon(td);
                    synchronized(td) {
                        publishProgress(td);
                    }
                }

                Process.setThreadPriority(origPri);
                return null;
            }
        };
        mThumbnailLoader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
