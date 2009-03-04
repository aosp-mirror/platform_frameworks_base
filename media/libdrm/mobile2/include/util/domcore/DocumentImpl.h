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
#ifndef __DOM_DOCUMENT_IMPL__
#define __DOM_DOCUMENT_IMPL__
#include "DOMString.h"
#include "NodeImpl.h"
#include "ElementImpl.h"
#include "NodeType.h"
#include "TextImpl.h"
#include "NodeListImpl.h"
#include "DOMException.h"

class DocumentImpl : public NodeImpl
{
private:
        const static DOMString nodeName;
public:
        /**
         * Default constructor for DocumentImpl.
         */
        DocumentImpl();
        /**
         * This is a convenience attribute that allows direct access to the child
         * node that is the root element of the document. For HTML documents,
         * this is the element with the tagName "HTML".
         * @return the pointer to element.
         */
         virtual ElementImpl* getDocumentElement() const;

        /**
         * Creates an element of the type specified. Note that the instance
         * returned implements the <code>Element</code> interface, so attributes
         * can be specified directly on the returned object.
         * <br>In addition, if there are known attributes with default values,
         * <code>Attr</code> nodes representing them are automatically created
         * and attached to the element.
         * <br>To create an element with a qualified name and namespace URI, use
         * the <code>createElementNS</code> method.
         * @param tagName The name of the element type to instantiate. For XML,
         *   this is case-sensitive. For HTML, the <code>tagName</code>
         *   parameter may be provided in any case, but it must be mapped to the
         *   canonical uppercase form by the DOM implementation.
         * @return A new <code>Element</code> object with the
         *   <code>nodeName</code> attribute set to <code>tagName</code>, and
         *   <code>localName</code>, <code>prefix</code>, and
         *   <code>namespaceURI</code> set to <code>null</code>.
         * @exception DOMException
         *   INVALID_CHARACTER_ERR: Raised if the specified name contains an
         *   illegal character.
         */
        virtual ElementImpl* createElement(const DOMString* tagName) const throw (DOMException);

        /**
         * Creates a <code>Text</code> node given the specified string.
         * @param data The data for the node.
         * @return The new <code>Text</code> object.
         */
        virtual TextImpl* createTextNode(const DOMString* data) const;

        /**
         * Returns a <code>NodeList</code> of all the <code>Elements</code> with a
         * given tag name in the order in which they are encountered in a
         * preorder traversal of the <code>Document</code> tree.
         * @param tagname The name of the tag to match on. The special value "*"
         *   matches all tags.
         * @return A new <code>NodeList</code> object containing all the matched
         *   <code>Elements</code>.
         */
         NodeListImpl* getElementsByTagName(const DOMString* tagname) const;

        /** Override getNodeName method in NodeImpl.h.*/
        const DOMString* getNodeName() const;

        /** Override getNodeType method in NodeImpl.h.*/
        NodeType getNodeType() const;

        /**
         *
         * Event Triggered after loaded the document.
         */
        virtual bool onLoad(){return true;}

        /**
         *
         *  Event Triggered when close or switch the document.
         */
        virtual bool onUnLoad(){return true;}

        ~DocumentImpl();
};
#endif /*__DOM_DOCUMENT_IMPL__*/
