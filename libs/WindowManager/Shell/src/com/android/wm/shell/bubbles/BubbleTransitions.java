/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.bubbles;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.View.INVISIBLE;
import static android.view.WindowManager.TRANSIT_CHANGE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.launcher3.icons.BubbleIconFactory;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.bubbles.bar.BubbleBarExpandedView;
import com.android.wm.shell.bubbles.bar.BubbleBarLayerView;
import com.android.wm.shell.taskview.TaskView;
import com.android.wm.shell.taskview.TaskViewRepository;
import com.android.wm.shell.taskview.TaskViewTaskController;
import com.android.wm.shell.taskview.TaskViewTransitions;
import com.android.wm.shell.transition.Transitions;

import java.util.concurrent.Executor;

/**
 * Implements transition coordination for bubble operations.
 */
public class BubbleTransitions {
    private static final String TAG = "BubbleTransitions";

    /**
     * Multiplier used to convert a view elevation to an "equivalent" shadow-radius. This is the
     * same multiple used by skia and surface-outsets in WMS.
     */
    private static final float ELEVATION_TO_RADIUS = 2;

    @NonNull final Transitions mTransitions;
    @NonNull final ShellTaskOrganizer mTaskOrganizer;
    @NonNull final TaskViewRepository mRepository;
    @NonNull final Executor mMainExecutor;
    @NonNull final BubbleData mBubbleData;
    @NonNull final TaskViewTransitions mTaskViewTransitions;
    @NonNull final Context mContext;

    BubbleTransitions(@NonNull Transitions transitions, @NonNull ShellTaskOrganizer organizer,
            @NonNull TaskViewRepository repository, @NonNull BubbleData bubbleData,
            @NonNull TaskViewTransitions taskViewTransitions, Context context) {
        mTransitions = transitions;
        mTaskOrganizer = organizer;
        mRepository = repository;
        mMainExecutor = transitions.getMainExecutor();
        mBubbleData = bubbleData;
        mTaskViewTransitions = taskViewTransitions;
        mContext = context;
    }

    /**
     * Starts a convert-to-bubble transition.
     *
     * @see ConvertToBubble
     */
    public BubbleTransition startConvertToBubble(Bubble bubble, TaskInfo taskInfo,
            BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
            BubblePositioner positioner, BubbleStackView stackView,
            BubbleBarLayerView layerView, BubbleIconFactory iconFactory,
            boolean inflateSync) {
        return new ConvertToBubble(bubble, taskInfo, mContext,
                expandedViewManager, factory, positioner, stackView, layerView, iconFactory,
                inflateSync);
    }

    /**
     * Starts a convert-from-bubble transition.
     *
     * @see ConvertFromBubble
     */
    public BubbleTransition startConvertFromBubble(Bubble bubble,
            TaskInfo taskInfo) {
        ConvertFromBubble convert = new ConvertFromBubble(bubble, taskInfo);
        return convert;
    }

    /**
     * Plucks the task-surface out of an ancestor view while making the view invisible. This helper
     * attempts to do this seamlessly (ie. view becomes invisible in sync with task reparent).
     */
    private void pluck(SurfaceControl taskLeash, View fromView, SurfaceControl dest,
            float destX, float destY, float cornerRadius, SurfaceControl.Transaction t,
            Runnable onPlucked) {
        SurfaceControl.Transaction pluckT = new SurfaceControl.Transaction();
        pluckT.reparent(taskLeash, dest);
        t.reparent(taskLeash, dest);
        pluckT.setPosition(taskLeash, destX, destY);
        t.setPosition(taskLeash, destX, destY);
        pluckT.show(taskLeash);
        pluckT.setAlpha(taskLeash, 1.f);
        float shadowRadius = fromView.getElevation() * ELEVATION_TO_RADIUS;
        pluckT.setShadowRadius(taskLeash, shadowRadius);
        pluckT.setCornerRadius(taskLeash, cornerRadius);
        t.setShadowRadius(taskLeash, shadowRadius);
        t.setCornerRadius(taskLeash, cornerRadius);

        // Need to remove the taskview AFTER applying the startTransaction because it isn't
        // synchronized.
        pluckT.addTransactionCommittedListener(mMainExecutor, onPlucked::run);
        fromView.getViewRootImpl().applyTransactionOnDraw(pluckT);
        fromView.setVisibility(INVISIBLE);
    }

    /**
     * Interface to a bubble-specific transition. Bubble transitions have a multi-step lifecycle
     * in order to coordinate with the bubble view logic. These steps are communicated on this
     * interface.
     */
    interface BubbleTransition {
        default void surfaceCreated() {}
        default void continueExpand() {}
        void skip();
        default void continueCollapse() {}
    }

    /**
     * BubbleTransition that coordinates the process of a non-bubble task becoming a bubble. The
     * steps are as follows:
     *
     * 1. Start inflating the bubble view
     * 2. Once inflated (but not-yet visible), tell WM to do the shell-transition.
     * 3. Transition becomes ready, so notify Launcher
     * 4. Launcher responds with showExpandedView which calls continueExpand() to make view visible
     * 5. Surface is created which kicks off actual animation
     *
     * So, constructor -> onInflated -> startAnimation -> continueExpand -> surfaceCreated.
     *
     * continueExpand and surfaceCreated are set-up to happen in either order, though, to support
     * UX/timing adjustments.
     */
    @VisibleForTesting
    class ConvertToBubble implements Transitions.TransitionHandler, BubbleTransition {
        final BubbleBarLayerView mLayerView;
        Bubble mBubble;
        IBinder mTransition;
        Transitions.TransitionFinishCallback mFinishCb;
        WindowContainerTransaction mFinishWct = null;
        final Rect mStartBounds = new Rect();
        SurfaceControl mSnapshot = null;
        TaskInfo mTaskInfo;
        boolean mFinishedExpand = false;
        BubbleViewProvider mPriorBubble = null;

        private SurfaceControl.Transaction mFinishT;
        private SurfaceControl mTaskLeash;

        ConvertToBubble(Bubble bubble, TaskInfo taskInfo, Context context,
                BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
                BubblePositioner positioner, BubbleStackView stackView,
                BubbleBarLayerView layerView, BubbleIconFactory iconFactory, boolean inflateSync) {
            mBubble = bubble;
            mTaskInfo = taskInfo;
            mLayerView = layerView;
            mBubble.setInflateSynchronously(inflateSync);
            mBubble.setPreparingTransition(this);
            mBubble.inflate(
                    this::onInflated,
                    context,
                    expandedViewManager,
                    factory,
                    positioner,
                    stackView,
                    layerView,
                    iconFactory,
                    false /* skipInflation */);
        }

        @VisibleForTesting
        void onInflated(Bubble b) {
            if (b != mBubble) {
                throw new IllegalArgumentException("inflate callback doesn't match bubble");
            }
            final Rect launchBounds = new Rect();
            mLayerView.getExpandedViewRestBounds(launchBounds);
            WindowContainerTransaction wct = new WindowContainerTransaction();
            if (mTaskInfo.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW) {
                if (mTaskInfo.getParentTaskId() != INVALID_TASK_ID) {
                    wct.reparent(mTaskInfo.token, null, true);
                }
            }

            wct.setAlwaysOnTop(mTaskInfo.token, true);
            wct.setWindowingMode(mTaskInfo.token, WINDOWING_MODE_MULTI_WINDOW);
            wct.setBounds(mTaskInfo.token, launchBounds);

            final TaskView tv = b.getTaskView();
            tv.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
            final TaskViewRepository.TaskViewState state = mRepository.byTaskView(
                    tv.getController());
            if (state != null) {
                state.mVisible = true;
            }
            mTaskViewTransitions.enqueueExternal(tv.getController(), () -> {
                mTransition = mTransitions.startTransition(TRANSIT_CHANGE, wct, this);
                return mTransition;
            });
        }

        @Override
        public void skip() {
            mBubble.setPreparingTransition(null);
            mFinishCb.onTransitionFinished(mFinishWct);
            mFinishCb = null;
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @Nullable TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @NonNull SurfaceControl.Transaction finishTransaction) {
            if (!aborted) return;
            mTransition = null;
            mTaskViewTransitions.onExternalDone(transition);
        }

        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (mTransition != transition) return false;
            boolean found = false;
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change chg = info.getChanges().get(i);
                if (chg.getTaskInfo() == null) continue;
                if (chg.getMode() != TRANSIT_CHANGE) continue;
                if (!mTaskInfo.token.equals(chg.getTaskInfo().token)) continue;
                mStartBounds.set(chg.getStartAbsBounds());
                // Converting a task into taskview, so treat as "new"
                mFinishWct = new WindowContainerTransaction();
                mTaskInfo = chg.getTaskInfo();
                mFinishT = finishTransaction;
                mTaskLeash = chg.getLeash();
                found = true;
                mSnapshot = chg.getSnapshot();
                break;
            }
            if (!found) {
                Slog.w(TAG, "Expected a TaskView conversion in this transition but didn't get "
                        + "one, cleaning up the task view");
                mBubble.getTaskView().getController().setTaskNotFound();
                mTaskViewTransitions.onExternalDone(transition);
                return false;
            }
            mFinishCb = finishCallback;

            // Now update state (and talk to launcher) in parallel with snapshot stuff
            mBubbleData.notificationEntryUpdated(mBubble, /* suppressFlyout= */ true,
                    /* showInShade= */ false);

            startTransaction.show(mSnapshot);
            // Move snapshot to root so that it remains visible while task is moved to taskview
            startTransaction.reparent(mSnapshot, info.getRoot(0).getLeash());
            startTransaction.setPosition(mSnapshot,
                    mStartBounds.left - info.getRoot(0).getOffset().x,
                    mStartBounds.top - info.getRoot(0).getOffset().y);
            startTransaction.setLayer(mSnapshot, Integer.MAX_VALUE);
            startTransaction.apply();

            mTaskViewTransitions.onExternalDone(transition);
            return true;
        }

        @Override
        public void continueExpand() {
            mFinishedExpand = true;
            final boolean animate = mLayerView.canExpandView(mBubble);
            if (animate) {
                mPriorBubble = mLayerView.prepareConvertedView(mBubble);
            }
            if (mPriorBubble != null) {
                // TODO: an animation. For now though, just remove it.
                final BubbleBarExpandedView priorView = mPriorBubble.getBubbleBarExpandedView();
                mLayerView.removeView(priorView);
                mPriorBubble = null;
            }
            if (!animate || mBubble.getTaskView().getSurfaceControl() != null) {
                playAnimation(animate);
            }
        }

        @Override
        public void surfaceCreated() {
            mMainExecutor.execute(() -> {
                final TaskViewTaskController tvc = mBubble.getTaskView().getController();
                final TaskViewRepository.TaskViewState state = mRepository.byTaskView(tvc);
                if (state == null) return;
                state.mVisible = true;
                if (mFinishedExpand) {
                    playAnimation(true /* animate */);
                }
            });
        }

        private void playAnimation(boolean animate) {
            final TaskViewTaskController tv = mBubble.getTaskView().getController();
            final SurfaceControl.Transaction startT = new SurfaceControl.Transaction();
            mTaskViewTransitions.prepareOpenAnimation(tv, true /* new */, startT, mFinishT,
                    (ActivityManager.RunningTaskInfo) mTaskInfo, mTaskLeash, mFinishWct);

            if (mFinishWct.isEmpty()) {
                mFinishWct = null;
            }

            // Preparation is complete.
            mBubble.setPreparingTransition(null);

            if (animate) {
                mLayerView.animateConvert(startT, mStartBounds, mSnapshot, mTaskLeash, () -> {
                    mFinishCb.onTransitionFinished(mFinishWct);
                    mFinishCb = null;
                });
            } else {
                startT.apply();
                mFinishCb.onTransitionFinished(mFinishWct);
                mFinishCb = null;
            }
        }
    }

    /**
     * BubbleTransition that coordinates the setup for moving a task out of a bubble. The actual
     * animation is owned by the "receiver" of the task; however, because Bubbles uses TaskView,
     * we need to do some extra coordination work to get the task surface out of the view
     * "seamlessly".
     *
     * The process here looks like:
     * 1. Send transition to WM for leaving bubbles mode
     * 2. in startAnimation, set-up a "pluck" operation to pull the task surface out of taskview
     * 3. Once "plucked", remove the view (calls continueCollapse when surfaces can be cleaned-up)
     * 4. Then re-dispatch the transition animation so that the "receiver" can animate it.
     *
     * So, constructor -> startAnimation -> continueCollapse -> re-dispatch.
     */
    @VisibleForTesting
    class ConvertFromBubble implements Transitions.TransitionHandler, BubbleTransition {
        @NonNull final Bubble mBubble;
        IBinder mTransition;
        TaskInfo mTaskInfo;
        SurfaceControl mTaskLeash;
        SurfaceControl mRootLeash;

        ConvertFromBubble(@NonNull Bubble bubble, TaskInfo taskInfo) {
            mBubble = bubble;
            mTaskInfo = taskInfo;

            mBubble.setPreparingTransition(this);
            WindowContainerTransaction wct = new WindowContainerTransaction();
            WindowContainerToken token = mTaskInfo.getToken();
            wct.setWindowingMode(token, WINDOWING_MODE_UNDEFINED);
            wct.setAlwaysOnTop(token, false);
            mTaskOrganizer.setInterceptBackPressedOnTaskRoot(token, false);
            mTaskViewTransitions.enqueueExternal(
                    mBubble.getTaskView().getController(),
                    () -> {
                        mTransition = mTransitions.startTransition(TRANSIT_CHANGE, wct, this);
                        return mTransition;
                    });
        }

        @Override
        public void skip() {
            mBubble.setPreparingTransition(null);
            final TaskViewTaskController tv =
                    mBubble.getTaskView().getController();
            tv.notifyTaskRemovalStarted(tv.getTaskInfo());
            mTaskLeash = null;
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @android.annotation.Nullable TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @NonNull SurfaceControl.Transaction finishTransaction) {
            if (!aborted) return;
            mTransition = null;
            skip();
            mTaskViewTransitions.onExternalDone(transition);
        }

        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (mTransition != transition) return false;

            final TaskViewTaskController tv =
                    mBubble.getTaskView().getController();
            if (tv == null) {
                mTaskViewTransitions.onExternalDone(transition);
                return false;
            }

            TransitionInfo.Change taskChg = null;

            boolean found = false;
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change chg = info.getChanges().get(i);
                if (chg.getTaskInfo() == null) continue;
                if (chg.getMode() != TRANSIT_CHANGE) continue;
                if (!mTaskInfo.token.equals(chg.getTaskInfo().token)) continue;
                found = true;
                mRepository.remove(tv);
                taskChg = chg;
                break;
            }

            if (!found) {
                Slog.w(TAG, "Expected a TaskView conversion in this transition but didn't get "
                        + "one, cleaning up the task view");
                tv.setTaskNotFound();
                skip();
                mTaskViewTransitions.onExternalDone(transition);
                return false;
            }

            mTaskLeash = taskChg.getLeash();
            mRootLeash = info.getRoot(0).getLeash();

            SurfaceControl dest =
                    mBubble.getBubbleBarExpandedView().getViewRootImpl().getSurfaceControl();
            final Runnable onPlucked = () -> {
                // Need to remove the taskview AFTER applying the startTransaction because
                // it isn't synchronized.
                tv.notifyTaskRemovalStarted(tv.getTaskInfo());
                // Unset after removeView so it can be used to pick a different animation.
                mBubble.setPreparingTransition(null);
                mBubbleData.setExpanded(false /* expanded */);
            };
            if (dest != null) {
                pluck(mTaskLeash, mBubble.getBubbleBarExpandedView(), dest,
                        taskChg.getStartAbsBounds().left - info.getRoot(0).getOffset().x,
                        taskChg.getStartAbsBounds().top - info.getRoot(0).getOffset().y,
                        mBubble.getBubbleBarExpandedView().getCornerRadius(), startTransaction,
                        onPlucked);
                mBubble.getBubbleBarExpandedView().post(() -> mTransitions.dispatchTransition(
                        mTransition, info, startTransaction, finishTransaction, finishCallback,
                        null));
            } else {
                onPlucked.run();
                mTransitions.dispatchTransition(mTransition, info, startTransaction,
                        finishTransaction, finishCallback, null);
            }

            mTaskViewTransitions.onExternalDone(transition);
            return true;
        }

        @Override
        public void continueCollapse() {
            mBubble.cleanupTaskView();
            if (mTaskLeash == null) return;
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.reparent(mTaskLeash, mRootLeash);
            t.apply();
        }
    }
}
