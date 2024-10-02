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

import android.annotation.Nullable;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the relative placement of extended displays.
 */
class DisplayTopology {
    private static final String TAG = "DisplayTopology";

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
    int mPrimaryDisplayId;

    /**
     * Add a display to the topology.
     * If this is the second display in the topology, it will be placed above the first display.
     * Subsequent displays will be places to the left or right of the second display.
     * @param displayId The ID of the display
     * @param width The width of the display
     * @param height The height of the display
     */
    void addDisplay(int displayId, double width, double height) {
        if (mRoot == null) {
            mRoot = new TreeNode(displayId, width, height, /* position= */ null, /* offset= */ 0);
            mPrimaryDisplayId = displayId;
            Slog.i(TAG, "First display added: " + mRoot);
        } else if (mRoot.mChildren.isEmpty()) {
            // This is the 2nd display. Align the middles of the top and bottom edges.
            double offset = mRoot.mWidth / 2 - width / 2;
            TreeNode display = new TreeNode(displayId, width, height,
                    TreeNode.Position.POSITION_TOP, offset);
            mRoot.mChildren.add(display);
            Slog.i(TAG, "Second display added: " + display + ", parent ID: " + mRoot.mDisplayId);
        } else {
            TreeNode rightMostDisplay = findRightMostDisplay(mRoot, mRoot.mWidth).first;
            TreeNode newDisplay = new TreeNode(displayId, width, height,
                    TreeNode.Position.POSITION_RIGHT, /* offset= */ 0);
            rightMostDisplay.mChildren.add(newDisplay);
            Slog.i(TAG, "Display added: " + newDisplay + ", parent ID: "
                    + rightMostDisplay.mDisplayId);
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

    /**
     * @param display The display from which the search should start.
     * @param xPos The x position of the right edge of that display.
     * @return The display that is the furthest to the right and the x position of the right edge
     * of that display
     */
    private Pair<TreeNode, Double> findRightMostDisplay(TreeNode display, double xPos) {
        Pair<TreeNode, Double> result = new Pair<>(display, xPos);
        for (TreeNode child : display.mChildren) {
            // The x position of the right edge of the child
            double childXPos;
            switch (child.mPosition) {
                case POSITION_LEFT -> childXPos = xPos - display.mWidth;
                case POSITION_TOP, POSITION_BOTTOM ->
                        childXPos = xPos - display.mWidth + child.mOffset + child.mWidth;
                case POSITION_RIGHT -> childXPos = xPos + child.mWidth;
                default -> throw new IllegalStateException("Unexpected value: " + child.mPosition);
            }

            // Recursive call - find the rightmost display starting from the child
            Pair<TreeNode, Double> childResult = findRightMostDisplay(child, childXPos);
            // Check if the one found is further right
            if (childResult.second > result.second) {
                result = new Pair<>(childResult.first, childResult.second);
            }
        }
        return result;
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
        double mWidth;

        /**
         * The height of the display in density-independent pixels (dp).
         */
        @VisibleForTesting
        double mHeight;

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
        double mOffset;

        @VisibleForTesting
        final List<TreeNode> mChildren = new ArrayList<>();

        TreeNode(int displayId, double width, double height, Position position,
                double offset) {
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
