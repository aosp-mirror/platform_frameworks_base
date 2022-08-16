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

import com.android.wm.shell.apppairs.AppPairsController;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.freeform.FreeformTaskListener;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.fullscreen.FullscreenUnfoldController;
import com.android.wm.shell.kidsmode.KidsModeTaskOrganizer;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.unfold.UnfoldTransitionHandler;

import java.util.Optional;

/**
 * The entry point implementation into the shell for initializing shell internal state.
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
    private final Optional<AppPairsController> mAppPairsOptional;
    private final Optional<PipTouchHandler> mPipTouchHandlerOptional;
    private final FullscreenTaskListener mFullscreenTaskListener;
    private final Optional<FullscreenUnfoldController> mFullscreenUnfoldController;
    private final Optional<UnfoldTransitionHandler> mUnfoldTransitionHandler;
    private final Optional<FreeformTaskListener> mFreeformTaskListenerOptional;
    private final ShellExecutor mMainExecutor;
    private final Transitions mTransitions;
    private final StartingWindowController mStartingWindow;
    private final Optional<RecentTasksController> mRecentTasks;

    private final InitImpl mImpl = new InitImpl();

    public ShellInitImpl(
            DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            DragAndDropController dragAndDropController,
            ShellTaskOrganizer shellTaskOrganizer,
            KidsModeTaskOrganizer kidsModeTaskOrganizer,
            Optional<BubbleController> bubblesOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<AppPairsController> appPairsOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional,
            FullscreenTaskListener fullscreenTaskListener,
            Optional<FullscreenUnfoldController> fullscreenUnfoldTransitionController,
            Optional<UnfoldTransitionHandler> unfoldTransitionHandler,
            Optional<FreeformTaskListener> freeformTaskListenerOptional,
            Optional<RecentTasksController> recentTasks,
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
        mAppPairsOptional = appPairsOptional;
        mFullscreenTaskListener = fullscreenTaskListener;
        mPipTouchHandlerOptional = pipTouchHandlerOptional;
        mFullscreenUnfoldController = fullscreenUnfoldTransitionController;
        mUnfoldTransitionHandler = unfoldTransitionHandler;
        mFreeformTaskListenerOptional = freeformTaskListenerOptional;
        mRecentTasks = recentTasks;
        mTransitions = transitions;
        mMainExecutor = mainExecutor;
        mStartingWindow = startingWindow;
    }

    public ShellInit asShellInit() {
        return mImpl;
    }

    private void init() {
        // Start listening for display and insets changes
        mDisplayController.initialize();
        mDisplayInsetsController.initialize();
        mDisplayImeController.startMonitorDisplays();

        // Setup the shell organizer
        mShellTaskOrganizer.addListenerForType(
                mFullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        mShellTaskOrganizer.initStartingWindow(mStartingWindow);
        mShellTaskOrganizer.registerOrganizer();

        mAppPairsOptional.ifPresent(AppPairsController::onOrganizerRegistered);
        mSplitScreenOptional.ifPresent(SplitScreenController::onOrganizerRegistered);
        mBubblesOptional.ifPresent(BubbleController::initialize);

        // Bind the splitscreen impl to the drag drop controller
        mDragAndDropController.initialize(mSplitScreenOptional);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mTransitions.register(mShellTaskOrganizer);
            mUnfoldTransitionHandler.ifPresent(UnfoldTransitionHandler::init);
        }

        // TODO(b/181599115): This should really be the pip controller, but until we can provide the
        // controller instead of the feature interface, can just initialize the touch handler if
        // needed
        mPipTouchHandlerOptional.ifPresent((handler) -> handler.init());

        // Initialize optional freeform
        mFreeformTaskListenerOptional.ifPresent(f ->
                mShellTaskOrganizer.addListenerForType(
                        f, ShellTaskOrganizer.TASK_LISTENER_TYPE_FREEFORM));

        mFullscreenUnfoldController.ifPresent(FullscreenUnfoldController::init);
        mRecentTasks.ifPresent(RecentTasksController::init);

        // Initialize kids mode task organizer
        mKidsModeTaskOrganizer.initialize(mStartingWindow);
    }

    @ExternalThread
    private class InitImpl implements ShellInit {
        @Override
        public void init() {
            try {
                mMainExecutor.executeBlocking(() -> ShellInitImpl.this.init());
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to initialize the Shell in 2s", e);
            }
        }
    }
}
