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

package com.android.systemui.recents.model;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.LruCache;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * A Task load queue
 */
class TaskResourceLoadQueue {
    ConcurrentLinkedQueue<Task> mQueue = new ConcurrentLinkedQueue<Task>();

    /** Adds a new task to the load queue */
    void addTask(Task t) {
        if (!mQueue.contains(t)) {
            mQueue.add(t);
        }
        synchronized(this) {
            notifyAll();
        }
    }

    /**
     * Retrieves the next task from the load queue, as well as whether we want that task to be
     * force reloaded.
     */
    Task nextTask() {
        return mQueue.poll();
    }

    /** Removes a task from the load queue */
    void removeTask(Task t) {
        mQueue.remove(t);
    }

    /** Clears all the tasks from the load queue */
    void clearTasks() {
        mQueue.clear();
    }

    /** Returns whether the load queue is empty */
    boolean isEmpty() {
        return mQueue.isEmpty();
    }
}

/**
 * Task resource loader
 */
class BackgroundTaskLoader implements Runnable {
    static String TAG = "TaskResourceLoader";
    static boolean DEBUG = false;

    Context mContext;
    HandlerThread mLoadThread;
    Handler mLoadThreadHandler;
    Handler mMainThreadHandler;

    TaskResourceLoadQueue mLoadQueue;
    TaskKeyLruCache<Drawable> mIconCache;
    TaskKeyLruCache<ThumbnailData> mThumbnailCache;
    Bitmap mDefaultThumbnail;
    BitmapDrawable mDefaultIcon;

    boolean mCancelled;
    boolean mWaitingOnLoadQueue;

    /** Constructor, creates a new loading thread that loads task resources in the background */
    public BackgroundTaskLoader(TaskResourceLoadQueue loadQueue,
            TaskKeyLruCache<Drawable> iconCache, TaskKeyLruCache<ThumbnailData> thumbnailCache,
            Bitmap defaultThumbnail, BitmapDrawable defaultIcon) {
        mLoadQueue = loadQueue;
        mIconCache = iconCache;
        mThumbnailCache = thumbnailCache;
        mDefaultThumbnail = defaultThumbnail;
        mDefaultIcon = defaultIcon;
        mMainThreadHandler = new Handler();
        mLoadThread = new HandlerThread("Recents-TaskResourceLoader",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mLoadThread.start();
        mLoadThreadHandler = new Handler(mLoadThread.getLooper());
        mLoadThreadHandler.post(this);
    }

    /** Restarts the loader thread */
    void start(Context context) {
        mContext = context;
        mCancelled = false;
        // Notify the load thread to start loading
        synchronized(mLoadThread) {
            mLoadThread.notifyAll();
        }
    }

    /** Requests the loader thread to stop after the current iteration */
    void stop() {
        // Mark as cancelled for the thread to pick up
        mCancelled = true;
        // If we are waiting for the load queue for more tasks, then we can just reset the
        // Context now, since nothing is using it
        if (mWaitingOnLoadQueue) {
            mContext = null;
        }
    }

    @Override
    public void run() {
        while (true) {
            if (mCancelled) {
                // We have to unset the context here, since the background thread may be using it
                // when we call stop()
                mContext = null;
                // If we are cancelled, then wait until we are started again
                synchronized(mLoadThread) {
                    try {
                        mLoadThread.wait();
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            } else {
                RecentsConfiguration config = Recents.getConfiguration();
                SystemServicesProxy ssp = Recents.getSystemServices();
                // If we've stopped the loader, then fall through to the above logic to wait on
                // the load thread
                if (ssp != null) {
                    // Load the next item from the queue
                    final Task t = mLoadQueue.nextTask();
                    if (t != null) {
                        Drawable cachedIcon = mIconCache.get(t.key);
                        ThumbnailData cachedThumbnailData = mThumbnailCache.get(t.key);

                        // Load the icon if it is stale or we haven't cached one yet
                        if (cachedIcon == null) {
                            cachedIcon = ssp.getBadgedTaskDescriptionIcon(t.taskDescription,
                                    t.key.userId, mContext.getResources());

                            if (cachedIcon == null) {
                                ActivityInfo info = ssp.getActivityInfo(
                                        t.key.getComponent(), t.key.userId);
                                if (info != null) {
                                    if (DEBUG) Log.d(TAG, "Loading icon: " + t.key);
                                    cachedIcon = ssp.getBadgedActivityIcon(info, t.key.userId);
                                }
                            }

                            if (cachedIcon == null) {
                                cachedIcon = mDefaultIcon;
                            }

                            // At this point, even if we can't load the icon, we will set the
                            // default icon.
                            mIconCache.put(t.key, cachedIcon);
                        }
                        // Load the thumbnail if it is stale or we haven't cached one yet
                        if (cachedThumbnailData == null) {
                            if (config.svelteLevel < RecentsConfiguration.SVELTE_DISABLE_LOADING) {
                                if (DEBUG) Log.d(TAG, "Loading thumbnail: " + t.key);
                                cachedThumbnailData = ssp.getTaskThumbnail(t.key.id);
                            }

                            if (cachedThumbnailData.thumbnail == null) {
                                cachedThumbnailData.thumbnail = mDefaultThumbnail;
                            } else {
                                // Kick off an early upload of the bitmap to GL so
                                // that this won't jank the first frame it's drawn in.
                                cachedThumbnailData.thumbnail.prepareToDraw();
                            }

                            // When svelte, we trim the memory to just the visible thumbnails when
                            // leaving, so don't thrash the cache as the user scrolls (just load
                            // them from scratch each time)
                            if (config.svelteLevel < RecentsConfiguration.SVELTE_LIMIT_CACHE) {
                                mThumbnailCache.put(t.key, cachedThumbnailData);
                            }
                        }
                        if (!mCancelled) {
                            // Notify that the task data has changed
                            final Drawable newIcon = cachedIcon;
                            final ThumbnailData newThumbnailData = cachedThumbnailData;
                            mMainThreadHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    t.notifyTaskDataLoaded(newThumbnailData.thumbnail, newIcon,
                                            newThumbnailData.thumbnailInfo);
                                }
                            });
                        }
                    }
                }

                // If there are no other items in the list, then just wait until something is added
                if (!mCancelled && mLoadQueue.isEmpty()) {
                    synchronized(mLoadQueue) {
                        try {
                            mWaitingOnLoadQueue = true;
                            mLoadQueue.wait();
                            mWaitingOnLoadQueue = false;
                        } catch (InterruptedException ie) {
                            ie.printStackTrace();
                        }
                    }
                }
            }
        }
    }
}

/**
 * Recents task loader
 */
public class RecentsTaskLoader {

    private static final String TAG = "RecentsTaskLoader";
    private static final boolean DEBUG = false;

    // This activity info LruCache is useful because it can be expensive to retrieve ActivityInfos
    // for many tasks, which we use to get the activity labels and icons.  Unlike the other caches
    // below, this is per-package so we can't invalidate the items in the cache based on the last
    // active time.  Instead, we rely on the RecentsPackageMonitor to keep us informed whenever a
    // package in the cache has been updated, so that we may remove it.
    private final LruCache<ComponentName, ActivityInfo> mActivityInfoCache;
    private final TaskKeyLruCache<Drawable> mIconCache;
    private final TaskKeyLruCache<ThumbnailData> mThumbnailCache;
    private final TaskKeyLruCache<String> mActivityLabelCache;
    private final TaskKeyLruCache<String> mContentDescriptionCache;
    private final TaskResourceLoadQueue mLoadQueue;
    private final BackgroundTaskLoader mLoader;

    private final int mMaxThumbnailCacheSize;
    private final int mMaxIconCacheSize;
    private int mNumVisibleTasksLoaded;
    private int mNumVisibleThumbnailsLoaded;

    int mDefaultTaskBarBackgroundColor;
    int mDefaultTaskViewBackgroundColor;
    BitmapDrawable mDefaultIcon;
    Bitmap mDefaultThumbnail;

    private TaskKeyLruCache.EvictionCallback mClearActivityInfoOnEviction =
            new TaskKeyLruCache.EvictionCallback() {
        @Override
        public void onEntryEvicted(Task.TaskKey key) {
            if (key != null) {
                mActivityInfoCache.remove(key.getComponent());
            }
        }
    };

    public RecentsTaskLoader(Context context) {
        Resources res = context.getResources();
        mDefaultTaskBarBackgroundColor =
                context.getColor(R.color.recents_task_bar_default_background_color);
        mDefaultTaskViewBackgroundColor =
                context.getColor(R.color.recents_task_view_default_background_color);
        mMaxThumbnailCacheSize = res.getInteger(R.integer.config_recents_max_thumbnail_count);
        mMaxIconCacheSize = res.getInteger(R.integer.config_recents_max_icon_count);
        int iconCacheSize = RecentsDebugFlags.Static.DisableBackgroundCache ? 1 :
                mMaxIconCacheSize;
        int thumbnailCacheSize = RecentsDebugFlags.Static.DisableBackgroundCache ? 1 :
                mMaxThumbnailCacheSize;

        // Create the default assets
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
        icon.eraseColor(0);
        mDefaultThumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        mDefaultThumbnail.setHasAlpha(false);
        mDefaultThumbnail.eraseColor(0xFFffffff);
        mDefaultIcon = new BitmapDrawable(context.getResources(), icon);

        // Initialize the proxy, cache and loaders
        int numRecentTasks = ActivityManager.getMaxRecentTasksStatic();
        mLoadQueue = new TaskResourceLoadQueue();
        mIconCache = new TaskKeyLruCache<>(iconCacheSize, mClearActivityInfoOnEviction);
        mThumbnailCache = new TaskKeyLruCache<>(thumbnailCacheSize);
        mActivityLabelCache = new TaskKeyLruCache<>(numRecentTasks, mClearActivityInfoOnEviction);
        mContentDescriptionCache = new TaskKeyLruCache<>(numRecentTasks,
                mClearActivityInfoOnEviction);
        mActivityInfoCache = new LruCache(numRecentTasks);
        mLoader = new BackgroundTaskLoader(mLoadQueue, mIconCache, mThumbnailCache,
                mDefaultThumbnail, mDefaultIcon);
    }

    /** Returns the size of the app icon cache. */
    public int getIconCacheSize() {
        return mMaxIconCacheSize;
    }

    /** Returns the size of the thumbnail cache. */
    public int getThumbnailCacheSize() {
        return mMaxThumbnailCacheSize;
    }

    /** Creates a new plan for loading the recent tasks. */
    public RecentsTaskLoadPlan createLoadPlan(Context context) {
        RecentsTaskLoadPlan plan = new RecentsTaskLoadPlan(context);
        return plan;
    }

    /** Preloads recents tasks using the specified plan to store the output. */
    public void preloadTasks(RecentsTaskLoadPlan plan, int runningTaskId,
            boolean includeFrontMostExcludedTask) {
        plan.preloadPlan(this, runningTaskId, includeFrontMostExcludedTask);
    }

    /** Begins loading the heavy task data according to the specified options. */
    public void loadTasks(Context context, RecentsTaskLoadPlan plan,
            RecentsTaskLoadPlan.Options opts) {
        if (opts == null) {
            throw new RuntimeException("Requires load options");
        }
        plan.executePlan(opts, this, mLoadQueue);
        if (!opts.onlyLoadForCache) {
            mNumVisibleTasksLoaded = opts.numVisibleTasks;
            mNumVisibleThumbnailsLoaded = opts.numVisibleTaskThumbnails;

            // Start the loader
            mLoader.start(context);
        }
    }

    /**
     * Acquires the task resource data directly from the cache, loading if necessary.
     */
    public void loadTaskData(Task t) {
        Drawable icon = mIconCache.getAndInvalidateIfModified(t.key);
        Bitmap thumbnail = null;
        ActivityManager.TaskThumbnailInfo thumbnailInfo = null;
        ThumbnailData thumbnailData = mThumbnailCache.getAndInvalidateIfModified(t.key);
        if (thumbnailData != null) {
            thumbnail = thumbnailData.thumbnail;
            thumbnailInfo = thumbnailData.thumbnailInfo;
        }

        // Grab the thumbnail/icon from the cache, if either don't exist, then trigger a reload and
        // use the default assets in their place until they load
        boolean requiresLoad = (icon == null) || (thumbnail == null);
        icon = icon != null ? icon : mDefaultIcon;
        if (requiresLoad) {
            mLoadQueue.addTask(t);
        }
        t.notifyTaskDataLoaded(thumbnail == mDefaultThumbnail ? null : thumbnail, icon,
                thumbnailInfo);
    }

    /** Releases the task resource data back into the pool. */
    public void unloadTaskData(Task t) {
        mLoadQueue.removeTask(t);
        t.notifyTaskDataUnloaded(null, mDefaultIcon);
    }

    /** Completely removes the resource data from the pool. */
    public void deleteTaskData(Task t, boolean notifyTaskDataUnloaded) {
        mLoadQueue.removeTask(t);
        mThumbnailCache.remove(t.key);
        mIconCache.remove(t.key);
        mActivityLabelCache.remove(t.key);
        mContentDescriptionCache.remove(t.key);
        if (notifyTaskDataUnloaded) {
            t.notifyTaskDataUnloaded(null, mDefaultIcon);
        }
    }

    /**
     * Handles signals from the system, trimming memory when requested to prevent us from running
     * out of memory.
     */
    public void onTrimMemory(int level) {
        RecentsConfiguration config = Recents.getConfiguration();
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                // Stop the loader immediately when the UI is no longer visible
                stopLoader();
                if (config.svelteLevel == RecentsConfiguration.SVELTE_NONE) {
                    mThumbnailCache.trimToSize(Math.max(mNumVisibleTasksLoaded,
                            mMaxThumbnailCacheSize / 2));
                } else if (config.svelteLevel == RecentsConfiguration.SVELTE_LIMIT_CACHE) {
                    mThumbnailCache.trimToSize(mNumVisibleThumbnailsLoaded);
                } else if (config.svelteLevel >= RecentsConfiguration.SVELTE_DISABLE_CACHE) {
                    mThumbnailCache.evictAll();
                }
                mIconCache.trimToSize(Math.max(mNumVisibleTasksLoaded,
                        mMaxIconCacheSize / 2));
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                // We are leaving recents, so trim the data a bit
                mThumbnailCache.trimToSize(Math.max(1, mMaxThumbnailCacheSize / 2));
                mIconCache.trimToSize(Math.max(1, mMaxIconCacheSize / 2));
                mActivityInfoCache.trimToSize(Math.max(1,
                        ActivityManager.getMaxRecentTasksStatic() / 2));
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                // We are going to be low on memory
                mThumbnailCache.trimToSize(Math.max(1, mMaxThumbnailCacheSize / 4));
                mIconCache.trimToSize(Math.max(1, mMaxIconCacheSize / 4));
                mActivityInfoCache.trimToSize(Math.max(1,
                        ActivityManager.getMaxRecentTasksStatic() / 4));
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // We are low on memory, so release everything
                mThumbnailCache.evictAll();
                mIconCache.evictAll();
                mActivityInfoCache.evictAll();
                // The cache is small, only clear the label cache when we are critical
                mActivityLabelCache.evictAll();
                mContentDescriptionCache.evictAll();
                break;
            default:
                break;
        }
    }

    /**
     * Returns the cached task label if the task key is not expired, updating the cache if it is.
     */
    String getAndUpdateActivityTitle(Task.TaskKey taskKey, ActivityManager.TaskDescription td) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Return the task description label if it exists
        if (td != null && td.getLabel() != null) {
            return td.getLabel();
        }
        // Return the cached activity label if it exists
        String label = mActivityLabelCache.getAndInvalidateIfModified(taskKey);
        if (label != null) {
            return label;
        }
        // All short paths failed, load the label from the activity info and cache it
        ActivityInfo activityInfo = getAndUpdateActivityInfo(taskKey);
        if (activityInfo != null) {
            label = ssp.getBadgedActivityLabel(activityInfo, taskKey.userId);
            mActivityLabelCache.put(taskKey, label);
            return label;
        }
        // If the activity info does not exist or fails to load, return an empty label for now,
        // but do not cache it
        return "";
    }

    /**
     * Returns the cached task content description if the task key is not expired, updating the
     * cache if it is.
     */
    String getAndUpdateContentDescription(Task.TaskKey taskKey, Resources res) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Return the cached content description if it exists
        String label = mContentDescriptionCache.getAndInvalidateIfModified(taskKey);
        if (label != null) {
            return label;
        }

        // All short paths failed, load the label from the activity info and cache it
        ActivityInfo activityInfo = getAndUpdateActivityInfo(taskKey);
        if (activityInfo != null) {
            label = ssp.getBadgedContentDescription(activityInfo, taskKey.userId, res);
            mContentDescriptionCache.put(taskKey, label);
            return label;
        }
        // If the content description does not exist, return an empty label for now, but do not
        // cache it
        return "";
    }

    /**
     * Returns the cached task icon if the task key is not expired, updating the cache if it is.
     */
    Drawable getAndUpdateActivityIcon(Task.TaskKey taskKey, ActivityManager.TaskDescription td,
            Resources res, boolean loadIfNotCached) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Return the cached activity icon if it exists
        Drawable icon = mIconCache.getAndInvalidateIfModified(taskKey);
        if (icon != null) {
            return icon;
        }

        if (loadIfNotCached) {
            // Return and cache the task description icon if it exists
            icon = ssp.getBadgedTaskDescriptionIcon(td, taskKey.userId, res);
            if (icon != null) {
                mIconCache.put(taskKey, icon);
                return icon;
            }

            // Load the icon from the activity info and cache it
            ActivityInfo activityInfo = getAndUpdateActivityInfo(taskKey);
            if (activityInfo != null) {
                icon = ssp.getBadgedActivityIcon(activityInfo, taskKey.userId);
                if (icon != null) {
                    mIconCache.put(taskKey, icon);
                    return icon;
                }
            }
        }
        // We couldn't load any icon
        return null;
    }

    /**
     * Returns the cached thumbnail if the task key is not expired, updating the cache if it is.
     */
    Bitmap getAndUpdateThumbnail(Task.TaskKey taskKey, boolean loadIfNotCached) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Return the cached thumbnail if it exists
        ThumbnailData thumbnailData = mThumbnailCache.getAndInvalidateIfModified(taskKey);
        if (thumbnailData != null) {
            return thumbnailData.thumbnail;
        }

        if (loadIfNotCached) {
            RecentsConfiguration config = Recents.getConfiguration();
            if (config.svelteLevel < RecentsConfiguration.SVELTE_DISABLE_LOADING) {
                // Load the thumbnail from the system
                thumbnailData = ssp.getTaskThumbnail(taskKey.id);
                if (thumbnailData.thumbnail != null) {
                    mThumbnailCache.put(taskKey, thumbnailData);
                    return thumbnailData.thumbnail;
                }
            }
        }
        // We couldn't load any thumbnail
        return null;
    }

    /**
     * Returns the task's primary color if possible, defaulting to the default color if there is
     * no specified primary color.
     */
    int getActivityPrimaryColor(ActivityManager.TaskDescription td) {
        if (td != null && td.getPrimaryColor() != 0) {
            return td.getPrimaryColor();
        }
        return mDefaultTaskBarBackgroundColor;
    }

    /**
     * Returns the task's background color if possible.
     */
    int getActivityBackgroundColor(ActivityManager.TaskDescription td) {
        if (td != null && td.getBackgroundColor() != 0) {
            return td.getBackgroundColor();
        }
        return mDefaultTaskViewBackgroundColor;
    }

    /**
     * Returns the activity info for the given task key, retrieving one from the system if the
     * task key is expired.
     */
    ActivityInfo getAndUpdateActivityInfo(Task.TaskKey taskKey) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        ComponentName cn = taskKey.getComponent();
        ActivityInfo activityInfo = mActivityInfoCache.get(cn);
        if (activityInfo == null) {
            activityInfo = ssp.getActivityInfo(cn, taskKey.userId);
            if (cn == null || activityInfo == null) {
                Log.e(TAG, "Unexpected null component name or activity info: " + cn + ", " +
                        activityInfo);
                return null;
            }
            mActivityInfoCache.put(cn, activityInfo);
        }
        return activityInfo;
    }

    /**
     * Stops the task loader and clears all queued, pending task loads.
     */
    private void stopLoader() {
        mLoader.stop();
        mLoadQueue.clearTasks();
    }

    /**** Event Bus Events ****/

    public final void onBusEvent(PackagesChangedEvent event) {
        // Remove all the cached activity infos for this package.  The other caches do not need to
        // be pruned at this time, as the TaskKey expiration checks will flush them next time their
        // cached contents are requested
        Map<ComponentName, ActivityInfo> activityInfoCache = mActivityInfoCache.snapshot();
        for (ComponentName cn : activityInfoCache.keySet()) {
            if (cn.getPackageName().equals(event.packageName)) {
                if (DEBUG) {
                    Log.d(TAG, "Removing activity info from cache: " + cn);
                }
                mActivityInfoCache.remove(cn);
            }
        }
    }

    public void dump(String prefix, PrintWriter writer) {
        String innerPrefix = prefix + "  ";

        writer.print(prefix); writer.println(TAG);
        writer.print(prefix); writer.println("Icon Cache");
        mIconCache.dump(innerPrefix, writer);
        writer.print(prefix); writer.println("Thumbnail Cache");
        mThumbnailCache.dump(innerPrefix, writer);
    }
}
