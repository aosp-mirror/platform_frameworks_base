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

package com.android.wm.shell.transition;

import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellInit;

import java.util.Optional;

/**
 * Handles transitions between the Splitscreen and PIP components.
 */
public class SplitscreenPipMixedHandler {

    private final Optional<SplitScreenController> mSplitScreenOptional;
    private final Optional<PipTouchHandler> mPipTouchHandlerOptional;
    private final Transitions mTransitions;

    public SplitscreenPipMixedHandler(ShellInit shellInit,
            Optional<SplitScreenController> splitScreenControllerOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional,
            Transitions transitions) {
        mSplitScreenOptional = splitScreenControllerOptional;
        mPipTouchHandlerOptional = pipTouchHandlerOptional;
        mTransitions = transitions;
        if (Transitions.ENABLE_SHELL_TRANSITIONS
                && mSplitScreenOptional.isPresent() && mPipTouchHandlerOptional.isPresent()) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        // Special handling for initializing based on multiple components
        final DefaultMixedHandler mixedHandler = new DefaultMixedHandler(mTransitions,
                mPipTouchHandlerOptional.get().getTransitionHandler(),
                mSplitScreenOptional.get().getTransitionHandler());
        // Added at end so that it has highest priority.
        mTransitions.addHandler(mixedHandler);
    }
}
