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


package org.apache.harmony.x.imageio.plugins.png;

import org.apache.harmony.awt.gl.image.DecodingImageSource;
import org.apache.harmony.awt.gl.image.OffscreenImage;
import org.apache.harmony.x.imageio.plugins.jpeg.IISDecodingImageSource;

import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageReadParam;
import javax.imageio.plugins.jpeg.JPEGImageReadParam;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadata;
import java.io.IOException;
import java.util.Iterator;
import java.awt.image.BufferedImage;

public class PNGImageReader  extends ImageReader {
    ImageInputStream iis;

    public PNGImageReader(ImageReaderSpi imageReaderSpi) {
        super(imageReaderSpi);
    }

    public int getNumImages(boolean allowSearch) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    public int getWidth(int imageIndex) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    public int getHeight(int imageIndex) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        //-- TODO imlement
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
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
        // current thread while decoding (due its architecture).
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
        return new ImageReadParam();
    }
}
