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
 * @author Sergey I. Salishev
 * @version $Revision: 1.2 $
 */

package javax.imageio.event;

import java.awt.image.BufferedImage;
import java.util.EventListener;
import javax.imageio.ImageReader;

/*
 * @author Sergey I. Salishev
 * @version $Revision: 1.2 $
 */

/**
 * The IIOReadUpdateListener interface provides functionality to receive
 * notification of pixel updates during image and thumbnail reading operations.
 * 
 * @since Android 1.0
 */
public interface IIOReadUpdateListener extends EventListener {

    /**
     * Notifies this listener that the specified area of the image has been
     * updated.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param theImage
     *            the image to be updated.
     * @param minX
     *            the minimum X coordinate of the pixels in the updated area.
     * @param minY
     *            the minimum Y coordinate of the pixels in the updated area.
     * @param width
     *            the width of updated area.
     * @param height
     *            the height of updated area.
     * @param periodX
     *            the horizontal spacing period between updated pixels, if it
     *            equals 1, there is no space between pixels.
     * @param periodY
     *            the vertical spacing period between updated pixels, if it
     *            equals 1, there is no space between pixels.
     * @param bands
     *            the array of integer values indicating the bands being
     *            updated.
     */
    void imageUpdate(ImageReader source, BufferedImage theImage, int minX, int minY, int width,
            int height, int periodX, int periodY, int[] bands);

    /**
     * Notifies this listener that the current read operation has completed a
     * progressive pass.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param theImage
     *            the image to be updated.
     */
    void passComplete(ImageReader source, BufferedImage theImage);

    /**
     * Notifies this listener that the current read operation has begun a
     * progressive pass.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param theImage
     *            the image to be updated.
     * @param pass
     *            the number of the pass.
     * @param minPass
     *            the index of the first pass that will be decoded.
     * @param maxPass
     *            the index of the last pass that will be decoded.
     * @param minX
     *            the minimum X coordinate of the pixels in the updated area.
     * @param minY
     *            the minimum Y coordinate of the pixels in the updated area.
     * @param periodX
     *            the horizontal spacing period between updated pixels, if it
     *            equals 1, there is no space between pixels.
     * @param periodY
     *            the vertical spacing period between updated pixels, if it
     *            equals 1, there is no space between pixels.
     * @param bands
     *            the array of integer values indicating the bands being
     *            updated.
     */
    void passStarted(ImageReader source, BufferedImage theImage, int pass, int minPass,
            int maxPass, int minX, int minY, int periodX, int periodY, int[] bands);

    /**
     * Notifies this listener that the current thumbnail read operation has
     * completed a progressive pass.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param theImage
     *            the thumbnail to be updated.
     */
    void thumbnailPassComplete(ImageReader source, BufferedImage theImage);

    /**
     * Notifies this listener that the current thumbnail read operation has
     * begun a progressive pass.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param theThumbnail
     *            the thumbnail to be updated.
     * @param pass
     *            the number of the pass.
     * @param minPass
     *            the index of the first pass that will be decoded.
     * @param maxPass
     *            the index of the last pass that will be decoded.
     * @param minX
     *            the minimum X coordinate of the pixels in the updated area.
     * @param minY
     *            the minimum Y coordinate of the pixels in the updated area.
     * @param periodX
     *            the horizontal spacing period between updated pixels, if it
     *            equals 1, there is no space between pixels.
     * @param periodY
     *            the vertical spacing period between updated pixels, if it
     *            equals 1, there is no space between pixels.
     * @param bands
     *            the array of integer values indicating the bands being
     *            updated.
     */
    void thumbnailPassStarted(ImageReader source, BufferedImage theThumbnail, int pass,
            int minPass, int maxPass, int minX, int minY, int periodX, int periodY, int[] bands);

    /**
     * Notifies this listener that a specified area of a thumbnail image has
     * been updated.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param theThumbnail
     *            the thumbnail to be updated.
     * @param minX
     *            the minimum X coordinate of the pixels in the updated area.
     * @param minY
     *            the minimum Y coordinate of the pixels in the updated area.
     * @param width
     *            the width of updated area.
     * @param height
     *            the height of updated area.
     * @param periodX
     *            the horizontal spacing period between updated pixels, if it
     *            equals 1, there is no space between pixels.
     * @param periodY
     *            the vertical spacing period between updated pixels, if it
     *            equals 1, there is no space between pixels.
     * @param bands
     *            the array of integer values indicating the bands being
     *            updated.
     */
    void thumbnailUpdate(ImageReader source, BufferedImage theThumbnail, int minX, int minY,
            int width, int height, int periodX, int periodY, int[] bands);
}
