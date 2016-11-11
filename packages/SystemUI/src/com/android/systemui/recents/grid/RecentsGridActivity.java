/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.grid;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsImpl;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.ConfigurationChangedEvent;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchNextTaskRequestEvent;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DeleteTaskDataEvent;
import com.android.systemui.recents.events.ui.DismissAllTaskViewsEvent;
import com.android.systemui.recents.events.ui.DismissTaskViewEvent;
import com.android.systemui.recents.events.ui.TaskViewDismissedEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.TaskView;

import java.util.ArrayList;
import java.util.List;

/**
 * The main grid recents activity started by the RecentsImpl.
 */
public class RecentsGridActivity extends Activity implements ViewTreeObserver.OnPreDrawListener {
    private final static String TAG = "RecentsGridActivity";

    private TaskStack mTaskStack;
    private List<Task> mTasks = new ArrayList<>();
    private List<TaskView> mTaskViews = new ArrayList<>();
    private FrameLayout mRecentsView;
    private TextView mEmptyView;
    private View mClearAllButton;
    private int mLastDisplayOrientation = Configuration.ORIENTATION_UNDEFINED;
    private int mLastDisplayDensity;
    private Rect mDisplayRect = new Rect();
    private LayoutInflater mInflater;
    private boolean mTouchExplorationEnabled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recents_grid);
        SystemServicesProxy ssp = Recents.getSystemServices();

        mInflater = LayoutInflater.from(this);
        Configuration appConfiguration = Utilities.getAppConfiguration(this);
        mDisplayRect = ssp.getDisplayRect();
        mLastDisplayOrientation = appConfiguration.orientation;
        mLastDisplayDensity = appConfiguration.densityDpi;
        mTouchExplorationEnabled = ssp.isTouchExplorationEnabled();

        mRecentsView = (FrameLayout) findViewById(R.id.recents_view);
        LinearLayout recentsContainer = (LinearLayout) findViewById(R.id.recents_container);
        mEmptyView = (TextView) mInflater.inflate(R.layout.recents_empty, recentsContainer, false);
        mClearAllButton = findViewById(R.id.button);

        FrameLayout.LayoutParams emptyViewLayoutParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        emptyViewLayoutParams.gravity = Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL;
        mEmptyView.setLayoutParams(emptyViewLayoutParams);
        mRecentsView.addView(mEmptyView);

        mClearAllButton.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) mClearAllButton.getLayoutParams();
        lp.gravity = Gravity.END;

        mClearAllButton.setOnClickListener(v -> {
            EventBus.getDefault().send(new DismissAllTaskViewsEvent());
        });

        mRecentsView.setOnClickListener(v -> {
            EventBus.getDefault().send(new HideRecentsEvent(
                    false /* triggeredFromAltTab */, false /* triggeredFromHomeKey */));
        });

        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY);
    }

    private TaskView createView() {
        return (TaskView) mInflater.inflate(R.layout.recents_task_view, mRecentsView, false);
    }

    private void removeTaskViews() {
        for (View taskView : mTaskViews) {
            ViewGroup parent = (ViewGroup) taskView.getParent();
            if (parent != null) {
                parent.removeView(taskView);
            }
        }
    }

    private void clearTaskViews() {
        removeTaskViews();
        mTaskViews.clear();
    }

    private TaskView getChildViewForTask(Task task) {
        for (TaskView tv : mTaskViews) {
            if (tv.getTask() == task) {
                return tv;
            }
        }
        return null;
    }

    private void updateControlVisibility() {
        boolean empty = (mTasks.size() == 0);
        mClearAllButton.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        mEmptyView.setVisibility(empty ? View.VISIBLE : View.INVISIBLE);
        if (empty) {
            mEmptyView.bringToFront();
        }
    }

    private void updateRecentsTasks() {
        RecentsTaskLoader loader = Recents.getTaskLoader();
        RecentsTaskLoadPlan plan = RecentsImpl.consumeInstanceLoadPlan();
        if (plan == null) {
            plan = loader.createLoadPlan(this);
        }
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        if (!plan.hasTasks()) {
            loader.preloadTasks(plan, -1, !launchState.launchedFromHome);
        }
        int numVisibleTasks = 9;
        mTaskStack = plan.getTaskStack();
        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = launchState.launchedToTaskId;
        loadOpts.numVisibleTasks = numVisibleTasks;
        loadOpts.numVisibleTaskThumbnails = numVisibleTasks;
        loader.loadTasks(this, plan, loadOpts);

        mTasks = mTaskStack.getStackTasks();

        updateControlVisibility();

        clearTaskViews();
        for (int i = 0; i < mTasks.size(); i++) {
            Task task = mTasks.get(i);
            TaskView taskView = createView();
            taskView.onTaskBound(task, mTouchExplorationEnabled, mLastDisplayOrientation,
                    mDisplayRect);
            Recents.getTaskLoader().loadTaskData(task);
            taskView.setTouchEnabled(true);
            // Show dismiss button right away.
            taskView.startNoUserInteractionAnimation();
            mTaskViews.add(taskView);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, true));
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRecentsTasks();
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, false));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onBackPressed() {
        // Back behaves like the recents button so just trigger a toggle event.
        EventBus.getDefault().send(new ToggleRecentsEvent());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Notify of the config change.
        Configuration newDeviceConfiguration = Utilities.getAppConfiguration(this);
        mDisplayRect = Recents.getSystemServices().getDisplayRect();
        mRecentsView.getViewTreeObserver().addOnPreDrawListener(this);
        mRecentsView.requestLayout();
        int numStackTasks = mTaskStack.getStackTaskCount();
        EventBus.getDefault().send(new ConfigurationChangedEvent(false /* fromMultiWindow */,
                mLastDisplayOrientation != newDeviceConfiguration.orientation,
                mLastDisplayDensity != newDeviceConfiguration.densityDpi, numStackTasks > 0));
        mLastDisplayOrientation = newDeviceConfiguration.orientation;
        mLastDisplayDensity = newDeviceConfiguration.densityDpi;
    }

    @Override
    public boolean onPreDraw() {
        mRecentsView.getViewTreeObserver().removeOnPreDrawListener(this);
        int width = mRecentsView.getWidth();
        int height = mRecentsView.getHeight();

        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
            mTasks.size(), width, height, false /* allowLineOfThree */, 30 /* padding */);
        removeTaskViews();
        for (int i = 0; i < rects.size(); i++) {
            Rect rect = rects.get(i);
            // We keep the same ordering in the model as other Recents flavors (older tasks are
            // first in the stack) so that the logic can be similar, but we reverse the order
            // when placing views on the screen so that most recent tasks are displayed first.
            View taskView = mTaskViews.get(rects.size() - 1 - i);
            taskView.setLayoutParams(new FrameLayout.LayoutParams(rect.width(), rect.height()));
            taskView.setTranslationX(rect.left);
            taskView.setTranslationY(rect.top);
            mRecentsView.addView(taskView);
        }
        return true;
    }

    void dismissRecentsToHome() {
        Intent startMain = new Intent(Intent.ACTION_MAIN);
        startMain.addCategory(Intent.CATEGORY_HOME);
        startActivity(startMain);
    }

    /** Launches the task that recents was launched from if possible. */
    boolean launchPreviousTask() {
        if (mRecentsView != null) {
            Task task = mTaskStack.getLaunchTarget();
            if (task != null) {
                TaskView taskView = getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null,
                        INVALID_STACK_ID, false));
                return true;
            }
        }
        return false;
    }

    /** Dismisses recents back to the launch target task. */
    boolean dismissRecentsToLaunchTargetTaskOrHome() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isRecentsActivityVisible()) {
            // If we can launch the task that Recents was launched from, do that, otherwise go home.
            if (launchPreviousTask()) return true;
            dismissRecentsToHome();
        }
        return false;
    }

    /**** EventBus events ****/

    public final void onBusEvent(HideRecentsEvent event) {
        if (event.triggeredFromAltTab) {
            dismissRecentsToLaunchTargetTaskOrHome();
        } else if (event.triggeredFromHomeKey) {
            dismissRecentsToHome();
        } else {
            // Fall through tap on the background view but not on any of the tasks.
            dismissRecentsToHome();
        }
    }

    public final void onBusEvent(ToggleRecentsEvent event) {
        dismissRecentsToLaunchTargetTaskOrHome();
    }

    public final void onBusEvent(DismissTaskViewEvent event) {
        int taskIndex = mTaskViews.indexOf(event.taskView);
        if (taskIndex != -1) {
            mTasks.remove(taskIndex);
            ((ViewGroup) event.taskView.getParent()).removeView(event.taskView);
            mTaskViews.remove(taskIndex);
            EventBus.getDefault().send(
                    new TaskViewDismissedEvent(event.taskView.getTask(), event.taskView, null));
        }
    }

    public final void onBusEvent(TaskViewDismissedEvent event) {
        mRecentsView.announceForAccessibility(this.getString(
            R.string.accessibility_recents_item_dismissed, event.task.title));
        updateControlVisibility();

        EventBus.getDefault().send(new DeleteTaskDataEvent(event.task));

        MetricsLogger.action(this, MetricsEvent.OVERVIEW_DISMISS,
                event.task.key.getComponent().toString());
    }

    public final void onBusEvent(DeleteTaskDataEvent event) {
        // Remove any stored data from the loader.
        RecentsTaskLoader loader = Recents.getTaskLoader();
        loader.deleteTaskData(event.task, false);

        // Remove the task from activity manager.
        SystemServicesProxy ssp = Recents.getSystemServices();
        ssp.removeTask(event.task.key.id);
    }

    public final void onBusEvent(final DismissAllTaskViewsEvent event) {
        // Keep track of the tasks which will have their data removed.
        ArrayList<Task> tasks = new ArrayList<>(mTaskStack.getStackTasks());
        mRecentsView.announceForAccessibility(this.getString(
                R.string.accessibility_recents_all_items_dismissed));
        mTaskStack.removeAllTasks();
        for (int i = tasks.size() - 1; i >= 0; i--) {
            EventBus.getDefault().send(new DeleteTaskDataEvent(tasks.get(i)));
        }
        mTasks = new ArrayList<>();
        updateRecentsTasks();

        MetricsLogger.action(this, MetricsEvent.OVERVIEW_DISMISS_ALL);
    }

    public final void onBusEvent(AllTaskViewsDismissedEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (!ssp.hasDockedTask()) {
            dismissRecentsToHome();
        }
    }

    public final void onBusEvent(LaunchNextTaskRequestEvent event) {
        if (mTaskStack.getTaskCount() > 0) {
            Task launchTask = mTaskStack.getNextLaunchTarget();
            TaskView launchTaskView = getChildViewForTask(launchTask);
            if (launchTaskView != null) {
                EventBus.getDefault().send(new LaunchTaskEvent(launchTaskView,
                        launchTask, null, INVALID_STACK_ID, false /* screenPinningRequested */));
                MetricsLogger.action(this, MetricsEvent.OVERVIEW_LAUNCH_PREVIOUS_TASK,
                        launchTask.key.getComponent().toString());
                return;
            }
        }
        // We couldn't find a matching task view, or there are no tasks. Just hide recents back
        // to home.
        EventBus.getDefault().send(new HideRecentsEvent(false, true));
    }

    public final void onBusEvent(LaunchTaskEvent event) {
        startActivity(event.task.key.baseIntent);
    }
}
