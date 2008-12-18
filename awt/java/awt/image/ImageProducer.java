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

/**
 * The ImageProducer provides an interface for objects which produce the image
 * data. ImageProducer is used for reconstructing the image. Each image contains
 * an ImageProducer.
 * 
 * @since Android 1.0
 */
public interface ImageProducer {

    /**
     * Checks if the specified ImageConsumer is registered with this
     * ImageProvider or not.
     * 
     * @param ic
     *            the ImageConsumer to be checked.
     * @return true, if the specified ImageConsumer is registered with this
     *         ImageProvider, false otherwise.
     */
    public boolean isConsumer(ImageConsumer ic);

    /**
     * Starts a reconstruction of the image data which will be delivered to this
     * consumer. This method adds the specified ImageConsumer before
     * reconstructing the image.
     * 
     * @param ic
     *            the specified ImageConsumer.
     */
    public void startProduction(ImageConsumer ic);

    /**
     * Requests the ImageProducer to resend the image data in
     * ImageConsumer.TOPDOWNLEFTRIGHT order.
     * 
     * @param ic
     *            the specified ImageConsumer.
     */
    public void requestTopDownLeftRightResend(ImageConsumer ic);

    /**
     * Deregisters the specified ImageConsumer.
     * 
     * @param ic
     *            the specified ImageConsumer.
     */
    public void removeConsumer(ImageConsumer ic);

    /**
     * Adds the specified ImageConsumer object to this ImageProducer.
     * 
     * @param ic
     *            the specified ImageConsumer.
     */
    public void addConsumer(ImageConsumer ic);

}
