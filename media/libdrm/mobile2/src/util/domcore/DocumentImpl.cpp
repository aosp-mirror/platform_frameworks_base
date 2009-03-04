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
#include <util/domcore/DocumentImpl.h>

const DOMString DocumentImpl::nodeName = "#DOCUMENT";

/** see DocumentImpl.h */
DocumentImpl::DocumentImpl()
{

}

/** see DocumentImpl.h */
ElementImpl* DocumentImpl::getDocumentElement() const
{
    return NULL;
}

/** see DocumentImpl.h */
ElementImpl* DocumentImpl::createElement(const DOMString* tagName) const throw (DOMException)
{
    return NULL;
}

/** see DocumentImpl.h */
TextImpl* DocumentImpl::createTextNode(const DOMString* data) const
{
    TextImpl* text = new TextImpl(data);

    if (text != NULL)
        text->setDocument(this);

    return text;
}

/** see DocumentImpl.h */
NodeListImpl* DocumentImpl::getElementsByTagName(const DOMString* tagname) const
{
    ElementImpl* element = getDocumentElement();
    NodeListImpl* list = NULL;

    if (element != NULL)
        list = element->getElementsByTagName(tagname);

    return list;
}

/** see DocumentImpl.h */
const DOMString* DocumentImpl::getNodeName() const
{
    return &nodeName;
}

/** see DocumentImpl.h */
NodeType DocumentImpl::getNodeType() const
{
    return DOCUMENT_NODE;
}

/** see DocumentImpl.h */
DocumentImpl::~DocumentImpl()
{

}

