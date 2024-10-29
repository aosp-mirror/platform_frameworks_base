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

package com.android.server.display

import android.view.Display
import com.android.server.display.DisplayTopology.TreeNode.Position.POSITION_BOTTOM
import com.android.server.display.DisplayTopology.TreeNode.Position.POSITION_TOP
import com.android.server.display.DisplayTopology.TreeNode.Position.POSITION_RIGHT
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayTopologyTest {
    private val topology = DisplayTopology()

    @Test
    fun addOneDisplay() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId)

        val display = topology.mRoot!!
        assertThat(display.mDisplayId).isEqualTo(displayId)
        assertThat(display.mWidth).isEqualTo(width)
        assertThat(display.mHeight).isEqualTo(height)
        assertThat(display.mChildren).isEmpty()
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

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId1)

        val display1 = topology.mRoot!!
        assertThat(display1.mDisplayId).isEqualTo(displayId1)
        assertThat(display1.mWidth).isEqualTo(width1)
        assertThat(display1.mHeight).isEqualTo(height1)
        assertThat(display1.mChildren).hasSize(1)

        val display2 = display1.mChildren[0]
        assertThat(display2.mDisplayId).isEqualTo(displayId2)
        assertThat(display2.mWidth).isEqualTo(width2)
        assertThat(display2.mHeight).isEqualTo(height2)
        assertThat(display2.mChildren).isEmpty()
        assertThat(display2.mPosition).isEqualTo(POSITION_TOP)
        assertThat(display2.mOffset).isEqualTo(width1 / 2 - width2 / 2)
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

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId1)

        val display1 = topology.mRoot!!
        assertThat(display1.mDisplayId).isEqualTo(displayId1)
        assertThat(display1.mWidth).isEqualTo(width1)
        assertThat(display1.mHeight).isEqualTo(height1)
        assertThat(display1.mChildren).hasSize(1)

        val display2 = display1.mChildren[0]
        assertThat(display2.mDisplayId).isEqualTo(displayId2)
        assertThat(display2.mWidth).isEqualTo(width2)
        assertThat(display2.mHeight).isEqualTo(height2)
        assertThat(display2.mChildren).hasSize(1)
        assertThat(display2.mPosition).isEqualTo(POSITION_TOP)
        assertThat(display2.mOffset).isEqualTo(width1 / 2 - width2 / 2)

        var display = display2
        for (i in 3..noOfDisplays) {
            display = display.mChildren[0]
            assertThat(display.mDisplayId).isEqualTo(i)
            assertThat(display.mWidth).isEqualTo(width1)
            assertThat(display.mHeight).isEqualTo(height1)
            // The last display should have no children
            assertThat(display.mChildren).hasSize(if (i < noOfDisplays) 1 else 0)
            assertThat(display.mPosition).isEqualTo(POSITION_RIGHT)
            assertThat(display.mOffset).isEqualTo(0)
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

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId1)

        var display1 = topology.mRoot!!
        assertThat(display1.mDisplayId).isEqualTo(displayId1)
        assertThat(display1.mWidth).isEqualTo(width1)
        assertThat(display1.mHeight).isEqualTo(height1)
        assertThat(display1.mChildren).hasSize(1)

        var display2 = display1.mChildren[0]
        assertThat(display2.mDisplayId).isEqualTo(displayId2)
        assertThat(display2.mWidth).isEqualTo(width2)
        assertThat(display2.mHeight).isEqualTo(height2)
        assertThat(display2.mChildren).hasSize(1)
        assertThat(display2.mPosition).isEqualTo(POSITION_TOP)
        assertThat(display2.mOffset).isEqualTo(width1 / 2 - width2 / 2)

        var display = display2
        for (i in 3..noOfDisplays) {
            if (i in removedDisplays) {
                continue
            }
            display = display.mChildren[0]
            assertThat(display.mDisplayId).isEqualTo(i)
            assertThat(display.mWidth).isEqualTo(width1)
            assertThat(display.mHeight).isEqualTo(height1)
            // The last display should have no children
            assertThat(display.mChildren).hasSize(if (i < noOfDisplays) 1 else 0)
            assertThat(display.mPosition).isEqualTo(POSITION_RIGHT)
            assertThat(display.mOffset).isEqualTo(0)
        }

        topology.removeDisplay(22)
        removedDisplays += 22
        topology.removeDisplay(23)
        removedDisplays += 23
        topology.removeDisplay(25)
        removedDisplays += 25

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId1)

        display1 = topology.mRoot!!
        assertThat(display1.mDisplayId).isEqualTo(displayId1)
        assertThat(display1.mWidth).isEqualTo(width1)
        assertThat(display1.mHeight).isEqualTo(height1)
        assertThat(display1.mChildren).hasSize(1)

        display2 = display1.mChildren[0]
        assertThat(display2.mDisplayId).isEqualTo(displayId2)
        assertThat(display2.mWidth).isEqualTo(width2)
        assertThat(display2.mHeight).isEqualTo(height2)
        assertThat(display2.mChildren).hasSize(1)
        assertThat(display2.mPosition).isEqualTo(POSITION_TOP)
        assertThat(display2.mOffset).isEqualTo(width1 / 2 - width2 / 2)

        display = display2
        for (i in 3..noOfDisplays) {
            if (i in removedDisplays) {
                continue
            }
            display = display.mChildren[0]
            assertThat(display.mDisplayId).isEqualTo(i)
            assertThat(display.mWidth).isEqualTo(width1)
            assertThat(display.mHeight).isEqualTo(height1)
            // The last display should have no children
            assertThat(display.mChildren).hasSize(if (i < noOfDisplays) 1 else 0)
            assertThat(display.mPosition).isEqualTo(POSITION_RIGHT)
            assertThat(display.mOffset).isEqualTo(0)
        }
    }

    @Test
    fun removeAllDisplays() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)
        topology.removeDisplay(displayId)

        assertThat(topology.mPrimaryDisplayId).isEqualTo(Display.INVALID_DISPLAY)
        assertThat(topology.mRoot).isNull()
    }

    @Test
    fun removeDisplayThatDoesNotExist() {
        val displayId = 1
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId, width, height)
        topology.removeDisplay(3)

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId)

        val display = topology.mRoot!!
        assertThat(display.mDisplayId).isEqualTo(displayId)
        assertThat(display.mWidth).isEqualTo(width)
        assertThat(display.mHeight).isEqualTo(height)
        assertThat(display.mChildren).isEmpty()
    }

    @Test
    fun removePrimaryDisplay() {
        val displayId1 = 1
        val displayId2 = 2
        val width = 800f
        val height = 600f

        topology.addDisplay(displayId1, width, height)
        topology.addDisplay(displayId2, width, height)
        topology.mPrimaryDisplayId = displayId2
        topology.removeDisplay(displayId2)

        assertThat(topology.mPrimaryDisplayId).isEqualTo(displayId1)
        val display = topology.mRoot!!
        assertThat(display.mDisplayId).isEqualTo(displayId1)
        assertThat(display.mWidth).isEqualTo(width)
        assertThat(display.mHeight).isEqualTo(height)
        assertThat(display.mChildren).isEmpty()
    }

    @Test
    fun normalization_noOverlaps_leavesTopologyUnchanged() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ null, /* offset= */ 0f)
        topology.mRoot = display1

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.mChildren.add(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.mChildren.add(display3)
        topology.mPrimaryDisplayId = primaryDisplayId

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.mChildren.add(display4)

        topology.normalize()

        assertThat(topology.mPrimaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.mRoot!!
        assertThat(actualDisplay1.mDisplayId).isEqualTo(1)
        assertThat(actualDisplay1.mWidth).isEqualTo(200f)
        assertThat(actualDisplay1.mHeight).isEqualTo(600f)
        assertThat(actualDisplay1.mChildren).hasSize(2)

        val actualDisplay2 = actualDisplay1.mChildren[0]
        assertThat(actualDisplay2.mDisplayId).isEqualTo(2)
        assertThat(actualDisplay2.mWidth).isEqualTo(600f)
        assertThat(actualDisplay2.mHeight).isEqualTo(200f)
        assertThat(actualDisplay2.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay2.mOffset).isEqualTo(0f)
        assertThat(actualDisplay2.mChildren).hasSize(1)

        val actualDisplay3 = actualDisplay1.mChildren[1]
        assertThat(actualDisplay3.mDisplayId).isEqualTo(3)
        assertThat(actualDisplay3.mWidth).isEqualTo(600f)
        assertThat(actualDisplay3.mHeight).isEqualTo(200f)
        assertThat(actualDisplay3.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay3.mOffset).isEqualTo(400f)
        assertThat(actualDisplay3.mChildren).isEmpty()

        val actualDisplay4 = actualDisplay2.mChildren[0]
        assertThat(actualDisplay4.mDisplayId).isEqualTo(4)
        assertThat(actualDisplay4.mWidth).isEqualTo(200f)
        assertThat(actualDisplay4.mHeight).isEqualTo(600f)
        assertThat(actualDisplay4.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay4.mOffset).isEqualTo(0f)
        assertThat(actualDisplay4.mChildren).isEmpty()
    }

    @Test
    fun normalization_moveDisplayWithoutReparenting() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ null, /* offset= */ 0f)
        topology.mRoot = display1

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display1.mChildren.add(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 10f)
        display1.mChildren.add(display3)
        topology.mPrimaryDisplayId = primaryDisplayId

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.mChildren.add(display4)

        // Display 3 becomes a child of display 2. Display 4 gets moved without changing its parent.
        topology.normalize()

        assertThat(topology.mPrimaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.mRoot!!
        assertThat(actualDisplay1.mDisplayId).isEqualTo(1)
        assertThat(actualDisplay1.mWidth).isEqualTo(200f)
        assertThat(actualDisplay1.mHeight).isEqualTo(600f)
        assertThat(actualDisplay1.mChildren).hasSize(1)

        val actualDisplay2 = actualDisplay1.mChildren[0]
        assertThat(actualDisplay2.mDisplayId).isEqualTo(2)
        assertThat(actualDisplay2.mWidth).isEqualTo(200f)
        assertThat(actualDisplay2.mHeight).isEqualTo(600f)
        assertThat(actualDisplay2.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay2.mOffset).isEqualTo(0f)
        assertThat(actualDisplay2.mChildren).hasSize(2)

        val actualDisplay3 = actualDisplay2.mChildren[1]
        assertThat(actualDisplay3.mDisplayId).isEqualTo(3)
        assertThat(actualDisplay3.mWidth).isEqualTo(600f)
        assertThat(actualDisplay3.mHeight).isEqualTo(200f)
        assertThat(actualDisplay3.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay3.mOffset).isEqualTo(10f)
        assertThat(actualDisplay3.mChildren).isEmpty()

        val actualDisplay4 = actualDisplay2.mChildren[0]
        assertThat(actualDisplay4.mDisplayId).isEqualTo(4)
        assertThat(actualDisplay4.mWidth).isEqualTo(200f)
        assertThat(actualDisplay4.mHeight).isEqualTo(600f)
        assertThat(actualDisplay4.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay4.mOffset).isEqualTo(210f)
        assertThat(actualDisplay4.mChildren).isEmpty()
    }

    @Test
    fun normalization_moveDisplayWithoutReparenting_offsetOutOfBounds() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 50f, /* position= */ null, /* offset= */ 0f)
        topology.mRoot = display1

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 0f)
        display1.mChildren.add(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 10f)
        display1.mChildren.add(display3)
        topology.mPrimaryDisplayId = primaryDisplayId

        // Display 3 gets moved and its left side is still on the same line as the right side
        // of Display 1, but it no longer touches it (the offset is out of bounds), so Display 2
        // becomes its new parent.
        topology.normalize()

        assertThat(topology.mPrimaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.mRoot!!
        assertThat(actualDisplay1.mDisplayId).isEqualTo(1)
        assertThat(actualDisplay1.mWidth).isEqualTo(200f)
        assertThat(actualDisplay1.mHeight).isEqualTo(50f)
        assertThat(actualDisplay1.mChildren).hasSize(1)

        val actualDisplay2 = actualDisplay1.mChildren[0]
        assertThat(actualDisplay2.mDisplayId).isEqualTo(2)
        assertThat(actualDisplay2.mWidth).isEqualTo(600f)
        assertThat(actualDisplay2.mHeight).isEqualTo(200f)
        assertThat(actualDisplay2.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay2.mOffset).isEqualTo(0f)
        assertThat(actualDisplay2.mChildren).hasSize(1)

        val actualDisplay3 = actualDisplay2.mChildren[0]
        assertThat(actualDisplay3.mDisplayId).isEqualTo(3)
        assertThat(actualDisplay3.mWidth).isEqualTo(600f)
        assertThat(actualDisplay3.mHeight).isEqualTo(200f)
        assertThat(actualDisplay3.mPosition).isEqualTo(POSITION_BOTTOM)
        assertThat(actualDisplay3.mOffset).isEqualTo(0f)
        assertThat(actualDisplay3.mChildren).isEmpty()
    }

    @Test
    fun normalization_moveAndReparentDisplay() {
        val display1 = DisplayTopology.TreeNode(/* displayId= */ 1, /* width= */ 200f,
            /* height= */ 600f, /* position= */ null, /* offset= */ 0f)
        topology.mRoot = display1

        val display2 = DisplayTopology.TreeNode(/* displayId= */ 2, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display1.mChildren.add(display2)

        val primaryDisplayId = 3
        val display3 = DisplayTopology.TreeNode(primaryDisplayId, /* width= */ 600f,
            /* height= */ 200f, POSITION_RIGHT, /* offset= */ 400f)
        display1.mChildren.add(display3)
        topology.mPrimaryDisplayId = primaryDisplayId

        val display4 = DisplayTopology.TreeNode(/* displayId= */ 4, /* width= */ 200f,
            /* height= */ 600f, POSITION_RIGHT, /* offset= */ 0f)
        display2.mChildren.add(display4)

        topology.normalize()

        assertThat(topology.mPrimaryDisplayId).isEqualTo(primaryDisplayId)

        val actualDisplay1 = topology.mRoot!!
        assertThat(actualDisplay1.mDisplayId).isEqualTo(1)
        assertThat(actualDisplay1.mWidth).isEqualTo(200f)
        assertThat(actualDisplay1.mHeight).isEqualTo(600f)
        assertThat(actualDisplay1.mChildren).hasSize(1)

        val actualDisplay2 = actualDisplay1.mChildren[0]
        assertThat(actualDisplay2.mDisplayId).isEqualTo(2)
        assertThat(actualDisplay2.mWidth).isEqualTo(200f)
        assertThat(actualDisplay2.mHeight).isEqualTo(600f)
        assertThat(actualDisplay2.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay2.mOffset).isEqualTo(0f)
        assertThat(actualDisplay2.mChildren).hasSize(1)

        val actualDisplay3 = actualDisplay2.mChildren[0]
        assertThat(actualDisplay3.mDisplayId).isEqualTo(3)
        assertThat(actualDisplay3.mWidth).isEqualTo(600f)
        assertThat(actualDisplay3.mHeight).isEqualTo(200f)
        assertThat(actualDisplay3.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay3.mOffset).isEqualTo(400f)
        assertThat(actualDisplay3.mChildren).hasSize(1)

        val actualDisplay4 = actualDisplay3.mChildren[0]
        assertThat(actualDisplay4.mDisplayId).isEqualTo(4)
        assertThat(actualDisplay4.mWidth).isEqualTo(200f)
        assertThat(actualDisplay4.mHeight).isEqualTo(600f)
        assertThat(actualDisplay4.mPosition).isEqualTo(POSITION_RIGHT)
        assertThat(actualDisplay4.mOffset).isEqualTo(-400f)
        assertThat(actualDisplay4.mChildren).isEmpty()
    }
}