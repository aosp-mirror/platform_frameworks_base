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

import org.apache.harmony.x.imageio.metadata.IIOMetadataUtils;
import org.w3c.dom.Node;

/**
 * The class IIOMetadata represents the metadata (bundled with an image) as a
 * Dom-type tree.
 * 
 * @since Android 1.0
 */
public abstract class IIOMetadata {

    /**
     * Whether the standard metadata format is supported.
     */
    protected boolean standardFormatSupported;

    /**
     * The native metadata format name.
     */
    protected String nativeMetadataFormatName;

    /**
     * The native metadata format class name.
     */
    protected String nativeMetadataFormatClassName;

    /**
     * The extra metadata format names.
     */
    protected String[] extraMetadataFormatNames;

    /**
     * The extra metadata format class names.
     */
    protected String[] extraMetadataFormatClassNames;

    /**
     * The default controller.
     */
    protected IIOMetadataController defaultController;

    /**
     * The controller.
     */
    protected IIOMetadataController controller;

    /**
     * Instantiates a new IIOMetadata with no data set.
     */
    protected IIOMetadata() {
    }

    /**
     * Instantiates a new IIOMetadata with the specified data parameters.
     * 
     * @param standardMetadataFormatSupported
     *            whether the standard metadata format is supported.
     * @param nativeMetadataFormatName
     *            the native metadata format name.
     * @param nativeMetadataFormatClassName
     *            the native metadata format class name.
     * @param extraMetadataFormatNames
     *            the extra metadata format names.
     * @param extraMetadataFormatClassNames
     *            the extra metadata format class names.
     */
    protected IIOMetadata(boolean standardMetadataFormatSupported, String nativeMetadataFormatName,
            String nativeMetadataFormatClassName, String[] extraMetadataFormatNames,
            String[] extraMetadataFormatClassNames) {
        standardFormatSupported = standardMetadataFormatSupported;
        this.nativeMetadataFormatName = nativeMetadataFormatName;
        this.nativeMetadataFormatClassName = nativeMetadataFormatClassName;
        if (extraMetadataFormatNames == null) {
            if (extraMetadataFormatClassNames != null) {
                throw new IllegalArgumentException(
                        "extraMetadataFormatNames == null && extraMetadataFormatClassNames != null!");
            }
        } else {
            if (extraMetadataFormatClassNames == null) {
                throw new IllegalArgumentException(
                        "extraMetadataFormatNames != null && extraMetadataFormatClassNames == null!");
            }
            if (extraMetadataFormatNames.length == 0) {
                throw new IllegalArgumentException("extraMetadataFormatNames.length == 0!");
            }
            if (extraMetadataFormatClassNames.length != extraMetadataFormatNames.length) {
                throw new IllegalArgumentException(
                        "extraMetadataFormatClassNames.length != extraMetadataFormatNames.length!");
            }
            this.extraMetadataFormatNames = extraMetadataFormatNames.clone();
            this.extraMetadataFormatClassNames = extraMetadataFormatClassNames.clone();
        }
    }

    /**
     * Gets the metadata as tree-type document.
     * 
     * @param formatName
     *            the format name.
     * @return the node in tree format.
     */
    public abstract Node getAsTree(String formatName);

    /**
     * Checks if the metadata is read only.
     * 
     * @return true, if the metadata is read only.
     */
    public abstract boolean isReadOnly();

    /**
     * Merges the specified tree with this metadata tree.
     * 
     * @param formatName
     *            the format of the specified tree.
     * @param root
     *            the root node of the metadata tree.
     * @throws IIOInvalidTreeException
     *             if the specified tree is incompatible with the this metadata
     *             tree.
     */
    public abstract void mergeTree(String formatName, Node root) throws IIOInvalidTreeException;

    /**
     * Resets the controller.
     */
    public abstract void reset();

    /**
     * Gets the controller associated with this metadata document.
     * 
     * @return the controller.
     */
    public IIOMetadataController getController() {
        return controller;
    }

    /**
     * Checks whether this metadata has a controller.
     * 
     * @return true, if this metadata has a controller.
     */
    public boolean hasController() {
        return getController() != null;
    }

    /**
     * Activate the controller.
     * 
     * @return true, if successful.
     */
    public boolean activateController() {
        if (!hasController()) {
            throw new IllegalStateException("hasController() == false!");
        }
        return getController().activate(this);
    }

    /**
     * Gets the default controller.
     * 
     * @return the default controller.
     */
    public IIOMetadataController getDefaultController() {
        return defaultController;
    }

    /**
     * Gets the extra metadata format names.
     * 
     * @return the extra metadata format names.
     */
    public String[] getExtraMetadataFormatNames() {
        return extraMetadataFormatNames == null ? null : extraMetadataFormatNames.clone();
    }

    /**
     * Gets the metadata format.
     * 
     * @param formatName
     *            the format name.
     * @return the metadata format.
     */
    public IIOMetadataFormat getMetadataFormat(String formatName) {
        return IIOMetadataUtils.instantiateMetadataFormat(formatName, standardFormatSupported,
                nativeMetadataFormatName, nativeMetadataFormatClassName, extraMetadataFormatNames,
                extraMetadataFormatClassNames);
    }

    /**
     * Gets the native metadata format name.
     * 
     * @return the native metadata format name.
     */
    public String getNativeMetadataFormatName() {
        return nativeMetadataFormatName;
    }

    /**
     * Checks if the standard metadata format is supported.
     * 
     * @return true, if the standard metadata format is supported.
     */
    public boolean isStandardMetadataFormatSupported() {
        return standardFormatSupported;
    }

    /**
     * Gets the metadata format names.
     * 
     * @return the metadata format names.
     */
    public String[] getMetadataFormatNames() {
        ArrayList<String> res = new ArrayList<String>();

        String nativeMetadataFormatName = getNativeMetadataFormatName();
        boolean standardFormatSupported = isStandardMetadataFormatSupported();
        String extraMetadataFormatNames[] = getExtraMetadataFormatNames();

        if (standardFormatSupported) {
            res.add(IIOMetadataFormatImpl.standardMetadataFormatName);
        }
        if (nativeMetadataFormatName != null) {
            res.add(nativeMetadataFormatName);
        }
        if (extraMetadataFormatNames != null) {
            for (String extraMetadataFormatName : extraMetadataFormatNames) {
                res.add(extraMetadataFormatName);
            }
        }

        return res.size() > 0 ? res.toArray(new String[0]) : null;
    }

    /**
     * Gets the standard chroma node.
     * 
     * @return the standard chroma node.
     */
    protected IIOMetadataNode getStandardChromaNode() {
        return null;
    }

    /**
     * Gets the standard compression node.
     * 
     * @return the standard compression node.
     */
    protected IIOMetadataNode getStandardCompressionNode() {
        return null;
    }

    /**
     * Gets the standard data node.
     * 
     * @return the standard data node.
     */
    protected IIOMetadataNode getStandardDataNode() {
        return null;
    }

    /**
     * Gets the standard dimension node.
     * 
     * @return the standard dimension node.
     */
    protected IIOMetadataNode getStandardDimensionNode() {
        return null;
    }

    /**
     * Gets the standard document node.
     * 
     * @return the standard document node.
     */
    protected IIOMetadataNode getStandardDocumentNode() {
        return null;
    }

    /**
     * Gets the standard text node.
     * 
     * @return the standard text node.
     */
    protected IIOMetadataNode getStandardTextNode() {
        return null;
    }

    /**
     * Gets the standard tile node.
     * 
     * @return the standard tile node.
     */
    protected IIOMetadataNode getStandardTileNode() {
        return null;
    }

    /**
     * Gets the standard transparency node.
     * 
     * @return the standard transparency node.
     */
    protected IIOMetadataNode getStandardTransparencyNode() {
        return null;
    }

    /**
     * Gets the metadata as a tree in standard format.
     * 
     * @return the metadata as a tree in standard format.
     */
    protected final IIOMetadataNode getStandardTree() {
        // Create root node
        IIOMetadataNode root = new IIOMetadataNode(IIOMetadataFormatImpl.standardMetadataFormatName);

        Node node;
        if ((node = getStandardChromaNode()) != null) {
            root.appendChild(node);
        }
        if ((node = getStandardCompressionNode()) != null) {
            root.appendChild(node);
        }
        if ((node = getStandardDataNode()) != null) {
            root.appendChild(node);
        }
        if ((node = getStandardDimensionNode()) != null) {
            root.appendChild(node);
        }
        if ((node = getStandardDocumentNode()) != null) {
            root.appendChild(node);
        }
        if ((node = getStandardTextNode()) != null) {
            root.appendChild(node);
        }
        if ((node = getStandardTileNode()) != null) {
            root.appendChild(node);
        }
        if ((node = getStandardTransparencyNode()) != null) {
            root.appendChild(node);
        }

        return root;
    }

    /**
     * Sets the controller.
     * 
     * @param controller
     *            the new controller.
     */
    public void setController(IIOMetadataController controller) {
        this.controller = controller;
    }

    /**
     * Sets the from tree.
     * 
     * @param formatName
     *            the name of the metatdata format of the from tree.
     * @param root
     *            the root node of the from tree.
     * @throws IIOInvalidTreeException
     *             if the tree or its format is not compatible with this
     *             metadata.
     */
    public void setFromTree(String formatName, Node root) throws IIOInvalidTreeException {
        reset();
        mergeTree(formatName, root);
    }
}
