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
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.UserHandle;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.recents.Console;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;


/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout implements TaskStackViewCallbacks {
    // The space partitioning root of this container
    SpaceNode mBSP;

    public RecentsView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /** Set/get the bsp root node */
    public void setBSP(SpaceNode n) {
        mBSP = n;

        // XXX: We shouldn't be recereating new stacks every time, but for now, that is OK
        // Add all the stacks for this partition
        removeAllViews();
        ArrayList<TaskStack> stacks = mBSP.getStacks();
        for (TaskStack stack : stacks) {
            TaskStackView stackView = new TaskStackView(getContext(), stack);
            stackView.setCallbacks(this);
            addView(stackView);
        }
    }

    /** Launches the first task from the first stack if possible */
    public boolean launchFirstTask() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskStackView stackView = (TaskStackView) getChildAt(i);
            TaskStack stack = stackView.mStack;
            ArrayList<Task> tasks = stack.getTasks();
            if (!tasks.isEmpty()) {
                Task task = tasks.get(tasks.size() - 1);
                TaskView tv = null;
                if (stackView.getChildCount() > 0) {
                    TaskView stv = (TaskView) stackView.getChildAt(stackView.getChildCount() - 1);
                    if (stv.getTask() == task) {
                        tv = stv;
                    }
                }
                onTaskLaunched(stackView, tv, stack, task);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        Console.log(Constants.DebugFlags.UI.MeasureAndLayout, "[RecentsView|measure]", "width: " + width + " height: " + height, Console.AnsiGreen);

        // We measure our stack views sans the status bar.  It will handle the nav bar itself.
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        int childHeight = height - config.systemInsets.top;

        // Measure each child
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.measure(widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(childHeight, heightMode));
            }
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        Console.log(Constants.DebugFlags.UI.MeasureAndLayout, "[RecentsView|layout]", new Rect(left, top, right, bottom) + " changed: " + changed, Console.AnsiGreen);
        // We offset our stack views by the status bar height.  It will handle the nav bar itself.
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        top += config.systemInsets.top;

        // Layout each child
        // XXX: Based on the space node for that task view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final int width = child.getMeasuredWidth();
                final int height = child.getMeasuredHeight();
                child.layout(left, top, left + width, top + height);
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        Console.log(Constants.DebugFlags.UI.Draw, "[RecentsView|dispatchDraw]", "", Console.AnsiPurple);
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        Console.log(Constants.DebugFlags.UI.MeasureAndLayout, "[RecentsView|fitSystemWindows]", "insets: " + insets, Console.AnsiGreen);

        // Update the configuration with the latest system insets and trigger a relayout
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        config.updateSystemInsets(insets);
        requestLayout();

        return true;
    }

    /** Unfilters any filtered stacks */
    public boolean unfilterFilteredStacks() {
        if (mBSP != null) {
            // Check if there are any filtered stacks and unfilter them before we back out of Recents
            boolean stacksUnfiltered = false;
            ArrayList<TaskStack> stacks = mBSP.getStacks();
            for (TaskStack stack : stacks) {
                if (stack.hasFilteredTasks()) {
                    stack.unfilterTasks();
                    stacksUnfiltered = true;
                }
            }
            return stacksUnfiltered;
        }
        return false;
    }

    /**** View.OnClickListener Implementation ****/

    @Override
    public void onTaskLaunched(final TaskStackView stackView, final TaskView tv,
                               final TaskStack stack, final Task task) {
        final Runnable launchRunnable = new Runnable() {
            @Override
            public void run() {
                TaskViewTransform transform;
                View sourceView = tv;
                int offsetX = 0;
                int offsetY = 0;
                if (tv == null) {
                    // Launch the activity
                    sourceView = stackView;
                    transform = stackView.getStackTransform(stack.indexOfTask(task));
                    offsetX = transform.rect.left;
                    offsetY = transform.rect.top;
                } else {
                    transform = stackView.getStackTransform(stack.indexOfTask(task));
                }

                // Compute the thumbnail to scale up from
                ActivityOptions opts = null;
                int thumbnailWidth = transform.rect.width();
                int thumbnailHeight = transform.rect.height();
                if (task.thumbnail != null && thumbnailWidth > 0 && thumbnailHeight > 0 &&
                        task.thumbnail.getWidth() > 0 && task.thumbnail.getHeight() > 0) {
                    // Resize the thumbnail to the size of the view that we are animating from
                    Bitmap b = Bitmap.createBitmap(thumbnailWidth, thumbnailHeight,
                            Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    c.drawBitmap(task.thumbnail,
                            new Rect(0, 0, task.thumbnail.getWidth(), task.thumbnail.getHeight()),
                            new Rect(0, 0, thumbnailWidth, thumbnailHeight), null);
                    c.setBitmap(null);
                    opts = ActivityOptions.makeThumbnailScaleUpAnimation(sourceView,
                            b, offsetX, offsetY);
                }

                // Launch the activity with the desired animation
                Intent i = new Intent(task.intent);
                i.setFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                        | Intent.FLAG_ACTIVITY_TASK_ON_HOME
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (opts != null) {
                    getContext().startActivityAsUser(i, opts.toBundle(), UserHandle.CURRENT);
                } else {
                    getContext().startActivityAsUser(i, UserHandle.CURRENT);
                }
            }
        };

        // Launch the app right away if there is no task view, otherwise, animate the icon out first
        if (tv == null || !Constants.Values.TaskView.AnimateFrontTaskIconOnLeavingRecents) {
            launchRunnable.run();
        } else {
            tv.animateOnLeavingRecents(launchRunnable);
        }
    }
}
