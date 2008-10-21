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
#include <util/domcore/ElementImpl.h>

/** see ElementImpl.h */
NodeType ElementImpl::getNodeType() const
{
    return ELEMENT_NODE;
}

/** see ElementImpl.h */
const DOMString* ElementImpl::getNodeName() const
{
    return getTagName();
}

/** see ElementImpl.h */
const DOMString* ElementImpl::getTagName() const
{
    return NULL;
}

/** see ElementImpl.h */
void ElementImpl::setAttribute(const DOMString* name, const DOMString* value) throw (DOMException)
{

}

/** see ElementImpl.h */
void ElementImpl::removeAttribute(const DOMString* name) throw (DOMException)
{

}

/** see ElementImpl.h */
const DOMString* ElementImpl::getAttribute(const DOMString* name) const
{
    return NULL;
}

/** see ElementImpl.h */
void ElementImpl::getElementsByTagName(const DOMString* name, NodeListImpl* nodeList) const
{
    NodeImpl* node = getFirstChild();

    if (node == NULL || name == NULL || nodeList == NULL)
        return;

    do {

        if (node->getNodeType() == ELEMENT_NODE) {
            ElementImpl* elementNode = static_cast<ElementImpl*>(node);
            if (*elementNode->getTagName() == *name)
               /* if current is element node and tag name is equal to <code>name</code>,put it into nodeList */
                nodeList->append(node);
               /*
                * visit DOM tree recursively,
                * get all Elements node whose tage name is equal to name.
                */
               elementNode->getElementsByTagName(name, nodeList);
        }

        /* set current node's next sibling node as current node.*/
        node = node->getNextSibling();
    } while(node != NULL);
}

/** see ElementImpl.h */
NodeListImpl* ElementImpl::getElementsByTagName(const DOMString* name) const
{
    NodeListImpl* nodeList = new NodeListImpl();

    if (nodeList == NULL || name == NULL)
        return NULL;

    getElementsByTagName(name,nodeList);

    return nodeList;
}

/** see ElementImpl.h */
bool ElementImpl::hasAttributes() const
{
    return false;
}
