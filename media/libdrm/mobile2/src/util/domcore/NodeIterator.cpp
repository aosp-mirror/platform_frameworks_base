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
#include <util/domcore/NodeImpl.h>
#include <util/domcore/NodeIterator.h>

/** see NodeIterator.h */
NodeIterator::NodeIterator(NodeImpl* start,NodeImpl* scope,NodeImpl* end): scopeNode(scope),endNode(end),curNode(start)
{
}

/** see NodeIterator.h */
NodeImpl* NodeIterator::findNextOrderNode(NodeImpl* node)
{
    if (node == endNode)
        return NULL;

    if (node != NULL) {
        if (node->hasChildNodes() == true) {
            node = node->getFirstChild();
        }else if (node == scopeNode && node->hasChildNodes() == false) {
            node = NULL;
        } else if (node->getNextSibling() != NULL) {
            node = node->getNextSibling();
        } else {
            while (node != scopeNode && node != NULL && node->getNextSibling() == NULL) {
                node = node->getParentNode();
            }
            if (node == scopeNode)
                node = NULL;
            if (node != NULL)
                node = node->getNextSibling();
        }
    }
    if (node == endNode || node == scopeNode)
        node = NULL;

    return node;
}

/** see NodeIterator.h */
NodeImpl* NodeIterator::next()
{
    NodeImpl* node = NULL;

    node = findNextOrderNode(curNode);

    if (node != NULL)
        curNode = node;

    return node;
}

/** see NodeIterator.h */
NodeImpl* NodeIterator::prev()
{
    NodeImpl* node;

    node = findPreviousOrderNode(curNode);

    if (node != NULL)
        curNode = node;

    return node;
}

/** see NodeIterator.h */
NodeImpl* NodeIterator::findPreviousOrderNode(NodeImpl* node)
{
    if (node == NULL || node == endNode)
        return NULL;

    if (node->getPreviousSibling() != NULL) {
        node = node->getPreviousSibling();
        while(node != NULL && node->hasChildNodes() == true)
            node = node->getLastChild();
    } else {
        if (node == scopeNode)
            node = NULL;
        else
            node = node->getParentNode();
    }

    if (node == scopeNode || node == endNode)
        return NULL;

    return node;
}

