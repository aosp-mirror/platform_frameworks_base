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

package javax.imageio;

import javax.imageio.metadata.IIOMetadata;
import javax.imageio.ImageTypeSpecifier;

/**
 * The ImageTranscoder interface is to be implemented by classes that perform
 * image transcoding operations, that is, take images written in one format and
 * write them in another format using read/write operations. Some image data can
 * be lost in such processes. The ImageTranscoder interface converts metadata
 * objects (IIOMetadata) of ImageReader to appropriate metadata object for
 * ImageWriter.
 * 
 * @since Android 1.0
 */
public interface ImageTranscoder {

    /**
     * Converts the specified IIOMetadata object using the specified
     * ImageWriteParam for obtaining writer's metadata structure.
     * 
     * @param inData
     *            the IIOMetadata.
     * @param param
     *            the ImageWriteParam.
     * @return the IIOMetadata, or null.
     */
    IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param);

    /**
     * Converts the specified IIOMetadata object using the specified
     * ImageWriteParam for obtaining writer's metadata structure and
     * ImageTypeSpecifier object for obtaining the layout and color information
     * of the image for this metadata.
     * 
     * @param inData
     *            the IIOMetadata.
     * @param imageType
     *            the ImageTypeSpecifier.
     * @param param
     *            the ImageWriteParam.
     * @return the IIOMetadata, or null.
     */
    IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType,
            ImageWriteParam param);
}
