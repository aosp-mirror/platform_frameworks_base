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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.view.View.MeasureSpec;

import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_RECENT_APPS;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.trust.TrustManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.MutableBoolean;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import android.widget.Toast;

import com.android.systemui.Dependency;
import com.android.systemui.OverviewProxyService;
import com.google.android.collect.Lists;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.policy.DockedDividerUtils;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DockedTopTaskEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowLastAnimationFrameEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchMostRecentTaskRequestEvent;
import com.android.systemui.recents.events.activity.LaunchNextTaskRequestEvent;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.ActivityPinnedEvent;
import com.android.systemui.recents.events.component.ActivityUnpinnedEvent;
import com.android.systemui.recents.events.component.HidePipMenuEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEndedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEvent;
import com.android.systemui.recents.events.ui.TaskSnapshotChangedEvent;
import com.android.systemui.recents.misc.DozeTrigger;
import com.android.systemui.recents.misc.ForegroundThread;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SysUiTaskStackChangeListener;
import com.android.systemui.shared.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.shared.recents.model.RecentsTaskLoader;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.Task.TaskKey;
import com.android.systemui.shared.recents.model.TaskStack;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm.VisibilityReport;
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.recents.views.TaskViewHeader;
import com.android.systemui.recents.views.TaskViewTransform;
import com.android.systemui.recents.views.grid.TaskGridLayoutAlgorithm;
import com.android.systemui.shared.recents.view.AppTransitionAnimationSpecCompat;
import com.android.systemui.shared.recents.view.AppTransitionAnimationSpecsFuture;
import com.android.systemui.shared.recents.view.RecentsTransition;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.stackdivider.DividerView;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.ArrayList;
import java.util.List;

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

    private final static ArraySet<TaskKey> EMPTY_SET = new ArraySet<>();

    public final static String RECENTS_PACKAGE = "com.android.systemui";
    public final static String RECENTS_ACTIVITY = "com.android.systemui.recents.RecentsActivity";

    /**
     * An implementation of SysUiTaskStackChangeListener, that allows us to listen for changes to the system
     * task stacks and update recents accordingly.
     */
    class TaskStackListenerImpl extends SysUiTaskStackChangeListener {

        private OverviewProxyService mOverviewProxyService;

        public TaskStackListenerImpl() {
            mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        }

        @Override
        public void onTaskStackChangedBackground() {
            // Skip background preloading recents in SystemUI if the overview services is bound
            if (mOverviewProxyService.isEnabled()) {
                return;
            }

            // Check this is for the right user
            if (!checkCurrentUserId(mContext, false /* debug */)) {
                return;
            }

            // Preloads the next task
            RecentsConfiguration config = Recents.getConfiguration();
            if (config.svelteLevel == RecentsTaskLoader.SVELTE_NONE) {
                Rect windowRect = getWindowRect(null /* windowRectOverride */);
                if (windowRect.isEmpty()) {
                    return;
                }

                // Load the next task only if we aren't svelte
                ActivityManager.RunningTaskInfo runningTaskInfo =
                        ActivityManagerWrapper.getInstance().getRunningTask();
                RecentsTaskLoader loader = Recents.getTaskLoader();
                RecentsTaskLoadPlan plan = new RecentsTaskLoadPlan(mContext);
                loader.preloadTasks(plan, -1);
                TaskStack stack = plan.getTaskStack();
                RecentsActivityLaunchState launchState = new RecentsActivityLaunchState();
                RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();

                synchronized (mBackgroundLayoutAlgorithm) {
                    // This callback is made when a new activity is launched and the old one is
                    // paused so ignore the current activity and try and preload the thumbnail for
                    // the previous one.
                    updateDummyStackViewLayout(mBackgroundLayoutAlgorithm, stack, windowRect);

                    // Launched from app is always the worst case (in terms of how many
                    // thumbnails/tasks visible)
                    launchState.launchedFromApp = true;
                    mBackgroundLayoutAlgorithm.update(plan.getTaskStack(), EMPTY_SET, launchState,
                            -1 /* lastScrollPPresent */);
                    VisibilityReport visibilityReport =
                            mBackgroundLayoutAlgorithm.computeStackVisibilityReport(
                                    stack.getTasks());

                    launchOpts.runningTaskId = runningTaskInfo != null ? runningTaskInfo.id : -1;
                    launchOpts.numVisibleTasks = visibilityReport.numVisibleTasks;
                    launchOpts.numVisibleTaskThumbnails = visibilityReport.numVisibleThumbnails;
                    launchOpts.onlyLoadForCache = true;
                    launchOpts.onlyLoadPausedActivities = true;
                    launchOpts.loadThumbnails = true;
                }
                loader.loadTasks(plan, launchOpts);
            }
        }

        @Override
        public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
            // Check this is for the right user
            if (!checkCurrentUserId(mContext, false /* debug */)) {
                return;
            }

            // This time needs to be fetched the same way the last active time is fetched in
            // {@link TaskRecord#touchActiveTime}
            Recents.getConfiguration().getLaunchState().launchedFromPipApp = true;
            Recents.getConfiguration().getLaunchState().launchedWithNextPipApp = false;
            EventBus.getDefault().send(new ActivityPinnedEvent(taskId));
            consumeInstanceLoadPlan();
            sLastPipTime = System.currentTimeMillis();
        }

        @Override
        public void onActivityUnpinned() {
            // Check this is for the right user
            if (!checkCurrentUserId(mContext, false /* debug */)) {
                return;
            }

            EventBus.getDefault().send(new ActivityUnpinnedEvent());
            sLastPipTime = -1;
        }

        @Override
        public void onTaskSnapshotChanged(int taskId, ThumbnailData snapshot) {
            // Check this is for the right user
            if (!checkCurrentUserId(mContext, false /* debug */)) {
                return;
            }

            EventBus.getDefault().send(new TaskSnapshotChangedEvent(taskId, snapshot));
        }
    }

    protected static RecentsTaskLoadPlan sInstanceLoadPlan;
    // Stores the last pinned task time
    protected static long sLastPipTime = -1;
    // Stores whether we are waiting for a transition to/from recents to start. During this time,
    // we disallow the user from manually toggling recents until the transition has started.
    private static boolean mWaitingForTransitionStart = false;
    // Stores whether or not the user toggled while we were waiting for a transition to/from
    // recents. In this case, we defer the toggle state until then and apply it immediately after.
    private static boolean mToggleFollowingTransitionStart = true;

    private Runnable mResetToggleFlagListener = new Runnable() {
        @Override
        public void run() {
            setWaitingForTransitionStart(false);
        }
    };

    private TrustManager mTrustManager;
    protected Context mContext;
    protected Handler mHandler;
    TaskStackListenerImpl mTaskStackListener;
    boolean mDraggingInRecents;
    boolean mLaunchedWhileDocking;

    // Task launching
    Rect mTmpBounds = new Rect();
    TaskViewTransform mTmpTransform = new TaskViewTransform();
    int mTaskBarHeight;

    // Header (for transition)
    TaskViewHeader mHeaderBar;
    final Object mHeaderBarLock = new Object();
    private TaskStackView mDummyStackView;
    private TaskStackLayoutAlgorithm mBackgroundLayoutAlgorithm;

    // Variables to keep track of if we need to start recents after binding
    protected boolean mTriggeredFromAltTab;
    protected long mLastToggleTime;
    DozeTrigger mFastAltTabTrigger = new DozeTrigger(FAST_ALT_TAB_DELAY_MS, new Runnable() {
        @Override
        public void run() {
            // When this fires, then the user has not released alt-tab for at least
            // FAST_ALT_TAB_DELAY_MS milliseconds
            showRecents(mTriggeredFromAltTab, false /* draggingInRecents */, true /* animate */,
                    DividerView.INVALID_RECENTS_GROW_TARGET);
        }
    });

    private OverviewProxyService.OverviewProxyListener mOverviewProxyListener =
            new OverviewProxyService.OverviewProxyListener() {
        @Override
        public void onConnectionChanged(boolean isConnected) {
            if (!isConnected) {
                // Clear everything when the connection to the overview service
                Recents.getTaskLoader().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
            }
        }
    };

    // Used to reset the dummy stack view
    private final TaskStack mEmptyTaskStack = new TaskStack();

    public RecentsImpl(Context context) {
        mContext = context;
        mHandler = new Handler();
        mBackgroundLayoutAlgorithm = new TaskStackLayoutAlgorithm(context, null);

        // Initialize the static foreground thread
        ForegroundThread.get();

        // Register the task stack listener
        mTaskStackListener = new TaskStackListenerImpl();
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);

        // Initialize the static configuration resources
        mDummyStackView = new TaskStackView(mContext);
        reloadResources();

        mTrustManager = (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);
    }

    public void onBootCompleted() {
        // Skip preloading tasks if we are already bound to the service
        if (Dependency.get(OverviewProxyService.class).isEnabled()) {
            return;
        }

        // When we start, preload the data associated with the previous recent tasks.
        // We can use a new plan since the caches will be the same.
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = new RecentsTaskLoadPlan(mContext);
        loader.preloadTasks(plan, -1);
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.numVisibleTasks = loader.getIconCacheSize();
        launchOpts.numVisibleTaskThumbnails = loader.getThumbnailCacheSize();
        launchOpts.onlyLoadForCache = true;
        loader.loadTasks(plan, launchOpts);
    }

    public void onConfigurationChanged() {
        reloadResources();
        mDummyStackView.reloadOnConfigurationChange();
        synchronized (mBackgroundLayoutAlgorithm) {
            mBackgroundLayoutAlgorithm.reloadOnConfigurationChange(mContext);
        }
    }

    /**
     * This is only called from the system user's Recents.  Secondary users will instead proxy their
     * visibility change events through to the system user via
     * {@link Recents#onBusEvent(RecentsVisibilityChangedEvent)}.
     */
    public void onVisibilityChanged(Context context, boolean visible) {
        Recents.getSystemServices().setRecentsVisibility(visible);
    }

    /**
     * This is only called from the system user's Recents.  Secondary users will instead proxy their
     * visibility change events through to the system user via
     * {@link Recents#onBusEvent(ScreenPinningRequestEvent)}.
     */
    public void onStartScreenPinning(Context context, int taskId) {
        final StatusBar statusBar = getStatusBar();
        if (statusBar != null) {
            statusBar.showScreenPinningRequest(taskId, false);
        }
    }

    public void showRecents(boolean triggeredFromAltTab, boolean draggingInRecents,
            boolean animate, int growTarget) {
        final SystemServicesProxy ssp = Recents.getSystemServices();
        final MutableBoolean isHomeStackVisible = new MutableBoolean(true);
        final boolean isRecentsVisible = Recents.getSystemServices().isRecentsActivityVisible(
                isHomeStackVisible);
        final boolean fromHome = isHomeStackVisible.value;
        final boolean launchedWhileDockingTask =
                Recents.getSystemServices().getSplitScreenPrimaryStack() != null;

        mTriggeredFromAltTab = triggeredFromAltTab;
        mDraggingInRecents = draggingInRecents;
        mLaunchedWhileDocking = launchedWhileDockingTask;
        if (mFastAltTabTrigger.isAsleep()) {
            // Fast alt-tab duration has elapsed, fall through to showing Recents and reset
            mFastAltTabTrigger.stopDozing();
        } else if (mFastAltTabTrigger.isDozing()) {
            // Fast alt-tab duration has not elapsed.  If this is triggered by a different
            // showRecents() call, then ignore that call for now.
            // TODO: We can not handle quick tabs that happen between the initial showRecents() call
            //       that started the activity and the activity starting up.  The severity of this
            //       is inversely proportional to the FAST_ALT_TAB_DELAY_MS duration though.
            if (!triggeredFromAltTab) {
                return;
            }
            mFastAltTabTrigger.stopDozing();
        } else if (triggeredFromAltTab) {
            // The fast alt-tab detector is not yet running, so start the trigger and wait for the
            // hideRecents() call, or for the fast alt-tab duration to elapse
            mFastAltTabTrigger.startDozing();
            return;
        }

        try {
            // Check if the top task is in the home stack, and start the recents activity
            final boolean forceVisible = launchedWhileDockingTask || draggingInRecents;
            if (forceVisible || !isRecentsVisible) {
                ActivityManager.RunningTaskInfo runningTask =
                        ActivityManagerWrapper.getInstance().getRunningTask();
                startRecentsActivityAndDismissKeyguardIfNeeded(runningTask,
                        isHomeStackVisible.value || fromHome, animate, growTarget);
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
            return;
        }

        // Defer to the activity to handle hiding recents, if it handles it, then it must still
        // be visible
        EventBus.getDefault().post(new HideRecentsEvent(triggeredFromAltTab,
                triggeredFromHomeKey));
    }

    public void toggleRecents(int growTarget) {
        if (ActivityManagerWrapper.getInstance().isScreenPinningActive()) {
            return;
        }

        // Skip this toggle if we are already waiting to trigger recents via alt-tab
        if (mFastAltTabTrigger.isDozing()) {
            return;
        }

        if (mWaitingForTransitionStart) {
            mToggleFollowingTransitionStart = true;
            return;
        }

        mDraggingInRecents = false;
        mLaunchedWhileDocking = false;
        mTriggeredFromAltTab = false;

        try {
            MutableBoolean isHomeStackVisible = new MutableBoolean(true);
            long elapsedTime = SystemClock.elapsedRealtime() - mLastToggleTime;

            SystemServicesProxy ssp = Recents.getSystemServices();
            if (ssp.isRecentsActivityVisible(isHomeStackVisible)) {
                RecentsConfiguration config = Recents.getConfiguration();
                RecentsActivityLaunchState launchState = config.getLaunchState();
                if (!launchState.launchedWithAltTab) {
                    if (Recents.getConfiguration().isGridEnabled) {
                        // Has the user tapped quickly?
                        boolean isQuickTap = elapsedTime < ViewConfiguration.getDoubleTapTimeout();
                        if (isQuickTap) {
                            EventBus.getDefault().post(new LaunchNextTaskRequestEvent());
                        } else {
                            EventBus.getDefault().post(new LaunchMostRecentTaskRequestEvent());
                        }
                    } else {
                        // Launch the next focused task
                        EventBus.getDefault().post(new LaunchNextTaskRequestEvent());
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
                ActivityManager.RunningTaskInfo runningTask =
                        ActivityManagerWrapper.getInstance().getRunningTask();
                startRecentsActivityAndDismissKeyguardIfNeeded(runningTask,
                        isHomeStackVisible.value, true /* animate */, growTarget);

                // Only close the other system windows if we are actually showing recents
                ActivityManagerWrapper.getInstance().closeSystemWindows(
                        SYSTEM_DIALOG_REASON_RECENT_APPS);
                mLastToggleTime = SystemClock.elapsedRealtime();
            }
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Failed to launch RecentsActivity", e);
        }
    }

    public void preloadRecents() {
        if (ActivityManagerWrapper.getInstance().isScreenPinningActive()) {
            return;
        }

        // Skip preloading recents when keyguard is showing
        final StatusBar statusBar = getStatusBar();
        if (statusBar != null && statusBar.isKeyguardShowing()) {
            return;
        }

        // Preload only the raw task list into a new load plan (which will be consumed by the
        // RecentsActivity) only if there is a task to animate to.  Post this to ensure that we
        // don't block the touch feedback on the nav bar button which triggers this.
        mHandler.post(() -> {
            SystemServicesProxy ssp = Recents.getSystemServices();
            if (!ssp.isRecentsActivityVisible(null)) {
                ActivityManager.RunningTaskInfo runningTask =
                        ActivityManagerWrapper.getInstance().getRunningTask();
                if (runningTask == null) {
                    return;
                }

                RecentsTaskLoader loader = Recents.getTaskLoader();
                sInstanceLoadPlan = new RecentsTaskLoadPlan(mContext);
                loader.preloadTasks(sInstanceLoadPlan, runningTask.id);
                TaskStack stack = sInstanceLoadPlan.getTaskStack();
                if (stack.getTaskCount() > 0) {
                    // Only preload the icon (but not the thumbnail since it may not have been taken
                    // for the pausing activity)
                    preloadIcon(runningTask.id);

                    // At this point, we don't know anything about the stack state.  So only
                    // calculate the dimensions of the thumbnail that we need for the transition
                    // into Recents, but do not draw it until we construct the activity options when
                    // we start Recents
                    updateHeaderBarLayout(stack, null /* window rect override*/);
                }
            }
        });
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

    public void onShowCurrentUserToast(int msgResId, int msgLength) {
        Toast.makeText(mContext, msgResId, msgLength).show();
    }

    /**
     * Transitions to the next recent task in the stack.
     */
    public void showNextTask() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = new RecentsTaskLoadPlan(mContext);
        loader.preloadTasks(plan, -1);
        TaskStack focusedStack = plan.getTaskStack();

        // Return early if there are no tasks in the focused stack
        if (focusedStack == null || focusedStack.getTaskCount() == 0) return;

        // Return early if there is no running task
        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        if (runningTask == null) return;

        // Find the task in the recents list
        boolean isRunningTaskInHomeStack =
                runningTask.configuration.windowConfiguration.getActivityType()
                        == ACTIVITY_TYPE_HOME;
        ArrayList<Task> tasks = focusedStack.getTasks();
        Task toTask = null;
        ActivityOptions launchOpts = null;
        int taskCount = tasks.size();
        for (int i = taskCount - 1; i >= 1; i--) {
            Task task = tasks.get(i);
            if (isRunningTaskInHomeStack) {
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
        ActivityManagerWrapper.getInstance().startActivityFromRecentsAsync(toTask.key, launchOpts,
                null /* resultCallback */, null /* resultCallbackHandler */);
    }

    /**
     * Transitions to the next affiliated task.
     */
    public void showRelativeAffiliatedTask(boolean showNextTask) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = new RecentsTaskLoadPlan(mContext);
        loader.preloadTasks(plan, -1);
        TaskStack focusedStack = plan.getTaskStack();

        // Return early if there are no tasks in the focused stack
        if (focusedStack == null || focusedStack.getTaskCount() == 0) return;

        // Return early if there is no running task (can't determine affiliated tasks in this case)
        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        final int activityType = runningTask.configuration.windowConfiguration.getActivityType();
        if (runningTask == null) return;
        // Return early if the running task is in the home/recents stack (optimization)
        if (activityType == ACTIVITY_TYPE_HOME || activityType == ACTIVITY_TYPE_RECENTS) return;

        // Find the task in the recents list
        ArrayList<Task> tasks = focusedStack.getTasks();
        Task toTask = null;
        ActivityOptions launchOpts = null;
        int taskCount = tasks.size();
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks.get(i);
            if (task.key.id == runningTask.id) {
                if (showNextTask) {
                    if ((i + 1) < taskCount) {
                        toTask = tasks.get(i + 1);
                        launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                                R.anim.recents_launch_next_affiliated_task_target,
                                R.anim.recents_launch_next_affiliated_task_source);
                    }
                } else {
                    if ((i - 1) >= 0) {
                        toTask = tasks.get(i - 1);
                        launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                                R.anim.recents_launch_prev_affiliated_task_target,
                                R.anim.recents_launch_prev_affiliated_task_source);
                    }
                }
                break;
            }
        }

        // Return early if there is no next task
        if (toTask == null) {
            if (showNextTask) {
                ssp.startInPlaceAnimationOnFrontMostApplication(
                        ActivityOptions.makeCustomInPlaceAnimation(mContext,
                                R.anim.recents_launch_next_affiliated_task_bounce));
            } else {
                ssp.startInPlaceAnimationOnFrontMostApplication(
                        ActivityOptions.makeCustomInPlaceAnimation(mContext,
                                R.anim.recents_launch_prev_affiliated_task_bounce));
            }
            return;
        }

        // Keep track of actually launched affiliated tasks
        MetricsLogger.count(mContext, "overview_affiliated_task_launch", 1);

        // Launch the task
        ActivityManagerWrapper.getInstance().startActivityFromRecentsAsync(toTask.key, launchOpts,
                null /* resultListener */, null /* resultCallbackHandler */);
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

    public void splitPrimaryTask(int taskId, int dragMode, int stackCreateMode,
            Rect initialBounds) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Make sure we inform DividerView before we actually start the activity so we can change
        // the resize mode already.
        if (ssp.setTaskWindowingModeSplitScreenPrimary(taskId, stackCreateMode, initialBounds)) {
            EventBus.getDefault().send(new DockedTopTaskEvent(dragMode, initialBounds));
        }
    }

    public void setWaitingForTransitionStart(boolean waitingForTransitionStart) {
        if (mWaitingForTransitionStart == waitingForTransitionStart) {
            return;
        }

        mWaitingForTransitionStart = waitingForTransitionStart;
        if (!waitingForTransitionStart && mToggleFollowingTransitionStart) {
            mHandler.post(() -> toggleRecents(DividerView.INVALID_RECENTS_GROW_TARGET));
        }
        mToggleFollowingTransitionStart = false;
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
     * @return the time at which a task last entered picture-in-picture.
     */
    public static long getLastPipTime() {
        return sLastPipTime;
    }

    /**
     * Clears the time at which a task last entered picture-in-picture.
     */
    public static void clearLastPipTime() {
        sLastPipTime = -1;
    }

    /**
     * Reloads all the resources for the current configuration.
     */
    private void reloadResources() {
        Resources res = mContext.getResources();

        mTaskBarHeight = TaskStackLayoutAlgorithm.getDimensionForDevice(mContext,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height_tablet_land,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height_tablet_land,
                R.dimen.recents_grid_task_view_header_height);

        LayoutInflater inflater = LayoutInflater.from(mContext);
        mHeaderBar = (TaskViewHeader) inflater.inflate(R.layout.recents_task_view_header,
                null, false);
        mHeaderBar.setLayoutDirection(res.getConfiguration().getLayoutDirection());
    }

    private void updateDummyStackViewLayout(TaskStackLayoutAlgorithm stackLayout,
            TaskStack stack, Rect windowRect) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        Rect displayRect = ssp.getDisplayRect();
        Rect systemInsets = new Rect();
        ssp.getStableInsets(systemInsets);

        // When docked, the nav bar insets are consumed and the activity is measured without insets.
        // However, the window bounds include the insets, so we need to subtract them here to make
        // them identical.
        if (ssp.hasDockedTask()) {
            if (systemInsets.bottom < windowRect.height()) {
                // Only apply inset if it isn't going to cause the rect height to go negative.
                windowRect.bottom -= systemInsets.bottom;
            }
            systemInsets.bottom = 0;
        }
        calculateWindowStableInsets(systemInsets, windowRect, displayRect);
        windowRect.offsetTo(0, 0);

        // Rebind the header bar and draw it for the transition
        stackLayout.setSystemInsets(systemInsets);
        if (stack != null) {
            stackLayout.getTaskStackBounds(displayRect, windowRect, systemInsets.top,
                    systemInsets.left, systemInsets.right, mTmpBounds);
            stackLayout.reset();
            stackLayout.initialize(displayRect, windowRect, mTmpBounds);
        }
    }

    private Rect getWindowRect(Rect windowRectOverride) {
       return windowRectOverride != null
                ? new Rect(windowRectOverride)
                : Recents.getSystemServices().getWindowRect();
    }

    /**
     * Prepares the header bar layout for the next transition, if the task view bounds has changed
     * since the last call, it will attempt to re-measure and layout the header bar to the new size.
     *
     * @param stack the stack to initialize the stack layout with
     * @param windowRectOverride the rectangle to use when calculating the stack state which can
     *                           be different from the current window rect if recents is resizing
     *                           while being launched
     */
    private void updateHeaderBarLayout(TaskStack stack, Rect windowRectOverride) {
        Rect windowRect = getWindowRect(windowRectOverride);
        int taskViewWidth = 0;
        boolean useGridLayout = mDummyStackView.useGridLayout();
        updateDummyStackViewLayout(mDummyStackView.getStackAlgorithm(), stack, windowRect);
        if (stack != null) {
            TaskStackLayoutAlgorithm stackLayout = mDummyStackView.getStackAlgorithm();
            mDummyStackView.getStack().removeAllTasks(false /* notifyStackChanges */);
            mDummyStackView.setTasks(stack, false /* allowNotifyStackChanges */);
            // Get the width of a task view so that we know how wide to draw the header bar.
            if (useGridLayout) {
                TaskGridLayoutAlgorithm gridLayout = mDummyStackView.getGridAlgorithm();
                gridLayout.initialize(windowRect);
                taskViewWidth = (int) gridLayout.getTransform(0 /* taskIndex */,
                        stack.getTaskCount(), new TaskViewTransform(),
                        stackLayout).rect.width();
            } else {
                Rect taskViewBounds = stackLayout.getUntransformedTaskViewBounds();
                if (!taskViewBounds.isEmpty()) {
                    taskViewWidth = taskViewBounds.width();
                }
            }
        }

        if (stack != null && taskViewWidth > 0) {
            synchronized (mHeaderBarLock) {
                if (mHeaderBar.getMeasuredWidth() != taskViewWidth ||
                        mHeaderBar.getMeasuredHeight() != mTaskBarHeight) {
                    if (useGridLayout) {
                        mHeaderBar.setShouldDarkenBackgroundColor(true);
                        mHeaderBar.setNoUserInteractionState();
                    }
                    mHeaderBar.forceLayout();
                    mHeaderBar.measure(
                            MeasureSpec.makeMeasureSpec(taskViewWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(mTaskBarHeight, MeasureSpec.EXACTLY));
                }
                mHeaderBar.layout(0, 0, taskViewWidth, mTaskBarHeight);
            }
        }
    }

    /**
     * Given the stable insets and the rect for our window, calculates the insets that affect our
     * window.
     */
    private void calculateWindowStableInsets(Rect inOutInsets, Rect windowRect, Rect displayRect) {

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
    private void preloadIcon(int runningTaskId) {
        // Ensure that we load the running task's icon
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.runningTaskId = runningTaskId;
        launchOpts.loadThumbnails = false;
        launchOpts.onlyLoadForCache = true;
        Recents.getTaskLoader().loadTasks(sInstanceLoadPlan, launchOpts);
    }

    /**
     * Creates the activity options for a unknown state->recents transition.
     */
    protected ActivityOptions getUnknownTransitionActivityOptions() {
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_unknown_enter,
                R.anim.recents_from_unknown_exit,
                mHandler, null);
    }

    /**
     * Creates the activity options for a home->recents transition.
     */
    protected ActivityOptions getHomeTransitionActivityOptions() {
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_launcher_enter,
                R.anim.recents_from_launcher_exit,
                mHandler, null);
    }

    /**
     * Creates the activity options for an app->recents transition.
     */
    private Pair<ActivityOptions, AppTransitionAnimationSpecsFuture>
            getThumbnailTransitionActivityOptions(ActivityManager.RunningTaskInfo runningTask,
                    Rect windowOverrideRect) {
        final boolean isLowRamDevice = Recents.getConfiguration().isLowRamDevice;

        // Update the destination rect
        Task toTask = new Task();
        TaskViewTransform toTransform = getThumbnailTransitionTransform(mDummyStackView, toTask,
                windowOverrideRect);

        RectF toTaskRect = toTransform.rect;
        AppTransitionAnimationSpecsFuture future = new AppTransitionAnimationSpecsFuture(mHandler) {
            @Override
            public List<AppTransitionAnimationSpecCompat> composeSpecs() {
                Rect rect = new Rect();
                toTaskRect.round(rect);
                Bitmap thumbnail = drawThumbnailTransitionBitmap(toTask, toTransform);
                return Lists.newArrayList(new AppTransitionAnimationSpecCompat(toTask.key.id,
                        thumbnail, rect));
            }
        };

        // For low end ram devices, wait for transition flag is reset when Recents entrance
        // animation is complete instead of when the transition animation starts
        return new Pair<>(RecentsTransition.createAspectScaleAnimation(mContext, mHandler,
                false /* scaleUp */, future, isLowRamDevice ? null : mResetToggleFlagListener),
                future);
    }

    /**
     * Returns the transition rect for the given task id.
     */
    private TaskViewTransform getThumbnailTransitionTransform(TaskStackView stackView,
            Task runningTaskOut, Rect windowOverrideRect) {
        // Find the running task in the TaskStack
        TaskStack stack = stackView.getStack();
        Task launchTask = stack.getLaunchTarget();
        if (launchTask != null) {
            runningTaskOut.copyFrom(launchTask);
        } else {
            // If no task is specified or we can not find the task just use the front most one
            launchTask = stack.getFrontMostTask();
            runningTaskOut.copyFrom(launchTask);
        }

        // Get the transform for the running task
        stackView.updateLayoutAlgorithm(true /* boundScroll */);
        stackView.updateToInitialState();
        stackView.getStackAlgorithm().getStackTransformScreenCoordinates(launchTask,
                stackView.getScroller().getStackScroll(), mTmpTransform, null, windowOverrideRect);
        return mTmpTransform;
    }

    /**
     * Draws the header of a task used for the window animation into a bitmap.
     */
    private Bitmap drawThumbnailTransitionBitmap(Task toTask,
            TaskViewTransform toTransform) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        int width = (int) toTransform.rect.width();
        int height = (int) toTransform.rect.height();
        if (toTransform != null && toTask.key != null && width > 0 && height > 0) {
            synchronized (mHeaderBarLock) {
                boolean disabledInSafeMode = !toTask.isSystemApp && ssp.isInSafeMode();
                mHeaderBar.onTaskViewSizeChanged(width, height);
                if (RecentsDebugFlags.Static.EnableTransitionThumbnailDebugMode) {
                    return RecentsTransition.drawViewIntoHardwareBitmap(width, mTaskBarHeight,
                            null, 1f, 0xFFff0000);
                } else {
                    // Workaround for b/27815919, reset the callback so that we do not trigger an
                    // invalidate on the header bar as a result of updating the icon
                    Drawable icon = mHeaderBar.getIconView().getDrawable();
                    if (icon != null) {
                        icon.setCallback(null);
                    }
                    mHeaderBar.bindToTask(toTask, false /* touchExplorationEnabled */,
                            disabledInSafeMode);
                    mHeaderBar.onTaskDataLoaded();
                    mHeaderBar.setDimAlpha(toTransform.dimAlpha);
                    return RecentsTransition.drawViewIntoHardwareBitmap(width, mTaskBarHeight,
                            mHeaderBar, 1f, 0);
                }
            }
        }
        return null;
    }

    /**
     * Shows the recents activity after dismissing the keyguard if visible
     */
    protected void startRecentsActivityAndDismissKeyguardIfNeeded(
            final ActivityManager.RunningTaskInfo runningTask, final boolean isHomeStackVisible,
            final boolean animate, final int growTarget) {
        // Preload only if device for current user is unlocked
        final StatusBar statusBar = getStatusBar();
        if (statusBar != null && statusBar.isKeyguardShowing()) {
            statusBar.executeRunnableDismissingKeyguard(() -> {
                    // Flush trustmanager before checking device locked per user when preloading
                    mTrustManager.reportKeyguardShowingChanged();
                    mHandler.post(() -> startRecentsActivity(runningTask, isHomeStackVisible,
                            animate, growTarget));
                }, null,  true /* dismissShade */, false /* afterKeyguardGone */,
                true /* deferred */);
        } else {
            startRecentsActivity(runningTask, isHomeStackVisible, animate, growTarget);
        }
    }

    private void startRecentsActivity(ActivityManager.RunningTaskInfo runningTask,
            boolean isHomeStackVisible, boolean animate, int growTarget) {
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();

        int runningTaskId = !mLaunchedWhileDocking && (runningTask != null)
                ? runningTask.id
                : -1;

        // In the case where alt-tab is triggered, we never get a preloadRecents() call, so we
        // should always preload the tasks now. If we are dragging in recents, reload them as
        // the stacks might have changed.
        if (mLaunchedWhileDocking || mTriggeredFromAltTab || sInstanceLoadPlan == null) {
            // Create a new load plan if preloadRecents() was never triggered
            sInstanceLoadPlan = new RecentsTaskLoadPlan(mContext);
        }
        if (mLaunchedWhileDocking || mTriggeredFromAltTab || !sInstanceLoadPlan.hasTasks()) {
            loader.preloadTasks(sInstanceLoadPlan, runningTaskId);
        }

        TaskStack stack = sInstanceLoadPlan.getTaskStack();
        boolean hasRecentTasks = stack.getTaskCount() > 0;
        boolean useThumbnailTransition = (runningTask != null) && !isHomeStackVisible &&
                hasRecentTasks;

        // Update the launch state that we need in updateHeaderBarLayout()
        launchState.launchedFromHome = !useThumbnailTransition && !mLaunchedWhileDocking;
        launchState.launchedFromApp = useThumbnailTransition || mLaunchedWhileDocking;
        launchState.launchedFromPipApp = false;
        launchState.launchedWithNextPipApp =
                stack.isNextLaunchTargetPip(RecentsImpl.getLastPipTime());
        launchState.launchedViaDockGesture = mLaunchedWhileDocking;
        launchState.launchedViaDragGesture = mDraggingInRecents;
        launchState.launchedToTaskId = runningTaskId;
        launchState.launchedWithAltTab = mTriggeredFromAltTab;

        // Disable toggling of recents between starting the activity and it is visible and the app
        // has started its transition into recents.
        setWaitingForTransitionStart(useThumbnailTransition);

        // Preload the icon (this will be a null-op if we have preloaded the icon already in
        // preloadRecents())
        preloadIcon(runningTaskId);

        // Update the header bar if necessary
        Rect windowOverrideRect = getWindowRectOverride(growTarget);
        updateHeaderBarLayout(stack, windowOverrideRect);

        // Prepare the dummy stack for the transition
        TaskStackLayoutAlgorithm.VisibilityReport stackVr =
                mDummyStackView.computeStackVisibilityReport();

        // Update the remaining launch state
        launchState.launchedNumVisibleTasks = stackVr.numVisibleTasks;
        launchState.launchedNumVisibleThumbnails = stackVr.numVisibleThumbnails;

        if (!animate) {
            startRecentsActivity(ActivityOptions.makeCustomAnimation(mContext, -1, -1),
                    null /* future */);
            return;
        }

        Pair<ActivityOptions, AppTransitionAnimationSpecsFuture> pair;
        if (useThumbnailTransition) {
            // Try starting with a thumbnail transition
            pair = getThumbnailTransitionActivityOptions(runningTask, windowOverrideRect);
        } else {
            // If there is no thumbnail transition, but is launching from home into recents, then
            // use a quick home transition
            pair = new Pair<>(hasRecentTasks
                    ? getHomeTransitionActivityOptions()
                    : getUnknownTransitionActivityOptions(), null);
        }
        startRecentsActivity(pair.first, pair.second);
        mLastToggleTime = SystemClock.elapsedRealtime();
    }

    private Rect getWindowRectOverride(int growTarget) {
        if (growTarget == DividerView.INVALID_RECENTS_GROW_TARGET) {
            return SystemServicesProxy.getInstance(mContext).getWindowRect();
        }
        Rect result = new Rect();
        Rect displayRect = Recents.getSystemServices().getDisplayRect();
        DockedDividerUtils.calculateBoundsForPosition(growTarget, WindowManager.DOCKED_BOTTOM,
                result, displayRect.width(), displayRect.height(),
                Recents.getSystemServices().getDockedDividerSize(mContext));
        return result;
    }

    private StatusBar getStatusBar() {
        return ((SystemUIApplication) mContext).getComponent(StatusBar.class);
    }

    /**
     * Starts the recents activity.
     */
    private void startRecentsActivity(ActivityOptions opts,
            final AppTransitionAnimationSpecsFuture future) {
        Intent intent = new Intent();
        intent.setClassName(RECENTS_PACKAGE, RECENTS_ACTIVITY);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        HidePipMenuEvent hideMenuEvent = new HidePipMenuEvent();
        hideMenuEvent.addPostAnimationCallback(() -> {
            Recents.getSystemServices().startActivityAsUserAsync(intent, opts);
            EventBus.getDefault().send(new RecentsActivityStartingEvent());
            if (future != null) {
                future.composeSpecsSynchronous();
            }
        });
        EventBus.getDefault().send(hideMenuEvent);

        // Once we have launched the activity, reset the dummy stack view tasks so we don't hold
        // onto references to the same tasks consumed by the activity
        mDummyStackView.setTasks(mEmptyTaskStack, false /* notifyStackChanges */);
    }

    /**** OnAnimationFinishedListener Implementation ****/

    @Override
    public void onAnimationFinished() {
        EventBus.getDefault().post(new EnterRecentsWindowLastAnimationFrameEvent());
    }
}
