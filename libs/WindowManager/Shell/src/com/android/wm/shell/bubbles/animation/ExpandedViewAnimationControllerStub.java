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
package com.android.wm.shell.bubbles.animation;

import com.android.wm.shell.bubbles.BubbleExpandedView;

/**
 * Stub implementation {@link ExpandedViewAnimationController} that does not animate the
 * {@link BubbleExpandedView}
 */
public class ExpandedViewAnimationControllerStub implements ExpandedViewAnimationController {
    @Override
    public void setExpandedView(BubbleExpandedView expandedView) {
    }

    @Override
    public void updateDrag(float distance) {
    }

    @Override
    public void setSwipeVelocity(float velocity) {
    }

    @Override
    public boolean shouldCollapse() {
        return false;
    }

    @Override
    public void animateCollapse(Runnable startStackCollapse, Runnable after) {
    }

    @Override
    public void animateBackToExpanded() {
    }

    @Override
    public void animateForImeVisibilityChange(boolean visible) {
    }

    @Override
    public void reset() {
    }
}
