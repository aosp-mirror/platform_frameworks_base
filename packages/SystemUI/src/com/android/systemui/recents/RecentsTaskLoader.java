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
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.util.LruCache;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;


/** A bitmap load queue */
class TaskResourceLoadQueue {
    ConcurrentLinkedQueue<Task> mQueue = new ConcurrentLinkedQueue<Task>();

    Task nextTask() {
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "  [TaskResourceLoadQueue|nextTask]");
        return mQueue.poll();
    }

    void addTask(Task t) {
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "  [TaskResourceLoadQueue|addTask]");
        if (!mQueue.contains(t)) {
            mQueue.add(t);
        }
        synchronized(this) {
            notifyAll();
        }
    }

    void removeTask(Task t) {
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "  [TaskResourceLoadQueue|removeTask]");
        mQueue.remove(t);
    }

    void clearTasks() {
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "  [TaskResourceLoadQueue|clearTasks]");
        mQueue.clear();
    }

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

    TaskResourceLoadQueue mLoadQueue;
    DrawableLruCache mIconCache;
    BitmapLruCache mThumbnailCache;

    boolean mCancelled;
    boolean mWaitingOnLoadQueue;

    /** Constructor, creates a new loading thread that loads task resources in the background */
    public TaskResourceLoader(TaskResourceLoadQueue loadQueue, DrawableLruCache iconCache,
                              BitmapLruCache thumbnailCache) {
        mLoadQueue = loadQueue;
        mIconCache = iconCache;
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
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "[TaskResourceLoader|start]");
        mContext = context;
        mCancelled = false;
        // Notify the load thread to start loading
        synchronized(mLoadThread) {
            mLoadThread.notifyAll();
        }
    }

    /** Requests the loader thread to stop after the current iteration */
    void stop() {
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "[TaskResourceLoader|stop]");
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
            Console.log(Constants.DebugFlags.App.TaskDataLoader,
                    "[TaskResourceLoader|run|" + Thread.currentThread().getId() + "]");
            if (mCancelled) {
                Console.log(Constants.DebugFlags.App.TaskDataLoader,
                        "[TaskResourceLoader|cancel|" + Thread.currentThread().getId() + "]");
                // We have to unset the context here, since the background thread may be using it
                // when we call stop()
                mContext = null;
                // If we are cancelled, then wait until we are started again
                synchronized(mLoadThread) {
                    try {
                        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                "[TaskResourceLoader|waitOnLoadThreadCancelled]");
                        mLoadThread.wait();
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            } else {
                // Load the next item from the queue
                final Task t = mLoadQueue.nextTask();
                if (t != null) {
                    try {
                        Drawable loadIcon = mIconCache.get(t.key);
                        Bitmap loadThumbnail = mThumbnailCache.get(t.key);
                        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                "  [TaskResourceLoader|load]",
                                t + " icon: " + loadIcon + " thumbnail: " + loadThumbnail);
                        // Load the icon
                        if (loadIcon == null) {
                            PackageManager pm = mContext.getPackageManager();
                            ActivityInfo info = pm.getActivityInfo(t.key.intent.getComponent(),
                                    PackageManager.GET_META_DATA);
                            Drawable icon = info.loadIcon(pm);
                            if (!mCancelled) {
                                if (icon != null) {
                                    Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                            "    [TaskResourceLoader|loadIcon]",
                                            icon);
                                    loadIcon = icon;
                                    mIconCache.put(t.key, icon);
                                }
                            }
                        }
                        // Load the thumbnail
                        if (loadThumbnail == null) {
                            ActivityManager am = (ActivityManager)
                                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
                            Bitmap thumbnail = am.getTaskTopThumbnail(t.key.id);
                            if (!mCancelled) {
                                if (thumbnail != null) {
                                    Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                            "    [TaskResourceLoader|loadThumbnail]",
                                            thumbnail);
                                    loadThumbnail = thumbnail;
                                    mThumbnailCache.put(t.key, thumbnail);
                                } else {
                                    Console.logError(mContext,
                                            "Failed to load task top thumbnail for: " +
                                                    t.key.intent.getComponent().getPackageName());
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
                                    t.notifyTaskDataLoaded(newThumbnail, newIcon);
                                }
                            });
                        }
                    } catch (PackageManager.NameNotFoundException ne) {
                        ne.printStackTrace();
                    }
                }

                // If there are no other items in the list, then just wait until something is added
                if (!mCancelled && mLoadQueue.isEmpty()) {
                    synchronized(mLoadQueue) {
                        try {
                            Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                    "[TaskResourceLoader|waitOnLoadQueue]");
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

    DrawableLruCache mIconCache;
    BitmapLruCache mThumbnailCache;
    TaskResourceLoadQueue mLoadQueue;
    TaskResourceLoader mLoader;

    int mMaxThumbnailCacheSize;
    int mMaxIconCacheSize;

    BitmapDrawable mDefaultIcon;
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

        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                "[RecentsTaskLoader|init]", "thumbnailCache: " + thumbnailCacheSize +
                " iconCache: " + iconCacheSize);

        // Initialize the cache and loaders
        mLoadQueue = new TaskResourceLoadQueue();
        mIconCache = new DrawableLruCache(iconCacheSize);
        mThumbnailCache = new BitmapLruCache(thumbnailCacheSize);
        mLoader = new TaskResourceLoader(mLoadQueue, mIconCache, mThumbnailCache);

        // Create the default assets
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        mDefaultThumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas();
        c.setBitmap(icon);
        c.drawColor(0x00000000);
        c.setBitmap(mDefaultThumbnail);
        c.drawColor(0x00000000);
        c.setBitmap(null);
        mDefaultIcon = new BitmapDrawable(context.getResources(), icon);
        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                "[RecentsTaskLoader|defaultBitmaps]",
                "icon: " + mDefaultIcon + " thumbnail: " + mDefaultThumbnail, Console.AnsiRed);
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

    /** Reload the set of recent tasks */
    SpaceNode reload(Context context, int preloadCount) {
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "[RecentsTaskLoader|reload]");
        TaskStack stack = new TaskStack(context);
        SpaceNode root = new SpaceNode(context);
        root.setStack(stack);
        try {
            long t1 = System.currentTimeMillis();

            PackageManager pm = context.getPackageManager();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            // Get the recent tasks
            List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasksForUser(25,
                    ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                    ActivityManager.RECENT_INCLUDE_RELATED, UserHandle.CURRENT.getIdentifier());
            Collections.reverse(tasks);
            Console.log(Constants.DebugFlags.App.TimeSystemCalls,
                    "[RecentsTaskLoader|getRecentTasks]",
                    "" + (System.currentTimeMillis() - t1) + "ms");
            Console.log(Constants.DebugFlags.App.TaskDataLoader,
                    "[RecentsTaskLoader|tasks]", "" + tasks.size());

            // Remove home/recents tasks
            Iterator<ActivityManager.RecentTaskInfo> iter = tasks.iterator();
            while (iter.hasNext()) {
                ActivityManager.RecentTaskInfo t = iter.next();

                // Skip tasks in the home stack
                if (am.isInHomeStack(t.persistentId)) {
                    iter.remove();
                    continue;
                }
                // Skip tasks from this Recents package
                if (t.baseIntent.getComponent().getPackageName().equals(context.getPackageName())) {
                    iter.remove();
                    continue;
                }
            }

            // Add each task to the task stack
            t1 = System.currentTimeMillis();
            int taskCount = tasks.size();
            for (int i = 0; i < taskCount; i++) {
                ActivityManager.RecentTaskInfo t = tasks.get(i);
                ActivityInfo info = pm.getActivityInfo(t.baseIntent.getComponent(),
                        PackageManager.GET_META_DATA);
                String title = info.loadLabel(pm).toString();
                boolean isForemostTask = (i == (taskCount - 1));

                // Preload the specified number of apps
                if (i >= (taskCount - preloadCount)) {
                    Console.log(Constants.DebugFlags.App.TaskDataLoader,
                            "[RecentsTaskLoader|preloadTask]",
                            "i: " + i + " task: " + t.baseIntent.getComponent().getPackageName());

                    Task task = new Task(t.persistentId, t.baseIntent, title);

                    // Load the icon (if possible and not the foremost task, from the cache)
                    if (!isForemostTask) {
                        task.icon = mIconCache.get(task.key);
                    }
                    if (task.icon == null) {
                        task.icon = info.loadIcon(pm);
                        if (task.icon != null) {
                            mIconCache.put(task.key, task.icon);
                        } else {
                            task.icon = mDefaultIcon;
                        }
                    }

                    // Load the thumbnail (if possible and not the foremost task, from the cache)
                    if (!isForemostTask) {
                        task.thumbnail = mThumbnailCache.get(task.key);
                    }
                    if (task.thumbnail == null) {
                        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                "[RecentsTaskLoader|loadingTaskThumbnail]");
                        task.thumbnail = am.getTaskTopThumbnail(t.id);
                        if (task.thumbnail != null) {
                            mThumbnailCache.put(task.key, task.thumbnail);
                        } else {
                            task.thumbnail = mDefaultThumbnail;
                        }
                    }

                    // Create as many tasks a we want to multiply by
                    for (int j = 0; j < Constants.Values.RecentsTaskLoader.TaskEntryMultiplier; j++) {
                        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                "  [RecentsTaskLoader|task]", t.baseIntent.getComponent().getPackageName());
                        stack.addTask(task);
                    }
                } else {
                    // Create as many tasks a we want to multiply by
                    for (int j = 0; j < Constants.Values.RecentsTaskLoader.TaskEntryMultiplier; j++) {
                        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                "  [RecentsTaskLoader|task]", t.baseIntent.getComponent().getPackageName());
                        stack.addTask(new Task(t.persistentId, t.baseIntent, title));
                    }
                }
            }
            Console.log(Constants.DebugFlags.App.TimeSystemCalls,
                    "[RecentsTaskLoader|getAllTaskTopThumbnail]",
                    "" + (System.currentTimeMillis() - t1) + "ms");

            /*
            // Get all the stacks
            t1 = System.currentTimeMillis();
            List<ActivityManager.StackInfo> stackInfos = ams.getAllStackInfos();
            Console.log(Constants.DebugFlags.App.TimeSystemCalls, "[RecentsTaskLoader|getAllStackInfos]", "" + (System.currentTimeMillis() - t1) + "ms");
            Console.log(Constants.DebugFlags.App.TaskDataLoader, "[RecentsTaskLoader|stacks]", "" + tasks.size());
            for (ActivityManager.StackInfo s : stackInfos) {
                Console.log(Constants.DebugFlags.App.TaskDataLoader, "  [RecentsTaskLoader|stack]", s.toString());
                if (stacks.containsKey(s.stackId)) {
                    stacks.get(s.stackId).setRect(s.bounds);
                }
            }
            */
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Start the task loader
        mLoader.start(context);

        return root;
    }

    /** Acquires the task resource data from the pool. */
    public void loadTaskData(Task t) {
        Drawable icon = mIconCache.get(t.key);
        Bitmap thumbnail = mThumbnailCache.get(t.key);

        Console.log(Constants.DebugFlags.App.TaskDataLoader, "[RecentsTaskLoader|loadTask]",
                t + " icon: " + icon + " thumbnail: " + thumbnail +
                        " thumbnailCacheSize: " + mThumbnailCache.size());

        boolean requiresLoad = false;
        if (icon == null) {
            icon = mDefaultIcon;
            requiresLoad = true;
        }
        if (thumbnail == null) {
            thumbnail = mDefaultThumbnail;
            requiresLoad = true;
        }
        if (requiresLoad) {
            mLoadQueue.addTask(t);
        }
        t.notifyTaskDataLoaded(thumbnail, icon);
    }

    /** Releases the task resource data back into the pool. */
    public void unloadTaskData(Task t) {
        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                "[RecentsTaskLoader|unloadTask]", t +
                " thumbnailCacheSize: " + mThumbnailCache.size());

        mLoadQueue.removeTask(t);
        t.notifyTaskDataUnloaded(mDefaultThumbnail, mDefaultIcon);
    }

    /** Completely removes the resource data from the pool. */
    public void deleteTaskData(Task t) {
        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                "[RecentsTaskLoader|deleteTask]", t);

        mLoadQueue.removeTask(t);
        mThumbnailCache.remove(t.key);
        mIconCache.remove(t.key);
        t.notifyTaskDataUnloaded(mDefaultThumbnail, mDefaultIcon);
    }

    /** Stops the task loader and clears all pending tasks */
    void stopLoader() {
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "[RecentsTaskLoader|stopLoader]");
        mLoader.stop();
        mLoadQueue.clearTasks();
    }

    void onTrimMemory(int level) {
        Console.log(Constants.DebugFlags.App.Memory, "[RecentsTaskLoader|onTrimMemory]",
                Console.trimMemoryLevelToString(level));

        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                // Stop the loader immediately when the UI is no longer visible
                stopLoader();
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                // We are leaving recents, so trim the data a bit
                mThumbnailCache.trimToSize(mMaxThumbnailCacheSize / 2);
                mIconCache.trimToSize(mMaxIconCacheSize / 2);
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                // We are going to be low on memory
                mThumbnailCache.trimToSize(mMaxThumbnailCacheSize / 4);
                mIconCache.trimToSize(mMaxIconCacheSize / 4);
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // We are low on memory, so release everything
                mThumbnailCache.evictAll();
                mIconCache.evictAll();
                break;
            default:
                break;
        }
    }
}
