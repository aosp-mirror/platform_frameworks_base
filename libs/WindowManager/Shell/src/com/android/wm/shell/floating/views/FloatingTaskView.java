/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.floating.views;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_FLOATING_APPS;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskView;
import com.android.wm.shell.TaskViewTransitions;
import com.android.wm.shell.bubbles.RelativeTouchListener;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.floating.FloatingTasksController;

/**
 * A view that holds a floating task using {@link TaskView} along with additional UI to manage
 * the task.
 */
public class FloatingTaskView extends FrameLayout {

    private static final String TAG = FloatingTaskView.class.getSimpleName();

    private FloatingTasksController mController;

    private FloatingMenuView mMenuView;
    private int mMenuHeight;
    private TaskView mTaskView;

    private float mCornerRadius = 0f;
    private int mBackgroundColor;

    private FloatingTasksController.Task mTask;

    private boolean mIsStashed;

    /**
     * Creates a floating task view.
     *
     * @param context the context to use.
     * @param controller the controller to notify about changes in the floating task (e.g. removal).
     */
    public FloatingTaskView(Context context, FloatingTasksController controller) {
        super(context);
        mController = controller;
        setElevation(getResources().getDimensionPixelSize(R.dimen.floating_task_elevation));
        mMenuHeight = context.getResources().getDimensionPixelSize(R.dimen.floating_task_menu_size);
        mMenuView = new FloatingMenuView(context);
        addView(mMenuView);

        applyThemeAttrs();

        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
    }

    // TODO: call this when theme/config changes
    void applyThemeAttrs() {
        boolean supportsRoundedCorners = ScreenDecorationsUtils.supportsRoundedCornersOnWindows(
                mContext.getResources());
        final TypedArray ta = mContext.obtainStyledAttributes(new int[] {
                android.R.attr.dialogCornerRadius,
                android.R.attr.colorBackgroundFloating});
        mCornerRadius = supportsRoundedCorners ? ta.getDimensionPixelSize(0, 0) : 0;
        mCornerRadius = mCornerRadius / 2f;
        mBackgroundColor = ta.getColor(1, Color.WHITE);

        ta.recycle();

        mMenuView.setCornerRadius(mCornerRadius);
        mMenuHeight = getResources().getDimensionPixelSize(
                R.dimen.floating_task_menu_size);

        if (mTaskView != null) {
            mTaskView.setCornerRadius(mCornerRadius);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Add corner radius here so that the menu extends behind the rounded corners of TaskView.
        int menuViewHeight = Math.min((int) (mMenuHeight + mCornerRadius), height);
        measureChild(mMenuView, widthMeasureSpec, MeasureSpec.makeMeasureSpec(menuViewHeight,
                MeasureSpec.getMode(heightMeasureSpec)));

        if (mTaskView != null) {
            int taskViewHeight = height - menuViewHeight;
            measureChild(mTaskView, widthMeasureSpec, MeasureSpec.makeMeasureSpec(taskViewHeight,
                    MeasureSpec.getMode(heightMeasureSpec)));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Drag handle above
        final int dragHandleBottom = t + mMenuView.getMeasuredHeight();
        mMenuView.layout(l, t, r, dragHandleBottom);
        if (mTaskView != null) {
            // Subtract radius so that the menu extends behind the rounded corners of TaskView.
            mTaskView.layout(l, (int) (dragHandleBottom - mCornerRadius), r,
                    dragHandleBottom + mTaskView.getMeasuredHeight());
        }
    }

    /**
     * Constructs the TaskView to display the task. Must be called for {@link #startTask} to work.
     */
    public void createTaskView(Context context, ShellTaskOrganizer organizer,
            TaskViewTransitions transitions, SyncTransactionQueue syncQueue) {
        mTaskView = new TaskView(context, organizer, transitions, syncQueue);
        addView(mTaskView);
        mTaskView.setEnableSurfaceClipping(true);
        mTaskView.setCornerRadius(mCornerRadius);
    }

    /**
     * Starts the provided task in the TaskView, if the TaskView exists. This should be called after
     * {@link #createTaskView}.
     */
    public void startTask(@ShellMainThread ShellExecutor executor,
            FloatingTasksController.Task task) {
        if (mTaskView == null) {
            Log.e(TAG, "starting task before creating the view!");
            return;
        }
        mTask = task;
        mTaskView.setListener(executor, mTaskViewListener);
    }

    /**
     * Sets the touch handler for the view.
     *
     * @param handler the touch handler for the view.
     */
    public void setTouchHandler(FloatingTaskLayer.FloatingTaskTouchHandler handler) {
        setOnTouchListener(new RelativeTouchListener() {
            @Override
            public boolean onDown(@NonNull View v, @NonNull MotionEvent ev) {
                handler.onDown(FloatingTaskView.this, ev, v.getTranslationX(), v.getTranslationY());
                return true;
            }

            @Override
            public void onMove(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                    float viewInitialY, float dx, float dy) {
                handler.onMove(FloatingTaskView.this, ev, dx, dy);
            }

            @Override
            public void onUp(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                    float viewInitialY, float dx, float dy, float velX, float velY) {
                handler.onUp(FloatingTaskView.this, ev, dx, dy, velX, velY);
            }
        });
        setOnClickListener(view -> {
            handler.onClick(FloatingTaskView.this);
        });

        mMenuView.addMenuItem(null, view -> {
            if (mIsStashed) {
                // If we're stashed all clicks un-stash.
                handler.onClick(FloatingTaskView.this);
            }
        });
    }

    private void setContentVisibility(boolean visible) {
        if (mTaskView == null) return;
        mTaskView.setAlpha(visible ? 1f : 0f);
    }

    /**
     * Sets the alpha of both this view and the TaskView.
     */
    public void setTaskViewAlpha(float alpha) {
        if (mTaskView != null) {
            mTaskView.setAlpha(alpha);
        }
        setAlpha(alpha);
    }

    /**
     * Call when the location or size of the view has changed to update TaskView.
     */
    public void updateLocation() {
        if (mTaskView == null) return;
        mTaskView.onLocationChanged();
    }

    private void updateMenuColor() {
        ActivityManager.RunningTaskInfo info = mTaskView.getTaskInfo();
        int color = info != null ? info.taskDescription.getBackgroundColor() : -1;
        if (color != -1) {
            mMenuView.setBackgroundColor(color);
        } else {
            mMenuView.setBackgroundColor(mBackgroundColor);
        }
    }

    /**
     * Sets whether the view is stashed or not.
     *
     * Also updates the touchable area based on this. If the view is stashed we don't direct taps
     * on the activity to the activity, instead a tap will un-stash the view.
     */
    public void setStashed(boolean isStashed) {
        if (mIsStashed != isStashed) {
            mIsStashed = isStashed;
            if (mTaskView == null) {
                return;
            }
            updateObscuredTouchRect();
        }
    }

    /** Whether the view is stashed at the edge of the screen or not. **/
    public boolean isStashed() {
        return mIsStashed;
    }

    private void updateObscuredTouchRect() {
        if (mIsStashed) {
            Rect tmpRect = new Rect();
            getBoundsOnScreen(tmpRect);
            mTaskView.setObscuredTouchRect(tmpRect);
        } else {
            mTaskView.setObscuredTouchRect(null);
        }
    }

    /**
     * Whether the task needs to be restarted, this can happen when {@link #cleanUpTaskView()} has
     * been called on this view or if
     * {@link #startTask(ShellExecutor, FloatingTasksController.Task)} was never called.
     */
    public boolean needsTaskStarted() {
        // If the task needs to be restarted then TaskView would have been cleaned up.
        return mTaskView == null;
    }

    /** Call this when the floating task activity is no longer in use. */
    public void cleanUpTaskView() {
        if (mTask != null && mTask.taskId != INVALID_TASK_ID) {
            try {
                ActivityTaskManager.getService().removeTask(mTask.taskId);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage());
            }
        }
        if (mTaskView != null) {
            mTaskView.release();
            removeView(mTaskView);
            mTaskView = null;
        }
    }

    // TODO: use task background colour / how to get the taskInfo ?
    private static int getDragBarColor(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskBgColor = taskInfo.taskDescription.getStatusBarColor();
        return Color.valueOf(taskBgColor == -1 ? Color.WHITE : taskBgColor).toArgb();
    }

    private final TaskView.Listener mTaskViewListener = new TaskView.Listener() {
        private boolean mInitialized = false;
        private boolean mDestroyed = false;

        @Override
        public void onInitialized() {
            if (mDestroyed || mInitialized) {
                return;
            }
            // Custom options so there is no activity transition animation
            ActivityOptions options = ActivityOptions.makeCustomAnimation(getContext(),
                    /* enterResId= */ 0, /* exitResId= */ 0);

            Rect launchBounds = new Rect();
            mTaskView.getBoundsOnScreen(launchBounds);

            try {
                options.setTaskAlwaysOnTop(true);
                if (mTask.intent != null) {
                    Intent fillInIntent = new Intent();
                    // Apply flags to make behaviour match documentLaunchMode=always.
                    fillInIntent.addFlags(FLAG_ACTIVITY_NEW_DOCUMENT);
                    fillInIntent.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);

                    PendingIntent pi = PendingIntent.getActivity(mContext, 0, mTask.intent,
                            PendingIntent.FLAG_MUTABLE,
                            null);
                    mTaskView.startActivity(pi, fillInIntent, options, launchBounds);
                } else {
                    ProtoLog.e(WM_SHELL_FLOATING_APPS, "Tried to start a task with null intent");
                }
            } catch (RuntimeException e) {
                ProtoLog.e(WM_SHELL_FLOATING_APPS, "Exception while starting task: %s",
                        e.getMessage());
                mController.removeTask();
            }
            mInitialized = true;
        }

        @Override
        public void onReleased() {
            mDestroyed = true;
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName name) {
            mTask.taskId = taskId;
            updateMenuColor();
            setContentVisibility(true);
        }

        @Override
        public void onTaskVisibilityChanged(int taskId, boolean visible) {
            setContentVisibility(visible);
        }

        @Override
        public void onTaskRemovalStarted(int taskId) {
            // Must post because this is called from a binder thread.
            post(() -> {
                mController.removeTask();
                cleanUpTaskView();
            });
        }

        @Override
        public void onBackPressedOnTaskRoot(int taskId) {
            if (mTask.taskId == taskId && !mIsStashed) {
                // TODO: is removing the window the desired behavior?
                post(() -> mController.removeTask());
            }
        }
    };
}
