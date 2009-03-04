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

import org.apache.harmony.x.imageio.plugins.jpeg.JPEGSpiConsts;

import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

public class PNGImageReaderSpi extends ImageReaderSpi {
    static final String PNG_NAMES[] = new String[] {"png", "PNG"};
    static final String PNG_SUFFIXES[] = new String[] {"png"};
    static final String PNG_MIME_TYPES[] = new String[] {"image/png"};
    static final String PNG_READER_CLASS_NAME = "org.apache.harmony.x.imageio.plugins.png.PNGImageReader";
    static final String PNG_READER_SPI_NAMES[] = {"org.apache.harmony.x.imageio.plugins.png.PNGImageReaderSpi"};

    public PNGImageReaderSpi() {
        super(
                JPEGSpiConsts.vendorName, JPEGSpiConsts.version,
                PNG_NAMES, PNG_SUFFIXES,
                PNG_MIME_TYPES, PNG_READER_CLASS_NAME,
                STANDARD_INPUT_TYPE, null,
                false, null,
                null, null,
                null, false, 
                null, null,
                null, null
        );
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        ImageInputStream markable = (ImageInputStream) source;
        markable.mark();

        byte[] signature = new byte[8];
        markable.seek(0);

        int nBytes = markable.read(signature, 0, 8);
        if(nBytes != 8) markable.read(signature, nBytes, 8-nBytes);
        markable.reset();

        // PNG signature: 137 80 78 71 13 10 26 10
        return  (signature[0] & 0xFF) == 137 &&
                (signature[1] & 0xFF) == 80 &&
                (signature[2] & 0xFF) == 78 &&
                (signature[3] & 0xFF) == 71 &&
                (signature[4] & 0xFF) == 13 &&
                (signature[5] & 0xFF) == 10 &&
                (signature[6] & 0xFF) == 26 &&
                (signature[7] & 0xFF) == 10;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) throws IOException {
        return new PNGImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "DRL PNG decoder";
    }

    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        super.onRegistration(registry, category);
    }
}
