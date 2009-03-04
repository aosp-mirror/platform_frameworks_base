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

package javax.imageio.spi;

import javax.imageio.ImageTranscoder;

/**
 * The ImageTranscoderSpi class is a service provider interface (SPI) for
 * ImageTranscoders.
 * 
 * @since Android 1.0
 */
public abstract class ImageTranscoderSpi extends IIOServiceProvider implements RegisterableService {

    /**
     * Instantiates a new ImageTranscoderSpi.
     */
    protected ImageTranscoderSpi() {
    }

    /**
     * Instantiates a new ImageTranscoderSpi with the specified vendor name and
     * version.
     * 
     * @param vendorName
     *            the vendor name.
     * @param version
     *            the version.
     */
    public ImageTranscoderSpi(String vendorName, String version) {
        super(vendorName, version);
    }

    /**
     * Gets the class name of an ImageReaderSpi that produces IIOMetadata
     * objects that can be used as input to this transcoder.
     * 
     * @return the class name of an ImageReaderSpi.
     */
    public abstract String getReaderServiceProviderName();

    /**
     * Gets the class name of an ImageWriterSpi that produces IIOMetadata
     * objects that can be used as input to this transcoder.
     * 
     * @return the class name of an ImageWriterSpi.
     */
    public abstract String getWriterServiceProviderName();

    /**
     * Creates an instance of the ImageTranscoder associated with this service
     * provider.
     * 
     * @return the ImageTranscoder instance.
     */
    public abstract ImageTranscoder createTranscoderInstance();
}
