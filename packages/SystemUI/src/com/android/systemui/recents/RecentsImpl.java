/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.app.ActivityOptions;
import android.app.ITaskStackListener;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.MutableBoolean;
import android.view.LayoutInflater;
import android.view.View;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationStartedEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.IterateRecentsEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.ForegroundThread;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskGrouping;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.recents.views.TaskStackViewLayoutAlgorithm;
import com.android.systemui.recents.views.TaskViewHeader;
import com.android.systemui.recents.views.TaskViewTransform;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.util.ArrayList;

/**
 * An implementation of the Recents component for the current user.  For secondary users, this can
 * be called remotely from the system user.
 */
public class RecentsImpl extends IRecentsNonSystemUserCallbacks.Stub
        implements ActivityOptions.OnAnimationStartedListener {

    private final static String TAG = "RecentsImpl";
    private final static boolean DEBUG = false;

    private final static int sMinToggleDelay = 350;

    public final static String RECENTS_PACKAGE = "com.android.systemui";
    public final static String RECENTS_ACTIVITY = "com.android.systemui.recents.RecentsActivity";


    /**
     * An implementation of ITaskStackListener, that allows us to listen for changes to the system
     * task stacks and update recents accordingly.
     */
    class TaskStackListenerImpl extends ITaskStackListener.Stub implements Runnable {
        Handler mHandler;

        public TaskStackListenerImpl(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onTaskStackChanged() {
            // Debounce any task stack changes
            mHandler.removeCallbacks(this);
            mHandler.post(this);
        }

        /** Preloads the next task */
        public void run() {
            // TODO: Temporarily skip this if multi stack is enabled
            /*
            RecentsConfiguration config = RecentsConfiguration.getInstance();
            if (config.svelteLevel == RecentsConfiguration.SVELTE_NONE) {
                RecentsTaskLoader loader = Recents.getTaskLoader();
                SystemServicesProxy ssp = Recents.getSystemServices();
                ActivityManager.RunningTaskInfo runningTaskInfo = ssp.getTopMostTask();

                // Load the next task only if we aren't svelte
                RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
                loader.preloadTasks(plan, true);
                RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
                // This callback is made when a new activity is launched and the old one is paused
                // so ignore the current activity and try and preload the thumbnail for the
                // previous one.
                if (runningTaskInfo != null) {
                    launchOpts.runningTaskId = runningTaskInfo.id;
                }
                launchOpts.numVisibleTasks = 2;
                launchOpts.numVisibleTaskThumbnails = 2;
                launchOpts.onlyLoadForCache = true;
                launchOpts.onlyLoadPausedActivities = true;
                loader.loadTasks(mContext, plan, launchOpts);
            }
            */
        }
    }

    private static RecentsTaskLoadPlan sInstanceLoadPlan;

    Context mContext;
    Handler mHandler;
    TaskStackListenerImpl mTaskStackListener;
    RecentsAppWidgetHost mAppWidgetHost;
    boolean mBootCompleted;
    boolean mStartAnimationTriggered;
    boolean mCanReuseTaskStackViews = true;

    // Task launching
    RecentsConfiguration mConfig;
    Rect mSearchBarBounds = new Rect();
    Rect mTaskStackBounds = new Rect();
    Rect mLastTaskViewBounds = new Rect();
    TaskViewTransform mTmpTransform = new TaskViewTransform();
    int mStatusBarHeight;
    int mNavBarHeight;
    int mNavBarWidth;
    int mTaskBarHeight;

    // Header (for transition)
    TaskViewHeader mHeaderBar;
    final Object mHeaderBarLock = new Object();
    TaskStackView mDummyStackView;

    // Variables to keep track of if we need to start recents after binding
    boolean mTriggeredFromAltTab;
    long mLastToggleTime;

    Bitmap mThumbnailTransitionBitmapCache;
    Task mThumbnailTransitionBitmapCacheKey;


    public RecentsImpl(Context context) {
        mContext = context;
        mHandler = new Handler();
        mAppWidgetHost = new RecentsAppWidgetHost(mContext, Constants.Values.App.AppWidgetHostId);
        Resources res = mContext.getResources();
        LayoutInflater inflater = LayoutInflater.from(mContext);

        // Initialize the static foreground thread
        ForegroundThread.get();

        // Register the task stack listener
        mTaskStackListener = new TaskStackListenerImpl(mHandler);
        SystemServicesProxy ssp = Recents.getSystemServices();
        ssp.registerTaskStackListener(mTaskStackListener);

        // Initialize the static configuration resources
        mConfig = RecentsConfiguration.initialize(mContext, ssp);
        mStatusBarHeight = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mNavBarHeight = res.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_height);
        mNavBarWidth = res.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_width);
        mTaskBarHeight = res.getDimensionPixelSize(R.dimen.recents_task_bar_height);
        mDummyStackView = new TaskStackView(mContext, new TaskStack());
        mHeaderBar = (TaskViewHeader) inflater.inflate(R.layout.recents_task_view_header,
                null, false);
        reloadHeaderBarLayout(true /* tryAndBindSearchWidget */);

        // When we start, preload the data associated with the previous recent tasks.
        // We can use a new plan since the caches will be the same.
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, true /* isTopTaskHome */);
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.numVisibleTasks = loader.getApplicationIconCacheSize();
        launchOpts.numVisibleTaskThumbnails = loader.getThumbnailCacheSize();
        launchOpts.onlyLoadForCache = true;
        loader.loadTasks(mContext, plan, launchOpts);
    }

    public void onBootCompleted() {
        mBootCompleted = true;
        reloadHeaderBarLayout(true /* tryAndBindSearchWidget */);
    }

    @Override
    public void onConfigurationChanged() {
        // Don't reuse task stack views if the configuration changes
        mCanReuseTaskStackViews = false;
        mConfig.updateOnConfigurationChange();
    }

    /**
     * This is only called from the system user's Recents.  Secondary users will instead proxy their
     * visibility change events through to the system user via
     * {@link Recents#onBusEvent(RecentsVisibilityChangedEvent)}.
     */
    public void onVisibilityChanged(Context context, boolean visible) {
        SystemUIApplication app = (SystemUIApplication) context;
        PhoneStatusBar statusBar = app.getComponent(PhoneStatusBar.class);
        if (statusBar != null) {
            statusBar.updateRecentsVisibility(visible);
        }
    }

    /**
     * This is only called from the system user's Recents.  Secondary users will instead proxy their
     * visibility change events through to the system user via
     * {@link Recents#onBusEvent(ScreenPinningRequestEvent)}.
     */
    public void onStartScreenPinning(Context context) {
        SystemUIApplication app = (SystemUIApplication) context;
        PhoneStatusBar statusBar = app.getComponent(PhoneStatusBar.class);
        if (statusBar != null) {
            statusBar.showScreenPinningRequest(false);
        }
    }

    @Override
    public void showRecents(boolean triggeredFromAltTab) {
        mTriggeredFromAltTab = triggeredFromAltTab;

        try {
            // Check if the top task is in the home stack, and start the recents activity
            SystemServicesProxy ssp = Recents.getSystemServices();
            ActivityManager.RunningTaskInfo topTask = ssp.getTopMostTask();
            MutableBoolean isTopTaskHome = new MutableBoolean(true);
            if (topTask == null || !ssp.isRecentsTopMost(topTask, isTopTaskHome)) {
                startRecentsActivity(topTask, isTopTaskHome.value);
            }
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    @Override
    public void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (mBootCompleted) {
            // Defer to the activity to handle hiding recents, if it handles it, then it must still
            // be visible
            EventBus.getDefault().post(new HideRecentsEvent(triggeredFromAltTab,
                    triggeredFromHomeKey));
        }
    }

    @Override
    public void toggleRecents() {
        mTriggeredFromAltTab = false;

        try {
            SystemServicesProxy ssp = Recents.getSystemServices();
            ActivityManager.RunningTaskInfo topTask = ssp.getTopMostTask();
            MutableBoolean isTopTaskHome = new MutableBoolean(true);
            if (topTask != null && ssp.isRecentsTopMost(topTask, isTopTaskHome)) {
                if (Constants.DebugFlags.App.EnableFastToggleRecents) {
                    // Notify recents to move onto the next task
                    EventBus.getDefault().post(new IterateRecentsEvent());
                } else {
                    // If the user has toggled it too quickly, then just eat up the event here (it's
                    // better than showing a janky screenshot).
                    // NOTE: Ideally, the screenshot mechanism would take the window transform into
                    // account
                    if ((SystemClock.elapsedRealtime() - mLastToggleTime) < sMinToggleDelay) {
                        return;
                    }

                    EventBus.getDefault().post(new ToggleRecentsEvent());
                    mLastToggleTime = SystemClock.elapsedRealtime();
                }
                return;
            } else {
                // If the user has toggled it too quickly, then just eat up the event here (it's
                // better than showing a janky screenshot).
                // NOTE: Ideally, the screenshot mechanism would take the window transform into
                // account
                if ((SystemClock.elapsedRealtime() - mLastToggleTime) < sMinToggleDelay) {
                    return;
                }

                // Otherwise, start the recents activity
                startRecentsActivity(topTask, isTopTaskHome.value);
                mLastToggleTime = SystemClock.elapsedRealtime();
            }
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    @Override
    public void preloadRecents() {
        // Preload only the raw task list into a new load plan (which will be consumed by the
        // RecentsActivity) only if there is a task to animate to.
        SystemServicesProxy ssp = Recents.getSystemServices();
        ActivityManager.RunningTaskInfo topTask = ssp.getTopMostTask();
        MutableBoolean topTaskHome = new MutableBoolean(true);
        RecentsTaskLoader loader = Recents.getTaskLoader();
        sInstanceLoadPlan = loader.createLoadPlan(mContext);
        if (topTask != null && !ssp.isRecentsTopMost(topTask, topTaskHome)) {
            sInstanceLoadPlan.preloadRawTasks(topTaskHome.value);
            loader.preloadTasks(sInstanceLoadPlan, topTaskHome.value);
            TaskStack stack = sInstanceLoadPlan.getTaskStack();
            if (stack.getTaskCount() > 0) {
                // We try and draw the thumbnail transition bitmap in parallel before
                // toggle/show recents is called
                preCacheThumbnailTransitionBitmapAsync(topTask, stack, mDummyStackView);
            }
        }
    }

    @Override
    public void cancelPreloadingRecents() {
        // Do nothing
    }

    public void showRelativeAffiliatedTask(boolean showNextTask) {
        // Return early if there is no focused stack
        SystemServicesProxy ssp = Recents.getSystemServices();
        int focusedStackId = ssp.getFocusedStack();
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, true /* isTopTaskHome */);
        TaskStack focusedStack = plan.getTaskStack();

        // Return early if there are no tasks in the focused stack
        if (focusedStack == null || focusedStack.getTaskCount() == 0) return;

        ActivityManager.RunningTaskInfo runningTask = ssp.getTopMostTask();
        // Return early if there is no running task (can't determine affiliated tasks in this case)
        if (runningTask == null) return;
        // Return early if the running task is in the home stack (optimization)
        if (SystemServicesProxy.isHomeStack(runningTask.stackId)) return;

        // Find the task in the recents list
        ArrayList<Task> tasks = focusedStack.getTasks();
        Task toTask = null;
        ActivityOptions launchOpts = null;
        int taskCount = tasks.size();
        int numAffiliatedTasks = 0;
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks.get(i);
            if (task.key.id == runningTask.id) {
                TaskGrouping group = task.group;
                Task.TaskKey toTaskKey;
                if (showNextTask) {
                    toTaskKey = group.getNextTaskInGroup(task);
                    launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                            R.anim.recents_launch_next_affiliated_task_target,
                            R.anim.recents_launch_next_affiliated_task_source);
                } else {
                    toTaskKey = group.getPrevTaskInGroup(task);
                    launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                            R.anim.recents_launch_prev_affiliated_task_target,
                            R.anim.recents_launch_prev_affiliated_task_source);
                }
                if (toTaskKey != null) {
                    toTask = focusedStack.findTaskWithId(toTaskKey.id);
                }
                numAffiliatedTasks = group.getTaskCount();
                break;
            }
        }

        // Return early if there is no next task
        if (toTask == null) {
            if (numAffiliatedTasks > 1) {
                if (showNextTask) {
                    ssp.startInPlaceAnimationOnFrontMostApplication(
                            ActivityOptions.makeCustomInPlaceAnimation(mContext,
                                    R.anim.recents_launch_next_affiliated_task_bounce));
                } else {
                    ssp.startInPlaceAnimationOnFrontMostApplication(
                            ActivityOptions.makeCustomInPlaceAnimation(mContext,
                                    R.anim.recents_launch_prev_affiliated_task_bounce));
                }
            }
            return;
        }

        // Keep track of actually launched affiliated tasks
        MetricsLogger.count(mContext, "overview_affiliated_task_launch", 1);

        // Launch the task
        if (toTask.isActive) {
            // Bring an active task to the foreground
            ssp.moveTaskToFront(toTask.key.id, launchOpts);
        } else {
            ssp.startActivityFromRecents(mContext, toTask.key.id, toTask.activityLabel, launchOpts);
        }
    }

    public void showNextAffiliatedTask() {
        // Keep track of when the affiliated task is triggered
        MetricsLogger.count(mContext, "overview_affiliated_task_next", 1);
        showRelativeAffiliatedTask(true);
    }

    public void showPrevAffiliatedTask() {
        // Keep track of when the affiliated task is triggered
        MetricsLogger.count(mContext, "overview_affiliated_task_prev", 1);
        showRelativeAffiliatedTask(false);
    }

    /**
     * Returns the preloaded load plan and invalidates it.
     */
    public static RecentsTaskLoadPlan consumeInstanceLoadPlan() {
        RecentsTaskLoadPlan plan = sInstanceLoadPlan;
        sInstanceLoadPlan = null;
        return plan;
    }

    /**
     * Prepares the header bar layout for the next transition, if the task view bounds has changed
     * since the last call, it will attempt to re-measure and layout the header bar to the new size.
     *
     * @param tryAndBindSearchWidget if set, will attempt to fetch and bind the search widget if one
     *                               is not already bound (can be expensive)
     */
    private void reloadHeaderBarLayout(boolean tryAndBindSearchWidget) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        Rect windowRect = ssp.getWindowRect();

        // Update the configuration for the current state
        mConfig.update(mContext, ssp, ssp.getWindowRect());

        if (!Constants.DebugFlags.App.DisableSearchBar && tryAndBindSearchWidget) {
            // Try and pre-emptively bind the search widget on startup to ensure that we
            // have the right thumbnail bounds to animate to.
            // Note: We have to reload the widget id before we get the task stack bounds below
            if (ssp.getOrBindSearchAppWidget(mContext, mAppWidgetHost) != null) {
                mConfig.getSearchBarBounds(windowRect, mStatusBarHeight, mSearchBarBounds);
            }
        }
        Rect systemInsets = new Rect(0, mStatusBarHeight,
                (mConfig.hasTransposedNavBar ? mNavBarWidth : 0),
                (mConfig.hasTransposedNavBar ? 0 : mNavBarHeight));
        mConfig.getTaskStackBounds(windowRect, systemInsets.top, systemInsets.right,
                mSearchBarBounds, mTaskStackBounds);

        // Rebind the header bar and draw it for the transition
        TaskStackViewLayoutAlgorithm algo = mDummyStackView.getStackAlgorithm();
        Rect taskStackBounds = new Rect(mTaskStackBounds);
        algo.setSystemInsets(systemInsets);
        algo.computeRects(windowRect.width(), windowRect.height(), taskStackBounds);
        Rect taskViewBounds = algo.getUntransformedTaskViewBounds();
        if (!taskViewBounds.equals(mLastTaskViewBounds)) {
            mLastTaskViewBounds.set(taskViewBounds);

            int taskViewWidth = taskViewBounds.width();
            synchronized (mHeaderBarLock) {
                mHeaderBar.measure(
                    View.MeasureSpec.makeMeasureSpec(taskViewWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(mTaskBarHeight, View.MeasureSpec.EXACTLY));
                mHeaderBar.layout(0, 0, taskViewWidth, mTaskBarHeight);
            }
        }
    }

    /**
     * Preloads the icon of a task.
     */
    private void preloadIcon(ActivityManager.RunningTaskInfo task) {
        // Ensure that we load the running task's icon
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.runningTaskId = task.id;
        launchOpts.loadThumbnails = false;
        launchOpts.onlyLoadForCache = true;
        Recents.getTaskLoader().loadTasks(mContext, sInstanceLoadPlan, launchOpts);
    }

    /**
     * Caches the header thumbnail used for a window animation asynchronously into
     * {@link #mThumbnailTransitionBitmapCache}.
     */
    private void preCacheThumbnailTransitionBitmapAsync(ActivityManager.RunningTaskInfo topTask,
            TaskStack stack, TaskStackView stackView) {
        preloadIcon(topTask);

        // Update the header bar if necessary
        reloadHeaderBarLayout(false /* tryAndBindSearchWidget */);

        // Update the destination rect
        mDummyStackView.updateMinMaxScrollForStack(stack);
        final Task toTask = new Task();
        final TaskViewTransform toTransform = getThumbnailTransitionTransform(stack, stackView,
                topTask.id, toTask);
        ForegroundThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                final Bitmap transitionBitmap = drawThumbnailTransitionBitmap(toTask, toTransform);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mThumbnailTransitionBitmapCache = transitionBitmap;
                        mThumbnailTransitionBitmapCacheKey = toTask;
                    }
                });
            }
        });
    }

    /**
     * Creates the activity options for a unknown state->recents transition.
     */
    private ActivityOptions getUnknownTransitionActivityOptions() {
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_unknown_enter,
                R.anim.recents_from_unknown_exit,
                mHandler, this);
    }

    /**
     * Creates the activity options for a home->recents transition.
     */
    private ActivityOptions getHomeTransitionActivityOptions(boolean fromSearchHome) {
        if (fromSearchHome) {
            return ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.recents_from_search_launcher_enter,
                    R.anim.recents_from_search_launcher_exit,
                    mHandler, this);
        }
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_launcher_enter,
                R.anim.recents_from_launcher_exit,
                mHandler, this);
    }

    /**
     * Creates the activity options for an app->recents transition.
     */
    private ActivityOptions getThumbnailTransitionActivityOptions(
            ActivityManager.RunningTaskInfo topTask, TaskStack stack, TaskStackView stackView) {

        // Update the destination rect
        Task toTask = new Task();
        TaskViewTransform toTransform = getThumbnailTransitionTransform(stack, stackView,
                topTask.id, toTask);
        RectF toTaskRect = toTransform.rect;
        Bitmap thumbnail;
        if (mThumbnailTransitionBitmapCacheKey != null
                && mThumbnailTransitionBitmapCacheKey.key != null
                && mThumbnailTransitionBitmapCacheKey.key.equals(toTask.key)) {
            thumbnail = mThumbnailTransitionBitmapCache;
            mThumbnailTransitionBitmapCacheKey = null;
            mThumbnailTransitionBitmapCache = null;
        } else {
            preloadIcon(topTask);
            thumbnail = drawThumbnailTransitionBitmap(toTask, toTransform);
        }
        if (thumbnail != null) {
            return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mDummyStackView,
                    thumbnail, (int) toTaskRect.left, (int) toTaskRect.top,
                    (int) toTaskRect.width(), (int) toTaskRect.height(), mHandler, this);
        }

        // If both the screenshot and thumbnail fails, then just fall back to the default transition
        return getUnknownTransitionActivityOptions();
    }

    /**
     * Returns the transition rect for the given task id.
     */
    private TaskViewTransform getThumbnailTransitionTransform(TaskStack stack,
            TaskStackView stackView, int runningTaskId, Task runningTaskOut) {
        // Find the running task in the TaskStack
        Task task = null;
        ArrayList<Task> tasks = stack.getTasks();
        if (runningTaskId != -1) {
            // Otherwise, try and find the task with the
            int taskCount = tasks.size();
            for (int i = taskCount - 1; i >= 0; i--) {
                Task t = tasks.get(i);
                if (t.key.id == runningTaskId) {
                    task = t;
                    runningTaskOut.copyFrom(t);
                    break;
                }
            }
        }
        if (task == null) {
            // If no task is specified or we can not find the task just use the front most one
            task = tasks.get(tasks.size() - 1);
            runningTaskOut.copyFrom(task);
        }

        // Get the transform for the running task
        stackView.getScroller().setStackScrollToInitialState();
        mTmpTransform = stackView.getStackAlgorithm().getStackTransform(task,
                stackView.getScroller().getStackScroll(), mTmpTransform, null);
        return mTmpTransform;
    }

    /**
     * Draws the header of a task used for the window animation into a bitmap.
     */
    private Bitmap drawThumbnailTransitionBitmap(Task toTask, TaskViewTransform toTransform) {
        if (toTransform != null && toTask.key != null) {
            Bitmap thumbnail;
            synchronized (mHeaderBarLock) {
                int toHeaderWidth = (int) (mHeaderBar.getMeasuredWidth() * toTransform.scale);
                int toHeaderHeight = (int) (mHeaderBar.getMeasuredHeight() * toTransform.scale);
                thumbnail = Bitmap.createBitmap(toHeaderWidth, toHeaderHeight,
                        Bitmap.Config.ARGB_8888);
                if (Constants.DebugFlags.App.EnableTransitionThumbnailDebugMode) {
                    thumbnail.eraseColor(0xFFff0000);
                } else {
                    Canvas c = new Canvas(thumbnail);
                    c.scale(toTransform.scale, toTransform.scale);
                    mHeaderBar.rebindToTask(toTask);
                    mHeaderBar.draw(c);
                    c.setBitmap(null);
                }
            }
            return thumbnail.createAshmemBitmap();
        }
        return null;
    }

    /**
     * Shows the recents activity
     */
    private void startRecentsActivity(ActivityManager.RunningTaskInfo topTask,
            boolean isTopTaskHome) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        RecentsTaskLoader loader = Recents.getTaskLoader();

        // Update the header bar if necessary
        reloadHeaderBarLayout(false /* tryAndBindSearchWidget */);

        if (sInstanceLoadPlan == null) {
            // Create a new load plan if onPreloadRecents() was never triggered
            sInstanceLoadPlan = loader.createLoadPlan(mContext);
        }

        if (!sInstanceLoadPlan.hasTasks()) {
            loader.preloadTasks(sInstanceLoadPlan, isTopTaskHome);
        }
        TaskStack stack = sInstanceLoadPlan.getTaskStack();

        // Prepare the dummy stack for the transition
        mDummyStackView.updateMinMaxScrollForStack(stack);
        TaskStackViewLayoutAlgorithm.VisibilityReport stackVr =
                mDummyStackView.computeStackVisibilityReport();
        boolean hasRecentTasks = stack.getTaskCount() > 0;
        boolean useThumbnailTransition = (topTask != null) && !isTopTaskHome && hasRecentTasks;

        if (useThumbnailTransition) {
            // Try starting with a thumbnail transition
            ActivityOptions opts = getThumbnailTransitionActivityOptions(topTask, stack,
                    mDummyStackView);
            if (opts != null) {
                startRecentsActivity(topTask, opts, false /* fromHome */,
                        false /* fromSearchHome */, true /* fromThumbnail */, stackVr);
            } else {
                // Fall through below to the non-thumbnail transition
                useThumbnailTransition = false;
            }
        }

        if (!useThumbnailTransition) {
            // If there is no thumbnail transition, but is launching from home into recents, then
            // use a quick home transition and do the animation from home
            if (!Constants.DebugFlags.App.DisableSearchBar && hasRecentTasks) {
                String homeActivityPackage = ssp.getHomeActivityPackageName();
                String searchWidgetPackage = Prefs.getString(mContext,
                        Prefs.Key.SEARCH_APP_WIDGET_PACKAGE, null);

                // Determine whether we are coming from a search owned home activity
                boolean fromSearchHome = (homeActivityPackage != null) &&
                        homeActivityPackage.equals(searchWidgetPackage);
                ActivityOptions opts = getHomeTransitionActivityOptions(fromSearchHome);
                startRecentsActivity(topTask, opts, true /* fromHome */, fromSearchHome,
                        false /* fromThumbnail */, stackVr);
            } else {
                // Otherwise we do the normal fade from an unknown source
                ActivityOptions opts = getUnknownTransitionActivityOptions();
                startRecentsActivity(topTask, opts, true /* fromHome */,
                        false /* fromSearchHome */, false /* fromThumbnail */, stackVr);
            }
        }
        mLastToggleTime = SystemClock.elapsedRealtime();
    }

    /**
     * Starts the recents activity.
     */
    private void startRecentsActivity(ActivityManager.RunningTaskInfo topTask,
              ActivityOptions opts, boolean fromHome, boolean fromSearchHome, boolean fromThumbnail,
              TaskStackViewLayoutAlgorithm.VisibilityReport vr) {
        mStartAnimationTriggered = false;

        // Update the configuration based on the launch options
        RecentsActivityLaunchState launchState = mConfig.getLaunchState();
        launchState.launchedFromHome = fromSearchHome || fromHome;
        launchState.launchedFromSearchHome = fromSearchHome;
        launchState.launchedFromAppWithThumbnail = fromThumbnail;
        launchState.launchedToTaskId = (topTask != null) ? topTask.id : -1;
        launchState.launchedWithAltTab = mTriggeredFromAltTab;
        launchState.launchedReuseTaskStackViews = mCanReuseTaskStackViews;
        launchState.launchedNumVisibleTasks = vr.numVisibleTasks;
        launchState.launchedNumVisibleThumbnails = vr.numVisibleThumbnails;
        launchState.launchedHasConfigurationChanged = false;

        Intent intent = new Intent();
        intent.setClassName(RECENTS_PACKAGE, RECENTS_ACTIVITY);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        if (opts != null) {
            mContext.startActivityAsUser(intent, opts.toBundle(), UserHandle.CURRENT);
        } else {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        }
        mCanReuseTaskStackViews = true;
    }

    /**** OnAnimationStartedListener Implementation ****/

    @Override
    public void onAnimationStarted() {
        // Notify recents to start the enter animation
        if (!mStartAnimationTriggered) {
            mStartAnimationTriggered = true;
            EventBus.getDefault().post(new EnterRecentsWindowAnimationStartedEvent());
        }
    }
}
