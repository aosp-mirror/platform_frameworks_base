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
 * @author Alexey A. Petrenko
 * @version $Revision$
 */

package java.awt;

/**
 * The ImageCapabilities class gives information about an image's capabilities.
 * 
 * @since Android 1.0
 */
public class ImageCapabilities implements Cloneable {

    /**
     * The accelerated.
     */
    private final boolean accelerated;

    /**
     * Instantiates a new ImageCapabilities with the specified acceleration flag
     * which indicates whether acceleration is desired or not.
     * 
     * @param accelerated
     *            the accelerated flag.
     */
    public ImageCapabilities(boolean accelerated) {
        this.accelerated = accelerated;
    }

    /**
     * Returns a copy of this ImageCapabilities object.
     * 
     * @return the copy of this ImageCapabilities object.
     */
    @Override
    public Object clone() {
        return new ImageCapabilities(accelerated);
    }

    /**
     * Returns true if the Image of this ImageCapabilities is or can be
     * accelerated.
     * 
     * @return true, if the Image of this ImageCapabilities is or can be
     *         accelerated, false otherwise.
     */
    public boolean isAccelerated() {
        return accelerated;
    }

    /**
     * Returns true if this ImageCapabilities applies to the VolatileImage which
     * can lose its surfaces.
     * 
     * @return true if this ImageCapabilities applies to the VolatileImage which
     *         can lose its surfaces, false otherwise.
     */
    public boolean isTrueVolatile() {
        return true;
    }
}
