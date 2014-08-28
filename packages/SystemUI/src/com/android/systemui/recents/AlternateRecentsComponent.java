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
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskGrouping;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.recents.views.TaskStackViewLayoutAlgorithm;
import com.android.systemui.recents.views.TaskViewHeader;
import com.android.systemui.recents.views.TaskViewTransform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A proxy implementation for the recents component */
public class AlternateRecentsComponent implements ActivityOptions.OnAnimationStartedListener {

    final public static String EXTRA_FROM_HOME = "recents.triggeredOverHome";
    final public static String EXTRA_FROM_APP_THUMBNAIL = "recents.animatingWithThumbnail";
    final public static String EXTRA_FROM_APP_FULL_SCREENSHOT = "recents.thumbnail";
    final public static String EXTRA_FROM_TASK_ID = "recents.activeTaskId";
    final public static String EXTRA_TRIGGERED_FROM_ALT_TAB = "recents.triggeredFromAltTab";
    final public static String EXTRA_TRIGGERED_FROM_HOME_KEY = "recents.triggeredFromHomeKey";

    final public static String ACTION_START_ENTER_ANIMATION = "action_start_enter_animation";
    final public static String ACTION_TOGGLE_RECENTS_ACTIVITY = "action_toggle_recents_activity";
    final public static String ACTION_HIDE_RECENTS_ACTIVITY = "action_hide_recents_activity";

    final static int sMinToggleDelay = 425;

    final static String sToggleRecentsAction = "com.android.systemui.recents.SHOW_RECENTS";
    final static String sRecentsPackage = "com.android.systemui";
    final static String sRecentsActivity = "com.android.systemui.recents.RecentsActivity";

    static Bitmap sLastScreenshot;
    static RecentsComponent.Callbacks sRecentsComponentCallbacks;

    Context mContext;
    LayoutInflater mInflater;
    SystemServicesProxy mSystemServicesProxy;
    Handler mHandler;
    boolean mBootCompleted;
    boolean mStartAnimationTriggered;

    // Task launching
    RecentsConfiguration mConfig;
    Rect mWindowRect = new Rect();
    Rect mTaskStackBounds = new Rect();
    Rect mSystemInsets = new Rect();
    TaskViewTransform mTmpTransform = new TaskViewTransform();
    int mStatusBarHeight;
    int mNavBarHeight;
    int mNavBarWidth;

    // Header (for transition)
    TaskViewHeader mHeaderBar;
    TaskStackView mDummyStackView;

    // Variables to keep track of if we need to start recents after binding
    View mStatusBarView;
    boolean mTriggeredFromAltTab;
    long mLastToggleTime;

    public AlternateRecentsComponent(Context context) {
        RecentsTaskLoader.initialize(context);
        mInflater = LayoutInflater.from(context);
        mContext = context;
        mSystemServicesProxy = new SystemServicesProxy(context);
        mHandler = new Handler();
        mTaskStackBounds = new Rect();
    }

    public void onStart() {}

    public void onBootCompleted() {
        // Initialize some static datastructures
        TaskStackViewLayoutAlgorithm.initializeCurve();
        // Load the header bar layout
        reloadHeaderBarLayout();
        mBootCompleted = true;
    }

    /** Shows the recents */
    public void onShowRecents(boolean triggeredFromAltTab, View statusBarView) {
        mStatusBarView = statusBarView;
        mTriggeredFromAltTab = triggeredFromAltTab;

        try {
            startRecentsActivity();
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    /** Hides the recents */
    public void onHideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (mBootCompleted) {
            if (isRecentsTopMost(getTopMostTask(), null)) {
                // Notify recents to hide itself
                Intent intent = new Intent(ACTION_HIDE_RECENTS_ACTIVITY);
                intent.setPackage(mContext.getPackageName());
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(EXTRA_TRIGGERED_FROM_ALT_TAB, triggeredFromAltTab);
                intent.putExtra(EXTRA_TRIGGERED_FROM_HOME_KEY, triggeredFromHomeKey);
                mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            }
        }
    }

    /** Toggles the alternate recents activity */
    public void onToggleRecents(View statusBarView) {
        mStatusBarView = statusBarView;
        mTriggeredFromAltTab = false;

        try {
            toggleRecentsActivity();
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    public void onPreloadRecents() {
        // Do nothing
    }

    public void onCancelPreloadingRecents() {
        // Do nothing
    }

    void showRelativeAffiliatedTask(boolean showNextTask) {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        TaskStack stack = loader.getTaskStack(mSystemServicesProxy, mContext.getResources(),
                -1, -1, false, null, null);
        // Return early if there are no tasks
        if (stack.getTaskCount() == 0) return;

        ActivityManager.RunningTaskInfo runningTask = getTopMostTask();
        // Return early if the running task is in the home stack (optimization)
        if (mSystemServicesProxy.isInHomeStack(runningTask.id)) return;

        // Find the task in the recents list
        ArrayList<Task> tasks = stack.getTasks();
        Task toTask = null;
        ActivityOptions launchOpts = null;
        int taskCount = tasks.size();
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks.get(i);
            if (task.key.id == runningTask.id) {
                TaskGrouping group = task.group;
                Task.TaskKey toTaskKey;
                if (showNextTask) {
                    toTaskKey = group.getNextTaskInGroup(task);
                    // XXX: We will actually set the appropriate launch animations here
                } else {
                    toTaskKey = group.getPrevTaskInGroup(task);
                    // XXX: We will actually set the appropriate launch animations here
                }
                if (toTaskKey != null) {
                    toTask = stack.findTaskWithId(toTaskKey.id);
                }
                break;
            }
        }

        // Return early if there is no next task
        if (toTask == null) {
            // XXX: We will actually show a bounce animation here
            return;
        }

        // Launch the task
        if (toTask.isActive) {
            // Bring an active task to the foreground
            mSystemServicesProxy.moveTaskToFront(toTask.key.id, launchOpts);
        } else {
            try {
                mSystemServicesProxy.startActivityFromRecents(toTask.key.id, launchOpts);
            } catch (ActivityNotFoundException anfe) {}
        }
    }

    public void onShowNextAffiliatedTask() {
        showRelativeAffiliatedTask(true);
    }

    public void onShowPrevAffiliatedTask() {
        showRelativeAffiliatedTask(false);
    }

    public void onConfigurationChanged(Configuration newConfig) {
        reloadHeaderBarLayout();
        sLastScreenshot = null;
    }

    /** Prepares the header bar layout. */
    void reloadHeaderBarLayout() {
        Resources res = mContext.getResources();
        mWindowRect = mSystemServicesProxy.getWindowRect();
        mStatusBarHeight = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mNavBarHeight = res.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_height);
        mNavBarWidth = res.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_width);
        mConfig = RecentsConfiguration.reinitialize(mContext, mSystemServicesProxy);
        mConfig.updateOnConfigurationChange();
        mConfig.getTaskStackBounds(mWindowRect.width(), mWindowRect.height(), mStatusBarHeight,
                mNavBarWidth, mTaskStackBounds);
        if (mConfig.isLandscape && mConfig.transposeRecentsLayoutWithOrientation) {
            mSystemInsets.set(0, mStatusBarHeight, mNavBarWidth, 0);
        } else {
            mSystemInsets.set(0, mStatusBarHeight, 0, mNavBarHeight);
        }

        // Inflate the header bar layout so that we can rebind and draw it for the transition
        TaskStack stack = new TaskStack();
        mDummyStackView = new TaskStackView(mContext, stack);
        TaskStackViewLayoutAlgorithm algo = mDummyStackView.getStackAlgorithm();
        Rect taskStackBounds = new Rect(mTaskStackBounds);
        taskStackBounds.bottom -= mSystemInsets.bottom;
        algo.computeRects(mWindowRect.width(), mWindowRect.height(), taskStackBounds);
        Rect taskViewSize = algo.getUntransformedTaskViewSize();
        int taskBarHeight = res.getDimensionPixelSize(R.dimen.recents_task_bar_height);
        mHeaderBar = (TaskViewHeader) mInflater.inflate(R.layout.recents_task_view_header, null,
                false);
        mHeaderBar.measure(
                View.MeasureSpec.makeMeasureSpec(taskViewSize.width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(taskBarHeight, View.MeasureSpec.EXACTLY));
        mHeaderBar.layout(0, 0, taskViewSize.width(), taskBarHeight);
    }

    /** Gets the top task. */
    ActivityManager.RunningTaskInfo getTopMostTask() {
        SystemServicesProxy ssp = mSystemServicesProxy;
        List<ActivityManager.RunningTaskInfo> tasks = ssp.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            return tasks.get(0);
        }
        return null;
    }

    /** Returns whether the recents is currently running */
    boolean isRecentsTopMost(ActivityManager.RunningTaskInfo topTask, AtomicBoolean isHomeTopMost) {
        SystemServicesProxy ssp = mSystemServicesProxy;
        if (topTask != null) {
            ComponentName topActivity = topTask.topActivity;

            // Check if the front most activity is recents
            if (topActivity.getPackageName().equals(sRecentsPackage) &&
                    topActivity.getClassName().equals(sRecentsActivity)) {
                if (isHomeTopMost != null) {
                    isHomeTopMost.set(false);
                }
                return true;
            }

            if (isHomeTopMost != null) {
                isHomeTopMost.set(ssp.isInHomeStack(topTask.id));
            }
        }
        return false;
    }

    /** Toggles the recents activity */
    void toggleRecentsActivity() {
        // If the user has toggled it too quickly, then just eat up the event here (it's better than
        // showing a janky screenshot).
        // NOTE: Ideally, the screenshot mechanism would take the window transform into account
        if (System.currentTimeMillis() - mLastToggleTime < sMinToggleDelay) {
            return;
        }

        // If Recents is the front most activity, then we should just communicate with it directly
        // to launch the first task or dismiss itself
        ActivityManager.RunningTaskInfo topTask = getTopMostTask();
        AtomicBoolean isTopTaskHome = new AtomicBoolean();
        if (isRecentsTopMost(topTask, isTopTaskHome)) {
            // Notify recents to toggle itself
            Intent intent = new Intent(ACTION_TOGGLE_RECENTS_ACTIVITY);
            intent.setPackage(mContext.getPackageName());
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            mLastToggleTime = System.currentTimeMillis();
            return;
        } else {
            // Otherwise, start the recents activity
            startRecentsActivity(topTask, isTopTaskHome.get());
        }
    }

    /** Starts the recents activity if it is not already running */
    void startRecentsActivity() {
        // Check if the top task is in the home stack, and start the recents activity
        ActivityManager.RunningTaskInfo topTask = getTopMostTask();
        AtomicBoolean isTopTaskHome = new AtomicBoolean();
        if (!isRecentsTopMost(topTask, isTopTaskHome)) {
            startRecentsActivity(topTask, isTopTaskHome.get());
        }
    }

    /**
     * Creates the activity options for a unknown state->recents transition.
     */
    ActivityOptions getUnknownTransitionActivityOptions() {
        mStartAnimationTriggered = false;
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_unknown_enter,
                R.anim.recents_from_unknown_exit, mHandler, this);
    }

    /**
     * Creates the activity options for a home->recents transition.
     */
    ActivityOptions getHomeTransitionActivityOptions() {
        mStartAnimationTriggered = false;
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_launcher_enter,
                R.anim.recents_from_launcher_exit, mHandler, this);
    }

    /**
     * Creates the activity options for an app->recents transition.  If this method sets the static
     * screenshot, then we will use that for the transition.
     */
    ActivityOptions getThumbnailTransitionActivityOptions(ActivityManager.RunningTaskInfo topTask,
            boolean isTopTaskHome) {
        if (Constants.DebugFlags.App.EnableScreenshotAppTransition) {
            // Recycle the last screenshot
            consumeLastScreenshot();

            // Take the full screenshot
            sLastScreenshot = mSystemServicesProxy.takeAppScreenshot();
            if (sLastScreenshot != null) {
                mStartAnimationTriggered = false;
                return ActivityOptions.makeCustomAnimation(mContext,
                        R.anim.recents_from_app_enter,
                        R.anim.recents_from_app_exit, mHandler, this);
            }
        }

        // Update the destination rect
        Task toTask = new Task();
        TaskViewTransform toTransform = getThumbnailTransitionTransform(topTask.id, isTopTaskHome,
                toTask);
        if (toTransform != null && toTask.key != null) {
            Rect toTaskRect = toTransform.rect;

            // XXX: Reduce the memory usage the to the task bar height
            Bitmap thumbnail = Bitmap.createBitmap(toTaskRect.width(), toTaskRect.height(),
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

            mStartAnimationTriggered = false;
            return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mStatusBarView,
                    thumbnail, toTaskRect.left, toTaskRect.top, this);
        }

        // If both the screenshot and thumbnail fails, then just fall back to the default transition
        return getUnknownTransitionActivityOptions();
    }

    /** Returns the transition rect for the given task id. */
    TaskViewTransform getThumbnailTransitionTransform(int runningTaskId, boolean isTopTaskHome,
                                                      Task runningTaskOut) {
        // Get the stack of tasks that we are animating into
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        TaskStack stack = loader.getTaskStack(mSystemServicesProxy, mContext.getResources(),
                runningTaskId, -1, false, null, null);
        if (stack.getTaskCount() == 0) {
            return null;
        }

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
        }

        // Get the transform for the running task
        mDummyStackView.updateMinMaxScrollForStack(stack, mTriggeredFromAltTab, isTopTaskHome);
        mDummyStackView.getScroller().setStackScrollToInitialState();
        mTmpTransform = mDummyStackView.getStackAlgorithm().getStackTransform(task,
                mDummyStackView.getScroller().getStackScroll(), mTmpTransform, null);
        return mTmpTransform;
    }

    /** Starts the recents activity */
    void startRecentsActivity(ActivityManager.RunningTaskInfo topTask, boolean isTopTaskHome) {
        // If Recents is not the front-most activity and we should animate into it.  If
        // the activity at the root of the top task stack in the home stack, then we just do a
        // simple transition.  Otherwise, we animate to the rects defined by the Recents service,
        // which can differ depending on the number of items in the list.
        SystemServicesProxy ssp = mSystemServicesProxy;
        List<ActivityManager.RecentTaskInfo> recentTasks =
                ssp.getRecentTasks(3, UserHandle.CURRENT.getIdentifier());
        boolean useThumbnailTransition = !isTopTaskHome;
        boolean hasRecentTasks = !recentTasks.isEmpty();

        if (useThumbnailTransition) {
            // Try starting with a thumbnail transition
            ActivityOptions opts = getThumbnailTransitionActivityOptions(topTask, isTopTaskHome);
            if (opts != null) {
                if (sLastScreenshot != null) {
                    startAlternateRecentsActivity(topTask, opts, EXTRA_FROM_APP_FULL_SCREENSHOT);
                } else {
                    startAlternateRecentsActivity(topTask, opts, EXTRA_FROM_APP_THUMBNAIL);
                }
            } else {
                // Fall through below to the non-thumbnail transition
                useThumbnailTransition = false;
            }
        }

        if (!useThumbnailTransition) {
            // If there is no thumbnail transition, but is launching from home into recents, then
            // use a quick home transition and do the animation from home
            if (hasRecentTasks) {
                ActivityOptions opts = getHomeTransitionActivityOptions();
                startAlternateRecentsActivity(topTask, opts, EXTRA_FROM_HOME);
            } else {
                // Otherwise we do the normal fade from an unknown source
                ActivityOptions opts = getUnknownTransitionActivityOptions();
                startAlternateRecentsActivity(topTask, opts, null);
            }
        }
        mLastToggleTime = System.currentTimeMillis();
    }

    /** Starts the recents activity */
    void startAlternateRecentsActivity(ActivityManager.RunningTaskInfo topTask,
                                       ActivityOptions opts, String extraFlag) {
        Intent intent = new Intent(sToggleRecentsAction);
        intent.setClassName(sRecentsPackage, sRecentsActivity);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        if (extraFlag != null) {
            intent.putExtra(extraFlag, true);
        }
        intent.putExtra(EXTRA_TRIGGERED_FROM_ALT_TAB, mTriggeredFromAltTab);
        intent.putExtra(EXTRA_FROM_TASK_ID, (topTask != null) ? topTask.id : -1);
        if (opts != null) {
            mContext.startActivityAsUser(intent, opts.toBundle(), UserHandle.CURRENT);
        } else {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        }
    }

    /** Returns the last screenshot taken, this will be called by the RecentsActivity. */
    public static Bitmap getLastScreenshot() {
        return sLastScreenshot;
    }

    /** Recycles the last screenshot taken, this will be called by the RecentsActivity. */
    public static void consumeLastScreenshot() {
        if (sLastScreenshot != null) {
            sLastScreenshot.recycle();
            sLastScreenshot = null;
        }
    }

    /** Sets the RecentsComponent callbacks. */
    public void setRecentsComponentCallback(RecentsComponent.Callbacks cb) {
        sRecentsComponentCallbacks = cb;
    }

    /** Notifies the callbacks that the visibility of Recents has changed. */
    public static void notifyVisibilityChanged(boolean visible) {
        if (sRecentsComponentCallbacks != null) {
            sRecentsComponentCallbacks.onVisibilityChanged(visible);
        }
    }

    /**** OnAnimationStartedListener Implementation ****/

    @Override
    public void onAnimationStarted() {
        // Notify recents to start the enter animation
        if (!mStartAnimationTriggered) {
            Intent intent = new Intent(ACTION_START_ENTER_ANIMATION);
            intent.setPackage(mContext.getPackageName());
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            mStartAnimationTriggered = true;
        }
    }
}
