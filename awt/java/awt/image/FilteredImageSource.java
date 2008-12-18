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

import java.util.Hashtable;

/**
 * The FilteredImageSource class is used for producing image data for a new
 * filtered version of the original image using the specified filter object.
 * 
 * @since Android 1.0
 */
public class FilteredImageSource implements ImageProducer {

    /**
     * The source.
     */
    private final ImageProducer source;

    /**
     * The filter.
     */
    private final ImageFilter filter;

    /**
     * The cons table.
     */
    private final Hashtable<ImageConsumer, ImageConsumer> consTable = new Hashtable<ImageConsumer, ImageConsumer>();

    /**
     * Instantiates a new FilteredImageSource object with the specified
     * ImageProducer and the ImageFilter objects.
     * 
     * @param orig
     *            the specified ImageProducer.
     * @param imgf
     *            the specified ImageFilter.
     */
    public FilteredImageSource(ImageProducer orig, ImageFilter imgf) {
        source = orig;
        filter = imgf;
    }

    public synchronized boolean isConsumer(ImageConsumer ic) {
        if (ic != null) {
            return consTable.containsKey(ic);
        }
        return false;
    }

    public void startProduction(ImageConsumer ic) {
        addConsumer(ic);
        ImageConsumer fic = consTable.get(ic);
        source.startProduction(fic);
    }

    public void requestTopDownLeftRightResend(ImageConsumer ic) {
        if (ic != null && isConsumer(ic)) {
            ImageFilter fic = (ImageFilter)consTable.get(ic);
            fic.resendTopDownLeftRight(source);
        }
    }

    public synchronized void removeConsumer(ImageConsumer ic) {
        if (ic != null && isConsumer(ic)) {
            ImageConsumer fic = consTable.get(ic);
            source.removeConsumer(fic);
            consTable.remove(ic);
        }
    }

    public synchronized void addConsumer(ImageConsumer ic) {
        if (ic != null && !isConsumer(ic)) {
            ImageConsumer fic = filter.getFilterInstance(ic);
            source.addConsumer(fic);
            consTable.put(ic, fic);
        }
    }
}
