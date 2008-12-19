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

package java.awt.image.renderable;

import java.awt.RenderingHints;
import java.awt.image.RenderedImage;

/**
 * A factory for creating RenderedImage objects based on parameters and
 * rendering hints.
 * 
 * @since Android 1.0
 */
public interface RenderedImageFactory {

    /**
     * Creates the rendered image.
     * 
     * @param a0
     *            the ParameterBlock.
     * @param a1
     *            the RenderingHints.
     * @return the rendered image.
     */
    public RenderedImage create(ParameterBlock a0, RenderingHints a1);

}
