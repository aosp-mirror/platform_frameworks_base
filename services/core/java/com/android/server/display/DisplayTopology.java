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

package com.android.server.display;

import static com.android.server.display.DisplayTopology.TreeNode.Position.POSITION_BOTTOM;
import static com.android.server.display.DisplayTopology.TreeNode.Position.POSITION_LEFT;
import static com.android.server.display.DisplayTopology.TreeNode.Position.POSITION_TOP;
import static com.android.server.display.DisplayTopology.TreeNode.Position.POSITION_RIGHT;

import android.annotation.Nullable;
import android.graphics.RectF;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Represents the relative placement of extended displays.
 * Does not support concurrent calls, so a lock should be held when calling into this class.
 */
class DisplayTopology {
    private static final String TAG = "DisplayTopology";
    private static final float EPSILON = 0.0001f;

    /**
     * The topology tree
     */
    @Nullable
    @VisibleForTesting
    TreeNode mRoot;

    /**
     * The logical display ID of the primary display that will show certain UI elements.
     * This is not necessarily the same as the default display.
     */
    @VisibleForTesting
    int mPrimaryDisplayId = Display.INVALID_DISPLAY;

    /**
     * Add a display to the topology.
     * If this is the second display in the topology, it will be placed above the first display.
     * Subsequent displays will be places to the left or right of the second display.
     * @param displayId The logical display ID
     * @param width The width of the display
     * @param height The height of the display
     */
    void addDisplay(int displayId, float width, float height) {
        addDisplay(displayId, width, height, /* shouldLog= */ true);
    }

    /**
     * Remove a display from the topology.
     * The default topology is created from the remaining displays, as if they were reconnected
     * one by one.
     * @param displayId The logical display ID
     */
    void removeDisplay(int displayId) {
        if (findDisplay(displayId, mRoot) == null) {
            return;
        }
        Queue<TreeNode> queue = new ArrayDeque<>();
        queue.add(mRoot);
        mRoot = null;
        while (!queue.isEmpty()) {
            TreeNode node = queue.poll();
            if (node.mDisplayId != displayId) {
                addDisplay(node.mDisplayId, node.mWidth, node.mHeight, /* shouldLog= */ false);
            }
            queue.addAll(node.mChildren);
        }
        if (mPrimaryDisplayId == displayId) {
            if (mRoot != null) {
                mPrimaryDisplayId = mRoot.mDisplayId;
            } else {
                mPrimaryDisplayId = Display.INVALID_DISPLAY;
            }
            Slog.i(TAG,  "Primary display with ID " + displayId
                    + " removed, new primary display: " + mPrimaryDisplayId);
        } else {
            Slog.i(TAG, "Display with ID " + displayId + " removed");
        }
    }

    /**
     * Print the object's state and debug information into the given stream.
     * @param pw The stream to dump information to.
     */
    void dump(PrintWriter pw) {
        pw.println("DisplayTopology:");
        pw.println("--------------------");
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.increaseIndent();

        ipw.println("mPrimaryDisplayId: " + mPrimaryDisplayId);

        ipw.println("Topology tree:");
        if (mRoot != null) {
            ipw.increaseIndent();
            mRoot.dump(ipw);
            ipw.decreaseIndent();
        }
    }

    private void addDisplay(int displayId, float width, float height, boolean shouldLog) {
        if (findDisplay(displayId, mRoot) != null) {
            throw new IllegalArgumentException(
                    "DisplayTopology: attempting to add a display that already exists");
        }
        if (mRoot == null) {
            mRoot = new TreeNode(displayId, width, height, /* position= */ null, /* offset= */ 0);
            mPrimaryDisplayId = displayId;
            if (shouldLog) {
                Slog.i(TAG, "First display added: " + mRoot);
            }
        } else if (mRoot.mChildren.isEmpty()) {
            // This is the 2nd display. Align the middles of the top and bottom edges.
            float offset = mRoot.mWidth / 2 - width / 2;
            TreeNode display = new TreeNode(displayId, width, height, POSITION_TOP, offset);
            mRoot.mChildren.add(display);
            if (shouldLog) {
                Slog.i(TAG, "Second display added: " + display + ", parent ID: "
                        + mRoot.mDisplayId);
            }
        } else {
            TreeNode rightMostDisplay = findRightMostDisplay(mRoot, mRoot.mWidth).first;
            TreeNode newDisplay = new TreeNode(displayId, width, height, POSITION_RIGHT,
                    /* offset= */ 0);
            rightMostDisplay.mChildren.add(newDisplay);
            if (shouldLog) {
                Slog.i(TAG, "Display added: " + newDisplay + ", parent ID: "
                        + rightMostDisplay.mDisplayId);
            }
        }
    }

    /**
     * @param display The display from which the search should start.
     * @param xPos The x position of the right edge of that display.
     * @return The display that is the furthest to the right and the x position of the right edge
     * of that display
     */
    private static Pair<TreeNode, Float> findRightMostDisplay(TreeNode display, float xPos) {
        Pair<TreeNode, Float> result = new Pair<>(display, xPos);
        for (TreeNode child : display.mChildren) {
            // The x position of the right edge of the child
            float childXPos;
            switch (child.mPosition) {
                case POSITION_LEFT -> childXPos = xPos - display.mWidth;
                case POSITION_TOP, POSITION_BOTTOM ->
                        childXPos = xPos - display.mWidth + child.mOffset + child.mWidth;
                case POSITION_RIGHT -> childXPos = xPos + child.mWidth;
                default -> throw new IllegalStateException("Unexpected value: " + child.mPosition);
            }

            // Recursive call - find the rightmost display starting from the child
            Pair<TreeNode, Float> childResult = findRightMostDisplay(child, childXPos);
            // Check if the one found is further right
            if (childResult.second > result.second) {
                result = new Pair<>(childResult.first, childResult.second);
            }
        }
        return result;
    }

    @Nullable
    private static TreeNode findDisplay(int displayId, TreeNode startingNode) {
        if (startingNode == null) {
            return null;
        }
        if (startingNode.mDisplayId == displayId) {
            return startingNode;
        }
        for (TreeNode child : startingNode.mChildren) {
            TreeNode display = findDisplay(displayId, child);
            if (display != null) {
                return display;
            }
        }
        return null;
    }

    /**
     * Get information about the topology that will be used for the normalization algorithm.
     * Assigns origins to each display to compute the bounds.
     * @param bounds The map where the bounds of each display will be put
     * @param depths The map where the depths of each display in the tree will be put
     * @param parents The map where the parent of each display will be put
     * @param display The starting node
     * @param x The starting x position
     * @param y The starting y position
     * @param depth The starting depth
     */
    private static void getInfo(Map<TreeNode, RectF> bounds, Map<TreeNode, Integer> depths,
            Map<TreeNode, TreeNode> parents, TreeNode display, float x, float y, int depth) {
        bounds.put(display, new RectF(x, y, x + display.mWidth, y + display.mHeight));
        depths.put(display, depth);
        for (TreeNode child : display.mChildren) {
            parents.put(child, display);
            if (child.mPosition == POSITION_LEFT) {
                getInfo(bounds, depths, parents, child, x - child.mWidth, y + child.mOffset,
                        depth + 1);
            } else if (child.mPosition == POSITION_RIGHT) {
                getInfo(bounds, depths, parents, child, x + display.mWidth, y + child.mOffset,
                        depth + 1);
            } else if (child.mPosition == POSITION_TOP) {
                getInfo(bounds, depths, parents, child, x + child.mOffset, y - child.mHeight,
                        depth + 1);
            } else if (child.mPosition == POSITION_BOTTOM) {
                getInfo(bounds, depths, parents, child, x + child.mOffset, y + display.mHeight,
                        depth + 1);
            }
        }
    }

    /**
     * Update the topology to remove any overlaps between displays.
     */
    @VisibleForTesting
    void normalize() {
        if (mRoot == null) {
            return;
        }
        Map<TreeNode, RectF> bounds = new HashMap<>();
        Map<TreeNode, Integer> depths = new HashMap<>();
        Map<TreeNode, TreeNode> parents = new HashMap<>();
        getInfo(bounds, depths, parents, mRoot, /* x= */ 0, /* y= */ 0, /* depth= */ 0);

        // Sort the displays first by their depth in the tree, then by the distance of their top
        // left point from the root display's origin (0, 0). This way we process the displays
        // starting at the root and we push out a display if necessary.
        Comparator<TreeNode> comparator = (d1, d2) -> {
            if (d1 == d2) {
                return 0;
            }

            int compareDepths = Integer.compare(depths.get(d1), depths.get(d2));
            if (compareDepths != 0) {
                return compareDepths;
            }

            RectF bounds1 = bounds.get(d1);
            RectF bounds2 = bounds.get(d2);
            return Double.compare(Math.hypot(bounds1.left, bounds1.top),
                    Math.hypot(bounds2.left, bounds2.top));
        };
        List<TreeNode> displays = new ArrayList<>(bounds.keySet());
        displays.sort(comparator);

        for (int i = 1; i < displays.size(); i++) {
            TreeNode targetDisplay = displays.get(i);
            TreeNode lastIntersectingSourceDisplay = null;
            float lastOffsetX = 0;
            float lastOffsetY = 0;

            for (int j = 0; j < i; j++) {
                TreeNode sourceDisplay = displays.get(j);
                RectF sourceBounds = bounds.get(sourceDisplay);
                RectF targetBounds = bounds.get(targetDisplay);

                if (!RectF.intersects(sourceBounds, targetBounds)) {
                    continue;
                }

                // Find the offset by which to move the display. Pick the smaller one among the x
                // and y axes.
                float offsetX = targetBounds.left >= 0
                        ? sourceBounds.right - targetBounds.left
                        : sourceBounds.left - targetBounds.right;
                float offsetY = targetBounds.top >= 0
                        ? sourceBounds.bottom - targetBounds.top
                        : sourceBounds.top - targetBounds.bottom;
                if (Math.abs(offsetX) <= Math.abs(offsetY)) {
                    targetBounds.left += offsetX;
                    targetBounds.right += offsetX;
                    // We need to also update the offset in the tree
                    if (targetDisplay.mPosition == POSITION_TOP
                            || targetDisplay.mPosition == POSITION_BOTTOM) {
                        targetDisplay.mOffset += offsetX;
                    }
                    offsetY = 0;
                } else {
                    targetBounds.top += offsetY;
                    targetBounds.bottom += offsetY;
                    // We need to also update the offset in the tree
                    if (targetDisplay.mPosition == POSITION_LEFT
                            || targetDisplay.mPosition == POSITION_RIGHT) {
                        targetDisplay.mOffset += offsetY;
                    }
                    offsetX = 0;
                }

                lastIntersectingSourceDisplay = sourceDisplay;
                lastOffsetX = offsetX;
                lastOffsetY = offsetY;
            }

            // Now re-parent the target display to the last intersecting source display if it no
            // longer touches its parent.
            if (lastIntersectingSourceDisplay == null) {
                // There was no overlap.
                continue;
            }
            TreeNode parent = parents.get(targetDisplay);
            if (parent == lastIntersectingSourceDisplay) {
                // The displays are moved in such a way that they're adjacent to the intersecting
                // display. If the last intersecting display happens to be the parent then we
                // already know that the display is adjacent to its parent.
                continue;
            }

            RectF childBounds = bounds.get(targetDisplay);
            RectF parentBounds = bounds.get(parent);
            // Check that the edges are on the same line
            boolean areTouching = switch (targetDisplay.mPosition) {
                case POSITION_LEFT -> floatEquals(parentBounds.left, childBounds.right);
                case POSITION_RIGHT -> floatEquals(parentBounds.right, childBounds.left);
                case POSITION_TOP -> floatEquals(parentBounds.top, childBounds.bottom);
                case POSITION_BOTTOM -> floatEquals(parentBounds.bottom, childBounds.top);
            };
            // Check that the offset is within bounds
            areTouching &= switch (targetDisplay.mPosition) {
                case POSITION_LEFT, POSITION_RIGHT ->
                        childBounds.bottom + EPSILON >= parentBounds.top
                                && childBounds.top <= parentBounds.bottom + EPSILON;
                case POSITION_TOP, POSITION_BOTTOM ->
                        childBounds.right + EPSILON >= parentBounds.left
                                && childBounds.left <= parentBounds.right + EPSILON;
            };

            if (!areTouching) {
                // Re-parent the display.
                parent.mChildren.remove(targetDisplay);
                RectF lastIntersectingSourceDisplayBounds =
                        bounds.get(lastIntersectingSourceDisplay);
                lastIntersectingSourceDisplay.mChildren.add(targetDisplay);

                if (lastOffsetX != 0) {
                    targetDisplay.mPosition = lastOffsetX > 0 ? POSITION_RIGHT : POSITION_LEFT;
                    targetDisplay.mOffset =
                            childBounds.top - lastIntersectingSourceDisplayBounds.top;
                } else if (lastOffsetY != 0) {
                    targetDisplay.mPosition = lastOffsetY > 0 ? POSITION_BOTTOM : POSITION_TOP;
                    targetDisplay.mOffset =
                            childBounds.left - lastIntersectingSourceDisplayBounds.left;
                }
            }
        }
    }

    /**
     * Tests whether two brightness float values are within a small enough tolerance
     * of each other.
     * @param a first float to compare
     * @param b second float to compare
     * @return whether the two values are within a small enough tolerance value
     */
    public static boolean floatEquals(float a, float b) {
        return a == b || Float.isNaN(a) && Float.isNaN(b) || Math.abs(a - b) < EPSILON;
    }

    @VisibleForTesting
    static class TreeNode {

        /**
         * The logical display ID
         */
        @VisibleForTesting
        final int mDisplayId;

        /**
         * The width of the display in density-independent pixels (dp).
         */
        @VisibleForTesting
        float mWidth;

        /**
         * The height of the display in density-independent pixels (dp).
         */
        @VisibleForTesting
        float mHeight;

        /**
         * The position of this display relative to its parent.
         */
        @VisibleForTesting
        Position mPosition;

        /**
         * The distance from the top edge of the parent display to the top edge of this display (in
         * case of POSITION_LEFT or POSITION_RIGHT) or from the left edge of the parent display
         * to the left edge of this display (in case of POSITION_TOP or POSITION_BOTTOM). The unit
         * used is density-independent pixels (dp).
         */
        @VisibleForTesting
        float mOffset;

        @VisibleForTesting
        final List<TreeNode> mChildren = new ArrayList<>();

        TreeNode(int displayId, float width, float height, Position position, float offset) {
            mDisplayId = displayId;
            mWidth = width;
            mHeight = height;
            mPosition = position;
            mOffset = offset;
        }

        /**
         * Print the object's state and debug information into the given stream.
         * @param ipw The stream to dump information to.
         */
        void dump(IndentingPrintWriter ipw) {
            ipw.println(this);
            ipw.increaseIndent();
            for (TreeNode child : mChildren) {
                child.dump(ipw);
            }
            ipw.decreaseIndent();
        }

        @Override
        public String toString() {
            return "Display {id=" + mDisplayId + ", width=" + mWidth + ", height=" + mHeight
                    + ", position=" + mPosition + ", offset=" + mOffset + "}";
        }

        @VisibleForTesting
        enum Position {
            POSITION_LEFT, POSITION_TOP, POSITION_RIGHT, POSITION_BOTTOM
        }
    }
}
