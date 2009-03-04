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
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */

package javax.imageio.event;

import javax.imageio.ImageWriter;
import java.util.EventListener;

/**
 * The IIOWriteProgressListener interface provides methods to receive
 * notification about the progress of the image and thumbnail writing methods.
 * 
 * @since Android 1.0
 */
public interface IIOWriteProgressListener extends EventListener {

    /**
     * Notifies this listener that an image write operation has been started.
     * 
     * @param source
     *            the ImageWriter object which calls this method.
     * @param imageIndex
     *            the index of the image being written.
     */
    void imageStarted(ImageWriter source, int imageIndex);

    /**
     * Notifies this listener about the degree of completion of the write call.
     * 
     * @param source
     *            the ImageWriter object which calls this method.
     * @param percentageDone
     *            the percentage of encoding done.
     */
    void imageProgress(ImageWriter source, float percentageDone);

    /**
     * Notifies this listener that the image writing has been completed.
     * 
     * @param source
     *            the ImageWriter object which calls this method.
     */
    void imageComplete(ImageWriter source);

    /**
     * Notifies this listener that a thumbnail write operation has been started.
     * 
     * @param source
     *            the ImageWriter object which calls this method.
     * @param imageIndex
     *            the index of the image being written.
     * @param thumbnailIndex
     *            the index of the thumbnail being written.
     */
    void thumbnailStarted(ImageWriter source, int imageIndex, int thumbnailIndex);

    /**
     * Notifies this listener about the degree of completion of the write call.
     * 
     * @param source
     *            the ImageWriter object which calls this method.
     * @param percentageDone
     *            the percentage of encoding done.
     */
    void thumbnailProgress(ImageWriter source, float percentageDone);

    /**
     * Notifies this listener that a thumbnail write operation has been
     * completed.
     * 
     * @param source
     *            the ImageWriter object which calls this method.
     */
    void thumbnailComplete(ImageWriter source);

    /**
     * Notifies this listener that writing operation has been aborted.
     * 
     * @param source
     *            the ImageWriter object which calls this method.
     */
    void writeAborted(ImageWriter source);
}
