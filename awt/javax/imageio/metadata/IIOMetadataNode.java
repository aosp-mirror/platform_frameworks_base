/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.imageio.metadata;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
//???AWT
//import org.w3c.dom.TypeInfo;
//import org.w3c.dom.UserDataHandler;

/**
 * The Class IIOMetadataNode represents a node of the 
 * (DOM-style) metadata tree.
 */
public class IIOMetadataNode implements Element, NodeList {

    /** The node name. */
    private String nodeName;
    
    /** The node value. */
    private String nodeValue;
    
    /** The attributes. */
    private IIOMetadataNodeList attrs = new IIOMetadataNodeList(new ArrayList<IIOMetadataNode>());

    /** The parent node. */
    private IIOMetadataNode parent;
    
    /** The first child node. */
    private IIOMetadataNode firstChild;
    
    /** The last child node. */
    private IIOMetadataNode lastChild;
    
    /** The previous sibling. */
    private IIOMetadataNode previousSibling;
    
    /** The next sibling. */
    private IIOMetadataNode nextSibling;

    /** The number of children. */
    private int nChildren;

    /** The user object associated with this node. */
    private Object userObject;

    /** The text content of this node. */
    private String textContent;

    /**
     * Instantiates a new empty node.
     */
    public IIOMetadataNode() {
    }

    /**
     * Instantiates a new empty node with the specified name.
     * 
     * @param nodeName the node name
     */
    public IIOMetadataNode(String nodeName) {
        this.nodeName = nodeName;
    }

    /**
     * Instantiates a new IIOMetadataNode with the specified 
     * name and value.
     * 
     * @param nodeName the node name
     * @param nodeValue the node value
     */
    private IIOMetadataNode(String nodeName, String nodeValue) {
        this.nodeName = nodeName;
        this.nodeValue = nodeValue;
    }

    public String getTagName() {
        return nodeName;
    }

    public String getAttribute(String name) {
        Attr attrNode = (Attr) attrs.getNamedItem(name);
        return (attrNode == null) ? "" : attrNode.getValue();
    }

    public void setAttribute(String name, String value) throws DOMException {
        Attr attr = (Attr) attrs.getNamedItem(name);
        if (attr != null) {
            attr.setValue(value);
        } else {
            attrs.list.add(new IIOMetadataAttr(name, value, this));
        }
    }

    public void removeAttribute(String name) throws DOMException {
        IIOMetadataAttr attr = (IIOMetadataAttr) attrs.getNamedItem(name);
        if (attr != null) {
            attr.setOwnerElement(null);
            attrs.list.remove(attr);
        }
    }

    public Attr getAttributeNode(String name) {
        return (Attr) attrs.getNamedItem(name);
    }

    public Attr setAttributeNode(Attr newAttr) throws DOMException {
        // Check if this attribute is already in use.
        Element owner = newAttr.getOwnerElement();
        if (owner != null) {
            if (owner == this) { // Replacing an attribute node by itself has no effect
                return null;
            } else {
                throw new DOMException(DOMException.INUSE_ATTRIBUTE_ERR, "Attribute is already in use");
            }
        }

        String name = newAttr.getName();
        Attr oldAttr = getAttributeNode(name);
        if (oldAttr != null) {
            removeAttributeNode(oldAttr);
        }

        IIOMetadataAttr iioAttr;
        if (newAttr instanceof IIOMetadataAttr) {
            iioAttr = (IIOMetadataAttr) newAttr;
            iioAttr.setOwnerElement(this);
        } else {
            iioAttr = new IIOMetadataAttr(name, newAttr.getValue(), this);
        }

        attrs.list.add(iioAttr);

        return oldAttr;
    }

    public Attr removeAttributeNode(Attr oldAttr) throws DOMException {
        if (!attrs.list.remove(oldAttr)) { // Not found
            throw new DOMException(DOMException.NOT_FOUND_ERR, "No such attribute!");
        }

        ((IIOMetadataAttr)oldAttr).setOwnerElement(null);

        return oldAttr;
    }

    public NodeList getElementsByTagName(String name) {
        ArrayList<IIOMetadataNode> nodes = new ArrayList<IIOMetadataNode>();

        // Non-recursive tree walk
        Node pos = this;

        while (pos != null) {
            if (pos.getNodeName().equals(name)) {
                nodes.add((IIOMetadataNode)pos);
            }

            Node nextNode = pos.getFirstChild();

            while (nextNode == null) {
                if (pos == this) {
                    break;
                }

                nextNode = pos.getNextSibling();

                if (nextNode == null) {
                    pos = pos.getParentNode();

                    if (pos == null || pos == this) {
                        nextNode = null;
                        break;
                    }
                }
            }
            pos = nextNode;
        }

        return new IIOMetadataNodeList(nodes);
    }

    public String getAttributeNS(String namespaceURI, String localName) throws DOMException {
        return getAttribute(localName);
    }

    public void setAttributeNS(String namespaceURI, String qualifiedName, String value) throws DOMException {
        setAttribute(qualifiedName, value);
    }

    public void removeAttributeNS(String namespaceURI, String localName) throws DOMException {
        removeAttribute(localName);
    }

    public Attr getAttributeNodeNS(String namespaceURI, String localName) throws DOMException {
        return getAttributeNode(localName);
    }

    public Attr setAttributeNodeNS(Attr newAttr) throws DOMException {
        return setAttributeNode(newAttr);
    }

    public NodeList getElementsByTagNameNS(String namespaceURI, String localName) throws DOMException {
        return getElementsByTagName(localName);
    }

    public boolean hasAttribute(String name) {
        return attrs.getNamedItem(name) != null;
    }

    public boolean hasAttributeNS(String namespaceURI, String localName) throws DOMException {
        return hasAttribute(localName);
    }

    //???AWT
    /*
    public TypeInfo getSchemaTypeInfo() {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }*/

    public void setIdAttribute(String name, boolean isId) throws DOMException {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public void setIdAttributeNS(String namespaceURI, String localName, boolean isId) throws DOMException {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public void setIdAttributeNode(Attr idAttr, boolean isId) throws DOMException {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getNodeValue() throws DOMException {
        return nodeValue;
    }

    public void setNodeValue(String nodeValue) throws DOMException {
        this.nodeValue = nodeValue;
    }

    public short getNodeType() {
        return ELEMENT_NODE;
    }

    public Node getParentNode() {
        return parent;
    }

    public NodeList getChildNodes() {
        return this;
    }

    public Node getFirstChild() {
        return firstChild;
    }

    public Node getLastChild() {
        return lastChild;
    }

    public Node getPreviousSibling() {
        return previousSibling;
    }

    public Node getNextSibling() {
        return nextSibling;
    }

    public NamedNodeMap getAttributes() {
        return attrs;
    }

    public Document getOwnerDocument() {
        return null;
    }

    public Node insertBefore(Node newChild, Node refChild) throws DOMException {
        if (newChild == null) {
            throw new IllegalArgumentException("newChild == null!");
        }

        IIOMetadataNode newIIOChild = (IIOMetadataNode) newChild;
        IIOMetadataNode refIIOChild = (IIOMetadataNode) refChild;

        newIIOChild.parent = this;

        if (refIIOChild == null) {
            newIIOChild.nextSibling = null;
            newIIOChild.previousSibling = lastChild;

            // Fix this node
            lastChild = newIIOChild;
            if (firstChild == null) {
                firstChild = newIIOChild;
            }
        } else {
            newIIOChild.nextSibling = refIIOChild;
            newIIOChild.previousSibling = refIIOChild.previousSibling;

            // Fix this node
            if (firstChild == refIIOChild) {
                firstChild = newIIOChild;
            }

            // Fix next node
            if (refIIOChild != null) {
                refIIOChild.previousSibling = newIIOChild;
            }
        }

        // Fix prev node
        if (newIIOChild.previousSibling != null) {
            newIIOChild.previousSibling.nextSibling = newIIOChild;
        }

        nChildren++;

        return newIIOChild;
    }

    public Node replaceChild(Node newChild, Node oldChild) throws DOMException {
        if (newChild == null) {
            throw new IllegalArgumentException("newChild == null!");
        }

        IIOMetadataNode newIIOChild = (IIOMetadataNode) newChild;
        IIOMetadataNode oldIIOChild = (IIOMetadataNode) oldChild;

        IIOMetadataNode next = oldIIOChild.nextSibling;
        IIOMetadataNode previous = oldIIOChild.previousSibling;

        // Fix new node
        newIIOChild.parent = this;
        newIIOChild.nextSibling = next;
        newIIOChild.previousSibling = previous;

        // Fix this node
        if (lastChild == oldIIOChild) {
            lastChild = newIIOChild;
        }
        if (firstChild == oldIIOChild) {
            firstChild = newIIOChild;
        }

        // Fix siblings
        if (next != null) {
            next.previousSibling = newIIOChild;
        }
        if (previous != null) {
            previous.nextSibling = newIIOChild;
        }

        // Fix old child
        oldIIOChild.parent = null;
        oldIIOChild.nextSibling = next;
        oldIIOChild.previousSibling = previous;

        return oldIIOChild;
    }

    public Node removeChild(Node oldChild) throws DOMException {
        if (oldChild == null) {
            throw new IllegalArgumentException("oldChild == null!");
        }

        IIOMetadataNode oldIIOChild = (IIOMetadataNode) oldChild;

        // Fix next and previous
        IIOMetadataNode previous = oldIIOChild.previousSibling;
        IIOMetadataNode next = oldIIOChild.nextSibling;

        if (previous != null) {
            previous.nextSibling = next;
        }
        if (next != null) {
            next.previousSibling = previous;
        }

        // Fix this node
        if (lastChild == oldIIOChild) {
            lastChild = previous;
        }
        if (firstChild == oldIIOChild) {
            firstChild = next;
        }
        nChildren--;

        // Fix old child
        oldIIOChild.parent = null;
        oldIIOChild.previousSibling = null;
        oldIIOChild.nextSibling = null;

        return oldIIOChild;
    }

    public Node appendChild(Node newChild) throws DOMException {
        return insertBefore(newChild, null);
    }

    public boolean hasChildNodes() {
        return nChildren != 0;
    }

    public Node cloneNode(boolean deep) {
        IIOMetadataNode cloned = new IIOMetadataNode(nodeName);
        cloned.setUserObject(getUserObject());

        if (deep) { // Clone recursively
            IIOMetadataNode c = firstChild;
            while (c != null) {
                cloned.insertBefore(c.cloneNode(true), null);
                c = c.nextSibling;
            }
        }

        return cloned;  //To change body of implemented methods use File | Settings | File Templates.
    }

    public void normalize() {
        // Do nothing
    }

    public boolean isSupported(String feature, String version) {
        return false;
    }

    public String getNamespaceURI() {
        return null;
    }

    public String getPrefix() {
        return null;
    }

    public void setPrefix(String prefix) throws DOMException {
        // Do nothing
    }

    public String getLocalName() {
        return nodeName;
    }

    public boolean hasAttributes() {
        return attrs.list.size() > 0;
    }

    public String getBaseURI() {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public short compareDocumentPosition(Node other) throws DOMException {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public String getTextContent() throws DOMException {
        return textContent;
    }

    public void setTextContent(String textContent) throws DOMException {
        this.textContent = textContent;
    }

    public boolean isSameNode(Node other) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public String lookupPrefix(String namespaceURI) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public boolean isDefaultNamespace(String namespaceURI) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public String lookupNamespaceURI(String prefix) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public boolean isEqualNode(Node arg) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public Object getFeature(String feature, String version) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    //???AWT
    /*
    public Object setUserData(String key, Object data, UserDataHandler handler) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }*/

    public Object getUserData(String key) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    public Node item(int index) {
        if (index < 0 || index >= nChildren) {
            return null;
        }

        Node n;
        for (n = getFirstChild(); index > 0; index--) {
            n = n.getNextSibling();
        }

        return n;
    }

    public int getLength() {
        return nChildren;
    }

    /**
     * Gets the user object associated with this node.
     * 
     * @return the user object associated with this node
     */
    public Object getUserObject() {
        return userObject;
    }

    /**
     * Sets the user object associated with this node.
     * 
     * @param userObject the new user object associated with this node
     */
    public void setUserObject(Object userObject) {
        this.userObject = userObject;
    }

    /**
     * The Class IIOMetadataAttr.
     */
    private class IIOMetadataAttr extends IIOMetadataNode implements Attr {
        
        /** The owner element. */
        private Element ownerElement;

        /**
         * Instantiates a new iIO metadata attr.
         * 
         * @param name the name
         * @param value the value
         * @param owner the owner
         */
        public IIOMetadataAttr(String name, String value, Element owner) {
            super(name, value);
            this.ownerElement = owner;
        }

        public String getName() {
            return getNodeName();
        }

        public boolean getSpecified() {
            return true;
        }

        public String getValue() {
            return nodeValue;
        }

        public void setValue(String value) throws DOMException {
            nodeValue = value;
        }

        public Element getOwnerElement() {
            return ownerElement;
        }

        /**
         * Sets the owner element.
         * 
         * @param ownerElement the new owner element
         */
        public void setOwnerElement(Element ownerElement) {
            this.ownerElement = ownerElement;
        }

        public boolean isId() {
            throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
        }

        @Override
        public short getNodeType() {
            return ATTRIBUTE_NODE;
        }
    }

    /**
     * The Class IIOMetadataNodeList.
     */
    private class IIOMetadataNodeList implements NodeList, NamedNodeMap {
        
        /** The list. */
        private List<IIOMetadataNode> list;

        /**
         * Instantiates a new iIO metadata node list.
         * 
         * @param list the list
         */
        IIOMetadataNodeList(List<IIOMetadataNode> list) {
            this.list = list;
        }

        public Node item(int index) {
            try {
                return list.get(index);
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        }

        public int getLength() {
            return list.size();
        }

        public Node getNamedItem(String name) {
            for(IIOMetadataNode node:list) {
                if (name.equals(node.getNodeName())) {
                    return node;
                }
            }
            return null;
        }

        public Node setNamedItem(Node arg) throws DOMException {
            throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR, "This NamedNodeMap is read-only!");
        }

        public Node removeNamedItem(String name) throws DOMException {
            throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR, "This NamedNodeMap is read-only!");
        }

        public Node getNamedItemNS(String namespaceURI, String localName) throws DOMException {
            return getNamedItem(localName);
        }

        public Node setNamedItemNS(Node arg) throws DOMException {
            throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR, "This NamedNodeMap is read-only!");
        }

        public Node removeNamedItemNS(String namespaceURI, String localName) throws DOMException {
            throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR, "This NamedNodeMap is read-only!");
        }
    }
}
