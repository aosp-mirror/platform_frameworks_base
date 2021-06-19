/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.render

import android.view.View
import java.lang.RuntimeException
import java.lang.StringBuilder

/**
 * A controller that represents a single unit of addable/removable view(s) in the notification
 * shade. Some nodes are just a single view (such as a header), while some might involve many views
 * (such as a notification row).
 *
 * It's possible for nodes to support having child nodes (for example, some notification rows
 * contain other notification rows). If so, they must implement all of the child-related methods
 * below.
 */
interface NodeController {
    /** A string that uniquely(ish) represents the node in the tree. Used for debugging. */
    val nodeLabel: String

    val view: View

    fun getChildAt(index: Int): View? {
        throw RuntimeException("Not supported")
    }

    fun getChildCount(): Int = 0

    fun addChildAt(child: NodeController, index: Int) {
        throw RuntimeException("Not supported")
    }

    fun moveChildTo(child: NodeController, index: Int) {
        throw RuntimeException("Not supported")
    }

    fun removeChild(child: NodeController, isTransfer: Boolean) {
        throw RuntimeException("Not supported")
    }
}

/**
 * Used to specify the tree of [NodeController]s that currently make up the shade.
 */
interface NodeSpec {
    val parent: NodeSpec?
    val controller: NodeController
    val children: List<NodeSpec>
}

class NodeSpecImpl(
    override val parent: NodeSpec?,
    override val controller: NodeController
) : NodeSpec {
    override val children = mutableListOf<NodeSpec>()
}

/**
 * Converts a tree spec to human-readable string, for dumping purposes.
 */
fun treeSpecToStr(tree: NodeSpec): String {
    return StringBuilder().also { treeSpecToStrHelper(tree, it, "") }.toString()
}

private fun treeSpecToStrHelper(tree: NodeSpec, sb: StringBuilder, indent: String) {
    sb.append("${indent}ns{${tree.controller.nodeLabel}")
    if (tree.children.isNotEmpty()) {
        val childIndent = "$indent  "
        for (child in tree.children) {
            treeSpecToStrHelper(child, sb, childIndent)
        }
    }
}
