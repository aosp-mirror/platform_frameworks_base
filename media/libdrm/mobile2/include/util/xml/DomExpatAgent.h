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
#ifndef _DOMEXPATAGENT_
#define _DOMEXPATAGENT_

#include <Drm2CommonTypes.h>
#include <ofstream.h>
#include <sostream.h>
#include <ustring.h>
#include <sistream.h>
#include <util/domcore/NodeImpl.h>
#include <util/domcore/DOMString.h>
#include "ExpatWrapper.h"
#include "XMLElementImpl.h"
#include "XMLDocumentImpl.h"
using namespace ustl;

class DomExpatAgent : public ExpatWrapper {
public:
    /**
     * Constructor for DomExpatAgent.
     * @param xmlDocPtr XMLDocument pointer.
     */
    DomExpatAgent(XMLDocumentImpl* xmlDocPtr);

    /** Destructor for DomExpatAgent. */
    ~DomExpatAgent();

    /**
     * Generate XML DOM Document from XML source.
     * @param <code>xmlStream</code> the XML source stream.
     * @return ture or false to indicate whether generate successfully.
     */
    bool generateDocumentFromXML(istringstream *xmlStream);

    /**
     * Generate XML stream from XML DOM document.
     * @return xml stream.
     */
    ostringstream* generateXMLFromDocument();

    /**
     * deal with start element in Expat.
     */
    virtual void startElement(const XML_Char *name,
                              const XML_Char **atts);

    /**
     * deal with end element for Expat.
     */
    virtual void endElement(const XML_Char *name);

    /**
     * deal with data handler for Expat.
     */
    virtual void dataHandler(const XML_Char *s, int len);

PRIVATE:
    /**
     * Push a xml element with the specific tag name into stack.
     * @param name The name of tag.
     * @param atts The attributes of related tag.
     */
    void pushTag(const DOMString *name, const XML_Char **atts);

    /**
     * Append text into top element of stack.
     * @param text The data related to the present tag.
     */
    void appendText(const DOMString *text);

    /**
     * Pop the xml element with the specific tag name.
     * @param name The name of tag.
     */
    void popTag(const DOMString *name);

    /**
     * Traverse the XML DOM document starting from specific element.
     * @param root The specific element start to traverse.
     */
    void traverse(ElementImpl *root);

PRIVATE:
    vector<NodeImpl*> mStack; /**< the stack to manage the tag. */
    XMLElementImpl* mTopElementPtr; /**< the top element of the stack. */
    XMLDocumentImpl* mXMLDocumentPtr; /**< XML DOM document pointer. */
    ostringstream mXMLostream; /**< xml output stream. */
};

#endif
