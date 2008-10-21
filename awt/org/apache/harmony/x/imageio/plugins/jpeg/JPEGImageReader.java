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
 * @version $Revision: 1.4 $
 */
package org.apache.harmony.x.imageio.plugins.jpeg;


import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.plugins.jpeg.JPEGImageReadParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;

import org.apache.harmony.awt.gl.image.DecodingImageSource;
import org.apache.harmony.awt.gl.image.OffscreenImage;

import java.io.IOException;
import java.util.Iterator;
import java.awt.image.BufferedImage;

/**
 * This implementation uses org.apache.harmony.awt.gl.image.JpegDecoder to read
 * an image. The only implemented method is read(..);
 *
 * TODO: Implements generic decoder to be used by javad2 and imageio
 *
 * @see org.apache.harmony.awt.gl.image.JpegDecoder
 * @see org.apache.harmony.x.imageio.plugins.jpeg.IISDecodingImageSource
 */
public class JPEGImageReader extends ImageReader {

    ImageInputStream iis;

    public JPEGImageReader(ImageReaderSpi imageReaderSpi) {
        super(imageReaderSpi);
    }

    @Override
    public int getHeight(int i) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public int getWidth(int i) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public int getNumImages(boolean b) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int i) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public IIOMetadata getImageMetadata(int i) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public BufferedImage read(int i, ImageReadParam imageReadParam) throws IOException {
        if (iis == null) {
            throw new IllegalArgumentException("input stream == null");
        }

        DecodingImageSource source = new IISDecodingImageSource(iis);
        OffscreenImage image = new OffscreenImage(source);
        source.addConsumer(image);
        source.load();
        // The interrupted flag should be cleared because ImageDecoder interrupts
        // current thread while decoding. The same technique is used in
        // ImageLoader#run(). Another solution can be to create
        // a separate decoding thread. However, decoder keeps its own pool
        // of threads so creating a new thread will be just a waste of resources.
        Thread.interrupted();
        return image.getBufferedImage();
    }

    @Override
    public BufferedImage read(int i) throws IOException {
        return read(i, null);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        iis = (ImageInputStream) input;
    }

    @Override
    public ImageReadParam getDefaultReadParam() {
        return new JPEGImageReadParam();
    }
}
