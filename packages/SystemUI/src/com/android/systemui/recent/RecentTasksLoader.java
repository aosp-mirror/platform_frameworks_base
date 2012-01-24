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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.tablet.TabletStatusBar;

public class RecentTasksLoader {
    static final String TAG = "RecentTasksLoader";
    static final boolean DEBUG = TabletStatusBar.DEBUG || PhoneStatusBar.DEBUG || false;

    private static final int DISPLAY_TASKS = 20;
    private static final int MAX_TASKS = DISPLAY_TASKS + 1; // allow extra for non-apps

    private Context mContext;
    private RecentsPanelView mRecentsPanel;

    private AsyncTask<Void, Integer, Void> mThumbnailLoader;
    private final Handler mHandler;

    private int mIconDpi;
    private Bitmap mDefaultThumbnailBackground;

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

        // Render the default thumbnail background
        int width = (int) res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_width);
        int height = (int) res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_height);
        int color = res.getColor(R.drawable.status_bar_recents_app_thumbnail_background);

        mDefaultThumbnailBackground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
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
    }

    public Bitmap getDefaultThumbnail() {
        return mDefaultThumbnailBackground;
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

        intent.setFlags((intent.getFlags()&~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
        if (resolveInfo != null) {
            final ActivityInfo info = resolveInfo.activityInfo;
            final String title = info.loadLabel(pm).toString();
            Drawable icon = getFullResIcon(resolveInfo, pm);

            if (title != null && title.length() > 0 && icon != null) {
                if (DEBUG) Log.v(TAG, "creating activity desc for id="
                        + persistentTaskId + ", label=" + title);

                TaskDescription item = new TaskDescription(taskId,
                        persistentTaskId, resolveInfo, baseIntent, info.packageName,
                        description);
                item.setLabel(title);
                item.setIcon(icon);

                // Don't load the current home activity.
                if (homeInfo != null
                        && homeInfo.packageName.equals(intent.getComponent().getPackageName())
                        && homeInfo.name.equals(intent.getComponent().getClassName())) {
                    return null;
                }

                return item;
            } else {
                if (DEBUG) Log.v(TAG, "SKIPPING item " + persistentTaskId);
            }
        }
        return null;
    }

    void loadThumbnail(TaskDescription td) {
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.TaskThumbnails thumbs = am.getTaskThumbnails(td.persistentTaskId);

        if (DEBUG) Log.v(TAG, "Loaded bitmap for task "
                + td + ": " + thumbs.mainThumbnail);
        synchronized (td) {
            if (thumbs != null && thumbs.mainThumbnail != null) {
                td.setThumbnail(thumbs.mainThumbnail);
            } else {
                td.setThumbnail(mDefaultThumbnailBackground);
            }
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

    public void cancelLoadingThumbnails() {
        if (mThumbnailLoader != null) {
            mThumbnailLoader.cancel(false);
            mThumbnailLoader = null;
        }
    }

    // return a snapshot of the current list of recent apps
    ArrayList<TaskDescription> getRecentTasks() {
        cancelLoadingThumbnails();

        ArrayList<TaskDescription> tasks = new ArrayList<TaskDescription>();
        final PackageManager pm = mContext.getPackageManager();
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);

        final List<ActivityManager.RecentTaskInfo> recentTasks =
                am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE);

        ActivityInfo homeInfo = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .resolveActivityInfo(pm, 0);

        HashSet<Integer> recentTasksToKeepInCache = new HashSet<Integer>();
        int numTasks = recentTasks.size();

        // skip the first task - assume it's either the home screen or the current activity.
        final int first = 1;
        recentTasksToKeepInCache.add(recentTasks.get(0).persistentId);
        for (int i = first, index = 0; i < numTasks && (index < MAX_TASKS); ++i) {
            final ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);

            TaskDescription item = createTaskDescription(recentInfo.id,
                    recentInfo.persistentId, recentInfo.baseIntent,
                    recentInfo.origActivity, recentInfo.description, homeInfo);

            if (item != null) {
                tasks.add(item);
                ++index;
            }
        }

        // when we're not using the TaskDescription cache, we load the thumbnails in the
        // background
        loadThumbnailsInBackground(new ArrayList<TaskDescription>(tasks));
        return tasks;
    }

    private void loadThumbnailsInBackground(final ArrayList<TaskDescription> descriptions) {
        if (descriptions.size() > 0) {
            if (DEBUG) Log.v(TAG, "Showing " + descriptions.size() + " tasks");
            loadThumbnail(descriptions.get(0));
            if (descriptions.size() > 1) {
                mThumbnailLoader = new AsyncTask<Void, Integer, Void>() {
                    @Override
                    protected void onProgressUpdate(Integer... values) {
                        final TaskDescription td = descriptions.get(values[0]);
                        if (!isCancelled()) {
                            mRecentsPanel.onTaskThumbnailLoaded(td);
                        }
                        // This is to prevent the loader thread from getting ahead
                        // of our UI updates.
                        mHandler.post(new Runnable() {
                            @Override public void run() {
                                synchronized (td) {
                                    td.notifyAll();
                                }
                            }
                        });
                    }

                    @Override
                    protected Void doInBackground(Void... params) {
                        final int origPri = Process.getThreadPriority(Process.myTid());
                        Process.setThreadPriority(Process.THREAD_GROUP_BG_NONINTERACTIVE);
                        long nextTime = SystemClock.uptimeMillis();
                        for (int i=1; i<descriptions.size(); i++) {
                            TaskDescription td = descriptions.get(i);
                            loadThumbnail(td);
                            long now = SystemClock.uptimeMillis();
                            nextTime += 0;
                            if (nextTime > now) {
                                try {
                                    Thread.sleep(nextTime-now);
                                } catch (InterruptedException e) {
                                }
                            }

                            if (isCancelled()) {
                                break;
                            }
                            synchronized (td) {
                                publishProgress(i);
                                try {
                                    td.wait(500);
                                } catch (InterruptedException e) {
                                }
                            }
                        }
                        Process.setThreadPriority(origPri);
                        return null;
                    }
                };
                mThumbnailLoader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        }
    }

}
