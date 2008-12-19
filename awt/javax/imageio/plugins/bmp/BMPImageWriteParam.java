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

package javax.imageio.plugins.bmp;

import javax.imageio.ImageWriteParam;
import java.util.Locale;

/**
 * The BMPImageWriteParam class allows encoding an image in BMP format.
 * 
 * @since Android 1.0
 */
public class BMPImageWriteParam extends ImageWriteParam {

    /**
     * The top down.
     */
    private boolean topDown; // Default is bottom-up

    /**
     * Instantiates a new BMPImageWriteParam with default values of all
     * parameters.
     */
    public BMPImageWriteParam() {
        this(null);
    }

    /**
     * Instantiates a new BMPImageWriteParam with the specified Locale.
     * 
     * @param locale
     *            the specified Locale.
     */
    public BMPImageWriteParam(Locale locale) {
        super(locale);

        // Set the compression
        canWriteCompressed = true;
        compressionTypes = new String[] {
                "BI_RGB", "BI_RLE8", "BI_RLE4", "BI_BITFIELDS"
        };
        compressionType = compressionTypes[0];
    }

    /**
     * Sets true if the data will be written in a top-down order, false
     * otherwise.
     * 
     * @param topDown
     *            the new top-down value.
     */
    public void setTopDown(boolean topDown) {
        this.topDown = topDown;
    }

    /**
     * Returns true if the data is written in top-down order, false otherwise.
     * 
     * @return true if the data is written in top-down order, false otherwise.
     */
    public boolean isTopDown() {
        return topDown;
    }
}
