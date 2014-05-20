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

package com.android.systemui.recents;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.util.LruCache;
import android.util.Pair;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


/** A bitmap load queue */
class TaskResourceLoadQueue {
    ConcurrentLinkedQueue<Task> mQueue = new ConcurrentLinkedQueue<Task>();
    ConcurrentHashMap<Task.TaskKey, Boolean> mForceLoadSet =
            new ConcurrentHashMap<Task.TaskKey, Boolean>();

    static final Boolean sFalse = new Boolean(false);

    /** Adds a new task to the load queue */
    void addTask(Task t, boolean forceLoad) {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "  [TaskResourceLoadQueue|addTask]");
        }
        if (!mQueue.contains(t)) {
            mQueue.add(t);
        }
        if (forceLoad) {
            mForceLoadSet.put(t.key, new Boolean(true));
        }
        synchronized(this) {
            notifyAll();
        }
    }

    /**
     * Retrieves the next task from the load queue, as well as whether we want that task to be
     * force reloaded.
     */
    Pair<Task, Boolean> nextTask() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "  [TaskResourceLoadQueue|nextTask]");
        }
        Task task = mQueue.poll();
        Boolean forceLoadTask = null;
        if (task != null) {
            forceLoadTask = mForceLoadSet.remove(task.key);
        }
        if (forceLoadTask == null) {
            forceLoadTask = sFalse;
        }
        return new Pair<Task, Boolean>(task, forceLoadTask);
    }

    /** Removes a task from the load queue */
    void removeTask(Task t) {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "  [TaskResourceLoadQueue|removeTask]");
        }
        mQueue.remove(t);
        mForceLoadSet.remove(t.key);
    }

    /** Clears all the tasks from the load queue */
    void clearTasks() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "  [TaskResourceLoadQueue|clearTasks]");
        }
        mQueue.clear();
        mForceLoadSet.clear();
    }

    /** Returns whether the load queue is empty */
    boolean isEmpty() {
        return mQueue.isEmpty();
    }
}

/* Task resource loader */
class TaskResourceLoader implements Runnable {
    Context mContext;
    HandlerThread mLoadThread;
    Handler mLoadThreadHandler;
    Handler mMainThreadHandler;

    SystemServicesProxy mSystemServicesProxy;
    TaskResourceLoadQueue mLoadQueue;
    DrawableLruCache mApplicationIconCache;
    BitmapLruCache mThumbnailCache;

    boolean mCancelled;
    boolean mWaitingOnLoadQueue;

    /** Constructor, creates a new loading thread that loads task resources in the background */
    public TaskResourceLoader(TaskResourceLoadQueue loadQueue,
                              DrawableLruCache applicationIconCache,
                              BitmapLruCache thumbnailCache) {
        mLoadQueue = loadQueue;
        mApplicationIconCache = applicationIconCache;
        mThumbnailCache = thumbnailCache;
        mMainThreadHandler = new Handler();
        mLoadThread = new HandlerThread("Recents-TaskResourceLoader");
        mLoadThread.setPriority(Thread.NORM_PRIORITY - 1);
        mLoadThread.start();
        mLoadThreadHandler = new Handler(mLoadThread.getLooper());
        mLoadThreadHandler.post(this);
    }

    /** Restarts the loader thread */
    void start(Context context) {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "[TaskResourceLoader|start]");
        }
        mContext = context;
        mCancelled = false;
        mSystemServicesProxy = new SystemServicesProxy(context);
        // Notify the load thread to start loading
        synchronized(mLoadThread) {
            mLoadThread.notifyAll();
        }
    }

    /** Requests the loader thread to stop after the current iteration */
    void stop() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "[TaskResourceLoader|stop]");
        }
        // Mark as cancelled for the thread to pick up
        mCancelled = true;
        mSystemServicesProxy = null;
        // If we are waiting for the load queue for more tasks, then we can just reset the
        // Context now, since nothing is using it
        if (mWaitingOnLoadQueue) {
            mContext = null;
        }
    }

    @Override
    public void run() {
        while (true) {
            if (Console.Enabled) {
                Console.log(Constants.Log.App.TaskDataLoader,
                        "[TaskResourceLoader|run|" + Thread.currentThread().getId() + "]");
            }
            if (mCancelled) {
                if (Console.Enabled) {
                    Console.log(Constants.Log.App.TaskDataLoader,
                            "[TaskResourceLoader|cancel|" + Thread.currentThread().getId() + "]");
                }
                // We have to unset the context here, since the background thread may be using it
                // when we call stop()
                mContext = null;
                // If we are cancelled, then wait until we are started again
                synchronized(mLoadThread) {
                    try {
                        if (Console.Enabled) {
                            Console.log(Constants.Log.App.TaskDataLoader,
                                    "[TaskResourceLoader|waitOnLoadThreadCancelled]");
                        }
                        mLoadThread.wait();
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            } else {
                SystemServicesProxy ssp = mSystemServicesProxy;

                // Load the next item from the queue
                Pair<Task, Boolean> nextTaskData = mLoadQueue.nextTask();
                final Task t = nextTaskData.first;
                final boolean forceLoadTask = nextTaskData.second;
                if (t != null) {
                    Drawable loadIcon = mApplicationIconCache.get(t.key);
                    Bitmap loadThumbnail = mThumbnailCache.get(t.key);
                    if (Console.Enabled) {
                        Console.log(Constants.Log.App.TaskDataLoader,
                                "  [TaskResourceLoader|load]",
                                t + " icon: " + loadIcon + " thumbnail: " + loadThumbnail +
                                        " forceLoad: " + forceLoadTask);
                    }
                    // Load the application icon
                    if (loadIcon == null || forceLoadTask) {
                        ActivityInfo info = ssp.getActivityInfo(t.key.baseIntent.getComponent(),
                                t.userId);
                        Drawable icon = ssp.getActivityIcon(info, t.userId);
                        if (!mCancelled) {
                            if (icon != null) {
                                if (Console.Enabled) {
                                    Console.log(Constants.Log.App.TaskDataLoader,
                                            "    [TaskResourceLoader|loadIcon]", icon);
                                }
                                loadIcon = icon;
                                mApplicationIconCache.put(t.key, icon);
                            }
                        }
                    }
                    // Load the thumbnail
                    if (loadThumbnail == null || forceLoadTask) {
                        Bitmap thumbnail = ssp.getTaskThumbnail(t.key.id);
                        if (!mCancelled) {
                            if (thumbnail != null) {
                                if (Console.Enabled) {
                                    Console.log(Constants.Log.App.TaskDataLoader,
                                            "    [TaskResourceLoader|loadThumbnail]", thumbnail);
                                }
                                thumbnail.setHasAlpha(false);
                                loadThumbnail = thumbnail;
                                mThumbnailCache.put(t.key, thumbnail);
                            } else {
                                Console.logError(mContext,
                                        "Failed to load task top thumbnail for: " +
                                                t.key.baseIntent.getComponent().getPackageName());
                            }
                        }
                    }
                    if (!mCancelled) {
                        // Notify that the task data has changed
                        final Drawable newIcon = loadIcon;
                        final Bitmap newThumbnail = loadThumbnail;
                        mMainThreadHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                t.notifyTaskDataLoaded(newThumbnail, newIcon, forceLoadTask);
                            }
                        });
                    }
                }

                // If there are no other items in the list, then just wait until something is added
                if (!mCancelled && mLoadQueue.isEmpty()) {
                    synchronized(mLoadQueue) {
                        try {
                            if (Console.Enabled) {
                                Console.log(Constants.Log.App.TaskDataLoader,
                                        "[TaskResourceLoader|waitOnLoadQueue]");
                            }
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
 * The drawable cache.  By using the Task's key, we can prevent holding onto a reference to the Task
 * resource data, while keeping the cache data in memory where necessary.
 */
class DrawableLruCache extends LruCache<Task.TaskKey, Drawable> {
    public DrawableLruCache(int cacheSize) {
        super(cacheSize);
    }

    @Override
    protected int sizeOf(Task.TaskKey t, Drawable d) {
        // The cache size will be measured in kilobytes rather than number of items
        // NOTE: this isn't actually correct, as the icon may be smaller
        int maxBytes = (d.getIntrinsicWidth() * d.getIntrinsicHeight() * 4);
        return maxBytes / 1024;
    }
}

/**
 * The bitmap cache.  By using the Task's key, we can prevent holding onto a reference to the Task
 * resource data, while keeping the cache data in memory where necessary.
 */
class BitmapLruCache extends LruCache<Task.TaskKey, Bitmap> {
    public BitmapLruCache(int cacheSize) {
        super(cacheSize);
    }

    @Override
    protected int sizeOf(Task.TaskKey t, Bitmap bitmap) {
        // The cache size will be measured in kilobytes rather than number of items
        return bitmap.getAllocationByteCount() / 1024;
    }
}

/* Recents task loader
 * NOTE: We should not hold any references to a Context from a static instance */
public class RecentsTaskLoader {
    static RecentsTaskLoader sInstance;

    SystemServicesProxy mSystemServicesProxy;
    DrawableLruCache mApplicationIconCache;
    BitmapLruCache mThumbnailCache;
    TaskResourceLoadQueue mLoadQueue;
    TaskResourceLoader mLoader;

    RecentsPackageMonitor mPackageMonitor;

    int mMaxThumbnailCacheSize;
    int mMaxIconCacheSize;

    BitmapDrawable mDefaultApplicationIcon;
    Bitmap mDefaultThumbnail;

    /** Private Constructor */
    private RecentsTaskLoader(Context context) {
        // Calculate the cache sizes, we just use a reasonable number here similar to those
        // suggested in the Android docs, 1/8th for the thumbnail cache and 1/32 of the max memory
        // for icons.
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        mMaxThumbnailCacheSize = maxMemory / 8;
        mMaxIconCacheSize = mMaxThumbnailCacheSize / 4;
        int iconCacheSize = Constants.DebugFlags.App.DisableBackgroundCache ? 1 :
                mMaxIconCacheSize;
        int thumbnailCacheSize = Constants.DebugFlags.App.DisableBackgroundCache ? 1 :
                mMaxThumbnailCacheSize;

        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader,
                    "[RecentsTaskLoader|init]", "thumbnailCache: " + thumbnailCacheSize +
                    " iconCache: " + iconCacheSize);
        }

        // Initialize the proxy, cache and loaders
        mSystemServicesProxy = new SystemServicesProxy(context);
        mPackageMonitor = new RecentsPackageMonitor(context);
        mLoadQueue = new TaskResourceLoadQueue();
        mApplicationIconCache = new DrawableLruCache(iconCacheSize);
        mThumbnailCache = new BitmapLruCache(thumbnailCacheSize);
        mLoader = new TaskResourceLoader(mLoadQueue, mApplicationIconCache, mThumbnailCache);

        // Create the default assets
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        icon.eraseColor(0x00000000);
        mDefaultThumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        mDefaultThumbnail.eraseColor(0x00000000);
        mDefaultApplicationIcon = new BitmapDrawable(context.getResources(), icon);
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader,
                    "[RecentsTaskLoader|defaultBitmaps]",
                    "icon: " + mDefaultApplicationIcon + " thumbnail: " + mDefaultThumbnail, Console.AnsiRed);
        }
    }

    /** Initializes the recents task loader */
    public static RecentsTaskLoader initialize(Context context) {
        if (sInstance == null) {
            sInstance = new RecentsTaskLoader(context);
        }
        return sInstance;
    }

    /** Returns the current recents task loader */
    public static RecentsTaskLoader getInstance() {
        return sInstance;
    }

    /** Returns the system services proxy */
    public SystemServicesProxy getSystemServicesProxy() {
        return mSystemServicesProxy;
    }

    private List<ActivityManager.RecentTaskInfo> getRecentTasks() {
        long t1 = System.currentTimeMillis();

        SystemServicesProxy ssp = mSystemServicesProxy;
        List<ActivityManager.RecentTaskInfo> tasks =
                ssp.getRecentTasks(25, UserHandle.CURRENT.getIdentifier());
        Collections.reverse(tasks);
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TimeSystemCalls,
                    "[RecentsTaskLoader|getRecentTasks]",
                    "" + (System.currentTimeMillis() - t1) + "ms");
            Console.log(Constants.Log.App.TaskDataLoader,
                    "[RecentsTaskLoader|tasks]", "" + tasks.size());
        }

        return tasks;
    }

    /** Reload the set of recent tasks */
    SpaceNode reload(Context context, int preloadCount) {
        long t1 = System.currentTimeMillis();

        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "[RecentsTaskLoader|reload]");
        }
        Resources res = context.getResources();
        ArrayList<Task> tasksToForceLoad = new ArrayList<Task>();
        TaskStack stack = new TaskStack(context);
        SpaceNode root = new SpaceNode(context);
        root.setStack(stack);

        // Get the recent tasks
        SystemServicesProxy ssp = mSystemServicesProxy;
        List<ActivityManager.RecentTaskInfo> tasks = getRecentTasks();

        // Add each task to the task stack
        t1 = System.currentTimeMillis();
        int taskCount = tasks.size();
        for (int i = 0; i < taskCount; i++) {
            ActivityManager.RecentTaskInfo t = tasks.get(i);
            ActivityInfo info = ssp.getActivityInfo(t.baseIntent.getComponent(), t.userId);
            if (info == null) continue;

            ActivityManager.TaskDescription av = t.taskDescription;
            String activityLabel = null;
            BitmapDrawable activityIcon = null;
            int activityColor = 0;
            if (av != null) {
                activityLabel = (av.getLabel() != null ? av.getLabel() : ssp.getActivityLabel(info));
                activityIcon = (av.getIcon() != null) ? new BitmapDrawable(res, av.getIcon()) : null;
                activityColor = av.getPrimaryColor();
            } else {
                activityLabel = ssp.getActivityLabel(info);
            }
            boolean isForemostTask = (i == (taskCount - 1));

            // Create a new task
            Task task = new Task(t.persistentId, (t.id > -1), t.baseIntent, activityLabel,
                    activityIcon, activityColor, t.userId);

            // Preload the specified number of apps
            if (i >= (taskCount - preloadCount)) {
                if (Console.Enabled) {
                    Console.log(Constants.Log.App.TaskDataLoader,
                            "[RecentsTaskLoader|preloadTask]",
                            "i: " + i + " task: " + t.baseIntent.getComponent().getPackageName());
                }

                // Load the icon (if possible and not the foremost task, from the cache)
                if (!isForemostTask) {
                    task.applicationIcon = mApplicationIconCache.get(task.key);
                    if (task.applicationIcon != null) {
                        // Even though we get things from the cache, we should update them
                        // if they've changed in the bg
                        tasksToForceLoad.add(task);
                    }
                }
                if (task.applicationIcon == null) {
                    task.applicationIcon = ssp.getActivityIcon(info, task.userId);
                    if (task.applicationIcon != null) {
                        mApplicationIconCache.put(task.key, task.applicationIcon);
                    } else {
                        task.applicationIcon = mDefaultApplicationIcon;
                    }
                }

                // Load the thumbnail (if possible and not the foremost task, from the cache)
                if (!isForemostTask) {
                    task.thumbnail = mThumbnailCache.get(task.key);
                    if (task.thumbnail != null) {
                        // Even though we get things from the cache, we should update them if
                        // they've changed in the bg
                        tasksToForceLoad.add(task);
                    }
                }
                if (task.thumbnail == null) {
                    if (Console.Enabled) {
                        Console.log(Constants.Log.App.TaskDataLoader,
                                "[RecentsTaskLoader|loadingTaskThumbnail]");
                    }
                    task.thumbnail = ssp.getTaskThumbnail(task.key.id);
                    if (task.thumbnail != null) {
                        task.thumbnail.setHasAlpha(false);
                        mThumbnailCache.put(task.key, task.thumbnail);
                    } else {
                        task.thumbnail = mDefaultThumbnail;
                    }
                }
            }

            // Add the task to the stack
            if (Console.Enabled) {
                Console.log(Constants.Log.App.TaskDataLoader,
                        "  [RecentsTaskLoader|task]", t.baseIntent.getComponent().getPackageName());
            }
            stack.addTask(task);
        }
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TimeSystemCalls,
                    "[RecentsTaskLoader|getAllTaskTopThumbnail]",
                    "" + (System.currentTimeMillis() - t1) + "ms");
        }

        /*
        // Get all the stacks
        t1 = System.currentTimeMillis();
        List<ActivityManager.StackInfo> stackInfos = ams.getAllStackInfos();
        Console.log(Constants.Log.App.TimeSystemCalls, "[RecentsTaskLoader|getAllStackInfos]", "" + (System.currentTimeMillis() - t1) + "ms");
        Console.log(Constants.Log.App.TaskDataLoader, "[RecentsTaskLoader|stacks]", "" + tasks.size());
        for (ActivityManager.StackInfo s : stackInfos) {
            Console.log(Constants.Log.App.TaskDataLoader, "  [RecentsTaskLoader|stack]", s.toString());
            if (stacks.containsKey(s.stackId)) {
                stacks.get(s.stackId).setRect(s.bounds);
            }
        }
        */

        // Start the task loader
        mLoader.start(context);

        // Add all the tasks that we are force/re-loading
        for (Task t : tasksToForceLoad) {
            mLoadQueue.addTask(t, true);
        }

        // Update the package monitor with the list of packages to listen for
        mPackageMonitor.setTasks(tasks);

        return root;
    }

    /** Acquires the task resource data from the pool. */
    public void loadTaskData(Task t) {
        Drawable applicationIcon = mApplicationIconCache.get(t.key);
        Bitmap thumbnail = mThumbnailCache.get(t.key);

        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "[RecentsTaskLoader|loadTask]",
                    t + " applicationIcon: " + applicationIcon + " thumbnail: " + thumbnail +
                            " thumbnailCacheSize: " + mThumbnailCache.size());
        }

        boolean requiresLoad = false;
        if (applicationIcon == null) {
            applicationIcon = mDefaultApplicationIcon;
            requiresLoad = true;
        }
        if (thumbnail == null) {
            thumbnail = mDefaultThumbnail;
            requiresLoad = true;
        }
        if (requiresLoad) {
            mLoadQueue.addTask(t, false);
        }
        t.notifyTaskDataLoaded(thumbnail, applicationIcon, false);
    }

    /** Releases the task resource data back into the pool. */
    public void unloadTaskData(Task t) {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader,
                    "[RecentsTaskLoader|unloadTask]", t +
                    " thumbnailCacheSize: " + mThumbnailCache.size());
        }

        mLoadQueue.removeTask(t);
        t.notifyTaskDataUnloaded(mDefaultThumbnail, mDefaultApplicationIcon);
    }

    /** Completely removes the resource data from the pool. */
    public void deleteTaskData(Task t, boolean notifyTaskDataUnloaded) {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader,
                    "[RecentsTaskLoader|deleteTask]", t);
        }

        mLoadQueue.removeTask(t);
        mThumbnailCache.remove(t.key);
        mApplicationIconCache.remove(t.key);
        if (notifyTaskDataUnloaded) {
            t.notifyTaskDataUnloaded(mDefaultThumbnail, mDefaultApplicationIcon);
        }
    }

    /** Stops the task loader and clears all pending tasks */
    void stopLoader() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.TaskDataLoader, "[RecentsTaskLoader|stopLoader]");
        }
        mLoader.stop();
        mLoadQueue.clearTasks();
    }

    /** Registers any broadcast receivers. */
    public void registerReceivers(Context context, RecentsPackageMonitor.PackageCallbacks cb) {
        // Register the broadcast receiver to handle messages related to packages being added/removed
        mPackageMonitor.register(context, cb);
    }

    /** Unregisters any broadcast receivers. */
    public void unregisterReceivers() {
        mPackageMonitor.unregister();
    }

    /**
     * Handles signals from the system, trimming memory when requested to prevent us from running
     * out of memory.
     */
    void onTrimMemory(int level) {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.Memory, "[RecentsTaskLoader|onTrimMemory]",
                    Console.trimMemoryLevelToString(level));
        }

        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                // Stop the loader immediately when the UI is no longer visible
                stopLoader();
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                // We are leaving recents, so trim the data a bit
                mThumbnailCache.trimToSize(mMaxThumbnailCacheSize / 2);
                mApplicationIconCache.trimToSize(mMaxIconCacheSize / 2);
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                // We are going to be low on memory
                mThumbnailCache.trimToSize(mMaxThumbnailCacheSize / 4);
                mApplicationIconCache.trimToSize(mMaxIconCacheSize / 4);
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // We are low on memory, so release everything
                mThumbnailCache.evictAll();
                mApplicationIconCache.evictAll();
                break;
            default:
                break;
        }
    }
}
