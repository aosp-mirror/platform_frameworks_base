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

import java.util.Locale;

/**
 * The IIOServiceProvider abstract class provides base functionality for ImageIO
 * service provider interfaces (SPIs).
 * 
 * @since Android 1.0
 */
public abstract class IIOServiceProvider implements RegisterableService {

    /**
     * The vendor name of this service provider.
     */
    protected String vendorName;

    /**
     * The version of this service provider.
     */
    protected String version;

    /**
     * Instantiates a new IIOServiceProvider.
     * 
     * @param vendorName
     *            the vendor name of service provider.
     * @param version
     *            the version of service provider.
     */
    public IIOServiceProvider(String vendorName, String version) {
        if (vendorName == null) {
            throw new NullPointerException("vendor name cannot be NULL");
        }
        if (version == null) {
            throw new NullPointerException("version name cannot be NULL");
        }
        this.vendorName = vendorName;
        this.version = version;
    }

    /**
     * Instantiates a new IIOServiceProvider.
     */
    public IIOServiceProvider() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        // the default impl. does nothing
    }

    public void onDeregistration(ServiceRegistry registry, Class<?> category) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    /**
     * Gets the vendor name of this service provider.
     * 
     * @return the vendor name of this service provider.
     */
    public String getVendorName() {
        return vendorName;
    }

    /**
     * Gets the version of this service provider.
     * 
     * @return the version of this service provider.
     */
    public String getVersion() {
        return version;
    }

    /**
     * Gets a description of this service provider. The result string should be
     * localized for the specified Locale.
     * 
     * @param locale
     *            the specified Locale.
     * @return the description of this service provider.
     */
    public abstract String getDescription(Locale locale);
}
