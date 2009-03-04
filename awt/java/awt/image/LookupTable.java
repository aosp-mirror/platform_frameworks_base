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
 * @author Oleg V. Khaschansky
 * @version $Revision$
 *
 * @date: Oct 14, 2005
 */

package java.awt.image;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * This abstract LookupTable class represents lookup table which is defined with
 * the number of components and offset value. ByteLookupTable and
 * ShortLookupTable classes are subclasses of LookupTable which contains byte
 * and short data tables as an input arrays for bands or components of image.
 * 
 * @since Android 1.0
 */
public abstract class LookupTable {

    /**
     * The offset.
     */
    private int offset;

    /**
     * The num components.
     */
    private int numComponents;

    /**
     * Instantiates a new LookupTable with the specified offset value and number
     * of components.
     * 
     * @param offset
     *            the offset value.
     * @param numComponents
     *            the number of components.
     */
    protected LookupTable(int offset, int numComponents) {
        if (offset < 0) {
            // awt.232=Offset should be not less than zero
            throw new IllegalArgumentException(Messages.getString("awt.232")); //$NON-NLS-1$
        }
        if (numComponents < 1) {
            // awt.233=Number of components should be positive
            throw new IllegalArgumentException(Messages.getString("awt.233")); //$NON-NLS-1$
        }

        this.offset = offset;
        this.numComponents = numComponents;
    }

    /**
     * Gets the offset value of this Lookup table.
     * 
     * @return the offset value of this Lookup table.
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Gets the number of components of this Lookup table.
     * 
     * @return the number components of this Lookup table.
     */
    public int getNumComponents() {
        return numComponents;
    }

    /**
     * Returns an integer array which contains samples of the specified pixel which
     * is translated with the lookup table of this LookupTable. The resulted
     * array is stored to the dst array.
     * 
     * @param src
     *            the source array.
     * @param dst
     *            the destination array where the result can be stored.
     * @return the integer array of translated samples of a pixel.
     */
    public abstract int[] lookupPixel(int[] src, int[] dst);
}
