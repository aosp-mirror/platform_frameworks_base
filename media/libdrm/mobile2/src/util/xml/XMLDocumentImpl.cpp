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
#include <util/xml/XMLDocumentImpl.h>
#include <util/xml/XMLElementImpl.h>

/** see XMLDocumentImpl.h */
XMLDocumentImpl::XMLDocumentImpl()
{}

/** see XMLDocumentImpl.h */
XMLDocumentImpl::~XMLDocumentImpl()
{}

/** see XMLDocumentImpl.h */
ElementImpl* XMLDocumentImpl::getDocumentElement() const
{
    XMLElementImpl *element = (XMLElementImpl *)(this->getFirstChild());
    return element;
}

/** see XMLDocumentImpl.h */
ElementImpl* XMLDocumentImpl::createElement(const DOMString* tagName) const throw (DOMException)
{
    if (tagName)
    {
        XMLElementImpl *element = new XMLElementImpl(tagName);
        return element;
    }
    return NULL;
}

/** see XMLDocumentImpl.h */
TextImpl* XMLDocumentImpl::createTextNode(const DOMString* data) const
{
    if (data)
    {
        TextImpl *text = new TextImpl(data);
        return text;
    }
    return NULL;
}

