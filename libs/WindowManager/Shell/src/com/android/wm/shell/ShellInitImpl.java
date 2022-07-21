/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wm.shell;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_INIT;

import android.os.Build;
import android.os.SystemClock;
import android.util.Pair;

import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.activityembedding.ActivityEmbeddingController;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.freeform.FreeformTaskListener;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.kidsmode.KidsModeTaskOrganizer;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.transition.DefaultMixedHandler;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.unfold.UnfoldAnimationController;
import com.android.wm.shell.unfold.UnfoldTransitionHandler;

import java.util.ArrayList;
import java.util.Optional;

/**
 * The entry point implementation into the shell for initializing shell internal state.  Classes
 * which need to setup on start should inject an instance of this class and add an init callback.
 */
public class ShellInitImpl {
    private static final String TAG = ShellInitImpl.class.getSimpleName();

    private final DisplayController mDisplayController;
    private final DisplayImeController mDisplayImeController;
    private final DisplayInsetsController mDisplayInsetsController;
    private final DragAndDropController mDragAndDropController;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final KidsModeTaskOrganizer mKidsModeTaskOrganizer;
    private final Optional<BubbleController> mBubblesOptional;
    private final Optional<SplitScreenController> mSplitScreenOptional;
    private final Optional<PipTouchHandler> mPipTouchHandlerOptional;
    private final FullscreenTaskListener mFullscreenTaskListener;
    private final Optional<UnfoldAnimationController> mUnfoldController;
    private final Optional<UnfoldTransitionHandler> mUnfoldTransitionHandler;
    private final Optional<FreeformTaskListener<?>> mFreeformTaskListenerOptional;
    private final ShellExecutor mMainExecutor;
    private final Transitions mTransitions;
    private final StartingWindowController mStartingWindow;
    private final Optional<RecentTasksController> mRecentTasks;
    private final Optional<ActivityEmbeddingController> mActivityEmbeddingOptional;

    private final InitImpl mImpl = new InitImpl();
    // An ordered list of init callbacks to be made once shell is first started
    private final ArrayList<Pair<String, Runnable>> mInitCallbacks = new ArrayList<>();
    private boolean mHasInitialized;

    public ShellInitImpl(
            DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            DragAndDropController dragAndDropController,
            ShellTaskOrganizer shellTaskOrganizer,
            KidsModeTaskOrganizer kidsModeTaskOrganizer,
            Optional<BubbleController> bubblesOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional,
            FullscreenTaskListener fullscreenTaskListener,
            Optional<UnfoldAnimationController> unfoldAnimationController,
            Optional<UnfoldTransitionHandler> unfoldTransitionHandler,
            Optional<FreeformTaskListener<?>> freeformTaskListenerOptional,
            Optional<RecentTasksController> recentTasks,
            Optional<ActivityEmbeddingController> activityEmbeddingOptional,
            Transitions transitions,
            StartingWindowController startingWindow,
            ShellExecutor mainExecutor) {
        mDisplayController = displayController;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mDragAndDropController = dragAndDropController;
        mShellTaskOrganizer = shellTaskOrganizer;
        mKidsModeTaskOrganizer = kidsModeTaskOrganizer;
        mBubblesOptional = bubblesOptional;
        mSplitScreenOptional = splitScreenOptional;
        mFullscreenTaskListener = fullscreenTaskListener;
        mPipTouchHandlerOptional = pipTouchHandlerOptional;
        mUnfoldController = unfoldAnimationController;
        mUnfoldTransitionHandler = unfoldTransitionHandler;
        mFreeformTaskListenerOptional = freeformTaskListenerOptional;
        mRecentTasks = recentTasks;
        mActivityEmbeddingOptional = activityEmbeddingOptional;
        mTransitions = transitions;
        mMainExecutor = mainExecutor;
        mStartingWindow = startingWindow;
    }

    public ShellInit asShellInit() {
        return mImpl;
    }

    private void legacyInit() {
        // Start listening for display and insets changes
        mDisplayController.initialize();
        mDisplayInsetsController.initialize();
        mDisplayImeController.startMonitorDisplays();

        // Setup the shell organizer
        mShellTaskOrganizer.addListenerForType(
                mFullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        mShellTaskOrganizer.initStartingWindow(mStartingWindow);
        mShellTaskOrganizer.registerOrganizer();

        mSplitScreenOptional.ifPresent(SplitScreenController::onOrganizerRegistered);
        mBubblesOptional.ifPresent(BubbleController::initialize);

        // Bind the splitscreen impl to the drag drop controller
        mDragAndDropController.initialize(mSplitScreenOptional);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mTransitions.register(mShellTaskOrganizer);
            mActivityEmbeddingOptional.ifPresent(ActivityEmbeddingController::init);
            mUnfoldTransitionHandler.ifPresent(UnfoldTransitionHandler::init);
            if (mSplitScreenOptional.isPresent() && mPipTouchHandlerOptional.isPresent()) {
                final DefaultMixedHandler mixedHandler = new DefaultMixedHandler(mTransitions,
                        mPipTouchHandlerOptional.get().getTransitionHandler(),
                        mSplitScreenOptional.get().getTransitionHandler());
                // Added at end so that it has highest priority.
                mTransitions.addHandler(mixedHandler);
            }
        }

        // TODO(b/181599115): This should really be the pip controller, but until we can provide the
        // controller instead of the feature interface, can just initialize the touch handler if
        // needed
        mPipTouchHandlerOptional.ifPresent((handler) -> handler.init());

        // Initialize optional freeform
        mFreeformTaskListenerOptional.ifPresent(f ->
                mShellTaskOrganizer.addListenerForType(
                        f, ShellTaskOrganizer.TASK_LISTENER_TYPE_FREEFORM));

        mUnfoldController.ifPresent(UnfoldAnimationController::init);
        mRecentTasks.ifPresent(RecentTasksController::init);

        // Initialize kids mode task organizer
        mKidsModeTaskOrganizer.initialize(mStartingWindow);
    }

    /**
     * Adds a callback to the ordered list of callbacks be made when Shell is first started.  This
     * can be used in class constructors when dagger is used to ensure that the initialization order
     * matches the dependency order.
     */
    public <T extends Object> void addInitCallback(Runnable r, T instance) {
        if (mHasInitialized) {
            if (Build.isDebuggable()) {
                // All callbacks must be added prior to the Shell being initialized
                throw new IllegalArgumentException("Can not add callback after init");
            }
            return;
        }
        final String className = instance.getClass().getSimpleName();
        mInitCallbacks.add(new Pair<>(className, r));
        ProtoLog.v(WM_SHELL_INIT, "Adding init callback for %s", className);
    }

    /**
     * Calls all the init callbacks when the Shell is first starting.
     */
    @VisibleForTesting
    public void init() {
        ProtoLog.v(WM_SHELL_INIT, "Initializing Shell Components: %d", mInitCallbacks.size());
        // Init in order of registration
        for (int i = 0; i < mInitCallbacks.size(); i++) {
            final Pair<String, Runnable> info = mInitCallbacks.get(i);
            final long t1 = SystemClock.uptimeMillis();
            info.second.run();
            final long t2 = SystemClock.uptimeMillis();
            ProtoLog.v(WM_SHELL_INIT, "\t%s took %dms", info.first, (t2 - t1));
        }
        mInitCallbacks.clear();

        // TODO: To be removed
        legacyInit();

        mHasInitialized = true;
    }

    @ExternalThread
    private class InitImpl implements ShellInit {
        @Override
        public void init() {
            try {
                mMainExecutor.executeBlocking(ShellInitImpl.this::init);
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to initialize the Shell in 2s", e);
            }
        }
    }
}
