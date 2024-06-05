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

import android.annotation.MainThread
import android.view.View
import com.android.app.tracing.traceSection

/**
 * Given a "spec" that describes a "tree" of views, adds and removes views from the
 * [rootController] and its children until the actual tree matches the spec.
 *
 * Every node in the spec tree must specify both a view and its associated [NodeController].
 * Commands to add/remove/reorder children are sent to the controller. How the controller
 * interprets these commands is left to its own discretion -- it might add them directly to its
 * associated view or to some subview container.
 *
 * It's possible for nodes to mix "unmanaged" views in alongside managed ones within the same
 * container. In this case, whenever the differ runs it will move all unmanaged views to the end
 * of the node's child list.
 */
@MainThread
class ShadeViewDiffer(
    rootController: NodeController,
    private val logger: ShadeViewDifferLogger
) {
    private val rootNode = ShadeNode(rootController)
    private val nodes = mutableMapOf(rootController to rootNode)

    /**
     * Adds and removes views from the root (and its children) until their structure matches the
     * provided [spec]. The root node of the spec must match the root controller passed to the
     * differ's constructor.
     */
    fun applySpec(spec: NodeSpec) = traceSection("ShadeViewDiffer.applySpec") {
        val specMap = treeToMap(spec)

        if (spec.controller != rootNode.controller) {
            throw IllegalArgumentException("Tree root ${spec.controller.nodeLabel} does not " +
                    "match own root at ${rootNode.label}")
        }

        detachChildren(rootNode, specMap)
        attachChildren(rootNode, specMap)
    }

    /**
     * If [view] is managed by this differ, then returns the label of the view's controller.
     * Otherwise returns View.toString().
     *
     * For debugging purposes.
     */
    fun getViewLabel(view: View): String =
            nodes.values.firstOrNull { node -> node.view === view }?.label ?: view.toString()

    private fun detachChildren(
        parentNode: ShadeNode,
        specMap: Map<NodeController, NodeSpec>
    ) = traceSection("detachChildren") {
        val views = nodes.values.associateBy { it.view }
        fun detachRecursively(parentNode: ShadeNode, specMap: Map<NodeController, NodeSpec>) {
            val parentSpec = specMap[parentNode.controller]
            for (i in parentNode.getChildCount() - 1 downTo 0) {
                val childView = parentNode.getChildAt(i)
                views[childView]?.let { childNode ->
                    val childSpec = specMap[childNode.controller]
                    maybeDetachChild(parentNode, parentSpec, childNode, childSpec)
                    if (childNode.controller.getChildCount() > 0) {
                        detachRecursively(childNode, specMap)
                    }
                }
            }
        }
        detachRecursively(parentNode, specMap)
    }

    private fun maybeDetachChild(
            parentNode: ShadeNode,
            parentSpec: NodeSpec?,
            childNode: ShadeNode,
            childSpec: NodeSpec?
    ) {
        val newParentNode = childSpec?.parent?.let { getNode(it) }

        if (newParentNode != parentNode) {
            val childCompletelyRemoved = newParentNode == null

            if (childCompletelyRemoved) {
                nodes.remove(childNode.controller)
            }

            if (childCompletelyRemoved && parentSpec == null &&
                    childNode.offerToKeepInParentForAnimation()) {
                // If both the child and the parent are being removed at the same time, then
                // keep the child attached to the parent for animation purposes
                logger.logSkipDetachingChild(
                        key = childNode.label,
                        parentKey = parentNode.label,
                        isTransfer = !childCompletelyRemoved,
                        isParentRemoved = true
                )
            } else {
                logger.logDetachingChild(
                        key = childNode.label,
                        isTransfer = !childCompletelyRemoved,
                        isParentRemoved = parentSpec == null,
                        oldParent = parentNode.label,
                        newParent = newParentNode?.label
                )
                parentNode.removeChild(childNode, isTransfer = !childCompletelyRemoved)
                childNode.parent = null
            }
        }
    }

    /**
     * Attach the Child Nodes to the parentNode using the structure from specMap
     */
    private fun attachChildren(
        parentNode: ShadeNode,
        specMap: Map<NodeController, NodeSpec>
    ): Unit = traceSection("attachChildren") {
        val parentSpec = checkNotNull(specMap[parentNode.controller])

        for ((index, childSpec) in parentSpec.children.withIndex()) {
            val currView = parentNode.getChildAt(index)
            val childNode = getNode(childSpec)

            if (childNode.view != currView) {
                val removedFromParent = childNode.removeFromParentIfKeptForAnimation()
                if (removedFromParent) {
                    logger.logDetachingChild(
                            key = childNode.label,
                            isTransfer = false,
                            isParentRemoved = true,
                            oldParent = null,
                            newParent = null
                    )
                }

                when (childNode.parent) {
                    null -> {
                        // A new child (either newly created or coming from some other parent)
                        logger.logAttachingChild(childNode.label, parentNode.label, index)
                        parentNode.addChildAt(childNode, index)
                        childNode.parent = parentNode
                    }
                    parentNode -> {
                        // A pre-existing child, just in the wrong position. Move it into place
                        logger.logMovingChild(childNode.label, parentNode.label, index)
                        parentNode.moveChildTo(childNode, index)
                    }
                    else -> {
                        // Error: child still has a parent. We should have detached it in the
                        // previous step.
                        throw IllegalStateException("Child ${childNode.label} should have " +
                                "parent ${parentNode.label} but is actually " +
                                "${childNode.parent?.label}")
                    }
                }
            }

            childNode.resetKeepInParentForAnimation()

            if (childSpec.children.isNotEmpty()) {
                attachChildren(childNode, specMap)
            }
        }
    }

    private fun getNode(spec: NodeSpec): ShadeNode {
        var node = nodes[spec.controller]
        if (node == null) {
            node = ShadeNode(spec.controller)
            nodes[node.controller] = node
        }
        return node
    }

    private fun treeToMap(tree: NodeSpec): Map<NodeController, NodeSpec> {
        val map = mutableMapOf<NodeController, NodeSpec>()

        try {
            registerNodes(tree, map)
        } catch (ex: DuplicateNodeException) {
            logger.logDuplicateNodeInTree(tree, ex)
            throw ex
        }

        return map
    }

    private fun registerNodes(node: NodeSpec, map: MutableMap<NodeController, NodeSpec>) {
        if (map.containsKey(node.controller)) {
            throw DuplicateNodeException("Node ${node.controller.nodeLabel} appears more than once")
        }
        map[node.controller] = node

        if (node.children.isNotEmpty()) {
            for (child in node.children) {
                registerNodes(child, map)
            }
        }
    }
}

private class DuplicateNodeException(message: String) : RuntimeException(message)

private class ShadeNode(val controller: NodeController) {
    val view: View
        get() = controller.view

    var parent: ShadeNode? = null

    val label: String
        get() = controller.nodeLabel

    fun getChildAt(index: Int): View? = controller.getChildAt(index)

    fun getChildCount(): Int = controller.getChildCount()

    fun addChildAt(child: ShadeNode, index: Int) {
        traceSection("ShadeNode#addChildAt") {
            controller.addChildAt(child.controller, index)
            child.controller.onViewAdded()
        }
    }

    fun moveChildTo(child: ShadeNode, index: Int) {
        traceSection("ShadeNode#moveChildTo") {
            controller.moveChildTo(child.controller, index)
            child.controller.onViewMoved()
        }
    }

    fun removeChild(child: ShadeNode, isTransfer: Boolean) {
        traceSection("ShadeNode#removeChild") {
            controller.removeChild(child.controller, isTransfer)
            child.controller.onViewRemoved()
        }
    }

    fun offerToKeepInParentForAnimation(): Boolean {
        return controller.offerToKeepInParentForAnimation()
    }

    fun removeFromParentIfKeptForAnimation(): Boolean {
        return controller.removeFromParentIfKeptForAnimation()
    }

    fun resetKeepInParentForAnimation() {
        controller.resetKeepInParentForAnimation()
    }
}
