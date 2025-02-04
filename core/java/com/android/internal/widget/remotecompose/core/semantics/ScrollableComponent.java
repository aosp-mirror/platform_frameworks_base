/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.semantics;

import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.RemoteContext;

/**
 * Interface for components that support scrolling.
 *
 * <p>This interface defines the contract for components that can be scrolled, either horizontally,
 * vertically. It provides methods for checking scroll support, performing scroll operations, and
 * querying the scroll range and current position.
 */
public interface ScrollableComponent extends AccessibilitySemantics {
    int SCROLL_NONE = 0;
    int SCROLL_HORIZONTAL = 1;
    int SCROLL_VERTICAL = 2;

    /** Indicates whether this component supports scrolling by a specified offset. */
    default boolean supportsScrollByOffset() {
        return true;
    }

    /**
     * Scrolls the content by the specified offset.
     *
     * @param offset The amount to scroll by in pixels. Positive values indicate scrolling down or
     *     to the right, while negative values indicate scrolling up or to the left.
     * @return The offset value that was consumed by this component scrolling.
     */
    default int scrollByOffset(RemoteContext context, int offset) {
        return offset;
    }

    /**
     * Show a child with the given ID on the screen, typically scrolling so it's fully on screen.
     *
     * @param childId The ID of the child to check for visibility.
     * @return {@code true} if the child with the given ID could be shown on screen; {@code false}
     *     otherwise.
     */
    default boolean showOnScreen(RemoteContext context, int childId) {
        return false;
    }

    /** Gets the current scroll direction. */
    int scrollDirection();

    /** Represents a range along a scroll axis. */
    default @Nullable ScrollAxisRange getScrollAxisRange() {
        return null;
    }

    /**
     * Represents the state of a scrollable axis, including its current value, maximum value, and
     * whether it can scroll forward or backward.
     */
    class ScrollAxisRange {
        private float mValue;
        private float mMaxValue;
        private boolean mCanScrollForward;
        private boolean mCanScrollBackwards;

        /**
         * Represents the range and scroll capabilities of a single axis (e.g., horizontal or
         * vertical) in a scrollable area.
         */
        public ScrollAxisRange(
                float value, float maxValue, boolean canScrollForward, boolean canScrollBackwards) {
            this.mValue = value;
            this.mMaxValue = maxValue;
            this.mCanScrollForward = canScrollForward;
            this.mCanScrollBackwards = canScrollBackwards;
        }

        /** Returns the current scroll offset held by this scrollable component. */
        public float getmValue() {
            return mValue;
        }

        /** Returns the maximum scroll offset possible by this scrollable component. */
        public float getMaxValue() {
            return mMaxValue;
        }

        /** Returns {@code true} if this scrollable component can scroll forward. */
        public boolean canScrollForward() {
            return mCanScrollForward;
        }

        /** Returns {@code true} if this scrollable component can scroll backwards. */
        public boolean canScrollBackwards() {
            return mCanScrollBackwards;
        }
    }
}
