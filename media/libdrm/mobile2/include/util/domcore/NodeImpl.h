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
#ifndef  __DOM_NODE_IMPL__
#define  __DOM_NODE_IMPL__

#include "DOMException.h"
#include "NodeType.h"
#include "DOMString.h"
class LayoutAttr;
class DocumentImpl;
class NodeImpl {
private:
    /** The pointer to first children */
    NodeImpl* firstChild;

    /** The pointer to lastChild children */
    NodeImpl* lastChild;

    /** The pointer to previous sibling children */
    NodeImpl* previousSibling;

    /** The pointer to next sibling children */
    NodeImpl* nextSibling;

    /** The pointer to parent */
    NodeImpl* parent;

    /** Current node's document context */
    const DocumentImpl* document;

   /**
     * Add next slibing node
     * @param node the node to be add.
     */
    void appendNextSibling(NodeImpl* node);

public:
    /**
     * Default constuctor.
     */
    NodeImpl(): firstChild(NULL),lastChild(NULL),previousSibling(NULL),nextSibling(NULL),parent(NULL),document(NULL) {};

    /**
     * Set <code>parentNode</code> as current node's parent.
     *
     * @param parentNode The specify parent node.
     */
    void setParent(NodeImpl* parentNode);

    /**
     * Set the node immediately following node.
     *
     * @param siblingNode The special node be insert after current node.
     */
    void setNextSibling(NodeImpl* siblingNode);

    /**
     * Returns the node immediately preceding this node.
     *
     * @param siblingNode The special node be insert before current node.
     */
    void setPreviousSibling(NodeImpl* siblingNode);

    /**
     * Set <code>childNode</code> as current node's first children. If current
     * node have first child node,it will replace with the <code>childNode</code>.
     *
     * @param childNode The special node be set as the first children node of current
     *                  node.
     */
    void setFirstChild(NodeImpl* childNode);

    /**
     * Set <code>childNode</code> as current node's last children. If current
     * node have last child node,it will replace with the <code>childNode</code>.
     *
     * @param childNode The special node be set as the last children node of current
     *                  node.
     */
    void setLastChild(NodeImpl* childNode);

    /**
     * The name of this node, depending on its type;
     * @return the node's name.
     */
    virtual const DOMString* getNodeName() const = 0;

    /**
     * The value of this node, depending on its type;
     * When it is defined to be <code>null</code>, setting it has no effect.
     * @return the value of node.
     * @exception DOMException
     * DOMSTRING_SIZE_ERR: Raised when it would return more characters than
     * fit in a <code>DOMString</code> variable on the implementation
     * platform.
     */
    virtual const DOMString* getNodeValue() const throw (DOMException);

    /**
     * Set the node value.
     * @param nodeValue the node value
     * @exception DOMException
     * NO_MODIFICATION_ALLOWED_ERR: Raised when the node is readonly.
     */
    virtual void setNodeValue(DOMString* nodeValue) throw (DOMException);

    /**
     * A code representing the type of the underlying object, as defined above.
     * @return the node's type.
     */
    virtual NodeType getNodeType() const = 0;

    /**
     * Returns whether this node (if it is an element) has any attributes.
     * @return <code>true</code> if this node has any attributes,
     *   <code>false</code> otherwise.
     * @since DOM Level 2
     */
    virtual bool hasAttributes() const;

    /**
     * The parent of this node. All nodes, except <code>Attr</code>,
     * <code>Document</code>, <code>DocumentFragment</code>,
     * <code>Entity</code>, and <code>Notation</code> may have a parent.
     * However, if a node has just been created and not yet added to the
     * tree, or if it has been removed from the tree, this is
     * <code>NULL</code>.
     * @return return current node's parent.
     */
    NodeImpl* getParentNode() const;

    /**
     * The first child of this node. If there is no such node, this returns
     * <code>NULL</code>.
     * @return current node  first children.
     */
    NodeImpl* getFirstChild() const;

    /**
     * The last child of this node. If there is no such node, this returns
     * <code>NULL</code>.
     * @return current  node last children.
     */
    NodeImpl* getLastChild() const;

    /**
     * The node immediately preceding this node. If there is no such node,
     * this returns <code>NULL</code>.
     * @return current node previous sibling children.
     */
    NodeImpl* getPreviousSibling() const;

    /**
     * The node immediately following this node. If there is no such node,
     * this returns <code>NULL</code>.
     * @return return current  node next sibling children.
     */
    NodeImpl* getNextSibling() const;

    /**
     * Inserts the node <code>newChild</code> before the existing child node
     * <code>refChild</code>. If <code>refChild</code> is <code>NULL</code>,
     * insert <code>newChild</code> at the end of the list of children.
     * <br>If <code>newChild</code> is a <code>DocumentFragment</code> object,
     * all of its children are inserted, in the same order, before
     * <code>refChild</code>. If the <code>newChild</code> is already in the
     * tree, it is first removed.
     * @param newChild The node to insert.
     * @param refChild The reference node, i.e., the node before which the
     *   new node must be inserted.
     * @return The node being inserted.
     * @exception DOMException
     *   HIERARCHY_REQUEST_ERR: Raised if this node is of a type that does not
     *   allow children of the type of the <code>newChild</code> node, or if
     *   the node to insert is one of this node's ancestors or this node
     *   itself.
     *   <br>WRONG_DOCUMENT_ERR: Raised if <code>newChild</code> was created
     *   from a different document than the one that created this node.
     *   <br>NO_MODIFICATION_ALLOWED_ERR: Raised if this node is readonly or
     *   if the parent of the node being inserted is readonly.
     *   <br>NOT_FOUND_ERR: Raised if <code>refChild</code> is not a child of
     *   this node.
     */
    NodeImpl* insertBefore(NodeImpl* newChild, NodeImpl* refChild) throw (DOMException);

    /**
     * Removes the child node indicated by <code>oldChild</code> from the list
     * of children, and returns it.
     * @param oldChild The node being removed.
     * @return The node removed.
     * @exception DOMException
     *   NO_MODIFICATION_ALLOWED_ERR: Raised if this node is readonly.
     *   <br>NOT_FOUND_ERR: Raised if <code>oldChild</code> is not a child of
     *   this node.
     */
    NodeImpl* removeChild(NodeImpl* oldChild) throw (DOMException);

    /**
     * Adds the node <code>newChild</code> to the end of the list of children
     * of this node. If the <code>newChild</code> is already in the tree, it
     * is first removed.
     * @param newChild The node to add.If it is a
     *   <code>DocumentFragment</code> object, the entire contents of the
     *   document fragment are moved into the child list of this node
     * @return The node added.
     * @exception DOMException
     *   HIERARCHY_REQUEST_ERR: Raised if this node is of a type that does not
     *   allow children of the type of the <code>newChild</code> node, or if
     *   the node to append is one of this node's ancestors or this node
     *   itself.
     *   <br>WRONG_DOCUMENT_ERR: Raised if <code>newChild</code> was created
     *   from a different document than the one that created this node.
     *   <br>NO_MODIFICATION_ALLOWED_ERR: Raised if this node is readonly or
     *   if the previous parent of the node being inserted is readonly.
     */
    NodeImpl* appendChild(NodeImpl* newChild) throw (DOMException);

    /**
     * Returns whether this node has any children.
     * @return <code>true</code> if this node has any children,
     *   <code>false</code> otherwise.
     */
    bool hasChildNodes() const;

    virtual ~NodeImpl() {};

    /**
     * Get the LayoutAttr of this node
     * @return the pointer to  LayoutAttr
     */
    virtual LayoutAttr* getLayoutAttr() const { return NULL;}

    /**
     * Set the LayoutAttr of this node
     * @param attr the attributes to be set
     * @return void
     */
    virtual void setLayoutAttr(LayoutAttr* attr) { return;}

    /**
     * Set document context.
     * @param document The specify document context.
     */
    void setDocument(const DocumentImpl* document);

    /**
     * Get document context.
     * @return the current node's document context.
     */
    const DocumentImpl* getDocument() const;
};
#endif  /*__DOM_NODE_IMPL__*/

