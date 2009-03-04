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
#include <util/xml/XMLElementImpl.h>
#include <util/domcore/TextImpl.h>

/** see XMLElementImpl.h */
XMLElementImpl::XMLElementImpl(const DOMString *tag)
{
    if (tag)
    {
        mTagName = *tag;
    }
}

/** see XMLElementImpl.h */
XMLElementImpl::~XMLElementImpl()
{
}

/** see XMLElementImpl.h */
const DOMString* XMLElementImpl::getTagName() const
{
    return &mTagName;
}

/** see XMLElementImpl.h */
void XMLElementImpl::setAttribute(const DOMString* name, const DOMString* value)
                                  throw (DOMException)
{
    if (name && value)
    {
        mAttributeMap[*name] = *value;
    }
}

/** see XMLElementImpl.h */
void XMLElementImpl::removeAttribute(const DOMString* name) throw (DOMException)
{
    if (name)
    {
       mAttributeMap.erase(*name);
    }
}

/** see XMLElementImpl.h */
const DOMString* XMLElementImpl::getAttribute(const DOMString* name) const
{
    if (name)
    {
        DOMStringMap::const_iterator pos = mAttributeMap.find(*name);

        if (pos != mAttributeMap.end())
        {
           return &(pos->second);
        }

    }
    return NULL;
}

/** see XMLElementImpl.h */
bool XMLElementImpl::hasAttributes() const
{
    return !mAttributeMap.empty();
}

/** see XMLElementImpl.h */
const DOMStringMap* XMLElementImpl::getAttributeMap() const
{
    return &mAttributeMap;
}

/** see XMLElementImpl.h */
const NodeImpl* XMLElementImpl::findSoloChildNode(const char* tag) const
{
    if (NULL == tag)
    {
        return NULL;
    }

    string token;
    NodeListImpl *nodeList = NULL;
    const NodeImpl *childNode = NULL;

    token.assign(tag);
    nodeList = getElementsByTagName(&token);

    if (nodeList->getLength() > 0)
    {
         childNode = nodeList->item(0);
    }

    return childNode;
}

/** see XMLElementImpl.h */
const string* XMLElementImpl::getSoloText(const char* tag) const
{
    const NodeImpl *textNode = this->findSoloChildNode(tag);

    if (textNode)
    {
        textNode = textNode->getFirstChild();
        if (textNode)
        {
            return static_cast<const TextImpl*>(textNode)->getData();
        }
    }

    return NULL;
}

/** see XMLElementImpl.h */
const XMLElementImpl* XMLElementImpl::getSoloElement(const char* tag) const
{
    const NodeImpl *node = findSoloChildNode(tag);
    if (node)
    {
        return static_cast<const XMLElementImpl*>(node);
    }

    return NULL;
}
