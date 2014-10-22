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

package com.android.systemui.recents.model;

import android.graphics.Rect;

import java.util.ArrayList;


/**
 * The full recents space is partitioned using a BSP into various nodes that define where task
 * stacks should be placed.
 */
public class SpaceNode {
    /* BSP node callbacks */
    public interface SpaceNodeCallbacks {
        /** Notifies when a node is added */
        public void onSpaceNodeAdded(SpaceNode node);
        /** Notifies when a node is measured */
        public void onSpaceNodeMeasured(SpaceNode node, Rect rect);
    }

    SpaceNode mStartNode;
    SpaceNode mEndNode;

    TaskStack mStack;

    public SpaceNode() {
        // Do nothing
    }

    /** Sets the current stack for this space node */
    public void setStack(TaskStack stack) {
        mStack = stack;
    }

    /** Returns the task stack (not null if this is a leaf) */
    TaskStack getStack() {
        return mStack;
    }

    /** Returns whether there are any tasks in any stacks below this node. */
    public boolean hasTasks() {
        return (mStack.getTaskCount() > 0) ||
                (mStartNode != null && mStartNode.hasTasks()) ||
                (mEndNode != null && mEndNode.hasTasks());
    }

    /** Returns whether this is a leaf node */
    boolean isLeafNode() {
        return (mStartNode == null) && (mEndNode == null);
    }

    /** Returns all the descendent task stacks */
    private void getStacksRec(ArrayList<TaskStack> stacks) {
        if (isLeafNode()) {
            stacks.add(mStack);
        } else {
            mStartNode.getStacksRec(stacks);
            mEndNode.getStacksRec(stacks);
        }
    }
    public ArrayList<TaskStack> getStacks() {
        ArrayList<TaskStack> stacks = new ArrayList<TaskStack>();
        getStacksRec(stacks);
        return stacks;
    }
}
