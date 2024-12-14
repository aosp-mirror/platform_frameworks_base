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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * This class manages the relative placement (topology) of extended displays. It is responsible for
 * updating and persisting the topology.
 */
class DisplayTopologyCoordinator {

    /**
     * The topology tree
     */
    @Nullable
    private TopologyTreeNode mRoot;

    /**
     * The logical display ID of the primary display that will show certain UI elements.
     * This is not necessarily the same as the default display.
     */
    private int mPrimaryDisplayId;

    /**
     * Print the object's state and debug information into the given stream.
     * @param pw The stream to dump information to.
     */
    public void dump(PrintWriter pw) {
        pw.println("DisplayTopologyCoordinator:");
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

    private static class TopologyTreeNode {

        /**
         * The logical display ID
         */
        private int mDisplayId;

        private final List<TopologyTreeNode> mChildren = new ArrayList<>();

        /**
         * The position of this display relative to its parent.
         */
        private Position mPosition;

        /**
         * The distance from the top edge of the parent display to the top edge of this display (in
         * case of POSITION_LEFT or POSITION_RIGHT) or from the left edge of the parent display
         * to the left edge of this display (in case of POSITION_TOP or POSITION_BOTTOM). The unit
         * used is density-independent pixels (dp).
         */
        private double mOffset;

        /**
         * Print the object's state and debug information into the given stream.
         * @param ipw The stream to dump information to.
         */
        void dump(IndentingPrintWriter ipw) {
            ipw.println("Display {id=" + mDisplayId + ", position=" + mPosition
                    + ", offset=" + mOffset + "}");
            ipw.increaseIndent();
            for (TopologyTreeNode child : mChildren) {
                child.dump(ipw);
            }
            ipw.decreaseIndent();
        }

        private enum Position {
            POSITION_LEFT, POSITION_TOP, POSITION_RIGHT, POSITION_BOTTOM
        }
    }
}
