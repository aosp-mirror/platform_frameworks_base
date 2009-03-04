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

import java.util.EventListener;
import javax.imageio.ImageReader;

/**
 * The IIOReadProgressListener interface notifies callers about the progress of
 * the image and thumbnail reading methods.
 * 
 * @since Android 1.0
 */
public interface IIOReadProgressListener extends EventListener {

    /**
     * Notifies this listener that the image reading has been completed.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     */
    void imageComplete(ImageReader source);

    /**
     * Notifies this listener about the degree of completion of the read call.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param percentageDone
     *            the percentage of decoding done.
     */
    void imageProgress(ImageReader source, float percentageDone);

    /**
     * Notifies this listener that an image read operation has been started.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param imageIndex
     *            the index of the image in an input file or stream to be read.
     */
    void imageStarted(ImageReader source, int imageIndex);

    /**
     * Notifies this listener that a read operation has been aborted.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     */
    void readAborted(ImageReader source);

    /**
     * Notifies this listener that a sequence of read operations has been
     * completed.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     */
    void sequenceComplete(ImageReader source);

    /**
     * Notifies this listener that a sequence of read operation has been
     * started.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param minIndex
     *            the index of the first image to be read.
     */
    void sequenceStarted(ImageReader source, int minIndex);

    /**
     * Notifies that a thumbnail read operation has been completed.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     */
    void thumbnailComplete(ImageReader source);

    /**
     * Notifies this listener about the degree of completion of the read call.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param percentageDone
     *            the percentage of decoding done.
     */
    void thumbnailProgress(ImageReader source, float percentageDone);

    /**
     * Notifies this listener that a thumbnail reading operation has been
     * started.
     * 
     * @param source
     *            the ImageReader object which calls this method.
     * @param imageIndex
     *            the index of the image in an input file or stream to be read.
     * @param thumbnailIndex
     *            the index of the thumbnail to be read.
     */
    void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex);
}
