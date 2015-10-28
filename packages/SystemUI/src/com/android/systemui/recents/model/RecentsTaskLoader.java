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
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;

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
    TaskKeyLruCache<Drawable> mApplicationIconCache;
    TaskKeyLruCache<Bitmap> mThumbnailCache;
    Bitmap mDefaultThumbnail;
    BitmapDrawable mDefaultApplicationIcon;

    boolean mCancelled;
    boolean mWaitingOnLoadQueue;

    /** Constructor, creates a new loading thread that loads task resources in the background */
    public BackgroundTaskLoader(TaskResourceLoadQueue loadQueue,
            TaskKeyLruCache<Drawable> applicationIconCache, TaskKeyLruCache<Bitmap> thumbnailCache,
            Bitmap defaultThumbnail, BitmapDrawable defaultApplicationIcon) {
        mLoadQueue = loadQueue;
        mApplicationIconCache = applicationIconCache;
        mThumbnailCache = thumbnailCache;
        mDefaultThumbnail = defaultThumbnail;
        mDefaultApplicationIcon = defaultApplicationIcon;
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
                        Drawable cachedIcon = mApplicationIconCache.get(t.key);
                        Bitmap cachedThumbnail = mThumbnailCache.get(t.key);

                        // Load the application icon if it is stale or we haven't cached one yet
                        if (cachedIcon == null) {
                            cachedIcon = getTaskDescriptionIcon(t.key, t.icon, t.iconFilename, ssp,
                                    mContext.getResources());

                            if (cachedIcon == null) {
                                ActivityInfo info = ssp.getActivityInfo(
                                        t.key.getComponent(), t.key.userId);
                                if (info != null) {
                                    if (DEBUG) Log.d(TAG, "Loading icon: " + t.key);
                                    cachedIcon = ssp.getActivityIcon(info, t.key.userId);
                                }
                            }

                            if (cachedIcon == null) {
                                cachedIcon = mDefaultApplicationIcon;
                            }

                            // At this point, even if we can't load the icon, we will set the
                            // default icon.
                            mApplicationIconCache.put(t.key, cachedIcon);
                        }
                        // Load the thumbnail if it is stale or we haven't cached one yet
                        if (cachedThumbnail == null) {
                            if (config.svelteLevel < RecentsConfiguration.SVELTE_DISABLE_LOADING) {
                                if (DEBUG) Log.d(TAG, "Loading thumbnail: " + t.key);
                                cachedThumbnail = ssp.getTaskThumbnail(t.key.id);
                            }
                            if (cachedThumbnail == null) {
                                cachedThumbnail = mDefaultThumbnail;
                            }
                            // When svelte, we trim the memory to just the visible thumbnails when
                            // leaving, so don't thrash the cache as the user scrolls (just load
                            // them from scratch each time)
                            if (config.svelteLevel < RecentsConfiguration.SVELTE_LIMIT_CACHE) {
                                mThumbnailCache.put(t.key, cachedThumbnail);
                            }
                        }
                        if (!mCancelled) {
                            // Notify that the task data has changed
                            final Drawable newIcon = cachedIcon;
                            final Bitmap newThumbnail = cachedThumbnail == mDefaultThumbnail
                                    ? null : cachedThumbnail;
                            mMainThreadHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    t.notifyTaskDataLoaded(newThumbnail, newIcon);
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

    Drawable getTaskDescriptionIcon(Task.TaskKey taskKey, Bitmap iconBitmap, String iconFilename,
            SystemServicesProxy ssp, Resources res) {
        Bitmap tdIcon = iconBitmap != null
                ? iconBitmap
                : ActivityManager.TaskDescription.loadTaskDescriptionIcon(iconFilename);
        if (tdIcon != null) {
            return ssp.getBadgedIcon(new BitmapDrawable(res, tdIcon), taskKey.userId);
        }
        return null;
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
    private final TaskKeyLruCache<Drawable> mApplicationIconCache;
    private final TaskKeyLruCache<Bitmap> mThumbnailCache;
    private final TaskKeyLruCache<String> mActivityLabelCache;
    private final TaskKeyLruCache<String> mContentDescriptionCache;
    private final TaskResourceLoadQueue mLoadQueue;
    private final BackgroundTaskLoader mLoader;

    private final int mMaxThumbnailCacheSize;
    private final int mMaxIconCacheSize;
    private int mNumVisibleTasksLoaded;
    private int mNumVisibleThumbnailsLoaded;

    int mDefaultTaskBarBackgroundColor;
    BitmapDrawable mDefaultApplicationIcon;
    Bitmap mDefaultThumbnail;

    public RecentsTaskLoader(Context context) {
        Resources res = context.getResources();
        mDefaultTaskBarBackgroundColor =
                res.getColor(R.color.recents_task_bar_default_background_color);
        mMaxThumbnailCacheSize = res.getInteger(R.integer.config_recents_max_thumbnail_count);
        mMaxIconCacheSize = res.getInteger(R.integer.config_recents_max_icon_count);
        int iconCacheSize = Constants.DebugFlags.App.DisableBackgroundCache ? 1 :
                mMaxIconCacheSize;
        int thumbnailCacheSize = Constants.DebugFlags.App.DisableBackgroundCache ? 1 :
                mMaxThumbnailCacheSize;

        // Create the default assets
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
        icon.eraseColor(0);
        mDefaultThumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        mDefaultThumbnail.setHasAlpha(false);
        mDefaultThumbnail.eraseColor(0xFFffffff);
        mDefaultApplicationIcon = new BitmapDrawable(context.getResources(), icon);

        // Initialize the proxy, cache and loaders
        int numRecentTasks = ActivityManager.getMaxRecentTasksStatic();
        mLoadQueue = new TaskResourceLoadQueue();
        mApplicationIconCache = new TaskKeyLruCache<>(iconCacheSize);
        mThumbnailCache = new TaskKeyLruCache<>(thumbnailCacheSize);
        mActivityLabelCache = new TaskKeyLruCache<>(numRecentTasks);
        mContentDescriptionCache = new TaskKeyLruCache<>(numRecentTasks);
        mActivityInfoCache = new LruCache(numRecentTasks);
        mLoader = new BackgroundTaskLoader(mLoadQueue, mApplicationIconCache, mThumbnailCache,
                mDefaultThumbnail, mDefaultApplicationIcon);
    }

    /** Returns the size of the app icon cache. */
    public int getApplicationIconCacheSize() {
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
    public void preloadTasks(RecentsTaskLoadPlan plan, boolean isTopTaskHome) {
        plan.preloadPlan(this, isTopTaskHome);
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

    /** Acquires the task resource data directly from the pool. */
    public void loadTaskData(Task t) {
        Drawable applicationIcon = mApplicationIconCache.getAndInvalidateIfModified(t.key);
        Bitmap thumbnail = mThumbnailCache.getAndInvalidateIfModified(t.key);

        // Grab the thumbnail/icon from the cache, if either don't exist, then trigger a reload and
        // use the default assets in their place until they load
        boolean requiresLoad = (applicationIcon == null) || (thumbnail == null);
        applicationIcon = applicationIcon != null ? applicationIcon : mDefaultApplicationIcon;
        if (requiresLoad) {
            mLoadQueue.addTask(t);
        }
        t.notifyTaskDataLoaded(thumbnail == mDefaultThumbnail ? null : thumbnail, applicationIcon);
    }

    /** Releases the task resource data back into the pool. */
    public void unloadTaskData(Task t) {
        mLoadQueue.removeTask(t);
        t.notifyTaskDataUnloaded(null, mDefaultApplicationIcon);
    }

    /** Completely removes the resource data from the pool. */
    public void deleteTaskData(Task t, boolean notifyTaskDataUnloaded) {
        mLoadQueue.removeTask(t);
        mThumbnailCache.remove(t.key);
        mApplicationIconCache.remove(t.key);
        mActivityInfoCache.remove(t.key.getComponent());
        if (notifyTaskDataUnloaded) {
            t.notifyTaskDataUnloaded(null, mDefaultApplicationIcon);
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
                mApplicationIconCache.trimToSize(Math.max(mNumVisibleTasksLoaded,
                        mMaxIconCacheSize / 2));
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                // We are leaving recents, so trim the data a bit
                mThumbnailCache.trimToSize(Math.max(1, mMaxThumbnailCacheSize / 2));
                mApplicationIconCache.trimToSize(Math.max(1, mMaxIconCacheSize / 2));
                mActivityInfoCache.trimToSize(Math.max(1,
                        ActivityManager.getMaxRecentTasksStatic() / 2));
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                // We are going to be low on memory
                mThumbnailCache.trimToSize(Math.max(1, mMaxThumbnailCacheSize / 4));
                mApplicationIconCache.trimToSize(Math.max(1, mMaxIconCacheSize / 4));
                mActivityInfoCache.trimToSize(Math.max(1,
                        ActivityManager.getMaxRecentTasksStatic() / 4));
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // We are low on memory, so release everything
                mThumbnailCache.evictAll();
                mApplicationIconCache.evictAll();
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
    String getAndUpdateActivityLabel(Task.TaskKey taskKey, ActivityManager.TaskDescription td,
                                     SystemServicesProxy ssp) {
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
        ActivityInfo activityInfo = getAndUpdateActivityInfo(taskKey, ssp);
        if (activityInfo != null) {
            label = ssp.getActivityLabel(activityInfo);
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
    String getAndUpdateContentDescription(Task.TaskKey taskKey, String activityLabel,
            SystemServicesProxy ssp, Resources res) {
        // Return the cached content description if it exists
        String label = mContentDescriptionCache.getAndInvalidateIfModified(taskKey);
        if (label != null) {
            return label;
        }
        // If the given activity label is empty, don't compute or cache the content description
        if (activityLabel.isEmpty()) {
            return "";
        }

        label = ssp.getContentDescription(taskKey.baseIntent, taskKey.userId, activityLabel, res);
        if (label != null) {
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
            SystemServicesProxy ssp, Resources res, boolean loadIfNotCached) {
        // Return the cached activity icon if it exists
        Drawable icon = mApplicationIconCache.getAndInvalidateIfModified(taskKey);
        if (icon != null) {
            return icon;
        }

        if (loadIfNotCached) {
            // Return and cache the task description icon if it exists
            icon = mLoader.getTaskDescriptionIcon(taskKey, td.getInMemoryIcon(),
                    td.getIconFilename(), ssp, res);
            if (icon != null) {
                mApplicationIconCache.put(taskKey, icon);
                return icon;
            }

            // Load the icon from the activity info and cache it
            ActivityInfo activityInfo = getAndUpdateActivityInfo(taskKey, ssp);
            if (activityInfo != null) {
                icon = ssp.getActivityIcon(activityInfo, taskKey.userId);
                if (icon != null) {
                    mApplicationIconCache.put(taskKey, icon);
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
    Bitmap getAndUpdateThumbnail(Task.TaskKey taskKey, SystemServicesProxy ssp,
            boolean loadIfNotCached) {
        // Return the cached thumbnail if it exists
        Bitmap thumbnail = mThumbnailCache.getAndInvalidateIfModified(taskKey);
        if (thumbnail != null) {
            return thumbnail;
        }

        if (loadIfNotCached) {
            RecentsConfiguration config = Recents.getConfiguration();
            if (config.svelteLevel < RecentsConfiguration.SVELTE_DISABLE_LOADING) {
                // Load the thumbnail from the system
                thumbnail = ssp.getTaskThumbnail(taskKey.id);
                if (thumbnail != null) {
                    mThumbnailCache.put(taskKey, thumbnail);
                    return thumbnail;
                }
            }
        }
        // We couldn't load any thumbnail
        return null;
    }

    /**
     * Returns the task's primary color.
     */
    int getActivityPrimaryColor(ActivityManager.TaskDescription td) {
        if (td != null && td.getPrimaryColor() != 0) {
            return td.getPrimaryColor();
        }
        return mDefaultTaskBarBackgroundColor;
    }

    /**
     * Returns the activity info for the given task key, retrieving one from the system if the
     * task key is expired.
     */
    private ActivityInfo getAndUpdateActivityInfo(Task.TaskKey taskKey, SystemServicesProxy ssp) {
        ComponentName cn = taskKey.getComponent();
        ActivityInfo activityInfo = mActivityInfoCache.get(cn);
        if (activityInfo == null) {
            activityInfo = ssp.getActivityInfo(cn, taskKey.userId);
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
}
