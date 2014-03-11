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
    }

    @Override
    public void run() {
        while (true) {
            Console.log(Constants.DebugFlags.App.TaskDataLoader,
                    "[TaskResourceLoader|run|" + Thread.currentThread().getId() + "]");
            if (mCancelled) {
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
                        Drawable cachedIcon = mIconCache.get(t);
                        Bitmap cachedThumbnail = mThumbnailCache.get(t);
                        Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                "  [TaskResourceLoader|load]",
                                t + " icon: " + cachedIcon + " thumbnail: " + cachedThumbnail);
                        // Load the icon
                        if (cachedIcon == null) {
                            PackageManager pm = mContext.getPackageManager();
                            ActivityInfo info = pm.getActivityInfo(t.intent.getComponent(),
                                    PackageManager.GET_META_DATA);
                            Drawable icon = info.loadIcon(pm);
                            if (!mCancelled) {
                                Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                        "    [TaskResourceLoader|loadIcon]",
                                        icon);
                                t.icon = icon;
                                mIconCache.put(t, icon);
                            }
                        }
                        // Load the thumbnail
                        if (cachedThumbnail == null) {
                            ActivityManager am = (ActivityManager)
                                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
                            Bitmap thumbnail = am.getTaskTopThumbnail(t.id);
                            if (!mCancelled) {
                                if (thumbnail != null) {
                                    Console.log(Constants.DebugFlags.App.TaskDataLoader,
                                            "    [TaskResourceLoader|loadThumbnail]",
                                            thumbnail);
                                    t.thumbnail = thumbnail;
                                    mThumbnailCache.put(t, thumbnail);
                                } else {
                                    Console.logError(mContext,
                                            "Failed to load task top thumbnail for: " +
                                                    t.intent.getComponent().getPackageName());
                                }
                            }
                        }
                        if (!mCancelled) {
                            // Notify that the task data has changed
                            mMainThreadHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    t.notifyTaskDataChanged();
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
                            mLoadQueue.wait();
                        } catch (InterruptedException ie) {
                            ie.printStackTrace();
                        }
                    }
                }
            }
        }
    }
}

/** The drawable cache */
class DrawableLruCache extends LruCache<Task, Drawable> {
    public DrawableLruCache(int cacheSize) {
        super(cacheSize);
    }

    @Override
    protected int sizeOf(Task t, Drawable d) {
        // The cache size will be measured in kilobytes rather than number of items
        // NOTE: this isn't actually correct, as the icon may be smaller
        int maxBytes = (d.getIntrinsicWidth() * d.getIntrinsicHeight() * 4);
        return maxBytes / 1024;
    }
}

/** The bitmap cache */
class BitmapLruCache extends LruCache<Task, Bitmap> {
    public BitmapLruCache(int cacheSize) {
        super(cacheSize);
    }

    @Override
    protected int sizeOf(Task t, Bitmap bitmap) {
        // The cache size will be measured in kilobytes rather than number of items
        return bitmap.getByteCount() / 1024;
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

    BitmapDrawable mDefaultIcon;
    Bitmap mDefaultThumbnail;

    /** Private Constructor */
    private RecentsTaskLoader(Context context) {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int iconCacheSize = Constants.DebugFlags.App.ForceDisableBackgroundCache ? 1 : maxMemory / 16;
        int thumbnailCacheSize = Constants.DebugFlags.App.ForceDisableBackgroundCache ? 1 : maxMemory / 8;
        Console.log(Constants.DebugFlags.App.SystemUIHandshake,
                "[RecentsTaskLoader|init]", "thumbnailCache: " + thumbnailCacheSize +
                " iconCache: " + iconCacheSize);
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
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsTaskLoader|reload]");
        TaskStack stack = new TaskStack(context);
        SpaceNode root = new SpaceNode(context);
        root.setStack(stack);
        try {
            long t1 = System.currentTimeMillis();

            PackageManager pm = context.getPackageManager();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            // Get the recent tasks
            List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasksForUser(25,
                    ActivityManager.RECENT_IGNORE_UNAVAILABLE, UserHandle.CURRENT.getIdentifier());
            Collections.reverse(tasks);
            Console.log(Constants.DebugFlags.App.TimeSystemCalls,
                    "[RecentsTaskLoader|getRecentTasks]",
                    "" + (System.currentTimeMillis() - t1) + "ms");
            Console.log(Constants.DebugFlags.App.SystemUIHandshake,
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

                // Load the label, icon and thumbnail
                ActivityInfo info = pm.getActivityInfo(t.baseIntent.getComponent(),
                        PackageManager.GET_META_DATA);
                String title = info.loadLabel(pm).toString();
                Drawable icon = null;
                Bitmap thumbnail = null;
                Task task;
                if (i >= (taskCount - preloadCount) || !Constants.DebugFlags.App.EnableBackgroundTaskLoading) {
                    Console.log(Constants.DebugFlags.App.SystemUIHandshake,
                            "[RecentsTaskLoader|preloadTask]",
                            "i: " + i + " task: " + t.baseIntent.getComponent().getPackageName());
                    icon = info.loadIcon(pm);
                    thumbnail = am.getTaskTopThumbnail(t.id);
                    for (int j = 0; j < Constants.Values.RecentsTaskLoader.TaskEntryMultiplier; j++) {
                        Console.log(Constants.DebugFlags.App.SystemUIHandshake,
                                "  [RecentsTaskLoader|task]", t.baseIntent.getComponent().getPackageName());
                        task = new Task(t.persistentId, t.baseIntent, title, icon, thumbnail);
                        if (Constants.DebugFlags.App.EnableBackgroundTaskLoading) {
                            if (thumbnail != null) mThumbnailCache.put(task, thumbnail);
                            if (icon != null) {
                                mIconCache.put(task, icon);
                            }
                        }
                        stack.addTask(task);
                    }
                } else {
                    for (int j = 0; j < Constants.Values.RecentsTaskLoader.TaskEntryMultiplier; j++) {
                        Console.log(Constants.DebugFlags.App.SystemUIHandshake,
                                "  [RecentsTaskLoader|task]", t.baseIntent.getComponent().getPackageName());
                        task = new Task(t.persistentId, t.baseIntent, title, null, null);
                        stack.addTask(task);
                    }
                }

                /*
                if (stacks.containsKey(t.stackId)) {
                    builder = stacks.get(t.stackId);
                } else {
                    builder = new TaskStackBuilder();
                    stacks.put(t.stackId, builder);
                }
                */
            }
            Console.log(Constants.DebugFlags.App.TimeSystemCalls,
                    "[RecentsTaskLoader|getAllTaskTopThumbnail]",
                    "" + (System.currentTimeMillis() - t1) + "ms");

            /*
            // Get all the stacks
            t1 = System.currentTimeMillis();
            List<ActivityManager.StackInfo> stackInfos = ams.getAllStackInfos();
            Console.log(Constants.DebugFlags.App.TimeSystemCalls, "[RecentsTaskLoader|getAllStackInfos]", "" + (System.currentTimeMillis() - t1) + "ms");
            Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsTaskLoader|stacks]", "" + tasks.size());
            for (ActivityManager.StackInfo s : stackInfos) {
                Console.log(Constants.DebugFlags.App.SystemUIHandshake, "  [RecentsTaskLoader|stack]", s.toString());
                if (stacks.containsKey(s.stackId)) {
                    stacks.get(s.stackId).setRect(s.bounds);
                }
            }
            */
        } catch (Exception e) {
            e.printStackTrace();
        }
        mLoader.start(context);
        return root;
    }

    /** Acquires the task resource data from the pool.
     * XXX: Move this into Task? */
    public void loadTaskData(Task t) {
        if (Constants.DebugFlags.App.EnableBackgroundTaskLoading) {
            t.icon = mIconCache.get(t);
            t.thumbnail = mThumbnailCache.get(t);

            Console.log(Constants.DebugFlags.App.TaskDataLoader, "[RecentsTaskLoader|loadTask]",
                    t + " icon: " + t.icon + " thumbnail: " + t.thumbnail);

            boolean requiresLoad = false;
            if (t.icon == null) {
                t.icon = mDefaultIcon;
                requiresLoad = true;
            }
            if (t.thumbnail == null) {
                t.thumbnail = mDefaultThumbnail;
                requiresLoad = true;
            }
            if (requiresLoad) {
                mLoadQueue.addTask(t);
            }
        }
    }

    /** Releases the task resource data back into the pool.
     * XXX: Move this into Task? */
    public void unloadTaskData(Task t) {
        if (Constants.DebugFlags.App.EnableBackgroundTaskLoading) {
            Console.log(Constants.DebugFlags.App.TaskDataLoader,
                    "[RecentsTaskLoader|unloadTask]", t);
            mLoadQueue.removeTask(t);
            t.icon = mDefaultIcon;
            t.thumbnail = mDefaultThumbnail;
        }
    }

    /** Completely removes the resource data from the pool.
     * XXX: Move this into Task? */
    public void deleteTaskData(Task t) {
        if (Constants.DebugFlags.App.EnableBackgroundTaskLoading) {
            Console.log(Constants.DebugFlags.App.TaskDataLoader,
                    "[RecentsTaskLoader|deleteTask]", t);
            mLoadQueue.removeTask(t);
            mThumbnailCache.remove(t);
            mIconCache.remove(t);
        }
        t.icon = mDefaultIcon;
        t.thumbnail = mDefaultThumbnail;
    }

    /** Stops the task loader */
    void stopLoader() {
        Console.log(Constants.DebugFlags.App.TaskDataLoader, "[RecentsTaskLoader|stopLoader]");
        mLoader.stop();
        mLoadQueue.clearTasks();
    }
}
