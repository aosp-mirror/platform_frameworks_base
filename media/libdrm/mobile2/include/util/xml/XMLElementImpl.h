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
#ifndef _XMLELEMENTIMPL_H_
#define _XMLELEMENTIMPL_H_

#include <Drm2CommonTypes.h>
#include <util/domcore/ElementImpl.h>
#include <util/domcore/DOMString.h>
#include <umap.h>
#include <ustring.h>
using namespace ustl;

typedef map<DOMString, DOMString> DOMStringMap;

class XMLElementImpl : public ElementImpl {
public:
    /**
     * Constructor for XMLElementImpl.
     * @param tag The name of the tag.
     */
    XMLElementImpl(const DOMString *tag);

    /** Destructor for XMLElementImpl. */
    ~XMLElementImpl();

    /**
     * Get the attribute map of the XML element.
     * @return <code>DOMStringMap</code>
     */
    const DOMStringMap* getAttributeMap() const;

    /**
     * Get the tag name of the element.
     * return tag name.
     */
    virtual const DOMString* getTagName() const;

    /**
     * Set the attribute of the element.
     * @param name The key of the attribute.
     * @param value The value of the attribute.
     */
    virtual void setAttribute(const DOMString* name, const DOMString* value) throw (DOMException);

    /**
     * Remove the specific attribute.
     * @param name The key of the attribute.
     * @exception DOMException.
     */
    virtual void removeAttribute(const DOMString* name) throw (DOMException);

    /**
     * Get the specific attribute.
     * @param name The key of the attribute.
     * @return the value of the attribute.
     */
    virtual const DOMString* getAttribute(const DOMString* name) const;

    /**
     * Detect whether element has attributes or not.
     * @return true or false to indicate the result.
     */
    virtual bool hasAttributes() const;

    /**
     * Find the first child node in element by its tag name.
     * @param element the specific element to be found.
     * @param tag the specific tag name to be searched.
     * @return NULL if not found otherwise the child node.
     */
    const NodeImpl* findSoloChildNode(const char* tag) const;

    /**
     * Get the first text containted in first child of the element.
     * @param tag the specific tag name to be searched.
     * @return NULL if not found otherwise the text.
     */
    const string* getSoloText(const char* tag) const;

    /**
     * Get the first child xml element containted in the element.
     * @param tag the specific tag name to be searched.
     * @return NULL if not found otherwise the element.
     */
    const XMLElementImpl* getSoloElement(const char* tag) const;

PRIVATE:
    DOMString mTagName; /**< The tag name. */
    DOMStringMap mAttributeMap; /** The map of attributes. */
};

#endif
