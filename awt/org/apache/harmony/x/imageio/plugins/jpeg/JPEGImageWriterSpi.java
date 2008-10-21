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

import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.ImageWriter;
import javax.imageio.ImageTypeSpecifier;
import java.io.IOException;
import java.util.Locale;

public class JPEGImageWriterSpi extends ImageWriterSpi {

    public JPEGImageWriterSpi() {
        super(JPEGSpiConsts.vendorName, JPEGSpiConsts.version,
                JPEGSpiConsts.names, JPEGSpiConsts.suffixes, JPEGSpiConsts.MIMETypes,
                JPEGSpiConsts.writerClassName, STANDARD_OUTPUT_TYPE,
                JPEGSpiConsts.readerSpiNames, JPEGSpiConsts.supportsStandardStreamMetadataFormat /*TODO: support st. metadata format*/,
                JPEGSpiConsts.nativeStreamMetadataFormatName, JPEGSpiConsts.nativeStreamMetadataFormatClassName,
                JPEGSpiConsts.extraStreamMetadataFormatNames, JPEGSpiConsts.extraStreamMetadataFormatClassNames,
                JPEGSpiConsts.supportsStandardImageMetadataFormat, JPEGSpiConsts.nativeImageMetadataFormatName, JPEGSpiConsts.nativeImageMetadataFormatClassName,
                JPEGSpiConsts.extraImageMetadataFormatNames, JPEGSpiConsts.extraImageMetadataFormatClassNames);
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier imageTypeSpecifier) {
        return true;
    }

    @Override
    public ImageWriter createWriterInstance(Object o) throws IOException {
        return new JPEGImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "DRL JPEG Encoder";
    }
}
