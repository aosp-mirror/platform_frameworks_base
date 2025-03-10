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
            // How many px this display is shifted along the touchingSide, can be negative.
            float offsetPx) {}
}
