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
#ifndef _XMLDOCUMENTIMPL_H_
#define _XMLDOCUMENTIMPL_H_

#include <Drm2CommonTypes.h>
#include <util/domcore/DocumentImpl.h>

class XMLDocumentImpl : public DocumentImpl {
public:
    /** Constructor for XMLDocumentImpl. */
    XMLDocumentImpl();

    /** Destructor for XMLDocumentImpl. */
    ~XMLDocumentImpl();

    /**
     * Get the first child element of the document.
     * @return the first child <code>Element</code> of document.
     */
    virtual ElementImpl* getDocumentElement() const;

    /**
     * Create a XML element with the specific name.
     * @param tagName The specific tag name.
     * @return a new xml <code>Element</code>
     * @exception DOMException
     */
    virtual ElementImpl* createElement(const DOMString* tagName) const throw (DOMException);

    /**
     * Create a text node with the specific data.
     * @param data The specific data.
     * @return a new <code>Text</code> node.
     */
    virtual TextImpl* createTextNode(const DOMString* data) const;
};

#endif
