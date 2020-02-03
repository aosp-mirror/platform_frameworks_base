/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import com.android.server.wm.DisplayContent.TaskContainers;

/**
 * Policy that manages DisplayAreas.
 */
public abstract class DisplayAreaPolicy {
    protected final WindowManagerService mWmService;
    protected final DisplayContent mContent;

    /**
     * The root DisplayArea. Attach all DisplayAreas to this area (directly or indirectly).
     */
    protected final DisplayArea.Root mRoot;

    /**
     * The IME container. The IME's windows are automatically added to this container.
     */
    protected final DisplayArea<? extends WindowContainer> mImeContainer;

    /**
     * The Tasks container. Tasks etc. are automatically added to this container.
     */
    protected final TaskContainers mTaskContainers;

    DisplayAreaPolicy(WindowManagerService wmService,
            DisplayContent content, DisplayArea.Root root,
            DisplayArea<? extends WindowContainer> imeContainer, TaskContainers taskContainers) {
        mWmService = wmService;
        mContent = content;
        mRoot = root;
        mImeContainer = imeContainer;
        mTaskContainers = taskContainers;
    }

    /**
     * Called to ask the policy to set up the DisplayArea hierarchy. At a minimum this must:
     *
     * - attach mImeContainer to mRoot (or one of its descendants)
     * - attach mTaskStacks to mRoot (or one of its descendants)
     *
     * Additionally, this is the right place to set up any other DisplayAreas as desired.
     */
    public abstract void attachDisplayAreas();

    /**
     * Called to ask the policy to attach the given WindowToken to the DisplayArea hierarchy.
     *
     * This must attach the token to mRoot (or one of its descendants).
     */
    public abstract void addWindow(WindowToken token);

    /**
     * Default policy that has no special features.
     */
    public static class Default extends DisplayAreaPolicy {

        public Default(WindowManagerService wmService, DisplayContent content,
                DisplayArea.Root root,
                DisplayArea<? extends WindowContainer> imeContainer,
                TaskContainers taskContainers) {
            super(wmService, content, root, imeContainer, taskContainers);
        }

        private final DisplayArea.Tokens mBelow = new DisplayArea.Tokens(mWmService,
                DisplayArea.Type.BELOW_TASKS, "BelowTasks");
        private final DisplayArea<DisplayArea> mAbove = new DisplayArea<>(mWmService,
                DisplayArea.Type.ABOVE_TASKS, "AboveTasks");
        private final DisplayArea.Tokens mAboveBelowIme = new DisplayArea.Tokens(mWmService,
                DisplayArea.Type.ABOVE_TASKS, "AboveTasksBelowIme");
        private final DisplayArea.Tokens mAboveAboveIme = new DisplayArea.Tokens(mWmService,
                DisplayArea.Type.ABOVE_TASKS, "AboveTasksAboveIme");

        @Override
        public void attachDisplayAreas() {
            mRoot.addChild(mBelow, 0);
            mRoot.addChild(mTaskContainers, 1);
            mRoot.addChild(mAbove, 2);

            mAbove.addChild(mAboveBelowIme, 0);
            mAbove.addChild(mImeContainer, 1);
            mAbove.addChild(mAboveAboveIme, 2);
        }

        @Override
        public void addWindow(WindowToken token) {
            switch (DisplayArea.Type.typeOf(token)) {
                case ABOVE_TASKS:
                    if (token.getWindowLayerFromType()
                            < mWmService.mPolicy.getWindowLayerFromTypeLw(TYPE_INPUT_METHOD)) {
                        mAboveBelowIme.addChild(token);
                    } else {
                        mAboveAboveIme.addChild(token);
                    }
                    break;
                case BELOW_TASKS:
                    mBelow.addChild(token);
                    break;
                default:
                    throw new IllegalArgumentException("don't know how to sort " + token);
            }
        }
    }
}
