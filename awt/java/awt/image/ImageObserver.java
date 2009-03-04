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

import java.awt.Image;

/**
 * the ImageObserver interface is an asynchronous update interface for receiving
 * notifications about Image construction status.
 * 
 * @since Android 1.0
 */
public interface ImageObserver {

    /**
     * The Constant WIDTH indicates that the width of the image is available.
     */
    public static final int WIDTH = 1;

    /**
     * The Constant HEIGHT indicates that the width of the image is available.
     */
    public static final int HEIGHT = 2;

    /**
     * The Constant PROPERTIES indicates that the properties of the image are
     * available.
     */
    public static final int PROPERTIES = 4;

    /**
     * The Constant SOMEBITS indicates that more bits needed for drawing a
     * scaled variation of the image pixels are available.
     */
    public static final int SOMEBITS = 8;

    /**
     * The Constant FRAMEBITS indicates that complete frame of a image which was
     * previously drawn is now available for drawing again.
     */
    public static final int FRAMEBITS = 16;

    /**
     * The Constant ALLBITS indicates that an image which was previously drawn
     * is now complete and can be drawn again.
     */
    public static final int ALLBITS = 32;

    /**
     * The Constant ERROR indicates that error occurred.
     */
    public static final int ERROR = 64;

    /**
     * The Constant ABORT indicates that the image producing is aborted.
     */
    public static final int ABORT = 128;

    /**
     * This method is called when information about an Image interface becomes
     * available. This method returns true if further updates are needed, false
     * if not.
     * 
     * @param img
     *            the image to be observed.
     * @param infoflags
     *            the bitwise OR combination of information flags: ABORT,
     *            ALLBITS, ERROR, FRAMEBITS, HEIGHT, PROPERTIES, SOMEBITS,
     *            WIDTH.
     * @param x
     *            the X coordinate.
     * @param y
     *            the Y coordinate.
     * @param width
     *            the width.
     * @param height
     *            the height.
     * @return true if further updates are needed, false if not.
     */
    public boolean imageUpdate(Image img, int infoflags, int x, int y, int width, int height);

}
