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

import javax.imageio.stream.ImageOutputStream;
import java.io.IOException;
import java.io.File;

/**
 * The ImageOutputStreamSpi abstract class is a service provider interface (SPI)
 * for ImageOutputStreams.
 * 
 * @since Android 1.0
 */
public abstract class ImageOutputStreamSpi extends IIOServiceProvider implements
        RegisterableService {

    /**
     * The output class.
     */
    protected Class<?> outputClass;

    /**
     * Instantiates a new ImageOutputStreamSpi.
     */
    protected ImageOutputStreamSpi() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Instantiates a new ImageOutputStreamSpi.
     * 
     * @param vendorName
     *            the vendor name.
     * @param version
     *            the version.
     * @param outputClass
     *            the output class.
     */
    public ImageOutputStreamSpi(String vendorName, String version, Class<?> outputClass) {
        super(vendorName, version);
        this.outputClass = outputClass;
    }

    /**
     * Gets an output Class object that represents the class or interface that
     * must be implemented by an output source.
     * 
     * @return the output class.
     */
    public Class<?> getOutputClass() {
        return outputClass;
    }

    /**
     * Returns true if the ImageOutputStream can use a cache file. If this
     * method returns false, the value of the useCache parameter of
     * createOutputStreamInstance will be ignored. The default implementation
     * returns false.
     * 
     * @return true, if the ImageOutputStream can use a cache file, false
     *         otherwise.
     */
    public boolean canUseCacheFile() {
        return false; // def
    }

    /**
     * Returns true if the ImageOutputStream implementation requires the use of
     * a cache file. The default implementation returns false.
     * 
     * @return true, if the ImageOutputStream implementation requires the use of
     *         a cache file, false otherwise.
     */
    public boolean needsCacheFile() {
        return false; // def
    }

    /**
     * Creates the ImageOutputStream associated with this service provider. The
     * output object should be an instance of the class returned by
     * getOutputClass method. This method uses the default system directory for
     * the cache file, if it is needed.
     * 
     * @param output
     *            the output Object.
     * @return the ImageOutputStream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public ImageOutputStream createOutputStreamInstance(Object output) throws IOException {
        return createOutputStreamInstance(output, true, null);
    }

    /**
     * Creates the ImageOutputStream associated with this service provider. The
     * output object should be an instance of the class returned by
     * getInputClass method. This method uses the specified directory for the
     * cache file, if the useCache parameter is true.
     * 
     * @param output
     *            the output Object.
     * @param useCache
     *            the flag indicating if cache file is needed or not.
     * @param cacheDir
     *            the cache directory.
     * @return the ImageOutputStream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public abstract ImageOutputStream createOutputStreamInstance(Object output, boolean useCache,
            File cacheDir) throws IOException;
}
