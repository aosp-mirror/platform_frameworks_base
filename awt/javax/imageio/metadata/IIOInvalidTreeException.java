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

import org.w3c.dom.Node;
import javax.imageio.IIOException;

/**
 * The IIOInvalidTreeException provides notification about fails of
 * IIOMetadataNodes tree parsing by IIOMetadata object.
 * 
 * @since Android 1.0
 */
public class IIOInvalidTreeException extends IIOException {

    /**
     * The offending node.
     */
    protected Node offendingNode = null;

    /**
     * Instantiates an IIOInvalidTreeException with the specified detailed
     * message and specified offending Node.
     * 
     * @param message
     *            the detailed message.
     * @param offendingNode
     *            the offending node.
     */
    public IIOInvalidTreeException(String message, Node offendingNode) {
        super(message);
        this.offendingNode = offendingNode;
    }

    /**
     * Instantiates a new IIOInvalidTreeException with the specified detailed
     * message and specified offending Node.
     * 
     * @param message
     *            the detailed message.
     * @param cause
     *            the cause of this exception.
     * @param offendingNode
     *            the offending node.
     */
    public IIOInvalidTreeException(String message, Throwable cause, Node offendingNode) {
        super(message, cause);
        this.offendingNode = offendingNode;
    }

    /**
     * Gets the offending node.
     * 
     * @return the offending node.
     */
    public Node getOffendingNode() {
        return offendingNode;
    }
}
