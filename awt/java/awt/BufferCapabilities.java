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
 * The BufferCapabilities class represents the capabilities and other properties
 * of the image buffers.
 * 
 * @since Android 1.0
 */
public class BufferCapabilities implements Cloneable {

    /**
     * The front buffer capabilities.
     */
    private final ImageCapabilities frontBufferCapabilities;

    /**
     * The back buffer capabilities.
     */
    private final ImageCapabilities backBufferCapabilities;

    /**
     * The flip contents.
     */
    private final FlipContents flipContents;

    /**
     * Instantiates a new BufferCapabilities object.
     * 
     * @param frontBufferCapabilities
     *            the front buffer capabilities, can not be null.
     * @param backBufferCapabilities
     *            the the back and intermediate buffers capabilities, can not be
     *            null.
     * @param flipContents
     *            the back buffer contents after page flipping, null if page
     *            flipping is not used.
     */
    public BufferCapabilities(ImageCapabilities frontBufferCapabilities,
            ImageCapabilities backBufferCapabilities, FlipContents flipContents) {
        if (frontBufferCapabilities == null || backBufferCapabilities == null) {
            throw new IllegalArgumentException();
        }

        this.frontBufferCapabilities = frontBufferCapabilities;
        this.backBufferCapabilities = backBufferCapabilities;
        this.flipContents = flipContents;
    }

    /**
     * Returns a copy of the BufferCapabilities object.
     * 
     * @return a copy of the BufferCapabilities object.
     */
    @Override
    public Object clone() {
        return new BufferCapabilities(frontBufferCapabilities, backBufferCapabilities, flipContents);
    }

    /**
     * Gets the image capabilities of the front buffer.
     * 
     * @return the ImageCapabilities object represented capabilities of the
     *         front buffer.
     */
    public ImageCapabilities getFrontBufferCapabilities() {
        return frontBufferCapabilities;
    }

    /**
     * Gets the image capabilities of the back buffer.
     * 
     * @return the ImageCapabilities object represented capabilities of the back
     *         buffer.
     */
    public ImageCapabilities getBackBufferCapabilities() {
        return backBufferCapabilities;
    }

    /**
     * Gets the flip contents of the back buffer after page-flipping.
     * 
     * @return the FlipContents of the back buffer after page-flipping.
     */
    public FlipContents getFlipContents() {
        return flipContents;
    }

    /**
     * Checks if the buffer strategy uses page flipping.
     * 
     * @return true, if the buffer strategy uses page flipping, false otherwise.
     */
    public boolean isPageFlipping() {
        return flipContents != null;
    }

    /**
     * Checks if page flipping is only available in full-screen mode.
     * 
     * @return true, if page flipping is only available in full-screen mode,
     *         false otherwise.
     */
    public boolean isFullScreenRequired() {
        return false;
    }

    /**
     * Checks if page flipping can be performed using more than two buffers.
     * 
     * @return true, if page flipping can be performed using more than two
     *         buffers, false otherwise.
     */
    public boolean isMultiBufferAvailable() {
        return false;
    }

    /**
     * The FlipContents class represents a set of possible back buffer contents
     * after page-flipping.
     * 
     * @since Android 1.0
     */
    public static final class FlipContents {

        /**
         * The back buffered contents are cleared with the background color
         * after flipping.
         */
        public static final FlipContents BACKGROUND = new FlipContents();

        /**
         * The back buffered contents are copied to the front buffer before
         * flipping.
         */
        public static final FlipContents COPIED = new FlipContents();

        /**
         * The back buffer contents are the prior contents of the front buffer.
         */
        public static final FlipContents PRIOR = new FlipContents();

        /**
         * The back buffer contents are undefined after flipping
         */
        public static final FlipContents UNDEFINED = new FlipContents();

        /**
         * Instantiates a new flip contents.
         */
        private FlipContents() {

        }

        /**
         * Returns the hash code of the FlipContents object.
         * 
         * @return the hash code of the FlipContents object.
         */
        @Override
        public int hashCode() {
            return super.hashCode();
        }

        /**
         * Returns the String representation of the FlipContents object.
         * 
         * @return the string
         */
        @Override
        public String toString() {
            return super.toString();
        }
    }
}
