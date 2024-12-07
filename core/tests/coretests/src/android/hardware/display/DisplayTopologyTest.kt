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

package android.hardware.display

import android.graphics.PointF
import android.graphics.RectF
import android.hardware.display.DisplayTopology.TreeNode.POSITION_BOTTOM
import android.hardware.display.DisplayTopology.TreeNode.POSITION_LEFT
import android.hardware.display.DisplayTopology.TreeNode.POSITION_TOP
import android.hardware.display.DisplayTopology.TreeNode.POSITION_RIGHT
import android.view.Display
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayTopologyTest {
    private var topology = DisplayTopology()

    @Test
    fun addOneDisplay() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)

        assertThat(topology.primaryDisplayId).isEqualTo(displayId)
        verifyDisplay(topology.root!!, displayId, width, height, noOfChildren = 0)
    }

    @Test
    fun addTwoDisplays() {
        val displayId1 = 1
        val width1 = 800f
        val height1 = 600f

        val displayId2 = 2
        val width2 = 1000f
        val height2 = 1500f

        topology.addDisplay(displayId1, width1, height1)
        topology.addDisplay(displayId2, width2, height2)

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        val display1 = topology.root!!
        verifyDisplay(display1, displayId1, width1, height1, noOfChildren = 1)
        verifyDisplay(display1.children[0], displayId2, width2, height2, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 0)
    }

    @Test
    fun addManyDisplays() {
        val displayId1 = 1
        val width1 = 800f
        val height1 = 600f

        val displayId2 = 2
        val width2 = 1000f
        val height2 = 1500f

        topology.addDisplay(displayId1, width1, height1)
        topology.addDisplay(displayId2, width2, height2)

        val noOfDisplays = 30
        for (i in 3..noOfDisplays) {
            topology.addDisplay(/* displayId= */ i, width1, height1)
        }

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        val display1 = topology.root!!
        verifyDisplay(display1, displayId1, width1, height1, noOfChildren = 1)

        val display2 = display1.children[0]
        verifyDisplay(display1.children[0], displayId2, width2, height2, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 1)

        var display = display2
        for (i in 3..noOfDisplays) {
            display = display.children[0]
            // The last display should have no children
            verifyDisplay(display, id = i, width1, height1, POSITION_RIGHT, offset = 0f,
                noOfChildren = if (i < noOfDisplays) 1 else 0)
        }
    }

    @Test
    fun removeDisplays() {
        val displayId1 = 1
        val width1 = 800f
        val height1 = 600f

        val displayId2 = 2
        val width2 = 1000f
        val height2 = 1500f

        topology.addDisplay(displayId1, width1, height1)
        topology.addDisplay(displayId2, width2, height2)

        val noOfDisplays = 30
        for (i in 3..noOfDisplays) {
            topology.addDisplay(/* displayId= */ i, width1, height1)
        }

        var removedDisplays = arrayOf(20)
        topology.removeDisplay(20)

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        var display1 = topology.root!!
        verifyDisplay(display1, displayId1, width1, height1, noOfChildren = 1)

        var display2 = display1.children[0]
        verifyDisplay(display2, displayId2, width2, height2, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 1)

        var display = display2
        for (i in 3..noOfDisplays) {
            if (i in removedDisplays) {
                continue
            }
            display = display.children[0]
            // The last display should have no children
            verifyDisplay(display, id = i, width1, height1, POSITION_RIGHT, offset = 0f,
                noOfChildren = if (i < noOfDisplays) 1 else 0)
        }

        topology.removeDisplay(22)
        removedDisplays += 22
        topology.removeDisplay(23)
        removedDisplays += 23
        topology.removeDisplay(25)
        removedDisplays += 25

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        display1 = topology.root!!
        verifyDisplay(display1, displayId1, width1, height1, noOfChildren = 1)

        display2 = display1.children[0]
        verifyDisplay(display2, displayId2, width2, height2, POSITION_TOP,
            offset = width1 / 2 - width2 / 2, noOfChildren = 1)

        display = display2
        for (i in 3..noOfDisplays) {
            if (i in removedDisplays) {
                continue
            }
            display = display.children[0]
            // The last display should have no children
            verifyDisplay(display, id = i, width1, height1, POSITION_RIGHT, offset = 0f,
                noOfChildren = if (i < noOfDisplays) 1 else 0)
        }
    }

    @Test
    fun removeAllDisplays() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)
        topology.removeDisplay(displayId)

        assertThat(topology.primaryDisplayId).isEqualTo(Display.INVALID_DISPLAY)
        assertThat(topology.root).isNull()
    }

    @Test
    fun removeDisplayThatDoesNotExist() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)
        topology.removeDisplay(3)

        assertThat(topology.primaryDisplayId).isEqualTo(displayId)
        verifyDisplay(topology.root!!, displayId, width, height, noOfChildren = 0)
    }

    @Test
    fun removePrimaryDisplay() {
        val displayId1 = 1
        val displayId2 = 2
        val width = 800f
        val height = 600f

        topology = DisplayTopology(/* root= */ null, displayId2)
        topology.addDisplay(displayId1, width, height)
        topology.addDisplay(displayId2, width, height)
        topology.removeDisplay(displayId2)

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)
        verifyDisplay(topology.root!!, displayId1, width, height, noOfChildren = 0)
    }

    @Test
    fun normalization_clampsOffsets() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 800f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_LEFT, /* offset= */ -300f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_TOP, /* offset= */ 1000f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 2)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 600f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay1.children[1]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_LEFT,
            offset = -200f, noOfChildren = 0)

        val actualDisplay4 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_TOP,
            offset = 600f, noOfChildren = 0)
    }

    @Test
    fun normalization_noOverlaps_leavesTopologyUnchanged() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 2)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay1.children[1]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 400f, noOfChildren = 0)

        val actualDisplay4 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 0)
    }

    @Test
    fun normalization_moveDisplayWithoutReparenting() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 10f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        // Display 3 becomes a child of display 2. Display 4 gets moved without changing its parent.
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 1)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 2)

        val actualDisplay3 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 10f, noOfChildren = 0)

        val actualDisplay4 = actualDisplay2.children[1]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 210f, noOfChildren = 0)
    }

    @Test
    fun normalization_moveDisplayWithoutReparenting_offsetOutOfBounds() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 50f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 10f)
        display1.addChild(display3)

        topology = DisplayTopology(display1, primaryDisplayId)
        // Display 3 gets moved and its left side is still on the same line as the right side
        // of Display 1, but it no longer touches it (the offset is out of bounds), so Display 2
        // becomes its new parent.
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 50f, noOfChildren = 1)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_BOTTOM,
            offset = 0f, noOfChildren = 0)
    }

    @Test
    fun normalization_moveAndReparentDisplay() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        topology.normalize()

        assertThat(topology.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 1)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 400f, noOfChildren = 1)

        val actualDisplay4 = actualDisplay3.children[0]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_RIGHT,
            offset = -400f, noOfChildren = 0)
    }

    @Test
    fun rearrange_twoDisplays() {
        val root = rearrangeRects(
            // Arrange in staggered manner, connected vertically.
            RectF(100f, 100f, 250f, 200f),
            RectF(150f, 200f, 300f, 300f),
        )

        verifyDisplay(root, id = 0, width = 150f, height = 100f, noOfChildren = 1)
        val node = root.children[0]
        verifyDisplay(
                node, id = 1, width = 150f, height = 100f, POSITION_BOTTOM, offset = 50f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_reverseOrderOfSeveralDisplays() {
        val root = rearrangeRects(
            RectF(0f, 0f, 150f, 100f),
            RectF(-150f, 0f, 0f, 100f),
            RectF(-300f, 0f, -150f, 100f),
            RectF(-450f, 0f, -300f, 100f),
        )

        verifyDisplay(root, id = 0, width = 150f, height = 100f, noOfChildren = 1)
        var node = root.children[0]
        verifyDisplay(
                node, id = 1, width = 150f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 2, width = 150f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 3, width = 150f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_crossWithRootInCenter() {
        val root = rearrangeRects(
            RectF(0f, 0f, 150f, 100f),
            RectF(-150f, 0f, 0f, 100f),
            RectF(0f, -100f, 150f, 0f),
            RectF(150f, 0f, 300f, 100f),
            RectF(0f, 100f, 150f, 200f),
        )

        verifyDisplay(root, id = 0, width = 150f, height = 100f, noOfChildren = 4)
        verifyDisplay(
                root.children[0], id = 1, width = 150f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 0)
        verifyDisplay(
                root.children[1], id = 2, width = 150f, height = 100f, POSITION_TOP, offset = 0f,
                noOfChildren = 0)
        verifyDisplay(
                root.children[2], id = 3, width = 150f, height = 100f, POSITION_RIGHT, offset = 0f,
                noOfChildren = 0)
        verifyDisplay(
                root.children[3], id = 4, width = 150f, height = 100f, POSITION_BOTTOM, offset = 0f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_elbowArrangementDoesNotUseCornerAdjacency1() {
        val root = rearrangeRects(
            //     2
            //     |
            // 0 - 1

            RectF(0f, 0f, 100f, 100f),
            RectF(100f, 0f, 200f, 100f),
            RectF(100f, -100f, 200f, 0f),
        )

        verifyDisplay(root, id = 0, width = 100f, height = 100f, noOfChildren = 1)
        var node = root.children[0]
        verifyDisplay(
                node, id = 1, width = 100f, height = 100f, POSITION_RIGHT, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 2, width = 100f, height = 100f, POSITION_TOP,
                offset = 0f, noOfChildren = 0)
    }

    @Test
    fun rearrange_elbowArrangementDoesNotUseCornerAdjacency2() {
        val root = rearrangeRects(
            //     0
            //     |
            //     1
            //     |
            // 3 - 2

            RectF(0f, 0f, 100f, 100f),
            RectF(0f, 100f, 100f, 200f),
            RectF(0f, 200f, 100f, 300f),
            RectF(-100f, 200f, 0f, 300f),
        )

        verifyDisplay(root, id = 0, width = 100f, height = 100f, noOfChildren = 1)
        var node = root.children[0]
        verifyDisplay(
                node, id = 1, width = 100f, height = 100f, POSITION_BOTTOM, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 2, width = 100f, height = 100f, POSITION_BOTTOM, offset = 0f,
                noOfChildren = 1)
        node = node.children[0]
        verifyDisplay(
                node, id = 3, width = 100f, height = 100f, POSITION_LEFT, offset = 0f,
                noOfChildren = 0)
    }

    @Test
    fun rearrange_useLargerEdge() {
        val root = rearrangeRects(
            // 444111
            // 444111
            // 444111
            //   000222
            //   000222
            //   000222
            //     333
            //     333
            //     333
            RectF(20f, 30f, 50f, 60f),
            RectF(30f, 0f, 60f, 30f),
            RectF(50f, 30f, 80f, 60f),
            RectF(40f, 60f, 70f, 90f),
            RectF(0f, 0f, 30f, 30f),
        )

        verifyDisplay(root, id = 0, width = 30f, height = 30f, noOfChildren = 2)
        verifyDisplay(
                root.children[0], id = 1, width = 30f, height = 30f, POSITION_TOP,
                offset = 10f, noOfChildren = 1)
        verifyDisplay(
                root.children[0].children[0], id = 4, width = 30f, height = 30f, POSITION_LEFT,
                offset = 0f, noOfChildren = 0)
        verifyDisplay(
                root.children[1], id = 2, width = 30f, height = 30f, POSITION_RIGHT,
                offset = 0f, noOfChildren = 1)
        verifyDisplay(
                root.children[1].children[0], id = 3, width = 30f, height = 30f, POSITION_BOTTOM,
                offset = -10f, noOfChildren = 0)
    }

    @Test
    fun rearrange_closeGaps() {
        val root = rearrangeRects(
            // 000
            // 000 111
            // 000 111
            //     111
            //
            //         222
            //         222
            //         222
            RectF(0f, 0f, 30f, 30f),
            RectF(40f, 10f, 70f, 40f),
            RectF(80.5f, 50f, 110f, 80f), // left+=0.5 to cause a preference for
                                                            // TOP/BOTTOM attach
        )

        verifyDisplay(root, id = 0, width = 30f, height = 30f, noOfChildren = 1)
        verifyDisplay(
                root.children[0], id = 1, width = 30f, height = 30f, POSITION_RIGHT, offset = 10f,
                noOfChildren = 1)
        // In the case of corner adjacency, we prefer a left/right attachment.
        verifyDisplay(
                root.children[0].children[0], id = 2, width = 29.5f, height = 30f, POSITION_BOTTOM,
                offset = 30f, noOfChildren = 0)
    }

    @Test
    fun copy() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ 0, /* offset= */ 0f)

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.addChild(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.addChild(display3)

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.addChild(display4)

        topology = DisplayTopology(display1, primaryDisplayId)
        val copy = topology.copy()

        assertThat(copy.primaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = copy.root!!
        verifyDisplay(actualDisplay1, id = 1, width = 200f, height = 600f, noOfChildren = 2)

        val actualDisplay2 = actualDisplay1.children[0]
        verifyDisplay(actualDisplay2, id = 2, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 1)

        val actualDisplay3 = actualDisplay1.children[1]
        verifyDisplay(actualDisplay3, id = 3, width = 600f, height = 200f, POSITION_RIGHT,
            offset = 400f, noOfChildren = 0)

        val actualDisplay4 = actualDisplay2.children[0]
        verifyDisplay(actualDisplay4, id = 4, width = 200f, height = 600f, POSITION_RIGHT,
            offset = 0f, noOfChildren = 0)
    }

    /**
     * Runs the rearrange algorithm and returns the resulting tree as a list of nodes, with the
     * root at index 0. The number of nodes is inferred from the number of positions passed.
     *
     * Returns the root node.
     */
    private fun rearrangeRects(vararg pos: RectF): DisplayTopology.TreeNode {
        // Generates a linear sequence of nodes in order in the List from root to leaf,
        // left-to-right. IDs are ascending from 0 to count - 1.

        val nodes = pos.indices.map {
            DisplayTopology.TreeNode(it, pos[it].width(), pos[it].height(), POSITION_RIGHT, 0f)
        }

        nodes.forEachIndexed { id, node ->
            if (id > 0) {
                nodes[id - 1].addChild(node)
            }
        }

        DisplayTopology(nodes[0], 0).rearrange(pos.indices.associateWith {
            PointF(pos[it].left, pos[it].top)
        })

        return nodes[0]
    }

    private fun verifyDisplay(display: DisplayTopology.TreeNode, id: Int, width: Float,
                              height: Float, @DisplayTopology.TreeNode.Position position: Int = 0,
                              offset: Float = 0f, noOfChildren: Int) {
        assertThat(display.displayId).isEqualTo(id)
        assertThat(display.width).isEqualTo(width)
        assertThat(display.height).isEqualTo(height)
        assertThat(display.position).isEqualTo(position)
        assertThat(display.offset).isEqualTo(offset)
        assertThat(display.children).hasSize(noOfChildren)
    }
}
