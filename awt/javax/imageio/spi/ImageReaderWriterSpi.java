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

import org.apache.harmony.x.imageio.metadata.IIOMetadataUtils;

import javax.imageio.metadata.IIOMetadataFormat;

/**
 * The ImageReaderWriterSpi class is a superclass for the ImageReaderSpi and
 * ImageWriterSpi SPIs.
 * 
 * @since Android 1.0
 */
public abstract class ImageReaderWriterSpi extends IIOServiceProvider implements
        RegisterableService {

    /**
     * The names.
     */
    protected String[] names;

    /**
     * The suffixes.
     */
    protected String[] suffixes;

    /**
     * The MIME types.
     */
    protected String[] MIMETypes;

    /**
     * The plug-in class name.
     */
    protected String pluginClassName;

    /**
     * Whether the reader/writer supports standard stream metadata format.
     */
    protected boolean supportsStandardStreamMetadataFormat;

    /**
     * The native stream metadata format name.
     */
    protected String nativeStreamMetadataFormatName;

    /**
     * The native stream metadata format class name.
     */
    protected String nativeStreamMetadataFormatClassName;

    /**
     * The extra stream metadata format names.
     */
    protected String[] extraStreamMetadataFormatNames;

    /**
     * The extra stream metadata format class names.
     */
    protected String[] extraStreamMetadataFormatClassNames;

    /**
     * Whether the reader/writer supports standard image metadata format.
     */
    protected boolean supportsStandardImageMetadataFormat;

    /**
     * The native image metadata format name.
     */
    protected String nativeImageMetadataFormatName;

    /**
     * The native image metadata format class name.
     */
    protected String nativeImageMetadataFormatClassName;

    /**
     * The extra image metadata format names.
     */
    protected String[] extraImageMetadataFormatNames;

    /**
     * The extra image metadata format class names.
     */
    protected String[] extraImageMetadataFormatClassNames;

    /**
     * Instantiates a new ImageReaderWriterSpi.
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
    public ImageReaderWriterSpi(String vendorName, String version, String[] names,
            String[] suffixes, String[] MIMETypes, String pluginClassName,
            boolean supportsStandardStreamMetadataFormat, String nativeStreamMetadataFormatName,
            String nativeStreamMetadataFormatClassName, String[] extraStreamMetadataFormatNames,
            String[] extraStreamMetadataFormatClassNames,
            boolean supportsStandardImageMetadataFormat, String nativeImageMetadataFormatName,
            String nativeImageMetadataFormatClassName, String[] extraImageMetadataFormatNames,
            String[] extraImageMetadataFormatClassNames) {
        super(vendorName, version);

        if (names == null || names.length == 0) {
            throw new NullPointerException("format names array cannot be NULL or empty");
        }

        if (pluginClassName == null) {
            throw new NullPointerException("Plugin class name cannot be NULL");
        }

        // We clone all the arrays to be consistent with the fact that
        // some methods of this class must return clones of the arrays
        // as it is stated in the spec.
        this.names = names.clone();
        this.suffixes = suffixes == null ? null : suffixes.clone();
        this.MIMETypes = MIMETypes == null ? null : MIMETypes.clone();
        this.pluginClassName = pluginClassName;
        this.supportsStandardStreamMetadataFormat = supportsStandardStreamMetadataFormat;
        this.nativeStreamMetadataFormatName = nativeStreamMetadataFormatName;
        this.nativeStreamMetadataFormatClassName = nativeStreamMetadataFormatClassName;

        this.extraStreamMetadataFormatNames = extraStreamMetadataFormatNames == null ? null
                : extraStreamMetadataFormatNames.clone();

        this.extraStreamMetadataFormatClassNames = extraStreamMetadataFormatClassNames == null ? null
                : extraStreamMetadataFormatClassNames.clone();

        this.supportsStandardImageMetadataFormat = supportsStandardImageMetadataFormat;
        this.nativeImageMetadataFormatName = nativeImageMetadataFormatName;
        this.nativeImageMetadataFormatClassName = nativeImageMetadataFormatClassName;

        this.extraImageMetadataFormatNames = extraImageMetadataFormatNames == null ? null
                : extraImageMetadataFormatNames.clone();

        this.extraImageMetadataFormatClassNames = extraImageMetadataFormatClassNames == null ? null
                : extraImageMetadataFormatClassNames.clone();
    }

    /**
     * Instantiates a new ImageReaderWriterSpi.
     */
    public ImageReaderWriterSpi() {
    }

    /**
     * Gets an array of strings representing names of the formats that can be
     * used by the ImageReader or ImageWriter implementation associated with
     * this service provider.
     * 
     * @return the array of supported format names.
     */
    public String[] getFormatNames() {
        return names.clone();
    }

    /**
     * Gets an array of strings representing file suffixes associated with the
     * formats that can be used by the ImageReader or ImageWriter implementation
     * of this service provider.
     * 
     * @return the array of file suffixes.
     */
    public String[] getFileSuffixes() {
        return suffixes == null ? null : suffixes.clone();
    }

    /**
     * Gets an array of strings with the names of additional formats of the
     * image metadata objects produced or consumed by this plug-in.
     * 
     * @return the array of extra image metadata format names.
     */
    public String[] getExtraImageMetadataFormatNames() {
        return extraImageMetadataFormatNames == null ? null : extraImageMetadataFormatNames.clone();
    }

    /**
     * Gets an array of strings with the names of additional formats of the
     * stream metadata objects produced or consumed by this plug-in.
     * 
     * @return the array of extra stream metadata format names.
     */
    public String[] getExtraStreamMetadataFormatNames() {
        return extraStreamMetadataFormatNames == null ? null : extraStreamMetadataFormatNames
                .clone();
    }

    /**
     * Gets an IIOMetadataFormat object for the specified image metadata format
     * name.
     * 
     * @param formatName
     *            the format name.
     * @return the IIOMetadataFormat, or null.
     */
    public IIOMetadataFormat getImageMetadataFormat(String formatName) {
        return IIOMetadataUtils.instantiateMetadataFormat(formatName,
                supportsStandardImageMetadataFormat, nativeImageMetadataFormatName,
                nativeImageMetadataFormatClassName, extraImageMetadataFormatNames,
                extraImageMetadataFormatClassNames);
    }

    /**
     * Gets an IIOMetadataFormat object for the specified stream metadata format
     * name.
     * 
     * @param formatName
     *            the format name.
     * @return the IIOMetadataFormat, or null.
     */
    public IIOMetadataFormat getStreamMetadataFormat(String formatName) {
        return IIOMetadataUtils.instantiateMetadataFormat(formatName,
                supportsStandardStreamMetadataFormat, nativeStreamMetadataFormatName,
                nativeStreamMetadataFormatClassName, extraStreamMetadataFormatNames,
                extraStreamMetadataFormatClassNames);
    }

    /**
     * Gets an array of strings representing the MIME types of the formats that
     * are supported by the ImageReader or ImageWriter implementation of this
     * service provider.
     * 
     * @return the array MIME types.
     */
    public String[] getMIMETypes() {
        return MIMETypes == null ? null : MIMETypes.clone();
    }

    /**
     * Gets the name of the native image metadata format for this reader/writer,
     * which allows for lossless encoding or decoding of the image metadata with
     * the format.
     * 
     * @return the string with native image metadata format name, or null.
     */
    public String getNativeImageMetadataFormatName() {
        return nativeImageMetadataFormatName;
    }

    /**
     * Gets the name of the native stream metadata format for this
     * reader/writer, which allows for lossless encoding or decoding of the
     * stream metadata with the format.
     * 
     * @return the string with native stream metadata format name, or null.
     */
    public String getNativeStreamMetadataFormatName() {
        return nativeStreamMetadataFormatName;
    }

    /**
     * Gets the class name of the ImageReader or ImageWriter associated with
     * this service provider.
     * 
     * @return the class name.
     */
    public String getPluginClassName() {
        return pluginClassName;
    }

    /**
     * Checks if the standard metadata format is supported by the getAsTree and
     * setFromTree methods for the image metadata objects produced or consumed
     * by this reader or writer.
     * 
     * @return true, if standard image metadata format is supported, false
     *         otherwise.
     */
    public boolean isStandardImageMetadataFormatSupported() {
        return supportsStandardImageMetadataFormat;
    }

    /**
     * Checks if the standard metadata format is supported by the getAsTree and
     * setFromTree methods for the stream metadata objects produced or consumed
     * by this reader or writer.
     * 
     * @return true, if standard stream metadata format is supported, false
     *         otherwise.
     */
    public boolean isStandardStreamMetadataFormatSupported() {
        return supportsStandardStreamMetadataFormat;
    }
}
