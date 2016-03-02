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
import android.app.UiModeManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.MutableBoolean;
import android.view.AppTransitionAnimationSpec;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DockingTopTaskEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowLastAnimationFrameEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.IterateRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchNextTaskRequestEvent;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEndedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEvent;
import com.android.systemui.recents.misc.DozeTrigger;
import com.android.systemui.recents.misc.ForegroundThread;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskGrouping;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.tv.views.TaskStackHorizontalGridView;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm;
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.recents.views.TaskViewHeader;
import com.android.systemui.recents.views.TaskViewTransform;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.phone.NavigationBarGestureHelper;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.util.ArrayList;

import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;

/**
 * An implementation of the Recents component for the current user.  For secondary users, this can
 * be called remotely from the system user.
 */
public class RecentsImpl implements ActivityOptions.OnAnimationFinishedListener {

    private final static String TAG = "RecentsImpl";

    // The minimum amount of time between each recents button press that we will handle
    private final static int MIN_TOGGLE_DELAY_MS = 350;

    // The duration within which the user releasing the alt tab (from when they pressed alt tab)
    // that the fast alt-tab animation will run.  If the user's alt-tab takes longer than this
    // duration, then we will toggle recents after this duration.
    private final static int FAST_ALT_TAB_DELAY_MS = 225;

    public final static String RECENTS_PACKAGE = "com.android.systemui";
    public final static String RECENTS_ACTIVITY = "com.android.systemui.recents.RecentsActivity";
    public final static String RECENTS_TV_ACTIVITY = "com.android.systemui.recents.tv.RecentsTvActivity";

    //Used to store tv or non-tv activty for use in creating intents.
    private final String mRecentsIntentActivityName;

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

        @Override
        public void onActivityPinned() {
        }

        @Override
        public void onPinnedActivityRestartAttempt() {
        }

        @Override
        public void onPinnedStackAnimationEnded() {
        }

        /** Preloads the next task */
        public void run() {
            RecentsConfiguration config = Recents.getConfiguration();
            if (config.svelteLevel == RecentsConfiguration.SVELTE_NONE) {
                RecentsTaskLoader loader = Recents.getTaskLoader();
                SystemServicesProxy ssp = Recents.getSystemServices();
                ActivityManager.RunningTaskInfo runningTaskInfo = ssp.getTopMostTask();

                // Load the next task only if we aren't svelte
                RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
                loader.preloadTasks(plan, -1, true /* isTopTaskHome */);
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
        }
    }

    private static RecentsTaskLoadPlan sInstanceLoadPlan;

    Context mContext;
    Handler mHandler;
    TaskStackListenerImpl mTaskStackListener;
    RecentsAppWidgetHost mAppWidgetHost;
    boolean mCanReuseTaskStackViews = true;
    boolean mDraggingInRecents;
    boolean mLaunchedWhileDocking;

    // Task launching
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
    DozeTrigger mFastAltTabTrigger = new DozeTrigger(FAST_ALT_TAB_DELAY_MS, new Runnable() {
        @Override
        public void run() {
            // When this fires, then the user has not released alt-tab for at least
            // FAST_ALT_TAB_DELAY_MS milliseconds
            showRecents(mTriggeredFromAltTab, false /* draggingInRecents */, true /* animate */,
                    false /* reloadTasks */);
        }
    });

    Bitmap mThumbnailTransitionBitmapCache;
    Task mThumbnailTransitionBitmapCacheKey;

    public RecentsImpl(Context context) {
        mContext = context;
        mHandler = new Handler();
        mAppWidgetHost = new RecentsAppWidgetHost(mContext, RecentsAppWidgetHost.HOST_ID);

        // Initialize the static foreground thread
        ForegroundThread.get();

        // Register the task stack listener
        mTaskStackListener = new TaskStackListenerImpl(mHandler);
        SystemServicesProxy ssp = Recents.getSystemServices();
        ssp.registerTaskStackListener(mTaskStackListener);

        // Initialize the static configuration resources
        reloadHeaderBarLayout();
        updateHeaderBarLayout(true /* tryAndBindSearchWidget */, null /* stack */);

        // When we start, preload the data associated with the previous recent tasks.
        // We can use a new plan since the caches will be the same.
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, -1, true /* isTopTaskHome */);
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.numVisibleTasks = loader.getIconCacheSize();
        launchOpts.numVisibleTaskThumbnails = loader.getThumbnailCacheSize();
        launchOpts.onlyLoadForCache = true;
        loader.loadTasks(mContext, plan, launchOpts);

        //Manager used to determine if we are running on tv or not
        UiModeManager uiModeManager = (UiModeManager) mContext.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            mRecentsIntentActivityName = RECENTS_TV_ACTIVITY;
        } else {
            mRecentsIntentActivityName = RECENTS_ACTIVITY;
        }
    }

    public void onBootCompleted() {
        updateHeaderBarLayout(true /* tryAndBindSearchWidget */, null /* stack */);
    }

    public void onConfigurationChanged() {
        reloadHeaderBarLayout();
        updateHeaderBarLayout(true /* tryAndBindSearchWidget */, null /* stack */);
        // Don't reuse task stack views if the configuration changes
        mCanReuseTaskStackViews = false;
        Recents.getConfiguration().updateOnConfigurationChange();
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

    public void showRecents(boolean triggeredFromAltTab, boolean draggingInRecents,
            boolean animate, boolean launchedWhileDockingTask) {
        mTriggeredFromAltTab = triggeredFromAltTab;
        mDraggingInRecents = draggingInRecents;
        mLaunchedWhileDocking = launchedWhileDockingTask;
        if (mFastAltTabTrigger.hasTriggered()) {
            // We are calling this from the doze trigger, so just fall through to show Recents
            mFastAltTabTrigger.resetTrigger();
        } else if (mFastAltTabTrigger.isDozing()) {
            // We are dozing but haven't yet triggered, ignore this if this is not another alt-tab,
            // otherwise, this is an additional tab (alt-tab*), which means that we should trigger
            // immediately (fall through and disable the pending trigger)
            // TODO: This is tricky, we need to handle the tab key, but Recents has not yet started
            //       so we may actually additional signal to handle multiple quick tab cases.  The
            //       severity of this is inversely proportional to the FAST_ALT_TAB_DELAY_MS
            //       duration though
            if (!triggeredFromAltTab) {
                return;
            }
            mFastAltTabTrigger.stopDozing();
        } else {
            // Otherwise, the doze trigger is not running, and if this is an alt tab, we should
            // start the trigger and then wait for the hide (or for it to elapse)
            if (triggeredFromAltTab) {
                mFastAltTabTrigger.startDozing();
                return;
            }
        }

        try {
            // Check if the top task is in the home stack, and start the recents activity
            SystemServicesProxy ssp = Recents.getSystemServices();
            ActivityManager.RunningTaskInfo topTask = ssp.getTopMostTask();
            MutableBoolean isTopTaskHome = new MutableBoolean(true);
            if (topTask == null || !ssp.isRecentsTopMost(topTask, isTopTaskHome)) {
                startRecentsActivity(topTask, isTopTaskHome.value, animate);
            }
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Failed to launch RecentsActivity", e);
        }
    }

    public void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (triggeredFromAltTab && mFastAltTabTrigger.isDozing()) {
            // The user has released alt-tab before the trigger has run, so just show the next
            // task immediately
            showNextTask();

            // Cancel the fast alt-tab trigger
            mFastAltTabTrigger.stopDozing();
            mFastAltTabTrigger.resetTrigger();
            return;
        }

        // Defer to the activity to handle hiding recents, if it handles it, then it must still
        // be visible
        EventBus.getDefault().post(new HideRecentsEvent(triggeredFromAltTab,
                triggeredFromHomeKey));
    }

    public void toggleRecents() {
        // Skip this toggle if we are already waiting to trigger recents via alt-tab
        if (mFastAltTabTrigger.isDozing()) {
            return;
        }

        mDraggingInRecents = false;
        mLaunchedWhileDocking = false;
        mTriggeredFromAltTab = false;

        try {
            SystemServicesProxy ssp = Recents.getSystemServices();
            ActivityManager.RunningTaskInfo topTask = ssp.getTopMostTask();
            MutableBoolean isTopTaskHome = new MutableBoolean(true);
            long elapsedTime = SystemClock.elapsedRealtime() - mLastToggleTime;

            if (topTask != null && ssp.isRecentsTopMost(topTask, isTopTaskHome)) {
                RecentsConfiguration config = Recents.getConfiguration();
                RecentsActivityLaunchState launchState = config.getLaunchState();
                if (!launchState.launchedWithAltTab) {
                    // If the user taps quickly
                    if (ViewConfiguration.getDoubleTapMinTime() < elapsedTime &&
                            elapsedTime < ViewConfiguration.getDoubleTapTimeout()) {
                        // Launch the next focused task
                        EventBus.getDefault().post(new LaunchNextTaskRequestEvent());
                    } else {
                        // Notify recents to move onto the next task
                        EventBus.getDefault().post(new IterateRecentsEvent());
                    }
                } else {
                    // If the user has toggled it too quickly, then just eat up the event here (it's
                    // better than showing a janky screenshot).
                    // NOTE: Ideally, the screenshot mechanism would take the window transform into
                    // account
                    if (elapsedTime < MIN_TOGGLE_DELAY_MS) {
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
                if (elapsedTime < MIN_TOGGLE_DELAY_MS) {
                    return;
                }

                // Otherwise, start the recents activity
                startRecentsActivity(topTask, isTopTaskHome.value, true /* animate */);

                // Only close the other system windows if we are actually showing recents
                ssp.sendCloseSystemWindows(BaseStatusBar.SYSTEM_DIALOG_REASON_RECENT_APPS);
                mLastToggleTime = SystemClock.elapsedRealtime();
            }
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Failed to launch RecentsActivity", e);
        }
    }

    public void preloadRecents() {
        // Preload only the raw task list into a new load plan (which will be consumed by the
        // RecentsActivity) only if there is a task to animate to.
        SystemServicesProxy ssp = Recents.getSystemServices();
        ActivityManager.RunningTaskInfo topTask = ssp.getTopMostTask();
        MutableBoolean topTaskHome = new MutableBoolean(true);
        if (topTask != null && !ssp.isRecentsTopMost(topTask, topTaskHome)) {
            RecentsTaskLoader loader = Recents.getTaskLoader();
            sInstanceLoadPlan = loader.createLoadPlan(mContext);
            sInstanceLoadPlan.preloadRawTasks(topTaskHome.value);
            loader.preloadTasks(sInstanceLoadPlan, topTask.id, topTaskHome.value);
            TaskStack stack = sInstanceLoadPlan.getTaskStack();
            if (stack.getTaskCount() > 0) {
                // We try and draw the thumbnail transition bitmap in parallel before
                // toggle/show recents is called
                preCacheThumbnailTransitionBitmapAsync(topTask, stack, mDummyStackView);
            }
        }
    }

    public void cancelPreloadingRecents() {
        // Do nothing
    }

    public void onDraggingInRecents(float distanceFromTop) {
        EventBus.getDefault().sendOntoMainThread(new DraggingInRecentsEvent(distanceFromTop));
    }

    public void onDraggingInRecentsEnded(float velocity) {
        EventBus.getDefault().sendOntoMainThread(new DraggingInRecentsEndedEvent(velocity));
    }

    /**
     * Transitions to the next recent task in the stack.
     */
    public void showNextTask() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, -1, true /* isTopTaskHome */);
        TaskStack focusedStack = plan.getTaskStack();

        // Return early if there are no tasks in the focused stack
        if (focusedStack == null || focusedStack.getTaskCount() == 0) return;

        ActivityManager.RunningTaskInfo runningTask = ssp.getTopMostTask();
        // Return early if there is no running task
        if (runningTask == null) return;

        // Find the task in the recents list
        boolean isTopTaskHome = SystemServicesProxy.isHomeStack(runningTask.stackId);
        ArrayList<Task> tasks = focusedStack.getStackTasks();
        Task toTask = null;
        ActivityOptions launchOpts = null;
        int taskCount = tasks.size();
        for (int i = taskCount - 1; i >= 1; i--) {
            Task task = tasks.get(i);
            if (isTopTaskHome) {
                toTask = tasks.get(i - 1);
                launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                        R.anim.recents_launch_next_affiliated_task_target,
                        R.anim.recents_fast_toggle_app_home_exit);
                break;
            } else if (task.key.id == runningTask.id) {
                toTask = tasks.get(i - 1);
                launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                        R.anim.recents_launch_prev_affiliated_task_target,
                        R.anim.recents_launch_prev_affiliated_task_source);
                break;
            }
        }

        // Return early if there is no next task
        if (toTask == null) {
            ssp.startInPlaceAnimationOnFrontMostApplication(
                    ActivityOptions.makeCustomInPlaceAnimation(mContext,
                            R.anim.recents_launch_prev_affiliated_task_bounce));
            return;
        }

        // Launch the task
        ssp.startActivityFromRecents(mContext, toTask.key.id, toTask.title, launchOpts);
    }

    /**
     * Transitions to the next affiliated task.
     */
    public void showRelativeAffiliatedTask(boolean showNextTask) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, -1, true /* isTopTaskHome */);
        TaskStack focusedStack = plan.getTaskStack();

        // Return early if there are no tasks in the focused stack
        if (focusedStack == null || focusedStack.getTaskCount() == 0) return;

        ActivityManager.RunningTaskInfo runningTask = ssp.getTopMostTask();
        // Return early if there is no running task (can't determine affiliated tasks in this case)
        if (runningTask == null) return;
        // Return early if the running task is in the home stack (optimization)
        if (SystemServicesProxy.isHomeStack(runningTask.stackId)) return;

        // Find the task in the recents list
        ArrayList<Task> tasks = focusedStack.getStackTasks();
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
        ssp.startActivityFromRecents(mContext, toTask.key.id, toTask.title, launchOpts);
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

    public void dockTopTask(int topTaskId, int dragMode,
            int stackCreateMode, Rect initialBounds) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Make sure we inform DividerView before we actually start the activity so we can change
        // the resize mode already.
        if (ssp.moveTaskToDockedStack(topTaskId, stackCreateMode, initialBounds)) {
            EventBus.getDefault().send(new DockingTopTaskEvent(dragMode));
            showRecents(
                    false /* triggeredFromAltTab */,
                    dragMode == NavigationBarGestureHelper.DRAG_MODE_RECENTS,
                    false /* animate */,
                    true /* reloadTasks*/);
        }
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
     * Reloads all the layouts for the header bar transition.
     */
    private void reloadHeaderBarLayout() {
        Resources res = mContext.getResources();
        LayoutInflater inflater = LayoutInflater.from(mContext);

        mStatusBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mNavBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        mNavBarWidth = res.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_width);
        mTaskBarHeight = res.getDimensionPixelSize(
                R.dimen.recents_task_bar_height);
        mDummyStackView = new TaskStackView(mContext, new TaskStack());
        mHeaderBar = (TaskViewHeader) inflater.inflate(R.layout.recents_task_view_header,
                null, false);
    }

    /**
     * Prepares the header bar layout for the next transition, if the task view bounds has changed
     * since the last call, it will attempt to re-measure and layout the header bar to the new size.
     *
     * @param tryAndBindSearchWidget if set, will attempt to fetch and bind the search widget if one
     *                               is not already bound (can be expensive)
     * @param stack the stack to initialize the stack layout with
     */
    private void updateHeaderBarLayout(boolean tryAndBindSearchWidget,
            TaskStack stack) {
        RecentsConfiguration config = Recents.getConfiguration();
        SystemServicesProxy ssp = Recents.getSystemServices();
        Rect systemInsets = new Rect();
        ssp.getStableInsets(systemInsets);
        Rect windowRect = ssp.getWindowRect();
        calculateWindowStableInsets(systemInsets, windowRect);
        windowRect.offsetTo(0, 0);

        // Update the configuration for the current state
        config.update(systemInsets);

        if (RecentsDebugFlags.Static.EnableSearchBar && tryAndBindSearchWidget) {
            // Try and pre-emptively bind the search widget on startup to ensure that we
            // have the right thumbnail bounds to animate to.
            // Note: We have to reload the widget id before we get the task stack bounds below
            if (ssp.getOrBindSearchAppWidget(mContext, mAppWidgetHost) != null) {
                config.getSearchBarBounds(windowRect, mStatusBarHeight, mSearchBarBounds);
            }
        }
        config.getTaskStackBounds(windowRect, systemInsets.top, systemInsets.right,
                mSearchBarBounds, mTaskStackBounds);

        // Rebind the header bar and draw it for the transition
        TaskStackLayoutAlgorithm algo = mDummyStackView.getStackAlgorithm();
        Rect taskStackBounds = new Rect(mTaskStackBounds);
        algo.setSystemInsets(systemInsets);
        if (stack != null) {
            algo.initialize(taskStackBounds,
                    TaskStackLayoutAlgorithm.StackState.getStackStateForStack(stack));
        }
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
     * Given the stable insets and the rect for our window, calculates the insets that affect our
     * window.
     */
    private void calculateWindowStableInsets(Rect inOutInsets, Rect windowRect) {
        Rect displayRect = Recents.getSystemServices().getDisplayRect();

        // Display rect without insets - available app space
        Rect appRect = new Rect(displayRect);
        appRect.inset(inOutInsets);

        // Our window intersected with available app space
        Rect windowRectWithInsets = new Rect(windowRect);
        windowRectWithInsets.intersect(appRect);
        inOutInsets.left = windowRectWithInsets.left - windowRect.left;
        inOutInsets.top = windowRectWithInsets.top - windowRect.top;
        inOutInsets.right = windowRect.right - windowRectWithInsets.right;
        inOutInsets.bottom = windowRect.bottom - windowRectWithInsets.bottom;
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
        updateHeaderBarLayout(false /* tryAndBindSearchWidget */, stack);

        // Update the destination rect
        mDummyStackView.updateLayoutForStack(stack);
        final Task toTask = new Task();
        final TaskViewTransform toTransform = getThumbnailTransitionTransform(stack, stackView,
                toTask);
        ForegroundThread.getHandler().postAtFrontOfQueue(new Runnable() {
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
                mHandler, null);
    }

    /**
     * Creates the activity options for a home->recents transition.
     */
    private ActivityOptions getHomeTransitionActivityOptions(boolean fromSearchHome) {
        if (fromSearchHome) {
            return ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.recents_from_search_launcher_enter,
                    R.anim.recents_from_search_launcher_exit,
                    mHandler, null);
        }
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_launcher_enter,
                R.anim.recents_from_launcher_exit,
                mHandler, null);
    }

    /**
     * Creates the activity options for an app->recents transition.
     */
    private ActivityOptions getThumbnailTransitionActivityOptions(
            ActivityManager.RunningTaskInfo topTask, TaskStack stack, TaskStackView stackView) {
        if (topTask.stackId == FREEFORM_WORKSPACE_STACK_ID) {
            ArrayList<AppTransitionAnimationSpec> specs = new ArrayList<>();
            stackView.getScroller().setStackScrollToInitialState();
            ArrayList<Task> tasks = stack.getStackTasks();
            for (int i = tasks.size() - 1; i >= 0; i--) {
                Task task = tasks.get(i);
                if (task.isFreeformTask()) {
                    mTmpTransform = stackView.getStackAlgorithm()
                            .getStackTransformScreenCoordinates(task,
                                    stackView.getScroller().getStackScroll(), mTmpTransform, null);
                    Rect toTaskRect = new Rect();
                    mTmpTransform.rect.round(toTaskRect);
                    Bitmap thumbnail = getThumbnailBitmap(topTask, task, mTmpTransform);
                    specs.add(new AppTransitionAnimationSpec(task.key.id, thumbnail, toTaskRect));
                }
            }
            AppTransitionAnimationSpec[] specsArray = new AppTransitionAnimationSpec[specs.size()];
            specs.toArray(specsArray);
            return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mDummyStackView,
                    specsArray, mHandler, null, this);
        } else {
            // Update the destination rect
            Task toTask = new Task();
            TaskViewTransform toTransform = getThumbnailTransitionTransform(stack, stackView,
                    toTask);
            RectF toTaskRect = toTransform.rect;
            Bitmap thumbnail = getThumbnailBitmap(topTask, toTask, toTransform);
            if (thumbnail != null) {
                return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mDummyStackView,
                        thumbnail, (int) toTaskRect.left, (int) toTaskRect.top,
                        (int) toTaskRect.width(), (int) toTaskRect.height(), mHandler, null);
            }
            // If both the screenshot and thumbnail fails, then just fall back to the default transition
            return getUnknownTransitionActivityOptions();
        }
    }

    private Bitmap getThumbnailBitmap(ActivityManager.RunningTaskInfo topTask, Task toTask,
            TaskViewTransform toTransform) {
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
        return thumbnail;
    }

    /**
     * Returns the transition rect for the given task id.
     */
    private TaskViewTransform getThumbnailTransitionTransform(TaskStack stack,
            TaskStackView stackView, Task runningTaskOut) {
        // Find the running task in the TaskStack
        Task launchTask = stack.getLaunchTarget();
        if (launchTask != null) {
            runningTaskOut.copyFrom(launchTask);
        } else {
            // If no task is specified or we can not find the task just use the front most one
            launchTask = stack.getStackFrontMostTask(true /* includeFreeform */);
            runningTaskOut.copyFrom(launchTask);
        }

        // Get the transform for the running task
        stackView.getScroller().setStackScrollToInitialState();
        mTmpTransform = stackView.getStackAlgorithm().getStackTransformScreenCoordinates(launchTask,
                stackView.getScroller().getStackScroll(), mTmpTransform, null);
        return mTmpTransform;
    }

    /**
     * Draws the header of a task used for the window animation into a bitmap.
     */
    private Bitmap drawThumbnailTransitionBitmap(Task toTask, TaskViewTransform toTransform) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (toTransform != null && toTask.key != null) {
            Bitmap thumbnail;
            synchronized (mHeaderBarLock) {
                int toHeaderWidth = (int) toTransform.rect.width();
                int toHeaderHeight = (int) (mHeaderBar.getMeasuredHeight() * toTransform.scale);
                boolean disabledInSafeMode = !toTask.isSystemApp && ssp.isInSafeMode();
                mHeaderBar.onTaskViewSizeChanged((int) toTransform.rect.width(),
                        (int) toTransform.rect.height());
                thumbnail = Bitmap.createBitmap(toHeaderWidth, toHeaderHeight,
                        Bitmap.Config.ARGB_8888);
                if (RecentsDebugFlags.Static.EnableTransitionThumbnailDebugMode) {
                    thumbnail.eraseColor(0xFFff0000);
                } else {
                    Canvas c = new Canvas(thumbnail);
                    c.scale(toTransform.scale, toTransform.scale);
                    mHeaderBar.rebindToTask(toTask, false /* touchExplorationEnabled */,
                            disabledInSafeMode);
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
            boolean isTopTaskHome, boolean animate) {
        RecentsTaskLoader loader = Recents.getTaskLoader();

        // In the case where alt-tab is triggered, we never get a preloadRecents() call, so we
        // should always preload the tasks now. If we are dragging in recents, reload them as
        // the stacks might have changed.
        if (mLaunchedWhileDocking || mTriggeredFromAltTab ||sInstanceLoadPlan == null) {
            // Create a new load plan if preloadRecents() was never triggered
            sInstanceLoadPlan = loader.createLoadPlan(mContext);
        }
        if (mLaunchedWhileDocking || mTriggeredFromAltTab || !sInstanceLoadPlan.hasTasks()) {
            loader.preloadTasks(sInstanceLoadPlan, topTask.id, isTopTaskHome);
        }
        TaskStack stack = sInstanceLoadPlan.getTaskStack();

        // Update the header bar if necessary
        updateHeaderBarLayout(false /* tryAndBindSearchWidget */, stack);

        // Prepare the dummy stack for the transition
        mDummyStackView.updateLayoutForStack(stack);
        TaskStackLayoutAlgorithm.VisibilityReport stackVr =
                mDummyStackView.computeStackVisibilityReport();

        if (!animate) {
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext, -1, -1);
            startRecentsActivity(topTask, opts, false /* fromHome */,
                    false /* fromSearchHome */, false /* fromThumbnail*/, stackVr);
            return;
        }

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
            if (hasRecentTasks) {
                SystemServicesProxy ssp = Recents.getSystemServices();
                String homeActivityPackage = ssp.getHomeActivityPackageName();
                String searchWidgetPackage = null;
                if (RecentsDebugFlags.Static.EnableSearchBar) {
                    searchWidgetPackage = Prefs.getString(mContext,
                            Prefs.Key.OVERVIEW_SEARCH_APP_WIDGET_PACKAGE, null);
                } else {
                    AppWidgetProviderInfo searchWidgetInfo = ssp.resolveSearchAppWidget();
                    if (searchWidgetInfo != null) {
                        searchWidgetPackage = searchWidgetInfo.provider.getPackageName();
                    }
                }

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
              TaskStackLayoutAlgorithm.VisibilityReport vr) {
        // Update the configuration based on the launch options
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        launchState.launchedFromHome = fromSearchHome || fromHome;
        launchState.launchedFromSearchHome = fromSearchHome;
        launchState.launchedFromAppWithThumbnail = fromThumbnail;
        launchState.launchedToTaskId = (topTask != null) ? topTask.id : -1;
        launchState.launchedWithAltTab = mTriggeredFromAltTab;
        launchState.launchedReuseTaskStackViews = mCanReuseTaskStackViews;
        launchState.launchedNumVisibleTasks = vr.numVisibleTasks;
        launchState.launchedNumVisibleThumbnails = vr.numVisibleThumbnails;
        launchState.launchedHasConfigurationChanged = false;
        launchState.launchedViaDragGesture = mDraggingInRecents;
        launchState.launchedWhileDocking = mLaunchedWhileDocking;

        Intent intent = new Intent();
        intent.setClassName(RECENTS_PACKAGE, mRecentsIntentActivityName);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);

        if (opts != null) {
            mContext.startActivityAsUser(intent, opts.toBundle(), UserHandle.CURRENT);
        } else {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        }
        mCanReuseTaskStackViews = true;
        EventBus.getDefault().send(new RecentsActivityStartingEvent());
    }

    /**** OnAnimationFinishedListener Implementation ****/

    @Override
    public void onAnimationFinished() {
        EventBus.getDefault().post(new EnterRecentsWindowLastAnimationFrameEvent());
    }
}
