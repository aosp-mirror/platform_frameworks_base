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
#include <util/domcore/DocumentImpl.h>

/** see NodeImpl.h. */
void NodeImpl::setParent(NodeImpl* parentNode)
{
    this->parent = parentNode;
}

/** see NodeImpl.h. */
void NodeImpl::setNextSibling(NodeImpl* siblingNode)
{
    this->nextSibling = siblingNode;
}
/** see NodeImpl.h. */
void NodeImpl::setPreviousSibling(NodeImpl* siblingNode)
{
    this->previousSibling = siblingNode;
}

/** see NodeImpl.h. */
void NodeImpl::setFirstChild(NodeImpl* childNode)
{
    this->firstChild = childNode;
}

/** see NodeImpl.h. */
void NodeImpl::setLastChild(NodeImpl* childNode)
{
    this->lastChild = childNode;
}

/** see NodeImpl.h. */
NodeImpl* NodeImpl::getParentNode() const
{
    return parent;
}

/** see NodeImpl.h. */
NodeImpl* NodeImpl::getFirstChild() const
{
    return firstChild;
}

/** see NodeImpl.h. */
NodeImpl* NodeImpl::getLastChild() const
{
    return lastChild;
}

/** see NodeImpl.h. */
NodeImpl* NodeImpl::getPreviousSibling() const
{
    return previousSibling;
}

/** see NodeImpl.h. */
NodeImpl* NodeImpl::getNextSibling() const
{
    return nextSibling;
}

/** see NodeImpl.h. */
NodeImpl* NodeImpl::insertBefore(NodeImpl* newChild, NodeImpl* refChild) throw (DOMException)
{
    if (newChild == NULL)
        #if PLATFORM_ANDROID
            return NULL;
        #else
            throw DOMException(DOMException::WRONG_DOCUMENT_ERR);
        #endif
    if (refChild == NULL || refChild->getParentNode() != this)
        #if PLATFORM_ANDROID
            return NULL;
        #else
            throw DOMException(DOMException::NOT_FOUND_ERR);
        #endif

    NodeImpl* parentNode = newChild->getParentNode();

    if (parentNode != NULL)
        parentNode->removeChild(newChild);

    NodeImpl* prevSiblingNode = refChild->getPreviousSibling();

    if (prevSiblingNode != NULL)
        prevSiblingNode->appendNextSibling(newChild);
    else
        setFirstChild(newChild);

    newChild->appendNextSibling(refChild);
    newChild->setParent(this);

    return newChild;
}

/** see NodeImpl.h. */
NodeImpl* NodeImpl::removeChild(NodeImpl* oldChild) throw (DOMException)
{

    if (oldChild == NULL || oldChild->getParentNode() != this )
    #if PLATFORM_ANDROID
        return NULL;
    #else
        throw DOMException(DOMException::NOT_FOUND_ERR);
    #endif

    NodeImpl* parentNode = oldChild->getParentNode();
    NodeImpl* nextSiblingNode = oldChild->getNextSibling();
    NodeImpl* prevSiblingNode = oldChild->getPreviousSibling();

    if (prevSiblingNode == NULL && nextSiblingNode != NULL) {
        /*
         * children's previous sibling node == NULL and next sibling node !=
         * NULL, means the children node is the first node of its parent.
         * so set the children's next sibling node as the first node of its parent.
         */
        parentNode->setFirstChild(nextSiblingNode);
        nextSiblingNode->setPreviousSibling(NULL);

    } else if (prevSiblingNode != NULL && nextSiblingNode == NULL) {
        /*
         * children's previous sibling node != NULL and next sibling node ==
         * NULL, means the child node is the last node of parent.so set the
         * last node of children's parent as children's previous sibling node.
         */
        prevSiblingNode->setNextSibling(NULL);
        parentNode->setLastChild(prevSiblingNode);

    } else if (prevSiblingNode != NULL && nextSiblingNode != NULL) {
        /*
         * children's previous sibling node != NULL and next sibling node !=
         * NULL,means the node is neither first child nor last children of its parent.
         */
        prevSiblingNode->appendNextSibling(nextSiblingNode);

    } else if (prevSiblingNode == NULL && nextSiblingNode == NULL) {
        /*
         * this means it's only one children node of its parent.
         * so adjust the first child and last child to NULL when remove the children node.
         */
        this->setFirstChild(NULL);
        this->setLastChild(NULL);
    }

    oldChild->setParent(NULL);
    oldChild->setNextSibling(NULL);
    oldChild->setPreviousSibling(NULL);

    return oldChild;
}

/** see NodeImpl.h. */
void NodeImpl::appendNextSibling(NodeImpl* node)
{
    if (node == NULL)
        return;

    setNextSibling(node);
    node->setPreviousSibling(this);
}

/** see NodeImpl.h. */
NodeImpl* NodeImpl::appendChild(NodeImpl* newChild) throw (DOMException)
{
    if (newChild == NULL)
        #if PLATFORM_ANDROID
            return NULL;
        #else
            throw DOMException(DOMException::WRONG_DOCUMENT_ERR);
        #endif
    /* If newChild have parent,remove it from its parent at first.*/
    NodeImpl* parent = newChild->getParentNode();
    if (parent != NULL)
        parent->removeChild(newChild);

    if (getFirstChild() == NULL && getLastChild() == NULL) {
        /* There are not any nodes in current node.*/
        setFirstChild(newChild);
    } else if (getLastChild() != NULL) {
        getLastChild()->appendNextSibling(newChild);
    }

    newChild->setParent(this);
    setLastChild(newChild);


    return newChild;
}

/** see NodeImpl.h. */
bool NodeImpl::hasChildNodes() const
{
    return getFirstChild() != NULL;
}

/** see NodeImpl.h. */
const DOMString* NodeImpl::getNodeValue() const throw (DOMException)
{
    return NULL;
}

/** see NodeImpl.h. */
void NodeImpl::setNodeValue(DOMString* nodeValue) throw (DOMException)
{
}

/** see NodeImpl.h. */
bool NodeImpl::hasAttributes() const
{
    return false;
}

/** see NodeImpl.h */
const DocumentImpl* NodeImpl::getDocument() const
{
    return document;
}

/** see NodeImpl.h */
void NodeImpl::setDocument(const DocumentImpl* document)
{
    this->document = document;
}
