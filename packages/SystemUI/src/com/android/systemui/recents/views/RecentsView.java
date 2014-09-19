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

package com.android.systemui.recents.views;

import android.app.ActivityOptions;
import android.app.TaskStackBuilder;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.RecentsPackageMonitor;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout implements TaskStackView.TaskStackViewCallbacks,
        RecentsPackageMonitor.PackageCallbacks {

    /** The RecentsView callbacks */
    public interface RecentsViewCallbacks {
        public void onTaskViewClicked();
        public void onTaskLaunchFailed();
        public void onAllTaskViewsDismissed();
        public void onExitToHomeAnimationTriggered();
    }

    RecentsConfiguration mConfig;
    LayoutInflater mInflater;
    DebugOverlayView mDebugOverlay;

    ArrayList<TaskStack> mStacks;
    View mSearchBar;
    RecentsViewCallbacks mCb;
    boolean mAlreadyLaunchingTask;

    public RecentsView(Context context) {
        super(context);
    }

    public RecentsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        mInflater = LayoutInflater.from(context);
    }

    /** Sets the callbacks */
    public void setCallbacks(RecentsViewCallbacks cb) {
        mCb = cb;
    }

    /** Sets the debug overlay */
    public void setDebugOverlay(DebugOverlayView overlay) {
        mDebugOverlay = overlay;
    }

    /** Set/get the bsp root node */
    public void setTaskStacks(ArrayList<TaskStack> stacks) {
        // Remove all TaskStackViews (but leave the search bar)
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (v != mSearchBar) {
                removeViewAt(i);
            }
        }

        // Create and add all the stacks for this partition of space.
        mStacks = stacks;
        int numStacks = mStacks.size();
        for (int i = 0; i < numStacks; i++) {
            TaskStack stack = mStacks.get(i);
            TaskStackView stackView = new TaskStackView(getContext(), stack);
            stackView.setCallbacks(this);
            // Enable debug mode drawing
            if (mConfig.debugModeEnabled) {
                stackView.setDebugOverlay(mDebugOverlay);
            }
            addView(stackView);
        }

        // Reset the launched state
        mAlreadyLaunchingTask = false;
    }

    /** Removes all the task stack views from this recents view. */
    public void removeAllTaskStacks() {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                removeViewAt(i);
            }
        }
    }

    /** Launches the focused task from the first stack if possible */
    public boolean launchFocusedTask() {
        // Get the first stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                TaskStackView stackView = (TaskStackView) child;
                TaskStack stack = stackView.mStack;
                // Iterate the stack views and try and find the focused task
                int taskCount = stackView.getChildCount();
                for (int j = 0; j < taskCount; j++) {
                    TaskView tv = (TaskView) stackView.getChildAt(j);
                    Task task = tv.getTask();
                    if (tv.isFocusedTask()) {
                        onTaskViewClicked(stackView, tv, stack, task, false);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Launches the task that Recents was launched from, if possible */
    public boolean launchPreviousTask() {
        // Get the first stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                TaskStackView stackView = (TaskStackView) child;
                TaskStack stack = stackView.mStack;
                ArrayList<Task> tasks = stack.getTasks();

                // Find the launch task in the stack
                if (!tasks.isEmpty()) {
                    int taskCount = tasks.size();
                    for (int j = 0; j < taskCount; j++) {
                        if (tasks.get(j).isLaunchTarget) {
                            Task task = tasks.get(j);
                            TaskView tv = stackView.getChildViewForTask(task);
                            onTaskViewClicked(stackView, tv, stack, task, false);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Requests all task stacks to start their enter-recents animation */
    public void startEnterRecentsAnimation(ViewAnimation.TaskViewEnterContext ctx) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                TaskStackView stackView = (TaskStackView) child;
                stackView.startEnterRecentsAnimation(ctx);
            }
        }
    }

    /** Requests all task stacks to start their exit-recents animation */
    public void startExitToHomeAnimation(ViewAnimation.TaskViewExitContext ctx) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                TaskStackView stackView = (TaskStackView) child;
                stackView.startExitToHomeAnimation(ctx);
            }
        }

        // Notify of the exit animation
        mCb.onExitToHomeAnimationTriggered();
    }

    /** Adds the search bar */
    public void setSearchBar(View searchBar) {
        // Create the search bar (and hide it if we have no recent tasks)
        if (Constants.DebugFlags.App.EnableSearchLayout) {
            // Remove the previous search bar if one exists
            if (mSearchBar != null && indexOfChild(mSearchBar) > -1) {
                removeView(mSearchBar);
            }
            // Add the new search bar
            if (searchBar != null) {
                mSearchBar = searchBar;
                addView(mSearchBar);
            }
        }
    }

    /** Returns whether there is currently a search bar */
    public boolean hasSearchBar() {
        return mSearchBar != null;
    }

    /** Sets the visibility of the search bar */
    public void setSearchBarVisibility(int visibility) {
        if (mSearchBar != null) {
            mSearchBar.setVisibility(visibility);
            // Always bring the search bar to the top
            mSearchBar.bringToFront();
        }
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Get the search bar bounds and measure the search bar layout
        if (mSearchBar != null) {
            Rect searchBarSpaceBounds = new Rect();
            mConfig.getSearchBarBounds(width, height, mConfig.systemInsets.top, searchBarSpaceBounds);
            mSearchBar.measure(
                    MeasureSpec.makeMeasureSpec(searchBarSpaceBounds.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(searchBarSpaceBounds.height(), MeasureSpec.EXACTLY));
        }

        Rect taskStackBounds = new Rect();
        mConfig.getTaskStackBounds(width, height, mConfig.systemInsets.top,
                mConfig.systemInsets.right, taskStackBounds);

        // Measure each TaskStackView with the full width and height of the window since the 
        // transition view is a child of that stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar && child.getVisibility() != GONE) {
                TaskStackView tsv = (TaskStackView) child;
                // Set the insets to be the top/left inset + search bounds
                tsv.setStackInsetRect(taskStackBounds);
                tsv.measure(widthMeasureSpec, heightMeasureSpec);
            }
        }

        setMeasuredDimension(width, height);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Get the search bar bounds so that we lay it out
        if (mSearchBar != null) {
            Rect searchBarSpaceBounds = new Rect();
            mConfig.getSearchBarBounds(getMeasuredWidth(), getMeasuredHeight(),
                    mConfig.systemInsets.top, searchBarSpaceBounds);
            mSearchBar.layout(searchBarSpaceBounds.left, searchBarSpaceBounds.top,
                    searchBarSpaceBounds.right, searchBarSpaceBounds.bottom);
        }

        // Layout each TaskStackView with the full width and height of the window since the 
        // transition view is a child of that stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar && child.getVisibility() != GONE) {
                child.layout(left, top, left + child.getMeasuredWidth(),
                        top + child.getMeasuredHeight());
            }
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // Update the configuration with the latest system insets and trigger a relayout
        mConfig.updateSystemInsets(insets.getSystemWindowInsets());
        requestLayout();
        return insets.consumeSystemWindowInsets();
    }

    /** Notifies each task view of the user interaction. */
    public void onUserInteraction() {
        // Get the first stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                TaskStackView stackView = (TaskStackView) child;
                stackView.onUserInteraction();
            }
        }
    }

    /** Focuses the next task in the first stack view */
    public void focusNextTask(boolean forward) {
        // Get the first stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                TaskStackView stackView = (TaskStackView) child;
                stackView.focusNextTask(forward);
                break;
            }
        }
    }

    /** Dismisses the focused task. */
    public void dismissFocusedTask() {
        // Get the first stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                TaskStackView stackView = (TaskStackView) child;
                stackView.dismissFocusedTask();
                break;
            }
        }
    }

    /** Unfilters any filtered stacks */
    public boolean unfilterFilteredStacks() {
        if (mStacks != null) {
            // Check if there are any filtered stacks and unfilter them before we back out of Recents
            boolean stacksUnfiltered = false;
            int numStacks = mStacks.size();
            for (int i = 0; i < numStacks; i++) {
                TaskStack stack = mStacks.get(i);
                if (stack.hasFilteredTasks()) {
                    stack.unfilterTasks();
                    stacksUnfiltered = true;
                }
            }
            return stacksUnfiltered;
        }
        return false;
    }

    /**** TaskStackView.TaskStackCallbacks Implementation ****/

    @Override
    public void onTaskViewClicked(final TaskStackView stackView, final TaskView tv,
                                  final TaskStack stack, final Task task, final boolean lockToTask) {
        // Notify any callbacks of the launching of a new task
        if (mCb != null) {
            mCb.onTaskViewClicked();
        }
        // Skip if we are already launching tasks
        if (mAlreadyLaunchingTask) {
            return;
        }
        mAlreadyLaunchingTask = true;

        // Upfront the processing of the thumbnail
        TaskViewTransform transform = new TaskViewTransform();
        View sourceView;
        int offsetX = 0;
        int offsetY = 0;
        float stackScroll = stackView.getScroller().getStackScroll();
        if (tv == null) {
            // If there is no actual task view, then use the stack view as the source view
            // and then offset to the expected transform rect, but bound this to just
            // outside the display rect (to ensure we don't animate from too far away)
            sourceView = stackView;
            transform = stackView.getStackAlgorithm().getStackTransform(task, stackScroll, transform, null);
            offsetX = transform.rect.left;
            offsetY = mConfig.displayRect.height();
        } else {
            sourceView = tv.mThumbnailView;
            transform = stackView.getStackAlgorithm().getStackTransform(task, stackScroll, transform, null);
        }

        // Compute the thumbnail to scale up from
        final SystemServicesProxy ssp =
                RecentsTaskLoader.getInstance().getSystemServicesProxy();
        ActivityOptions opts = null;
        if (task.thumbnail != null && task.thumbnail.getWidth() > 0 &&
                task.thumbnail.getHeight() > 0) {
            Bitmap b;
            if (tv != null) {
                // Disable any focused state before we draw the header
                if (tv.isFocusedTask()) {
                    tv.unsetFocusedTask();
                }

                float scale = tv.getScaleX();
                int fromHeaderWidth = (int) (tv.mHeaderView.getMeasuredWidth() * scale);
                int fromHeaderHeight = (int) (tv.mHeaderView.getMeasuredHeight() * scale);
                b = Bitmap.createBitmap(fromHeaderWidth, fromHeaderHeight,
                        Bitmap.Config.ARGB_8888);
                if (Constants.DebugFlags.App.EnableTransitionThumbnailDebugMode) {
                    b.eraseColor(0xFFff0000);
                } else {
                    Canvas c = new Canvas(b);
                    c.scale(tv.getScaleX(), tv.getScaleY());
                    tv.mHeaderView.draw(c);
                    c.setBitmap(null);
                }
            } else {
                // Notify the system to skip the thumbnail layer by using an ALPHA_8 bitmap
                b = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
            }
            ActivityOptions.OnAnimationStartedListener animStartedListener = null;
            if (lockToTask) {
                animStartedListener = new ActivityOptions.OnAnimationStartedListener() {
                    boolean mTriggered = false;
                    @Override
                    public void onAnimationStarted() {
                        if (!mTriggered) {
                            postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    ssp.lockCurrentTask();
                                }
                            }, 350);
                            mTriggered = true;
                        }
                    }
                };
            }
            opts = ActivityOptions.makeThumbnailAspectScaleUpAnimation(sourceView,
                    b, offsetX, offsetY, transform.rect.width(), transform.rect.height(),
                    animStartedListener);
        }

        final ActivityOptions launchOpts = opts;
        final Runnable launchRunnable = new Runnable() {
            @Override
            public void run() {
                if (task.isActive) {
                    // Bring an active task to the foreground
                    ssp.moveTaskToFront(task.key.id, launchOpts);
                } else {
                    if (ssp.startActivityFromRecents(getContext(), task.key.id,
                            task.activityLabel, launchOpts)) {
                        if (launchOpts == null && lockToTask) {
                            ssp.lockCurrentTask();
                        }
                    } else {
                        // Dismiss the task and return the user to home if we fail to
                        // launch the task
                        onTaskViewDismissed(task);
                        if (mCb != null) {
                            mCb.onTaskLaunchFailed();
                        }
                    }
                }
            }
        };

        // Launch the app right away if there is no task view, otherwise, animate the icon out first
        if (tv == null) {
            post(launchRunnable);
        } else {
            if (!task.group.isFrontMostTask(task)) {
                // For affiliated tasks that are behind other tasks, we must animate the front cards
                // out of view before starting the task transition
                stackView.startLaunchTaskAnimation(tv, launchRunnable, lockToTask);
            } else {
                // Otherwise, we can start the task transition immediately
                stackView.startLaunchTaskAnimation(tv, null, lockToTask);
                postDelayed(launchRunnable, 17);
            }
        }
    }

    @Override
    public void onTaskViewAppInfoClicked(Task t) {
        // Create a new task stack with the application info details activity
        Intent baseIntent = t.key.baseIntent;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", baseIntent.getComponent().getPackageName(), null));
        intent.setComponent(intent.resolveActivity(getContext().getPackageManager()));
        TaskStackBuilder.create(getContext())
                .addNextIntentWithParentStack(intent).startActivities(null,
                new UserHandle(t.key.userId));
    }

    @Override
    public void onTaskViewDismissed(Task t) {
        // Remove any stored data from the loader.  We currently don't bother notifying the views
        // that the data has been unloaded because at the point we call onTaskViewDismissed(), the views
        // either don't need to be updated, or have already been removed.
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        loader.deleteTaskData(t, false);

        // Remove the old task from activity manager
        RecentsTaskLoader.getInstance().getSystemServicesProxy().removeTask(t.key.id,
                Utilities.isDocument(t.key.baseIntent));
    }

    @Override
    public void onAllTaskViewsDismissed() {
        mCb.onAllTaskViewsDismissed();
    }

    @Override
    public void onTaskStackFilterTriggered() {
        // Hide the search bar
        if (mSearchBar != null) {
            mSearchBar.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .setDuration(mConfig.filteringCurrentViewsAnimDuration)
                    .withLayer()
                    .start();
        }
    }

    @Override
    public void onTaskStackUnfilterTriggered() {
        // Show the search bar
        if (mSearchBar != null) {
            mSearchBar.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .setDuration(mConfig.filteringNewViewsAnimDuration)
                    .withLayer()
                    .start();
        }
    }

    /**** RecentsPackageMonitor.PackageCallbacks Implementation ****/

    @Override
    public void onComponentRemoved(HashSet<ComponentName> cns) {
        // Propagate this event down to each task stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mSearchBar) {
                TaskStackView stackView = (TaskStackView) child;
                stackView.onComponentRemoved(cns);
            }
        }
    }
}
