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
 * @author Igor V. Stolyarov
 * @version $Revision$
 */

package java.awt;

import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * The CompositeContext interface specifies the encapsulated and optimized
 * environment for a compositing operation.
 * 
 * @since Android 1.0
 */
public interface CompositeContext {

    /**
     * Composes the two source Raster objects and places the result in the
     * destination WritableRaster.
     * 
     * @param src
     *            the source Raster.
     * @param dstIn
     *            the destination Raster.
     * @param dstOut
     *            the WritableRaster object where the result of composing
     *            operation is stored.
     */
    public void compose(Raster src, Raster dstIn, WritableRaster dstOut);

    /**
     * Releases resources allocated for a context.
     */
    public void dispose();

}
