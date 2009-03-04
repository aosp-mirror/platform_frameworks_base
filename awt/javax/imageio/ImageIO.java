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

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.spi.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Arrays;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.net.URL;

/**
 * The ImageIO class provides static methods to perform reading and writing
 * operations using registered ImageReader and ImageWriter objects.
 * 
 * @since Android 1.0
 */
public final class ImageIO {

    /**
     * The constant registry.
     */
    private static final IIORegistry registry = IIORegistry.getDefaultInstance();

    /**
     * Instantiates a new ImageIO.
     */
    private ImageIO() {
    }

    /**
     * Scans for plug-ins in the class path, loads spi classes, and registers
     * them with the IIORegistry.
     */
    public static void scanForPlugins() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Sets flag which indicates whether a cache file is used when creating
     * ImageInputStreams and ImageOutputStreams or not.
     * 
     * @param useCache
     *            the use cache flag.
     */
    public static void setUseCache(boolean useCache) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets the flag which indicates whether a cache file is used when creating
     * ImageInputStreams and ImageOutputStreams or not. This method returns the
     * current value which is set by setUseCache method.
     * 
     * @return the use cache flag.
     */
    public static boolean getUseCache() {
        // TODO implement
        return false;
    }

    /**
     * Sets the cache directory.
     * 
     * @param cacheDirectory
     *            the File which specifies a cache directory.
     */
    public static void setCacheDirectory(File cacheDirectory) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets the directory where cache files are created, returned the file which
     * is set by setCacheDirectory method, or null.
     * 
     * @return the File object which is set by setCacheDirectory method, or
     *         null.
     */
    public static File getCacheDirectory() {
        // TODO implement
        // -- null indicates system-dep default temporary directory
        return null;
    }

    /**
     * Creates an ImageInputStream from the specified Object. The specified
     * Object should obtain the input source such as File, or InputStream.
     * 
     * @param input
     *            the input Object such as File, or InputStream.
     * @return the ImageInputStream object, or null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static ImageInputStream createImageInputStream(Object input) throws IOException {

        if (input == null) {
            throw new IllegalArgumentException("input source cannot be NULL");
        }

        Iterator<ImageInputStreamSpi> it = registry.getServiceProviders(ImageInputStreamSpi.class,
                true);

        while (it.hasNext()) {
            ImageInputStreamSpi spi = it.next();
            if (spi.getInputClass().isInstance(input)) {
                return spi.createInputStreamInstance(input);
            }
        }
        return null;
    }

    /**
     * Creates an ImageOutputStream using the specified Object. The specified
     * Object should obtain the output source such as File, or OutputStream.
     * 
     * @param output
     *            the output Object such as File, or OutputStream.
     * @return the ImageOutputStream object, or null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static ImageOutputStream createImageOutputStream(Object output) throws IOException {
        if (output == null) {
            throw new IllegalArgumentException("output destination cannot be NULL");
        }

        Iterator<ImageOutputStreamSpi> it = registry.getServiceProviders(
                ImageOutputStreamSpi.class, true);

        while (it.hasNext()) {
            ImageOutputStreamSpi spi = it.next();
            if (spi.getOutputClass().isInstance(output)) {
                // todo - use getUseCache and getCacheDir here
                return spi.createOutputStreamInstance(output);
            }
        }
        return null;
    }

    /**
     * Gets the array of format names as String which can be decoded by
     * registered ImageReader objects.
     * 
     * @return the array of format names.
     */
    public static String[] getReaderFormatNames() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets the array of MIME types as String which can be decoded by registered
     * ImageReader objects.
     * 
     * @return the array of MIME types.
     */
    public static String[] getReaderMIMETypes() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets the Iterator of registered ImageReader which are able to decode an
     * input data specified by input Object.
     * 
     * @param input
     *            the input Object with encoded data such as ImageInputStream
     *            object.
     * @return the Iterator of registered ImageReader.
     */
    public static Iterator<ImageReader> getImageReaders(Object input) {
        if (input == null) {
            throw new NullPointerException("input cannot be NULL");
        }

        Iterator<ImageReaderSpi> it = registry.getServiceProviders(ImageReaderSpi.class,
                new CanReadFilter(input), true);

        return new SpiIteratorToReadersIteratorWrapper(it);
    }

    /**
     * Gets the Iterator of registered ImageReader which are able to decode the
     * specified format.
     * 
     * @param formatName
     *            the format name such as "jpeg", or "gif".
     * @return the Iterator of registered ImageReader.
     */
    public static Iterator<ImageReader> getImageReadersByFormatName(String formatName) {
        if (formatName == null) {
            throw new NullPointerException("format name cannot be NULL");
        }

        Iterator<ImageReaderSpi> it = registry.getServiceProviders(ImageReaderSpi.class,
                new FormatFilter(formatName), true);

        return new SpiIteratorToReadersIteratorWrapper(it);
    }

    /**
     * Gets the Iterator which lists the registered ImageReader objects that are
     * able to decode files with the specified suffix.
     * 
     * @param fileSuffix
     *            the file suffix such as "jpg".
     * @return the Iterator of registered ImageReaders.
     */
    public static Iterator<ImageReader> getImageReadersBySuffix(String fileSuffix) {
        if (fileSuffix == null) {
            throw new NullPointerException("suffix cannot be NULL");
        }
        Iterator<ImageReaderSpi> it = registry.getServiceProviders(ImageReaderSpi.class,
                new SuffixFilter(fileSuffix), true);

        return new SpiIteratorToReadersIteratorWrapper(it);
    }

    /**
     * Gets the Iterator of registered ImageReader objects that are able to
     * decode files with the specified MIME type.
     * 
     * @param MIMEType
     *            the MIME type such as "image/jpeg".
     * @return the Iterator of registered ImageReaders.
     */
    public static Iterator<ImageReader> getImageReadersByMIMEType(String MIMEType) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets an array of Strings giving the names of the formats supported by
     * registered ImageWriter objects.
     * 
     * @return the array of format names.
     */
    public static String[] getWriterFormatNames() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets an array of Strings giving the MIME types of the formats supported
     * by registered ImageWriter objects.
     * 
     * @return the array of MIME types.
     */
    public static String[] getWriterMIMETypes() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets the Iterator which lists the registered ImageReader objects that are
     * able to encode the specified image format.
     * 
     * @param formatName
     *            the image format name such as "jpeg".
     * @return the Iterator of registered ImageWriter.
     */
    public static Iterator<ImageWriter> getImageWritersByFormatName(String formatName) {
        if (formatName == null) {
            throw new NullPointerException("format name cannot be NULL");
        }

        Iterator<ImageWriterSpi> it = registry.getServiceProviders(ImageWriterSpi.class,
                new FormatFilter(formatName), true);

        return new SpiIteratorToWritersIteratorWrapper(it);
    }

    /**
     * Gets the Iterator which lists the registered ImageReader objects that are
     * able to encode the specified suffix.
     * 
     * @param fileSuffix
     *            the file suffix such as "jpg".
     * @return the Iterator of registered ImageWriter.
     */
    public static Iterator<ImageWriter> getImageWritersBySuffix(String fileSuffix) {
        if (fileSuffix == null) {
            throw new NullPointerException("suffix cannot be NULL");
        }
        Iterator<ImageWriterSpi> it = registry.getServiceProviders(ImageWriterSpi.class,
                new SuffixFilter(fileSuffix), true);
        return new SpiIteratorToWritersIteratorWrapper(it);
    }

    /**
     * Gets the Iterator which lists the registered ImageReader objects that are
     * able to encode the specified MIME type.
     * 
     * @param MIMEType
     *            the MIME type such as "image/jpeg".
     * @return the Iterator of registered ImageWriter.
     */
    public static Iterator<ImageWriter> getImageWritersByMIMEType(String MIMEType) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets an ImageWriter object which corresponds to the specified
     * ImageReader, or returns null if the specified ImageReader is not
     * registered.
     * 
     * @param reader
     *            the specified ImageReader.
     * @return the ImageWriter, or null.
     */
    public static ImageWriter getImageWriter(ImageReader reader) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets an ImageReader object which corresponds to the specified
     * ImageWriter, or returns null if the specified ImageWriter is not
     * registered.
     * 
     * @param writer
     *            the registered ImageWriter object.
     * @return the ImageReader.
     */
    public static ImageReader getImageReader(ImageWriter writer) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets the Iterator of ImageWriter objects which are able to encode images
     * with the specified ImageTypeSpecifier and format.
     * 
     * @param type
     *            the ImageTypeSpecifier, which defines layout.
     * @param formatName
     *            the format name.
     * @return the Iterator of ImageWriter objects.
     */
    public static Iterator<ImageWriter> getImageWriters(ImageTypeSpecifier type, String formatName) {
        if (type == null) {
            throw new NullPointerException("type cannot be NULL");
        }

        if (formatName == null) {
            throw new NullPointerException("format name cannot be NULL");
        }

        Iterator<ImageWriterSpi> it = registry.getServiceProviders(ImageWriterSpi.class,
                new FormatAndEncodeFilter(type, formatName), true);

        return new SpiIteratorToWritersIteratorWrapper(it);
    }

    /**
     * Gets the Iterator of registered ImageTranscoders which are able to
     * transcode the metadata of the specified ImageReader object to a suitable
     * object for encoding by the specified ImageWriter.
     * 
     * @param reader
     *            the specified ImageReader.
     * @param writer
     *            the specified ImageWriter.
     * @return the Iterator of registered ImageTranscoders.
     */
    public static Iterator<ImageTranscoder> getImageTranscoders(ImageReader reader,
            ImageWriter writer) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Reads image data from the specified File and decodes it using the
     * appropriate registered ImageReader object. The File is wrapped in an
     * ImageInputStream.
     * 
     * @param input
     *            the File to be read.
     * @return the BufferedImage decoded from the specified File, or null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static BufferedImage read(File input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input == null!");
        }

        ImageInputStream stream = createImageInputStream(input);
        return read(stream);
    }

    /**
     * Reads image data from the specified InputStream and decodes it using an
     * appropriate registered an ImageReader object.
     * 
     * @param input
     *            the InputStream.
     * @return the BufferedImage decoded from the specified InputStream, or
     *         null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static BufferedImage read(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input == null!");
        }

        ImageInputStream stream = createImageInputStream(input);
        return read(stream);
    }

    /**
     * Reads image data from the specified URL and decodes it using the
     * appropriate registered ImageReader object.
     * 
     * @param input
     *            the URL to be read.
     * @return the BufferedImage decoded from the specified URL, or null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static BufferedImage read(URL input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input == null!");
        }

        InputStream stream = input.openStream();
        BufferedImage res = read(stream);
        stream.close();

        return res;
    }

    /**
     * Reads image data from the specified ImageInputStream and decodes it using
     * appropriate registered an ImageReader object.
     * 
     * @param stream
     *            the ImageInputStream.
     * @return the BufferedImage decoded from the specified ImageInputStream, or
     *         null.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static BufferedImage read(ImageInputStream stream) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("stream == null!");
        }

        Iterator<ImageReader> imageReaders = getImageReaders(stream);
        if (!imageReaders.hasNext()) {
            return null;
        }

        ImageReader reader = imageReaders.next();
        reader.setInput(stream, false, true);
        BufferedImage res = reader.read(0);
        reader.dispose();

        try {
            stream.close();
        } catch (IOException e) {
            // Stream could be already closed, proceed silently in this case
        }

        return res;
    }

    /**
     * Writes the specified image in the specified format (using an appropriate
     * ImageWriter) to the specified ImageOutputStream.
     * 
     * @param im
     *            the RenderedImage.
     * @param formatName
     *            the format name.
     * @param output
     *            the ImageOutputStream where Image to be written.
     * @return true, if Image is written successfully, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static boolean write(RenderedImage im, String formatName, ImageOutputStream output)
            throws IOException {

        if (im == null) {
            throw new IllegalArgumentException("image cannot be NULL");
        }
        if (formatName == null) {
            throw new IllegalArgumentException("format name cannot be NULL");
        }
        if (output == null) {
            throw new IllegalArgumentException("output cannot be NULL");
        }

        Iterator<ImageWriter> it = getImageWriters(ImageTypeSpecifier.createFromRenderedImage(im),
                formatName);
        if (it.hasNext()) {
            ImageWriter writer = it.next();
            writer.setOutput(output);
            writer.write(im);
            output.flush();
            writer.dispose();
            return true;
        }
        return false;
    }

    /**
     * Writes the specified image in the specified format (using an appropriate
     * ImageWriter) to the specified File.
     * 
     * @param im
     *            the RenderedImage.
     * @param formatName
     *            the format name.
     * @param output
     *            the output File where Image to be written.
     * @return true, if Image is written successfully, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static boolean write(RenderedImage im, String formatName, File output)
            throws IOException {

        if (output == null) {
            throw new IllegalArgumentException("output cannot be NULL");
        }

        if (output.exists()) {
            output.delete();
        }

        ImageOutputStream ios = createImageOutputStream(output);
        boolean rt = write(im, formatName, ios);
        ios.close();
        return rt;
    }

    /**
     * Writes the specified image in the specified format (using an appropriate
     * ImageWriter) to the specified OutputStream.
     * 
     * @param im
     *            the RenderedImage.
     * @param formatName
     *            the format name.
     * @param output
     *            the OutputStream where Image is to be written.
     * @return true, if Image is written successfully, false otherwise.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public static boolean write(RenderedImage im, String formatName, OutputStream output)
            throws IOException {

        if (output == null) {
            throw new IllegalArgumentException("output cannot be NULL");
        }

        ImageOutputStream ios = createImageOutputStream(output);
        boolean rt = write(im, formatName, ios);
        ios.close();
        return rt;
    }

    /**
     * Filter to match spi by format name.
     */
    static class FormatFilter implements ServiceRegistry.Filter {

        /**
         * The name.
         */
        private String name;

        /**
         * Instantiates a new format filter.
         * 
         * @param name
         *            the name.
         */
        public FormatFilter(String name) {
            this.name = name;
        }

        public boolean filter(Object provider) {
            ImageReaderWriterSpi spi = (ImageReaderWriterSpi)provider;
            return Arrays.asList(spi.getFormatNames()).contains(name);
        }
    }

    /**
     * Filter to match spi by format name and encoding possibility.
     */
    static class FormatAndEncodeFilter extends FormatFilter {

        /**
         * The type.
         */
        private ImageTypeSpecifier type;

        /**
         * Instantiates a new format and encode filter.
         * 
         * @param type
         *            the type.
         * @param name
         *            the name.
         */
        public FormatAndEncodeFilter(ImageTypeSpecifier type, String name) {
            super(name);
            this.type = type;
        }

        @Override
        public boolean filter(Object provider) {
            ImageWriterSpi spi = (ImageWriterSpi)provider;
            return super.filter(provider) && spi.canEncodeImage(type);
        }
    }

    /**
     * Filter to match spi by suffix.
     */
    static class SuffixFilter implements ServiceRegistry.Filter {

        /**
         * The suf.
         */
        private String suf;

        /**
         * Instantiates a new suffix filter.
         * 
         * @param suf
         *            the suf.
         */
        public SuffixFilter(String suf) {
            this.suf = suf;
        }

        public boolean filter(Object provider) {
            ImageReaderWriterSpi spi = (ImageReaderWriterSpi)provider;
            return Arrays.asList(spi.getFileSuffixes()).contains(suf);
        }
    }

    /**
     * Filter to match spi by decoding possibility.
     */
    static class CanReadFilter implements ServiceRegistry.Filter {

        /**
         * The input.
         */
        private Object input;

        /**
         * Instantiates a new can read filter.
         * 
         * @param input
         *            the input.
         */
        public CanReadFilter(Object input) {
            this.input = input;
        }

        public boolean filter(Object provider) {
            ImageReaderSpi spi = (ImageReaderSpi)provider;
            try {
                return spi.canDecodeInput(input);
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * Wraps Spi's iterator to ImageWriter iterator.
     */
    static class SpiIteratorToWritersIteratorWrapper implements Iterator<ImageWriter> {

        /**
         * The backend.
         */
        private Iterator<ImageWriterSpi> backend;

        /**
         * Instantiates a new spi iterator to writers iterator wrapper.
         * 
         * @param backend
         *            the backend.
         */
        public SpiIteratorToWritersIteratorWrapper(Iterator<ImageWriterSpi> backend) {
            this.backend = backend;
        }

        /**
         * Next.
         * 
         * @return the image writer.
         */
        public ImageWriter next() {
            try {
                return backend.next().createWriterInstance();
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        /**
         * Checks for next.
         * 
         * @return true, if successful.
         */
        public boolean hasNext() {
            return backend.hasNext();
        }

        /**
         * Removes the.
         */
        public void remove() {
            throw new UnsupportedOperationException(
                    "Use deregisterServiceprovider instead of Iterator.remove()");
        }
    }

    /**
     * Wraps spi's iterator to ImageReader iterator.
     */
    static class SpiIteratorToReadersIteratorWrapper implements Iterator<ImageReader> {

        /**
         * The backend.
         */
        private Iterator<ImageReaderSpi> backend;

        /**
         * Instantiates a new spi iterator to readers iterator wrapper.
         * 
         * @param backend
         *            the backend.
         */
        public SpiIteratorToReadersIteratorWrapper(Iterator<ImageReaderSpi> backend) {
            this.backend = backend;
        }

        /**
         * Next.
         * 
         * @return the image reader.
         */
        public ImageReader next() {
            try {
                return backend.next().createReaderInstance();
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        /**
         * Checks for next.
         * 
         * @return true, if successful.
         */
        public boolean hasNext() {
            return backend.hasNext();
        }

        /**
         * Removes the.
         */
        public void remove() {
            throw new UnsupportedOperationException(
                    "Use deregisterServiceprovider instead of Iterator.remove()");
        }
    }
}
