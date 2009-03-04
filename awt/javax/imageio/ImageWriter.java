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

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.event.IIOWriteWarningListener;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;

/**
 * The ImageWriter class is an abstract class for encoding images. ImageWriter
 * objects are instantiated by the service provider interface, ImageWriterSpi
 * class, for the specific format. ImageWriterSpi class should be registered
 * with the IIORegistry, which uses them for format recognition and presentation
 * of available format readers and writers.
 * 
 * @since Android 1.0
 */
public abstract class ImageWriter implements ImageTranscoder {

    /**
     * The available locales.
     */
    protected Locale[] availableLocales;

    /**
     * The locale.
     */
    protected Locale locale;

    /**
     * The originating provider.
     */
    protected ImageWriterSpi originatingProvider;

    /**
     * The output.
     */
    protected Object output;

    /**
     * The progress listeners.
     */
    protected List<IIOWriteProgressListener> progressListeners;

    /**
     * The warning listeners.
     */
    protected List<IIOWriteWarningListener> warningListeners;

    /**
     * The warning locales.
     */
    protected List<Locale> warningLocales;

    // Indicates that abort operation is requested
    // Abort mechanism should be thread-safe
    /** The aborted. */
    private boolean aborted;

    /**
     * Instantiates a new ImageWriter.
     * 
     * @param originatingProvider
     *            the ImageWriterSpi which instantiates this ImageWriter.
     */
    protected ImageWriter(ImageWriterSpi originatingProvider) {
        this.originatingProvider = originatingProvider;
    }

    public abstract IIOMetadata convertStreamMetadata(IIOMetadata iioMetadata,
            ImageWriteParam imageWriteParam);

    public abstract IIOMetadata convertImageMetadata(IIOMetadata iioMetadata,
            ImageTypeSpecifier imageTypeSpecifier, ImageWriteParam imageWriteParam);

    /**
     * Gets the ImageWriterSpi which instantiated this ImageWriter.
     * 
     * @return the ImageWriterSpi.
     */
    public ImageWriterSpi getOriginatingProvider() {
        return originatingProvider;
    }

    /**
     * Processes the start of an image read by calling their imageStarted method
     * of registered IIOWriteProgressListeners.
     * 
     * @param imageIndex
     *            the image index.
     */
    protected void processImageStarted(int imageIndex) {
        if (null != progressListeners) {
            for (IIOWriteProgressListener listener : progressListeners) {
                listener.imageStarted(this, imageIndex);
            }
        }
    }

    /**
     * Processes the current percentage of image completion by calling
     * imageProgress method of registered IIOWriteProgressListener.
     * 
     * @param percentageDone
     *            the percentage done.
     */
    protected void processImageProgress(float percentageDone) {
        if (null != progressListeners) {
            for (IIOWriteProgressListener listener : progressListeners) {
                listener.imageProgress(this, percentageDone);
            }
        }
    }

    /**
     * Processes image completion by calling imageComplete method of registered
     * IIOWriteProgressListeners.
     */
    protected void processImageComplete() {
        if (null != progressListeners) {
            for (IIOWriteProgressListener listener : progressListeners) {
                listener.imageComplete(this);
            }
        }
    }

    /**
     * Processes a warning message by calling warningOccurred method of
     * registered IIOWriteWarningListeners.
     * 
     * @param imageIndex
     *            the image index.
     * @param warning
     *            the warning.
     */
    protected void processWarningOccurred(int imageIndex, String warning) {
        if (null == warning) {
            throw new NullPointerException("warning message should not be NULL");
        }
        if (null != warningListeners) {
            for (IIOWriteWarningListener listener : warningListeners) {
                listener.warningOccurred(this, imageIndex, warning);
            }
        }
    }

    /**
     * Processes a warning message by calling warningOccurred method of
     * registered IIOWriteWarningListeners with string from ResourceBundle.
     * 
     * @param imageIndex
     *            the image index.
     * @param bundle
     *            the name of ResourceBundle.
     * @param key
     *            the keyword.
     */
    protected void processWarningOccurred(int imageIndex, String bundle, String key) {
        if (warningListeners != null) { // Don't check the parameters
            return;
        }

        if (bundle == null) {
            throw new IllegalArgumentException("baseName == null!");
        }
        if (key == null) {
            throw new IllegalArgumentException("keyword == null!");
        }

        // Get the context class loader and try to locate the bundle with it
        // first
        ClassLoader contextClassloader = AccessController
                .doPrivileged(new PrivilegedAction<ClassLoader>() {
                    public ClassLoader run() {
                        return Thread.currentThread().getContextClassLoader();
                    }
                });

        // Iterate through both listeners and locales
        int n = warningListeners.size();
        for (int i = 0; i < n; i++) {
            IIOWriteWarningListener listener = warningListeners.get(i);
            Locale locale = warningLocales.get(i);

            // Now try to get the resource bundle
            ResourceBundle rb;
            try {
                rb = ResourceBundle.getBundle(bundle, locale, contextClassloader);
            } catch (MissingResourceException e) {
                try {
                    rb = ResourceBundle.getBundle(bundle, locale);
                } catch (MissingResourceException e1) {
                    throw new IllegalArgumentException("Bundle not found!");
                }
            }

            try {
                String warning = rb.getString(key);
                listener.warningOccurred(this, imageIndex, warning);
            } catch (MissingResourceException e) {
                throw new IllegalArgumentException("Resource is missing!");
            } catch (ClassCastException e) {
                throw new IllegalArgumentException("Resource is not a String!");
            }
        }
    }

    /**
     * Sets the specified Object to the output of this ImageWriter.
     * 
     * @param output
     *            the Object which represents destination, it can be
     *            ImageOutputStream or other objects.
     */
    public void setOutput(Object output) {
        if (output != null) {
            ImageWriterSpi spi = getOriginatingProvider();
            if (null != spi) {
                Class[] outTypes = spi.getOutputTypes();
                boolean supported = false;
                for (Class<?> element : outTypes) {
                    if (element.isInstance(output)) {
                        supported = true;
                        break;
                    }
                }
                if (!supported) {
                    throw new IllegalArgumentException("output " + output + " is not supported");
                }
            }
        }
        this.output = output;
    }

    /**
     * Writes a completed image stream that contains the specified image,
     * default metadata, and thumbnails to the output.
     * 
     * @param image
     *            the specified image to be written.
     * @throws IOException
     *             if an I/O exception has occurred during writing.
     */
    public void write(IIOImage image) throws IOException {
        write(null, image, null);
    }

    /**
     * Writes a completed image stream that contains the specified rendered
     * image, default metadata, and thumbnails to the output.
     * 
     * @param image
     *            the specified RenderedImage to be written.
     * @throws IOException
     *             if an I/O exception has occurred during writing.
     */
    public void write(RenderedImage image) throws IOException {
        write(null, new IIOImage(image, null, null), null);
    }

    /**
     * Writes a completed image stream that contains the specified image,
     * metadata and thumbnails to the output.
     * 
     * @param streamMetadata
     *            the stream metadata, or null.
     * @param image
     *            the specified image to be written, if canWriteRaster() method
     *            returns false, then Image must contain only RenderedImage.
     * @param param
     *            the ImageWriteParam, or null.
     * @throws IOException
     *             if an error occurs during writing.
     */
    public abstract void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param)
            throws IOException;

    /**
     * Disposes of any resources.
     */
    public void dispose() {
        // def impl. does nothing according to the spec.
    }

    /**
     * Requests an abort operation for current writing operation.
     */
    public synchronized void abort() {
        aborted = true;
    }

    /**
     * Checks whether or not a request to abort the current write operation has
     * been made successfully.
     * 
     * @return true, if the request to abort the current write operation has
     *         been made successfully, false otherwise.
     */
    protected synchronized boolean abortRequested() {
        return aborted;
    }

    /**
     * Clears all previous abort request, and abortRequested returns false after
     * calling this method.
     */
    protected synchronized void clearAbortRequest() {
        aborted = false;
    }

    /**
     * Adds the IIOWriteProgressListener listener.
     * 
     * @param listener
     *            the IIOWriteProgressListener listener.
     */
    public void addIIOWriteProgressListener(IIOWriteProgressListener listener) {
        if (listener == null) {
            return;
        }

        if (progressListeners == null) {
            progressListeners = new ArrayList<IIOWriteProgressListener>();
        }

        progressListeners.add(listener);
    }

    /**
     * Adds the IIOWriteWarningListener.
     * 
     * @param listener
     *            the IIOWriteWarningListener listener.
     */
    public void addIIOWriteWarningListener(IIOWriteWarningListener listener) {
        if (listener == null) {
            return;
        }

        if (warningListeners == null) {
            warningListeners = new ArrayList<IIOWriteWarningListener>();
            warningLocales = new ArrayList<Locale>();
        }

        warningListeners.add(listener);
        warningLocales.add(getLocale());
    }

    /**
     * Gets the output object that was set by setOutput method.
     * 
     * @return the output object such as ImageOutputStream, or null if it is not
     *         set.
     */
    public Object getOutput() {
        return output;
    }

    /**
     * Check output return false.
     * 
     * @return true, if successful.
     */
    private final boolean checkOutputReturnFalse() {
        if (getOutput() == null) {
            throw new IllegalStateException("getOutput() == null!");
        }
        return false;
    }

    /**
     * Unsupported operation.
     */
    private final void unsupportedOperation() {
        if (getOutput() == null) {
            throw new IllegalStateException("getOutput() == null!");
        }
        throw new UnsupportedOperationException("Unsupported write variant!");
    }

    /**
     * Returns true if a new empty image can be inserted at the specified index.
     * 
     * @param imageIndex
     *            the specified index of image.
     * @return true if a new empty image can be inserted at the specified index,
     *         false otherwise.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public boolean canInsertEmpty(int imageIndex) throws IOException {
        return checkOutputReturnFalse();
    }

    /**
     * Returns true if a new image can be inserted at the specified index.
     * 
     * @param imageIndex
     *            the specified index of image.
     * @return true if a new image can be inserted at the specified index, false
     *         otherwise.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public boolean canInsertImage(int imageIndex) throws IOException {
        return checkOutputReturnFalse();
    }

    /**
     * Returns true if the image with the specified index can be removed.
     * 
     * @param imageIndex
     *            the specified index of image.
     * @return true if the image with the specified index can be removed, false
     *         otherwise.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public boolean canRemoveImage(int imageIndex) throws IOException {
        return checkOutputReturnFalse();
    }

    /**
     * Returns true if metadata of the image with the specified index can be
     * replaced.
     * 
     * @param imageIndex
     *            the specified image index.
     * @return true if metadata of the image with the specified index can be
     *         replaced, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public boolean canReplaceImageMetadata(int imageIndex) throws IOException {
        return checkOutputReturnFalse();
    }

    /**
     * Returns true if pixels of the image with the specified index can be
     * replaced by the replacePixels methods.
     * 
     * @param imageIndex
     *            the image's index.
     * @return true if pixels of the image with the specified index can be
     *         replaced by the replacePixels methods, false otherwise.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public boolean canReplacePixels(int imageIndex) throws IOException {
        return checkOutputReturnFalse();
    }

    /**
     * Returns true if the stream metadata presented in the output can be
     * removed.
     * 
     * @return true if the stream metadata presented in the output can be
     *         removed, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public boolean canReplaceStreamMetadata() throws IOException {
        return checkOutputReturnFalse();
    }

    /**
     * Returns true if the writing of a complete image stream which contains a
     * single image is supported with undefined pixel values and associated
     * metadata and thumbnails to the output.
     * 
     * @return true if the writing of a complete image stream which contains a
     *         single image is supported, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public boolean canWriteEmpty() throws IOException {
        return checkOutputReturnFalse();
    }

    /**
     * Returns true if the methods which taken an IIOImageParameter can deal
     * with a Raster source image.
     * 
     * @return true if the methods which taken an IIOImageParameter can deal
     *         with a Raster source image, false otherwise.
     */
    public boolean canWriteRasters() {
        return false;
    }

    /**
     * Returns true if the writer can add an image to stream that already
     * contains header information.
     * 
     * @return if the writer can add an image to stream that already contains
     *         header information, false otherwise.
     */
    public boolean canWriteSequence() {
        return false;
    }

    /**
     * Ends the insertion of a new image.
     * 
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void endInsertEmpty() throws IOException {
        unsupportedOperation();
    }

    /**
     * Ends the replace pixels operation.
     * 
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void endReplacePixels() throws IOException {
        unsupportedOperation();
    }

    /**
     * Ends an empty write operation.
     * 
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void endWriteEmpty() throws IOException {
        unsupportedOperation();
    }

    /**
     * Ends the sequence of write operations.
     * 
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void endWriteSequence() throws IOException {
        unsupportedOperation();
    }

    /**
     * Gets an array of available locales.
     * 
     * @return an of array available locales.
     */
    public Locale[] getAvailableLocales() {
        if (availableLocales == null) {
            return null;
        }

        return availableLocales.clone();
    }

    /**
     * Gets an IIOMetadata object that contains default values for encoding an
     * image with the specified type.
     * 
     * @param imageType
     *            the ImageTypeSpecifier.
     * @param param
     *            the ImageWriteParam.
     * @return the IIOMetadata object.
     */
    public abstract IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType,
            ImageWriteParam param);

    /**
     * Gets an IIOMetadata object that contains default values for encoding a
     * stream of images.
     * 
     * @param param
     *            the ImageWriteParam.
     * @return the IIOMetadata object.
     */
    public abstract IIOMetadata getDefaultStreamMetadata(ImageWriteParam param);

    /**
     * Gets the current locale of this ImageWriter.
     * 
     * @return the current locale of this ImageWriter.
     */
    public Locale getLocale() {
        return locale;
    }

    /**
     * Gets the default write param. Gets a new ImageWriteParam object for this
     * ImageWriter with the current Locale.
     * 
     * @return a new ImageWriteParam object for this ImageWriter.
     */
    public ImageWriteParam getDefaultWriteParam() {
        return new ImageWriteParam(getLocale());
    }

    /**
     * Gets the number of thumbnails supported by the format being written with
     * supported image type, image write parameters, stream, and image metadata
     * objects.
     * 
     * @param imageType
     *            the ImageTypeSpecifier.
     * @param param
     *            the image's parameters.
     * @param streamMetadata
     *            the stream metadata.
     * @param imageMetadata
     *            the image metadata.
     * @return the number of thumbnails supported.
     */
    public int getNumThumbnailsSupported(ImageTypeSpecifier imageType, ImageWriteParam param,
            IIOMetadata streamMetadata, IIOMetadata imageMetadata) {
        return 0;
    }

    /**
     * Gets the preferred thumbnail sizes. Gets an array of Dimensions with the
     * sizes for thumbnail images as they are encoded in the output file or
     * stream.
     * 
     * @param imageType
     *            the ImageTypeSpecifier.
     * @param param
     *            the ImageWriteParam.
     * @param streamMetadata
     *            the stream metadata.
     * @param imageMetadata
     *            the image metadata.
     * @return the preferred thumbnail sizes.
     */
    public Dimension[] getPreferredThumbnailSizes(ImageTypeSpecifier imageType,
            ImageWriteParam param, IIOMetadata streamMetadata, IIOMetadata imageMetadata) {
        return null;
    }

    /**
     * Prepares insertion of an empty image by requesting the insertion of a new
     * image into an existing image stream.
     * 
     * @param imageIndex
     *            the image index.
     * @param imageType
     *            the image type.
     * @param width
     *            the width of the image.
     * @param height
     *            the height of the image.
     * @param imageMetadata
     *            the image metadata, or null.
     * @param thumbnails
     *            the array thumbnails for this image, or null.
     * @param param
     *            the ImageWriteParam, or null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void prepareInsertEmpty(int imageIndex, ImageTypeSpecifier imageType, int width,
            int height, IIOMetadata imageMetadata, List<? extends BufferedImage> thumbnails,
            ImageWriteParam param) throws IOException {
        unsupportedOperation();
    }

    /**
     * Prepares the writer to call the replacePixels method for the specified
     * region.
     * 
     * @param imageIndex
     *            the image's index.
     * @param region
     *            the specified region.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void prepareReplacePixels(int imageIndex, Rectangle region) throws IOException {
        unsupportedOperation();
    }

    /**
     * Prepares the writer for writing an empty image by beginning the process
     * of writing a complete image stream that contains a single image with
     * undefined pixel values, metadata and thumbnails, to the output.
     * 
     * @param streamMetadata
     *            the stream metadata.
     * @param imageType
     *            the image type.
     * @param width
     *            the width of the image.
     * @param height
     *            the height of the image.
     * @param imageMetadata
     *            the image's metadata, or null.
     * @param thumbnails
     *            the image's thumbnails, or null.
     * @param param
     *            the image's parameters, or null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void prepareWriteEmpty(IIOMetadata streamMetadata, ImageTypeSpecifier imageType,
            int width, int height, IIOMetadata imageMetadata,
            List<? extends BufferedImage> thumbnails, ImageWriteParam param) throws IOException {
        unsupportedOperation();
    }

    /**
     * Prepares a stream to accept calls of writeToSequence method using the
     * metadata object.
     * 
     * @param streamMetadata
     *            the stream metadata.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IOException {
        unsupportedOperation();
    }

    /**
     * Processes the completion of a thumbnail read by calling their
     * thumbnailComplete method of registered IIOWriteProgressListeners.
     */
    protected void processThumbnailComplete() {
        if (progressListeners != null) {
            for (IIOWriteProgressListener listener : progressListeners) {
                listener.thumbnailComplete(this);
            }
        }
    }

    /**
     * Processes the current percentage of thumbnail completion by calling their
     * thumbnailProgress method of registered IIOWriteProgressListeners.
     * 
     * @param percentageDone
     *            the percentage done.
     */
    protected void processThumbnailProgress(float percentageDone) {
        if (progressListeners != null) {
            for (IIOWriteProgressListener listener : progressListeners) {
                listener.thumbnailProgress(this, percentageDone);
            }
        }
    }

    /**
     * Processes the start of a thumbnail read by calling thumbnailStarted
     * method of registered IIOWriteProgressListeners.
     * 
     * @param imageIndex
     *            the image index.
     * @param thumbnailIndex
     *            the thumbnail index.
     */
    protected void processThumbnailStarted(int imageIndex, int thumbnailIndex) {
        if (progressListeners != null) {
            for (IIOWriteProgressListener listener : progressListeners) {
                listener.thumbnailStarted(this, imageIndex, thumbnailIndex);
            }
        }
    }

    /**
     * Processes that the writing has been aborted by calling writeAborted
     * method of registered IIOWriteProgressListeners.
     */
    protected void processWriteAborted() {
        if (progressListeners != null) {
            for (IIOWriteProgressListener listener : progressListeners) {
                listener.writeAborted(this);
            }
        }
    }

    /**
     * Removes the all IIOWriteProgressListener listeners.
     */
    public void removeAllIIOWriteProgressListeners() {
        progressListeners = null;
    }

    /**
     * Removes the all IIOWriteWarningListener listeners.
     */
    public void removeAllIIOWriteWarningListeners() {
        warningListeners = null;
        warningLocales = null;
    }

    /**
     * Removes the specified IIOWriteProgressListener listener.
     * 
     * @param listener
     *            the registered IIOWriteProgressListener to be removed.
     */
    public void removeIIOWriteProgressListener(IIOWriteProgressListener listener) {
        if (progressListeners != null && listener != null) {
            if (progressListeners.remove(listener) && progressListeners.isEmpty()) {
                progressListeners = null;
            }
        }
    }

    /**
     * Removes the specified IIOWriteWarningListener listener.
     * 
     * @param listener
     *            the registered IIOWriteWarningListener listener to be removed.
     */
    public void removeIIOWriteWarningListener(IIOWriteWarningListener listener) {
        if (warningListeners == null || listener == null) {
            return;
        }

        int idx = warningListeners.indexOf(listener);
        if (idx > -1) {
            warningListeners.remove(idx);
            warningLocales.remove(idx);

            if (warningListeners.isEmpty()) {
                warningListeners = null;
                warningLocales = null;
            }
        }
    }

    /**
     * Removes the image with the specified index from the stream.
     * 
     * @param imageIndex
     *            the image's index.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void removeImage(int imageIndex) throws IOException {
        unsupportedOperation();
    }

    /**
     * Replaces image metadata of the image with specified index.
     * 
     * @param imageIndex
     *            the image's index.
     * @param imageMetadata
     *            the image metadata.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void replaceImageMetadata(int imageIndex, IIOMetadata imageMetadata) throws IOException {
        unsupportedOperation();
    }

    /**
     * Replaces a part of an image presented in the output with the specified
     * RenderedImage.
     * 
     * @param image
     *            the RenderedImage.
     * @param param
     *            the ImageWriteParam.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void replacePixels(RenderedImage image, ImageWriteParam param) throws IOException {
        unsupportedOperation();
    }

    /**
     * Replaces a part of an image presented in the output with the specified
     * Raster.
     * 
     * @param raster
     *            the Raster.
     * @param param
     *            the ImageWriteParam.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void replacePixels(Raster raster, ImageWriteParam param) throws IOException {
        unsupportedOperation();
    }

    /**
     * Replaces the stream metadata of the output with new IIOMetadata.
     * 
     * @param streamMetadata
     *            the new stream metadata.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void replaceStreamMetadata(IIOMetadata streamMetadata) throws IOException {
        unsupportedOperation();
    }

    /**
     * Sets the locale of this ImageWriter.
     * 
     * @param locale
     *            the new locale.
     */
    public void setLocale(Locale locale) {
        if (locale == null) {
            this.locale = null;
            return;
        }

        Locale[] locales = getAvailableLocales();
        boolean validLocale = false;
        if (locales != null) {
            for (int i = 0; i < locales.length; i++) {
                if (locale.equals(locales[i])) {
                    validLocale = true;
                    break;
                }
            }
        }

        if (validLocale) {
            this.locale = locale;
        } else {
            throw new IllegalArgumentException("Invalid locale!");
        }
    }

    /**
     * Resets this ImageWriter.
     */
    public void reset() {
        setOutput(null);
        setLocale(null);
        removeAllIIOWriteWarningListeners();
        removeAllIIOWriteProgressListeners();
        clearAbortRequest();
    }

    /**
     * Inserts image into existing output stream.
     * 
     * @param imageIndex
     *            the image index where an image will be written.
     * @param image
     *            the specified image to be written.
     * @param param
     *            the ImageWriteParam, or null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public void writeInsert(int imageIndex, IIOImage image, ImageWriteParam param)
            throws IOException {
        unsupportedOperation();
    }

    /**
     * Writes the specified image to the sequence.
     * 
     * @param image
     *            the image to be written.
     * @param param
     *            the ImageWriteParam, or null.
     * @throws IOException
     *             if an I/O exception has occurred during writing.
     */
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IOException {
        unsupportedOperation();
    }
}
