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

package javax.imageio.spi;

import java.io.File;
import java.io.IOException;
import javax.imageio.stream.ImageInputStream;

/**
 * The ImageInputStreamSpi abstract class is a service provider interface (SPI)
 * for ImageInputStreams.
 * 
 * @since Android 1.0
 */
public abstract class ImageInputStreamSpi extends IIOServiceProvider implements RegisterableService {

    /**
     * The input class.
     */
    protected Class<?> inputClass;

    /**
     * Instantiates a new ImageInputStreamSpi.
     */
    protected ImageInputStreamSpi() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Instantiates a new ImageInputStreamSpi.
     * 
     * @param vendorName
     *            the vendor name.
     * @param version
     *            the version.
     * @param inputClass
     *            the input class.
     */
    public ImageInputStreamSpi(String vendorName, String version, Class<?> inputClass) {
        super(vendorName, version);
        this.inputClass = inputClass;
    }

    /**
     * Gets an input Class object that represents class or interface that must
     * be implemented by an input source.
     * 
     * @return the input class.
     */
    public Class<?> getInputClass() {
        return inputClass;
    }

    /**
     * Returns true if the ImageInputStream can use a cache file. If this method
     * returns false, the value of the useCache parameter of
     * createInputStreamInstance will be ignored. The default implementation
     * returns false.
     * 
     * @return true, if the ImageInputStream can use a cache file, false
     *         otherwise.
     */
    public boolean canUseCacheFile() {
        return false; // -- def
    }

    /**
     * Returns true if the ImageInputStream implementation requires the use of a
     * cache file. The default implementation returns false.
     * 
     * @return true, if the ImageInputStream implementation requires the use of
     *         a cache file, false otherwise.
     */
    public boolean needsCacheFile() {
        return false; // def
    }

    /**
     * Creates the ImageInputStream associated with this service provider. The
     * input object should be an instance of the class returned by the
     * getInputClass method. This method uses the specified directory for the
     * cache file if the useCache parameter is true.
     * 
     * @param input
     *            the input Object.
     * @param useCache
     *            the flag indicating if a cache file is needed or not.
     * @param cacheDir
     *            the cache directory.
     * @return the ImageInputStream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract ImageInputStream createInputStreamInstance(Object input, boolean useCache,
            File cacheDir) throws IOException;

    /**
     * Creates the ImageInputStream associated with this service provider. The
     * input object should be an instance of the class returned by getInputClass
     * method. This method uses the default system directory for the cache file,
     * if it is needed.
     * 
     * @param input
     *            the input Object.
     * @return the ImageInputStream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public ImageInputStream createInputStreamInstance(Object input) throws IOException {
        return createInputStreamInstance(input, true, null);
    }
}
