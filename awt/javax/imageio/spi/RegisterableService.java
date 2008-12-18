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

/**
 * The RegisterableService interface provides service provider objects that can
 * be registered by a ServiceRegistry, and notifications that registration and
 * deregistration have been performed.
 * 
 * @since Android 1.0
 */
public interface RegisterableService {

    /**
     * This method is called when the object which implements this interface is
     * registered to the specified category of the specified registry.
     * 
     * @param registry
     *            the ServiceRegistry to be registered.
     * @param category
     *            the class representing a category.
     */
    void onRegistration(ServiceRegistry registry, Class<?> category);

    /**
     * This method is called when the object which implements this interface is
     * deregistered to the specified category of the specified registry.
     * 
     * @param registry
     *            the ServiceRegistry to be registered.
     * @param category
     *            the class representing a category.
     */
    void onDeregistration(ServiceRegistry registry, Class<?> category);
}
