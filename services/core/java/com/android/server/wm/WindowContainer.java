/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import android.annotation.CallSuper;

import java.util.Comparator;
import java.util.LinkedList;

/**
 * Defines common functionality for classes that can hold windows directly or through their
 * children.
 * The test class is {@link WindowContainerTests} which must be kept up-to-date and ran anytime
 * changes are made to this class.
 */
class WindowContainer {

    // The parent of this window container.
    private WindowContainer mParent = null;

    // List of children for this window container. List is in z-order as the children appear on
    // screen with the top-most window container at the tail of the list.
    protected final LinkedList<WindowContainer> mChildren = new LinkedList();

    protected WindowContainer getParent() {
        return mParent;
    }

    /**
     * Adds the input window container has a child of this container in order based on the input
     * comparator.
     * @param child The window container to add as a child of this window container.
     * @param comparator Comparator to use in determining the position the child should be added to.
     *                   If null, the child will be added to the top.
     */
    @CallSuper
    protected void addChild(WindowContainer child, Comparator<WindowContainer> comparator) {
        child.mParent = this;

        if (mChildren.isEmpty() || comparator == null) {
            mChildren.add(child);
            return;
        }

        final int count = mChildren.size();
        for (int i = 0; i < count; i++) {
            if (comparator.compare(child, mChildren.get(i)) < 0) {
                mChildren.add(i, child);
                return;
            }
        }

        mChildren.add(child);
    }

    /** Removes this window container and its children */
    @CallSuper
    void remove() {
        while (!mChildren.isEmpty()) {
            final WindowContainer child = mChildren.removeLast();
            child.remove();
        }

        if (mParent != null) {
            mParent.mChildren.remove(this);
            mParent = null;
        }
    }

    /** Returns true if this window container has the input child. */
    boolean hasChild(WindowContainer child) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer current = mChildren.get(i);
            if (current == child || current.hasChild(child)) {
                return true;
            }
        }
        return false;
    }
}
