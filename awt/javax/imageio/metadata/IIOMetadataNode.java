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
 * The Class IIOMetadataNode represents a node of the (DOM-style) metadata tree.
 * 
 * @since Android 1.0
 */
public class IIOMetadataNode implements Element, NodeList {

    /**
     * The node name.
     */
    private String nodeName;

    /**
     * The node value.
     */
    private String nodeValue;

    /**
     * The attributes.
     */
    private IIOMetadataNodeList attrs = new IIOMetadataNodeList(new ArrayList<IIOMetadataNode>());

    /**
     * The parent node.
     */
    private IIOMetadataNode parent;

    /**
     * The first child node.
     */
    private IIOMetadataNode firstChild;

    /**
     * The last child node.
     */
    private IIOMetadataNode lastChild;

    /**
     * The previous sibling.
     */
    private IIOMetadataNode previousSibling;

    /**
     * The next sibling.
     */
    private IIOMetadataNode nextSibling;

    /**
     * The number of children.
     */
    private int nChildren;

    /**
     * The user object associated with this node.
     */
    private Object userObject;

    /**
     * The text content of this node.
     */
    private String textContent;

    /**
     * Instantiates a new empty node.
     */
    public IIOMetadataNode() {
    }

    /**
     * Instantiates a new empty node with the specified name.
     * 
     * @param nodeName
     *            the node name.
     */
    public IIOMetadataNode(String nodeName) {
        this.nodeName = nodeName;
    }

    /**
     * Instantiates a new IIOMetadataNode with the specified name and value.
     * 
     * @param nodeName
     *            the node name.
     * @param nodeValue
     *            the node value.
     */
    private IIOMetadataNode(String nodeName, String nodeValue) {
        this.nodeName = nodeName;
        this.nodeValue = nodeValue;
    }

    public String getTagName() {
        return nodeName;
    }

    public String getAttribute(String name) {
        Attr attrNode = (Attr)attrs.getNamedItem(name);
        return (attrNode == null) ? "" : attrNode.getValue();
    }

    public void setAttribute(String name, String value) throws DOMException {
        Attr attr = (Attr)attrs.getNamedItem(name);
        if (attr != null) {
            attr.setValue(value);
        } else {
            attrs.list.add(new IIOMetadataAttr(name, value, this));
        }
    }

    public void removeAttribute(String name) throws DOMException {
        IIOMetadataAttr attr = (IIOMetadataAttr)attrs.getNamedItem(name);
        if (attr != null) {
            attr.setOwnerElement(null);
            attrs.list.remove(attr);
        }
    }

    public Attr getAttributeNode(String name) {
        return (Attr)attrs.getNamedItem(name);
    }

    public Attr setAttributeNode(Attr newAttr) throws DOMException {
        // Check if this attribute is already in use.
        Element owner = newAttr.getOwnerElement();
        if (owner != null) {
            if (owner == this) { // Replacing an attribute node by itself has no
                // effect
                return null;
            } else {
                throw new DOMException(DOMException.INUSE_ATTRIBUTE_ERR,
                        "Attribute is already in use");
            }
        }

        String name = newAttr.getName();
        Attr oldAttr = getAttributeNode(name);
        if (oldAttr != null) {
            removeAttributeNode(oldAttr);
        }

        IIOMetadataAttr iioAttr;
        if (newAttr instanceof IIOMetadataAttr) {
            iioAttr = (IIOMetadataAttr)newAttr;
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

    public void setAttributeNS(String namespaceURI, String qualifiedName, String value)
            throws DOMException {
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

    public NodeList getElementsByTagNameNS(String namespaceURI, String localName)
            throws DOMException {
        return getElementsByTagName(localName);
    }

    public boolean hasAttribute(String name) {
        return attrs.getNamedItem(name) != null;
    }

    public boolean hasAttributeNS(String namespaceURI, String localName) throws DOMException {
        return hasAttribute(localName);
    }

    // ???AWT
    /*
     * public TypeInfo getSchemaTypeInfo() { throw new
     * DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported"); }
     */

    /**
     * <i>Description copied from interface: org.w3c.dom.Element (DOM Level
     * 3)</i>
     * <p>
     * If the parameter isId is true, this method declares the specified
     * attribute to be a user-determined ID attribute . This affects the value
     * of Attr.isId and the behavior of Document.getElementById, but does not
     * change any schema that may be in use, in particular this does not affect
     * the Attr.schemaTypeInfo of the specified Attr node. Use the value false
     * for the parameter isId to undeclare an attribute for being a
     * user-determined ID attribute. To specify an attribute by local name and
     * namespace URI, use the setIdAttributeNS method.
     * </p>
     * 
     * @param name
     *            the name of the attribute.
     * @param isId
     *            the flag which determines whether this attribute is of type
     *            ID.
     * @throws DOMException
     *             if a DOM error occurred while setting the attribute type.
     *             <p>
     *             NO_MODIFICATION_ALLOWED_ERR: Raised if this node is readonly.
     *             <br>
     *             NOT_FOUND_ERR: Raised if the specified node is not an
     *             attribute of this element.
     *             </p>
     */
    public void setIdAttribute(String name, boolean isId) throws DOMException {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Element (DOM Level
     * 3)</i>
     * <p>
     * If the parameter isId is true, this method declares the specified
     * attribute to be a user-determined ID attribute . This affects the value
     * of Attr.isId and the behavior of Document.getElementById, but does not
     * change any schema that may be in use, in particular this does not affect
     * the Attr.schemaTypeInfo of the specified Attr node. Use the value false
     * for the parameter isId to undeclare an attribute for being a
     * user-determined ID attribute.
     * </p>
     * 
     * @param namespaceURI
     *            the namespace URI of the attribute.
     * @param localName
     *            the local name of the attribute.
     * @param isId
     *            the flag which determines whether this attribute is of type
     *            ID.
     * @throws DOMException
     *             if a DOM error occurred while setting the attribute type.
     *             <p>
     *             NO_MODIFICATION_ALLOWED_ERR: Raised if this node is readonly.
     *             <br>
     *             NOT_FOUND_ERR: Raised if the specified node is not an
     *             attribute of this element.
     *             </p>
     */
    public void setIdAttributeNS(String namespaceURI, String localName, boolean isId)
            throws DOMException {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Element (DOM Level
     * 3)</i>
     * <p>
     * If the parameter isId is true, this method declares the specified
     * attribute to be a user-determined ID attribute . This affects the value
     * of Attr.isId and the behavior of Document.getElementById, but does not
     * change any schema that may be in use, in particular this does not affect
     * the Attr.schemaTypeInfo of the specified Attr node. Use the value false
     * for the parameter isId to undeclare an attribute for being a
     * user-determined ID attribute.
     * </p>
     * 
     * @param idAttr
     *            the attribute node.
     * @param isId
     *            the flag which determines whether this attribute is of type
     *            ID.
     * @throws DOMException
     *             if a DOM error occurred while setting the attribute type.
     *             <p>
     *             NO_MODIFICATION_ALLOWED_ERR: Raised if this node is readonly.
     *             <br>
     *             NOT_FOUND_ERR: Raised if the specified node is not an
     *             attribute of this element.
     *             </p>
     */
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

        IIOMetadataNode newIIOChild = (IIOMetadataNode)newChild;
        IIOMetadataNode refIIOChild = (IIOMetadataNode)refChild;

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

        IIOMetadataNode newIIOChild = (IIOMetadataNode)newChild;
        IIOMetadataNode oldIIOChild = (IIOMetadataNode)oldChild;

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

        IIOMetadataNode oldIIOChild = (IIOMetadataNode)oldChild;

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

        return cloned; // To change body of implemented methods use File |
        // Settings | File Templates.
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

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * The absolute base URI of this node or null if the implementation wasn't
     * able to obtain an absolute URI. This value is computed as described in.
     * However, when the Document supports the feature "HTML" [DOM Level 2
     * HTML], the base URI is computed using first the value of the href
     * attribute of the HTML BASE element if any, and the value of the
     * documentURI attribute from the Document interface otherwise.
     * </p>
     * 
     * @return the string representation of the absolute base URI.
     */
    public String getBaseURI() {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * Compares the reference node, i.e. the node on which this method is being
     * called, with a node, i.e. the one passed as a parameter, with regard to
     * their position in the document and according to the document order.
     * </p>
     * 
     * @param other
     *            the node to compare against the reference node.
     * @return Returns how the node is positioned relatively to the reference
     *         node.
     * @throws DOMException
     *             NOT_SUPPORTED_ERR: when the compared nodes are from different
     *             DOM implementations that do not coordinate to return
     *             consistent implementation-specific results.
     */
    public short compareDocumentPosition(Node other) throws DOMException {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * This attribute returns the text content of this node and its descendants.
     * When it is defined to be null, setting it has no effect. On setting, any
     * possible children this node may have are removed and, if it the new
     * string is not empty or null, replaced by a single Text node containing
     * the string this attribute is set to. On getting, no serialization is
     * performed, the returned string does not contain any markup. No whitespace
     * normalization is performed and the returned string does not contain the
     * white spaces in element content (see the attribute
     * Text.isElementContentWhitespace). Similarly, on setting, no parsing is
     * performed either, the input string is taken as pure textual content. The
     * string returned is made of the text content of this node depending on its
     * type, as defined below:
     * <table>
     * <tr>
     * <td><strong>Node type</strong></td>
     * <td><strong>Content</strong></td>
     * </tr>
     * <tr>
     * <td>ELEMENT_NODE, ATTRIBUTE_NODE, ENTITY_NODE, ENTITY_REFERENCE_NODE,
     * DOCUMENT_FRAGMENT_NODE</td>
     * <td>concatenation of the textContent attribute value of every child node,
     * excluding COMMENT_NODE and PROCESSING_INSTRUCTION_NODE nodes. This is the
     * empty string if the node has no children.</td>
     * </tr>
     * <tr>
     * <td>TEXT_NODE, CDATA_SECTION_NODE, COMMENT_NODE,
     * PROCESSING_INSTRUCTION_NODE</td>
     * <td>nodeValue</td>
     * </tr>
     * <tr>
     * <td>DOCUMENT_NODE, DOCUMENT_TYPE_NODE, NOTATION_NODE</td>
     * <td>null</td>
     * </tr>
     * </table>
     * </p>
     * 
     * @return the text content depending on the type of this node.
     * @throws DOMException
     *             DOMSTRING_SIZE_ERR: Raised when it would return more
     *             characters than fit in a DOMString variable on the
     *             implementation platform.
     */
    public String getTextContent() throws DOMException {
        return textContent;
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * This attribute returns the text content of this node and its descendants.
     * When it is defined to be null, setting it has no effect. On setting, any
     * possible children this node may have are removed and, if it the new
     * string is not empty or null, replaced by a single Text node containing
     * the string this attribute is set to. On getting, no serialization is
     * performed, the returned string does not contain any markup. No whitespace
     * normalization is performed and the returned string does not contain the
     * white spaces in element content (see the attribute
     * Text.isElementContentWhitespace). Similarly, on setting, no parsing is
     * performed either, the input string is taken as pure textual content. The
     * string returned is made of the text content of this node depending on its
     * type, as defined below:
     * <table>
     * <tr>
     * <td><strong>Node type</strong></td>
     * <td><strong>Content</strong></td>
     * </tr>
     * <tr>
     * <td>ELEMENT_NODE, ATTRIBUTE_NODE, ENTITY_NODE, ENTITY_REFERENCE_NODE,
     * DOCUMENT_FRAGMENT_NODE</td>
     * <td>concatenation of the textContent attribute value of every child node,
     * excluding COMMENT_NODE and PROCESSING_INSTRUCTION_NODE nodes. This is the
     * empty string if the node has no children.</td>
     * </tr>
     * <tr>
     * <td>TEXT_NODE, CDATA_SECTION_NODE, COMMENT_NODE,
     * PROCESSING_INSTRUCTION_NODE</td>
     * <td>nodeValue</td>
     * </tr>
     * <tr>
     * <td>DOCUMENT_NODE, DOCUMENT_TYPE_NODE, NOTATION_NODE</td>
     * <td>null</td>
     * </tr>
     * </table>
     * </p>
     * 
     * @param textContent
     *            the text content for this node.
     * @throws DOMException
     *             NO_MODIFICATION_ALLOWED_ERR: Raised when the node is
     *             readonly.
     */
    public void setTextContent(String textContent) throws DOMException {
        this.textContent = textContent;
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * Returns whether this node is the same node as the given one. This method
     * provides a way to determine whether two Node references returned by the
     * implementation reference the same object. When two Node references are
     * references to the same object, even if through a proxy, the references
     * may be used completely interchangeably, such that all attributes have the
     * same values and calling the same DOM method on either reference always
     * has exactly the same effect.
     * </p>
     * 
     * @param other
     *            the node to test against.
     * @return true, if the nodes are the same, false otherwise.
     */
    public boolean isSameNode(Node other) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * Look up the prefix associated to the given namespace URI, starting from
     * this node. The default namespace declarations are ignored by this method.
     * See for details on the algorithm used by this method.
     * </p>
     * 
     * @param namespaceURI
     *            the namespace URI to look for.
     * @return the associated namespace prefix if found or null if none is
     *         found. If more than one prefix are associated to the namespace
     *         prefix, the returned namespace prefix is implementation
     *         dependent.
     */
    public String lookupPrefix(String namespaceURI) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * This method checks if the specified namespaceURI is the default namespace
     * or not.
     * </p>
     * 
     * @param namespaceURI
     *            the namespace URI to look for.
     * @return true, if the specified namespaceURI is the default namespace,
     *         false otherwise.
     */
    public boolean isDefaultNamespace(String namespaceURI) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * Look up the namespace URI associated to the given prefix, starting from
     * this node. See for details on the algorithm used by this method.
     * </p>
     * 
     * @param prefix
     *            the prefix to look for. If this parameter is null, the method
     *            will return the default namespace URI if any.
     * @return the associated namespace URI or null if none is found.
     */
    public String lookupNamespaceURI(String prefix) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * Tests whether two nodes are equal. This method tests for equality of
     * nodes, not sameness (i.e., whether the two nodes are references to the
     * same object) which can be tested with Node.isSameNode(). All nodes that
     * are the same will also be equal, though the reverse may not be true. Two
     * nodes are equal if and only if the following conditions are satisfied:
     * <p>
     * <li>The two nodes are of the same type.</li>
     * <li>The following string attributes are equal: nodeName, localName,
     * namespaceURI, prefix, nodeValue . This is: they are both null, or they
     * have the same length and are character for character identical.</li>
     * <li>The attributes NamedNodeMaps are equal. This is: they are both null,
     * or they have the same length and for each node that exists in one map
     * there is a node that exists in the other map and is equal, although not
     * necessarily at the same index.</li>
     * <li>The childNodes NodeLists are equal. This is: they are both null, or
     * they have the same length and contain equal nodes at the same index. Note
     * that normalization can affect equality; to avoid this, nodes should be
     * normalized before being compared.</li>
     * </p>
     * For two DocumentType nodes to be equal, the following conditions must
     * also be satisfied:
     * <p>
     * <li>The following string attributes are equal: publicId, systemId,
     * internalSubset.</li>
     * <li>The entities NamedNodeMaps are equal.</li>
     * <li>The notations NamedNodeMaps are equal.</li>
     * </p>
     * On the other hand, the following do not affect equality: the
     * ownerDocument, baseURI, and parentNode attributes, the specified
     * attribute for Attr nodes, the schemaTypeInfo attribute for Attr and
     * Element nodes, the Text.isElementContentWhitespace attribute for Text
     * nodes, as well as any user data or event listeners registered on the
     * nodes. </p>
     * <p>
     * Note: As a general rule, anything not mentioned in the description above
     * is not significant in consideration of equality checking. Note that
     * future versions of this specification may take into account more
     * attributes and implementations conform to this specification are expected
     * to be updated accordingly.
     * </p>
     * 
     * @param arg
     *            the node to compare equality with.
     * @return true, if the nodes are equal, false otherwise.
     */
    public boolean isEqualNode(Node arg) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * This method returns a specialized object which implements the specialized
     * APIs of the specified feature and version, as specified in. The
     * specialized object may also be obtained by using binding-specific casting
     * methods but is not necessarily expected to, as discussed in. This method
     * also allow the implementation to provide specialized objects which do not
     * support the Node interface.
     * </p>
     * 
     * @param feature
     *            the name of the feature requested. Note that any plus sign "+"
     *            prepended to the name of the feature will be ignored since it
     *            is not significant in the context of this method.
     * @param version
     *            this is the version number of the feature to test.
     * @return the object which implements the specialized APIs of the specified
     *         feature and version, if any, or null if there is no object which
     *         implements interfaces associated with that feature. If the
     *         DOMObject returned by this method implements the Node interface,
     *         it must delegate to the primary core Node and not return results
     *         inconsistent with the primary core Node such as attributes,
     *         childNodes, etc.
     */
    public Object getFeature(String feature, String version) {
        throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "Method not supported");
    }

    // ???AWT
    /*
     * public Object setUserData(String key, Object data, UserDataHandler
     * handler) { throw new DOMException(DOMException.NOT_SUPPORTED_ERR,
     * "Method not supported"); }
     */

    /**
     * <i>Description copied from interface: org.w3c.dom.Node (DOM Level 3)</i>
     * <p>
     * Retrieves the object associated to a key on a this node. The object must
     * first have been set to this node by calling setUserData with the same
     * key.
     * </p>
     * 
     * @param key
     *            the key the object is associated to.
     * @return the DOMUserData associated to the given key on this node, or null
     *         if there was none.
     */
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
     * @return the user object associated with this node.
     */
    public Object getUserObject() {
        return userObject;
    }

    /**
     * Sets the user object associated with this node.
     * 
     * @param userObject
     *            the new user object associated with this node.
     */
    public void setUserObject(Object userObject) {
        this.userObject = userObject;
    }

    /**
     * The Class IIOMetadataAttr.
     */
    private class IIOMetadataAttr extends IIOMetadataNode implements Attr {

        /**
         * The owner element.
         */
        private Element ownerElement;

        /**
         * Instantiates a new iIO metadata attr.
         * 
         * @param name
         *            the name.
         * @param value
         *            the value.
         * @param owner
         *            the owner.
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
         * @param ownerElement
         *            the new owner element.
         */
        public void setOwnerElement(Element ownerElement) {
            this.ownerElement = ownerElement;
        }

        /**
         * @return
         */
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

        /**
         * The list.
         */
        private List<IIOMetadataNode> list;

        /**
         * Instantiates a new iIO metadata node list.
         * 
         * @param list
         *            the list.
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
            for (IIOMetadataNode node : list) {
                if (name.equals(node.getNodeName())) {
                    return node;
                }
            }
            return null;
        }

        public Node setNamedItem(Node arg) throws DOMException {
            throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR,
                    "This NamedNodeMap is read-only!");
        }

        public Node removeNamedItem(String name) throws DOMException {
            throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR,
                    "This NamedNodeMap is read-only!");
        }

        public Node getNamedItemNS(String namespaceURI, String localName) throws DOMException {
            return getNamedItem(localName);
        }

        public Node setNamedItemNS(Node arg) throws DOMException {
            throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR,
                    "This NamedNodeMap is read-only!");
        }

        public Node removeNamedItemNS(String namespaceURI, String localName) throws DOMException {
            throw new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR,
                    "This NamedNodeMap is read-only!");
        }
    }
}
