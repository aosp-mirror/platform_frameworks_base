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
 * The ImageFilter class provides a filter for delivering image data from an
 * ImageProducer to an ImageConsumer.
 * 
 * @since Android 1.0
 */
public class ImageFilter implements ImageConsumer, Cloneable {

    /**
     * The consumer.
     */
    protected ImageConsumer consumer;

    /**
     * Instantiates a new ImageFilter.
     */
    public ImageFilter() {
        super();
    }

    /**
     * Gets an instance of an ImageFilter object which performs the filtering
     * for the specified ImageConsumer.
     * 
     * @param ic
     *            the specified ImageConsumer.
     * @return an ImageFilter used to perform the filtering for the specified
     *         ImageConsumer.
     */
    public ImageFilter getFilterInstance(ImageConsumer ic) {
        ImageFilter filter = (ImageFilter)clone();
        filter.consumer = ic;
        return filter;
    }

    @SuppressWarnings("unchecked")
    public void setProperties(Hashtable<?, ?> props) {
        Hashtable<Object, Object> fprops;
        if (props == null) {
            fprops = new Hashtable<Object, Object>();
        } else {
            fprops = (Hashtable<Object, Object>)props.clone();
        }
        String propName = "Filters"; //$NON-NLS-1$
        String prop = "Null filter"; //$NON-NLS-1$
        Object o = fprops.get(propName);
        if (o != null) {
            if (o instanceof String) {
                prop = (String)o + "; " + prop; //$NON-NLS-1$
            } else {
                prop = o.toString() + "; " + prop; //$NON-NLS-1$
            }
        }
        fprops.put(propName, prop);
        consumer.setProperties(fprops);
    }

    /**
     * Returns a copy of this ImageFilter.
     * 
     * @return a copy of this ImageFilter.
     */
    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    /**
     * Responds to a request for a Top-Down-Left-Right ordered resend of the
     * pixel data from an ImageConsumer.
     * 
     * @param ip
     *            the ImageProducer that provides this instance of the filter.
     */
    public void resendTopDownLeftRight(ImageProducer ip) {
        ip.requestTopDownLeftRightResend(this);
    }

    public void setColorModel(ColorModel model) {
        consumer.setColorModel(model);
    }

    public void setPixels(int x, int y, int w, int h, ColorModel model, int[] pixels, int off,
            int scansize) {
        consumer.setPixels(x, y, w, h, model, pixels, off, scansize);
    }

    public void setPixels(int x, int y, int w, int h, ColorModel model, byte[] pixels, int off,
            int scansize) {
        consumer.setPixels(x, y, w, h, model, pixels, off, scansize);
    }

    public void setDimensions(int width, int height) {
        consumer.setDimensions(width, height);
    }

    public void setHints(int hints) {
        consumer.setHints(hints);
    }

    public void imageComplete(int status) {
        consumer.imageComplete(status);
    }

}
