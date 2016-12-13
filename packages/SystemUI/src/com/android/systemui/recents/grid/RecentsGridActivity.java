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
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
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
import java.util.Arrays;

/**
 * The main grid recents activity started by the RecentsImpl.
 */
public class RecentsGridActivity extends Activity {
    public final static int MAX_VISIBLE_TASKS = 9;

    private final static String TAG = "RecentsGridActivity";

    private ArrayList<Integer> mMargins = new ArrayList<>();

    private TaskStack mTaskStack;
    private ArrayList<Task> mTasks = new ArrayList<>();
    private ArrayList<TaskView> mTaskViews = new ArrayList<>();
    private ArrayList<Rect> mTaskViewRects;
    private FrameLayout mRecentsView;
    private TextView mEmptyView;
    private View mClearAllButton;
    private int mLastDisplayOrientation = Configuration.ORIENTATION_UNDEFINED;
    private int mLastDisplayDensity;
    private Rect mDisplayRect = new Rect();
    private LayoutInflater mInflater;
    private boolean mTouchExplorationEnabled;
    private Point mScreenSize;
    private int mTitleBarHeightPx;
    private int mStatusBarHeightPx;
    private int mNavigationBarHeightPx;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recents_grid);
        SystemServicesProxy ssp = Recents.getSystemServices();

        Resources res = getResources();
        Integer[] margins = {
                res.getDimensionPixelSize(R.dimen.recents_grid_margin_left),
                res.getDimensionPixelSize(R.dimen.recents_grid_margin_top),
                res.getDimensionPixelSize(R.dimen.recents_grid_margin_right),
                res.getDimensionPixelSize(R.dimen.recents_grid_margin_bottom),
        };
        mMargins.addAll(Arrays.asList(margins));

        mInflater = LayoutInflater.from(this);
        Configuration appConfiguration = Utilities.getAppConfiguration(this);
        mDisplayRect = ssp.getDisplayRect();
        mLastDisplayOrientation = appConfiguration.orientation;
        mLastDisplayDensity = appConfiguration.densityDpi;
        mTouchExplorationEnabled = ssp.isTouchExplorationEnabled();
        mScreenSize = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(mScreenSize);
        mStatusBarHeightPx = res.getDimensionPixelSize(R.dimen.status_bar_height);
        mNavigationBarHeightPx = res.getDimensionPixelSize(R.dimen.navigation_bar_height);
        mTitleBarHeightPx = getResources().getDimensionPixelSize(
                R.dimen.recents_grid_task_header_height);

        mRecentsView = (FrameLayout) findViewById(R.id.recents_view);
        mRecentsView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY;
        mEmptyView = (TextView) mInflater.inflate(R.layout.recents_empty, mRecentsView, false);
        mClearAllButton = findViewById(R.id.button);

        FrameLayout.LayoutParams emptyViewLayoutParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        emptyViewLayoutParams.gravity = Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL;
        mEmptyView.setLayoutParams(emptyViewLayoutParams);
        mRecentsView.addView(mEmptyView);

        mClearAllButton.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mClearAllButton.getLayoutParams();
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

    private void customizeTaskView(TaskView taskView) {
        Resources res = getResources();
        taskView.setOverlayHeaderOnThumbnailActionBar(false);
        View thumbnail = taskView.findViewById(R.id.task_view_thumbnail);
        thumbnail.setTranslationY(mTitleBarHeightPx);

        // These need to be adjusted in code to override behavior in TaskViewHeader (not defined
        // in layout XML code).
        View title = taskView.findViewById(R.id.title);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) title.getLayoutParams();
        lp.setMarginStart(res.getDimensionPixelSize(R.dimen.recents_grid_task_title_margin_start));
        title.setLayoutParams(lp);

        View dismiss = taskView.findViewById(R.id.dismiss_task);
        int padding = res.getDimensionPixelSize(R.dimen.recents_grid_task_dismiss_button_padding);
        dismiss.setPadding(padding, padding, padding, padding);
        dismiss.getLayoutParams().height = mTitleBarHeightPx;
        dismiss.getLayoutParams().width = mTitleBarHeightPx;
        View header = taskView.findViewById(R.id.task_view_bar);
        header.getLayoutParams().height = mTitleBarHeightPx;
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

    /**
     * Starts animations for each task view to either enlarge it to the size of the screen (when
     * launching a task), or (if {@code reverse} is true, to reduce it from the size of the screen
     * back to its place in the recents layout (when opening recents).
     * @param animationListener An animation listener for executing code before or after the
     *         animations run.
     * @param reverse Whether the blow-up animations should be run in reverse.
     */
    private void startBlowUpAnimations(Animation.AnimationListener animationListener,
            boolean reverse) {
        if (mTaskViews.size() == 0) {
            return;
        }
        int screenWidth = mLastDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
                ? mScreenSize.x : mScreenSize.y;
        int screenHeight = mLastDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
                ? mScreenSize.y : mScreenSize.x;
        screenHeight -= mStatusBarHeightPx + mNavigationBarHeightPx;
        for (int i = 0; i < mTaskViews.size(); i++) {
            View tv = mTaskViews.get(i);
            AnimationSet animations = new AnimationSet(true /* shareInterpolator */);
            animations.setInterpolator(new DecelerateInterpolator());
            if (i == 0 && animationListener != null) {
                animations.setAnimationListener(animationListener);
            }
            animations.setFillBefore(reverse);
            animations.setFillAfter(!reverse);
            Rect initialRect = mTaskViewRects.get(mTaskViewRects.size() - 1 - i);
            int xDelta = - initialRect.left;
            int yDelta = - initialRect.top - mTitleBarHeightPx + mStatusBarHeightPx;
            TranslateAnimation translate = new TranslateAnimation(
                    reverse ? xDelta : 0, reverse ? 0 : xDelta,
                    reverse ? yDelta : 0, reverse ? 0 : yDelta);
            translate.setDuration(250);
            animations.addAnimation(translate);


            float xScale = (float) screenWidth / (float) initialRect.width();
            float yScale = (float) screenHeight /
                    ((float) initialRect.height() - mTitleBarHeightPx);
            ScaleAnimation scale = new ScaleAnimation(
                    reverse ? xScale : 1, reverse ? 1 : xScale,
                    reverse ? yScale : 1, reverse ? 1 : yScale,
                    Animation.ABSOLUTE, 0, Animation.ABSOLUTE, mStatusBarHeightPx);
            scale.setDuration(300);
            animations.addAnimation(scale);

            tv.startAnimation(animations);
        }
    }

    private void updateControlVisibility() {
        boolean empty = (mTasks.size() == 0);
        mClearAllButton.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        mEmptyView.setVisibility(empty ? View.VISIBLE : View.INVISIBLE);
        if (empty) {
            mEmptyView.bringToFront();
        }
    }

    private void updateModel() {
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
        mTaskStack = plan.getTaskStack();
        RecentsTaskLoadPlan.Options loadOpts = new RecentsTaskLoadPlan.Options();
        loadOpts.runningTaskId = launchState.launchedToTaskId;
        loadOpts.numVisibleTasks = MAX_VISIBLE_TASKS;
        loadOpts.numVisibleTaskThumbnails = MAX_VISIBLE_TASKS;
        loader.loadTasks(this, plan, loadOpts);

        mTasks = mTaskStack.getStackTasks();
    }

    private void updateViews() {
        int screenWidth = mLastDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
                ? mScreenSize.x : mScreenSize.y;
        int screenHeight = mLastDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
                ? mScreenSize.y : mScreenSize.x;
        int paddingPixels = getResources().getDimensionPixelSize(
                R.dimen.recents_grid_inter_task_padding);
        mTaskViewRects = TaskGridLayoutAlgorithm.getRectsForTaskCount(
                mTasks.size(), screenWidth, screenHeight, getAppRectRatio(), paddingPixels,
                mMargins, mTitleBarHeightPx);
        boolean recycleViews = (mTaskViews.size() == mTasks.size());
        if (!recycleViews) {
            clearTaskViews();
        }
        for (int i = 0; i < mTasks.size(); i++) {
            Task task = mTasks.get(i);
            // We keep the same ordering in the model as other Recents flavors (older tasks are
            // first in the stack) so that the logic can be similar, but we reverse the order
            // when placing views on the screen so that most recent tasks are displayed first.
            Rect rect = mTaskViewRects.get(mTaskViewRects.size() - 1 - i);
            TaskView taskView;
            if (recycleViews) {
                taskView = mTaskViews.get(i);
            } else {
                taskView = createView();
                customizeTaskView(taskView);
            }
            taskView.onTaskBound(task, mTouchExplorationEnabled, mLastDisplayOrientation,
                    mDisplayRect);
            Recents.getTaskLoader().loadTaskData(task);
            taskView.setTouchEnabled(true);
            // Show dismiss button right away.
            taskView.startNoUserInteractionAnimation();
            taskView.setLayoutParams(new FrameLayout.LayoutParams(rect.width(), rect.height()));
            taskView.setTranslationX(rect.left);
            taskView.setTranslationY(rect.top);
            if (!recycleViews) {
                mRecentsView.addView(taskView);
                mTaskViews.add(taskView);
            }
        }
        updateControlVisibility();
    }

    private float getAppRectRatio() {
        if (mLastDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            return (float) mScreenSize.x /
                    (float) (mScreenSize.y - mStatusBarHeightPx - mNavigationBarHeightPx);
        } else {
            return (float) mScreenSize.y /
                    (float) (mScreenSize.x - mStatusBarHeightPx - mNavigationBarHeightPx);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().send(new RecentsVisibilityChangedEvent(this, true));
        updateModel();
        updateViews();
        if (mTaskViews.size() > 0) {
            mTaskViews.get(mTaskViews.size() - 1).bringToFront();
        }
        startBlowUpAnimations(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) { }

            @Override
            public void onAnimationEnd(Animation animation) {
                updateViews();
            }

            @Override
            public void onAnimationRepeat(Animation animation) { }
        }, true /* reverse */);
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
        int numStackTasks = mTaskStack.getStackTaskCount();
        EventBus.getDefault().send(new ConfigurationChangedEvent(false /* fromMultiWindow */,
                mLastDisplayOrientation != newDeviceConfiguration.orientation,
                mLastDisplayDensity != newDeviceConfiguration.densityDpi, numStackTasks > 0));
        mLastDisplayOrientation = newDeviceConfiguration.orientation;
        mLastDisplayDensity = newDeviceConfiguration.densityDpi;
        updateViews();
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
        updateModel();
        updateViews();

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
        event.taskView.bringToFront();
        startActivity(event.task.key.baseIntent);
        // Eventually we should start blow-up animations here, but we need to make sure it's done
        // in parallel with starting the activity so that we don't introduce unneeded latency.
    }
}
