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

package java.awt.image;

import java.awt.BufferCapabilities;
import java.awt.Graphics;

/**
 * The BufferStrategy abstract class provides an opportunity to organize the
 * buffers for a Canvas or Window. The BufferStrategy implementation depends on
 * hardware and software limitations. These limitations are detectable through
 * the capabilities object which can be obtained by the GraphicsConfiguration of
 * the Canvas or Window.
 * 
 * @since Android 1.0
 */
public abstract class BufferStrategy {

    /**
     * Returns true if the drawing buffer was lost since the last call of
     * getDrawGraphics.
     * 
     * @return true if the drawing buffer was lost since the last call of
     *         getDrawGraphics, false otherwise.
     */
    public abstract boolean contentsLost();

    /**
     * Returns true if the drawing buffer is restored from a lost state.
     * 
     * @return true if the drawing buffer is restored from a lost state, false
     *         otherwise.
     */
    public abstract boolean contentsRestored();

    /**
     * Gets the BufferCapabilities of BufferStrategy.
     * 
     * @return the BufferCapabilities of BufferStrategy.
     */
    public abstract BufferCapabilities getCapabilities();

    /**
     * Gets the Graphics object to use to draw to the buffer.
     * 
     * @return the Graphics object to use to draw to the buffer.
     */
    public abstract Graphics getDrawGraphics();

    /**
     * Shows the next available buffer.
     */
    public abstract void show();

}
