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

import javax.imageio.stream.ImageInputStream;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import java.awt.image.RenderedImage;
import java.io.IOException;

/**
 * The ImageWriterSpi abstract class is a service provider interface (SPI) for
 * ImageWriters.
 * 
 * @since Android 1.0
 */
public abstract class ImageWriterSpi extends ImageReaderWriterSpi {

    /**
     * The STANDARD_OUTPUT_TYPE contains ImageInputStream.class.
     */
    public static final Class[] STANDARD_OUTPUT_TYPE = new Class[] {
        ImageInputStream.class
    };

    /**
     * The output types.
     */
    protected Class[] outputTypes;

    /**
     * The reader SPI names.
     */
    protected String[] readerSpiNames;

    /**
     * Instantiates a new ImageWriterSpi.
     */
    protected ImageWriterSpi() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Instantiates a new ImageWriterSpi with the specified parameters.
     * 
     * @param vendorName
     *            the vendor name.
     * @param version
     *            the version.
     * @param names
     *            the format names.
     * @param suffixes
     *            the array of strings representing the file suffixes.
     * @param MIMETypes
     *            the an array of strings representing MIME types.
     * @param pluginClassName
     *            the plug-in class name.
     * @param outputTypes
     *            the output types.
     * @param readerSpiNames
     *            the array of strings with class names of all associated
     *            ImageReaders.
     * @param supportsStandardStreamMetadataFormat
     *            the value indicating if stream metadata can be described by
     *            standard metadata format.
     * @param nativeStreamMetadataFormatName
     *            the native stream metadata format name, returned by
     *            getNativeStreamMetadataFormatName.
     * @param nativeStreamMetadataFormatClassName
     *            the native stream metadata format class name, returned by
     *            getNativeStreamMetadataFormat.
     * @param extraStreamMetadataFormatNames
     *            the extra stream metadata format names, returned by
     *            getExtraStreamMetadataFormatNames.
     * @param extraStreamMetadataFormatClassNames
     *            the extra stream metadata format class names, returned by
     *            getStreamMetadataFormat.
     * @param supportsStandardImageMetadataFormat
     *            the value indicating if image metadata can be described by
     *            standard metadata format.
     * @param nativeImageMetadataFormatName
     *            the native image metadata format name, returned by
     *            getNativeImageMetadataFormatName.
     * @param nativeImageMetadataFormatClassName
     *            the native image metadata format class name, returned by
     *            getNativeImageMetadataFormat.
     * @param extraImageMetadataFormatNames
     *            the extra image metadata format names, returned by
     *            getExtraImageMetadataFormatNames.
     * @param extraImageMetadataFormatClassNames
     *            the extra image metadata format class names, returned by
     *            getImageMetadataFormat.
     */
    public ImageWriterSpi(String vendorName, String version, String[] names, String[] suffixes,
            String[] MIMETypes, String pluginClassName, Class[] outputTypes,
            String[] readerSpiNames, boolean supportsStandardStreamMetadataFormat,
            String nativeStreamMetadataFormatName, String nativeStreamMetadataFormatClassName,
            String[] extraStreamMetadataFormatNames, String[] extraStreamMetadataFormatClassNames,
            boolean supportsStandardImageMetadataFormat, String nativeImageMetadataFormatName,
            String nativeImageMetadataFormatClassName, String[] extraImageMetadataFormatNames,
            String[] extraImageMetadataFormatClassNames) {
        super(vendorName, version, names, suffixes, MIMETypes, pluginClassName,
                supportsStandardStreamMetadataFormat, nativeStreamMetadataFormatName,
                nativeStreamMetadataFormatClassName, extraStreamMetadataFormatNames,
                extraStreamMetadataFormatClassNames, supportsStandardImageMetadataFormat,
                nativeImageMetadataFormatName, nativeImageMetadataFormatClassName,
                extraImageMetadataFormatNames, extraImageMetadataFormatClassNames);

        if (outputTypes == null || outputTypes.length == 0) {
            throw new NullPointerException("output types array cannot be NULL or empty");
        }

        this.outputTypes = outputTypes;
        this.readerSpiNames = readerSpiNames;
    }

    /**
     * Returns true if the format of the writer's output is lossless. The
     * default implementation returns true.
     * 
     * @return true, if a format is lossless, false otherwise.
     */
    public boolean isFormatLossless() {
        return true;
    }

    /**
     * Gets an array of Class objects whose types can be used as output for this
     * writer.
     * 
     * @return the output types.
     */
    public Class[] getOutputTypes() {
        return outputTypes;
    }

    /**
     * Checks whether or not the ImageWriter implementation associated with this
     * service provider can encode an image with the specified type.
     * 
     * @param type
     *            the ImageTypeSpecifier.
     * @return true, if an image with the specified type can be encoded, false
     *         otherwise.
     */
    public abstract boolean canEncodeImage(ImageTypeSpecifier type);

    /**
     * Checks whether or not the ImageWriter implementation associated with this
     * service provider can encode the specified RenderedImage.
     * 
     * @param im
     *            the RenderedImage.
     * @return true, if RenderedImage can be encoded, false otherwise.
     */
    public boolean canEncodeImage(RenderedImage im) {
        return canEncodeImage(ImageTypeSpecifier.createFromRenderedImage(im));
    }

    /**
     * Returns an instance of the ImageWriter implementation for this service
     * provider.
     * 
     * @return the ImageWriter.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public ImageWriter createWriterInstance() throws IOException {
        return createWriterInstance(null);
    }

    /**
     * Returns an instance of the ImageWriter implementation for this service
     * provider.
     * 
     * @param extension
     *            the a plug-in specific extension object, or null.
     * @return the ImageWriter.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract ImageWriter createWriterInstance(Object extension) throws IOException;

    /**
     * Checks whether or not the specified ImageWriter object is an instance of
     * the ImageWriter associated with this service provider or not.
     * 
     * @param writer
     *            the ImageWriter.
     * @return true, if the specified ImageWriter object is an instance of the
     *         ImageWriter associated with this service provider, false
     *         otherwise.
     */
    public boolean isOwnWriter(ImageWriter writer) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets an array of strings with names of the ImageReaderSpi classes that
     * support the internal metadata representation used by the ImageWriter of
     * this service provider, or null if there are no such ImageReaders.
     * 
     * @return the array of strings with names of the ImageWriterSpi classes.
     */
    public String[] getImageReaderSpiNames() {
        return readerSpiNames;
    }
}
