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
package org.apache.harmony.x.imageio.plugins.jpeg;

import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;

public class JPEGImageReaderSpi extends ImageReaderSpi {

    public JPEGImageReaderSpi() {
        super(JPEGSpiConsts.vendorName, JPEGSpiConsts.version,
                JPEGSpiConsts.names, JPEGSpiConsts.suffixes,
                JPEGSpiConsts.MIMETypes, JPEGSpiConsts.readerClassName,
                STANDARD_INPUT_TYPE, JPEGSpiConsts.writerSpiNames,
                JPEGSpiConsts.supportsStandardStreamMetadataFormat,
                JPEGSpiConsts.nativeStreamMetadataFormatName,
                JPEGSpiConsts.nativeStreamMetadataFormatClassName,
                JPEGSpiConsts.extraStreamMetadataFormatNames,
                JPEGSpiConsts.extraStreamMetadataFormatClassNames,
                JPEGSpiConsts.supportsStandardImageMetadataFormat,
                JPEGSpiConsts.nativeImageMetadataFormatName,
                JPEGSpiConsts.nativeImageMetadataFormatClassName,
                JPEGSpiConsts.extraImageMetadataFormatNames,
                JPEGSpiConsts.extraImageMetadataFormatClassNames);
    }


    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        ImageInputStream markable = (ImageInputStream) source;
        try {
            markable.mark();

            byte[] signature = new byte[3];
            markable.seek(0);
            markable.read(signature, 0, 3);
            markable.reset();

            if ((signature[0] & 0xFF) == 0xFF &&
                    (signature[1] & 0xFF) == JPEGConsts.SOI &&
                    (signature[2] & 0xFF) == 0xFF) { // JPEG
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) throws IOException {
        return new JPEGImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "DRL JPEG decoder";
    }

    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        // super.onRegistration(registry, category);
    }
}
