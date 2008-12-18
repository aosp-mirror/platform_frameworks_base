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

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.event.IIOReadUpdateListener;
import java.util.Locale;
import java.util.List;
import java.util.Iterator;
import java.util.Set;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.*;

/**
 * The ImageReader class is an abstract class for decoding images. ImageReader
 * objects are instantiated by the service provider interface, ImageReaderSpi
 * class, for the specific format. ImageReaderSpi class should be registered
 * with the IIORegistry, which uses them for format recognition and presentation
 * of available format readers and writers.
 * 
 * @since Android 1.0
 */
public abstract class ImageReader {

    /**
     * The originating provider.
     */
    protected ImageReaderSpi originatingProvider;

    /**
     * The input object such as ImageInputStream.
     */
    protected Object input;

    /**
     * The seek forward only.
     */
    protected boolean seekForwardOnly;

    /**
     * The ignore metadata flag indicates whether current input source has been
     * marked as metadata is allowed to be ignored by setInput.
     */
    protected boolean ignoreMetadata;

    /**
     * The minimum index.
     */
    protected int minIndex;

    /**
     * The available locales.
     */
    protected Locale[] availableLocales;

    /**
     * The locale.
     */
    protected Locale locale;

    /**
     * The list of warning listeners.
     */
    protected List<IIOReadWarningListener> warningListeners;

    /**
     * The list of warning locales.
     */
    protected List<Locale> warningLocales;

    /**
     * The list of progress listeners.
     */
    protected List<IIOReadProgressListener> progressListeners;

    /**
     * The list of update listeners.
     */
    protected List<IIOReadUpdateListener> updateListeners;

    /**
     * Instantiates a new ImageReader.
     * 
     * @param originatingProvider
     *            the ImageReaderSpi which instantiates this ImageReader.
     */
    protected ImageReader(ImageReaderSpi originatingProvider) {
        this.originatingProvider = originatingProvider;
    }

    /**
     * Gets the format name of this input source.
     * 
     * @return the format name of this input source.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public String getFormatName() throws IOException {
        return originatingProvider.getFormatNames()[0];
    }

    /**
     * Gets the ImageReaderSpi which instantiated this ImageReader.
     * 
     * @return the ImageReaderSpi.
     */
    public ImageReaderSpi getOriginatingProvider() {
        return originatingProvider;
    }

    /**
     * Sets the specified Object as the input source of this ImageReader.
     * 
     * @param input
     *            the input source, it can be an ImageInputStream or other
     *            supported objects.
     * @param seekForwardOnly
     *            indicates whether the stream must be read sequentially from
     *            its current starting point.
     * @param ignoreMetadata
     *            parameter which indicates if metadata may be ignored during
     *            reads or not.
     */
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        if (input != null) {
            if (!isSupported(input) && !(input instanceof ImageInputStream)) {
                throw new IllegalArgumentException("input " + input + " is not supported");
            }
        }
        this.minIndex = 0;
        this.seekForwardOnly = seekForwardOnly;
        this.ignoreMetadata = ignoreMetadata;
        this.input = input;
    }

    /**
     * Checks if is supported.
     * 
     * @param input
     *            the input.
     * @return true, if is supported.
     */
    private boolean isSupported(Object input) {
        ImageReaderSpi spi = getOriginatingProvider();
        if (null != spi) {
            Class[] outTypes = spi.getInputTypes();
            for (Class<?> element : outTypes) {
                if (element.isInstance(input)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sets the specified Object as the input source of this ImageReader.
     * Metadata is not ignored.
     * 
     * @param input
     *            the input source, it can be an ImageInputStream or other
     *            supported objects.
     * @param seekForwardOnly
     *            indicates whether the stream must be read sequentially from
     *            its current starting point.
     */
    public void setInput(Object input, boolean seekForwardOnly) {
        setInput(input, seekForwardOnly, false);
    }

    /**
     * Sets the specified Object as the input source of this ImageReader.
     * Metadata is not ignored and forward seeking is not required.
     * 
     * @param input
     *            the input source, it can be ImageInputStream or other objects.
     */
    public void setInput(Object input) {
        setInput(input, false, false);
    }

    /**
     * Gets the input source object of this ImageReader, or returns null.
     * 
     * @return the input source object such as ImageInputStream, or null.
     */
    public Object getInput() {
        return input;
    }

    /**
     * Checks if the input source supports only forward reading, or not.
     * 
     * @return true, if the input source supports only forward reading, false
     *         otherwise.
     */
    public boolean isSeekForwardOnly() {
        return seekForwardOnly;
    }

    /**
     * Returns true if the current input source allows to metadata to be ignored
     * by passing true as the ignoreMetadata argument to the setInput method.
     * 
     * @return true, if the current input source allows to metadata to be
     *         ignored by passing true as the ignoreMetadata argument to the
     *         setInput method.
     */
    public boolean isIgnoringMetadata() {
        return ignoreMetadata;
    }

    /**
     * Gets the minimum valid index for reading an image, thumbnail, or image
     * metadata.
     * 
     * @return the minimum valid index for reading an image, thumbnail, or image
     *         metadata.
     */
    public int getMinIndex() {
        return minIndex;
    }

    /**
     * Gets the available locales.
     * 
     * @return an array of the available locales.
     */
    public Locale[] getAvailableLocales() {
        return availableLocales;
    }

    /**
     * Sets the locale to this ImageReader.
     * 
     * @param locale
     *            the Locale.
     */
    public void setLocale(Locale locale) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Gets the locale of this ImageReader.
     * 
     * @return the locale of this ImageReader.
     */
    public Locale getLocale() {
        return locale;
    }

    /**
     * Gets the number of images available in the current input source.
     * 
     * @param allowSearch
     *            the parameter which indicates what a search is required; if
     *            false, the reader may return -1 without searching.
     * @return the number of images.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract int getNumImages(boolean allowSearch) throws IOException;

    /**
     * Gets the width of the specified image in input source.
     * 
     * @param imageIndex
     *            the image index.
     * @return the width in pixels.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract int getWidth(int imageIndex) throws IOException;

    /**
     * Gets the height of the specified image in input source.
     * 
     * @param imageIndex
     *            the image index.
     * @return the height in pixels.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract int getHeight(int imageIndex) throws IOException;

    /**
     * Checks if the storage format of the specified image places an impediment
     * on random pixels access or not.
     * 
     * @param imageIndex
     *            the image's index.
     * @return true, if the storage format of the specified image places an
     *         impediment on random pixels access, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public boolean isRandomAccessEasy(int imageIndex) throws IOException {
        return false; // def
    }

    /**
     * Gets the aspect ratio (width devided by height) of the image.
     * 
     * @param imageIndex
     *            the image index.
     * @return the aspect ratio of the image.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public float getAspectRatio(int imageIndex) throws IOException {
        return (float)getWidth(imageIndex) / getHeight(imageIndex);
    }

    /**
     * Gets an ImageTypeSpecifier which indicates the type of the specified
     * image.
     * 
     * @param imageIndex
     *            the image's index.
     * @return the ImageTypeSpecifier.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public ImageTypeSpecifier getRawImageType(int imageIndex) throws IOException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Gets an Iterator of ImageTypeSpecifier objects which are associated with
     * image types that may be used when decoding specified image.
     * 
     * @param imageIndex
     *            the image index.
     * @return an Iterator of ImageTypeSpecifier objects.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException;

    /**
     * Gets the default ImageReadParam object.
     * 
     * @return the ImageReadParam object.
     */
    public ImageReadParam getDefaultReadParam() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Gets an IIOMetadata object for this input source.
     * 
     * @return the IIOMetadata.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract IIOMetadata getStreamMetadata() throws IOException;

    /**
     * Gets an IIOMetadata object for this input source.
     * 
     * @param formatName
     *            the desired metadata format to be used in the returned
     *            IIOMetadata object.
     * @param nodeNames
     *            the node names of the document.
     * @return the IIOMetadata.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public IIOMetadata getStreamMetadata(String formatName, Set<String> nodeNames)
            throws IOException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Gets the image metadata of the specified image in input source.
     * 
     * @param imageIndex
     *            the image index.
     * @return the IIOMetadata.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract IIOMetadata getImageMetadata(int imageIndex) throws IOException;

    /**
     * Gets the image metadata of the specified image input source.
     * 
     * @param imageIndex
     *            the image index.
     * @param formatName
     *            the desired metadata format to be used in the returned
     *            IIOMetadata object.
     * @param nodeNames
     *            the node names which can be contained in the document.
     * @return the IIOMetadata.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public IIOMetadata getImageMetadata(int imageIndex, String formatName, Set<String> nodeNames)
            throws IOException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Reads the specified image and returns it as a BufferedImage using the
     * default ImageReadParam.
     * 
     * @param imageIndex
     *            the image index.
     * @return the BufferedImage.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public BufferedImage read(int imageIndex) throws IOException {
        return read(imageIndex, null);
    }

    /**
     * Reads the specified image and returns it as a BufferedImage using the
     * specified ImageReadParam.
     * 
     * @param imageIndex
     *            the image index.
     * @param param
     *            the ImageReadParam.
     * @return the BufferedImage.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract BufferedImage read(int imageIndex, ImageReadParam param) throws IOException;

    /**
     * Reads the specified image and returns an IIOImage with this image,
     * thumbnails, and metadata for this image, using the specified
     * ImageReadParam.
     * 
     * @param imageIndex
     *            the image index.
     * @param param
     *            the ImageReadParam.
     * @return the IIOImage.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public IIOImage readAll(int imageIndex, ImageReadParam param) throws IOException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Returns an Iterator of IIOImages from the input source.
     * 
     * @param params
     *            the Iterator of ImageReadParam objects.
     * @return the iterator of IIOImages.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public Iterator<IIOImage> readAll(Iterator<? extends ImageReadParam> params) throws IOException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Checks whether or not this plug-in supports reading a Raster.
     * 
     * @return true, if this plug-in supports reading a Raster, false otherwise.
     */
    public boolean canReadRaster() {
        return false; // def
    }

    /**
     * Reads a new Raster object which contains the raw pixel data from the
     * image.
     * 
     * @param imageIndex
     *            the image index.
     * @param param
     *            the ImageReadParam.
     * @return the Raster.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public Raster readRaster(int imageIndex, ImageReadParam param) throws IOException {
        throw new UnsupportedOperationException("Unsupported");
    }

    /**
     * Checks if the specified image has tiles or not.
     * 
     * @param imageIndex
     *            the image's index.
     * @return true, if the specified image has tiles, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public boolean isImageTiled(int imageIndex) throws IOException {
        return false; // def
    }

    /**
     * Gets the tile width in the specified image.
     * 
     * @param imageIndex
     *            the image's index.
     * @return the tile width.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public int getTileWidth(int imageIndex) throws IOException {
        return getWidth(imageIndex); // def
    }

    /**
     * Gets the tile height in the specified image.
     * 
     * @param imageIndex
     *            the image's index.
     * @return the tile height.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public int getTileHeight(int imageIndex) throws IOException {
        return getHeight(imageIndex); // def
    }

    /**
     * Gets the X coordinate of the upper left corner of the tile grid in the
     * specified image.
     * 
     * @param imageIndex
     *            the image's index.
     * @return the X coordinate of the upper left corner of the tile grid.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public int getTileGridXOffset(int imageIndex) throws IOException {
        return 0; // def
    }

    /**
     * Gets the Y coordinate of the upper left corner of the tile grid in the
     * specified image.
     * 
     * @param imageIndex
     *            the image's index.
     * @return the Y coordinate of the upper left corner of the tile grid.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public int getTileGridYOffset(int imageIndex) throws IOException {
        return 0; // def
    }

    /**
     * Reads the tile specified by the tileX and tileY parameters of the
     * specified image and returns it as a BufferedImage.
     * 
     * @param imageIndex
     *            the image index.
     * @param tileX
     *            the X index of tile.
     * @param tileY
     *            the Y index of tile.
     * @return the BufferedImage.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public BufferedImage readTile(int imageIndex, int tileX, int tileY) throws IOException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Reads the tile specified by the tileX and tileY parameters of the
     * specified image and returns it as a Raster.
     * 
     * @param imageIndex
     *            the image index.
     * @param tileX
     *            the X index of tile.
     * @param tileY
     *            the Y index of tile.
     * @return the Raster.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public Raster readTileRaster(int imageIndex, int tileX, int tileY) throws IOException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Reads the specified image using the specified ImageReadParam and returns
     * it as a RenderedImage.
     * 
     * @param imageIndex
     *            the image index.
     * @param param
     *            the ImageReadParam.
     * @return the RenderedImage.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public RenderedImage readAsRenderedImage(int imageIndex, ImageReadParam param)
            throws IOException {
        return read(imageIndex, param);
    }

    /**
     * Returns true if the image format supported by this reader supports
     * thumbnail preview images.
     * 
     * @return true, if the image format supported by this reader supports
     *         thumbnail preview images, false otherwise.
     */
    public boolean readerSupportsThumbnails() {
        return false; // def
    }

    /**
     * Checks if the specified image has thumbnails or not.
     * 
     * @param imageIndex
     *            the image's index.
     * @return true, if the specified image has thumbnails, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public boolean hasThumbnails(int imageIndex) throws IOException {
        return getNumThumbnails(imageIndex) > 0; // def
    }

    /**
     * Gets the number of thumbnails for the specified image.
     * 
     * @param imageIndex
     *            the image's index.
     * @return the number of thumbnails.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public int getNumThumbnails(int imageIndex) throws IOException {
        return 0; // def
    }

    /**
     * Gets the width of the specified thumbnail for the specified image.
     * 
     * @param imageIndex
     *            the image's index.
     * @param thumbnailIndex
     *            the thumbnail's index.
     * @return the thumbnail width.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public int getThumbnailWidth(int imageIndex, int thumbnailIndex) throws IOException {
        return readThumbnail(imageIndex, thumbnailIndex).getWidth(); // def
    }

    /**
     * Gets the height of the specified thumbnail for the specified image.
     * 
     * @param imageIndex
     *            the image's index.
     * @param thumbnailIndex
     *            the thumbnail's index.
     * @return the thumbnail height.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public int getThumbnailHeight(int imageIndex, int thumbnailIndex) throws IOException {
        return readThumbnail(imageIndex, thumbnailIndex).getHeight(); // def
    }

    /**
     * Reads the thumbnail image for the specified image as a BufferedImage.
     * 
     * @param imageIndex
     *            the image index.
     * @param thumbnailIndex
     *            the thumbnail index.
     * @return the BufferedImage.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public BufferedImage readThumbnail(int imageIndex, int thumbnailIndex) throws IOException {
        throw new UnsupportedOperationException("Unsupported"); // def
    }

    /**
     * Requests an abort operation for current reading operation.
     */
    public void abort() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Checks whether or not a request to abort the current read operation has
     * been made successfully.
     * 
     * @return true, if the request to abort the current read operation has been
     *         made successfully, false otherwise.
     */
    protected boolean abortRequested() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Clears all previous abort request, and abortRequested returns false after
     * calling this method.
     */
    protected void clearAbortRequest() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Adds the IIOReadWarningListener.
     * 
     * @param listener
     *            the IIOReadWarningListener.
     */
    public void addIIOReadWarningListener(IIOReadWarningListener listener) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Removes the specified IIOReadWarningListener.
     * 
     * @param listener
     *            the IIOReadWarningListener to be removed.
     */
    public void removeIIOReadWarningListener(IIOReadWarningListener listener) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Removes all registered IIOReadWarningListeners.
     */
    public void removeAllIIOReadWarningListeners() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Adds the IIOReadProgressListener.
     * 
     * @param listener
     *            the IIOReadProgressListener.
     */
    public void addIIOReadProgressListener(IIOReadProgressListener listener) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Removes the specified IIOReadProgressListener.
     * 
     * @param listener
     *            the IIOReadProgressListener to be removed.
     */
    public void removeIIOReadProgressListener(IIOReadProgressListener listener) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Removes registered IIOReadProgressListeners.
     */
    public void removeAllIIOReadProgressListeners() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Adds the IIOReadUpdateListener.
     * 
     * @param listener
     *            the IIOReadUpdateListener.
     */
    public void addIIOReadUpdateListener(IIOReadUpdateListener listener) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Removes the specified IIOReadUpdateListener.
     * 
     * @param listener
     *            the IIOReadUpdateListener to be removed.
     */
    public void removeIIOReadUpdateListener(IIOReadUpdateListener listener) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Removes registered IIOReadUpdateListeners.
     */
    public void removeAllIIOReadUpdateListeners() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the start of an sequence of image reads by calling the
     * sequenceStarted method on all registered IIOReadProgressListeners.
     * 
     * @param minIndex
     *            the minimum index.
     */
    protected void processSequenceStarted(int minIndex) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the completion of an sequence of image reads by calling
     * sequenceComplete method on all registered IIOReadProgressListeners.
     */
    protected void processSequenceComplete() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the start of an image read by calling the imageStarted method
     * on all registered IIOReadProgressListeners.
     * 
     * @param imageIndex
     *            the image index.
     */
    protected void processImageStarted(int imageIndex) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the current percentage of image completion by calling the
     * imageProgress method on all registered IIOReadProgressListeners.
     * 
     * @param percentageDone
     *            the percentage done.
     */
    protected void processImageProgress(float percentageDone) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes image completion by calling the imageComplete method on all
     * registered IIOReadProgressListeners.
     */
    protected void processImageComplete() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the start of a thumbnail read by calling the thumbnailStarted
     * method on all registered IIOReadProgressListeners.
     * 
     * @param imageIndex
     *            the image index.
     * @param thumbnailIndex
     *            the thumbnail index.
     */
    protected void processThumbnailStarted(int imageIndex, int thumbnailIndex) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the current percentage of thumbnail completion by calling the
     * thumbnailProgress method on all registered IIOReadProgressListeners.
     * 
     * @param percentageDone
     *            the percentage done.
     */
    protected void processThumbnailProgress(float percentageDone) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the completion of a thumbnail read by calling the
     * thumbnailComplete method on all registered IIOReadProgressListeners.
     */
    protected void processThumbnailComplete() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes a read aborted event by calling the readAborted method on all
     * registered IIOReadProgressListeners.
     */
    protected void processReadAborted() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the beginning of a progressive pass by calling the passStarted
     * method on all registered IIOReadUpdateListeners.
     * 
     * @param theImage
     *            the image to be updated.
     * @param pass
     *            the current pass index.
     * @param minPass
     *            the minimum pass index.
     * @param maxPass
     *            the maximum pass index.
     * @param minX
     *            the X coordinate of of the upper left pixel.
     * @param minY
     *            the Y coordinate of of the upper left pixel.
     * @param periodX
     *            the horizontal separation between pixels.
     * @param periodY
     *            the vertical separation between pixels.
     * @param bands
     *            the number of affected bands.
     */
    protected void processPassStarted(BufferedImage theImage, int pass, int minPass, int maxPass,
            int minX, int minY, int periodX, int periodY, int[] bands) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the update of a set of samples by calling the imageUpdate
     * method on all registered IIOReadUpdateListeners.
     * 
     * @param theImage
     *            the image to be updated.
     * @param minX
     *            the X coordinate of the upper left pixel.
     * @param minY
     *            the Y coordinate of the upper left pixel.
     * @param width
     *            the width of updated area.
     * @param height
     *            the height of updated area.
     * @param periodX
     *            the horizontal separation between pixels.
     * @param periodY
     *            the vertical separation between pixels.
     * @param bands
     *            the number of affected bands.
     */
    protected void processImageUpdate(BufferedImage theImage, int minX, int minY, int width,
            int height, int periodX, int periodY, int[] bands) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the end of a progressive pass by calling passComplete method of
     * registered IIOReadUpdateListeners.
     * 
     * @param theImage
     *            the image to be updated.
     */
    protected void processPassComplete(BufferedImage theImage) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the beginning of a thumbnail progressive pass by calling the
     * thumbnailPassStarted method on all registered IIOReadUpdateListeners.
     * 
     * @param theThumbnail
     *            the thumbnail to be updated.
     * @param pass
     *            the current pass index.
     * @param minPass
     *            the minimum pass index.
     * @param maxPass
     *            the maximum pass index.
     * @param minX
     *            the X coordinate of the upper left pixel.
     * @param minY
     *            the Y coordinate of the upper left pixel.
     * @param periodX
     *            the horizontal separation between pixels.
     * @param periodY
     *            the vertical separation between pixels.
     * @param bands
     *            the number of affected bands.
     */
    protected void processThumbnailPassStarted(BufferedImage theThumbnail, int pass, int minPass,
            int maxPass, int minX, int minY, int periodX, int periodY, int[] bands) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the update of a set of samples in a thumbnail image by calling
     * the thumbnailUpdate method on all registered IIOReadUpdateListeners.
     * 
     * @param theThumbnail
     *            the thumbnail to be updated.
     * @param minX
     *            the X coordinate of the upper left pixel.
     * @param minY
     *            the Y coordinate of the upper left pixel.
     * @param width
     *            the total width of the updated area.
     * @param height
     *            the total height of the updated area.
     * @param periodX
     *            the horizontal separation between pixels.
     * @param periodY
     *            the vertical separation between pixels.
     * @param bands
     *            the number of affected bands.
     */
    protected void processThumbnailUpdate(BufferedImage theThumbnail, int minX, int minY,
            int width, int height, int periodX, int periodY, int[] bands) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes the end of a thumbnail progressive pass by calling the
     * thumbnailPassComplete method on all registered IIOReadUpdateListeners.
     * 
     * @param theThumbnail
     *            the thumbnail to be updated.
     */
    protected void processThumbnailPassComplete(BufferedImage theThumbnail) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes a warning message by calling warningOccurred method of
     * registered IIOReadWarningListeners.
     * 
     * @param warning
     *            the warning.
     */
    protected void processWarningOccurred(String warning) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Processes a warning by calling the warningOccurred method of on all
     * registered IIOReadWarningListeners.
     * 
     * @param baseName
     *            the base name of ResourceBundles.
     * @param keyword
     *            the keyword to index the warning among ResourceBundles.
     */
    protected void processWarningOccurred(String baseName, String keyword) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Resets this ImageReader.
     */
    public void reset() {
        // def
        setInput(null, false);
        setLocale(null);
        removeAllIIOReadUpdateListeners();
        removeAllIIOReadWarningListeners();
        removeAllIIOReadProgressListeners();
        clearAbortRequest();
    }

    /**
     * Disposes of any resources.
     */
    public void dispose() {
        // do nothing by def
    }

    /**
     * Gets the region of source image that should be read with the specified
     * width, height and ImageReadParam.
     * 
     * @param param
     *            the ImageReadParam object, or null.
     * @param srcWidth
     *            the source image's width.
     * @param srcHeight
     *            the source image's height.
     * @return the Rectangle of source region.
     */
    protected static Rectangle getSourceRegion(ImageReadParam param, int srcWidth, int srcHeight) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Computes the specified source region and the specified destination region
     * with the specified the width and height of the source image, an optional
     * destination image, and an ImageReadParam.
     * 
     * @param param
     *            the an ImageReadParam object, or null.
     * @param srcWidth
     *            the source image's width.
     * @param srcHeight
     *            the source image's height.
     * @param image
     *            the destination image.
     * @param srcRegion
     *            the source region.
     * @param destRegion
     *            the destination region.
     */
    protected static void computeRegions(ImageReadParam param, int srcWidth, int srcHeight,
            BufferedImage image, Rectangle srcRegion, Rectangle destRegion) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Checks the validity of the source and destination band and is called when
     * the reader knows the number of bands of the source image and the number
     * of bands of the destination image.
     * 
     * @param param
     *            the ImageReadParam for reading the Image.
     * @param numSrcBands
     *            the number of bands in the source.
     * @param numDstBands
     *            the number of bands in the destination.
     */
    protected static void checkReadParamBandSettings(ImageReadParam param, int numSrcBands,
            int numDstBands) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Gets the destination image where the decoded data is written.
     * 
     * @param param
     *            the ImageReadParam.
     * @param imageTypes
     *            the iterator of ImageTypeSpecifier objects.
     * @param width
     *            the width of the image being decoded.
     * @param height
     *            the height of the image being decoded.
     * @return the BufferedImage where decoded pixels should be written.
     * @throws IIOException
     *             the IIOException is thrown if there is no suitable
     *             ImageTypeSpecifier.
     */
    protected static BufferedImage getDestination(ImageReadParam param,
            Iterator<ImageTypeSpecifier> imageTypes, int width, int height) throws IIOException {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
