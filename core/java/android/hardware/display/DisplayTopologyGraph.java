/*
 * Copyright 2024 The Android Open Source Project
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

package android.hardware.display;

import static android.hardware.display.DisplayTopology.TreeNode.positionToString;

/**
 * Graph of the displays in {@link android.hardware.display.DisplayTopology} tree.
 *
 * @hide
 */
public record DisplayTopologyGraph(int primaryDisplayId, DisplayNode[] displayNodes) {
    /**
     * Display in the topology
     */
    public record DisplayNode(
            int displayId,
            int density,
            AdjacentDisplay[] adjacentDisplays) {}

    /**
     * Edge to adjacent display
     */
    public record AdjacentDisplay(
            // The logical Id of this adjacent display
            int displayId,
            // Side of the other display which touches this adjacent display.
            @DisplayTopology.TreeNode.Position
            int position,
            // The distance from the top edge of the other display to the top edge of this display
            // (in case of POSITION_LEFT or POSITION_RIGHT) or from the left edge of the parent
            // display to the left edge of this display (in case of POSITION_TOP or
            // POSITION_BOTTOM). The unit used is density-independent pixels (dp).
            float offsetDp) {
        @Override
        public String toString() {
            return "AdjacentDisplay{"
                    + "displayId=" + displayId
                    + ", position=" + positionToString(position)
                    + ", offsetDp=" + offsetDp
                    + '}';
        }
    }
}
