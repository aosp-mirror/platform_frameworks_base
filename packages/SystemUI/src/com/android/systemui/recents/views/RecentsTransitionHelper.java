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

import static android.app.ActivityManager.StackId.ASSISTANT_STACK_ID;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.annotation.Nullable;
import android.app.ActivityManager.StackId;
import android.app.ActivityOptions;
import android.app.ActivityOptions.OnAnimationStartedListener;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.view.AppTransitionAnimationSpec;
import android.view.DisplayListCanvas;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.RenderNode;
import android.view.ThreadedRenderer;
import android.view.View;

import com.android.internal.annotations.GuardedBy;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.ExitRecentsWindowFirstAnimationFrameEvent;
import com.android.systemui.recents.events.activity.LaunchTaskFailedEvent;
import com.android.systemui.recents.events.activity.LaunchTaskStartedEvent;
import com.android.systemui.recents.events.activity.LaunchTaskSucceededEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.component.SetWaitingForTransitionStartEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A helper class to create transitions to/from Recents
 */
public class RecentsTransitionHelper {

    private static final String TAG = "RecentsTransitionHelper";
    private static final boolean DEBUG = false;

    /**
     * Special value for {@link #mAppTransitionAnimationSpecs}: Indicate that we are currently
     * waiting for the specs to be retrieved.
     */
    private static final List<AppTransitionAnimationSpec> SPECS_WAITING = new ArrayList<>();

    @GuardedBy("this")
    private List<AppTransitionAnimationSpec> mAppTransitionAnimationSpecs = SPECS_WAITING;

    private Context mContext;
    private Handler mHandler;
    private TaskViewTransform mTmpTransform = new TaskViewTransform();

    private class StartScreenPinningRunnableRunnable implements Runnable {

        private int taskId = -1;

        @Override
        public void run() {
            EventBus.getDefault().send(new ScreenPinningRequestEvent(mContext, taskId));
        }
    }
    private StartScreenPinningRunnableRunnable mStartScreenPinningRunnable
            = new StartScreenPinningRunnableRunnable();

    public RecentsTransitionHelper(Context context) {
        mContext = context;
        mHandler = new Handler();
    }

    /**
     * Launches the specified {@link Task}.
     */
    public void launchTaskFromRecents(final TaskStack stack, @Nullable final Task task,
            final TaskStackView stackView, final TaskView taskView,
            final boolean screenPinningRequested, final int destinationStack) {

        final ActivityOptions.OnAnimationStartedListener animStartedListener;
        final AppTransitionAnimationSpecsFuture transitionFuture;
        if (taskView != null) {

            // Fetch window rect here already in order not to be blocked on lock contention in WM
            // when the future calls it.
            final Rect windowRect = Recents.getSystemServices().getWindowRect();
            transitionFuture = getAppTransitionFuture(
                    () -> composeAnimationSpecs(task, stackView, destinationStack, windowRect));
            animStartedListener = new OnAnimationStartedListener() {
                private boolean mHandled;

                @Override
                public void onAnimationStarted() {
                    if (mHandled) {
                        return;
                    }
                    mHandled = true;

                    // If we are launching into another task, cancel the previous task's
                    // window transition
                    EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(task));
                    EventBus.getDefault().send(new ExitRecentsWindowFirstAnimationFrameEvent());
                    stackView.cancelAllTaskViewAnimations();

                    if (screenPinningRequested) {
                        // Request screen pinning after the animation runs
                        mStartScreenPinningRunnable.taskId = task.key.id;
                        mHandler.postDelayed(mStartScreenPinningRunnable, 350);
                    }

                    if (!Recents.getConfiguration().isLowRamDevice) {
                        // Reset the state where we are waiting for the transition to start
                        EventBus.getDefault().send(new SetWaitingForTransitionStartEvent(false));
                    }
                }
            };
        } else {
            // This is only the case if the task is not on screen (scrolled offscreen for example)
            transitionFuture = null;
            animStartedListener = new OnAnimationStartedListener() {
                private boolean mHandled;

                @Override
                public void onAnimationStarted() {
                    if (mHandled) {
                        return;
                    }
                    mHandled = true;

                    // If we are launching into another task, cancel the previous task's
                    // window transition
                    EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(task));
                    EventBus.getDefault().send(new ExitRecentsWindowFirstAnimationFrameEvent());
                    stackView.cancelAllTaskViewAnimations();

                    if (!Recents.getConfiguration().isLowRamDevice) {
                        // Reset the state where we are waiting for the transition to start
                        EventBus.getDefault().send(new SetWaitingForTransitionStartEvent(false));
                    }
                }
            };
        }

        EventBus.getDefault().send(new SetWaitingForTransitionStartEvent(true));
        final ActivityOptions opts = ActivityOptions.makeMultiThumbFutureAspectScaleAnimation(mContext,
                mHandler, transitionFuture != null ? transitionFuture.future : null,
                animStartedListener, true /* scaleUp */);
        if (taskView == null) {
            // If there is no task view, then we do not need to worry about animating out occluding
            // task views, and we can launch immediately
            startTaskActivity(stack, task, taskView, opts, transitionFuture, destinationStack);
        } else {
            LaunchTaskStartedEvent launchStartedEvent = new LaunchTaskStartedEvent(taskView,
                    screenPinningRequested);
            if (task.group != null && !task.group.isFrontMostTask(task)) {
                launchStartedEvent.addPostAnimationCallback(new Runnable() {
                    @Override
                    public void run() {
                        startTaskActivity(stack, task, taskView, opts, transitionFuture,
                                destinationStack);
                    }
                });
                EventBus.getDefault().send(launchStartedEvent);
            } else {
                EventBus.getDefault().send(launchStartedEvent);
                startTaskActivity(stack, task, taskView, opts, transitionFuture, destinationStack);
            }
        }
        Recents.getSystemServices().sendCloseSystemWindows(
                StatusBar.SYSTEM_DIALOG_REASON_HOME_KEY);
    }

    public IRemoteCallback wrapStartedListener(final OnAnimationStartedListener listener) {
        if (listener == null) {
            return null;
        }
        return new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) throws RemoteException {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onAnimationStarted();
                    }
                });
            }
        };
    }

    /**
     * Starts the activity for the launch task.
     *
     * @param taskView this is the {@link TaskView} that we are launching from. This can be null if
     *                 we are toggling recents and the launch-to task is now offscreen.
     * @param destinationStack id of the stack to put the task into.
     */
    private void startTaskActivity(TaskStack stack, Task task, @Nullable TaskView taskView,
            ActivityOptions opts, AppTransitionAnimationSpecsFuture transitionFuture,
            int destinationStack) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        ssp.startActivityFromRecents(mContext, task.key, task.title, opts, destinationStack,
                succeeded -> {
            if (succeeded) {
                // Keep track of the index of the task launch
                int taskIndexFromFront = 0;
                int taskIndex = stack.indexOfStackTask(task);
                if (taskIndex > -1) {
                    taskIndexFromFront = stack.getTaskCount() - taskIndex - 1;
                }
                EventBus.getDefault().send(new LaunchTaskSucceededEvent(taskIndexFromFront));
            } else {
                // Dismiss the task if we fail to launch it
                if (taskView != null) {
                    taskView.dismissTask();
                }

                // Keep track of failed launches
                EventBus.getDefault().send(new LaunchTaskFailedEvent());
            }
        });
        if (transitionFuture != null) {
            mHandler.post(transitionFuture::precacheSpecs);
        }
    }

    /**
     * Creates a future which will later be queried for animation specs for this current transition.
     *
     * @param composer The implementation that composes the specs on the UI thread.
     */
    public AppTransitionAnimationSpecsFuture getAppTransitionFuture(
            final AnimationSpecComposer composer) {
        synchronized (this) {
            mAppTransitionAnimationSpecs = SPECS_WAITING;
        }
        IAppTransitionAnimationSpecsFuture future = new IAppTransitionAnimationSpecsFuture.Stub() {
            @Override
            public AppTransitionAnimationSpec[] get() throws RemoteException {
                mHandler.post(() -> {
                    synchronized (RecentsTransitionHelper.this) {
                        mAppTransitionAnimationSpecs = composer.composeSpecs();
                        RecentsTransitionHelper.this.notifyAll();
                    }
                });
                synchronized (RecentsTransitionHelper.this) {
                    while (mAppTransitionAnimationSpecs == SPECS_WAITING) {
                        try {
                            RecentsTransitionHelper.this.wait();
                        } catch (InterruptedException e) {}
                    }
                    if (mAppTransitionAnimationSpecs == null) {
                        return null;
                    }
                    AppTransitionAnimationSpec[] specs
                            = new AppTransitionAnimationSpec[mAppTransitionAnimationSpecs.size()];
                    mAppTransitionAnimationSpecs.toArray(specs);
                    mAppTransitionAnimationSpecs = SPECS_WAITING;
                    return specs;
                }
            }
        };
        return new AppTransitionAnimationSpecsFuture(composer, future);
    }

    /**
     * Composes the transition spec when docking a task, which includes a full task bitmap.
     */
    public List<AppTransitionAnimationSpec> composeDockAnimationSpec(TaskView taskView,
            Rect bounds) {
        mTmpTransform.fillIn(taskView);
        Task task = taskView.getTask();
        GraphicBuffer buffer = RecentsTransitionHelper.composeTaskBitmap(taskView, mTmpTransform);
        return Collections.singletonList(new AppTransitionAnimationSpec(task.key.id, buffer,
                bounds));
    }

    /**
     * Composes the animation specs for all the tasks in the target stack.
     */
    private List<AppTransitionAnimationSpec> composeAnimationSpecs(final Task task,
            final TaskStackView stackView, final int destinationStack, Rect windowRect) {
        // Ensure we have a valid target stack id
        final int targetStackId = destinationStack != INVALID_STACK_ID ?
                destinationStack : task.key.stackId;
        if (!StackId.useAnimationSpecForAppTransition(targetStackId)) {
            return null;
        }

        // Calculate the offscreen task rect (for tasks that are not backed by views)
        TaskView taskView = stackView.getChildViewForTask(task);
        TaskStackLayoutAlgorithm stackLayout = stackView.getStackAlgorithm();
        Rect offscreenTaskRect = new Rect();
        stackLayout.getFrontOfStackTransform().rect.round(offscreenTaskRect);

        // If this is a full screen stack, the transition will be towards the single, full screen
        // task. We only need the transition spec for this task.
        List<AppTransitionAnimationSpec> specs = new ArrayList<>();

        // TODO: Sometimes targetStackId is not initialized after reboot, so we also have to
        // check for INVALID_STACK_ID
        if (targetStackId == FULLSCREEN_WORKSPACE_STACK_ID || targetStackId == DOCKED_STACK_ID
                || targetStackId == ASSISTANT_STACK_ID || targetStackId == INVALID_STACK_ID) {
            if (taskView == null) {
                specs.add(composeOffscreenAnimationSpec(task, offscreenTaskRect));
            } else {
                mTmpTransform.fillIn(taskView);
                stackLayout.transformToScreenCoordinates(mTmpTransform, windowRect);
                AppTransitionAnimationSpec spec = composeAnimationSpec(stackView, taskView,
                        mTmpTransform, true /* addHeaderBitmap */);
                if (spec != null) {
                    specs.add(spec);
                }
            }
            return specs;
        }

        // Otherwise, for freeform tasks, create a new animation spec for each task we have to
        // launch
        TaskStack stack = stackView.getStack();
        ArrayList<Task> tasks = stack.getStackTasks();
        int taskCount = tasks.size();
        for (int i = taskCount - 1; i >= 0; i--) {
            Task t = tasks.get(i);
            if (t.isFreeformTask() || targetStackId == FREEFORM_WORKSPACE_STACK_ID) {
                TaskView tv = stackView.getChildViewForTask(t);
                if (tv == null) {
                    // TODO: Create a different animation task rect for this case (though it should
                    //       never happen)
                    specs.add(composeOffscreenAnimationSpec(t, offscreenTaskRect));
                } else {
                    mTmpTransform.fillIn(taskView);
                    stackLayout.transformToScreenCoordinates(mTmpTransform,
                            null /* windowOverrideRect */);
                    AppTransitionAnimationSpec spec = composeAnimationSpec(stackView, tv,
                            mTmpTransform, true /* addHeaderBitmap */);
                    if (spec != null) {
                        specs.add(spec);
                    }
                }
            }
        }

        return specs;
    }

    /**
     * Composes a single animation spec for the given {@link Task}
     */
    private static AppTransitionAnimationSpec composeOffscreenAnimationSpec(Task task,
            Rect taskRect) {
        return new AppTransitionAnimationSpec(task.key.id, null, taskRect);
    }

    public static GraphicBuffer composeTaskBitmap(TaskView taskView, TaskViewTransform transform) {
        float scale = transform.scale;
        int fromWidth = (int) (transform.rect.width() * scale);
        int fromHeight = (int) (transform.rect.height() * scale);
        if (fromWidth == 0 || fromHeight == 0) {
            Log.e(TAG, "Could not compose thumbnail for task: " + taskView.getTask() +
                    " at transform: " + transform);

            return drawViewIntoGraphicBuffer(1, 1, null, 1f, 0x00ffffff);
        } else {
            if (RecentsDebugFlags.Static.EnableTransitionThumbnailDebugMode) {
                return drawViewIntoGraphicBuffer(fromWidth, fromHeight, null, 1f, 0xFFff0000);
            } else {
                return drawViewIntoGraphicBuffer(fromWidth, fromHeight, taskView, scale, 0);
            }
        }
    }

    private static GraphicBuffer composeHeaderBitmap(TaskView taskView,
            TaskViewTransform transform) {
        float scale = transform.scale;
        int headerWidth = (int) (transform.rect.width());
        int headerHeight = (int) (taskView.mHeaderView.getMeasuredHeight() * scale);
        if (headerWidth == 0 || headerHeight == 0) {
            return null;
        }

        if (RecentsDebugFlags.Static.EnableTransitionThumbnailDebugMode) {
            return drawViewIntoGraphicBuffer(headerWidth, headerHeight, null, 1f, 0xFFff0000);
        } else {
            return drawViewIntoGraphicBuffer(headerWidth, headerHeight, taskView.mHeaderView,
                    scale, 0);
        }
    }

    public static GraphicBuffer drawViewIntoGraphicBuffer(int bufferWidth, int bufferHeight,
            View view, float scale, int eraseColor) {
        RenderNode node = RenderNode.create("RecentsTransition", null);
        node.setLeftTopRightBottom(0, 0, bufferWidth, bufferHeight);
        node.setClipToBounds(false);
        DisplayListCanvas c = node.start(bufferWidth, bufferHeight);
        c.scale(scale, scale);
        if (eraseColor != 0) {
            c.drawColor(eraseColor);
        }
        if (view != null) {
            view.draw(c);
        }
        node.end(c);
        Bitmap hwBitmap = ThreadedRenderer.createHardwareBitmap(node, bufferWidth, bufferHeight);
        return hwBitmap.createGraphicBufferHandle();
    }

    /**
     * Composes a single animation spec for the given {@link TaskView}
     */
    private static AppTransitionAnimationSpec composeAnimationSpec(TaskStackView stackView,
            TaskView taskView, TaskViewTransform transform, boolean addHeaderBitmap) {
        GraphicBuffer b = null;
        if (addHeaderBitmap) {
            b = composeHeaderBitmap(taskView, transform);
            if (b == null) {
                return null;
            }
        }

        Rect taskRect = new Rect();
        transform.rect.round(taskRect);
        // Disable in for low ram devices because each task does in Recents does not have fullscreen
        // height (stackView height) and when transitioning to fullscreen app, the code below would
        // force the task thumbnail to full stackView height immediately causing the transition
        // jarring.
        if (!Recents.getConfiguration().isLowRamDevice && taskView.getTask() !=
                stackView.getStack().getStackFrontMostTask(false /* includeFreeformTasks */)) {
            taskRect.bottom = taskRect.top + stackView.getMeasuredHeight();
        }
        return new AppTransitionAnimationSpec(taskView.getTask().key.id, b, taskRect);
    }

    public interface AnimationSpecComposer {
        List<AppTransitionAnimationSpec> composeSpecs();
    }

    /**
     * Class to be returned from {@link #composeAnimationSpec} that gives access to both the future
     * and the anonymous class used for composing.
     */
    public class AppTransitionAnimationSpecsFuture {

        private final AnimationSpecComposer composer;
        private final IAppTransitionAnimationSpecsFuture future;

        private AppTransitionAnimationSpecsFuture(AnimationSpecComposer composer,
                IAppTransitionAnimationSpecsFuture future) {
            this.composer = composer;
            this.future = future;
        }

        public IAppTransitionAnimationSpecsFuture getFuture() {
            return future;
        }

        /**
         * Manually generates and caches the spec such that they are already available when the
         * future needs.
         */
        public void precacheSpecs() {
            synchronized (RecentsTransitionHelper.this) {
                mAppTransitionAnimationSpecs = composer.composeSpecs();
            }
        }
    }
}
