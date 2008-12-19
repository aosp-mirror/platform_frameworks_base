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
import javax.imageio.ImageReader;
import java.io.IOException;

/**
 * The ImageReaderSpi abstract class is a service provider interface (SPI) for
 * ImageReaders.
 * 
 * @since Android 1.0
 */
public abstract class ImageReaderSpi extends ImageReaderWriterSpi {

    /**
     * The STANDARD_INPUT_TYPE contains ImageInputStream.class.
     */
    public static final Class[] STANDARD_INPUT_TYPE = new Class[] {
        ImageInputStream.class
    };

    /**
     * The input types.
     */
    protected Class[] inputTypes;

    /**
     * The writer SPI names.
     */
    protected String[] writerSpiNames;

    /**
     * Instantiates a new ImageReaderSpi.
     */
    protected ImageReaderSpi() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Instantiates a new ImageReaderSpi.
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
     * @param inputTypes
     *            the input types.
     * @param writerSpiNames
     *            the array of strings with class names of all associated
     *            ImageWriters.
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
    public ImageReaderSpi(String vendorName, String version, String[] names, String[] suffixes,
            String[] MIMETypes, String pluginClassName, Class[] inputTypes,
            String[] writerSpiNames, boolean supportsStandardStreamMetadataFormat,
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

        if (inputTypes == null || inputTypes.length == 0) {
            throw new NullPointerException("input types array cannot be NULL or empty");
        }
        this.inputTypes = inputTypes;
        this.writerSpiNames = writerSpiNames;
    }

    /**
     * Gets an array of Class objects whose types can be used as input for this
     * reader.
     * 
     * @return the input types.
     */
    public Class[] getInputTypes() {
        return inputTypes;
    }

    /**
     * Returns true if the format of source object is supported by this reader.
     * 
     * @param source
     *            the source object to be decoded (for example an
     *            ImageInputStream).
     * @return true, if the format of source object is supported by this reader,
     *         false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract boolean canDecodeInput(Object source) throws IOException;

    /**
     * Returns an instance of the ImageReader implementation for this service
     * provider.
     * 
     * @return the ImageReader.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public ImageReader createReaderInstance() throws IOException {
        return createReaderInstance(null);
    }

    /**
     * Returns an instance of the ImageReader implementation for this service
     * provider.
     * 
     * @param extension
     *            the a plug-in specific extension object, or null.
     * @return the ImageReader.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract ImageReader createReaderInstance(Object extension) throws IOException;

    /**
     * Checks whether or not the specified ImageReader object is an instance of
     * the ImageReader associated with this service provider or not.
     * 
     * @param reader
     *            the ImageReader.
     * @return true, if the specified ImageReader object is an instance of the
     *         ImageReader associated with this service provider, false
     *         otherwise.
     */
    public boolean isOwnReader(ImageReader reader) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets an array of strings with names of the ImageWriterSpi classes that
     * support the internal metadata representation used by the ImageReader of
     * this service provider, or null if there are no such ImageWriters.
     * 
     * @return the array of strings with names of the ImageWriterSpi classes.
     */
    public String[] getImageWriterSpiNames() {
        throw new UnsupportedOperationException("Not supported yet");
    }
}
