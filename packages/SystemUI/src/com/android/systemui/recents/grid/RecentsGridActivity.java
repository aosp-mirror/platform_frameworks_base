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

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
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
import java.util.Collections;
import java.util.List;

/**
 * The main grid recents activity started by the RecentsImpl.
 */
public class RecentsGridActivity extends Activity implements ViewTreeObserver.OnPreDrawListener {
    private final static String TAG = "RecentsGridActivity";

    private TaskStack mTaskStack;
    private List<Task> mTasks = new ArrayList<>();
    private List<View> mTaskViews = new ArrayList<>();
    private FrameLayout mRecentsView;
    private TextView mEmptyView;
    private View mClearAllButton;
    private int mDisplayOrientation = Configuration.ORIENTATION_UNDEFINED;
    private Rect mDisplayRect = new Rect();
    private LayoutInflater mInflater;
    private boolean mTouchExplorationEnabled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recents_grid);
        SystemServicesProxy ssp = Recents.getSystemServices();

        mInflater = LayoutInflater.from(this);
        mDisplayOrientation = Utilities.getAppConfiguration(this).orientation;
        mDisplayRect = ssp.getDisplayRect();
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

    private void clearTaskViews() {
        for (View taskView : mTaskViews) {
            ViewGroup parent = (ViewGroup) taskView.getParent();
            if (parent != null) {
                parent.removeView(taskView);
            }
        }
        mTaskViews.clear();
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

        List<Task> stackTasks = mTaskStack.getStackTasks();
        Collections.reverse(stackTasks);
        mTasks = stackTasks;

        updateControlVisibility();

        clearTaskViews();
        for (int i = 0; i < mTasks.size(); i++) {
            Task task = mTasks.get(i);
            TaskView taskView = createView();
            taskView.onTaskBound(task, mTouchExplorationEnabled, mDisplayOrientation, mDisplayRect);
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
    public boolean onPreDraw() {
        mRecentsView.getViewTreeObserver().removeOnPreDrawListener(this);
        int width = mRecentsView.getWidth();
        int height = mRecentsView.getHeight();

        List<Rect> rects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
            mTasks.size(), width, height, false /* allowLineOfThree */, 30 /* padding */);
        for (int i = 0; i < rects.size(); i++) {
            Rect rect = rects.get(i);
            View taskView = mTaskViews.get(i);
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

    /**** EventBus events ****/

    public final void onBusEvent(HideRecentsEvent event) {
        if (event.triggeredFromAltTab) {
            // Do nothing for now.
        } else if (event.triggeredFromHomeKey) {
            dismissRecentsToHome();
        }
    }

    public final void onBusEvent(ToggleRecentsEvent event) {
        // Always go back home for simplicity for now. If recents is entered from another app, this
        // code will eventually need to go back to the original app.
        dismissRecentsToHome();
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
        // Always go back home for simplicity for now. Quick switch will be supported soon.
        EventBus.getDefault().send(new HideRecentsEvent(false, true));
    }

    public final void onBusEvent(LaunchTaskEvent event) {
        startActivity(event.task.key.baseIntent);
    }
}

