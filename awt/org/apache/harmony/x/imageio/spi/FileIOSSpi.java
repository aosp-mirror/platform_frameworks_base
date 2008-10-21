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
 * @version $Revision: 1.2 $
 */
package org.apache.harmony.x.imageio.spi;

import javax.imageio.spi.ImageOutputStreamSpi;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.FileImageOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class FileIOSSpi extends ImageOutputStreamSpi {
    private static final String vendor = "Apache";

    private static final String ver = "0.1";

    public FileIOSSpi() {
        super(vendor, ver, File.class);
    }

    @Override
    public ImageOutputStream createOutputStreamInstance(Object output, boolean useCache,
            File cacheDir) throws IOException {
        if (output instanceof File) {
            return new FileImageOutputStream((File) output);
        }
        throw new IllegalArgumentException("output is not instance of File");
    }

    @Override
    public String getDescription(Locale locale) {
        return "File IOS Spi";
    }
}
