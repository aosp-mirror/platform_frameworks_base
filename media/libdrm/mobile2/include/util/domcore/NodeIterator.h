/*
 * Copyright (C) 2007 The Android Open Source Project
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
#ifndef __DOM_NODE_ITERATOR__
#define __DOM_NODE_ITERATOR__
class NodeImpl;
/**
 * The Iterator is used to visit DOM_TREE.
 * <code>Attention</code>:The Iterator is not safe.
 * When the caller using the Iterator to access the tree,
 * the underlying data was modified, the next() or prev() may not return the right result.
 * means we have a <code>restriction</code>: the Iterator can only be used in the case that the tree structure will
 * not be modified before the end of iteration.
 */
class NodeIterator {
private:
    NodeImpl* scopeNode;/** The specify the range of iterating */
    NodeImpl* endNode;  /** The specify the end position of iterating */
    NodeImpl* curNode;  /** The position of current node.*/

    /**
     * Find the specify node's next order node.
     * @param node The specify node.
     * @return The next order node when success.
     *         NULL when has an error.
     */
    NodeImpl* findNextOrderNode(NodeImpl* node);

    /**
     * Find the specify node's previous order node.
     * @param node The specify node.
     * @return The previous order node when success.
     *         NULL when has an error.
     */
    NodeImpl* findPreviousOrderNode(NodeImpl* node);
public:
    /**
     * Construct for NodeIterator.
     * we must specify <code>start</code> value when we want iterate the DOM_TREE.
     * and we also can specify the <code>scope</code> if want restrict the range of iterator.
     * (eg: restrict the range of iterating at a subtree).otherwise it will iterate the whole DOM_TREE.
     * @param start The start position.
     * @param scope The scope of iterating.
     * @param end The end position of iterating.
     */
    NodeIterator(NodeImpl* start, NodeImpl* scope = NULL, NodeImpl* end = NULL);

    /**
     * Get next order node at current position in DOM_TREE.
     * @return NULL On there is not node can be get.
     *         The pointer of node On can get next node.
     */
    NodeImpl* next();

    /**
     * Get next order node at current position in DOM_TREE.
     * @return NULL On there is not node can be get.
     *         The pointer of node On can get previous node.
     */
    NodeImpl* prev();
};
#endif /*  __DOM_NODE_ITERATOR__ */
