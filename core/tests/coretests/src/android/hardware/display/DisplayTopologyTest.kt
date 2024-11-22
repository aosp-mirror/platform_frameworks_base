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

        val display = topology.root!!
        assertThat(display.displayId).isEqualTo(displayId)
        assertThat(display.width).isEqualTo(width)
        assertThat(display.height).isEqualTo(height)
        assertThat(display.children).isEmpty()
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
        assertThat(display1.displayId).isEqualTo(displayId1)
        assertThat(display1.width).isEqualTo(width1)
        assertThat(display1.height).isEqualTo(height1)
        assertThat(display1.children).hasSize(1)

        val display2 = display1.children[0]
        assertThat(display2.displayId).isEqualTo(displayId2)
        assertThat(display2.width).isEqualTo(width2)
        assertThat(display2.height).isEqualTo(height2)
        assertThat(display2.children).isEmpty()
        assertThat(display2.position).isEqualTo(POSITION_TOP)
        assertThat(display2.offset).isEqualTo(width1 / 2 - width2 / 2)
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
        assertThat(display1.displayId).isEqualTo(displayId1)
        assertThat(display1.width).isEqualTo(width1)
        assertThat(display1.height).isEqualTo(height1)
        assertThat(display1.children).hasSize(1)

        val display2 = display1.children[0]
        assertThat(display2.displayId).isEqualTo(displayId2)
        assertThat(display2.width).isEqualTo(width2)
        assertThat(display2.height).isEqualTo(height2)
        assertThat(display2.children).hasSize(1)
        assertThat(display2.position).isEqualTo(POSITION_TOP)
        assertThat(display2.offset).isEqualTo(width1 / 2 - width2 / 2)

        var display = display2
        for (i in 3..noOfDisplays) {
            display = display.children[0]
            assertThat(display.displayId).isEqualTo(i)
            assertThat(display.width).isEqualTo(width1)
            assertThat(display.height).isEqualTo(height1)
            // The last display should have no children
            assertThat(display.children).hasSize(if (i < noOfDisplays) 1 else 0)
            assertThat(display.position).isEqualTo(POSITION_RIGHT)
            assertThat(display.offset).isEqualTo(0)
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
        assertThat(display1.displayId).isEqualTo(displayId1)
        assertThat(display1.width).isEqualTo(width1)
        assertThat(display1.height).isEqualTo(height1)
        assertThat(display1.children).hasSize(1)

        var display2 = display1.children[0]
        assertThat(display2.displayId).isEqualTo(displayId2)
        assertThat(display2.width).isEqualTo(width2)
        assertThat(display2.height).isEqualTo(height2)
        assertThat(display2.children).hasSize(1)
        assertThat(display2.position).isEqualTo(POSITION_TOP)
        assertThat(display2.offset).isEqualTo(width1 / 2 - width2 / 2)

        var display = display2
        for (i in 3..noOfDisplays) {
            if (i in removedDisplays) {
                continue
            }
            display = display.children[0]
            assertThat(display.displayId).isEqualTo(i)
            assertThat(display.width).isEqualTo(width1)
            assertThat(display.height).isEqualTo(height1)
            // The last display should have no children
            assertThat(display.children).hasSize(if (i < noOfDisplays) 1 else 0)
            assertThat(display.position).isEqualTo(POSITION_RIGHT)
            assertThat(display.offset).isEqualTo(0)
        }

        topology.removeDisplay(22)
        removedDisplays += 22
        topology.removeDisplay(23)
        removedDisplays += 23
        topology.removeDisplay(25)
        removedDisplays += 25

        assertThat(topology.primaryDisplayId).isEqualTo(displayId1)

        display1 = topology.root!!
        assertThat(display1.displayId).isEqualTo(displayId1)
        assertThat(display1.width).isEqualTo(width1)
        assertThat(display1.height).isEqualTo(height1)
        assertThat(display1.children).hasSize(1)

        display2 = display1.children[0]
        assertThat(display2.displayId).isEqualTo(displayId2)
        assertThat(display2.width).isEqualTo(width2)
        assertThat(display2.height).isEqualTo(height2)
        assertThat(display2.children).hasSize(1)
        assertThat(display2.position).isEqualTo(POSITION_TOP)
        assertThat(display2.offset).isEqualTo(width1 / 2 - width2 / 2)

        display = display2
        for (i in 3..noOfDisplays) {
            if (i in removedDisplays) {
                continue
            }
            display = display.children[0]
            assertThat(display.displayId).isEqualTo(i)
            assertThat(display.width).isEqualTo(width1)
            assertThat(display.height).isEqualTo(height1)
            // The last display should have no children
            assertThat(display.children).hasSize(if (i < noOfDisplays) 1 else 0)
            assertThat(display.position).isEqualTo(POSITION_RIGHT)
            assertThat(display.offset).isEqualTo(0)
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

        val display = topology.root!!
        assertThat(display.displayId).isEqualTo(displayId)
        assertThat(display.width).isEqualTo(width)
        assertThat(display.height).isEqualTo(height)
        assertThat(display.children).isEmpty()
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
        val display = topology.root!!
        assertThat(display.displayId).isEqualTo(displayId1)
        assertThat(display.width).isEqualTo(width)
        assertThat(display.height).isEqualTo(height)
        assertThat(display.children).isEmpty()
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
        assertThat(actualDisplay1.displayId).isEqualTo(1)
        assertThat(actualDisplay1.width).isEqualTo(200f)
        assertThat(actualDisplay1.height).isEqualTo(600f)
        assertThat(actualDisplay1.children).hasSize(2)

        val actualDisplay2 = actualDisplay1.children[0]
        assertThat(actualDisplay2.displayId).isEqualTo(2)
        assertThat(actualDisplay2.width).isEqualTo(600f)
        assertThat(actualDisplay2.height).isEqualTo(200f)
        assertThat(actualDisplay2.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay2.offset).isEqualTo(0f)
        assertThat(actualDisplay2.children).hasSize(1)

        val actualDisplay3 = actualDisplay1.children[1]
        assertThat(actualDisplay3.displayId).isEqualTo(3)
        assertThat(actualDisplay3.width).isEqualTo(600f)
        assertThat(actualDisplay3.height).isEqualTo(200f)
        assertThat(actualDisplay3.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay3.offset).isEqualTo(400f)
        assertThat(actualDisplay3.children).isEmpty()

        val actualDisplay4 = actualDisplay2.children[0]
        assertThat(actualDisplay4.displayId).isEqualTo(4)
        assertThat(actualDisplay4.width).isEqualTo(200f)
        assertThat(actualDisplay4.height).isEqualTo(600f)
        assertThat(actualDisplay4.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay4.offset).isEqualTo(0f)
        assertThat(actualDisplay4.children).isEmpty()
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
        assertThat(actualDisplay1.displayId).isEqualTo(1)
        assertThat(actualDisplay1.width).isEqualTo(200f)
        assertThat(actualDisplay1.height).isEqualTo(600f)
        assertThat(actualDisplay1.children).hasSize(1)

        val actualDisplay2 = actualDisplay1.children[0]
        assertThat(actualDisplay2.displayId).isEqualTo(2)
        assertThat(actualDisplay2.width).isEqualTo(200f)
        assertThat(actualDisplay2.height).isEqualTo(600f)
        assertThat(actualDisplay2.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay2.offset).isEqualTo(0f)
        assertThat(actualDisplay2.children).hasSize(2)

        val actualDisplay3 = actualDisplay2.children[1]
        assertThat(actualDisplay3.displayId).isEqualTo(3)
        assertThat(actualDisplay3.width).isEqualTo(600f)
        assertThat(actualDisplay3.height).isEqualTo(200f)
        assertThat(actualDisplay3.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay3.offset).isEqualTo(10f)
        assertThat(actualDisplay3.children).isEmpty()

        val actualDisplay4 = actualDisplay2.children[0]
        assertThat(actualDisplay4.displayId).isEqualTo(4)
        assertThat(actualDisplay4.width).isEqualTo(200f)
        assertThat(actualDisplay4.height).isEqualTo(600f)
        assertThat(actualDisplay4.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay4.offset).isEqualTo(210f)
        assertThat(actualDisplay4.children).isEmpty()
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
        assertThat(actualDisplay1.displayId).isEqualTo(1)
        assertThat(actualDisplay1.width).isEqualTo(200f)
        assertThat(actualDisplay1.height).isEqualTo(50f)
        assertThat(actualDisplay1.children).hasSize(1)

        val actualDisplay2 = actualDisplay1.children[0]
        assertThat(actualDisplay2.displayId).isEqualTo(2)
        assertThat(actualDisplay2.width).isEqualTo(600f)
        assertThat(actualDisplay2.height).isEqualTo(200f)
        assertThat(actualDisplay2.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay2.offset).isEqualTo(0f)
        assertThat(actualDisplay2.children).hasSize(1)

        val actualDisplay3 = actualDisplay2.children[0]
        assertThat(actualDisplay3.displayId).isEqualTo(3)
        assertThat(actualDisplay3.width).isEqualTo(600f)
        assertThat(actualDisplay3.height).isEqualTo(200f)
        assertThat(actualDisplay3.position).isEqualTo(POSITION_BOTTOM)
        assertThat(actualDisplay3.offset).isEqualTo(0f)
        assertThat(actualDisplay3.children).isEmpty()
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
        assertThat(actualDisplay1.displayId).isEqualTo(1)
        assertThat(actualDisplay1.width).isEqualTo(200f)
        assertThat(actualDisplay1.height).isEqualTo(600f)
        assertThat(actualDisplay1.children).hasSize(1)

        val actualDisplay2 = actualDisplay1.children[0]
        assertThat(actualDisplay2.displayId).isEqualTo(2)
        assertThat(actualDisplay2.width).isEqualTo(200f)
        assertThat(actualDisplay2.height).isEqualTo(600f)
        assertThat(actualDisplay2.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay2.offset).isEqualTo(0f)
        assertThat(actualDisplay2.children).hasSize(1)

        val actualDisplay3 = actualDisplay2.children[0]
        assertThat(actualDisplay3.displayId).isEqualTo(3)
        assertThat(actualDisplay3.width).isEqualTo(600f)
        assertThat(actualDisplay3.height).isEqualTo(200f)
        assertThat(actualDisplay3.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay3.offset).isEqualTo(400f)
        assertThat(actualDisplay3.children).hasSize(1)

        val actualDisplay4 = actualDisplay3.children[0]
        assertThat(actualDisplay4.displayId).isEqualTo(4)
        assertThat(actualDisplay4.width).isEqualTo(200f)
        assertThat(actualDisplay4.height).isEqualTo(600f)
        assertThat(actualDisplay4.position).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay4.offset).isEqualTo(-400f)
        assertThat(actualDisplay4.children).isEmpty()
    }

    @Test
    fun rearrange_twoDisplays() {
        val nodes = rearrangeRects(
            // Arrange in staggered manner, connected vertically.
            RectF(100f, 100f, 250f, 200f),
            RectF(150f, 200f, 300f, 300f),
        )

        assertThat(nodes[0].children).containsExactly(nodes[1])
        assertThat(nodes[1].children).isEmpty()
        assertPositioning(nodes, Pair(POSITION_BOTTOM, 50f))
    }

    @Test
    fun rearrange_reverseOrderOfSeveralDisplays() {
        val nodes = rearrangeRects(
            RectF(0f, 0f, 150f, 100f),
            RectF(-150f, 0f, 0f, 100f),
            RectF(-300f, 0f, -150f, 100f),
            RectF(-450f, 0f, -300f, 100f),
        )

        assertPositioning(
            nodes,
            Pair(POSITION_LEFT, 0f),
            Pair(POSITION_LEFT, 0f),
            Pair(POSITION_LEFT, 0f),
        )

        assertThat(nodes[0].children).containsExactly(nodes[1])
        assertThat(nodes[1].children).containsExactly(nodes[2])
        assertThat(nodes[2].children).containsExactly(nodes[3])
        assertThat(nodes[3].children).isEmpty()
    }

    @Test
    fun rearrange_crossWithRootInCenter() {
        val nodes = rearrangeRects(
            RectF(0f, 0f, 150f, 100f),
            RectF(-150f, 0f, 0f, 100f),
            RectF(0f,-100f, 150f, 0f),
            RectF(150f, 0f, 300f, 100f),
            RectF(0f, 100f, 150f, 200f),
        )

        assertPositioning(
            nodes,
            Pair(POSITION_LEFT, 0f),
            Pair(POSITION_TOP, 0f),
            Pair(POSITION_RIGHT, 0f),
            Pair(POSITION_BOTTOM, 0f),
        )

        assertThat(nodes[0].children)
            .containsExactly(nodes[1], nodes[2], nodes[3], nodes[4])
    }

    @Test
    fun rearrange_elbowArrangementDoesNotUseCornerAdjacency1() {
        val nodes = rearrangeRects(
            //     2
            //     |
            // 0 - 1

            RectF(0f, 0f, 100f, 100f),
            RectF(100f, 0f, 200f, 100f),
            RectF(100f, -100f, 200f, 0f),
        )

        assertThat(nodes[0].children).containsExactly(nodes[1])
        assertThat(nodes[1].children).containsExactly(nodes[2])
        assertThat(nodes[2].children).isEmpty()

        assertPositioning(
            nodes,
            Pair(POSITION_RIGHT, 0f),
            Pair(POSITION_TOP, 0f),
        )
    }

    @Test
    fun rearrange_elbowArrangementDoesNotUseCornerAdjacency2() {
        val nodes = rearrangeRects(
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

        assertThat(nodes[0].children).containsExactly(nodes[1])
        assertThat(nodes[1].children).containsExactly(nodes[2])
        assertThat(nodes[2].children).containsExactly(nodes[3])
        assertThat(nodes[3].children).isEmpty()

        assertPositioning(
            nodes,
            Pair(POSITION_BOTTOM, 0f),
            Pair(POSITION_BOTTOM, 0f),
            Pair(POSITION_LEFT, 0f),
        )
    }

    @Test
    fun rearrange_useLargerEdge() {
        val nodes = rearrangeRects(
            //444111
            //444111
            //444111
            //  000222
            //  000222
            //  000222
            //    333
            //    333
            //    333
            RectF(20f, 30f, 50f, 60f),
            RectF(30f, 0f, 60f, 30f),
            RectF(50f, 30f, 80f, 60f),
            RectF(40f, 60f, 70f, 90f),
            RectF(0f, 0f, 30f, 30f),
        )

        assertPositioning(
            nodes,
            Pair(POSITION_TOP, 10f),
            Pair(POSITION_RIGHT, 0f),
            Pair(POSITION_BOTTOM, -10f),
            Pair(POSITION_LEFT, 0f),
        )

        assertThat(nodes[0].children).containsExactly(nodes[1], nodes[2])
        assertThat(nodes[1].children).containsExactly(nodes[4])
        assertThat(nodes[2].children).containsExactly(nodes[3])
        (3..4).forEach { assertThat(nodes[it].children).isEmpty() }
    }

    @Test
    fun rearrange_closeGaps() {
        val nodes = rearrangeRects(
            //000
            //000 111
            //000 111
            //    111
            //
            //        222
            //        222
            //        222
            RectF(0f, 0f, 30f, 30f),
            RectF(40f, 10f, 70f, 40f),
            RectF(80.5f, 50f, 110f, 80f),  // left+=0.5 to cause a preference for TOP/BOTTOM attach
        )

        assertPositioning(
            nodes,
            // In the case of corner adjacency, we prefer a left/right attachment.
            Pair(POSITION_RIGHT, 10f),
            Pair(POSITION_BOTTOM, 40.5f),  // TODO: fix implementation to remove this gap
        )

        assertThat(nodes[0].children).containsExactly(nodes[1])
        assertThat(nodes[1].children).containsExactly(nodes[2])
        assertThat(nodes[2].children).isEmpty()
    }

    /**
     * Runs the rearrange algorithm and returns the resulting tree as a list of nodes, with the
     * root at index 0. The number of nodes is inferred from the number of positions passed.
     */
    private fun rearrangeRects(vararg pos : RectF) : List<DisplayTopology.TreeNode> {
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

        return nodes
    }

    private fun assertPositioning(
            nodes : List<DisplayTopology.TreeNode>, vararg positions : Pair<Int, Float>) {
        assertThat(nodes.drop(1).map { Pair(it.position, it.offset )})
            .containsExactly(*positions)
            .inOrder()
    }
}
