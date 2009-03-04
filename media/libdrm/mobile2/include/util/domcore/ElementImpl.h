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
#ifndef __DOM_ELEMENT_IMPL__
#define __DOM_ELEMENT_IMPL__
#include "NodeImpl.h"
#include "NodeListImpl.h"
#include "NodeType.h"

class ElementImpl : public NodeImpl
{
public:

        /**
         * The name of the element. For example, in:
         * <pre> &lt;elementExample
         * id="demo"&gt; ... &lt;/elementExample&gt; , </pre>
         *  <code>tagName</code> has
         * the value <code>"elementExample"</code>. Note that this is
         * case-preserving in XML, as are all of the operations of the DOM. The
         * HTML DOM returns the <code>tagName</code> of an HTML element in the
         * canonical uppercase form, regardless of the case in the source HTML
         * document.
         * @return the element's tag name.
         */
        virtual const DOMString* getTagName() const;

        /**
         * Retrieves an attribute value by name.
         * @param name The name of the attribute to retrieve.
         * @return The <code>Attr</code> value as a string, or the empty string
         *   if that attribute does not have a specified or default value.
         */
        virtual const DOMString* getAttribute(const DOMString* name) const;

        /**
         * Adds a new attribute. If an attribute with that name is already present
         * in the element, its value is changed to be that of the value
         * parameter. This value is a simple string; it is not parsed as it is
         * being set. So any markup (such as syntax to be recognized as an
         * entity reference) is treated as literal text, and needs to be
         * appropriately escaped by the implementation when it is written out.
         * In order to assign an attribute value that contains entity
         * references, the user must create an <code>Attr</code> node plus any
         * <code>Text</code> and <code>EntityReference</code> nodes, build the
         * appropriate subtree, and use <code>setAttributeNode</code> to assign
         * it as the value of an attribute.
         * <br>To set an attribute with a qualified name and namespace URI, use
         * the <code>setAttributeNS</code> method.
         * @param name The name of the attribute to create or alter.
         * @param value Value to set in string form.
         * @exception DOMException
         *   INVALID_CHARACTER_ERR: Raised if the specified name contains an
         *   illegal character.
         *   <br>NO_MODIFICATION_ALLOWED_ERR: Raised if this node is readonly.
         */
        virtual void setAttribute(const DOMString* name, const DOMString* value) throw (DOMException);

        /**
         * Removes an attribute by name. If the removed attribute is known to have
         * a default value, an attribute immediately appears containing the
         * default value as well as the corresponding namespace URI, local name,
         * and prefix when applicable.
         * <br>To remove an attribute by local name and namespace URI, use the
         * <code>removeAttributeNS</code> method.
         * @param name The name of the attribute to remove.
         * @exception DOMException
         *   NO_MODIFICATION_ALLOWED_ERR: Raised if this node is readonly.
         */
        virtual void removeAttribute(const DOMString* name) throw (DOMException);

        /**
         * Returns a <code>NodeList</code> of all descendant <code>Elements</code>
         * with a given tag name, in the order in which they are encountered in
         * a preorder traversal of this <code>Element</code> tree.
         * @param name The name of the tag to match on. The special value "*"
         *   matches all tags.
         * @return A list of matching <code>Element</code> nodes.
         */
        NodeListImpl* getElementsByTagName(const DOMString* name) const;

        /** Override getNodeType() method in NodeImpl.h.*/
        virtual bool hasAttributes() const;

        /** Override getNodeName() method in NodeImpl.h.*/
        const DOMString* getNodeName() const;

        /** Override getNodeType() method in NodeImpl.h.*/
        NodeType getNodeType() const;

        /** Defining "click()" method*/
        virtual void click(){}

        /** Defining "blur()" method,*/
        virtual void blur(){}

        /** Defining "focus()" method*/
        virtual void focus(){}

        /** Defining "change()" method*/
        virtual void change(){}

        /** Defining "select()" method*/
        virtual void select(){}

        /** Defining "onClick()" event input,textarea,button, and anchor*/
        virtual bool onClick(){return true;}

        /** Defining "onBlur()" event,for input,textarea,button,anchor and select */
        virtual bool onBlur(){return true;}

        /** Defining "onFocus()" event,for input,textarea,button,anchor and select*/
        virtual bool onFocus(){return true;}

        /** Defining "onChange()" event,for input,textarea and select tag*/
        virtual bool onChange(){return true;}

        /** Defining "onSelect()" event,for textarea and input*/
        virtual bool onSelect(){return true;}

        /**
         * when the end tag of one element is found,this method would be called.The basic action is call seCompleted().
        **/
        virtual void endElement() {}

private:
        /**
         * Get elements whose name match on <code>name</code>,than keep they into <code>nodeList</code>.
         * @param name   The tag name of the elements to match on.
         * @param nodeList keep all the matched element.
         */
        void getElementsByTagName(const DOMString* name,NodeListImpl* nodeList) const;
};
#endif /*__DOM_ELEMENT_IMPL__*/

