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
 * @author Viskov Nikolay
 * @version $Revision$
 */
package org.apache.harmony.x.imageio.plugins.png;

import java.awt.image.ColorModel;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;

public class PNGImageWriterSpi extends ImageWriterSpi {

    public PNGImageWriterSpi() {
        super("Intel Corporation",// vendorName
                "1.0",// version
                new String[] {
                        "png", "PNG" },// names
                new String[] {
                        "png", "PNG" },// suffixes
                new String[] {
                    "image/png" },// MIMETypes
                "org.apache.harmony.x.imageio.plugins.png.PNGImageWriter",// writerClassName
                STANDARD_OUTPUT_TYPE,// outputTypes
                new String[] {
                    "org.apache.harmony.x.imageio.plugins.png.PNGImageWriterSpi" },// readerSpiNames
                false,// supportsStandardStreamMetadataFormat
                null,// nativeStreamMetadataFormatName
                null,// nativeStreamMetadataFormatClassName
                null,// extraStreamMetadataFormatNames
                null,// extraStreamMetadataFormatClassNames
                false,// supportsStandardImageMetadataFormat
                null,// nativeImageMetadataFormatName
                null,// nativeImageMetadataFormatClassName
                null,// extraImageMetadataFormatNames
                null// extraImageMetadataFormatClassNames
        );
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        boolean canEncode = true;

        int numBands = type.getSampleModel().getNumBands();

        ColorModel colorModel = type.getColorModel();

        int bitDepth = colorModel.getPixelSize() / numBands;

        if (colorModel instanceof IndexColorModel) {
            if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 && bitDepth != 8) {
                canEncode = false;
            }
            if (numBands != 1) {
                canEncode = false;
            }
        }
        else if (numBands == 1) {
            if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 && bitDepth != 8 && bitDepth != 16) {
                canEncode = false;
            }
        }
        else if (numBands == 2) {
            if (bitDepth != 8 && bitDepth != 16) {
                canEncode = false;
            }
        }
        else if (numBands == 3) {
            if (bitDepth != 8 && bitDepth != 16) {
                canEncode = false;
            }
        }
        else if (numBands == 4) {
            if (bitDepth != 8 && bitDepth != 16) {
                canEncode = false;
            }
        }

        return canEncode;
    }

    @Override
    public ImageWriter createWriterInstance(Object arg0) throws IOException {
        return new PNGImageWriter(this);
    }

    @Override
    public String getDescription(Locale arg0) {
        return "DRL PNG encoder";
    }

}
