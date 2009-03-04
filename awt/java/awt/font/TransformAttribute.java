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
/**
 * @author Ilya S. Okomin
 * @version $Revision$
 */

package java.awt.font;

import java.awt.geom.AffineTransform;
import java.io.Serializable;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The TransformAttribute class is a wrapper for the AffineTransform class in
 * order to use it as attribute.
 * 
 * @since Android 1.0
 */
public final class TransformAttribute implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 3356247357827709530L;

    // affine transform of this TransformAttribute instance
    /**
     * The transform.
     */
    private AffineTransform fTransform;

    /**
     * Instantiates a new TransformAttribute from the specified AffineTransform.
     * 
     * @param transform
     *            the AffineTransform to be wrapped.
     */
    public TransformAttribute(AffineTransform transform) {
        if (transform == null) {
            // awt.94=transform can not be null
            throw new IllegalArgumentException(Messages.getString("awt.94")); //$NON-NLS-1$
        }
        if (!transform.isIdentity()) {
            this.fTransform = new AffineTransform(transform);
        }
    }

    /**
     * Gets the initial AffineTransform which is wrapped.
     * 
     * @return the initial AffineTransform which is wrapped.
     */
    public AffineTransform getTransform() {
        if (fTransform != null) {
            return new AffineTransform(fTransform);
        }
        return new AffineTransform();
    }

    /**
     * Checks if this transform is an identity transform.
     * 
     * @return true, if this transform is an identity transform, false
     *         otherwise.
     */
    public boolean isIdentity() {
        return (fTransform == null);
    }

}
